from dataclasses import dataclass
from datetime import datetime

from ..http import Http
from ..llm import LLM


class Skip(Exception):
    """Source could not run this time for an expected reason (missing key, quota, robots)."""

    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


@dataclass
class Ctx:
    http: Http
    llm: LLM
    now: datetime
    state: dict


def get_adapter(kind: str):
    from . import html_llm, ical, jsonld, ticketmaster, tribe

    return {"tribe": tribe.fetch, "ical": ical.fetch, "jsonld": jsonld.fetch,
            "html_llm": html_llm.fetch, "ticketmaster": ticketmaster.fetch}.get(kind)
