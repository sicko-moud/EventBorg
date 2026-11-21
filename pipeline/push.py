import base64
import json
import time
from collections import Counter
from datetime import datetime, timedelta
from pathlib import Path

import requests

from .config import ROOT, TZ, env
from .llm import _error_message
from .state import _load

PUSH_MAX_DAYS = 60
GROUP_OVER = 3
FLOOD_OVER = 8
BACKFILL_MIN = 10
BACKFILL_SHARE = 0.5
QUIET_FROM, QUIET_UNTIL = 22, 8
MAX_TOPICS = 5
DATA_LIMIT = 3800
TTL = "43200s"
PACKAGE = "app.eventborg"
SCOPE = "https://www.googleapis.com/auth/firebase.messaging"


def _src_ids(e: dict) -> list[str]:
    return list(dict.fromkeys(s["id"] for s in e.get("src") or []))


def backfill_sources(new: list[dict], counts: dict[str, int]) -> set[str]:
    """Sources whose new events this run look like a catch-up (e.g. after a truncated extraction), not news."""
    per = Counter(sid for e in new for sid in _src_ids(e))
    return {sid for sid, n in per.items() if n >= BACKFILL_MIN and n >= BACKFILL_SHARE * max(counts.get(sid) or n, n)}


def select(new: list[dict], first_ingest: set[str], counts: dict[str, int], now: datetime) -> list[str]:
    silent = first_ingest | backfill_sources(new, counts)
    horizon = now + timedelta(days=PUSH_MAX_DAYS)
    return [e["id"] for e in new
            if e.get("st", "ok") == "ok"
            and not all(sid in silent for sid in _src_ids(e))
            and datetime.fromisoformat(e["s"]) <= horizon]


def is_quiet(now: datetime) -> bool:
    return now.hour >= QUIET_FROM or now.hour < QUIET_UNTIL


def schedule(queue: list[str], pushable: list[str], now: datetime) -> tuple[list[str], list[str]]:
    """Returns (send now, keep queued): pushes found at night wait for the morning."""
    ids = list(dict.fromkeys(queue + pushable))
    return ([], ids) if is_quiet(now) else (ids, [])


def condition(topics: list[str]) -> str:
    return " || ".join(f"'{t}' in topics" for t in list(dict.fromkeys(topics))[:MAX_TOPICS])


def _json(x) -> str:
    return json.dumps(x, ensure_ascii=False, separators=(",", ":"))


def event_data(e: dict) -> dict:
    for drop in ((), ("sum",), ("sum", "tags", "org", "age")):
        ev = _json({k: v for k, v in e.items() if k not in ("desc", "upd", *drop)})
        if len(ev.encode()) <= DATA_LIMIT:
            break
    return {"type": "event", "id": e["id"], "c": e.get("c", "other"), "ev": ev}


def group_data(cat: str, events: list[dict]) -> dict:
    items = [{"id": e["id"], "t": e["t"], "s": e["s"], **({"v": {"n": e["v"]["n"]}} if (e.get("v") or {}).get("n") else {})}
             for e in events]
    while len(items) > 1 and len(_json(items).encode()) > DATA_LIMIT:
        items.pop()
    return {"type": "group", "c": cat, "n": str(len(events)), "evs": _json(items)}


def message(topics: list[str], data: dict, collapse: str | None = None) -> dict:
    android = {"priority": "HIGH", "ttl": TTL, "restricted_package_name": PACKAGE}
    if collapse:
        android["collapse_key"] = collapse
    return {"condition": condition(topics), "data": data, "android": android}


def plan(events: list[dict]) -> list[dict]:
    """One message per event, or one per category when a category has more than GROUP_OVER new events
    (or ≥2 when the whole run is a flood). Each device gets every event at most once."""
    events = sorted(events, key=lambda e: e["s"])
    by_cat: dict[str, list[dict]] = {}
    for e in events:
        by_cat.setdefault(e.get("c", "other"), []).append(e)
    flood = len(events) > FLOOD_OVER
    msgs = []
    for cat, evs in by_cat.items():
        if len(evs) > GROUP_OVER or (flood and len(evs) >= 2):
            srcs = [sid for sid, _ in Counter(sid for e in evs for sid in _src_ids(e)).most_common()]
            msgs.append(message(["all", f"c_{cat}", *(f"s_{s}" for s in srcs)], group_data(cat, evs), f"c_{cat}"))
        else:
            msgs += [message(["all", f"c_{cat}", *(f"s_{s}" for s in _src_ids(e))], event_data(e)) for e in evs]
    return msgs


def _b64(b: bytes) -> bytes:
    return base64.urlsafe_b64encode(b).rstrip(b"=")


class FCM:
    def __init__(self, sa: dict):
        self.sa = sa
        self.url = f"https://fcm.googleapis.com/v1/projects/{sa['project_id']}/messages:send"
        self.token: str | None = None
        self.last: str | None = None

    def _auth(self) -> str:
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import padding

        now = int(time.time())
        aud = self.sa.get("token_uri", "https://oauth2.googleapis.com/token")
        claims = {"iss": self.sa["client_email"], "scope": SCOPE, "aud": aud, "iat": now, "exp": now + 3600}
        signing = _b64(_json({"alg": "RS256", "typ": "JWT"}).encode()) + b"." + _b64(_json(claims).encode())
        key = serialization.load_pem_private_key(self.sa["private_key"].encode(), None)
        jwt = signing + b"." + _b64(key.sign(signing, padding.PKCS1v15(), hashes.SHA256()))
        r = requests.post(aud, data={"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": jwt.decode()},
                          timeout=20)
        r.raise_for_status()
        return r.json()["access_token"]

    def send(self, msg: dict) -> str | None:
        """Returns None on success, else the reason it failed."""
        self.token = self.token or self._auth()
        for attempt in range(3):
            r = requests.post(self.url, headers={"Authorization": f"Bearer {self.token}"}, json={"message": msg}, timeout=20)
            if r.status_code in (429, 500, 502, 503) and attempt < 2:
                try:
                    delay = min(int(r.headers.get("Retry-After", "")), 30)
                except ValueError:
                    delay = 2 * (attempt + 1)
                time.sleep(delay)
                continue
            if r.ok:
                self.last = r.json().get("name")
                return None
            return f"HTTP {r.status_code}: {_error_message(r)}"
        return "retries exhausted"


def service_account() -> dict | None:
    """FCM_SERVICE_ACCOUNT holds the JSON itself (CI secret) or, locally, a path to the file."""
    v = env("FCM_SERVICE_ACCOUNT")
    if not v:
        return None
    return json.loads(v if v.lstrip().startswith("{") else (ROOT / v).read_text(encoding="utf-8"))


def send_test(out: Path) -> dict:
    """Sends the soonest upcoming event to the `test` topic only, to check credentials and payload format."""
    sa = service_account()
    if not sa:
        return {"sent": 0, "errors": 1, "why": "FCM_SERVICE_ACCOUNT not set"}
    now = datetime.now(TZ)
    events = [e for e in _load(out / "events.json", {}).get("events", []) if datetime.fromisoformat(e["s"]) > now]
    e = min(events, key=lambda x: x["s"])
    fcm = FCM(sa)
    err = fcm.send(message(["test"], event_data(e)))
    if err:
        print(f"test push failed: {err}")
        return {"sent": 0, "errors": 1, "project": sa["project_id"]}
    print(f"test push ok: {fcm.last} ({e['t']})")
    return {"sent": 1, "errors": 0, "project": sa["project_id"]}


def send_pending(out: Path, dry_run: bool = False) -> dict:
    ids = _load(out / "state" / "meta.json", {}).get("push") or []
    events = {e["id"]: e for e in _load(out / "events.json", {}).get("events", [])}
    now = datetime.now(TZ)
    todo = [events[i] for i in ids
            if i in events and events[i].get("st", "ok") == "ok" and datetime.fromisoformat(events[i]["s"]) > now]
    msgs = plan(todo)
    sa = service_account()
    if dry_run or not sa:
        for m in msgs:
            d = m["data"]
            print(f"dry-run {d['type']:5} {d.get('id') or d['c'] + ' x' + d['n']:16} {len(_json(d).encode()):5}B  {m['condition']}")
        return {"events": len(todo), "messages": len(msgs), "sent": 0, "dry_run": True}
    fcm, sent, errors = FCM(sa), 0, []
    for m in msgs:
        err = fcm.send(m)
        if err:
            errors.append(err)
            print(f"push failed: {err}")
        else:
            sent += 1
    return {"events": len(todo), "messages": len(msgs), "sent": sent, "errors": len(errors)}
