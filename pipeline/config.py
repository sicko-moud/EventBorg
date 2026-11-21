import os
import re
import subprocess
from pathlib import Path
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parent.parent
TZ = ZoneInfo("Europe/Copenhagen")
HORIZON_DAYS = 120

CATEGORIES = [
    "music", "nightlife", "theatre_culture", "exhibitions", "food_drink", "sports", "family_kids",
    "students", "talks_learning", "markets", "outdoor_nature", "festivals", "community", "business", "other",
]


def load_env(path: Path = ROOT / ".env") -> None:
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip().strip("\"'"))


def env(name: str, default: str | None = None) -> str | None:
    return os.environ.get(name, "").strip() or default


def repo_slug() -> str:
    """owner/name from Actions, else from the local git remote."""
    slug = env("GITHUB_REPOSITORY")
    if slug:
        return slug
    try:
        url = subprocess.run(["git", "-C", str(ROOT), "config", "--get", "remote.origin.url"],
                             capture_output=True, text=True, timeout=5).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        url = ""
    m = re.search(r"github\.com[:/](.+?)(?:\.git)?$", url)
    return m.group(1) if m else "eventborg/EventBorg"
