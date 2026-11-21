from datetime import timedelta, timezone

from ..config import HORIZON_DAYS, env
from . import Ctx, Skip

API = "https://app.ticketmaster.com/discovery/v2/events.json"
_B32 = "0123456789bcdefghjkmnpqrstuvwxyz"


def geohash(lat: float, lng: float, precision: int = 7) -> str:
    lat_r, lng_r, bits, out, even, ch, n = [-90.0, 90.0], [-180.0, 180.0], 0, [], True, 0, 0
    while len(out) < precision:
        r, v = (lng_r, lng) if even else (lat_r, lat)
        mid = (r[0] + r[1]) / 2
        ch <<= 1
        if v >= mid:
            ch |= 1
            r[0] = mid
        else:
            r[1] = mid
        even, n = not even, n + 1
        if n == 5:
            out.append(_B32[ch])
            ch, n = 0, 0
    return "".join(out)


def fetch(src: dict, ctx: Ctx) -> list[dict]:
    key = env("TICKETMASTER_KEY")
    if not key:
        raise Skip("disabled")
    now = ctx.now.astimezone(timezone.utc)
    params = {
        "apikey": key, "geoPoint": geohash(src["lat"], src["lng"]), "radius": src.get("radius", 25), "unit": "km",
        "size": 200, "sort": "date,asc", "locale": "*",
        "startDateTime": now.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "endDateTime": (now + timedelta(days=HORIZON_DAYS)).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    out = []
    for page in range(5):
        d = ctx.http.get(API, params={**params, "page": page}, robots=False).json()
        out += [parse(e) for e in (d.get("_embedded") or {}).get("events", [])]
        if page + 1 >= (d.get("page") or {}).get("totalPages", 1):
            break
    return out


def _image(images) -> str | None:
    best = sorted((i for i in images or [] if i.get("ratio") == "16_9"), key=lambda i: abs(i.get("width", 0) - 640))
    return best[0]["url"] if best else (images[0]["url"] if images else None)


def parse(e: dict) -> dict:
    st = (e.get("dates") or {}).get("start") or {}
    code = ((e.get("dates") or {}).get("status") or {}).get("code")
    timed = st.get("dateTime") and not st.get("timeTBA") and not st.get("noSpecificTime")
    ve = ((e.get("_embedded") or {}).get("venues") or [{}])[0]
    loc = ve.get("location") or {}
    cls = (e.get("classifications") or [{}])[0]
    cats = [x.get("name") for x in (cls.get("segment"), cls.get("genre")) if isinstance(x, dict) and x.get("name") != "Undefined"]
    pr = (e.get("priceRanges") or [None])[0]
    rate = 7.46 if pr and pr.get("currency") == "EUR" else 1.0
    return {
        "title": e.get("name"),
        "start": st["dateTime"] if timed else st.get("localDate"),
        "all_day": not timed,
        "venue": {
            "n": ve.get("name"), "addr": (ve.get("address") or {}).get("line1"), "town": (ve.get("city") or {}).get("name"),
            "zip": ve.get("postalCode"), "lat": loc.get("latitude"), "lng": loc.get("longitude"),
        },
        "cats": cats,
        "price": {"free": False, "min": round(pr["min"] * rate), "max": round(pr["max"] * rate)} if pr and pr.get("min") else None,
        "url": e.get("url"),
        "src_url": e.get("url"),
        "org": (e.get("promoter") or {}).get("name"),
        "img": _image(e.get("images")),
        "desc": e.get("info") or e.get("pleaseNote"),
        "status": {"cancelled": "cancelled", "rescheduled": "moved", "postponed": "moved"}.get(code),
    }
