# PROGRESS

**M3 Social inbox + story relay — done, revised per owner decisions (2026-11-21).** Next: M4.

Status
- Worker live: /meta/webhook (verify + X-Hub-Signature-256), /meta/deletion, /story-seen, /stories, /channels, /job/* (INBOX_TOKEN). Secrets set: INBOX_TOKEN, META_VERIFY_TOKEN (values in gitignored worker/.dev.vars); GitHub secret INBOX_TOKEN; labels inbox/approve.
- Telegram removed. Public Instagram DMs/story mentions off (IG_PUBLIC_INBOX="false", code kept). WhatsApp off until WA_TOKEN + WA_PHONE_ID + WA_NUMBER exist. Live ways in today: sharing into the app (+ Business Discovery once keys exist).
- Pipeline: pipeline/inbox.py inside `run` (KV → Gemini → rules → publish or Issue), Business Discovery polling, trust, DELETE; `python -m pipeline ack` after publish.
- CLAUDE.md now specs on-device story detection as primary, the local watch list (IG/FB profiles, FB groups), group-post alerts and "Hide original notification" for M5.
- Tests: 39 pytest + 13 node.

Open issues
- Keys missing: META_APP_SECRET, META_TOKEN, IG_USER_ID (Business Discovery); WA_TOKEN, WA_PHONE_ID, WA_NUMBER (optional WhatsApp); GH_TOKEN (Worker → repository_dispatch).
- App Review + Business Verification only if IG_PUBLIC_INBOX should ever be true.
- Not yet verified live: Meta webhook test tool, Business Discovery, WhatsApp replies (need the keys).
- Gemini free tier 503s often; inbox items wait in KV for the next run.

Next step (M4)
- Android app core: feed, filters, search, detail, saved, sources, settings, cache (ETag).
