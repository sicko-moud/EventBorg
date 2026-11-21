# PROGRESS

**M2 Push sender + Worker app endpoints — done; Worker live (2026-11-21); FCM pending the Firebase files.** Next: FCM secret + test push, then M3.

Status
- M1 pipeline live: https://sicko-moud.github.io/EventBorg/events.json (~314 events, 10+ sources).
- Push: pipeline/push.py. The run step picks pushes (first-ingest + backfill silence, 60-day rule, quiet hours 22–08 queue in meta.pushq) into meta.push; the CI step `python -m pipeline push` sends them after publish (dry run without FCM_SERVICE_ACCOUNT). Grouping >3/category, flood guard >8.
- Worker (worker/, wrangler 4.160): POST /extract (text/link/image → Gemini → draft + signed tok), POST /submit (→ KV `in:<ts>:<id>`, repository_dispatch ≤1/60 s + minute cron flush), GET /privacy /data-deletion /organizers (EN/DA). Verified with `wrangler dev`: text, link (robots-checked), image, blocked IG link, forged token, rate limit, cron flush.
- Shared extraction prompt/schema: shared/extract.json.
- Tests: 28 pytest + 6 node (`node --test worker/test/worker.test.js`).

Live
- Worker: https://eventborg.eventborg-worker.workers.dev (secrets GEMINI_API_KEY, SIGNING_KEY; KV c2c5b060…). Smoke-tested /extract, /submit, pages.

Still needed
- google-services.json + firebase-service-account.json in the project root (both gitignored) → move the first to android/app/, set FCM_SERVICE_ACCOUNT as GitHub + Worker secret, `python -m pipeline push --test`.
- GH_TOKEN Worker secret (fine-grained PAT, this repo, Contents read/write) for repository_dispatch.

Open issues
- Gemini free tier 503s often; Worker retries primary→fallback→primary in 45 s, else 503 llm_unavailable.
- ig handles still unknown for Mejeriet, Koncertsalen Alsion, Universe, Nordborghus, Skansen, Sønderborg Teater.

Next step (M3)
- Inbox job: pull KV `in:` items via an authenticated Worker endpoint, map with shared/extract.json, auto-publish rules (app items: conf only if `orig` and not `edited`), Issues, replies; DELETE/SLET command + Meta deletion callback (promised on /data-deletion); Meta/WhatsApp/Telegram webhooks; story relay.
