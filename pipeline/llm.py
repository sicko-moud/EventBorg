import hashlib
import json
import time
from datetime import date

import requests

from .config import env

API = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"


def content_hash(*parts: str) -> str:
    return hashlib.sha256("\x1f".join(parts).encode("utf-8")).hexdigest()[:24]


class LLM:
    """Gemini via REST with JSON-schema output. Results are cached by content hash in state."""

    def __init__(self, cache: dict, enabled: bool = True):
        self.key = env("GEMINI_API_KEY") if enabled else None
        self.model = env("GEMINI_MODEL", "gemini-flash-latest")
        self.fallback = env("GEMINI_FALLBACK_MODEL", "gemini-flash-lite-latest")
        self.max_calls = int(env("LLM_MAX_CALLS", "25"))
        self.cache = cache
        self.calls = 0
        self.errors = 0
        self._last = 0.0
        self._primary_down = False

    @property
    def available(self) -> bool:
        return bool(self.key) and self.calls < self.max_calls

    def cached(self, key: str):
        hit = self.cache.get(key)
        if hit is not None:
            hit["u"] = date.today().isoformat()
            return hit["r"]
        return None

    def store(self, key: str, result) -> None:
        self.cache[key] = {"r": result, "u": date.today().isoformat()}

    def prune(self, max_age_days: int = 30) -> None:
        today = date.today()
        for k in [k for k, v in self.cache.items() if (today - date.fromisoformat(v["u"])).days > max_age_days]:
            del self.cache[k]

    def generate(self, prompt: str, schema: dict):
        gen = {"responseMimeType": "application/json", "responseSchema": schema, "temperature": 0.1,
               "maxOutputTokens": 32768, "thinkingConfig": {"thinkingLevel": env("GEMINI_THINKING", "low")}}
        body = {"contents": [{"role": "user", "parts": [{"text": prompt}]}], "generationConfig": gen}
        # A busy primary model is skipped for the rest of the run; the fallback model takes over.
        models = ([] if self._primary_down else [self.model]) + [m for m in [self.fallback] if m and m != self.model]
        for model in models:
            if not self.available:
                return None
            wait = 4.5 - (time.monotonic() - self._last)
            if wait > 0:
                time.sleep(wait)
            self._last = time.monotonic()
            self.calls += 1
            try:
                r = requests.post(API.format(model=model), headers={"x-goog-api-key": self.key}, json=body, timeout=90)
                if r.status_code == 429:
                    self.calls = self.max_calls
                    return None
                if r.status_code in (500, 503, 504):
                    print(f"  llm {model}: HTTP {r.status_code}, switching model")
                    self._primary_down = self._primary_down or model == self.model
                    continue
                if r.status_code == 400 and "thinking" in r.text.lower() and "thinkingConfig" in gen:
                    del gen["thinkingConfig"]
                    r = requests.post(API.format(model=model), headers={"x-goog-api-key": self.key}, json=body, timeout=90)
                r.raise_for_status()
                cands = r.json().get("candidates") or []
                parts = cands[0].get("content", {}).get("parts", []) if cands else []
                text = "".join(p.get("text", "") for p in parts if not p.get("thought"))
                try:
                    return json.loads(text) if text else None
                except ValueError:
                    reason = cands[0].get("finishReason") if cands else None
                    print(f"  llm {model}: bad JSON ({len(text)} chars, finish={reason}), switching model")
                    continue
            except requests.Timeout:
                print(f"  llm {model}: timeout, switching model")
                self._primary_down = self._primary_down or model == self.model
            except (requests.RequestException, ValueError) as e:
                self.errors += 1
                print(f"  llm error: {type(e).__name__}: {str(e)[:160]}")
                return None
        self.errors += 1
        return None
