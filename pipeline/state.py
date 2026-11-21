import json
from pathlib import Path


def _load(p: Path, default):
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return default


def dump(p: Path, data, pretty: bool = False) -> None:
    p.parent.mkdir(parents=True, exist_ok=True)
    kw = {"indent": 1} if pretty else {"separators": (",", ":")}
    p.write_text(json.dumps(data, ensure_ascii=False, **kw), encoding="utf-8")


class State:
    """Everything that survives between runs; lives on the data branch next to events.json."""

    def __init__(self, out: Path):
        self.out = out
        self.dir = out / "state"
        self.sources: dict = _load(self.dir / "sources.json", {})
        self.raw: dict = _load(self.dir / "raw.json", {})
        self.llm: dict = _load(self.dir / "llm.json", {})
        self.seen: dict = _load(self.dir / "seen.json", {})
        self.meta: dict = _load(self.dir / "meta.json", {})
        self.prev_events: list = _load(out / "events.json", {}).get("events", [])
        self.prev_sources: list = _load(out / "sources.json", {}).get("sources", [])
        self._sizes = (len(self.llm), len(self.seen))

    def source(self, sid: str) -> dict:
        return self.sources.setdefault(sid, {})

    def grew(self) -> bool:
        return (len(self.llm), len(self.seen)) != self._sizes

    def save(self) -> None:
        dump(self.dir / "sources.json", self.sources)
        dump(self.dir / "raw.json", self.raw)
        dump(self.dir / "llm.json", self.llm)
        dump(self.dir / "seen.json", self.seen)
        dump(self.dir / "meta.json", self.meta)
