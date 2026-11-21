# PROGRESS

**M3 Social inbox + story relay — code done, Worker deployed (2026-11-21).** Next: M4 (needs Meta/Telegram keys only for live social tests).

Status
- Worker live with /meta/webhook (verify + X-Hub-Signature-256), /meta/deletion, /tg/webhook (secret header), /story-seen, /stories, /job/* (INBOX_TOKEN). Secrets set: INBOX_TOKEN, META_VERIFY_TOKEN, TG_SECRET (values in gitignored worker/.dev.vars); GitHub secret INBOX_TOKEN; labels inbox/approve.
- Pipeline: pipeline/inbox.py runs inside `run` (pull KV → Gemini batched/multimodal → rules → publish or Issue), Business Discovery polling, trust, DELETE; `python -m pipeline ack` after publish deletes items and replies.
- Local e2e verified against wrangler dev: TG/WA/IG webhooks → KV → job → 2 events auto-published, replies queued, ack deleted all; deletion callback + story match OK.
- Tests: 39 pytest + 13 node.

Open issues
- Instagram DMs/story mentions from the public need Meta App Review + Business Verification (see DECISIONS); owner decision pending. Works now for app-role accounts.
- Keys still missing: META_APP_SECRET, META_TOKEN, IG_USER_ID, WA_TOKEN, WA_PHONE_ID, WA_NUMBER, TG_TOKEN, TG_BOT (+ GH_TOKEN from M2). After TG_TOKEN: `curl -X POST -H "Authorization: Bearer $INBOX_TOKEN" <worker>/job/setup`.
- Not yet verified live: Meta webhook test tool, a real story mention, real replies, Business Discovery (needs the keys above).
- Gemini free tier 503s often; inbox items simply wait in KV for the next run.

Next step (M4)
- Android app core: feed, filters, search, detail, saved, sources, settings, cache (ETag).
