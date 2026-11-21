# PROGRESS

**M5 App push, stories, watch list, share — built on branch `m5` (2026-11-21), not merged to main yet.** Next: M6.

Status
- Push: FCM service handles event / grouped / story data messages on three channels (New events, Story alerts, Group posts); pushed events go into the cache at once (pushed.json); Save and Add-to-calendar actions; onNewToken resubscribes topics. Android 13+ permission explained once on the feed.
- Story strip (GET /stories + pushes + local detections, 24 h, tap opens Instagram/Facebook and dims, long-press mutes). Story alerts deduped by source within 3 h.
- Notification listener (opt-in; component disabled until the disclosure is accepted): Instagram/Facebook only, matches watch list + listed handles/names, hide-original, optional /story-seen relay. Onboarding tips incl. "Restricted setting" help for sideloaded APKs.
- Watching tab: profiles and groups by pasted/shared link, suggestions from sources.json, ✓ on first catch, mute/remove; Group posts (7 days, on-phone only) with Open / Add as event.
- Share target + "Add your event" (paste text/link, pick image, live channels from GET /channels) → /extract → editable draft → /submit.
- Tests: 42 pytest + 13 node + Kotlin unit tests (link parser, matchers, heuristic) in CI. CI build + emulator smoke pass: APK 0.5.0, 2.3 MB, cold start 1.0–1.4 s on the CI emulator; share → /extract → draft works live.

Open issues
- Not tested on a real phone: FCM delivery, the listener against real Instagram/Facebook notification texts (formats are guessed; adjust matcher/tests once seen), Facebook PendingIntent "Open".
- Keys still missing from M3: META_APP_SECRET, META_TOKEN, IG_USER_ID, GH_TOKEN (+ optional WA_*).
- No app baseline profile and no release keystore yet (M6).

Next step (M6)
- Merge `m5` into main, then size/performance pass, baseline profile, README setup guide, keep-alive workflow.
