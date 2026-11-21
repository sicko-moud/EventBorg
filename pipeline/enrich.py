import json

from .config import CATEGORIES
from .llm import LLM, content_hash

PROMPT_VERSION = "s1"
BATCH = 15

PROMPT = """You write short summaries for an event listing app in Sønderborg, Denmark.
For each item return: k (copied), en (English) and da (Danish) summaries of at most 200 characters each,
neutral and factual, no emojis, no hype, no date, time, price or venue (the app shows those),
and c: the single best category from {cats}.
Items (JSON):
{items}"""

SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "items": {
            "type": "ARRAY",
            "items": {
                "type": "OBJECT",
                "properties": {"k": {"type": "STRING"}, "en": {"type": "STRING"}, "da": {"type": "STRING"},
                               "c": {"type": "STRING", "enum": CATEGORIES}},
                "required": ["k", "en", "da", "c"],
            },
        }
    },
    "required": ["items"],
}


def _key(e: dict) -> str:
    return PROMPT_VERSION + ":" + content_hash(e["t"], (e.get("desc") or "")[:1000])


def _apply(e: dict, r: dict) -> None:
    e["sum"] = {"en": r["en"][:200], "da": r["da"][:200]}
    if not e.get("c") and r.get("c") in CATEGORIES:
        e["c"] = r["c"]


def enrich(events: list[dict], llm: LLM) -> int:
    """Adds summaries and fallback categories; returns how many events are still pending."""
    todo = []
    for e in events:
        r = llm.cached(_key(e))
        if r:
            _apply(e, r)
        elif e.get("desc") or not e.get("c"):
            todo.append(e)
    todo.sort(key=lambda e: e["s"])
    for i in range(0, len(todo), BATCH):
        if not llm.available:
            break
        batch = todo[i:i + BATCH]
        items = [{"k": str(n), "title": e["t"], "text": (e.get("desc") or "")[:700]} for n, e in enumerate(batch)]
        res = llm.generate(PROMPT.format(cats=", ".join(CATEGORIES), items=json.dumps(items, ensure_ascii=False)), SCHEMA)
        for r in (res or {}).get("items", []):
            if r.get("k", "").isdigit() and int(r["k"]) < len(batch):
                e = batch[int(r["k"])]
                llm.store(_key(e), r)
                _apply(e, r)
    pending = 0
    for e in events:
        if not e.get("c"):
            e["c"] = "other"
        if "sum" not in e and (e.get("desc") or e["c"] == "other"):
            pending += 1
    return pending
