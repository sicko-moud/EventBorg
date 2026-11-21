# PROGRESS

**M4 App core — done (2026-11-21).** Next: M5.

Status
- android/: Kotlin + Compose M3 app (app.eventborg, minSdk 26, target 37, AGP 9.5.2, Gradle 9.9.2 wrapper). Built by `.github/workflows/android.yml` (no local SDK); release APK 2.2 MB (artifact `eventborg-release`). Commit message with `[smoke]` (or a manual run) adds an emulator smoke test: click-through screenshots + crash check (`android/smoke.sh`); last run passed, cold start 1.3 s on the CI emulator.
- Screens: feed (search, quick chips, sticky sections incl. Ongoing, pull-to-refresh, offline/error/empty states), filter sheet (saved), detail (Custom Tab tickets, maps, calendar, share, save, star category/source), saved, sources + source page + "Suggest a source", settings (event/story topics, EN/DA, about/privacy).
- Data: events.json/sources.json from Pages with ETag, cached in noBackupFilesDir; prefs in DataStore; FCM topics synced from settings (push handling itself is M5).
- Worker: POST /suggest (deployed); inbox job opens `source`-labelled issues. Privacy page mentions it.
- Students (ad hoc, branch `students`, not merged yet): IDA via new `sitemap` adapter (5 local events today, fill spread over ~9 runs, push-silent), 10 SDU student associations as social sources, `students` tag; app "For students" row + chip first + filter + setting (student and youth 15–30 events). APK 0.4.1.
- Tests: 42 pytest + 13 node (all pass).

Open issues
- Keys still missing from M3: META_APP_SECRET, META_TOKEN, IG_USER_ID, GH_TOKEN (+ optional WA_*).
- Only checked on the CI emulator; not yet on a real phone (CI APKs are debug-signed per run: uninstall between builds). FCM topic sync untested (emulator Play services too old).
- No app baseline profile yet (library profiles only) and no release keystore: M6.

Next step (M5)
- Push handling (Event / Story alerts / Group posts channels, Android 13 permission flow, insert pushed events into the cache, onNewToken → resync topics), story strip, notification listener + disclosure, watch list, group-post alerts, share target, "Add your event".
