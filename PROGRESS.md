# PROGRESS

**M6 Size, performance, setup — done (2026-11-22).** Next: M7.

Status
- APK 0.6.0: 2.33 MB, signed with the release key (SHA-256 92:85:D5:4B:…:93:3B). Keystore in ~/.eventborg (passwords in .env); CI secrets EB_KEYSTORE_B64 / EB_KEYSTORE_PASSWORD / EB_KEY_ALIAS / EB_KEY_PASSWORD are set.
- Cold start on the CI emulator, 2 runs: 406–767 ms (medians 431 / 676 ms) after profile compilation. The first launch after install took 2.9 s and 1.8 s; M5 measured 1.0–1.4 s. The spread follows emulator load: System UI itself dropped 295 frames in one run.
- Hand-written baseline profile (app code, kotlinx-serialization, OkHttp) on top of the profiles AndroidX ships. Graph stores open lazily on IO, so startup does no file I/O on the main thread.
- `v*` tag → GitHub Release with `eventborg.apk` + .sha256 (README links releases/latest). No tag pushed yet.
- Keep-alive: the pipeline re-enables its own workflow daily (03 UTC) through the API.
- README: setup for GitHub/Pages, Gemini, Ticketmaster, Firebase, Cloudflare, signing, Meta (Instagram, WhatsApp, webhooks, privacy/deletion URLs, App Review before IG_PUBLIC_INBOX).

Open issues
- Not yet tested on a real phone: FCM delivery, real Instagram/Facebook notification texts, the Facebook "Open" PendingIntent. Installs of earlier CI APKs (debug-signed) must be uninstalled once.
- Keys still missing: META_APP_SECRET, META_TOKEN, IG_USER_ID, GH_TOKEN (+ optional WA_*).
- README step 7.7.3 (Page subscribed_apps field): Meta's docs didn't settle it; confirm with the webhook test tool.
- Back up ~/.eventborg/eventborg-release.jks and the EB_* lines in .env outside this PC.

Next step (M7)
- Implement the best [M7] items from IDEAS.md; ask first about Google Play (paid account).
