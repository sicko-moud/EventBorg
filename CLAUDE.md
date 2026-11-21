# EventBorg — project spec (read every session)

If PROGRESS.md doesn't exist, start with M1. Otherwise continue from PROGRESS.md.

ROLE: Senior Android + backend engineer. Build a production-quality, zero-cost event aggregator for Sønderborg Municipality, Denmark.

WORKING RULES (tight token budget, but think like a product owner)
- This spec is the baseline, not the ceiling. Build it first, then improve it: you may add features, refine designs, or change a decision when that clearly makes the app better for users or more reliable. Every change must stay within HARD LIMITS. Log each change or addition in DECISIONS.md (1 line: what + why).
- At the start of each milestone, do a short planning pass: write up to 5 improvement ideas for that milestone's area in IDEAS.md (1 line each, with value and cost). Do the cheap, high-value ones in the same milestone; leave bigger ones for M7.
- Don't ask questions unless you are truly blocked. When something is unclear, pick the best option and log it.
- Ask me first (in your final reply, then stop) before anything that costs money, needs a new external account, needs a new Android permission, or makes the APK noticeably bigger.
- Write code straight to files. Never paste file contents or diffs into chat. No explanations.
- Write all code yourself. Never ask me to write parts of it.
- One milestone per session. At the end of a milestone: update PROGRESS.md (≤20 lines: status, open issues, next step), reply in ≤8 lines (done / how to verify / next), then stop. Each new session starts with "Continue from PROGRESS.md": read that file plus only the files you need.
- Secrets never go in the repo (it is public). Locally read them from a gitignored .env; in CI use GitHub Actions secrets and Cloudflare Worker secrets. Keep .env.example up to date with variable names only. When a milestone needs a key I don't have yet, list exactly which ones in your final reply.
- When unsure about an API field, read only that endpoint's official doc page. No broad browsing.
- Keep comments minimal; no boilerplate docstrings or KDoc; no placeholder features. Build and test once per milestone.
- Write tests only for tricky logic: parsers, dedupe, categorizer, push rules, inbox auto-publish rules, webhook signature checks, story-notification matcher, profile/group link parser, group-post event heuristic.

PRODUCT
An Android app listing every public event in Sønderborg Municipality (Sønderborg, Als, Nordborg, Augustenborg, Gråsten, Broager…). Events arrive automatically from websites, ticket APIs and social media. They are deduplicated and categorized, and new events trigger an instant push. New Instagram/Facebook stories from local venues and from profiles the user watches show up as "might be an event" alerts, detected on the phone from Instagram/Facebook notifications; posts in watched Facebook groups that look like events can be added in one tap. Free, no login, no ads, no tracking. Not an official municipal app. App name "EventBorg"; Android applicationId app.eventborg; social handle @eventborg (placeholder).

HARD LIMITS (never trade these away)
- Free to run: free tiers only, no paid services.
- Lightweight: no background polling, services or wakelocks; release APK target ≤6 MB, hard cap 8 MB; cold start <1 s.
- Legal: official APIs and public pages only; never scrape behind a login; respect robots.txt and ToS.
- Privacy: no user accounts, no analytics, ads or tracking; no personal data stored; follow every privacy rule in this spec.
- Secrets never go in the repo.

ARCHITECTURE (free tiers only, nothing to host)
1. Pipeline: public GitHub repo named EventBorg, Python 3.12, GitHub Actions (free minutes on public repos). If the GitHub CLI (`gh`) is installed and logged in, create the repo and set Actions secrets with it; otherwise ask me to create an empty public repo.
   - Triggers: cron every 15 min, plus repository_dispatch from the Worker so inbox items are handled within ~1–2 min.
   - One `concurrency` group so runs never overlap.
2. Output: compact events.json (next 120 days) and sources.json, served by GitHub Pages from an orphan `data` branch that is force-pushed each run so the repo stays small. Store state (seen IDs, content hashes, LLM cache, sender trust) on the same branch.
3. Push: Firebase Cloud Messaging HTTP v1. The Action sends event pushes; the Worker sends story alerts.
4. LLM: Gemini API free tier, multimodal (text, images), model name from an env var. Use it only for unstructured content, summaries, and categories that rules can't map. Batch 10–20 text items per call, require JSON-schema output, and cache by content hash.
5. Cloudflare Worker (free): the only always-on piece. Keep it pass-through so it stays under the free CPU limit.
   - App endpoints: POST /extract (text or image → LLM → draft event), POST /submit (confirmed draft). The app shrinks images to ≤1280 px JPEG before upload.
   - Story endpoints: POST /story-seen, GET /stories (see STORY ALERTS). Post endpoint: POST /post-seen (see POST ALERTS).
   - Social endpoints: GET+POST /meta/webhook for Instagram (only with IG_PUBLIC_INBOX=true) and WhatsApp (only once configured); answer the verify-token challenge, check X-Hub-Signature-256. POST /meta/deletion (Meta's data-deletion callback). GET /channels returns the live ways in {ig, wa}.
   - Static pages: /privacy (names the processors: Meta, Google, Cloudflare, GitHub), /data-deletion (Meta requirement), /organizers (how to get listed). They mention only the live channels.
   - Inbox items become KV records {channel, type, text, media URLs, sender hash, ts}. Never store media bytes. Then send repository_dispatch, at most once per 60 s. Rate-limit per sender and per IP.

WEB SOURCES (sources.yaml, one adapter per type)
- Rules: respect robots.txt and ToS, max 1 request/s per host, User-Agent includes a contact email.
- Each source may also have: ig (Instagram handle), fb (Facebook Page URL), names[] (display names, used for story matching), and kind: venue | organizer | business. Never list personal accounts.
- watch: true pre-loads the source into every phone's watch list once (first launch after it appears; a removed one stays removed). priority: true (currently HK, @hkctutten) gives it its own heads-up notification channel with sound, notifies even when it isn't a favourite (unless the global setting is Off), never groups its event pushes, and pins it first in the story strip and the watch list. sources.json carries these as pri and dw.
- Types:
  - tribe: WordPress "The Events Calendar" REST API, /wp-json/tribe/events/v1/events
  - ical
  - jsonld: schema.org Event markup in pages
  - html_llm: fallback; strip the page to its main text, extract events with the LLM
  - ticketmaster: Discovery API, 25 km radius, free key
- Seed list. Verify each one and drop dead ones, then web-search for about 20 more (venues, sports clubs, churches, SDU Sønderborg and student organizations, museums, markets, festivals), and fill in their ig/fb handles:
  - sonderborg.dk/begivenheder (The Events Calendar; per-event iCal via ?ical=1)
  - Sønderborghus, Mejeriet, Alsion, Biblioteket Sønderborg, Sønderborg Slot, Historiecenter Dybbøl Banke

SOCIAL INBOX (posts and messages; official APIs only, never scrape behind a login)
Setup (the README gives exact click-by-click steps):
- An Instagram professional account @eventborg linked to a Facebook Page.
- One Meta app of type Business, using "Instagram API with Facebook Login" at Standard Access: Business Discovery polling and the data-deletion callback.
- WhatsApp Cloud API added to the same Meta app, with a dedicated phone number (optional).
- No Facebook Page feed/mention webhooks (they need App Review).
Switches (channels that aren't live are dropped quietly: no queueing, no replies, no errors, not advertised):
- IG_PUBLIC_INBOX (Worker var, default "false"): Instagram DMs and story mentions from the public need App Review (Advanced Access) plus Business Verification. The code stays; with false, those webhook events are acknowledged and dropped. Turning it on is my decision.
- WhatsApp is live only once WA_TOKEN, WA_PHONE_ID and WA_NUMBER all exist.
- GET /channels, the static pages and the app's "Add your event" screen show only live channels; sharing into the app always works.
Inputs:
- Instagram DMs (IG_PUBLIC_INBOX only): people forward posts, reels, screenshots or text. Handle the types ig_post, ig_reel, reel, image, video (use the caption/text only), text, and the legacy share type. Story types (story, ig_story, story_mention) are NOT extracted; they go to STORY ALERTS.
- WhatsApp (when live): people forward messages or flyers to our number. Incoming messages are free. Send at most 1 reply per item, with a hard cap of 900 replies/month to stay inside the free monthly allowance.
- Instagram posts (polling): use Business Discovery for sources that have an ig handle. Stagger requests so each account is polled every 1–2 h, and respect rate-limit headers.
- App drafts: POST /submit from the share target and from a group post's "Add as event".
Processing (the `inbox` job):
1. Pull new items from KV. Download media to temp storage only, send it to Gemini, then delete it. Never store or re-host DM media. Events from DMs get img=null; the app shows category art instead.
2. Gemini returns {is_event, confidence, fields, reason}. Drop non-events silently.
3. Dedupe against existing events. A match adds the new source and counts as corroboration.
4. Auto-publish only when ALL of these hold: is_event; confidence ≥0.8; start within 120 days; location inside the municipality; and at least one of trusted sender, corroborated, or confidence ≥0.92.
   Otherwise open a GitHub Issue with the extracted fields and the reason (no media, no personal data). I approve by adding the label `approve`; the next run publishes it. A sender becomes trusted after 3 published events.
5. Reply in the same channel (Instagram/WhatsApp, within the 24 h window): "Added: <title>, <date> <link>" or "Thanks, we're checking it."
6. Continue with the normal publish and push steps.
Privacy: store only salted hashes of sender IDs; strip names and phone numbers from text; /data-deletion removes a sender's items and trust record.

STORY ALERTS
Stories are never downloaded, read or analyzed. The app only says "<Source> posted a new story, might be an event" and opens it in Instagram or Facebook.
Detection:
a) On the device (PRIMARY): a NotificationListenerService, used only after the user opts in through a clear disclosure screen. The app still works fully without it (relayed alerts and GET /stories keep the strip useful).
   - Act only on notifications from com.instagram.android and com.facebook.katana; return immediately for every other app.
   - Match title/text against the watch list (see WATCH LIST) and each listed source's ig handle and names[], plus a story keyword (story/historie/Story). Never touch a notification that doesn't match.
   - Read title/text in memory only. Never store or send story notification content.
   - On a match: show EventBorg's alert right away if the profile is watched, the source is a favourite, or "All local venues" is on. A watched profile gets its ✓ on its first catch.
   - "Hide original notification" (setting, on by default): right after a match, cancelNotification() the original Instagram/Facebook one. For a listed venue that isn't watched, only cancel when EventBorg showed its own alert, so nothing is lost silently.
   - If the user also enabled sharing and the match is a listed source: POST /story-seen {sourceId, ts} and nothing else. Profiles the user added never leave the phone.
b) Server side (IG_PUBLIC_INBOX only): a story_mention webhook, or a story/ig_story forwarded by DM, raises an alert for that account if it matches a source. Ignore all other accounts. Never fetch the media.
Relay (in the Worker):
- Accept only sourceIds that appear in sources.json with kind venue, organizer or business. Rate-limit per IP.
- Cooldown: at most 1 alert per source per 3 h.
- Send FCM directly from the Worker. Cache the OAuth access token in KV for ~55 min.
  - Target the condition "'st_all' in topics || 'st_<sourceId>' in topics".
  - Data: {sourceId, name, url, ts}, where url is https://www.instagram.com/stories/<handle>/ or the Facebook Page URL.
- Keep alerts from the last 24 h in KV; GET /stories returns them with Cache-Control: max-age=60.

POST ALERTS (new posts from listed accounts and watched profiles; same opt-in listener)
- The listener also catches Instagram/Facebook "new post" notifications ("<handle> shared a post", "delte et opslag", "hat einen Beitrag geteilt"…) from watched profiles and listed sources. Activity on your own posts and posts inside groups don't count; a story match wins.
- Listed sources (public organization accounts) with "Check new posts from local venues for events" on (default; disclosed in the listener disclosure and on /privacy): the phone POSTs the notification text and its picture (≤1024 px JPEG) to /post-seen. The Worker accepts only listed eligible sources, rate-limits per IP, runs one Gemini extraction per source per 20 min (later reports get the cached verdict), never stores the picture, and queues an event as an inbox item (channel post). The inbox job treats it like an app draft: not trusted (any client could send one), so it publishes on confidence ≥0.92 or corroboration, otherwise an Issue.
- Event → no local alert (the normal event push follows once published). Not an event, or no answer → a local "New post from <name>" alert on the "New posts" channel (priority channel for priority sources), only for watched, favourite or priority sources. Tap fires the original notification's intent (opens the post), else the profile; action Mute source. Hide original only when our alert shows.
- Profiles the user added themselves: local "New post from" alert only; nothing leaves the phone.
- Business Discovery polling (when the Meta token exists) still polls every listed ig handle, so posts are caught for everyone even without phones; non-professional accounts fail with code 100/110 and are retried weekly.
- Per-source switches (source page, and "Notifications" in a watched profile's menu): New events / New stories / New posts, all on by default. Off removes that source's s_/st_ topic and drops its pushes and local alerts.

WATCH LIST AND GROUP POSTS (on the phone only; uses the same opt-in notification listener)
Watch a profile:
- Paste an Instagram or Facebook profile/Page link, or share a profile from the Instagram/Facebook app into EventBorg. The app extracts the handle (instagram.com/<handle>, facebook.com/<page>, profile.php?id=…) plus a display name used for matching (prefilled, editable; Facebook share links carry no name, so ask for it).
- Suggestions: the listed sources in sources.json that have ig/fb, added with one tap. Sources marked watch are pre-loaded (see WEB SOURCES).
- Pasted or shared profile links are saved without their query string (?igsh=, ?mibextid=…); profile.php keeps only id.
- Each entry: "Open profile" button, the tip "tap the bell and turn on Story notifications", a ✓ once its first story notification has been caught, Remove, Mute.
- Watched profiles behave like favourite sources for story alerts: an EventBorg notification plus a story-strip circle. Watching a listed source also subscribes st_<sourceId> (unless Story alerts is Off).
- Profiles the user adds stay on the phone and are never sent to the server. Only listed sources are relayed to other users.
Watch a group (Facebook has no group API):
- Paste a facebook.com/groups/<id|slug> link or share the group from the Facebook app. Store the group id/slug and name locally only. Onboarding: set that group's notifications to "All posts".
- Catch new-post notifications from watched groups: title/text, plus the picture from the notification extras if present. Hide the original (same setting as stories).
- On-device event heuristic: dates, times, weekdays, prices and EN/DA/DE keywords (event, koncert, fest, billetter, tickets, Veranstaltung …). Match → EventBorg alert "Possible event in <Group>" with the preview. No match → discarded silently.
- Alert actions: Open (fires the original notification's tap intent while the app still holds it, else opens the group in Facebook); "Add as event" sends the preview text and picture to /extract and shows the editable draft. Nothing from a group leaves the phone without that tap.
- "Group posts" section: possible-event alerts from the last 7 days, stored on the phone only (text plus a small picture, poster names stripped where recognisable), with the same actions; pruned after 7 days.
- Own notification channel "Group posts", tunable in Android settings.
Onboarding (part of the listener disclosure):
- Set Instagram's story notification channel to Silent in Android settings (deep link to Instagram's notification settings). Not Off: blocked notifications never reach the listener.
- Option: a separate Instagram/Facebook account used only for EventBorg follows.

DATA MODEL (short JSON keys, nulls omitted)
- id: stable hash of normalized title + date + venue
- t: title
- s, e: start and end, ISO 8601 with Europe/Copenhagen offset
- v: venue {n, addr, lat, lng, town}
- c: category
- tags[]
- p: price {free, min, max} in DKK
- url: signup/ticket link
- src[]: sources {id, kind, url}, where kind is one of web | api | ig_dm | ig_post | wa | fb | app
- org: organizer
- img: image URL (null for DM events)
- sum: summary {en, da}, ≤200 chars each
- desc: original text, ≤1500 chars
- lang, age
- st: status, one of ok | cancelled | moved
- upd: last updated
Categories (fixed list): music, nightlife, theatre_culture, exhibitions, food_drink, sports, family_kids, students, talks_learning, markets, outdoor_nature, festivals, community, business, other.

PIPELINE
fetch/inbox → parse → normalize (dates, venue aliases, price) → keep only events inside the municipality → dedupe → categorize → summarize (batched, cached) → diff against the previous run → publish → push.
- Dedupe: same event if same day AND start within ±90 min AND (title similarity ≥0.85 OR same URL). Merge them, preferring structured fields and keeping every source.
- Categorize: map the source's own categories plus keyword rules first; use the LLM only as a fallback.
- Drop: past events, non-events (ads, opening hours), and personal data.

PUSH RULES
- Event topics: all, c_<category>, s_<sourceId>. Send each new event once, with the condition "'all' in topics || 'c_X' in topics || 's_Y' in topics", so each device gets it at most once.
- Send a high-priority data message; the app builds the notification:
  - text: title, then "date · venue", plus a category icon
  - tap opens the event detail
  - actions: Save, Add to calendar
- Don't push: on a source's first ingest (silent backfill), for events more than 60 days away, or for updates to existing events.
- If one run finds more than 3 new events in a category, send one grouped push for that category instead.
- Story alerts use the topics st_all and st_<sourceId> and a separate Android notification channel, "Story alerts", which users can silence in system settings. A story alert is never merged with event pushes.
- Group-post alerts are local only, on their own channel "Group posts".
- Favourite, watched and priority sources get s_<id> and st_<id> topics in "Favourites only" mode, minus the per-source switches. Events from priority sources are never grouped and show on the "Priority sources" channel (high importance, sound).

ANDROID APP
Stack:
- Kotlin, Jetpack Compose + Material 3 (dynamic color, dark mode), single Activity, minSdk 26, latest stable compile/target SDK, manual DI.
- Default dependencies: AndroidX core/activity/lifecycle, Compose BOM, navigation-compose, kotlinx-serialization, OkHttp, Coil, DataStore, androidx.browser, profileinstaller, firebase-messaging. Add another only if it removes a lot of code or clearly improves the experience and the APK stays within the cap; log why. Avoid heavy frameworks (Hilt, Room, Retrofit) unless the gain is clear. Never add analytics, ads or tracking SDKs.
Lightweight:
- No background services, no WorkManager polling, no wakelocks. The only background entry points are FCM and the optional, system-bound notification listener.
- Events are cached as one JSON file, fetched on app open and on pull-to-refresh, using ETag/If-None-Match.
- Events that arrive by push are inserted into the cache immediately.
- Release APK target ≤6 MB (R8 full mode + resource shrinking), cold start <1 s, baseline profile.
Screens:
- Feed:
  - Story strip at the top: circles for sources and watched profiles that posted a story in the last 24 h (from GET /stories plus local detections), labelled "New story · might be an event". Tap opens the story in Instagram/Facebook (falls back to the browser). Long-press mutes that source.
  - Below it, sticky sections Today / Tomorrow / This weekend / Next week / Later. Compact cards: thumbnail or category art, title, time, venue, category chip, price or "Free", source badge (web/IG/WA…), ★ button.
- Filters (bottom sheet, saved between sessions): categories (multi-select), date presets + custom range, free only, town, source, favourites only.
- Search: instant, local, ignores Danish letters (ø/æ/å match o/ae/a).
- Detail: everything at a glance, no wall of text.
  - Shows: date/time, venue (tap opens maps), price, organizer, age, language, the 200-char summary with an expandable original text, and all sources.
  - Main button "Sign up / Tickets": opens url in a Custom Tab; falls back to the source URL.
  - Secondary actions: Add to calendar (CalendarContract insert intent, no permission needed), Share, Save.
- Saved: saved events, favourite categories, favourite sources. Users can star from any card, chip or source page.
- Sources: list with last-updated time and status, plus a "Suggest a source" form.
- Watching: watched profiles and groups, one-tap suggestions from sources.json, add by pasted link, onboarding tips (see WATCH LIST AND GROUP POSTS).
- Group posts: possible-event alerts from the last 7 days with Open and "Add as event".
- Add your event: explains the live ways in from GET /channels (Instagram DM to @eventborg only when IG_PUBLIC_INBOX is on, the WhatsApp number only when configured) plus sharing from any app, which always works.
- Settings:
  - Event notifications: All / Favourites only / Off (mapped to topic subscriptions).
  - Story alerts: Off / Favourite sources / All local venues (mapped to st_ topics).
  - "Check new posts from local venues for events" (on by default; see POST ALERTS).
  - "Detect stories and group posts from my Instagram/Facebook notifications" (opens the disclosure screen and the system access settings).
  - "Hide original notification" (on by default).
  - "Share story alerts from local venues", with the explanation "sends only the venue and the time".
  - UI language EN/DA, About/Privacy.
- Share target: accepts text, URLs and images from any app → calls /extract → shows an editable draft card → Submit. Instagram/Facebook profile and group links go to the watch list instead.
- Story alert notification: "<Source> posted a new story, might be an event". Tap opens the story; action: Mute source. Dedupe local and pushed alerts by sourceId within the cooldown window.
- Group post notification: "Possible event in <Group>" with the preview; actions Open and "Add as event".
Quality:
- Accessibility: content descriptions, 48 dp touch targets, works with TalkBack.
- Empty, error and offline states.
- On Android 13+, explain once why notifications are needed, then request the permission.
- Predictive back gesture.

MILESTONES
M1 Web ingest: verified sources.yaml (including ig/fb handles), adapters, pipeline, tests, Actions workflow, Pages output. Verify by running locally and printing the event count per source.
M2 Event push sender following the push rules; Worker app endpoints and static pages.
M3 Social inbox and story relay: webhooks (Instagram behind IG_PUBLIC_INBOX, WhatsApp once configured), KV, repository_dispatch, inbox job, auto-publish rules, Issue moderation, replies, Business Discovery polling, /story-seen, /stories and /channels, FCM from the Worker. Verify with the Meta webhook test tool once the keys exist.
M4 App core: feed (without the story strip), filters, search, detail, saved, sources, settings, cache.
M5 App push handling (Priority sources, New events, Story alerts, New posts and Group posts channels), post alerts and per-source notification switches, watch-list pre-load and priority sources, story strip, notification listener (primary story detection) with disclosure/onboarding, watch list (profiles and groups), group-post alerts, "Hide original notification", share target (including profile/group links), "Add your event" screen.
M6 Size and performance pass; README with exact setup steps for Firebase, GitHub secrets and Pages, Cloudflare, the Gemini key, the Meta app (Instagram/WhatsApp, publishing, privacy and data-deletion URLs, and the App Review steps needed before IG_PUBLIC_INBOX=true), plus a keep-alive so GitHub doesn't disable the schedule after 60 days without repo activity.
M7 Improvements: implement the best items from IDEAS.md within HARD LIMITS. List any that need my approval (see WORKING RULES) and stop before doing them.

IDEAS TO CONSIDER (optional starting points; judge value against size and cost)
- Weather badge on outdoor events (Open-Meteo: free, no key).
- Home-screen widget "Next up / Tonight".
- Map view of today's events by venue.
- Reminder before a saved event (inexact alarm, no extra permission).
- Subscribable calendar feeds (.ics per category) published next to events.json.
- German UI (border region) in addition to EN/DA.
- Quick filters such as "Happening now" and "Free this weekend".
