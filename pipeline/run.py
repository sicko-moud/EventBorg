import os
import re
from datetime import datetime, timedelta
from pathlib import Path

import yaml

import json

from . import inbox, publish, push
from .adapters import Ctx, Skip, get_adapter
from .categorize import categorize
from .config import HORIZON_DAYS, ROOT, TZ
from .dedupe import dedupe, stabilize_ids
from .enrich import enrich
from .geo import in_municipality, is_online
from .http import Blocked, Http
from .llm import LLM
from .normalize import make_id, to_event
from .state import State
from .text import fold

REFRESH_HOURS = 3
_NON_EVENT = re.compile(r"abningstider|opening hours|\blukket\b|\bclosed\b|\bferielukket")


def load_config(path: Path = ROOT / "sources.yaml") -> tuple[list[dict], dict]:
    cfg = yaml.safe_load(path.read_text(encoding="utf-8"))
    aliases = {}
    for v in cfg.get("venues", []):
        for name in [v["n"], *v.get("aliases", [])]:
            aliases[fold(name)] = v
    return cfg["sources"], aliases


RETRY_NEXT_RUN = {"llm_unavailable", "llm_quota"}


def _due(st: dict, src: dict, now: datetime) -> bool:
    chk = st.get("chk")
    if not chk or st.get("st") in RETRY_NEXT_RUN:
        return True
    return now - datetime.fromisoformat(chk) >= timedelta(minutes=src.get("every", 60) - 2)


def keep(e: dict, src: dict, now: datetime) -> str | None:
    """Returns the reason an event is dropped, or None to keep it."""
    s = datetime.fromisoformat(e["s"])
    if e.get("e"):
        end = datetime.fromisoformat(e["e"])
    elif e.get("ad"):
        end = s + timedelta(days=1)
    else:
        end = s + timedelta(hours=3)
    if end < now:
        return "past"
    if s > now + timedelta(days=HORIZON_DAYS):
        return "horizon"
    v = {**(e.get("v") or {}), "zip": e.get("_zip")}
    if e.get("_online") or is_online(v):
        return "online"
    if _NON_EVENT.search(fold(e["t"])):
        return "non_event"
    inside = in_municipality(v)
    if inside is None:
        inside = bool(src.get("local"))
    return None if inside else "outside"


def fetch_source(src: dict, st: dict, state: State, ctx_args: tuple) -> bool:
    """Fetches one source into state.raw; returns True when this was its first successful ingest."""
    http, llm, now = ctx_args
    fn = get_adapter(src["type"])
    first = False
    try:
        raws = fn(src, Ctx(http, llm, now, st))
        state.raw[src["id"]] = raws
        st.update(st="ok", ok=now.isoformat(timespec="seconds"), err=None, fails=0)
        first = not st.get("ingested")
        st["ingested"] = True
    except Skip as e:
        st.update(st=e.code, err=None)
    except Blocked as e:
        st.update(st="robots", err=str(e))
    except Exception as e:  # noqa: BLE001 - one broken source must not stop the run
        st.update(st="error", err=f"{type(e).__name__}: {str(e)[:200]}", fails=st.get("fails", 0) + 1)
    st["chk"] = now.isoformat(timespec="seconds")
    return first


def resolve_id_collisions(events: list[dict]) -> None:
    """Two shows of the same title, day and venue get time-qualified IDs."""
    by_id: dict[str, list[dict]] = {}
    for e in events:
        by_id.setdefault(e["id"], []).append(e)
    for group in by_id.values():
        if len(group) > 1:
            for e in group:
                start = datetime.fromisoformat(e["s"])
                e["id"] = make_id(f"{e['t']} {start:%H%M}", start, (e.get("v") or {}).get("n"))


def run(out: Path, only: set[str] | None = None, force: bool = False, use_llm: bool = True) -> dict:
    now = datetime.now(TZ)
    sources, aliases = load_config()
    state = State(out)
    http, llm = Http(), LLM(state.llm, enabled=use_llm)
    src_types = {s["id"]: s["type"] for s in sources}
    report, events, first_ingest = [], [], set()
    for src in sources:
        st = state.source(src["id"])
        if src["type"] != "none" and (not only or src["id"] in only) and (force or _due(st, src, now)):
            print(f"fetch {src['id']} ({src['type']})")
            if fetch_source(src, st, state, (http, llm, now)):
                first_ingest.add(src["id"])
        raws = state.raw.get(src["id"], [])
        kept, drops = [], {}
        for r in raws:
            e = to_event(r, src, aliases)
            why = "invalid" if e is None else keep(e, src, now)
            if why:
                drops[why] = drops.get(why, 0) + 1
            else:
                kept.append(e)
        st["cnt"] = len(kept)
        events += kept
        report.append({"id": src["id"], "type": src["type"], "st": st.get("st", "-"), "raw": len(raws),
                       "kept": len(kept), "drops": drops, "err": st.get("err")})

    box_before = json.dumps(state.inbox, sort_keys=True)
    ib = inbox.step(state, llm, events, sources, aliases, now)
    for e in inbox.events_for_run(state.inbox):
        if not keep(e, {"local": True}, now):
            events.append(e)
    report.append({"id": "inbox", "type": "inbox", "st": "ok", "raw": ib["items"] + ib["posts"],
                   "kept": len(state.inbox.get("events", {})), "err": None,
                   "drops": {k: ib[k] for k in ("published", "review", "dropped", "retry") if ib[k]}})
    inbox_changed = json.dumps(state.inbox, sort_keys=True) != box_before or bool(state.meta.get("ack"))

    merged = dedupe(events, src_types)
    resolve_id_collisions(merged)
    stabilize_ids(merged, state.prev_events)
    categorize(merged)
    pending = enrich(merged, llm)
    llm.prune()

    new = []
    for e in merged:
        if e["id"] not in state.seen:
            state.seen[e["id"]] = now.date().isoformat()
            new.append(e)
    counts = {sid: st.get("cnt") or 0 for sid, st in state.sources.items()}
    pushable = push.select([e for e in new if not e.get("_quiet")], first_ingest, counts, now) if state.prev_events else []
    old_queue = state.meta.get("pushq", [])
    send, state.meta["pushq"] = push.schedule(old_queue, pushable, now)
    live = {e["id"] for e in merged}
    cutoff = (now - timedelta(days=HORIZON_DAYS + 30)).date().isoformat()
    state.seen = {k: v for k, v in state.seen.items() if k in live or v >= cutoff}

    pub_events = publish.public_events(merged)
    publish.stamp_updates(pub_events, state.prev_events, now.isoformat(timespec="seconds"))
    pub_sources = publish.public_sources(sources, state.sources)
    changed = publish.write(out, pub_events, pub_sources, state.prev_events, state.prev_sources, now)
    last = state.meta.get("pub")
    stale = not last or now - datetime.fromisoformat(last) >= timedelta(hours=REFRESH_HOURS)
    do_publish = changed or state.grew() or stale or bool(send) or state.meta["pushq"] != old_queue or inbox_changed
    if do_publish:
        state.meta["pub"] = now.isoformat(timespec="seconds")
    state.meta["push"] = send
    state.save()
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as f:
            f.write(f"publish={'true' if do_publish else 'false'}\npush={'true' if send else 'false'}\n"
                    f"ack={'true' if state.meta.get('ack') else 'false'}\n")
    return {"inbox_log": ib["log"], "report": report, "events": len(pub_events), "merged_from": len(events), "new": len(new),
            "pushable": len(pushable), "push_now": len(send), "queued": len(state.meta["pushq"]), "llm_calls": llm.calls, "llm_fallbacks": llm.fallbacks, "llm_pending": pending, "publish": do_publish,
            "per_source": {s["id"]: sum(1 for e in pub_events if any(x["id"] == s["id"] for x in e["src"])) for s in [*sources, {"id": "inbox"}]}}
