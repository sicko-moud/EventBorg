import time
import urllib.robotparser
from urllib.parse import urlsplit

import requests

from .config import env, repo_slug


class Blocked(Exception):
    pass


class Http:
    def __init__(self, min_interval: float = 1.0):
        contact = env("CONTACT_EMAIL")
        self.ua = f"EventBorgBot/1.0 (+https://github.com/{repo_slug()}{'; ' + contact if contact else ''})"
        self.s = requests.Session()
        self.s.headers.update({"User-Agent": self.ua, "Accept-Language": "da,en;q=0.8"})
        self.min_interval = min_interval
        self._last: dict[str, float] = {}
        self._robots: dict[str, urllib.robotparser.RobotFileParser] = {}
        self._unreachable: set[str] = set()

    def _wait(self, host: str) -> None:
        gap = time.monotonic() - self._last.get(host, 0.0)
        if gap < self.min_interval:
            time.sleep(self.min_interval - gap)
        self._last[host] = time.monotonic()

    def _request(self, url: str, **kw) -> requests.Response:
        self._wait(urlsplit(url).netloc)
        return self.s.get(url, timeout=25, **kw)

    def allowed(self, url: str) -> bool:
        p = urlsplit(url)
        rp = self._robots.get(p.netloc)
        if rp is None:
            rp = urllib.robotparser.RobotFileParser()
            try:
                r = self._request(f"{p.scheme}://{p.netloc}/robots.txt")
                if r.status_code >= 500:
                    rp.disallow_all = True
                elif r.status_code >= 400:
                    rp.allow_all = True
                else:
                    rp.parse(r.text.splitlines())
            except requests.RequestException:
                rp.disallow_all = True
                self._unreachable.add(p.netloc)
            self._robots[p.netloc] = rp
        return rp.can_fetch(self.ua, url)

    def get(self, url: str, *, params=None, headers=None, robots: bool = True) -> requests.Response:
        if robots and not self.allowed(url):
            why = "robots.txt unreachable" if urlsplit(url).netloc in self._unreachable else "disallowed by robots.txt"
            raise Blocked(why)
        for attempt in range(3):
            r = self._request(url, params=params, headers=headers)
            if r.status_code in (429, 502, 503, 504) and attempt < 2:
                try:
                    delay = min(int(r.headers.get("Retry-After", "")), 30)
                except ValueError:
                    delay = 3 * (attempt + 1)
                time.sleep(delay)
                continue
            if r.status_code != 304:
                r.raise_for_status()
            if "charset" not in r.headers.get("Content-Type", "").lower():
                r.encoding = "utf-8"
            return r
        return r

    def get_cached(self, url: str, cache: dict) -> tuple[str | None, bool]:
        """Conditional GET. Returns (text, changed); cache holds etag/last-modified per URL."""
        c = cache.get(url, {})
        h = {}
        if c.get("etag"):
            h["If-None-Match"] = c["etag"]
        if c.get("lm"):
            h["If-Modified-Since"] = c["lm"]
        r = self.get(url, headers=h)
        if r.status_code == 304:
            return None, False
        cache[url] = {k: v for k, v in {"etag": r.headers.get("ETag"), "lm": r.headers.get("Last-Modified")}.items() if v}
        return r.text, True
