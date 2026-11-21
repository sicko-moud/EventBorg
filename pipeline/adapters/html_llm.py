import re
from urllib.parse import urljoin, urlsplit

from bs4 import BeautifulSoup

from ..llm import content_hash
from . import Ctx, Skip

PROMPT_VERSION = "x3"
MAX_CHARS = 14000
_LINK = re.compile(r"\[(https?://[^\]\s]+)\]")

PROMPT = """You extract public events from one web page of a venue or organizer in Sønderborg Municipality, Denmark.
Today is {today}. Page URL: {url}

Rules:
- Return only real public events with a specific date: concerts, shows, talks, workshops, markets, matches, guided tours, dated exhibitions.
- Skip opening hours, ads, news, menus, courses without a date, weekly recurring offers without a date, and events before today.
- start/end: local time (Europe/Copenhagen) as YYYY-MM-DDTHH:MM, or YYYY-MM-DD when no time is given. If the year is missing, use the next upcoming date.
- url: the event's own link if one appears in [brackets] next to it, else null.
- venue/address/town: only if stated on the page.
- price: the price text as written (e.g. "Gratis", "150 kr.", "100-250 kr"), else null.
- description: at most 200 characters copied or condensed from the page, original language. No personal names of private people, no phone numbers or e-mails.
- cancelled: true if the page says the event is cancelled (aflyst).

PAGE TEXT:
{text}"""

_S = {"type": "STRING", "nullable": True}
SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "events": {
            "type": "ARRAY",
            "items": {
                "type": "OBJECT",
                "properties": {
                    "title": {"type": "STRING"}, "start": {"type": "STRING"}, "end": _S, "venue": _S, "address": _S,
                    "town": _S, "price": _S, "url": _S, "description": _S, "organizer": _S, "age": _S,
                    "cancelled": {"type": "BOOLEAN", "nullable": True},
                },
                "required": ["title", "start"],
            },
        }
    },
    "required": ["events"],
}


def page_text(html: str, base: str) -> str:
    soup = BeautifulSoup(html, "html.parser")
    for t in soup(["script", "style", "noscript", "svg", "nav", "footer", "form", "iframe", "header", "template"]):
        t.decompose()
    root = soup.find("main") or soup.find(attrs={"role": "main"}) or soup.body or soup
    host = urlsplit(base).netloc
    for a in root.find_all("a", href=True):
        u = urljoin(base, a["href"])
        if u.startswith("http") and u.split("#")[0].rstrip("/") != base.rstrip("/") and a.get_text(strip=True):
            if urlsplit(u).netloc == host or any(x in u for x in ("billet", "ticket", "place2book")):
                a.append(f" [{u}]")
    lines = [re.sub(r"\s+", " ", ln).strip() for ln in root.get_text("\n").splitlines()]
    text = "\n".join(ln for ln in lines if ln)
    return text[:MAX_CHARS]


def short_reply(text: str, res) -> str | None:
    """Flags a reply that lists far fewer events than the page has event-like links (flash-lite sometimes stops after one)."""
    links = len(set(_LINK.findall(text)))
    n = len(res.get("events") or []) if isinstance(res, dict) else 0
    return f"only {n} events for {links} links" if links >= 10 and n * 4 < links else None


def fetch(src: dict, ctx: Ctx) -> list[dict]:
    urls = [src["url"], *src.get("pages", [])]
    out = []
    http_cache = ctx.state.setdefault("http", {})
    pages = ctx.state.setdefault("pages", {})
    if ctx.state.get("pv") != PROMPT_VERSION:
        pages.clear()  # re-extract every page once after a prompt/validation change
        ctx.state["pv"] = PROMPT_VERSION
    for url in urls:
        if url not in pages:
            http_cache.pop(url, None)
        html, changed = ctx.http.get_cached(url, http_cache)
        if not changed:
            out += pages[url]
            continue
        text = page_text(html, url)
        key = PROMPT_VERSION + ":" + content_hash(text)
        res = ctx.llm.cached(key)
        if res is None:
            if not ctx.llm.key or not ctx.llm.available:
                http_cache.pop(url, None)
                raise Skip("no_llm" if not ctx.llm.key else "llm_quota")
            res = ctx.llm.generate(PROMPT.format(today=ctx.now.date().isoformat(), url=url, text=text), SCHEMA,
                                   check=lambda r, t=text: short_reply(t, r))
            if res is None:
                http_cache.pop(url, None)
                raise Skip("llm_unavailable")
            ctx.llm.store(key, res)
        evs = [_map(e, url, src) for e in res.get("events", []) if e.get("title") and e.get("start")]
        pages[url] = evs
        out += evs
    return out


def _map(e: dict, page: str, src: dict) -> dict:
    return {
        "title": e["title"],
        "start": e["start"],
        "end": e.get("end"),
        "all_day": len(e["start"]) <= 10,
        "venue": {"n": e.get("venue"), "addr": e.get("address"), "town": e.get("town")},
        "price": e.get("price"),
        "url": e.get("url"),
        "src_url": e.get("url") or page,
        "generic": not e.get("url"),
        "org": e.get("organizer"),
        "desc": e.get("description"),
        "age": e.get("age"),
        "status": "cancelled" if e.get("cancelled") else None,
    }
