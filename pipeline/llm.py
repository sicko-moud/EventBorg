import hashlib
import json
import time
from datetime import date

import requests

from .config import env

API = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"


def content_hash(*parts: str) -> str:
    return hashlib.sha256("\x1f".join(parts).encode("utf-8")).hexdigest()[:24]


def _error_message(r) -> str:
    try:
        msg = r.json()["error"]["message"]
    except (ValueError, KeyError, TypeError):
        msg = r.text
    return " ".join(str(msg).split())[:160]


class LLM:
    """Gemini via REST with JSON-schema output. Results are cached by content hash in state.

    Every call goes to the primary model first; only a call it fails on (5xx, timeout, quota, unusable reply)
    is retried once on the fallback model. A model that hits its quota is skipped for the rest of the run,
    and all calls share one wall-clock budget so slow replies can't push the CI job past its timeout."""

    def __init__(self, cache: dict, enabled: bool = True):
        self.key = env("GEMINI_API_KEY") if enabled else None
        self.models = [m for m in dict.fromkeys([env("GEMINI_MODEL", "gemini-flash-lite-latest"),
                                                  env("GEMINI_FALLBACK_MODEL", "gemini-flash-latest")]) if m]
        self.max_calls = int(env("LLM_MAX_CALLS", "25"))
        self.deadline = time.monotonic() + int(env("LLM_MAX_SECONDS", "480"))
        self.cache = cache
        self.calls = 0
        self.errors = 0
        self.fallbacks = 0
        self._last = 0.0
        self._exhausted: set[str] = set()

    @property
    def available(self) -> bool:
        return (bool(self.key) and self.calls < self.max_calls and time.monotonic() < self.deadline
                and any(m not in self._exhausted for m in self.models))

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

    def generate(self, prompt: str, schema: dict, check=None):
        """`check(result)` returns a reason when a reply looks incomplete. The fallback model's reply is then
        final; if the fallback is down, the primary gets one more try and the fuller of its two replies wins."""
        gen = {"responseMimeType": "application/json", "responseSchema": schema, "temperature": 0.1,
               "maxOutputTokens": 32768, "thinkingConfig": {"thinkingLevel": env("GEMINI_THINKING", "low")}}
        body = {"contents": [{"role": "user", "parts": [{"text": prompt}]}], "generationConfig": gen}
        todo = [m for m in self.models if m not in self._exhausted]
        partial = None
        for i, model in enumerate(todo):
            if not self.available:
                break
            if model != self.models[0]:
                self.fallbacks += 1
            final = i == len(todo) - 1
            result, why = self._call(model, body)
            if why is None and check and not final:
                why = check(result)
                partial = result if why else partial
            if why is None:
                return result
            print(f"  llm {model}: {why}{'' if final else ', trying fallback'}")
        primary = self.models[0]
        if partial is not None and self.available and primary not in self._exhausted:
            result, why = self._call(primary, body)
            print(f"  llm {primary}: second try {'failed: ' + why if why else 'done'}")
            if why is None:
                return max(partial, result, key=lambda r: len(json.dumps(r)))
        self.errors += 1
        return None

    def _call(self, model: str, body: dict) -> tuple[object, str | None]:
        """One request; returns (parsed JSON, None) or (None, failure reason)."""
        wait = 4.5 - (time.monotonic() - self._last)
        if wait > 0:
            time.sleep(wait)
        self._last = time.monotonic()
        self.calls += 1
        timeout = max(5.0, min(90.0, self.deadline - time.monotonic()))
        url, headers = API.format(model=model), {"x-goog-api-key": self.key}
        gen = body["generationConfig"]
        try:
            r = requests.post(url, headers=headers, json=body, timeout=timeout)
            if r.status_code == 400 and "thinking" in r.text.lower() and "thinkingConfig" in gen:
                body = {**body, "generationConfig": {k: v for k, v in gen.items() if k != "thinkingConfig"}}
                r = requests.post(url, headers=headers, json=body, timeout=timeout)
        except requests.Timeout:
            return None, "timeout"
        except requests.RequestException as e:
            return None, f"{type(e).__name__}: {str(e)[:160]}"
        if r.status_code == 429:
            self._exhausted.add(model)
            return None, "HTTP 429 (quota), skipped for the rest of this run"
        if r.status_code >= 400:
            return None, f"HTTP {r.status_code}: {_error_message(r)}"
        try:
            cands = r.json().get("candidates") or []
        except ValueError:
            return None, "non-JSON response"
        parts = cands[0].get("content", {}).get("parts", []) if cands else []
        text = "".join(p.get("text", "") for p in parts if not p.get("thought"))
        try:
            return json.loads(text), None
        except ValueError:
            reason = cands[0].get("finishReason") if cands else None
            return None, f"unusable reply ({len(text)} chars, finish={reason})"
