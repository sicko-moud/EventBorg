import json
from datetime import datetime, timedelta

from pipeline import push
from pipeline.config import TZ

NOW = datetime(2026, 12, 1, 12, 0, tzinfo=TZ)


def ev(i, c="music", src=("hus",), days=3, **kw):
    s = (NOW + timedelta(days=days)).isoformat(timespec="minutes")
    return {"id": f"e{i}", "t": f"Event {i}", "s": s, "c": c, "st": "ok", "v": {"n": "Sønderborghus"},
            "src": [{"id": x, "kind": "web"} for x in src], **kw}


def test_select_skips_first_ingest_far_future_and_non_ok():
    new = [ev(1), ev(2, src=("tm",)), ev(3, days=61), ev(4, st="cancelled"), ev(5, src=("tm", "hus"))]
    assert push.select(new, {"tm"}, {}, NOW) == ["e1", "e5"]


def test_backfill_source_is_silent_but_big_regular_source_is_not():
    catchup = [ev(i, src=("hus",)) for i in range(12)]
    assert push.select(catchup, set(), {"hus": 14}, NOW) == []
    festival = [ev(i, src=("sdk",)) for i in range(12)]
    assert len(push.select(festival, set(), {"sdk": 240}, NOW)) == 12
    corroborated = [*catchup, ev(99, src=("hus", "sdk"))]
    assert push.select(corroborated, set(), {"hus": 14, "sdk": 240}, NOW) == ["e99"]


def test_quiet_hours_queue_until_morning():
    night = NOW.replace(hour=23)
    assert push.schedule(["a"], ["b", "a"], night) == ([], ["a", "b"])
    assert push.schedule(["a"], ["b"], NOW.replace(hour=7, minute=59)) == ([], ["a", "b"])
    assert push.schedule(["a"], ["b"], NOW.replace(hour=8)) == (["a", "b"], [])


def test_condition_caps_at_five_topics():
    cond = push.condition(["all", "c_music", "s_a", "s_b", "s_c", "s_d", "s_a"])
    assert cond == "'all' in topics || 'c_music' in topics || 's_a' in topics || 's_b' in topics || 's_c' in topics"


def test_plan_groups_categories_over_three():
    events = [ev(i) for i in range(4)] + [ev(10, c="sports", src=("club",)), ev(11, c="sports", src=("club",))]
    msgs = push.plan(events)
    kinds = sorted((m["data"]["type"], m["data"]["c"]) for m in msgs)
    assert kinds == [("event", "sports"), ("event", "sports"), ("group", "music")]
    group = next(m for m in msgs if m["data"]["type"] == "group")
    assert group["data"]["n"] == "4" and group["android"]["collapse_key"] == "c_music"
    assert group["condition"] == "'all' in topics || 'c_music' in topics || 's_hus' in topics"
    assert all(isinstance(v, str) for m in msgs for v in m["data"].values())


def test_flood_groups_pairs():
    events = [ev(i, c=c) for i, c in enumerate(["music", "music", "sports", "sports", "food_drink", "food_drink",
                                                "markets", "markets", "community"])]
    msgs = push.plan(events)
    assert sorted(m["data"]["type"] for m in msgs) == ["event", "group", "group", "group", "group"]


def test_event_payload_fits_fcm_limit():
    big = ev(1, desc="x" * 1500, sum={"en": "é" * 200, "da": "ø" * 200}, tags=["t" * 100] * 30)
    data = push.event_data(big)
    assert len(json.dumps(data, ensure_ascii=False).encode()) < 4096
    ev_json = json.loads(data["ev"])
    assert "desc" not in ev_json and ev_json["id"] == "e1"
    many = push.group_data("music", [ev(i, t="T" * 200) for i in range(40)])
    assert len(json.dumps(many, ensure_ascii=False).encode()) < 4096 and many["n"] == "40"
