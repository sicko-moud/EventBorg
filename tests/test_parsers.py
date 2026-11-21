from datetime import datetime

from pipeline.adapters import html_llm, ical, jsonld, ticketmaster, tribe
from pipeline.config import TZ
from pipeline.normalize import detect_status, parse_dt, parse_price, scrub, split_location, to_event

SRC = {"id": "t", "type": "tribe", "local": True}


def test_tribe_parse_and_normalize():
    raw = tribe.parse({
        "title": "Redesign din garderobe &#8211; Weekendkursus",
        "start_date": "2026-11-21 10:00:00", "end_date": "2026-11-21 15:00:00", "all_day": False,
        "url": "https://sonderborg.dk/event/redesign/", "website": "https://www.fof.dk/redesign",
        "cost": "835,00 kr.", "description": "<p>Skab din egen stil. Ring 93 88 15 97 eller skriv til a@b.dk</p>",
        "venue": {"venue": "Foreningshuset", "address": "Gerlachsgade 5", "city": "Sønderborg", "zip": "6400"},
        "categories": [{"name": "Foreninger og fællesskab"}], "organizer": [],
        "image": {"url": "https://x/full.jpg", "sizes": {"medium_large": {"url": "https://x/768.jpg"}}},
    })
    e = to_event(raw, SRC, {})
    assert e["t"] == "Redesign din garderobe – Weekendkursus"
    assert e["s"] == "2026-11-21T10:00+01:00" and e["e"] == "2026-11-21T15:00+01:00"
    assert e["p"] == {"free": False, "min": 835}
    assert e["v"] == {"n": "Foreningshuset", "addr": "Gerlachsgade 5", "town": "Sønderborg"} and e["_zip"] == "6400"
    assert e["img"] == "https://x/768.jpg" and e["url"] == "https://www.fof.dk/redesign"
    assert "93 88" not in e["desc"] and "@" not in e["desc"]
    assert e["src"] == [{"id": "t", "kind": "web", "url": "https://sonderborg.dk/event/redesign/"}]


def test_tribe_venue_list_and_zip_in_city():
    raw = tribe.parse({"title": "X", "start_date": "2026-12-01 19:00:00", "venue": [], "url": "u"})
    assert raw["venue"]["n"] is None
    e = to_event({**raw, "venue": {"n": "Ulkebøl Kirke", "town": "6400"}}, SRC, {})
    assert e["v"]["town"] == "Sønderborg"
    e = to_event({**raw, "venue": {"n": "Ulkebøl Kirke", "town": "6400", "zip": "Sønderborg"}}, SRC, {})
    assert e["v"]["town"] == "Sønderborg" and e["_zip"] == "6400"


JSONLD_PAGE = """<html><script type="application/ld+json">{"@context":"https://schema.org","@graph":[
 {"@type":"BreadcrumbList"},
 {"@type":"MusicEvent","name":"GIMME – A Tribute to ABBA","startDate":"2027-04-16T20:00:00+02:00",
  "eventStatus":"https://schema.org/EventRescheduled",
  "location":{"@type":"MusicVenue","name":"Koncertsalen Alsion","address":{"streetAddress":"Alsion 2","postalCode":"6400","addressLocality":"Sønderborg"}},
  "offers":[{"price":"395","url":"https://billet/abba"},{"price":"495"}],"image":["https://img/abba.jpg"]}]}</script>
<script type="application/ld+json">{"@type":"Event","name":"Nøddeknækkeren","startDate":"2026-12-10",
 "location":{"name":"Koncertsalen Alsion","address":"Alsion 2, 6400 Sønderborg"},"offers":{"price":"0"}}</script>
<script type="application/ld+json">{broken</script></html>"""


def test_jsonld_from_html():
    evs = jsonld.from_html(JSONLD_PAGE, "https://koncertsalenalsion.dk/event/abba/")
    assert [e["title"] for e in evs] == ["GIMME – A Tribute to ABBA", "Nøddeknækkeren"]
    a, b = evs
    assert a["price"] == {"free": False, "min": 395.0, "max": 495.0} and a["url"] == "https://billet/abba"
    assert a["status"] == "moved" and a["venue"]["zip"] == "6400" and a["img"] == "https://img/abba.jpg"
    assert b["all_day"] and b["price"] is None and b["venue"]["town"] == "Sønderborg"
    e = to_event(b, {"id": "s", "type": "jsonld"}, {})
    assert e["ad"] is True and e["s"] == "2026-12-10T00:00+01:00"


def test_jsonld_find_links():
    html = '<a href="/event/a/">A</a><a href="https://other.dk/event/b">B</a><a href="/event/a/">A</a><a href="/om/">x</a>'
    assert jsonld.find_links(html, "https://k.dk/", "/event/") == ["https://k.dk/event/a/"]


ICS = b"""BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:1
SUMMARY:Strikkecafe
DTSTART;TZID=Europe/Copenhagen:20261124T190000
DTEND;TZID=Europe/Copenhagen:20261124T210000
RRULE:FREQ=WEEKLY;COUNT=3
LOCATION:Biblioteket Gr\xc3\xa5sten, Ahlmannsparken 4, 6300 Gr\xc3\xa5sten
END:VEVENT
BEGIN:VEVENT
UID:2
SUMMARY:Juletr\xc3\xa6\xc3\xa6nding
DTSTART;VALUE=DATE:20261129
STATUS:CANCELLED
END:VEVENT
END:VCALENDAR
"""


def test_ical_parse_rrule_and_status():
    evs = ical.parse(ICS, datetime(2026, 11, 21, tzinfo=TZ))
    knit = [e for e in evs if e["title"] == "Strikkecafe"]
    assert [e["start"][:10] for e in knit] == ["2026-11-24", "2026-12-01", "2026-12-08"]
    assert knit[1]["end"].startswith("2026-12-01T21:00")
    assert knit[0]["venue"] == {"n": "Biblioteket Gråsten", "addr": "Ahlmannsparken 4, 6300 Gråsten", "zip": "6300", "town": "Gråsten"}
    jul = [e for e in evs if e["status"]]
    assert len(jul) == 1 and jul[0]["all_day"] and jul[0]["status"] == "cancelled"


def test_ticketmaster_parse_and_geohash():
    assert ticketmaster.geohash(57.64911, 10.40744, 11) == "u4pruydqqvj"
    raw = ticketmaster.parse({
        "name": "Suspekt", "url": "https://tm/e/1",
        "dates": {"start": {"localDate": "2027-03-05", "dateTime": "2027-03-05T19:00:00Z"}, "status": {"code": "onsale"}},
        "classifications": [{"segment": {"name": "Music"}, "genre": {"name": "Hip-Hop/Rap"}}],
        "priceRanges": [{"min": 395.0, "max": 495.0, "currency": "DKK"}],
        "images": [{"ratio": "16_9", "width": 1024, "url": "big"}, {"ratio": "16_9", "width": 640, "url": "mid"}],
        "_embedded": {"venues": [{"name": "Sydjysk Sparekasse Skansen", "postalCode": "6400", "city": {"name": "Sønderborg"},
                                  "location": {"latitude": "54.91", "longitude": "9.79"}}]},
    })
    assert raw["img"] == "mid" and raw["cats"] == ["Music", "Hip-Hop/Rap"] and not raw["all_day"]
    e = to_event(raw, {"id": "ticketmaster", "type": "ticketmaster"}, {})
    assert e["s"] == "2027-03-05T20:00+01:00" and e["src"][0]["kind"] == "api"
    assert e["v"]["lat"] == 54.91 and e["p"] == {"free": False, "min": 395, "max": 495}


def test_html_llm_page_text_marks_links():
    html = ('<html><body><nav>Menu</nav><main><h2>Koncert</h2><a href="/events/x/">Læs mere</a>'
            '<a href="https://billet.dk/1">Køb</a><script>x()</script></main><footer>f</footer></body></html>')
    text = html_llm.page_text(html, "https://sonderborghus.dk/arrangementer/")
    assert "Menu" not in text and "x()" not in text
    assert "[https://sonderborghus.dk/events/x/]" in text and "[https://billet.dk/1]" in text


def test_parse_price():
    assert parse_price("Gratis") == {"free": True}
    assert parse_price("Fri entré") == {"free": True}
    assert parse_price("100-150 kr.") == {"free": False, "min": 100, "max": 150}
    assert parse_price("Kr. 1.200,-") == {"free": False, "min": 1200}
    assert parse_price("Billetter fra 2026: 125 kr") == {"free": False, "min": 125}
    assert parse_price("€ 10") == {"free": False, "min": 75}
    assert parse_price("Se hjemmeside") is None


def test_parse_dt_and_status_and_location():
    assert parse_dt("2026-07-10 18:00:00")[0].isoformat() == "2026-07-10T18:00:00+02:00"
    assert parse_dt("2026-11-21T09:00:00Z")[0].isoformat() == "2026-11-21T10:00:00+01:00"
    assert parse_dt("2026-11-21") == (datetime(2026, 11, 21, tzinfo=TZ), True)
    assert parse_dt("snart") == (None, False)
    assert detect_status("AFLYST: Julekoncert", None) == ("Julekoncert", "cancelled")
    assert detect_status("Julekoncert", "moved") == ("Julekoncert", "moved")
    assert split_location("Mejeriet, Løngang 2, 6400 Sønderborg")["town"] == "Sønderborg"
    assert scrub("Tilmelding: 20 30 40 50 eller x@y.dk Start 2026-11-21 kl. 19.30") == "Tilmelding: eller Start 2026-11-21 kl. 19.30"
