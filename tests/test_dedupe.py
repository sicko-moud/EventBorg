from pipeline.dedupe import dedupe, same_event, similarity, stabilize_ids
from pipeline.text import normalize_title

TYPES = {"sdk": "tribe", "hus": "html_llm", "tm": "ticketmaster"}


def ev(t, s, src="sdk", url=None, **kw):
    return {"id": f"{src}-{t}-{s}", "t": t, "s": s, "url": url, "src": [{"id": src, "kind": "web", "url": url}], "st": "ok", **kw}


def test_normalize_title_is_case_space_and_punctuation_insensitive():
    assert normalize_title("Rikke Thomsen – Zeitgeist Tour 2026") == normalize_title("RIKKE THOMSEN: Zeitgeist tour 2026")
    assert normalize_title("  Emil  Hess Quartet ") == normalize_title("Emil Hess Quartet")
    assert normalize_title("Julekoncert") != normalize_title("Nytårskoncert")


def test_similarity():
    assert similarity("The Blues Bones (BE) // Sønderborghus", "The Blues Bones") >= 0.85
    assert similarity("Heino fixer en fest – en del af Rytmeræs", "Heino fixer en fest") >= 0.85
    assert similarity("Julekoncert", "Nytårskoncert") < 0.85
    assert similarity("Fortælleteater for børn", "Fortælleteater for voksne") < 0.85


def test_same_event_rules():
    a = ev("Emil Hess Quartet", "2026-12-04T20:00+01:00")
    assert same_event(a, ev("Emil Hess Quartet // Sønderborghus", "2026-12-04T21:00+01:00", "hus"))
    assert not same_event(a, ev("Emil Hess Quartet", "2026-12-04T22:00+01:00", "hus"))
    assert not same_event(a, ev("Emil Hess Quartet", "2026-12-05T20:00+01:00", "hus"))
    assert same_event(a, ev("Emil Hess Quartet", "2026-12-04T00:00+01:00", "hus", ad=True))
    same_url = "https://sonderborghus.dk/events/emil-hess/"
    assert same_event(ev("Jazz", "2026-12-04T20:00+01:00", url=same_url), ev("Emil Hess 4tet", "2026-12-04T20:30+01:00", "hus", url=same_url))
    home = "https://sonderborghus.dk/"
    assert not same_event(ev("Jazz", "2026-12-04T20:00+01:00", url=home), ev("Rock", "2026-12-04T20:00+01:00", "hus", url=home))


def test_dedupe_merges_and_prefers_structured():
    events = [
        ev("Rikke Thomsen – Zeitgeist Tour 2026", "2027-01-15T20:00+01:00", "hus", desc="Lang tekst", tags=["a"]),
        ev("Rikke Thomsen: Zeitgeist tour 2026", "2027-01-15T20:00+01:00", "sdk", img="i.jpg", tags=["b"]),
        ev("Rikke Thomsen", "2027-01-16T20:00+01:00", "sdk"),
    ]
    out = dedupe(events, TYPES)
    assert len(out) == 2
    m = next(e for e in out if len(e["src"]) == 2)
    assert m["t"] == "Rikke Thomsen: Zeitgeist tour 2026" and m["img"] == "i.jpg" and m["desc"] == "Lang tekst"
    assert [s["id"] for s in m["src"]] == ["sdk", "hus"] and m["tags"] == ["a", "b"]


def test_date_only_record_does_not_bridge_two_shows():
    TYPES2 = {**TYPES, "sym": "jsonld"}
    events = [
        ev("TV 2 Alletiders Juleshow", "2026-11-24T17:30+01:00", e="2026-11-24T19:00+01:00"),
        ev("TV 2 Alletiders Juleshow", "2026-11-24T20:30+01:00"),
        ev("TV 2 Alletiders Juleshow", "2026-11-24T00:00+01:00", "sym", ad=True, img="sym.jpg"),
    ]
    out = sorted(dedupe(events, TYPES2), key=lambda e: e["s"])
    assert [e["s"][11:16] for e in out] == ["17:30", "20:30"]
    assert not any(e.get("ad") for e in out) and out[0]["e"] == "2026-11-24T19:00+01:00"
    assert sum(len(e["src"]) for e in out) == 3


def test_dedupe_keeps_cancelled_status_from_any_source():
    out = dedupe([ev("Julekoncert", "2026-12-20T16:00+01:00"), ev("Julekoncert", "2026-12-20T16:00+01:00", "hus", st="cancelled")], TYPES)
    assert len(out) == 1 and out[0]["st"] == "cancelled"


def test_stabilize_ids_reuses_previous():
    prev = [ev("Emil Hess Quartet", "2026-12-04T20:00+01:00") | {"id": "old1"}]
    cur = [ev("Emil Hess Quartet // Sønderborghus", "2026-12-04T20:00+01:00", "hus") | {"id": "new1"},
           ev("Something else", "2026-12-04T20:00+01:00", "hus") | {"id": "new2"}]
    stabilize_ids(cur, prev)
    assert [e["id"] for e in cur] == ["old1", "new2"]
