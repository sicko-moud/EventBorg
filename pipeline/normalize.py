import hashlib
import re
from datetime import date, datetime, time

from .config import TZ
from .geo import ZIP_TOWN, find_zip
from .text import clean_line, detect_lang, fold, html_to_text, normalize_title

_STATUS_PREFIX = re.compile(r"^\s*[\[(]?\s*(aflyst|cancelled|canceled|abgesagt|udsat|flyttet|postponed)\s*[\])!:–—-]*\s*", re.I)
_SOLD_OUT = re.compile(r"\b(udsolgt|sold out|ausverkauft)\b", re.I)
_FREE = re.compile(r"\b(gratis|fri entr[eé]|free|kostenlos|ingen entr[eé]|entr[eé]fri)\b|\b0\s*kr", re.I)
_NUM = re.compile(r"\d{1,3}(?:\.\d{3})+(?:,\d+)?|\d+(?:[.,]\d+)?")
_LOC_ZIP = re.compile(r"(\d{4})\s+([A-Za-zÆØÅæøåÄÖÜäöüß .\-]+)")


_EMAIL = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")
_PHONE = re.compile(r"(?:\+45\s?)?(?<![\d.,:/-])(?:\d{2} \d{2} \d{2} \d{2}|\d{4} \d{4}|\d{8})(?![\d,:/-]|\.\d)")


def scrub(text: str) -> str:
    """Removes e-mail addresses and phone numbers from free text."""
    return re.sub(r"[ \t]{2,}", " ", _PHONE.sub("", _EMAIL.sub("", text))).strip()


def split_location(s: str | None) -> dict:
    s = clean_line(s)
    if not s:
        return {}
    parts = [p.strip() for p in s.split(",") if p.strip()]
    v = {"n": parts[0], "addr": ", ".join(parts[1:]) or None}
    m = _LOC_ZIP.search(s)
    if m:
        v["zip"], v["town"] = m.group(1), m.group(2).strip()
        if len(parts) == 1:
            v["n"] = s[: m.start()].strip(" ,") or v["town"]
    return v


def parse_dt(value) -> tuple[datetime | None, bool]:
    """Returns (aware datetime in Europe/Copenhagen, date_only)."""
    if value is None or value == "":
        return None, False
    if isinstance(value, datetime):
        dt = value
    elif isinstance(value, date):
        return datetime.combine(value, time(0), TZ), True
    else:
        s = str(value).strip()
        if re.fullmatch(r"\d{4}-\d{2}-\d{2}", s):
            return datetime.combine(date.fromisoformat(s), time(0), TZ), True
        try:
            dt = datetime.fromisoformat(s.replace("Z", "+00:00"))
        except ValueError:
            return None, False
    return (dt.replace(tzinfo=TZ) if dt.tzinfo is None else dt.astimezone(TZ)), False


def iso(dt: datetime) -> str:
    return dt.isoformat(timespec="minutes")


def parse_price(p) -> dict | None:
    if p is None or p == "":
        return None
    if isinstance(p, dict):
        return {k: v for k, v in p.items() if v is not None} or None
    s = html_to_text(str(p)).lower()
    nums = []
    for m in _NUM.findall(s):
        n = float(m.replace(".", "").replace(",", ".") if re.search(r"\.\d{3}", m) else m.replace(",", "."))
        if 0 < n < 20000 and not (2000 <= n <= 2100):
            nums.append(n)
    rate = 7.46 if ("€" in s or "eur" in s) else 1.0
    nums = [round(n * rate) for n in nums]
    if nums:
        lo, hi = min(nums), max(nums)
        return {"free": False, "min": lo, **({"max": hi} if hi != lo else {})}
    if _FREE.search(s):
        return {"free": True}
    return None


def _clean_venue(v: dict | None, src: dict, aliases: dict) -> dict:
    v = {k: (clean_line(x) if isinstance(x, str) else x) for k, x in (v or {}).items()}
    v = {k: x for k, x in v.items() if x not in (None, "", [], 0)}
    if str(v.get("town", "")).isdigit() and v.get("zip") and not str(v["zip"]).isdigit():
        v["town"], v["zip"] = v["zip"], v["town"]
    if not v.get("n") and src.get("venue"):
        v["n"] = src["venue"]
    canon = aliases.get(fold(v.get("n")))
    if canon:
        v["n"] = canon["n"]
        for k in ("addr", "town"):
            if canon.get(k) and not v.get(k):
                v[k] = canon[k]
    town = v.get("town") or ""
    z = v.get("zip") or find_zip(v.get("addr"), town)
    if z:
        v["zip"] = z
    if (not town or town.isdigit() or re.match(r"^\d{4}\s", town)) and z in ZIP_TOWN:
        v["town"] = ZIP_TOWN[z]
    if not v.get("town") and src.get("town") and src.get("local"):
        v["town"] = src["town"]
    return v


def detect_status(title: str, raw_status: str | None) -> tuple[str, str]:
    st = raw_status or "ok"
    m = _STATUS_PREFIX.match(title)
    if m:
        word = m.group(1).lower()
        st = "cancelled" if word in ("aflyst", "cancelled", "canceled", "abgesagt") else "moved"
        title = title[m.end():].strip() or title
    return title, st


def make_id(title: str, start: datetime, venue: str | None) -> str:
    key = f"{normalize_title(title)}|{start.date().isoformat()}|{fold(venue)}"
    return hashlib.sha1(key.encode("utf-8")).hexdigest()[:12]


def to_event(raw: dict, src: dict, aliases: dict) -> dict | None:
    title = clean_line(raw.get("title"))
    start, date_only = parse_dt(raw.get("start"))
    if not title or start is None:
        return None
    end, _ = parse_dt(raw.get("end"))
    title, st = detect_status(title, raw.get("status"))
    all_day = bool(raw.get("all_day")) or date_only
    if all_day:
        start = start.replace(hour=0, minute=0)
    if end is not None and (end < start or (all_day and end.date() == start.date())):
        end = None
    v = _clean_venue(raw.get("venue"), src, aliases)
    desc = scrub(html_to_text(raw.get("desc")))[:1500] or None
    tags = [t for t in (clean_line(x) for x in raw.get("tags") or []) if t]
    if src.get("audience"):
        tags.append(src["audience"])
    if _SOLD_OUT.search(title + " " + str(raw.get("price") or "")):
        tags.append("sold_out")
    url = raw.get("url") or (None if raw.get("generic") else raw.get("src_url"))
    kind = "api" if src.get("type") == "ticketmaster" else "web"
    src_ref = {"id": src["id"], "kind": kind, "url": raw.get("src_url") or url}
    if raw.get("generic"):
        src_ref["_g"] = True
    e = {
        "id": make_id(title, start, v.get("n") or v.get("town")),
        "t": title[:200],
        "s": iso(start),
        "e": iso(end) if end else None,
        "ad": True if all_day else None,
        "v": {k: v[k] for k in ("n", "addr", "lat", "lng", "town") if v.get(k) not in (None, "")} or None,
        "tags": sorted(set(tags)) or None,
        "p": parse_price(raw.get("price")),
        "url": url,
        "src": [src_ref],
        "org": clean_line(raw.get("org")) or None,
        "img": raw.get("img") or None,
        "desc": desc,
        "lang": (raw.get("lang") or detect_lang(title + " " + (desc or "")) or None),
        "age": clean_line(raw.get("age")) or None,
        "st": st,
        "_cats": [c for c in raw.get("cats") or [] if c],
        "_zip": v.get("zip"),
        "_online": bool(raw.get("online")),
    }
    for k in ("lat", "lng"):
        if e["v"] and k in e["v"]:
            try:
                e["v"][k] = round(float(e["v"][k]), 5)
            except (TypeError, ValueError):
                del e["v"][k]
    return e
