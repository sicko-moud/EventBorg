import re
from datetime import datetime, timedelta

import requests

from ..geo import in_municipality
from ..http import Blocked
from ..normalize import parse_dt
from . import Ctx
from .jsonld import from_html

# National calendars (IDA) publish every event page in a sitemap but offer no location filter.
# Each page is fetched once, only local upcoming events are kept, and other pages are remembered
# as empty so the next runs only fetch new URLs. The first fill is spread over runs by `budget`.
_LOC = re.compile(r"<loc>\s*([^<\s]+)\s*</loc>")
_NUM = re.compile(r"(\d+)/?$")
LOCAL_TTL = timedelta(hours=23)
OTHER_TTL = timedelta(days=30)


def pick(xml: str, src: dict) -> list[str]:
    """Event URLs from the sitemap, newest (highest trailing id) first."""
    skip = src.get("skip") or []
    urls = [u for u in dict.fromkeys(_LOC.findall(xml)) if src["match"] in u and not any(s in u for s in skip)]
    return sorted(urls, key=lambda u: -int(m.group(1)) if (m := _NUM.search(u)) else 0)


def _ended(e: dict, now: datetime) -> bool:
    end, _ = parse_dt(e.get("end") or e.get("start"))
    return end is not None and end < now


def is_local(e: dict, now: datetime) -> bool:
    return not e.get("online") and in_municipality(e.get("venue") or {}) is True and not _ended(e, now)


def _due(page: dict | None, now: datetime) -> bool:
    if page is None:
        return True
    return now - datetime.fromisoformat(page["ts"]) >= (LOCAL_TTL if page.get("ev") else OTHER_TTL)


def fetch(src: dict, ctx: Ctx) -> list[dict]:
    urls = pick(ctx.http.get(src["sitemap"]).text, src)
    if not urls:
        raise ValueError("no event URLs in sitemap")
    old = ctx.state.get("pages") or {}
    pages = {u: old[u] for u in urls if u in old}
    todo = [u for u in urls if _due(pages.get(u), ctx.now)]
    unseen = sum(1 for u in todo if u not in pages)
    budget = src.get("budget", 120)
    stamp = ctx.now.isoformat(timespec="minutes")
    for u in todo[:budget]:
        try:
            evs = from_html(ctx.http.get(u).text, u, src.get("cats") or ())
        except requests.HTTPError as e:
            if e.response is not None and 400 <= e.response.status_code < 500:
                pages[u] = {"ts": stamp}
            continue
        except (Blocked, OSError, ValueError) as e:
            print(f"  skip {u}: {type(e).__name__}")
            continue
        keep = [e for e in evs if is_local(e, ctx.now)]
        pages[u] = {"ts": stamp, **({"ev": keep} if keep else {})}
    ctx.state["pages"] = pages
    if not ctx.state.get("filled"):
        ctx.state["quiet"] = True
        ctx.state["filled"] = unseen <= budget
    return [e for p in pages.values() for e in p.get("ev", [])]
