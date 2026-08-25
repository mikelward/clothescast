# Gemini TTS proxy setup

One-time setup so the Gemini voice works for users who haven't pasted
their own API key. A tiny Cloud Function holds your Gemini key, the
Android app calls it instead of Google directly, and Firebase App
Check verifies the request came from a genuine install of the app.

## What this gets you

- Users who select the Gemini voice in Settings can hear it
  immediately, no API key required. The function holds your developer
  key and forwards each TTS request to Google.
- Users who paste their own key in Settings continue to bypass the
  function entirely and pay their own Gemini bill.
- Per-install usage is counted in Firestore (`quota/<uid>`, keyed on
  the verified anonymous Firebase Auth uid) and
  capped at 5 successful syntheses per UTC day. Above the cap the
  function returns `429 daily_quota_exhausted`; the Android client
  surfaces a friendly "free TTS limit reached" message and the user
  can drop in their own Gemini key to bypass the proxy entirely. See
  `functions/README.md` → "Quota enforcement" for the doc shape and
  rollback semantics.
- Firebase App Check (Play Integrity in release, Debug provider in
  debug) blocks scraping and modded clients without holding up
  legitimate users.

User-facing privacy disclosure for everything below lives in
`PRIVACY.md` → "Online TTS: shared key by default, your own key on
request".

## Setup checklist (~30 minutes, one-time)

The function only needs Firestore + App Check + Cloud Functions, so it
can share a Firebase project with Crashlytics and Analytics or sit in
one of its own.

### 1. Pick (or create) the Firebase project

[console.firebase.google.com](https://console.firebase.google.com).
If you already use one for Crashlytics or Analytics, reuse it.
Otherwise **Add project** — name is cosmetic.

You'll need the **Blaze (pay-as-you-go) plan** to deploy Cloud
Functions and Firestore. Real-world cost on a tiny user base is
pennies (Gemini API itself is the dominant cost, ~$0.003 per call,
covered by Google's free tier at low volume).

### 2. Add the Android app to Firebase

If the app is already registered against this project, skip this step.
Otherwise, in the project's **Project settings** → **Your apps** →
Android:

- **Package name**: `app.clothescast.debug` for debug builds,
  `app.clothescast` for release. Add both — they're separate apps
  from Firebase's perspective.
- **SHA-1**: required for Play Integrity. From the upload keystore:
  `keytool -list -v -keystore <upload.jks> -alias <alias>` → grab the
  `SHA1:` line. For debug builds, do the same against
  `~/.android/debug.keystore`, or against the stable debug keystore
  behind the `DEBUG_KEYSTORE_BASE64` CI secret if you use one.

Download `google-services.json` from the project settings and drop
it at `app/google-services.json`. It's gitignored — per-developer,
never committed. Without this file the Firebase SDK no-ops at
runtime and the app falls back to BYOK semantics for Gemini, so
nothing breaks; it just means the shared path is off.

### 3. Enable Firestore

In the Firebase console, open **Firestore Database → Create
database**. Pick **Production mode** (the rules in
`firestore.rules` deny all client access; only the function writes
to it via the Admin SDK). Region is up to you — pick something close
to your function region (step 5).

### 4. Register apps in App Check, and enable anonymous sign-in

Find these products by name in the left sidebar — the category
headers Firebase groups them under (App Check currently sits under a
"Security" group) get reshuffled periodically, so navigate by product
name, not by category. There is no top-level "Build" menu.

**App Check** — register each app:

- For each Android app added in step 2, **Register** it.
- **Release app** (`app.clothescast`): choose **Play Integrity** as
  the provider. No further config needed once the SHA-1 from step 2
  is in.
- **Debug app** (`app.clothescast.debug`): choose **Play Integrity**
  too (it'll be paired with a Debug provider override at runtime —
  see step 8). Click into the app → **Manage debug tokens**; you'll
  add per-device tokens here in step 8.

You do **not** enforce App Check on Functions from the console. That
toggle only applies to *callable* (`onCall`) functions; our `tts` is a
plain HTTP (`onRequest`) function, which enforces App Check **in code**
— it reads the `X-Firebase-AppCheck` header and rejects the request if
`getAppCheck().verifyToken()` fails (see `functions/src/index.ts`). So
enforcement is always on for `tts` and there's nothing to flip here.

**Authentication** — enable anonymous sign-in:

- Open **Authentication → Sign-in method → Anonymous → Enable.** The
  app signs in anonymously to get the ID token it sends to the proxy
  (the function verifies it and keys the per-install daily quota on the
  `uid`); no other provider is needed. **If this is off, the shared
  voice silently falls back to device TTS.**
- **Enforce App Check on Authentication** to stop scripted
  anonymous-account farming (minting fresh `uid`s to reset the daily
  cap): in **App Check**, expand the **Authentication** product's
  metrics row and click **Enforce** (takes up to ~15 min to apply).
  Without it, App Check still gates the function but not account
  creation.
- *(Optional)* Auto-delete anonymous accounts inactive for 30+ days so
  stale quota identities don't pile up. This lives under
  **Authentication → Settings → User account management** and
  **requires upgrading to Firebase Authentication with Identity
  Platform.** The client re-signs in anonymously if a still-cached
  account gets deleted, so it's safe to enable.

### 5. Set up the function locally

```sh
cd functions
npm install
npm install -g firebase-tools         # if you don't have it
firebase login
firebase use --add                    # pick the project from step 1
```

Install the CLI through npm, **not** as the standalone binary. The
standalone build (firepit) prepends its own `node` and `npm` shims to
`PATH` and points npm's `script-shell` at a third shim, none of which
carry a shebang line — so the `predeploy` hook dies with
`/bin/sh: --: invalid option` before `tsc` ever runs. `firebase
--version` reports the same number either way; check for
`~/.cache/firebase/runtime` to tell them apart.

`firebase use --add` writes a `.firebaserc` entry — that file *is*
committed (it just pins the project ID), so other developers cloning
the repo can `firebase use <alias>` if you've set one up.

### 6. Set the Gemini API key as a secret

Get a Gemini API key from
[ai.google.dev](https://ai.google.dev/gemini-api/docs/api-key). Then:

```sh
firebase functions:secrets:set GEMINI_API_KEY
# paste the key when prompted; it's stored encrypted in Secret Manager
```

The function references it by name (`defineSecret("GEMINI_API_KEY")`
in `functions/src/index.ts`); the value never appears in the
deployed source.

### 7. Deploy

```sh
firebase deploy --only functions:tts,firestore
```

First deploy prompts to enable a few APIs in your GCP project
(Artifact Registry, Cloud Build, Cloud Run); click through. After
that you'll see a URL like:

```
Function URL (tts(us-central1)): https://us-central1-<projectId>.cloudfunctions.net/tts
```

That's your `GEMINI_PROXY_URL`. Save it.

### 8. Point the Android app at the function

Two ways, pick whichever fits your workflow:

**`local.properties`** (recommended for `./gradlew assembleDebug`):

```properties
geminiProxyUrl=https://us-central1-<projectId>.cloudfunctions.net/tts
```

**Env var** (recommended for CI):

```sh
export GEMINI_PROXY_URL=https://us-central1-<projectId>.cloudfunctions.net/tts
```

Either way the value lands in `BuildConfig.GEMINI_PROXY_URL`; the app
reads it at runtime. Leaving both unset (the CI default when the env
var isn't exported) makes the shared path silently disabled — the app
behaves as BYOK-only, no error.

### 9. Register a debug App Check token

On the device or emulator you're testing with, run the app once with
the debug variant. Filter Logcat for `com.google.firebase.appcheck`
and find a line like:

```
Enter this debug secret into the allow list in the Firebase Console
for your project: 12345678-90ab-cdef-1234-567890abcdef
```

Copy that token, then in Firebase Console → **App Check** → click
the debug app → **Manage debug tokens** → **Add debug token**. Paste
the secret, give it a name ("Mikel's Pixel 8"). Save.

Without a registered debug token, every TTS request from the debug
build will get a 401 back from the function. Release builds use Play
Integrity and don't need this step.

## Verifying it works

End-to-end check after all the above:

1. Open the app's debug build with no own Gemini key set.
2. Settings → Voice → pick **Gemini** as the engine.
3. Tap **Test voice**. You should hear audio.
4. Filter Logcat for `GeminiTtsClient` (or the proxy hostname) and
   confirm the request hit your function URL, **not**
   `generativelanguage.googleapis.com`.
5. In the Firebase console → Firestore → `quota` collection, you
   should see a doc keyed by your anonymous Firebase Auth uid with
   `firstUseAt`, `count: 1`, `lastUseAt`, `dayKey: "YYYY-MM-DD"`,
   `dayCount: 1`.

Then paste a real Gemini key into Settings and Test Voice again:

6. Same Logcat filter — this time the request should go straight to
   `generativelanguage.googleapis.com` with `x-goog-api-key` set, and
   the Firestore `quota/<uid>` doc should **not** get a new
   increment (BYOK never touches the proxy).

## Running the function locally (emulator)

For iterating on `functions/src/index.ts` without redeploying:

```sh
cd functions
npm run serve       # starts functions + firestore emulators
```

It prints local URLs like
`http://127.0.0.1:5001/<projectId>/us-central1/tts`. Curl-test:

```sh
curl -i -X POST "http://127.0.0.1:5001/<projectId>/us-central1/tts" \
  -H "X-Firebase-AppCheck: <debug token>" \
  -H "Authorization: Bearer <anonymous ID token>" \
  -H "X-Gemini-Model: gemini-2.5-flash-preview-tts" \
  -H "Content-Type: application/json" \
  -d '{ "contents":[{"parts":[{"text":"Hello"}]}],
        "generationConfig":{"responseModalities":["AUDIO"],
        "speechConfig":{"voiceConfig":{"prebuiltVoiceConfig":{"voiceName":"Despina"}}}} }'
```

To make the Android app talk to the emulator, point `geminiProxyUrl`
at the local URL above. The Firebase emulator skips App Check
verification by default (since the SDK can't reach the real Firebase
backend), so a debug token isn't strictly required while emulating —
but using one is closer to production behaviour.

## Rolling out the anonymous-ID migration

The quota identity changed from a client-chosen `X-Install-Id` header to
a server-verified anonymous Firebase Auth `uid`. To avoid breaking the
free voice for anyone mid-rollout, both client and function speak both
dialects during the transition, so **the function deploy, the app
release, and the Firebase config can happen in any order**:

- **Client** sends *both* the legacy `X-Install-Id` and an
  `Authorization: Bearer <idToken>` on every shared-key request. If
  anonymous sign-in is unavailable it sends the FID alone.
- **Function** prefers the verified `uid` and falls back to
  `X-Install-Id` only when there's no Bearer token.

So an old app hits the new function (FID fallback) and a new app hits an
old function (still reads the FID) without anyone dropping to device TTS.

One step is *not* order-independent: **enable Anonymous sign-in (step 4)
before the new app ships**, or the client can't get an ID token (it
degrades to the spoofable FID path, which still works but doesn't gain
the protection).

⚠️ **The fallback keeps the spoofing hole open.** A client can omit the
Bearer token and rotate `X-Install-Id` to evade the cap for as long as
the fallback exists. Close it in a **cleanup phase** once old app
versions have aged out: drop the `X-Install-Id` send from the client and
delete the fallback branch in `functions/src/index.ts` (then the
`missing_identity` path becomes `missing_auth_token` again). Only then is
the cap actually tamper-resistant.

## Operational notes

- **Cost**: Gemini Flash TTS is ~$0.003 per ~5 s clip. Cloud
  Functions free tier covers ~2M invocations/month; Firestore free
  tier covers 50K reads + 20K writes/day. At 1000 users × 2 daily
  insights = ~60K invocations/month — well within free tier on the
  infrastructure side; the Gemini bill is the only one that scales.
- **Limiting abuse**: App Check stops scrapers and modded clients; a
  transactional cap of 5 successful syntheses per UTC day, keyed on the
  verified anonymous Auth `uid`, stops a single install from melting the
  budget. Because the `uid` is minted and signed by Firebase Auth (not a
  client-supplied header), a modified client can't rotate it to reset the
  cap — provided App Check is enforced on Authentication (step 4) so it
  can't farm fresh anonymous accounts either. Above the cap the function
  returns `429 daily_quota_exhausted` with a `resetAtUtc` timestamp; see
  `functions/README.md` → "Quota enforcement" for the doc shape and the
  rollback-on-failure semantics.
- **Rotating the Gemini key**: `firebase functions:secrets:set
  GEMINI_API_KEY` again, then redeploy. The function picks up the
  new value on cold start.
- **CI builds**: GitHub Actions builds don't carry
  `google-services.json` and don't set `GEMINI_PROXY_URL`, so the
  shared path is disabled on those APKs. That's intentional — CI is
  just the build pipeline, not a tester install. **No CI debug artifact
  has the shared path enabled**, for either of two reasons:

  - `GEMINI_PROXY_URL` is passed only to the `deploy` job's
    `Bundle release AAB` step. `assembleDebug` never receives it, so
    `BuildConfig.GEMINI_PROXY_URL` is blank in every `app-debug-apk`
    and the planner falls back to BYOK. Only the Play release build
    gets the proxy URL.
  - `google-services.json` is decoded from a repository secret, which
    fork PRs never receive and a repo without `GOOGLE_SERVICES_JSON`
    set never has. Same-repository builds with the secret configured
    do get the file — but per the point above, that alone isn't enough
    to enable the shared path.
