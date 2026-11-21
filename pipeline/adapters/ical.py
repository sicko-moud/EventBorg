from datetime import date, datetime, timedelta

from dateutil.rrule import rrulestr
from icalendar import Calendar

from ..config import HORIZON_DAYS, TZ
from ..normalize import split_location
from . import Ctx


def fetch(src: dict, ctx: Ctx) -> list[dict]:
    return parse(ctx.http.get(src["url"]).content, ctx.now)


def _aware(v):
    if isinstance(v, datetime):
        return v.replace(tzinfo=TZ) if v.tzinfo is None else v.astimezone(TZ)
    return v


def _iso(v) -> str:
    return v.isoformat()


def parse(data: bytes, now: datetime) -> list[dict]:
    out = []
    horizon = now + timedelta(days=HORIZON_DAYS)
    for ev in Calendar.from_ical(data).walk("VEVENT"):
        if "DTSTART" not in ev:
            continue
        start = _aware(ev.decoded("DTSTART"))
        end = _aware(ev.decoded("DTEND")) if "DTEND" in ev else None
        all_day = isinstance(start, date) and not isinstance(start, datetime)
        status = str(ev.get("STATUS", "")).upper()
        venue = split_location(str(ev.get("LOCATION", "")))
        geo = ev.get("GEO")
        if geo is not None:
            venue["lat"], venue["lng"] = geo.latitude, geo.longitude
        base = {
            "title": str(ev.get("SUMMARY", "")),
            "all_day": all_day,
            "venue": venue,
            "cats": _cats(ev) if "CATEGORIES" in ev else [],
            "url": str(ev.get("URL")) if ev.get("URL") else None,
            "desc": str(ev.get("DESCRIPTION", "")) or None,
            "status": "cancelled" if status == "CANCELLED" else None,
        }
        starts = [start]
        if "RRULE" in ev:
            first = start if isinstance(start, datetime) else datetime(start.year, start.month, start.day, tzinfo=TZ)
            rule = rrulestr(ev["RRULE"].to_ical().decode(), dtstart=first)
            starts = [d if isinstance(start, datetime) else d.date() for d in rule.between(now - timedelta(days=1), horizon, inc=True)[:30]]
        dur = (end - start) if end is not None else None
        for s in starts:
            out.append({**base, "start": _iso(s), "end": _iso(s + dur) if dur else None})
    return out


def _cats(ev) -> list[str]:
    raw = ev.get("CATEGORIES")
    items = raw if isinstance(raw, list) else [raw]
    return [str(c) for item in items for c in getattr(item, "cats", [item])]
