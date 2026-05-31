# clothescast Cloud Functions

Single function today: `tts` — the Gemini TTS proxy backing the Android
app's shared-key path. Holds the developer's Gemini API key, verifies
Firebase App Check on each request, counts usage per Firebase
Installation ID in Firestore, and forwards to Gemini.

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
in the Firebase Console first):

```sh
curl -i -X POST "http://127.0.0.1:5001/<project-id>/us-central1/tts" \
  -H "X-Firebase-AppCheck: <debug token>" \
  -H "X-Install-Id: dev-test-install-001" \
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

## Counting vs enforcement

v1 counts every successful call in `installs/<fid>` (`firstUseAt`,
`count`, `lastUseAt`) but does not enforce any limit — every
authenticated request is forwarded to Gemini. Enforcement (return
`429 trial_exhausted` after 30 calls or 30 days, emit
`X-Trial-Remaining` on success) is tracked under "Deferred to v2" in
`docs/TODO.md`.
