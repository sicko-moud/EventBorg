# PROGRESS

**M6 done (2026-11-22). Added on top of M5 (2026-11-22): 15 Instagram sources, watch-list pre-load, post alerts, priority sources, per-source switches.** Next: M7.

Status
- APK 0.8.1, release-signed (SHA-256 92:85:D5:4B:…:93:3B); keystore in ~/.eventborg, CI secrets EB_*. `v*` tag → GitHub Release `eventborg.apk` (none pushed yet). Pipeline keep-alive daily.
- sources.yaml: 15 accounts with `watch: true` (pre-loaded once per phone); HK (@hkctutten) `priority: true` → "Priority sources" channel (sound, heads-up), pinned first, never grouped, notified without being a favourite.
- Post alerts: the listener catches "new post" notifications; for listed accounts (setting "Check new posts…", on by default) the text and picture go to Worker POST /post-seen → Gemini once per source per 20 min → inbox item (untrusted: ≥0.92 or corroboration) → normal push. Not an event → local "New post from <name>" (New posts channel).
- Per-source switches on the source page (events / stories / posts), also from a watched profile's menu. Profile links are saved without their query string.
- Look: Graphite design system (DESIGN.md) — dark only, neutral tokens, ticket launcher icon (art/), Inter + IBM Plex Mono bundled, outlined icons, bordered cards, divided lists.
- Tests: 44 pytest, 14 node, Kotlin unit tests (link parser, story/post matcher, heuristic, topics).

Open issues
- Instagram's and Facebook's post notification wording is guessed (EN/DA/DE); check it on a real phone and adjust Match.kt and PostTest.
- HK's kind and audience are guesses; no website or Facebook found for most new accounts.
- Not yet tested on a real phone: FCM delivery, listener texts, held-intent "Open". Uninstall earlier debug-signed CI builds once.
- Keys still missing: META_APP_SECRET, META_TOKEN, IG_USER_ID, GH_TOKEN (+ optional WA_*). Without GH_TOKEN, queued /post-seen events wait for the 15-min cron.
- Back up ~/.eventborg/eventborg-release.jks and the EB_* lines in .env.

Next step (M7)
- Implement the best [M7] items from IDEAS.md; ask first about Google Play (paid account).
