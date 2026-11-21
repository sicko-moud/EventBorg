import re

from .text import fold

ZIP_TOWN = {"6300": "Gråsten", "6310": "Broager", "6320": "Egernsund", "6400": "Sønderborg",
            "6430": "Nordborg", "6440": "Augustenborg", "6470": "Sydals"}

TOWNS = [
    "Sønderborg", "Sonderburg", "Nordborg", "Augustenborg", "Augustenburg", "Gråsten", "Gravenstein", "Broager",
    "Egernsund", "Dybbøl", "Guderup", "Havnbjerg", "Svenstrup", "Lysabild", "Kegnæs", "Høruphav", "Hørup",
    "Mommark", "Fynshav", "Ketting", "Sydals", "Nordals", "Vollerup", "Ulkebøl", "Kværs", "Rinkenæs", "Avnbøl",
    "Ullerup", "Nybøl", "Blans", "Sottrup", "Stenderup", "Oksbøl", "Tandslet", "Vibæk", "Asserballe", "Notmark",
    "Skelde", "Dynt", "Iller", "Adsbøl", "Fiskbæk", "Sundeved", "Ragebøl", "Dyvig", "Hjortspring",
    "Elstrup", "Stevning", "Mjels", "Østerholm", "Kirke Hørup", "Snogbæk", "Sandbjerg", "Als",
]
OUTSIDE = [
    "Aabenraa", "Haderslev", "Tønder", "Kolding", "Vejle", "Esbjerg", "Odense", "Padborg", "Kruså", "Kollund",
    "Sønderhav", "Rødekro", "Tinglev", "Flensburg", "Flensborg", "Glücksburg", "Kiel", "Hamburg", "København",
    "Aarhus", "Herning", "Fredericia", "Middelfart", "Svendborg", "Bov", "Gram", "Vojens",
]
_TOWNS_RE = re.compile(r"\b(" + "|".join(re.escape(fold(t)) for t in sorted(TOWNS, key=len, reverse=True)) + r")\b")
_OUT_RE = re.compile(r"\b(" + "|".join(re.escape(fold(t)) for t in OUTSIDE) + r")\b")
_ZIP_RE = re.compile(r"(?<![\d-])(\d{4})(?![\d-])")
_ONLINE_RE = re.compile(r"\b(online|zoom|teams|webinar|virtuel|livestream)\b")

# Rough outline of the municipality (lat, lng): Als, Sundeved and the Gråsten/Broager peninsula.
POLYGON = [
    (54.905, 9.530), (54.985, 9.530), (55.025, 9.620), (55.060, 9.690), (55.120, 9.740), (55.120, 9.900),
    (55.000, 10.100), (54.860, 10.060), (54.820, 9.900), (54.850, 9.700), (54.860, 9.620), (54.890, 9.580),
]


def _inside(lat: float, lng: float) -> bool:
    hit, j = False, len(POLYGON) - 1
    for i, (yi, xi) in enumerate(POLYGON):
        yj, xj = POLYGON[j]
        if (yi > lat) != (yj > lat) and lng < (xj - xi) * (lat - yi) / (yj - yi) + xi:
            hit = not hit
        j = i
    return hit


def find_zip(*parts: str | None) -> str | None:
    for p in parts:
        m = _ZIP_RE.search(str(p or ""))
        if m:
            return m.group(1)
    return None


def is_online(v: dict) -> bool:
    return bool(_ONLINE_RE.search(fold(" ".join(str(v.get(k) or "") for k in ("n", "addr", "town")))))


def in_municipality(v: dict) -> bool | None:
    """True/False when the venue tells us, None when it does not."""
    z = v.get("zip") or find_zip(v.get("addr"), v.get("town"))
    if z and str(z).isdigit() and len(str(z)) == 4:
        return str(z) in ZIP_TOWN
    try:
        lat, lng = float(v["lat"]), float(v["lng"])
        if lat and lng:
            return _inside(lat, lng)
    except (KeyError, TypeError, ValueError):
        pass
    text = fold(" ".join(str(v.get(k) or "") for k in ("town", "addr", "n")))
    if _OUT_RE.search(text):
        return False
    if _TOWNS_RE.search(text):
        return True
    return None
