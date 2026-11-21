from datetime import date, datetime, timedelta

from pipeline import inbox
from pipeline.config import TZ
from pipeline.inbox import (app_confidence, corroborated, decide, fix_year, issue_body, parse_issue, poll_due,
                            reply_text, to_candidate_event, usage)

NOW = datetime(2026, 11, 21, 12, 0, tzinfo=TZ)


def ev(s="2026-12-04T20:00:00+01:00", t="Jazz i Parken", src=None, **kw):
    return {"id": "x", "t": t, "s": s, "src": src or [{"id": "inbox", "kind": "wa"}], **kw}


def test_decide_publishes_only_when_every_rule_holds():
    e = ev()
    ok = {"is_event": True, "confidence": 0.85}
    assert decide(ok, e, trusted=True, corroborated=False, inside=True, now=NOW) == ("publish", "auto")
    assert decide(ok, e, trusted=False, corroborated=True, inside=True, now=NOW)[0] == "publish"
    assert decide({**ok, "confidence": 0.95}, e, trusted=False, corroborated=False, inside=True, now=NOW)[0] == "publish"
    v, why = decide(ok, e, trusted=False, corroborated=False, inside=True, now=NOW)
    assert v == "review" and "new sender" in why
    v, why = decide({**ok, "confidence": 0.7}, e, trusted=True, corroborated=True, inside=True, now=NOW)
    assert v == "review" and "confidence 0.70" in why
    assert "not confirmed" in decide(ok, e, trusted=True, corroborated=False, inside=None, now=NOW)[1]
    assert "outside" in decide(ok, e, trusted=True, corroborated=False, inside=False, now=NOW)[1]
    far = ev(s=(NOW + timedelta(days=121)).isoformat())
    assert "120 days" in decide(ok, far, trusted=True, corroborated=False, inside=True, now=NOW)[1]


def test_decide_drops_non_events_and_past_events_silently():
    assert decide({"is_event": False, "confidence": 0.99}, ev(), trusted=True, corroborated=True, inside=True, now=NOW)[0] == "drop"
    assert decide({"is_event": True, "confidence": 0.99}, None, trusted=True, corroborated=True, inside=True, now=NOW)[0] == "drop"
    past = ev(s="2026-11-21T08:00:00+01:00")
    assert decide({"is_event": True, "confidence": 0.99}, past, trusted=True, corroborated=True, inside=True, now=NOW) == ("drop", "in the past")
    running = ev(s="2026-11-21T10:00:00+01:00")
    assert decide({"is_event": True, "confidence": 0.99}, running, trusted=True, corroborated=True, inside=True, now=NOW)[0] == "publish"
    allday = ev(s="2026-11-21T00:00:00+01:00", ad=True)
    assert decide({"is_event": True, "confidence": 0.99}, allday, trusted=True, corroborated=True, inside=True, now=NOW)[0] == "publish"


def test_app_confidence_needs_verified_unedited_draft():
    assert app_confidence({"orig": {"title": "x"}, "edited": False, "conf": 0.9}) == 0.9
    assert app_confidence({"orig": {"title": "x"}, "edited": True, "conf": 0.9}) == 0.0
    assert app_confidence({"orig": None, "edited": None, "conf": None}) == 0.0
    assert app_confidence({"conf": 0.99}) == 0.0


def test_fix_year_uses_the_posting_date():
    d = {"title": "Jul", "start": "2025-12-13T16:00", "end": "2025-12-13T18:00"}
    assert fix_year(d, date(2026, 11, 21), "lørdag d. 13. december") == {"title": "Jul", "start": "2026-12-13T16:00", "end": "2026-12-13T18:00"}
    assert fix_year(d, date(2026, 11, 21), "13. december 2025")["start"] == "2025-12-13T16:00"
    assert fix_year({"start": "2026-01-05"}, date(2026, 11, 21), "")["start"] == "2027-01-05"
    assert fix_year({"start": "2026-11-20"}, date(2026, 11, 21), "")["start"] == "2026-11-20"
    assert fix_year({"start": "2026-11-02"}, date(2026, 11, 3), "")["start"] == "2026-11-02"


def test_corroboration_needs_another_source_or_another_sender():
    e = ev()
    web = ev(t="Jazz i Parken", src=[{"id": "sonderborg-dk", "kind": "web"}])
    assert corroborated(e, [web], "s1")
    other_day = ev(s="2026-12-05T20:00:00+01:00", src=[{"id": "sonderborg-dk", "kind": "web"}])
    assert not corroborated(e, [other_day], "s1")
    mine = ev(_by="s1")
    assert not corroborated(e, [mine], "s1")
    theirs = ev(_by="s2")
    assert corroborated(e, [theirs], "s1")
    assert not corroborated(e, [ev(t="Helt andet foredrag", src=[{"id": "biblioteket", "kind": "web"}])], "s1")


def test_issue_body_round_trip_keeps_edits_and_marker():
    d = {"title": "Loppemarked", "start": "2026-12-06T10:00", "venue": "Rådhustorvet", "town": "Sønderborg"}
    body = issue_body("in:1732:ab12", "wa", d, "new sender", {"confidence": 0.84, "reason": "Ring 12 34 56 78 for info"})
    assert "12 34 56 78" not in body
    key, ch, fields = parse_issue(body)
    assert (key, ch, fields["title"], fields["start"], fields["end"]) == ("in:1732:ab12", "wa", "Loppemarked", "2026-12-06T10:00", None)
    edited = body.replace("title: Loppemarked", "title: Julemarked på torvet").replace("\n", "\r\n")
    assert parse_issue(edited)[2]["title"] == "Julemarked på torvet"
    assert parse_issue("no marker here") == (None, None, None)


def test_candidate_event_has_no_image_and_keeps_channel_source():
    c = {"day": date(2026, 11, 21), "text": "Koncert lørdag", "src": {"id": "inbox", "kind": "tg"}}
    e = to_candidate_event({"title": "Koncert", "start": "2026-11-28T20:00", "venue": "Sønderborghus", "town": "Sønderborg",
                            "price": "Gratis", "category": "music"}, c, {})
    assert e["src"] == [{"id": "inbox", "kind": "tg"}]
    assert e["img"] is None and e["p"] == {"free": True} and e["s"].startswith("2026-11-28T20:00")
    assert to_candidate_event({"title": "Uden dato"}, c, {}) is None


def test_poll_due_spreads_and_caps_instagram_polling():
    sources = [{"id": f"s{i}", "ig": f"h{i}"} for i in range(10)] + [{"id": "noig"}]
    box = inbox.empty_box({})
    due = poll_due(sources, box, NOW)
    assert len(due) == inbox.POLL_PER_RUN and all(s.get("ig") for s in due)
    for s in sources[:10]:
        box["ig"][s["id"]] = {"at": (NOW - timedelta(minutes=80)).isoformat()}
    assert poll_due(sources, box, NOW) == []
    box["ig"]["s3"]["at"] = (NOW - timedelta(minutes=125)).isoformat()
    box["ig"]["s4"] = {"at": (NOW - timedelta(minutes=125)).isoformat(), "off": (NOW + timedelta(days=1)).isoformat()}
    assert [s["id"] for s in poll_due(sources, box, NOW)] == ["s3"]
    box["ig_wait"] = (NOW + timedelta(minutes=5)).isoformat()
    assert poll_due(sources, box, NOW) == []


def test_usage_reads_meta_rate_limit_headers():
    assert usage({"x-app-usage": '{"call_count": 12, "total_time": 81, "total_cputime": 3}'}) == 81
    assert usage({"x-business-use-case-usage": '{"123": [{"type": "x", "call_count": 40, "estimated_time_to_regain_access": 95}]}'}) == 40
    assert usage({}) == 0


def test_reply_text_in_danish_and_english():
    e = {"t": "Jazz i Parken", "s": "2026-12-05T20:00:00+01:00", "url": "https://x.dk/jazz"}
    assert reply_text(e, "da") == "Tilføjet: Jazz i Parken, lør 5. dec kl. 20.00 https://x.dk/jazz"
    assert reply_text({**e, "url": None, "ad": True}, "en") == "Added: Jazz i Parken, Sat 5 Dec"
    assert reply_text(None, "en") == "Thanks, we're checking it."


class FakeGitHub:
    def __init__(self):
        self.closed = []

    def close(self, n, comment, reason="completed"):
        self.closed.append((n, comment, reason))


class FakeLLM:
    def __init__(self):
        self.cache = {}

    def store(self, k, v):
        self.cache[k] = v


def test_moderation_publishes_edited_approvals_and_forgets_rejections():
    box = inbox.empty_box({})
    d = {"title": "Loppemarked", "start": "2026-12-06T10:00", "venue": "Rådhustorvet", "town": "Sønderborg"}
    for key in ("in:1:a", "in:2:b"):
        box["pending"][key] = {"d": d, "src": {"id": "inbox", "kind": "wa"}, "day": "2026-11-21", "s": "s1", "local": False,
                               "at": NOW.isoformat(), "why": "new sender"}
    approved = issue_body("in:1:a", "wa", d, "new sender", {"confidence": 0.8}).replace("title: Loppemarked", "title: Julemarked")
    issues = [{"number": 5, "state": "open", "labels": [{"name": "inbox"}, {"name": "approve"}], "body": approved},
              {"number": 6, "state": "closed", "labels": [{"name": "inbox"}], "body": issue_body("in:2:b", "wa", d, "x", {})},
              {"number": 7, "state": "open", "labels": [{"name": "inbox"}], "body": "unrelated"}]
    gh, log = FakeGitHub(), []
    listed = inbox.moderate(gh, box, issues, {}, FakeLLM(), NOW, log)
    assert listed == {"in:1:a": 5, "in:2:b": 6}
    assert box["pending"] == {}
    (e,) = box["events"].values()
    assert e["t"] == "Julemarked" and e["src"] == [{"id": "inbox", "kind": "wa"}] and e["_by"] == "s1"
    assert box["trust"] == {"s1": 1} and gh.closed[0][0] == 5
    inbox.forget(box, ["s1"])
    assert box["trust"] == {} and e["_by"] is None
