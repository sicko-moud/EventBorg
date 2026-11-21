from datetime import timedelta

import requests

from ..config import HORIZON_DAYS
from . import Ctx


def fetch(src: dict, ctx: Ctx) -> list[dict]:
    api = src["url"].rstrip("/") + "/wp-json/tribe/events/v1/events"
    params = {
        "per_page": 50,
        "status": "publish",
        "start_date": ctx.now.strftime("%Y-%m-%d 00:00:00"),
        "end_date": (ctx.now + timedelta(days=HORIZON_DAYS)).strftime("%Y-%m-%d 23:59:59"),
    }
    out = []
    for page in range(1, 41):
        try:
            d = ctx.http.get(api, params={**params, "page": page}).json()
        except requests.HTTPError as e:
            if e.response is not None and e.response.status_code == 404 and page == 1:
                return []
            raise
        out += [parse(e) for e in d.get("events", [])]
        if page >= int(d.get("total_pages") or 1):
            break
    return out


def _image(img) -> str | None:
    if not isinstance(img, dict):
        return None
    sizes = img.get("sizes") or {}
    for k in ("medium_large", "large", "medium"):
        if isinstance(sizes.get(k), dict) and sizes[k].get("url"):
            return sizes[k]["url"]
    return img.get("url")


def parse(e: dict) -> dict:
    ve = e.get("venue") if isinstance(e.get("venue"), dict) else {}
    orgs = [o.get("organizer") for o in (e.get("organizer") or []) if isinstance(o, dict)]
    values = (e.get("cost_details") or {}).get("values") or []
    return {
        "title": e.get("title"),
        "start": e.get("start_date"),
        "end": e.get("end_date"),
        "all_day": bool(e.get("all_day")),
        "venue": {
            "n": ve.get("venue"), "addr": ve.get("address"), "town": ve.get("city"), "zip": ve.get("zip"),
            "lat": ve.get("geo_lat"), "lng": ve.get("geo_lng"),
        },
        "cats": [c.get("name") for c in e.get("categories") or [] if isinstance(c, dict)],
        "tags": [t.get("name") for t in e.get("tags") or [] if isinstance(t, dict)],
        "price": e.get("cost") or (" - ".join(values) + " kr" if values else None),
        "url": e.get("website") or e.get("url"),
        "src_url": e.get("url"),
        "org": orgs[0] if orgs else None,
        "img": _image(e.get("image")),
        "desc": e.get("description") or e.get("excerpt"),
    }
