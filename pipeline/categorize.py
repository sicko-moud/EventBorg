import re

from .text import fold

# Source categories (sonderborg.dk, Ticketmaster, JSON-LD keywords). First match wins.
SOURCE_RULES = [
    (r"debat|foredrag|talk|lecture|oplaesning|literatur|litteratur", "talks_learning"),
    (r"koncert|musik|music|jazz|rock|pop|klassisk|kor\b", "music"),
    (r"sport|motion|idraet", "sports"),
    (r"teater|theatre|comedy|arts|film|show|musical|opera|ballet|dans", "theatre_culture"),
    (r"udstilling|kunst|exhibition|krea|museum", "exhibitions"),
    (r"famil|born|kids|children", "family_kids"),
    (r"natur|outdoor|friluft", "outdoor_nature"),
    (r"marked|market|shopping", "markets"),
    (r"mad|food|gastro", "food_drink"),
    (r"festival", "festivals"),
    (r"erhverv|business|job", "business"),
    (r"uddannelse|student|studie|studerende", "students"),
    (r"nightlife|natteliv", "nightlife"),
    (r"forening|faellesskab|borgermode|tilflytter|frivillig|community", "community"),
]

# Title/description keywords (folded: o for ø, ae for æ, a for å). Danish compounds need substring matches.
TEXT_RULES = [
    (r"ringrider|ringridning|festival|byfest|karneval", "festivals"),
    (r"julemarked|loppemarked|marked\b|kraemmer|torvedag|flea market|market\b", "markets"),
    (r"fredagsbar|studenter|\bsdu\b|\bstudents?\b", "students"),
    (r"\bdj\b|natklub|\bparty\b|\bclub night\b|technofest|\brave\b", "nightlife"),
    (r"koncert|concert|\bjazz|\bblues\b|symfoni|orkester|\bkor\b|korkoncert|band\b|\blive\b|singer|sangskriver|fallessang|tribute|kammermusik", "music"),
    (r"teater|forestilling|musical|comedy|stand.?up|opera|ballet|revy|\bshow\b|\bfilm|biograf|cirkus|tryllekunst", "theatre_culture"),
    (r"udstilling|exhibition|fernisering|vernissage|galleri|kunsthal|kunstudstilling", "exhibitions"),
    (r"foredrag|\btalk\b|lecture|workshop|kursus|\bdebat|oplaesning|forfatter|seminar|webinar|undervisning|byvandring|rundvisning|guided tour", "talks_learning"),
    (r"handbold|fodbold|basket|volley|\bkamp\b|\bmatch\b|marathon|motionslob|\blob\b|cykel|svom|turnering|\byoga|\bgolf|\btriatlon|sejlads|regatta", "sports"),
    (r"\bborn|bornene|familie|\bkids\b|family|\bbaby|bamse|juniorklub|legeplads", "family_kids"),
    (r"smagning|tasting|middag|dinner|brunch|vinsmagning|olsmagning|julefrokost|buffet|\bmad\b|\bfood\b|gourmet", "food_drink"),
    (r"\bnatur|vandretur|\bhike|strand|skoven|\bfugle|svampetur|naturvejleder|friluft|kajak|\bsankthans", "outdoor_nature"),
    (r"erhverv|netvaerk|networking|\bbusiness|ivaerksaet|konference|jobmesse|\bjob", "business"),
    (r"frivillig|forening|faellesspisning|borgermode|cafe for|tilflytter|newcomer|velkomst|generalforsamling|gudstjeneste|\bmesse\b", "community"),
]

# Student events get this tag whatever their category; the app ranks them first for students.
STUDENT = r"\bstudents?\b|studerende|studenter|studieevent|studiestart|\bsdu\b|fredagsbar|friday bar|\bida student"
TAG_RULES = [(r"\bjul|christmas|advent|nisse", "christmas"), (r"\bunge\b|\bung\b|15-30|youth", "youth"), (STUDENT, "students")]

_SRC = [(re.compile(p), c) for p, c in SOURCE_RULES]
_TXT = [(re.compile(p), c) for p, c in TEXT_RULES]
_TAG = [(re.compile(p), t) for p, t in TAG_RULES]


def _first(rules, text: str) -> str | None:
    for rx, c in rules:
        if rx.search(text):
            return c
    return None


def rule_category(e: dict) -> str | None:
    for c in e.get("_cats") or []:
        hit = _first(_SRC, fold(c))
        if hit:
            return hit
    return _first(_TXT, fold(e["t"])) or _first(_TXT, fold((e.get("desc") or "")[:400]))


def rule_tags(e: dict) -> list[str]:
    text = fold(" ".join([e["t"], *(e.get("_cats") or [])]))
    return [t for rx, t in _TAG if rx.search(text)]


def categorize(events: list[dict]) -> None:
    for e in events:
        e["c"] = rule_category(e)
        tags = set(e.get("tags") or []) | set(rule_tags(e))
        e["tags"] = sorted(tags) or None
