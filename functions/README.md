# clothescast Cloud Functions

Single function today: `tts` — the Gemini TTS proxy backing the Android
app's shared-key path. Holds the developer's Gemini API key, verifies
Firebase App Check and an anonymous Firebase Authentication ID token on
each request, counts usage per verified `uid` in Firestore, and forwards
to Gemini.

**Setup guide for the whole proxy (Firebase project, App Check,
function deploy, Android wiring):**
**[`docs/gemini-tts-proxy.md`](../docs/gemini-tts-proxy.md)**.

User-facing privacy disclosure: `PRIVACY.md` → "Online TTS: shared
key by default, your own key on request".

## Quick commands

```sh
npm install                                     # one-time
firebase functions:secrets:set GEMINI_API_KEY   # one-time (see setup guide)
firebase functions:secrets:set QUOTA_HASH_KEY   # one-time: any long random string
```

## Run locally

```sh
npm run serve     # starts functions + firestore emulators, prints local URL
```

Exercise the local endpoint with a debug App Check token (register it
in the Firebase Console first) and an anonymous ID token (mint one from
the Auth emulator, or sign in anonymously against the real project):

```sh
curl -i -X POST "http://127.0.0.1:5001/<project-id>/us-central1/tts" \
  -H "X-Firebase-AppCheck: <debug token>" \
  -H "Authorization: Bearer <anonymous ID token>" \
  -H "X-Gemini-Model: gemini-2.5-flash-preview-tts" \
  -H "Content-Type: application/json" \
  -d '{ "contents":[{"parts":[{"text":"Read the following: hello"}]}],
        "generationConfig":{"responseModalities":["AUDIO"],
        "speechConfig":{"voiceConfig":{"prebuiltVoiceConfig":{"voiceName":"Despina"}}}} }'
```

## Deploy

```sh
npm run deploy
```

## Quota enforcement

Each install gets 5 successful syntheses per UTC calendar day, keyed on
the anonymous Firebase Auth `uid` from the verified ID token (not a
client-supplied header — a modded client can't pick its own bucket).
During the client rollout, requests without a Bearer token fall back to
the legacy client-chosen `X-Install-Id` header so pre-switch app
versions keep working; that fallback is spoofable and is removed once
old app versions age out (see `docs/gemini-tts-proxy.md` → rollout).
The reservation is transactional:

1. Pre-flight transaction reads `quota/<uid>`; if today's
   `dayCount` is already at 5, the request returns
   `429 { "error": "daily_quota_exhausted", "limit": 5,
   "resetAtUtc": "<next-UTC-midnight>" }` without calling Gemini.
2. Otherwise we increment `dayCount` (and lifetime `count` +
   `lastUseAt`) before the upstream call.
3. On a non-success upstream status — or a fetch failure — we
   transactionally decrement so a Gemini hiccup doesn't burn a
   slot.

A request sent with `X-TTS-Purpose: preview` (Settings voice previews)
counts against a separate `quota/<key>__preview` doc capped at 10 per
day instead, and its 429 body carries `"purpose": "preview"`. The
header is trusted as sent: mislabeling casts as previews gains at most
10 more clips a day.

Successful responses carry `X-Daily-Quota-Limit: 5` and
`X-Daily-Quota-Remaining: <n>` headers. The Android client
(`core/data/.../tts/GeminiTtsClient.kt`) throws
`GeminiTtsDailyQuotaExhaustedException` on the 429, which surfaces
in the Voice settings preview Toast as "Free TTS limit reached for
today. Add your own Gemini key in Settings for unlimited use."

Firestore document shape for an anonymous caller:

```
quota/<uid> {
  firstUseAt: Timestamp,
  lastUseAt:  Timestamp,
  count:      number,   // lifetime
  dayKey:     string,   // "YYYY-MM-DD" UTC
  dayCount:   number,   // successful calls within dayKey
}
```

### Google-signed-in callers

A Google sign-in token (`firebase.sign_in_provider == "google.com"`) is
counted per Google account, not per Firebase uid, so the cap is shared
across a person's devices and survives deleting and recreating the
account. The bucket is an HMAC-SHA256 of the Google account ID, keyed by
the `QUOTA_HASH_KEY` secret, and the doc holds nothing else:

```
quota/g_<hex> {
  dayKey:   string,     // "YYYY-MM-DD" UTC
  dayCount: number,     // successful calls within dayKey
  expireAt: Timestamp,  // next UTC midnight
}
```

A Firestore TTL policy on `quota.expireAt` (`firestore.indexes.json`,
deployed with `firebase deploy --only firestore`) deletes the doc after its
day ends; TTL deletion typically runs within 24 hours of expiry. Anonymous
docs carry no `expireAt` and are unaffected.

The limit comes from the account's `ttsDailyLimit` custom claim: absent
means the default (5), a whole number is that cap, and `"unlimited"`
skips the count (the project-level Gemini quota and billing alert bound
it instead). A malformed claim reads as the default. Set it with:

```sh
npm run build
node scripts/set-tts-limit.js <email> <limit|unlimited|default>
```

using Application Default Credentials for the project. The change applies
at the app's next ID-token refresh, within an hour. Claims on anonymous
callers are ignored.

Once the proxy stops accepting older app versions (the `TODO.md` cutover),
delete what they left behind: every anonymous Firebase user, every `quota` doc
without `expireAt`, and every doc in `installs` (the first proxy's per-install
counters, unused since they moved to `quota`), as `docs/delete-account.md`
promises.

```sh
npm run build
node scripts/purge-legacy-identities.js --project <id>           # dry run
node scripts/purge-legacy-identities.js --project <id> --apply   # deletes
```

Run `--apply` only after the cutover is deployed **and** the Anonymous
provider is disabled (Firebase console → Authentication → Sign-in method →
Anonymous). Older app versions call `signInAnonymously()` before they reach
the proxy, so while the provider is on, an old install opened after the purge
creates a new anonymous user even though the proxy then rejects it. `--project` is required, so the target is
never whichever project Application Default Credentials happen to resolve
to. It prints the project and counts, never uids, and paces user deletes to
the Auth API's 1 request a second.

If Firestore is unreachable, the function **fails open** (forwards
the call without recording it) so a backend hiccup doesn't deny
service. The trade-off is that a sustained outage lets a single
uid exceed the daily cap — acceptable given how expensive
Gemini TTS is relative to a Firestore read.

## Weather proxy

`weather` forwards one Google Weather hourly-forecast page
(`/v1/forecast/hours:lookup`) for a Google-signed-in caller of a genuine app
build, spending the developer's Maps Platform key. It takes `POST` with the
fields of Google's query as a form body (`application/x-www-form-urlencoded`),
never a query string, because Cloud Run's request log records the full URL and
the coordinate must stay out of it. It also needs the `X-Firebase-AppCheck`
header and `Authorization: Bearer <ID token>`. It:

- refuses an anonymous token (`403 sign_in_required`);
- forwards only the known fields (`weather-query.ts`): the coordinate, up to
  168 hours, pages of up to 24, `METRIC`, and the next-page token;
- caps each account at 60 upstream calls per UTC day in
  `weatherQuota/g_<hex>` (day and count only, TTL on `expireAt`);
- never logs or stores the coordinate or the upstream URL.

One-time setup: enable the Weather API in the same project as `tts` (the
app derives the weather URL from `GEMINI_PROXY_URL`'s host, so the project
ID is the `<projectId>` in `https://us-central1-<projectId>.cloudfunctions.net`),
create a Maps Platform API key restricted to the Weather API, store it as a
secret, then deploy alongside Firestore (the TTL policy). Use the npm-installed
CLI and install this directory's dependencies first; see
`docs/gemini-tts-proxy.md` steps 5 and 7 for login and CLI pitfalls.

```sh
npm ci
firebase functions:secrets:set GOOGLE_WEATHER_API_KEY
firebase deploy --only functions:weather,firestore
```

If the deploy couldn't set the public invoker (it warns), grant it by hand;
the function still enforces App Check, Google sign-in and the daily cap:

```sh
gcloud run services add-iam-policy-binding weather \
  --region us-central1 --project <projectId> \
  --member allUsers --role roles/run.invoker
```

What the app's bug report shows for `GoogleWeather`:

- HTML `404 Page not found`: the function isn't deployed in that project.
- HTML `401 Unauthorized` (and "The access token could not be verified" in
  `firebase functions:log --only weather`): the invoker binding above is
  missing.
- JSON `{"error":"…"}`: the function itself refused (App Check, sign-in or
  quota), as listed above.

## Dependency pins

`package.json` carries an `overrides` block that holds three transitive
dependencies inside their current majors. Without it, the weekly npm-update
batch (`.github/workflows/npm-update.yml`) trips its own no-majors rule and
can never open a PR: the `@types/*` packages reference `@types/node` as `*`,
and newer `fast-xml-parser` 5.x minors cross a major underneath.

Each override is a deferred migration, not a permanent fact. Drop one when
you are ready to take the crossing deliberately:

- `@types/node` `^22` — matches `engines.node`. Move both together when the
  Functions runtime moves.
- `fast-xml-parser` `~5.8.0` — newer 5.x minors moved their `entities`
  dependency across a major; held rather than forcing the sub-dependency
  against its declared range. Revisit on the next `firebase-admin` bump.
- `fast-xml-builder` `~1.2.0` — 1.3.x moved its own `xml-naming` dependency
  from `^0.1.0` to `^0.3.0`, which a caret pins to the minor on a 0.x line,
  so the batch reads it as breaking. Pinning `fast-xml-parser` does not
  constrain the edges beneath it, which is why this needs a pin of its own
  rather than a tighter one above. Drop it with `fast-xml-parser`.

Express is no longer pinned — that migration is done. `firebase-functions`
v7.3.2 declares `express ^5.2.1`, so express and `@types/express` resolve to
5 and holding them at 4 would have forced the SDK against its own declared
range. The handler was read against express 5's breaking changes first: it
sets status through `res.status(...)`, never the removed
`res.json(status, body)` / `res.send(status)` overloads; it registers a
single bare handler, so there are no route patterns for path-to-regexp 8 to
reject; and it reads only `req.method`, `req.header(...)` and `req.body`,
none of which changed.
