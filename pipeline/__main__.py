import argparse
import sys
from pathlib import Path

from .config import ROOT, load_env


def main() -> int:
    ap = argparse.ArgumentParser(prog="pipeline")
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run", help="fetch, normalize, dedupe, enrich and publish")
    r.add_argument("--out", default=str(ROOT / "out"))
    r.add_argument("--only", help="comma-separated source ids")
    r.add_argument("--force", action="store_true", help="ignore per-source refresh intervals")
    r.add_argument("--no-llm", action="store_true")
    pu = sub.add_parser("push", help="send the pushes the last run scheduled (dry run without FCM_SERVICE_ACCOUNT)")
    pu.add_argument("--out", default=str(ROOT / "out"))
    pu.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    load_env()
    if a.cmd == "push":
        from .push import send_pending

        res = send_pending(Path(a.out), a.dry_run)
        print(" ".join(f"{k}={v}" for k, v in res.items()))
        return 1 if res.get("errors") and not res.get("sent") else 0
    from .run import run

    res = run(Path(a.out), set(a.only.split(",")) if a.only else None, a.force, not a.no_llm)
    print(f"\n{'source':24} {'type':12} {'status':16} {'raw':>5} {'kept':>5} {'final':>5}  drops")
    for row in res["report"]:
        drops = " ".join(f"{k}={v}" for k, v in row["drops"].items())
        print(f"{row['id']:24} {row['type']:12} {row['st']:16} {row['raw']:5} {row['kept']:5} "
              f"{res['per_source'][row['id']]:5}  {drops}{'  ERR ' + row['err'] if row['err'] else ''}")
    print(f"\nevents={res['events']} (from {res['merged_from']} before dedupe) new={res['new']} "
          f"pushable={res['pushable']} push_now={res['push_now']} queued={res['queued']} llm_calls={res['llm_calls']} llm_fallbacks={res['llm_fallbacks']} llm_pending={res['llm_pending']} publish={res['publish']}")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
