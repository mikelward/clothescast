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
- Per-install usage is counted in Firestore (`installs/<fid>`) so you
  can later enforce a free-trial limit — currently informational only,
  see `docs/TODO.md` "Enforce shared-key TTS trial limit".
- Firebase App Check (Play Integrity in release, Debug provider in
  debug) blocks scraping and modded clients without holding up
  legitimate users.

User-facing privacy disclosure for everything below lives in
`PRIVACY.md` → "Online TTS: shared key by default, your own key on
request".

## Setup checklist (~30 minutes, one-time)

You can share the same Firebase project as Firebase App Distribution
(see `docs/firebase-app-distribution.md`) or use a separate one — the
function only needs Firestore + App Check + Cloud Functions, none of
which conflict with FAD.

### 1. Pick (or create) the Firebase project

[console.firebase.google.com](https://console.firebase.google.com).
If you already use one for FAD or Crashlytics, reuse it. Otherwise
**Add project** — name is cosmetic.

You'll need the **Blaze (pay-as-you-go) plan** to deploy Cloud
Functions and Firestore. Real-world cost on a tiny user base is
pennies (Gemini API itself is the dominant cost, ~$0.003 per call,
covered by Google's free tier at low volume).

### 2. Add the Android app to Firebase

If FAD is already set up against this project, the Android app is
already registered — skip this step. Otherwise, in the project's
**Project settings** → **Your apps** → Android:

- **Package name**: `app.clothescast.debug` for debug builds,
  `app.clothescast` for release. Add both — they're separate apps
  from Firebase's perspective.
- **SHA-1**: required for Play Integrity. From the upload keystore:
  `keytool -list -v -keystore <upload.jks> -alias <alias>` → grab the
  `SHA1:` line. For debug builds, do the same against
  `~/.android/debug.keystore` (or your stable debug keystore from
  `docs/firebase-app-distribution.md`).

Download `google-services.json` from the project settings and drop
it at `app/google-services.json`. It's gitignored — per-developer,
never committed. Without this file the Firebase SDK no-ops at
runtime and the app falls back to BYOK semantics for Gemini, so
nothing breaks; it just means the shared path is off.

### 3. Enable Firestore

In the Firebase console: **Build → Firestore Database → Create
database**. Pick **Production mode** (the rules in
`firestore.rules` deny all client access; only the function writes
to it via the Admin SDK). Region is up to you — pick something close
to your function region (step 5).

### 4. Enable App Check

**Build → App Check**.

- For each Android app added in step 2, **Register** it.
- **Release app** (`app.clothescast`): choose **Play Integrity** as
  the provider. No further config needed once the SHA-1 from step 2
  is in.
- **Debug app** (`app.clothescast.debug`): choose **Play Integrity**
  too (it'll be paired with a Debug provider override at runtime —
  see step 8). Click into the app → **Manage debug tokens**; you'll
  add per-device tokens here in step 8.
- Once both are registered, click **Cloud Functions → Enforce**.
  Until you do this, the function still works but doesn't actually
  reject unsigned requests.

### 5. Set up the function locally

```sh
cd functions
npm install
npm install -g firebase-tools         # if you don't have it
firebase login
firebase use --add                    # pick the project from step 1
```

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
5. In the Firebase console → Firestore → `installs` collection, you
   should see a doc keyed by your Firebase Installation ID with
   `firstUseAt`, `count: 1`, `lastUseAt`.

Then paste a real Gemini key into Settings and Test Voice again:

6. Same Logcat filter — this time the request should go straight to
   `generativelanguage.googleapis.com` with `x-goog-api-key` set, and
   the Firestore `installs/<fid>` doc should **not** get a new
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
  -H "X-Install-Id: dev-test-install-001" \
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

## Operational notes

- **Cost**: Gemini Flash TTS is ~$0.003 per ~5 s clip. Cloud
  Functions free tier covers ~2M invocations/month; Firestore free
  tier covers 50K reads + 20K writes/day. At 1000 users × 2 daily
  insights = ~60K invocations/month — well within free tier on the
  infrastructure side; the Gemini bill is the only one that scales.
- **Limiting abuse**: App Check stops scrapers but doesn't cap
  per-install volume. The function already counts every successful
  call in `installs/<fid>.count` — wiring the limit (30 calls or
  30 days from first use, return 429) is tracked in
  `docs/TODO.md` under "Deferred to v2".
- **Rotating the Gemini key**: `firebase functions:secrets:set
  GEMINI_API_KEY` again, then redeploy. The function picks up the
  new value on cold start.
- **CI builds**: GitHub Actions builds don't carry
  `google-services.json` and don't set `GEMINI_PROXY_URL`, so the
  shared path is disabled on those APKs. That's intentional — CI is
  just the build pipeline, not a tester install. FAD-distributed
  debug APKs *do* get `google-services.json` (it's decoded from a
  GitHub Secret in the workflow), and they pick up
  `GEMINI_PROXY_URL` from the same env var setup.
