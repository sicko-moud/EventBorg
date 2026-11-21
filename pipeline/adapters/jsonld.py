import json
import re
from datetime import datetime, timedelta
from urllib.parse import urljoin, urlsplit

from ..http import Blocked
from ..normalize import split_location
from ..text import clean_line
from . import Ctx

_LD = re.compile(r"<script[^>]+application/ld\+json[^>]*>(.*?)</script>", re.S | re.I)
_HREF = re.compile(r'href="([^"#]+)"', re.I)
DETAIL_TTL = timedelta(hours=24)


def fetch(src: dict, ctx: Ctx) -> list[dict]:
    pages = [src["url"], *src.get("pages", [])]
    out, links = [], []
    for p in pages:
        html = ctx.http.get(p).text
        out += from_html(html, p)
        if src.get("follow"):
            links += [u for u in find_links(html, p, src["follow"]) if u not in links]
    cache = ctx.state.setdefault("detail", {})
    fresh = {}
    for u in links[: src.get("max_follow", 80)]:
        c = cache.get(u)
        if c and ctx.now - datetime.fromisoformat(c["ts"]) < DETAIL_TTL:
            fresh[u] = c
            continue
        try:
            fresh[u] = {"ts": ctx.now.isoformat(), "ev": from_html(ctx.http.get(u).text, u)}
        except (Blocked, OSError, ValueError) as e:
            print(f"  skip {u}: {type(e).__name__}")
            if c:
                fresh[u] = c
    ctx.state["detail"] = fresh
    for c in fresh.values():
        out += c["ev"]
    seen, uniq = set(), []
    for e in out:
        k = (e["title"], e["start"])
        if k not in seen:
            seen.add(k)
            uniq.append(e)
    return uniq


def find_links(html: str, base: str, pattern: str) -> list[str]:
    host = urlsplit(base).netloc
    out = []
    for h in _HREF.findall(html):
        u = urljoin(base, h)
        if urlsplit(u).netloc == host and pattern in urlsplit(u).path and u.rstrip("/") != base.rstrip("/") and u not in out:
            out.append(u)
    return out


def _walk(node):
    if isinstance(node, list):
        for n in node:
            yield from _walk(n)
    elif isinstance(node, dict):
        t = node.get("@type")
        types = t if isinstance(t, list) else [t]
        if any(isinstance(x, str) and x.endswith("Event") for x in types):
            yield node
        for k in ("@graph", "subEvent", "itemListElement", "item"):
            if k in node:
                yield from _walk(node[k])


def _first(v):
    return v[0] if isinstance(v, list) and v else v


def _name(v) -> str | None:
    v = _first(v)
    if isinstance(v, dict):
        return v.get("name")
    return v if isinstance(v, str) and v else None


def _place(loc) -> tuple[dict, bool]:
    loc = _first(loc)
    if isinstance(loc, str):
        return split_location(loc), False
    if not isinstance(loc, dict):
        return {}, False
    online = "Virtual" in str(loc.get("@type", ""))
    v = {"n": loc.get("name")}
    a = loc.get("address")
    if isinstance(a, dict):
        v.update(addr=a.get("streetAddress"), zip=a.get("postalCode"), town=a.get("addressLocality"))
    elif isinstance(a, str):
        p = split_location(a)
        v.update(addr=a, zip=p.get("zip"), town=p.get("town"))
    g = loc.get("geo")
    if isinstance(g, dict):
        v.update(lat=g.get("latitude"), lng=g.get("longitude"))
    return v, online


def _price(offers):
    offers = offers if isinstance(offers, list) else [offers] if offers else []
    nums = []
    for o in offers:
        if not isinstance(o, dict):
            continue
        for k in ("price", "lowPrice", "highPrice"):
            try:
                n = float(str(o.get(k)).replace(",", "."))
            except ValueError:
                continue
            if n > 0:
                nums.append(n)
    nums = [int(n) if n.is_integer() else n for n in nums]
    return {"free": False, "min": min(nums), "max": max(nums)} if nums else None


def _offer_url(offers) -> str | None:
    o = _first(offers)
    return o.get("url") if isinstance(o, dict) else None


def _status(s) -> str | None:
    s = str(s or "")
    if "Cancelled" in s:
        return "cancelled"
    if any(x in s for x in ("Rescheduled", "Postponed", "MovedOnline")):
        return "moved"
    return None


def _cats(n: dict, keys) -> list[str]:
    out = []
    for k in keys:
        v = n.get(k)
        for x in v if isinstance(v, list) else [v]:
            if isinstance(x, str):
                out += [p.strip() for p in re.split(r"[|,]", x) if p.strip()]
    return list(dict.fromkeys(out))


def parse_event(n: dict, page: str, cats=()) -> dict | None:
    title, start = clean_line(n.get("name")), n.get("startDate")
    if not title or not start:
        return None
    venue, online = _place(n.get("location"))
    img = _first(n.get("image"))
    return {
        "title": title,
        "start": str(start),
        "end": str(n["endDate"]) if n.get("endDate") else None,
        "all_day": len(str(start)) <= 10,
        "venue": venue,
        "online": online or "Online" in str(n.get("eventAttendanceMode", "")) and "Mixed" not in str(n.get("eventAttendanceMode", "")),
        "price": _price(n.get("offers")),
        "url": _offer_url(n.get("offers")) or n.get("url") or page,
        "src_url": n.get("url") or page,
        "org": _name(n.get("organizer")),
        "img": img.get("url") if isinstance(img, dict) else img or None,
        "desc": n.get("description"),
        "status": _status(n.get("eventStatus")),
        "age": n.get("typicalAgeRange"),
        "lang": n.get("inLanguage") if isinstance(n.get("inLanguage"), str) else None,
        "cats": _cats(n, cats),
    }


def from_html(html: str, page: str, cats=()) -> list[dict]:
    out = []
    for block in _LD.findall(html):
        try:
            data = json.loads(block.strip(), strict=False)
        except ValueError:
            continue
        for n in _walk(data):
            e = parse_event(n, page, cats)
            if e:
                out.append(e)
    return out
