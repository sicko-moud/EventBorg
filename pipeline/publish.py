from datetime import datetime
from pathlib import Path

from .state import dump

PUBLIC_SOURCE_KEYS = ("id", "name", "kind", "type", "url", "town", "ig", "fb", "names")


def _clean(x):
    if isinstance(x, dict):
        return {k: _clean(v) for k, v in x.items() if not k.startswith("_") and v not in (None, "", [], {})}
    if isinstance(x, list):
        return [_clean(v) for v in x]
    return x


def public_events(events: list[dict]) -> list[dict]:
    return sorted((_clean(e) for e in events), key=lambda e: (e["s"], e["t"]))


def public_sources(sources: list[dict], state: dict) -> list[dict]:
    out = []
    for s in sources:
        st = state.get(s["id"], {})
        row = {k: s[k] for k in PUBLIC_SOURCE_KEYS if s.get(k)}
        row["n"] = row.pop("name")
        if s.get("priority"):
            row["pri"] = True
        if s.get("watch"):
            row["dw"] = True
        row.update({"st": st.get("st", "new" if s["type"] != "none" else "social"), "ok": st.get("ok"), "cnt": st.get("cnt")})
        out.append(_clean(row))
    return out


def stamp_updates(events: list[dict], previous: list[dict], now: str) -> None:
    prev = {p["id"]: p for p in previous}
    for e in events:
        p = prev.get(e["id"])
        if p and {k: v for k, v in p.items() if k != "upd"} == {k: v for k, v in e.items() if k != "upd"}:
            e["upd"] = p.get("upd", now)
        else:
            e["upd"] = now


def write(out: Path, events: list[dict], sources: list[dict], prev_events: list, prev_sources: list, now: datetime) -> bool:
    strip_ok = lambda rows: [{k: v for k, v in r.items() if k != "ok"} for r in rows]
    changed = events != prev_events or strip_ok(sources) != strip_ok(prev_sources)
    gen = now.isoformat(timespec="seconds")
    dump(out / "events.json", {"v": 1, "gen": gen, "n": len(events), "events": events})
    dump(out / "sources.json", {"v": 1, "gen": gen, "sources": sources})
    (out / ".nojekyll").touch()
    return changed
