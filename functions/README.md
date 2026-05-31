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
