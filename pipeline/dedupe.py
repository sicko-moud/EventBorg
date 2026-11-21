from datetime import datetime
from difflib import SequenceMatcher
from urllib.parse import urlsplit

from .text import normalize_title

STRUCTURED = {"tribe": 0, "ical": 0, "jsonld": 0, "ticketmaster": 1, "html_llm": 2}
WINDOW_MIN = 90
THRESHOLD = 0.85


def similarity(a: str, b: str) -> float:
    na, nb = normalize_title(a), normalize_title(b)
    if not na or not nb:
        return 0.0
    if na == nb:
        return 1.0
    ta, tb = set(na.split()), set(nb.split())
    common = ta & tb
    # "Rikke Thomsen" vs "Rikke Thomsen – Zeitgeist Tour 2026": one title is the other plus extra words.
    if len(common) >= 2 and (ta <= tb or tb <= ta) and len(" ".join(common)) >= 8:
        return 1.0
    return SequenceMatcher(None, " ".join(sorted(ta)), " ".join(sorted(tb))).ratio()


_GENERIC = {"", "billetter", "tickets", "events", "event", "arrangementer", "kalender", "program", "da", "en", "de"}


def _specific(u: str | None) -> str | None:
    """URL that identifies one event; homepages and listing pages do not count."""
    if not u:
        return None
    p = urlsplit(u)
    path = p.path.strip("/").lower()
    if path.split("/")[-1] in _GENERIC and not p.query:
        return None
    return f"{p.netloc.lower().removeprefix('www.')}/{path}?{p.query}"


def _urls(e: dict) -> set[str]:
    cands = [e.get("url")] + [s.get("url") for s in e.get("src", []) if not s.get("_g")]
    return {u for u in map(_specific, cands) if u}


def same_event(a: dict, b: dict) -> bool:
    sa, sb = datetime.fromisoformat(a["s"]), datetime.fromisoformat(b["s"])
    if sa.date() != sb.date():
        return False
    if not (a.get("ad") or b.get("ad")) and abs((sa - sb).total_seconds()) > WINDOW_MIN * 60:
        return False
    if _urls(a) & _urls(b):
        return True
    return similarity(a["t"], b["t"]) >= THRESHOLD


SOCIAL = {"ig_dm", "ig_post", "wa", "fb", "app"}


def _rank(e: dict, src_types: dict) -> tuple:
    """Structured web data wins a merge, then LLM-read pages, then social/inbox items."""
    first = e["src"][0]
    t = "social" if first.get("kind") in SOCIAL else src_types.get(first["id"], "html_llm")
    filled = sum(1 for k in ("e", "v", "p", "img", "desc", "org") if e.get(k))
    return STRUCTURED.get(t, 3), -filled


def merge(group: list[dict], src_types: dict) -> dict:
    group = sorted(group, key=lambda e: _rank(e, src_types))
    out = {k: (dict(v) if isinstance(v, dict) else list(v) if isinstance(v, list) else v) for k, v in group[0].items()}
    for other in group[1:]:
        for k, v in other.items():
            if k == "src":
                have = {(s["id"], s.get("url")) for s in out["src"]}
                out["src"] += [s for s in v if (s["id"], s.get("url")) not in have]
            elif k in ("tags", "_cats"):
                out[k] = sorted(set(out.get(k) or []) | set(v or [])) or None
            elif k == "v" and v:
                out["v"] = {**v, **(out.get("v") or {})}
            elif k == "st" and v != "ok" and out.get("st") == "ok":
                out["st"] = v
            elif k in ("ad", "s", "e"):
                continue
            elif out.get(k) in (None, "", []) and v not in (None, "", []):
                out[k] = v
    timed = next((e for e in group if not e.get("ad")), None)
    if out.get("ad") and timed:
        out.update(s=timed["s"], e=timed.get("e"), ad=None)
    elif not out.get("e"):
        out["e"] = next((e["e"] for e in group if e.get("e") and not e.get("ad")), None)
    return out


def dedupe(events: list[dict], src_types: dict) -> list[dict]:
    by_day: dict[str, list[int]] = {}
    for i, e in enumerate(events):
        by_day.setdefault(e["s"][:10], []).append(i)
    parent = list(range(len(events)))

    def find(i: int) -> int:
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    for idx in by_day.values():
        timed = sorted((i for i in idx if not events[i].get("ad")), key=lambda i: events[i]["s"])
        dated = [i for i in idx if events[i].get("ad")]
        for group in (timed, dated):
            for x, i in enumerate(group):
                for j in group[x + 1:]:
                    if find(i) != find(j) and same_event(events[i], events[j]):
                        parent[find(j)] = find(i)
        # A date-only record joins at most one timed cluster, so it can't bridge two shows on one day.
        linked: set[int] = set()
        for i in dated:
            if find(i) in linked:
                continue
            for j in timed:
                if same_event(events[i], events[j]):
                    parent[find(i)] = find(j)
                    linked.add(find(i))
                    break
    groups: dict[int, list[dict]] = {}
    for i, e in enumerate(events):
        groups.setdefault(find(i), []).append(e)
    return [merge(g, src_types) if len(g) > 1 else g[0] for g in groups.values()]


def stabilize_ids(events: list[dict], previous: list[dict]) -> None:
    """Re-use last run's ID when an event matches one we already published."""
    prev_ids = {p["id"] for p in previous}
    by_day: dict[str, list[dict]] = {}
    for p in previous:
        by_day.setdefault(p["s"][:10], []).append(p)
    taken = set()
    for e in events:
        if e["id"] in prev_ids:
            taken.add(e["id"])
    for e in events:
        if e["id"] in prev_ids:
            continue
        for p in by_day.get(e["s"][:10], []):
            if p["id"] not in taken and same_event(e, p):
                e["id"] = p["id"]
                taken.add(p["id"])
                break
