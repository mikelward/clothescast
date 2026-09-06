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

Successful responses carry `X-Daily-Quota-Limit: 5` and
`X-Daily-Quota-Remaining: <n>` headers. The Android client
(`core/data/.../tts/GeminiTtsClient.kt`) throws
`GeminiTtsDailyQuotaExhaustedException` on the 429, which surfaces
in the Voice settings preview Toast as "Free TTS limit reached for
today. Add your own Gemini key in Settings for unlimited use."

Firestore document shape:

```
quota/<uid> {
  firstUseAt: Timestamp,
  lastUseAt:  Timestamp,
  count:      number,   // lifetime
  dayKey:     string,   // "YYYY-MM-DD" UTC
  dayCount:   number,   // successful calls within dayKey
}
```

If Firestore is unreachable, the function **fails open** (forwards
the call without recording it) so a backend hiccup doesn't deny
service. The trade-off is that a sustained outage lets a single
uid exceed the daily cap — acceptable given how expensive
Gemini TTS is relative to a Firestore read.

## Dependency pins

`package.json` carries an `overrides` block that holds a few transitive
dependencies inside their current majors. Without it, the weekly npm-update
batch (`.github/workflows/npm-update.yml`) trips its own no-majors rule on
the first run and can never open a PR: the `@types/*` packages reference
`@types/node` as `*`.

This paragraph used to give a second reason — that `firebase-functions`
declares `express ^4 || ^5`, so an unconstrained resolve jumps to express 5.
No v7 release declares that: it is `express ^4.21.0` through 7.3.0 and
`^5.2.1` from 7.3.2, with nothing in between accepting both.

Each override is a deferred migration, not a permanent fact. Drop one when
you are ready to take the crossing deliberately:

- `express` / `@types/express` `^4` — **this one has stopped being
  deferrable.** `firebase-functions` 7.3.2 declares `express ^5.2.1` and
  `@types/express ^5.0.0`, dropping express 4 from its range entirely.
  7.3.0 is the last release that still declares `express ^4.21.0` (7.3.1
  was never published), so that is the ceiling this override supports.
  Past it the override no longer holds the SDK inside a supported
  combination — it forces one outside it, and silently: an `overrides`
  entry is authoritative for npm, and forcing the v4 types alongside the
  runtime stops `tsc` seeing the mismatch. It also clears the batch's
  hold-back check, which compares resolved lockfile versions — express does
  not move, so nothing reads as a crossing. Until the override is dropped,
  every batch that would take 7.3.2 has to hold `firebase-functions` back
  by hand (#1211), landing on whatever the lockfile already pins.

  Migrating is still a code review of the handler surface rather than a
  version bump, and that surface is small. `src/index.ts` uses `req.body`,
  `req.header`, `req.method`, `res.status`, `res.json`, `res.setHeader` and
  `res.send`. Express 5 dropped the `res.json(status, body)` and
  `res.send(status)` overloads; every call here is the surviving form —
  `res.status(n).json(obj)` and `res.send(buffer)` — so no call site
  changes. `res.setHeader` is Node's own, and `req.header` / `req.method`
  are unchanged.
- `@types/node` `^22` — matches `engines.node`. Move both together when the
  Functions runtime moves.
- `fast-xml-parser` `~5.8.0` — newer 5.x minors moved their `entities`
  dependency across a major; held rather than forcing the sub-dependency
  against its declared range. Revisit on the next `firebase-admin` bump.
