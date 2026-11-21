# PROGRESS

**M2 Push sender + Worker app endpoints — done and live (2026-11-21).** Next: M3.

Status
- M1 pipeline live: https://sicko-moud.github.io/EventBorg/events.json (~314 events, 10+ sources).
- Push: pipeline/push.py. The run step picks pushes (first-ingest + backfill silence, 60-day rule, quiet hours 22–08 queue in meta.pushq) into meta.push; the CI step `python -m pipeline push` sends them after publish. Grouping >3/category, flood guard >8. `push --test` (or workflow input test_push) sends one event to topic `test` only.
- Worker live: https://eventborg.eventborg-worker.workers.dev — POST /extract, POST /submit (KV inbox, dispatch ≤1/60 s + minute cron), GET /privacy /data-deletion /organizers (EN/DA). KV id in wrangler.toml.
- Secrets: GitHub GEMINI_API_KEY, FCM_SERVICE_ACCOUNT; Worker GEMINI_API_KEY, SIGNING_KEY, FCM_SERVICE_ACCOUNT (for M3 story alerts).
- Firebase project `eventborg`; android/app/google-services.json (package app.eventborg) and firebase-service-account.json stay gitignored. Local push test: `FCM_SERVICE_ACCOUNT=firebase-service-account.json python -m pipeline push --test`.
- Shared extraction prompt/schema: shared/extract.json. Tests: 28 pytest + 6 node.

Open issues
- GH_TOKEN Worker secret not set (fine-grained PAT, this repo, Contents read/write): /submit skips repository_dispatch; the 15-min cron picks items up.
- Gemini free tier 503s often; Worker retries primary→fallback→primary in 45 s, else 503 llm_unavailable.
- ig handles still unknown for Mejeriet, Koncertsalen Alsion, Universe, Nordborghus, Skansen, Sønderborg Teater.
- CI will need google-services.json as a secret when the APK is built in CI (M6).

Next step (M3)
- Inbox job: pull KV `in:` items via an authenticated Worker endpoint, map with shared/extract.json, auto-publish rules (app items: conf only if `orig` and not `edited`), Issues, replies; DELETE/SLET command + Meta deletion callback (promised on /data-deletion); Meta/WhatsApp/Telegram webhooks; story relay with FCM from the Worker.
