import html
import re
import unicodedata

from bs4 import BeautifulSoup

_FOLD = str.maketrans({"ø": "o", "æ": "ae", "å": "a", "ß": "ss", "Ø": "o", "Æ": "ae", "Å": "a"})
_WS = re.compile(r"\s+")


def fold(s: str | None) -> str:
    if not s:
        return ""
    s = s.translate(_FOLD).lower().replace("aa", "a")
    s = unicodedata.normalize("NFKD", s)
    return "".join(c for c in s if not unicodedata.combining(c))


def html_to_text(s: str | None) -> str:
    if not s:
        return ""
    if "<" in s:
        s = BeautifulSoup(s, "html.parser").get_text("\n")
    s = html.unescape(s).replace("\xa0", " ")
    lines = [_WS.sub(" ", ln).strip() for ln in s.splitlines()]
    return "\n".join(ln for ln in lines if ln)


def clean_line(s: str | None) -> str:
    return _WS.sub(" ", html_to_text(s)).strip()


_VENUE_SUFFIX = re.compile(r"\s+//.*$")
_COUNTRY = re.compile(r"\(\s*[A-Z]{2,3}\s*\)")
_NOISE = re.compile(r"\b(udsolgt|sold out|ausverkauft|aflyst|cancelled|ny dato|ekstra(?:forestilling|koncert)?)\b")


def normalize_title(title: str) -> str:
    """Matching key for event IDs and dedupe similarity (never shown to users)."""
    raw = html.unescape(title or "")
    t = _COUNTRY.sub(" ", _VENUE_SUFFIX.sub("", raw))
    t = _NOISE.sub(" ", fold(t))
    t = " ".join(re.sub(r"[^\w\s]|_", " ", t).split())
    return t or " ".join(re.sub(r"[^\w\s]|_", " ", fold(raw)).split())


def detect_lang(text: str) -> str | None:
    words = re.findall(r"[a-zæøåäöüß]+", text.lower())
    if len(words) < 4:
        return None
    sets = {
        "da": {"og", "er", "det", "til", "på", "med", "af", "for", "ikke", "som", "der", "en", "et", "du", "vi", "kan", "fra", "kl"},
        "en": {"the", "and", "is", "to", "of", "with", "for", "in", "you", "we", "a", "an", "on", "at", "this", "from"},
        "de": {"und", "der", "die", "das", "mit", "ist", "für", "ein", "eine", "wir", "sie", "von", "zu", "im", "auf"},
    }
    scores = {k: sum(w in v for w in words) for k, v in sets.items()}
    best = max(scores, key=scores.get)
    return best if scores[best] >= 2 else None
