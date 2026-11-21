from pipeline.categorize import rule_category, rule_tags
from pipeline.geo import in_municipality


def cat(t, cats=None, desc=None):
    return rule_category({"t": t, "_cats": cats or [], "desc": desc})


def test_source_categories_win():
    assert cat("Hygge i salen", ["Koncert, musik og fællessang"]) == "music"
    assert cat("Aften i biblioteket", ["Borgermøder, debatter, foredrag m.m."]) == "talks_learning"
    assert cat("Lørdagsleg", ["Familie og børn"]) == "family_kids"
    assert cat("Juleteater", ["Jul"]) == "theatre_culture"


def test_title_and_description_rules():
    assert cat("Julemarked på Sønderborg Slot") == "markets"
    assert cat("Julekoncert i Sct. Marie Kirke") == "music"
    assert cat("SønderjyskE – Aalborg Håndbold") == "sports"
    assert cat("Ringriderfesten 2027") == "festivals"
    assert cat("Fernisering: Nye værker") == "exhibitions"
    assert cat("Aften med Peter", desc="Et foredrag om Dybbøl 1864") == "talks_learning"
    assert cat("Hyggeaften") is None


def test_tags():
    assert rule_tags({"t": "Julekoncert", "_cats": []}) == ["christmas"]
    assert rule_tags({"t": "Stand-up", "_cats": ["Unge 15-30 år"]}) == ["youth"]


def test_municipality():
    assert in_municipality({"zip": "6400"}) is True
    assert in_municipality({"addr": "Storegade 1, 6200 Aabenraa"}) is False
    assert in_municipality({"n": "Ahlmannsparken", "town": "Gråsten"}) is True
    assert in_municipality({"n": "Deutsches Haus", "town": "Flensburg"}) is False
    assert in_municipality({"n": "Kulturhus"}) is None
    inside = [(54.909, 9.792), (55.057, 9.741), (54.95, 9.87), (54.889, 9.674), (54.92, 9.595), (54.85, 9.95), (55.001, 9.985)]
    outside = [(55.044, 9.418), (54.79, 9.43), (54.863, 9.588), (54.84, 9.47), (55.25, 9.49)]
    assert all(in_municipality({"lat": a, "lng": b}) for a, b in inside)
    assert not any(in_municipality({"lat": a, "lng": b}) for a, b in outside)


def test_student_tag_and_category():
    assert cat("Bowling night", ["Kurser for studerende", "Sønderborg studieevents"]) == "students"
    assert cat("Calling all students! Get wet and eat pizza", ["Unge 15-30 år", "Sport, dans og vandringer"]) == "sports"
    assert sorted(rule_tags({"t": "Calling all students! Pizza", "_cats": ["Unge 15-30 år"]})) == ["students", "youth"]
    assert rule_tags({"t": "Bowling", "_cats": ["Sønderborg studieevents"]}) == ["students"]
    assert rule_tags({"t": "Studieliv", "_cats": []}) == []
