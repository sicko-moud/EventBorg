"""Social inbox: items the Worker queued (app drafts, WhatsApp and, when enabled, Instagram DMs) and new Instagram posts
of listed sources become events. Auto-publish rules decide; everything else waits in a GitHub Issue for `approve`."""
import copy
import hashlib
import json
import re
from datetime import date, datetime, timedelta, timezone

import requests
import yaml

from . import enrich
from .config import HORIZON_DAYS, ROOT, TZ, env, repo_slug
from .dedupe import same_event
from .geo import in_municipality
from .llm import LLM, content_hash
from .normalize import scrub, to_event
from .text import detect_lang

SPEC = json.loads((ROOT / "shared" / "extract.json").read_text(encoding="utf-8"))
PV = "i1" + SPEC["version"]
CONF_MIN, CONF_HIGH, TRUST_AFTER = 0.8, 0.92, 3
BATCH = 10
MAX_MEDIA = 8_000_000
WORKER_DEFAULT = "https://eventborg.eventborg-worker.workers.dev"
POLL_MIN, POLL_SPREAD, POLL_PER_RUN, POSTS, POST_MAX_AGE = 90, 30, 6, 8, 21
PENDING_DAYS, DONE_DAYS = 30, 15
FIELDS = ["title", "start", "end", "venue", "address", "town", "price", "url", "organizer", "age", "category", "description"]
_MARKER = re.compile(r"<!-- eventborg:(\S+) (\S+) -->")
_YAML = re.compile(r"```yaml\n(.*?)\n```", re.S)


def _iso(dt: datetime) -> str:
    return dt.isoformat(timespec="seconds")


# --- auto-publish rules -------------------------------------------------------------------------------------

def decide(res: dict, e: dict | None, *, trusted: bool, corroborated: bool, inside: bool | None,
           now: datetime) -> tuple[str, str]:
    """('publish' | 'review' | 'drop', reason). Publish only when every rule holds; non-events and past events
    are dropped silently; anything else goes to a person."""
    if not res.get("is_event") or e is None:
        return "drop", "not an event"
    start = datetime.fromisoformat(e["s"])
    end = datetime.fromisoformat(e["e"]) if e.get("e") else start + timedelta(days=1 if e.get("ad") else 0, hours=0 if e.get("ad") else 3)
    if end < now:
        return "drop", "in the past"
    conf = float(res.get("confidence") or 0)
    why = []
    if conf < CONF_MIN:
        why.append(f"confidence {conf:.2f} < {CONF_MIN}")
    if start > now + timedelta(days=HORIZON_DAYS):
        why.append(f"starts more than {HORIZON_DAYS} days ahead")
    if inside is not True:
        why.append("outside the municipality" if inside is False else "location not confirmed inside the municipality")
    if not (trusted or corroborated or conf >= CONF_HIGH):
        why.append(f"new sender, not corroborated, confidence {conf:.2f} < {CONF_HIGH}")
    return ("review", "; ".join(why)) if why else ("publish", "auto")


def app_confidence(item: dict) -> float:
    """An app draft keeps the model's confidence only when the Worker verified its token and core fields weren't edited."""
    return float(item.get("conf") or 0) if item.get("orig") and item.get("edited") is False else 0.0


def fix_year(d: dict, today: date, haystack: str) -> dict:
    """Moves a year-less date the model put in the past to its next occurrence, unless the text names that year."""
    s = d.get("start") or ""
    floor = (today - timedelta(days=1)).isoformat()
    if len(s) < 10 or s[:10] >= floor or s[:4] in haystack:
        return d
    for n in (1, 2):
        shifted = str(int(s[:4]) + n) + s[4:]
        if shifted[:10] >= floor:
            end = d.get("end")
            return {**d, "start": shifted, **({"end": str(int(end[:4]) + n) + end[4:]} if end and end[:4].isdigit() else {})}
    return d


def corroborated(e: dict, known: list[dict], sender: str | None = None) -> bool:
    """A match with an event from another source, or with an inbox event another sender sent, corroborates."""
    mine = {(s["id"], s.get("kind")) for s in e["src"]}
    for k in known:
        if k["s"][:10] != e["s"][:10] or not same_event(e, k):
            continue
        if {(s["id"], s.get("kind")) for s in k.get("src") or []} - mine or k.get("_by") not in (None, sender):
            return True
    return False


# --- Worker + GitHub clients --------------------------------------------------------------------------------

class Worker:
    def __init__(self, url: str, token: str):
        self.url = url.rstrip("/")
        self.s = requests.Session()
        self.s.headers.update({"Authorization": f"Bearer {token}", "User-Agent": "EventBorg-inbox"})

    @classmethod
    def from_env(cls):
        tok = env("INBOX_TOKEN")
        return cls(env("WORKER_URL", WORKER_DEFAULT), tok) if tok else None

    def items(self) -> list[dict]:
        r = self.s.get(f"{self.url}/job/inbox", timeout=30)
        r.raise_for_status()
        return r.json().get("items", [])

    def media(self, key: str, i: int) -> tuple[str, bytes] | None:
        try:
            r = self.s.get(f"{self.url}/job/media", params={"k": key, "i": i}, timeout=40, stream=True)
            if not r.ok:
                return None
            data = r.raw.read(MAX_MEDIA + 1, decode_content=True)
        except requests.RequestException:
            return None
        return None if len(data) > MAX_MEDIA else (r.headers.get("content-type", "image/jpeg").split(";")[0], data)

    def ig(self, handle: str) -> tuple[int, dict, float]:
        r = self.s.get(f"{self.url}/job/ig", params={"u": handle, "n": POSTS}, timeout=40)
        try:
            body = r.json()
        except ValueError:
            body = {}
        return r.status_code, body, usage(r.headers)

    def done(self, acks: list[dict]) -> dict:
        tot = {"deleted": 0, "replied": 0, "errors": []}
        for i in range(0, len(acks), 20):
            r = self.s.post(f"{self.url}/job/done", json={"items": acks[i:i + 20]}, timeout=60)
            r.raise_for_status()
            res = r.json()
            tot["deleted"] += res.get("deleted", 0)
            tot["replied"] += res.get("replied", 0)
            tot["errors"] += res.get("errors", [])
        return tot


def usage(headers) -> float:
    """Highest percentage in Meta's X-App-Usage / X-Business-Use-Case-Usage headers."""
    vals = []
    for h in ("x-app-usage", "x-business-use-case-usage"):
        try:
            data = json.loads(headers.get(h) or "{}")
        except ValueError:
            continue
        rows = [data] if h == "x-app-usage" else [r for v in data.values() for r in v]
        vals += [float(v) for r in rows for k, v in r.items() if isinstance(v, (int, float)) and k != "estimated_time_to_regain_access"]
    return max(vals, default=0.0)


class GitHub:
    def __init__(self, token: str, repo: str):
        self.api = f"https://api.github.com/repos/{repo}"
        self.s = requests.Session()
        self.s.headers.update({"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
                               "X-GitHub-Api-Version": "2022-11-28"})

    @classmethod
    def from_env(cls):
        tok = env("GITHUB_TOKEN")
        return cls(tok, repo_slug()) if tok else None

    def issues(self, since: datetime) -> list[dict]:
        r = self.s.get(f"{self.api}/issues", params={"labels": "inbox", "state": "all", "per_page": 100,
                                                     "since": since.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}, timeout=30)
        r.raise_for_status()
        return [i for i in r.json() if "pull_request" not in i]

    def create(self, title: str, body: str, labels: tuple[str, ...] = ("inbox",)) -> int:
        r = self.s.post(f"{self.api}/issues", json={"title": title[:200], "body": body, "labels": list(labels)}, timeout=30)
        r.raise_for_status()
        return r.json()["number"]

    def close(self, n: int, comment: str, reason: str = "completed") -> None:
        self.s.post(f"{self.api}/issues/{n}/comments", json={"body": comment}, timeout=30)
        self.s.patch(f"{self.api}/issues/{n}", json={"state": "closed", "state_reason": reason}, timeout=30)


# --- issue body ---------------------------------------------------------------------------------------------

def issue_body(key: str, channel: str, d: dict, why: str, res: dict) -> str:
    fields = {k: d.get(k) for k in FIELDS}
    reason = scrub(str(res.get("reason") or ""))[:200]
    return (f"An item from **{channel}** needs a check: {why}.\n\n"
            f"Model: confidence {float(res.get('confidence') or 0):.2f}" + (f", \"{reason}\"" if reason else "") + ".\n\n"
            "Fix anything in the YAML below if needed, then add the label `approve` to publish it. "
            "Close the issue to reject it.\n\n"
            f"```yaml\n{yaml.safe_dump(fields, allow_unicode=True, sort_keys=False, width=1000).strip()}\n```\n\n"
            f"<!-- eventborg:{key} {channel} -->\n")


def suggestion_issue(s: dict) -> tuple[str, str]:
    """Title and body for a "Suggest a source" item; values sit in a code block so markdown and mentions stay inert."""
    def f(k: str, n: int) -> str:
        return scrub(str(s.get(k) or "")).replace("`", "'")[:n] or "-"
    name, url = f("name", 100), f("url", 300)
    title = "Source suggestion: " + re.sub(r"[@<>\[\]]", "", name if name != "-" else url)
    body = ("Someone suggested a source in the app.\n\n"
            f"```text\nName: {name}\nLink: {url}\nNote: {f('note', 500)}\n```\n\n"
            "Check robots.txt and the site's terms, then add it to sources.yaml (with ig/fb/names) or close this issue.\n")
    return title[:200], body


def parse_issue(body: str | None) -> tuple[str | None, str | None, dict | None]:
    """(key, channel, fields) from an inbox issue; fields reflect any edits made in the issue."""
    body = (body or "").replace("\r\n", "\n")
    m, y = _MARKER.search(body), _YAML.search(body)
    fields = None
    if y:
        try:
            data = yaml.safe_load(y.group(1))
            fields = {k: (str(v) if v is not None else None) for k, v in data.items() if k in FIELDS} if isinstance(data, dict) else None
        except yaml.YAMLError:
            fields = None
    return (m.group(1), m.group(2), fields) if m else (None, None, fields)


# --- replies ------------------------------------------------------------------------------------------------

_DA_DAYS = ["man", "tir", "ons", "tor", "fre", "lør", "søn"]
_DA_MON = ["jan", "feb", "mar", "apr", "maj", "jun", "jul", "aug", "sep", "okt", "nov", "dec"]
_EN_DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
_EN_MON = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]


def reply_text(e: dict | None, lang: str) -> str:
    da = lang == "da"
    if e is None:
        return "Tak! Vi tjekker det." if da else "Thanks, we're checking it."
    s = datetime.fromisoformat(e["s"])
    if da:
        when = f"{_DA_DAYS[s.weekday()]} {s.day}. {_DA_MON[s.month - 1]}" + ("" if e.get("ad") else f" kl. {s:%H.%M}")
    else:
        when = f"{_EN_DAYS[s.weekday()]} {s.day} {_EN_MON[s.month - 1]}" + ("" if e.get("ad") else f" {s:%H:%M}")
    link = f" {e['url']}" if e.get("url") else ""
    return f"{'Tilføjet' if da else 'Added'}: {e['t']}, {when}{link}"


# --- candidates ---------------------------------------------------------------------------------------------

def _local_day(ts) -> date:
    if isinstance(ts, (int, float)):
        return datetime.fromtimestamp(ts / 1000, TZ).date()
    return datetime.fromisoformat(str(ts).replace("Z", "+00:00").replace("+0000", "+00:00")).astimezone(TZ).date()


def _posted(day: date) -> str:
    return f"{day:%A} {day.isoformat()}"


def candidate_from_item(it: dict, sources: dict[str, dict] | None = None) -> dict:
    ch = it.get("channel") or "app"
    c = {"key": it["key"], "ch": ch, "text": (it.get("text") or "")[:4000], "media": it.get("media") or [],
         "day": _local_day(it.get("ts") or 0), "src": {"id": "inbox", "kind": ch}, "sender": it.get("sender"),
         "trusted": False, "local": False, "replyable": bool(it.get("replyable")), "mh": it.get("mh")}
    if ch == "app":
        d = it.get("draft") or {}
        c["res"] = {"is_event": True, "confidence": app_confidence(it), "reason": "app submission", **d}
        c["src"]["url"] = d.get("url")
    elif ch == "post":
        # A listed account's new post, caught from a phone's notification and extracted by the Worker. Any client can
        # claim to have seen one, so it is not trusted: it needs high confidence or corroboration like an app draft.
        src = (sources or {}).get(it.get("source") or "")
        d = it.get("draft") or {}
        conf = max(0.0, min(1.0, float(it.get("conf") or 0)))
        c["res"] = {"is_event": bool(src), "confidence": conf, "reason": "post notification", **d}
        if src:
            url = f"https://www.instagram.com/{src['ig']}/" if src.get("ig") else src.get("fb")
            c["src"] = {"id": src["id"], "kind": "ig_post" if src.get("ig") else "fb", "url": url}
            c["local"] = bool(src.get("local"))
    return c


def poll_due(sources: list[dict], box: dict, now: datetime) -> list[dict]:
    """Instagram accounts due for Business Discovery: each every 90–120 min (spread by id), oldest first."""
    wait = box.get("ig_wait")
    if wait and now < datetime.fromisoformat(wait):
        return []
    due = []
    for s in sources:
        st = box["ig"].get(s["id"], {})
        if not s.get("ig") or (st.get("off") and now < datetime.fromisoformat(st["off"])):
            continue
        spread = int(hashlib.sha1(s["id"].encode()).hexdigest(), 16) % POLL_SPREAD
        last = st.get("at")
        if not last or now - datetime.fromisoformat(last) >= timedelta(minutes=POLL_MIN + spread):
            due.append((last or "", s))
    return [s for _, s in sorted(due, key=lambda x: x[0])][:POLL_PER_RUN]


def poll_instagram(w: Worker, sources: list[dict], box: dict, now: datetime, log: list) -> list[dict]:
    out = []
    for s in poll_due(sources, box, now):
        status, body, used = w.ig(s["ig"])
        if status == 503:
            box["ig_wait"] = _iso(now + timedelta(hours=1))
            log.append("ig: Business Discovery not configured in the Worker")
            break
        st = box["ig"].setdefault(s["id"], {})
        st["at"] = _iso(now)
        if status != 200:
            err = body.get("error") or {}
            code = err.get("code")
            log.append(f"ig {s['id']}: HTTP {status} code {code}")
            if code in (4, 17, 32, 613) or status == 429:
                box["ig_wait"] = _iso(now + timedelta(hours=1))
                break
            if code in (100, 110):
                st["off"] = _iso(now + timedelta(days=7))
            continue
        first = "seen" not in st
        seen = set(st.get("seen") or [])
        for m in (body.get("business_discovery") or {}).get("media", {}).get("data", []):
            day = _local_day(m.get("timestamp") or _iso(now))
            if m.get("id") in seen or not m.get("caption") or (now.date() - day).days > POST_MAX_AGE:
                continue
            out.append({"key": f"ig:{m['id']}", "ch": "ig_post", "text": m["caption"][:2500], "media": [], "day": day,
                        "src": {"id": s["id"], "kind": "ig_post", "url": m.get("permalink")}, "sender": None,
                        "trusted": True, "local": bool(s.get("local")), "source": s, "quiet": first, "mid": m["id"]})
        st.setdefault("seen", [])
        if used >= 80:
            box["ig_wait"] = _iso(now + timedelta(minutes=30))
            break
    return out


# --- extraction ---------------------------------------------------------------------------------------------

def _batch_schema() -> dict:
    item = copy.deepcopy(SPEC["schema"])
    item["properties"] = {"k": {"type": "STRING"}, **item["properties"]}
    item["required"] = ["k", *item["required"]]
    item["propertyOrdering"] = ["k", *item.get("propertyOrdering", [])]
    return {"type": "OBJECT", "properties": {"items": {"type": "ARRAY", "items": item}}, "required": ["items"]}


BATCH_SCHEMA = _batch_schema()


def single_prompt(day: date, text: str) -> str:
    return f"{SPEC['intro'].replace('{today}', _posted(day))}\n\n{SPEC['rules']}\n\nSHARED ITEM:\n{text or '(see the image)'}"


def batch_prompt(rows: list[dict]) -> str:
    return f"{SPEC['intro_batch']}\n\n{SPEC['rules']}\n\nITEMS (JSON):\n{json.dumps(rows, ensure_ascii=False)}"


def extract(cands: list[dict], llm: LLM, w: Worker | None) -> None:
    """Fills c['res'] from the cache or Gemini. Media is downloaded to memory only, sent once, then dropped."""
    texts = []
    for c in cands:
        if "res" in c:
            continue
        images = [m for i in range(len(c["media"])) if w and (m := w.media(c["key"], i))]
        c["ck"] = PV + ":" + content_hash(c["day"].isoformat(), c["text"], *(hashlib.sha256(b).hexdigest() for _, b in images))
        hit = llm.cached(c["ck"])
        if hit is not None:
            c["res"] = hit
        elif images:
            if llm.available:
                res = llm.generate(single_prompt(c["day"], c["text"]), SPEC["schema"], images=images)
                if res is not None:
                    llm.store(c["ck"], res)
                    c["res"] = res
        elif c["text"].strip():
            texts.append(c)
        else:
            c["res"] = {"is_event": False, "confidence": 0, "reason": "empty"}
        del images
    for i in range(0, len(texts), BATCH):
        if not llm.available:
            break
        batch = texts[i:i + BATCH]
        rows = [{"k": str(n), "posted": _posted(c["day"]), "text": c["text"]} for n, c in enumerate(batch)]
        res = llm.generate(batch_prompt(rows), BATCH_SCHEMA)
        for r in (res or {}).get("items", []):
            k = str(r.pop("k", ""))
            if k.isdigit() and int(k) < len(batch) and "res" not in batch[int(k)]:
                batch[int(k)]["res"] = r
                llm.store(batch[int(k)]["ck"], r)


def to_candidate_event(d: dict, c: dict, aliases: dict) -> dict | None:
    d = fix_year(d, c["day"], c["text"])
    start = d.get("start") or ""
    src = c.get("source") or {"id": c["src"]["id"], "type": "inbox"}
    raw = {"title": d.get("title"), "start": start, "end": d.get("end"), "all_day": len(start) <= 10,
           "venue": {"n": d.get("venue"), "addr": d.get("address"), "town": d.get("town")}, "price": d.get("price"),
           "url": d.get("url"), "org": d.get("organizer"), "desc": d.get("description"), "age": d.get("age"),
           "lang": d.get("language"), "cats": [d["category"]] if d.get("category") else []}
    e = to_event(raw, src, aliases)
    if e is None:
        return None
    e["src"] = [{k: v for k, v in {**c["src"], "url": c["src"].get("url") or d.get("url")}.items() if v}]
    e["img"] = None
    if c.get("quiet"):
        e["_quiet"] = True
    return e


def _inside(e: dict, local: bool) -> bool | None:
    inside = in_municipality({**(e.get("v") or {}), "zip": e.get("_zip")})
    return True if inside is None and local else inside


def _seed_summary(e: dict, d: dict, llm: LLM) -> None:
    """Re-use the extraction's summaries so enrich doesn't spend another call on this event."""
    if d.get("summary_en") and d.get("summary_da"):
        llm.store(enrich._key(e), {"en": d["summary_en"][:200], "da": d["summary_da"][:200], "c": d.get("category") or "other"})


# --- the step -----------------------------------------------------------------------------------------------

def empty_box(box: dict) -> dict:
    for k in ("events", "pending", "done", "trust", "ig"):
        box.setdefault(k, {})
    return box


def forget(box: dict, senders: list[str]) -> None:
    for h in senders:
        box["trust"].pop(h, None)
        for rec in [*box["pending"].values(), *box["events"].values()]:
            for k in ("s", "_by"):
                if rec.get(k) == h:
                    rec[k] = None


def _publish(box: dict, e: dict, sender: str | None) -> None:
    """Stores the event; a sender's trust grows only with events we didn't have yet, so resends don't count."""
    if sender and e["id"] not in box["events"]:
        box["trust"][sender] = box["trust"].get(sender, 0) + 1
    e["_by"] = sender
    box["events"][e["id"]] = e


def moderate(gh: GitHub, box: dict, issues: list[dict], aliases: dict, llm: LLM, now: datetime, log: list) -> dict:
    """Publishes approved issues, forgets closed ones; returns {key: issue number} of every inbox issue seen."""
    listed = {}
    for iss in issues:
        key, ch, fields = parse_issue(iss.get("body"))
        if not key:
            continue
        listed[key] = iss["number"]
        p = box["pending"].get(key)
        if p is not None:
            p["issue"] = iss["number"]
        labels = {lb["name"] for lb in iss.get("labels", [])}
        if iss["state"] == "closed":
            box["pending"].pop(key, None)
            continue
        if "approve" not in labels:
            continue
        p = p or {"d": {}, "src": {"id": "inbox", "kind": ch}, "day": now.date().isoformat(), "s": None, "local": False}
        c = {"day": date.fromisoformat(p["day"]), "text": json.dumps(fields or p["d"]), "src": p["src"]}
        e = to_candidate_event(fields or p["d"], c, aliases)
        if e is None:
            gh.close(iss["number"], "Could not publish: title or start date is missing or invalid.", "not_planned")
        else:
            _publish(box, e, p.get("s"))
            _seed_summary(e, fields or p["d"], llm)
            gh.close(iss["number"], f"Published: {e['t']} ({e['s']}), id `{e['id']}`.")
            log.append(f"approved {e['id']}")
        box["pending"].pop(key, None)
    return listed


def step(state, llm: LLM, web_events: list[dict], sources: list[dict], aliases: dict, now: datetime) -> dict:
    box = empty_box(state.inbox)
    log, acks = [], []
    stats = {"items": 0, "published": 0, "review": 0, "dropped": 0, "retry": 0, "posts": 0}
    w, gh = Worker.from_env(), GitHub.from_env()
    today = now.date().isoformat()
    listed = {}
    if gh:
        try:
            listed = moderate(gh, box, gh.issues(now - timedelta(days=PENDING_DAYS)), aliases, llm, now, log)
        except requests.RequestException as ex:
            log.append(f"github: {type(ex).__name__}")
    cands = []
    if w:
        try:
            items = w.items()
        except requests.RequestException as ex:
            items = []
            log.append(f"worker: {type(ex).__name__}")
        stats["items"] = len(items)
        batch_mh = set()
        by_id = {s["id"]: s for s in sources}
        for it in items:
            mh = f"mh:{it['mh']}" if it.get("mh") else None
            if it["key"] in box["done"] or (mh and (mh in box["done"] or mh in batch_mh)):
                acks.append({"key": it["key"]})
            elif it.get("type") == "source":
                if gh:
                    try:
                        gh.create(*suggestion_issue(it.get("source") or {}), labels=("source",))
                        box["done"][it["key"]] = today
                        acks.append({"key": it["key"]})
                        log.append("source suggestion: issue")
                    except requests.RequestException as ex:
                        log.append(f"suggestion: {type(ex).__name__}")
            elif it.get("type") == "delete":
                forget(box, it.get("senders") or [])
                box["done"][it["key"]] = today
                acks.append({"key": it["key"]})
            else:
                batch_mh.add(mh)
                cands.append(candidate_from_item(it, by_id))
        try:
            cands += poll_instagram(w, sources, box, now, log)
        except requests.RequestException as ex:
            log.append(f"ig: {type(ex).__name__}")
    extract(cands, llm, w)

    known = web_events + state.prev_events + list(box["events"].values())
    for c in cands:
        res = c.get("res")
        if res is None:
            stats["retry"] += 1
            continue
        d = {k: res.get(k) for k in [*FIELDS, "language", "summary_en", "summary_da"]}
        e = to_candidate_event(d, c, aliases) if res.get("is_event") else None
        trusted = c["trusted"] or box["trust"].get(c.get("sender") or "", 0) >= TRUST_AFTER
        verdict, why = decide(res, e, trusted=trusted, corroborated=bool(e) and corroborated(e, known, c.get("sender")),
                              inside=_inside(e, c["local"]) if e else None, now=now)
        lang = "da" if detect_lang(c["text"]) == "da" or (e and e.get("lang") == "da") else "en"
        reply = None
        if verdict == "publish":
            _publish(box, e, c.get("sender"))
            _seed_summary(e, d, llm)
            known.append(e)
            reply = reply_text(e, lang)
            stats["published"] += 1
        elif verdict == "review":
            stats["review"] += 1
            reply = reply_text(None, lang)
            if c["key"] not in box["pending"]:
                box["pending"][c["key"]] = {"d": {k: v for k, v in d.items() if k in FIELDS and v}, "src": c["src"],
                                            "day": c["day"].isoformat(), "s": c.get("sender"), "local": c["local"],
                                            "at": _iso(now), "why": why, "conf": res.get("confidence"), "reason": res.get("reason"),
                                            "issue": listed.get(c["key"])}
        else:
            stats["dropped"] += 1
        if c["ch"] == "ig_post":
            stats["posts"] += 1
            st = box["ig"][c["src"]["id"]]
            st["seen"] = (st.get("seen") or [])[-59:] + [c["mid"]]
        else:
            box["done"][c["key"]] = today
            if c.get("mh"):
                box["done"][f"mh:{c['mh']}"] = today
            acks.append({"key": c["key"], **({"reply": reply} if reply and c["replyable"] else {})})
        log.append(f"{c['ch']} {verdict}: {why}" + (f" ({e['t'][:40]})" if e else ""))

    if gh:
        for key, p in box["pending"].items():
            if p.get("issue") is None:
                try:
                    p["issue"] = gh.create(f"Inbox: {p['d'].get('title', '?')} ({str(p['d'].get('start', ''))[:10]})",
                                           issue_body(key, p["src"]["kind"], p["d"], p["why"], {"confidence": p.get("conf"), "reason": p.get("reason")}))
                except requests.RequestException as ex:
                    log.append(f"issue: {type(ex).__name__}")
                    break
    _prune(box, now)
    state.meta["ack"] = acks
    stats["log"] = log
    return stats


def _prune(box: dict, now: datetime) -> None:
    for k, e in list(box["events"].items()):
        end = datetime.fromisoformat(e.get("e") or e["s"]) + timedelta(days=1)
        if end < now:
            del box["events"][k]
    cutoff = (now - timedelta(days=PENDING_DAYS)).isoformat()
    box["pending"] = {k: p for k, p in box["pending"].items() if p.get("at", "") >= cutoff}
    old = (now - timedelta(days=DONE_DAYS)).date().isoformat()
    box["done"] = {k: d for k, d in box["done"].items() if d >= old}


def events_for_run(box: dict) -> list[dict]:
    return [copy.deepcopy(e) for e in box.get("events", {}).values()]


def send_acks(out) -> dict:
    from .state import _load

    acks = _load(out / "state" / "meta.json", {}).get("ack") or []
    w = Worker.from_env()
    if not acks or not w:
        return {"acks": len(acks), "sent": 0}
    res = w.done(acks)
    return {"acks": len(acks), "deleted": res["deleted"], "replied": res["replied"], "errors": len(res["errors"]),
            **({"why": ",".join(sorted(set(res["errors"])))} if res["errors"] else {})}
