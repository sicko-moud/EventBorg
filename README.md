# EventBorg

Every public event in Sønderborg Municipality (Sønderborg, Als, Nordborg, Augustenborg, Gråsten, Broager…) in one Android app. Events come in by themselves from websites, ticket APIs and social media. They are deduplicated and categorized, and new ones arrive as a push. Free, no login, no ads, no tracking. **Not an official municipal app.**

**Install:** [eventborg.apk (latest release)](https://github.com/sicko-moud/EventBorg/releases/latest/download/eventborg.apk). Android 8.0+, about 2.5 MB. Allow "Install unknown apps" for your browser when Android asks.

| Part | Where | Runs on |
|---|---|---|
| Pipeline (fetch → parse → dedupe → categorize → publish → push) | `pipeline/`, `sources.yaml`, `tests/` | GitHub Actions, every 15 min |
| Data (`events.json`, `sources.json`, state) | orphan branch `data` | GitHub Pages |
| Worker (share/extract, inbox webhooks, story relay, static pages) | `worker/` | Cloudflare Workers (free) |
| App | `android/` | Kotlin + Compose |

Everything runs on free tiers. Secrets never go in the repo: locally they live in the gitignored `.env` (see `.env.example`), in CI they are GitHub Actions secrets, and in the Worker they are Worker secrets.

---

## Setup

Do the steps in this order. Each one names exactly where its value goes. Make random values with:

```powershell
python -c "import secrets; print(secrets.token_hex(32))"
```

### 1. GitHub repo, Actions and Pages

1. Fork or push this repo as a **public** repo (Actions minutes are free on public repos).
2. **Settings → Actions → General**: allow all actions. The workflows set their own token permissions, so the default is fine.
3. **Actions → pipeline → Run workflow** once. The first run creates the `data` branch.
4. **Settings → Pages**: Source **Deploy from a branch**, Branch **`data`**, folder **`/ (root)`**, then **Save**. After about a minute, `https://<user>.github.io/EventBorg/events.json` loads.
5. If your user or repo name differs, change it in three places: `DATA_URL` in `android/app/build.gradle.kts`, and `GH_REPO` and `PAGES_URL` in `worker/wrangler.toml`.

Secrets and variables live under **Settings → Secrets and variables → Actions**. You can also set them from the command line; the value is read from stdin:

```powershell
gh secret set NAME
```

| Kind | Name | Value |
|---|---|---|
| secret | `CONTACT_EMAIL` | contact address, sent in the crawler User-Agent |
| secret | `GEMINI_API_KEY` | step 2 |
| secret | `TICKETMASTER_KEY` | step 3 |
| secret | `FCM_SERVICE_ACCOUNT` | step 4, the whole JSON |
| secret | `GOOGLE_SERVICES_JSON` | step 4, the whole JSON |
| secret | `INBOX_TOKEN` | random value, the same one as in the Worker |
| secrets | `EB_KEYSTORE_B64`, `EB_KEYSTORE_PASSWORD`, `EB_KEY_ALIAS`, `EB_KEY_PASSWORD` | step 6 |
| variable | `WORKER_URL` | `https://eventborg.<subdomain>.workers.dev` (step 5) |
| variables (optional) | `GEMINI_MODEL`, `GEMINI_FALLBACK_MODEL`, `LLM_MAX_CALLS`, `LLM_MAX_SECONDS` | defaults are in `pipeline.yml` |

### 2. Gemini API key (free tier)

1. Open <https://aistudio.google.com/apikey> and sign in with a Google account.
2. Click **Create API key**, then pick or create a Google Cloud project. **Do not turn on billing for that project**; with billing it stops being free.
3. Put the key in the GitHub secret `GEMINI_API_KEY` and the Worker secret `GEMINI_API_KEY`.

The pipeline sends 10–20 items per call and caches results by content hash. `LLM_MAX_CALLS` and `LLM_MAX_SECONDS` keep each run inside the free limits.

### 3. Ticketmaster key (free)

1. Register at <https://developer-acct.ticketmaster.com/user/register>.
2. Open **My Apps** and copy the **Consumer Key**.
3. Put it in the GitHub secret `TICKETMASTER_KEY`.

### 4. Firebase (push messages)

1. Open <https://console.firebase.google.com> and click **Create a project** (Spark plan, free). **Turn Google Analytics off** when asked.
2. **Project overview → Add app → Android**, package name **`app.eventborg`**, then **Register app** and **Download google-services.json**. Skip the SDK steps; the build reads the file directly.
3. Copy the file to `android/app/google-services.json` (gitignored) for local builds, and store it in the GitHub secret:

```powershell
Get-Content android/app/google-services.json -Raw | gh secret set GOOGLE_SERVICES_JSON
```

4. **Project settings → Service accounts → Generate new private key**. Save the file as `firebase-service-account.json` in the repo root (gitignored). Then store it twice:

```powershell
Get-Content firebase-service-account.json -Raw | gh secret set FCM_SERVICE_ACCOUNT
```

```powershell
Get-Content firebase-service-account.json -Raw | npx --prefix worker wrangler secret put FCM_SERVICE_ACCOUNT --config worker/wrangler.toml
```

5. **Cloud Messaging**: "Firebase Cloud Messaging API (V1)" must show **Enabled** (the default for new projects).

Without `google-services.json`, the app still builds and runs, just without push.

### 5. Cloudflare Worker (free)

1. Sign up at <https://dash.cloudflare.com/sign-up>. Under **Workers & Pages**, pick a `workers.dev` subdomain.
2. Deploy from the repo:

```powershell
cd worker; npm ci; npx wrangler login
```

```powershell
npx wrangler kv namespace create KV
```

   Put the printed `id` into `[[kv_namespaces]]` in `wrangler.toml`.

3. Set the secrets. Each command asks for the value:

| Secret | Value |
|---|---|
| `GEMINI_API_KEY` | step 2 |
| `FCM_SERVICE_ACCOUNT` | step 4 |
| `SIGNING_KEY` | random value |
| `INBOX_TOKEN` | random value, the same as the GitHub secret |
| `META_VERIFY_TOKEN` | random value (step 7) |
| `GH_TOKEN` | fine-grained PAT: <https://github.com/settings/personal-access-tokens/new>, **Only select repositories →** this repo, permission **Contents: Read and write**. Used only for `repository_dispatch` |
| `META_APP_SECRET`, `META_TOKEN` | step 7 |
| `WA_TOKEN` | step 8 (optional) |
| `CONTACT_EMAIL` | optional, shown on the static pages |

```powershell
npx wrangler secret put SIGNING_KEY
```

4. Deploy:

```powershell
npx wrangler deploy
```

   Copy the printed URL into the GitHub variable `WORKER_URL`, into `WORKER_URL` in `android/app/build.gradle.kts`, and into `.env`.

5. Check that `<worker>/privacy`, `<worker>/data-deletion`, `<worker>/organizers` and `<worker>/channels` all load.

The Worker's cron (every minute) only flushes queued inbox items into one `repository_dispatch`, at most once per 60 s.

### 6. Android release signing

Every APK must be signed with the same key, or installed apps can't update. **Back up the keystore and its passwords somewhere safe (a password manager). If they are lost, every user has to uninstall and reinstall.**

1. Create the key once, with the `keytool` from Android Studio's JBR (`C:\Program Files\Android\Android Studio\jbr\bin`):

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -storetype PKCS12 -keystore $HOME\.eventborg\eventborg-release.jks -alias eventborg -keyalg RSA -keysize 4096 -validity 12000 -dname "CN=EventBorg, C=DK"
```

2. Add `EB_KEYSTORE` (path), `EB_KEYSTORE_PASSWORD`, `EB_KEY_ALIAS` and `EB_KEY_PASSWORD` to `.env`. A PKCS12 keystore uses the store password for the key too.
3. Set the CI secrets:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\.eventborg\eventborg-release.jks")) | gh secret set EB_KEYSTORE_B64
```

   Then set `EB_KEYSTORE_PASSWORD`, `EB_KEY_ALIAS` and `EB_KEY_PASSWORD` with `gh secret set`.

Without these secrets (for example on forks), CI signs with a throwaway debug key. A release tag fails instead.

**Publish a release:** bump `versionCode`/`versionName` in `android/app/build.gradle.kts`, then:

```powershell
git tag v0.6.0; git push origin v0.6.0
```

The `android` workflow builds the signed APK and attaches it to a GitHub Release as `eventborg.apk` with a `.sha256`. The install link at the top always points to the newest one.

### 7. Meta app: Instagram (Business Discovery, optional public inbox)

What runs with Standard Access (no App Review): polling public posts of listed venues through Business Discovery, and the data-deletion callback. Public DMs and story mentions need App Review; see step 9.

1. **Instagram account**: create @eventborg. In the Instagram app go to **Settings → Account type and tools → Switch to professional account → Business**.
2. **Facebook Page**: create a Page "EventBorg" at <https://www.facebook.com/pages/create>. Under **Page settings → Linked accounts → Instagram → Connect account**, link @eventborg.
3. **Meta app**: open <https://developers.facebook.com/apps> and click **Create app**. Choose **Other** as the use case, then app type **Business**, name "EventBorg", and your business portfolio, or none.
4. **Add the product**: in the app dashboard, **Add product → Instagram → Set up**. Choose **API setup with Facebook login**.
5. **App settings → Basic**:
   - **Privacy policy URL**: `<worker>/privacy`
   - **User data deletion → Data deletion callback URL**: `<worker>/meta/deletion`
   - **Terms of service URL** (optional): `<worker>/organizers`
   - App icon (1024×1024), category **Lifestyle**, contact email. Then **Save changes**.
   - Copy the **App secret** into the Worker secret `META_APP_SECRET`.
6. **Access token** (a Page token that doesn't expire):
   1. Open the **Graph API Explorer** (<https://developers.facebook.com/tools/explorer>). Select the app and **User Token**, and add the permissions `pages_show_list`, `pages_read_engagement`, `pages_manage_metadata`, `instagram_basic`, `instagram_manage_messages`, `business_management`. Click **Generate Access Token** and allow the Page and @eventborg.
   2. Open the **Access Token Debugger** (<https://developers.facebook.com/tools/debug/accesstoken>), paste the token, click **Extend Access Token**, and copy the long-lived token.
   3. Back in the Explorer, with the long-lived token, run `GET me/accounts?fields=name,access_token,instagram_business_account`.
   4. The Page's `access_token` (the Debugger shows it **Never** expires) goes into the Worker secret `META_TOKEN`. `instagram_business_account.id` goes into `IG_USER_ID` in `wrangler.toml`.
7. **Webhooks** (only needed once the public inbox or WhatsApp goes live, but you can set them up now):
   1. Go to **Instagram → API setup with Facebook login → Configure webhooks**. Callback URL: `<worker>/meta/webhook`. Verify token: the `META_VERIFY_TOKEN` value. Click **Verify and save**.
   2. Subscribe to the field **`messages`**. It carries DMs, and story mentions as `story_mention` attachments.
   3. Install the app on the Page with the Page token. In the Explorer, run `POST {page-id}/subscribed_apps?subscribed_fields=messages`. If Meta rejects that field for the Page, use `feed`: the point is only that the app is installed on the Page.
   4. In the Instagram app on @eventborg: **Settings → Messages and story replies → Message controls → Connected tools → Allow access to messages: On**.
   5. Use the **Test** button next to `messages` to send a sample. The Worker answers 200; with `IG_PUBLIC_INBOX="false"` the event is acknowledged and dropped.
8. Run `npx wrangler deploy` again after changing `wrangler.toml`.

The Worker checks `X-Hub-Signature-256` with `META_APP_SECRET` on every webhook POST.

### 8. WhatsApp Cloud API (optional)

WhatsApp is off until `WA_TOKEN`, `WA_PHONE_ID` and `WA_NUMBER` all exist. Until then it is not mentioned anywhere.

1. In the same Meta app, **Add product → WhatsApp → Set up**, and choose or create a WhatsApp Business account.
2. **WhatsApp → API Setup → Add phone number**. Use a dedicated number that has no WhatsApp account, and verify it by SMS or call. The display name "EventBorg" needs Meta's approval.
3. Copy the **Phone number ID** into `WA_PHONE_ID`, and the number in international format (digits only, e.g. `4512345678`) into `WA_NUMBER`. Both go in `wrangler.toml`.
4. **Permanent token**: go to <https://business.facebook.com/settings>, then **Users → System users → Add** (role Admin). Under **Assign assets**, add the app (Full control) and the WhatsApp account (Full control). Click **Generate new token**, pick the app, choose expiry **Never**, and add the permissions `whatsapp_business_messaging` and `whatsapp_business_management`. Store the token in the Worker secret `WA_TOKEN`.
5. **WhatsApp → Configuration → Webhook**: same callback URL and verify token as step 7. Under **Webhook fields**, subscribe to **`messages`**.
6. **Publish the app** (step 9.4). Webhooks only arrive for a published app.
7. Run `npx wrangler deploy`. `<worker>/channels` now lists `wa`.

Incoming messages are free. Replies are sent only inside the 24 h customer-service window (free), at most one per item, with a hard cap of 900 per month.

### 9. Before setting `IG_PUBLIC_INBOX = "true"`

DMs and story mentions from the public are data from people without a role on the app. Meta delivers those webhooks only to a **published** app that has been **approved in App Review**.

1. **Business verification**: go to <https://business.facebook.com/settings>, then **Security Center → Start verification**. This needs a registered business or organisation, with its documents and a matching website or domain.
2. **App Review → Permissions and features**: request **Advanced Access** for `instagram_basic`, `instagram_manage_messages`, `pages_manage_metadata`, `pages_show_list` and `business_management`. For each one:
   - Describe the use: "People forward event posts to @eventborg by DM. We extract the event, publish it in a public event list, and reply once."
   - Attach a screencast of the full flow. Send a DM with a post to @eventborg, show the Worker's reply ("Thanks, we're checking it"), the moderation Issue, and the event in the app.
   - Link the privacy policy and data-deletion URLs.
3. **Data Use Checkup** and **App Review → Requests**: answer the data-handling questions. EventBorg stores only salted sender hashes, never media, and deletes on request.
4. **Publish**: in the top bar of the app dashboard, set **App mode → Live**. This needs the Basic settings from step 7.5 to be complete.
5. Once approved: set `IG_PUBLIC_INBOX = "true"` in `worker/wrangler.toml` and run `npx wrangler deploy`. `/channels`, the static pages and the app's "Add your event" screen then show the Instagram DM option.

---

## Running and operating

**Locally**: Python 3.12, plus Node 24 for the Worker tests.

```powershell
python -m venv .venv; .venv\Scripts\pip install -r requirements.txt
```

```powershell
.venv\Scripts\python -m pipeline run --out out
```

```powershell
.venv\Scripts\python -m pytest -q
```

```powershell
node --test worker/test/worker.test.js
```

The run prints the event count per source and writes `out/events.json` and `out/sources.json`. The Android app builds in CI (`android` workflow, which also runs the Kotlin unit tests). Put `[smoke]` in a commit message to also run the emulator smoke test, with screenshots and cold-start timing in the job summary. To build locally you need JDK 21 and the Android SDK:

```powershell
cd android; .\gradlew assembleRelease
```

**Moderation**: inbox items that don't meet the auto-publish rules become GitHub Issues. Edit the YAML in the Issue if needed, then add the label `approve`. The next run, within about a minute, publishes it. Suggested sources arrive as Issues labelled `source`.

**Adding a source**: add an entry to `sources.yaml`, with type `tribe`, `ical`, `jsonld`, `sitemap`, `html_llm`, `ticketmaster` or `none` for social-only. A new source's first ingest is silent (no push).

**Keep-alive**: GitHub turns off scheduled workflows after 60 days without repository activity. The pipeline re-enables itself through the API once a day (03:00 UTC), which resets that timer without commits. If it was turned off anyway, open **Actions → pipeline → Enable workflow**.

**Free-tier limits worth knowing**:

| Service | Free tier |
|---|---|
| Actions | unlimited minutes on public repos, but a 15 min cron can start late |
| Pages | 100 GB per month |
| Workers | 100k requests per day, 10 ms CPU per request |
| KV | 1k writes per day |
| Gemini | per-model rate limits; the run budget stays under them |
| FCM | free |

## Privacy

There are no accounts, analytics, ads or tracking. The app's watch list, group posts and story detections stay on the phone. The processors are Meta, Google (Firebase, Gemini), Cloudflare and GitHub; see `<worker>/privacy`. Deletion requests: `<worker>/data-deletion`.
