# Clothescast design specs

## Clothing drawables

The `ic_outfit_*` (and matching `ic_notification_top_*`) drawables show up at
two very different sizes: small in lists and notifications, large in the
home-screen widget and outfit detail. Both ends need to look good.

### Glanceability at small sizes

- Each garment must be **clear, readily distinct, and glanceable** at icon
  sizes — a user scanning the Today screen or a notification should
  recognise "t-shirt vs. sweater vs. puffer" without squinting. Silhouette
  does the heavy lifting; fine detail is wasted at 24–48dp.
- Avoid relying on stroke thickness or interior detail to tell two
  garments apart at small sizes. If you have to zoom to see the
  difference, the small-size rendering is wrong.

### Quality at large sizes

- The same drawables must **look great scaled up** in the widget and any
  hero placements. No pixelation, no awkwardly-thin strokes, no detail
  that only made sense at icon size. Vector geometry should hold up to
  several hundred dp.

### Proportions and alignment

- **Waist widths must match** across tops and bottoms so an outfit
  (t-shirt + shorts, sweater + long pants, etc.) lines up cleanly when
  stacked. The bottom edge of a top and the top edge of a bottom should
  meet at the same width.
- **Aspect ratios may be slightly exaggerated** — to make garments look
  distinct from each other, or to make stacked outfits line up — but the
  result must still read as **recognisable and realistic**. Stylised, not
  cartoonish; a sweater that's a bit boxier than reality is fine, a
  sweater shaped like a square is not.

### Default colours

- Default colours should lean **distinctive while still being realistic**.
  Tops have more leeway — a t-shirt can default to red or pink so it
  reads instantly in a list — but garments with a strong real-world
  colour association should respect it. Jeans default to **blue-jeans
  blue**, not red or pink. The test is: would a stranger looking at this
  icon for half a second still recognise it as the right garment?

### Seasonal visual cues

Use consistent cues so the season a garment is meant for reads at a
glance:

- **Summer:** short sleeves, hollow (uncoloured) neckline. Light,
  unencumbered silhouette.
- **Spring / autumn:** longer sleeves, still hollow neckline. Reads as
  "more coverage than summer, not yet winter."
- **Winter:** longest sleeves, **darker neck fill** behind the collar to
  suggest an insulating layer / closed-up neck. Reads as warmest.

The neck treatment (hollow → hollow → dark-filled) and the sleeve length
(short → long → longest) are the primary signals; keep them consistent
across the set so the progression is legible when garments sit next to
each other.

## Delivery pipeline

The intended end state of the twice-daily forecast pipeline, including
the planned **Cast to smart display** destination. This section is the
contract for what fires when, in what order, with what gates, on what
threads, and which permissions / system components it needs. It
deliberately omits implementation detail — the goal is a design
reviewers can agree on before the code lands. Pre-existing destinations
(phone notification, phone speaker, MQTT bridge) are documented as they
already work in `main`; Cast is described as the destination we're
adding.

### Destinations

Each scheduled run can fan out to four user-visible destinations. They
are independent — each has its own enablement and runtime gates, and
the worker delivers to whichever apply.

1. **Phone notification.** The Android system tray. The current insight
   notification (Today) and tonight insight notification (Tonight).
   Persistent until dismissed; tapping opens the Today screen.
2. **Phone speaker.** Spoken forecast via on-device Android TTS or
   Gemini TTS over BYOK. Uses `AudioFocus` to duck other media for the
   duration of playback.
3. **Smart display (Cast).** A Google Cast receiver on the LAN — Nest
   Hub, Chromecast-built-in TV, etc. — picked once in Settings. Plays
   the WAV-wrapped Gemini synth and shows the rendered outfit PNG as
   poster art via the Default Media Receiver.
4. **MQTT bridge.** A user-hosted broker (typically Home Assistant).
   Three retained topics under a configurable prefix: prose, outfit
   image, WAV-wrapped audio.

Destinations are deliberately additive: no destination's success or
failure cancels another. The only cross-destination interaction is the
**Skip phone speech if cast succeeded** toggle (see below), which is a
narrow, opt-in suppression of one destination by another.

### Settings and gates

Every per-run "should this fire?" decision derives from these settings.
All persisted in DataStore; all readable via the same `UserPreferences`
flow the worker subscribes to.

#### Schedule and delivery mode

- **Schedule (Today)** — time + days for the morning run. Default
  07:00, weekdays + weekends.
- **Tonight enabled** — whether the evening run happens at all.
  Default on.
- **Schedule (Tonight)** — time + days for the evening run. Default
  19:00.
- **Delivery mode (Today)** — one of `NOTIFICATION_ONLY`, `TTS_ONLY`,
  `NOTIFICATION_AND_TTS`. Default `NOTIFICATION_AND_TTS`. Gates the
  phone notification post and the phone speaker for the morning run.
- **Delivery mode (Tonight)** — same shape, gates the evening run.
- **Tonight: only when events** — when on, the worker skips the
  evening run's notification, phone TTS, **and cast** if the calendar
  has no events in the evening window. Default off. Cache and widget
  are still updated.

#### Voice / TTS

- **TTS engine** — `Device` (on-device Android TTS) or `Gemini`
  (BYOK API call returning PCM). Default `Device`.
- **Voice locale / voice id / style** — passed to the chosen engine.
- **Gemini API key configured** — derived from the secure key store.
  When the engine is Gemini but no key is set, audio-carrying cast
  is unavailable — cast falls back to image-only. Settings
  surfaces a one-tap "Open voice settings" warning under the Cast
  picker.
- **Skip TTS at home** + **Home pin** — when on, the worker silences
  the **phone** speaker if the current coarse fix is within ~100 m
  of the home pin. Cast and MQTT-audio are *not* gated by this —
  they're the explicit "play it somewhere else" destinations.

#### MQTT bridge

- **Bridge enabled** + **host / port / TLS / username / topic
  prefix** — the broker config. The bridge is **publishable** when
  the toggle is on **and the host is non-empty** (a blank host
  effectively disables the bridge regardless of the toggle, per
  `MqttPublisher.preparePublish`). Downstream gates use this
  composite — referred to as `mqttPublishable` in the sequencing
  section — rather than the raw toggle.
- The prose and image topics fire on every worker run when the
  bridge is `mqttPublishable` (regardless of the worker's other
  destinations, including on empty-evening tonight runs — the
  retained payload still reflects the latest forecast for HA
  automations that don't care about the calendar). The audio
  topic fires on every worker run when the bridge is
  `mqttPublishable` **and Gemini is available** — the bridge is
  its own PCM consumer (see `needsSynth`), so a Gemini-configured,
  bridge-publishable, no-cast, notification-only run still
  synthesises and retains `${topic}/audio` for HA's
  speak-on-trigger automations. The audio topic does **not** fire
  on a device-TTS run (no PCM buffer exists) or when Gemini synth
  fails this run.

#### Cast (smart display)

The new Cast section lives in **Settings → Smart Displays and Home
Automation → Cast**, above the MQTT card.

- **Picked display** — `castRouteId` + cached `castRouteName`. Null
  means cast is disabled. The Settings row reads the cached name
  even when the device is asleep so the picker stays readable.
- **Daily ClothesCast** — per-period toggle. Default on.
- **Nightly ClothesCast** — per-period toggle. Default on.
- **Cast does not require Gemini.** When the Gemini engine is
  available (engine = Gemini AND API key set), the cast carries
  the synthesised WAV and the smart display speaks the forecast.
  When Gemini isn't available (engine = Device, or no API key, or
  Gemini synth fails), the cast falls back to **image-only** —
  the receiver still shows the outfit PNG, just silently. The
  phone speaker handles audio in that case (via Device TTS if
  configured). Implementation note: Default Media Receiver
  requires *some* media to load, so the image-only path attaches
  a short silent WAV stub purely as a loading carrier — nothing
  audible plays on the receiver.
- **Skip phone speech if cast succeeded** — when on (default), the
  phone speaker is silenced when a cast **actually plays audio**.
  Phone *notification* still fires per `Delivery mode`; only TTS
  playback is suppressed. Three conditions for the suppression to
  apply: cast load succeeded, cast carried real audio (Gemini
  synth available), and the user hasn't opted out via this
  toggle. Image-only casts don't suppress phone speech — the
  smart display isn't doing the audio. Gated by `Delivery mode`
  first: a `NOTIFICATION_ONLY` user opted out of phone TTS
  entirely, so this toggle has no effect for them (no speech to
  suppress).
- **Power on smart display** — when on (default), the cast will wake
  the display if it's asleep. When off, the worker skips the cast
  if the SDK doesn't already see the route as `CONNECTED`. Status
  row reflects "Skipped — smart display is asleep."
- **Interrupt if already playing** — when on (default), the cast
  takes over from another app's session on the saved route. When
  off, the worker skips the cast if the route is `CONNECTED` but
  not by our app. Status row reflects "Skipped — smart display is
  in use."
- **Cast now** — a manual button on the Settings card. Synth, render,
  resolve route, select, load. Status row reports outcome. Bypasses
  the wake / interrupt gates — the user explicitly asked.

### Sequencing

A single worker (`FetchAndNotifyWorker`) handles both periods. The flow
splits at `awaitDeliveryAlignment` — the moment we want every device
in a multi-device household to see / hear the new forecast at the same
wall-clock instant. Everything slow or variable happens **before** the
barrier so the post-barrier steps are user-visible at a predictable
moment.

**Setup** — serial, fast, just enough state to decide what to run:

1. **Resolve location.** Coarse fix or fallback pin.
2. **Fetch + generate insight.** Open-Meteo + per-model confidence,
   feeds the clothes rules and prose generator. After this returns,
   we have the insight prose, the calendar window's `hasEvents`
   flag, and `UserPreferences` — enough to decide every downstream
   gate.
3. **Compute the gates.** Two stages: a *preference-level* gate that
   decides whether synth needs to happen (because some destination
   would consume it), and a *runtime* gate that decides whether the
   phone actually speaks. Keeping them separate matters for
   skip-at-home: that toggle suppresses the phone speaker, not the
   MQTT audio publish, so synth still needs to fire to feed the
   retained topic.
   - `phoneTtsConfigured` — `Delivery mode allows TTS`. The user's
     standing preference; does **not** include the skip-at-home
     check.
   - `phoneRequested` — `phoneTtsConfigured` AND `Skip TTS at home`
     doesn't apply right now. This is the runtime "play on the
     phone speaker?" gate, evaluated post-alignment.
   - `willCast` — display picked AND period toggle on. **Does not
     require Gemini**: when Gemini is unavailable the cast falls
     back to image-only with a silent WAV stub. The presence of
     audio is a separate determination (see `castWillHaveAudio`).
   - `geminiAvailable` — TTS engine = Gemini AND API key configured
     AND synth hasn't already failed this run.
   - `castWillHaveAudio` — `willCast && geminiAvailable`. A
     **pre-synth prediction**: "based on settings, this run should
     try to carry audio on the cast." Drives `needsSynth`. Doesn't
     guarantee a buffer will exist by step 8 — a synth failure
     between step 3 and step 8 can leave us with
     `castWillHaveAudio = true` but no PCM. The runtime
     "does the cast actually have audio right now?" view used by
     steps 8–9 is `castHasAudio = willCast && synth produced a
     buffer`, defined post-synth.
   - `mqttPublishable` — bridge toggle on **and host non-empty**.
     A blank host effectively disables the bridge even when the
     toggle is on (per `MqttPublisher.preparePublish`), so the
     gate uses publishability, not the raw toggle.
   - `emptyEveningSkip` (tonight only) — `tonightNotifyOnlyOnEvents`
     AND `!insight.hasEvents`. When true, the user-facing
     destinations (notification, phone TTS, cast) are suppressed.
     **MQTT publishes are not suppressed**: the bridge fires
     prose, image, and audio on every run when `mqttPublishable`
     (and Gemini is available, for audio) so HA still sees the
     latest forecast. Cache + widget were already updated
     upstream of `deliver()`.
   - `needsSynth` — `geminiAvailable && (mqttPublishable ||
     ((phoneRequested || willCast) && !emptyEveningSkip))`. Synth
     happens only when **a real consumer for the PCM exists**.
     Each disjunct names one consumer:
     - `mqttPublishable` — the retained `${topic}/audio` is a
       destination in its own right and survives skip-at-home
       *and* `emptyEveningSkip` (HA automations want fresh
       audio regardless). Requires a *publishable* bridge, not
       just the toggle, so a bridge-enabled-but-blank-host
       config doesn't burn a BYOK request for an unreachable
       broker.
     - `phoneRequested && !emptyEveningSkip` — phone speaker on
       the Gemini engine, skip-at-home not active, evening
       isn't being skipped. Uses the runtime gate (not
       `phoneTtsConfigured`) because skip-at-home means the
       phone won't play and so by itself doesn't justify synth.
     - `willCast && !emptyEveningSkip` — audio-carrying cast
       (`willCast && geminiAvailable`, i.e. `castWillHaveAudio`)
       consumes the PCM. Under the outer `geminiAvailable`
       guard, bare `willCast` is sufficient here. Image-only
       cast (`willCast && !geminiAvailable`) doesn't drive
       synth — step 10 loads the silent WAV stub.

     Gemini is the only producer of routable PCM, so
     `geminiAvailable` is a hard prerequisite: when the engine
     is Device the on-device TTS does its own synth at playback
     time and exposes no buffer, so there's nothing to route to
     MQTT or cast — `needsSynth` stays false and the default
     Device-TTS / no-cast run never makes a BYOK Gemini request.

**Pre-alignment fan-out** — two concurrent tracks. They start the
moment step 3 returns and run in parallel; the worker awaits both
before the alignment barrier. No off-device publishes happen
pre-alignment — all MQTT and cast traffic is held until the
alignment moment so the household sees every destination update
together.

4. **Synth track** — launched as a coroutine on `Dispatchers.IO`,
   `async`-style, only when `needsSynth`. Gemini TTS is the worker's
   slowest variable step (multi-second network round-trip), so
   kicking it off here, before the render, means it overlaps with
   the render and the rest of the pre-alignment wait happens "for
   free" in the synth's shadow.
   1. Call Gemini TTS → `PcmAudio`.
   2. WAV-wrap → `ByteArray`. The same buffer feeds MQTT audio,
      cast, and phone playback at the post-alignment fan-out.
   3. Suppressed entirely on the device-TTS path: the on-device
      engine doesn't expose a buffer, so synth + play happens at
      the phone-speaker step below.
5. **Render track** — concurrent with the synth track. Render the
   outfit PNG once; hold it in memory for the post-alignment fan-out.

**Alignment barrier:**

6. **`awaitDeliveryAlignment`** — pause until ~60 s past the
   alarm-fire timestamp the receiver stamped. By the time we cross
   this line, both pre-alignment tracks have completed — the WAV
   (Gemini path) is in memory and the PNG is rendered. Steps 7–12
   are the predictable user-visible moment, with no slow work
   left to do. The alignment barrier runs **regardless of
   `emptyEveningSkip`**: MQTT publishes still go through at the
   aligned moment, even on empty-evening runs where the
   user-facing destinations are suppressed.

**Post-alignment fan-out** — six destinations, fired in parallel
where there's no ordering constraint:

7. **Phone notification** (gated on delivery mode AND
   `!emptyEveningSkip`). Fires immediately at alignment. The first
   thing the user sees on event-bearing evenings; suppressed on
   empty-evening tonight runs.
8. **Cast load** (gated by `willCast` AND `!emptyEveningSkip` AND
   runtime wake / interrupt gates). Resolves the route, selects,
   awaits the session, calls `client.load(...)` with the PNG plus
   either the pre-rendered WAV (when the synth track produced a
   buffer at track 4) or a silent WAV stub (image-only fallback —
   when synth never ran, or ran but failed, or `geminiAvailable`
   was false to begin with). **The choice is keyed off the actual
   buffer state at the alignment moment, not the pre-synth
   `castWillHaveAudio` prediction**: a Gemini-engine /
   key-configured run where the synth coroutine then fails was
   `castWillHaveAudio = true` at step 3 but has no buffer here,
   so the cast falls back to image-only with the silent stub.
   Define `castHasAudio = willCast && synth produced a buffer`
   for the post-synth runtime view used by this step and step 9.
   Awaits the receiver's load result so a `Success` outcome
   means "media accepted," not "request issued." Persists the
   outcome to `castLastError`. SDK latency for select-and-load
   is the main variable in this step — typically 1–3 seconds on
   a route that's already discovered, longer for a cold wake.
   Kicks off in parallel with the notification.
9. **Phone speaker** (gated on `!emptyEveningSkip`). Has to
   *serialise behind* the cast outcome when `castHasAudio` (i.e.
   `willCast` AND the synth buffer actually exists) so the
   `castSkipPhoneSpeech` toggle can actually suppress us.
   Otherwise phone TTS could begin before the cast result is
   known and we'd produce duplicate audio. Image-only casts
   (`willCast && !castHasAudio` — synth never ran or failed)
   don't serialise — the cast isn't playing audio, so there's
   nothing to suppress, and phone speech fires immediately at
   alignment.
   - If `!willCast` (or `willCast && !castHasAudio`): phone
     speaker fires immediately at alignment, gated on
     `phoneRequested`.
   - If `castHasAudio`: await the step-8 cast outcome first.
     Then:
     - If cast succeeded AND `castSkipPhoneSpeech`: skip the
       phone speaker — the smart display is handling the audio.
     - Else: fire the phone speaker, gated on `phoneRequested`.
   Per-engine playback:
   - *(Gemini engine, synth succeeded.)* `speaker.play(pcm)` with
     the buffer from track 4 — no synth latency at this point,
     just playback.
   - *(Gemini engine, synth failed or no key.)* No PCM was
     produced (or `needsSynth` was false because no destination
     wanted it). Fall back to the on-device engine for phone
     playback: `androidTtsSpeaker.speak(text, locale)`. The
     phone still speaks; only the smart display falls back to
     image-only.
   - *(Device engine.)* `androidTtsSpeaker.speak(text, locale)` —
     this is where the on-device engine does its own synth + play.
   Wraps the call in `withSpeechAudioFocus`; only this step takes
   audio focus on the phone.
10. **MQTT prose** (gated on `mqttPublishable`). Publish the prose
    sentence to the retained prose topic. Fires immediately at
    alignment, in parallel with the notification, cast, and the
    other MQTT publishes. Not gated by `emptyEveningSkip` — HA
    sees the latest forecast on every run.
11. **MQTT image** (gated on `mqttPublishable`). Publish the
    rendered outfit PNG to the retained image topic. Fires
    immediately at alignment. Not gated by `emptyEveningSkip`.
12. **MQTT audio** (gated on `mqttPublishable` AND the synth
    buffer from track 4 exists). Publish the WAV to the retained
    audio topic. Fires immediately at alignment. Not gated by
    `emptyEveningSkip`. Skipped when synth wasn't run (device-TTS
    path, or no consumer wanted it) or when synth failed.
13. **End of run.**

The phone notification (step 7), cast load (step 8), and the three
MQTT publishes (steps 10–12) all kick off at alignment, in
parallel. The phone speaker (step 9) is the only post-alignment
step with an ordering dependency: when a cast is attempted, the
phone speaker waits for the cast result so `castSkipPhoneSpeech`
can do its job. In the typical discovered-route case the wait is
1–3 seconds; on a cold wake it can be longer. When no cast is
attempted, the phone speaker fires immediately. Gemini synth has
been pulled into a pre-alignment coroutine that runs concurrently
with the render, so the synth's variability doesn't push out the
alignment moment as long as it completes inside the ≤60 s window
the receiver stamps — and at the user, the phone audio latency is
just "SDK round-trip on the cast outcome + playback start," not
"synth + SDK setup + playback start." Holding MQTT publishes until
alignment means HA's "speak the prose when the wardrobe opens"
automation fires *with* the phone notification, not 30 seconds
ahead of it.

### Behaviour matrix

Common configurations and what fires per run. `N` = phone notification,
`P` = phone speaker, `C` = cast, `M-audio` = MQTT `${topic}/audio`.
`M-prose` and `M-image` always fire when the bridge is enabled, so
they're omitted from the matrix.

For brevity the rows below assume Gemini is available unless marked
otherwise, and that synth succeeds when `needsSynth` fires. "Cast ok"
means load succeeded; "Cast fails" means the receiver rejected the
load, the route wasn't reachable, or a wake / interrupt gate skipped
the cast. "C (audio)" is an audio-carrying cast; "C (silent)" is an
image-only cast.

Runtime synth failure degrades the same row's outcome along one
edge: every `Gemini ok = yes` row with `C (audio)` becomes `C
(silent)`, and the same row's `P` outcome is whatever the matrix
already shows for the `Gemini ok = no` equivalent — phone falls
back to Device TTS where the delivery mode wants TTS, since `P`
makes no distinction between Gemini playback and Device fallback.

| Delivery mode             | Cast picked | Gemini ok | Cast ok at runtime | castSkipPhoneSpeech | Fires             |
| ------------------------- | ----------- | --------- | ------------------ | ------------------- | ----------------- |
| `NOTIFICATION_AND_TTS`    | no          | yes       | n/a                | n/a                 | N + P             |
| `NOTIFICATION_AND_TTS`    | yes         | yes       | ok                 | on                  | N + C (audio)     |
| `NOTIFICATION_AND_TTS`    | yes         | yes       | ok                 | off                 | N + P + C (audio) |
| `NOTIFICATION_AND_TTS`    | yes         | yes       | fails              | any                 | N + P             |
| `NOTIFICATION_AND_TTS`    | yes         | no        | ok                 | any                 | N + P + C (silent)|
| `NOTIFICATION_AND_TTS`    | yes         | no        | fails              | any                 | N + P             |
| `NOTIFICATION_ONLY`       | no          | yes       | n/a                | n/a                 | N                 |
| `NOTIFICATION_ONLY`       | yes         | yes       | ok                 | any                 | N + C (audio)     |
| `NOTIFICATION_ONLY`       | yes         | yes       | fails              | any                 | N (silent)        |
| `NOTIFICATION_ONLY`       | yes         | no        | ok                 | any                 | N + C (silent)    |
| `TTS_ONLY`                | no          | yes       | n/a                | n/a                 | P                 |
| `TTS_ONLY`                | yes         | yes       | ok                 | on                  | C (audio)         |
| `TTS_ONLY`                | yes         | yes       | ok                 | off                 | P + C (audio)     |
| `TTS_ONLY`                | yes         | yes       | fails              | any                 | P                 |
| `TTS_ONLY`                | yes         | no        | ok                 | any                 | P + C (silent)    |
| At home + skip-at-home on | yes         | yes       | ok                 | any                 | N (or none) + C (audio) |
| At home + skip-at-home on | yes         | no        | ok                 | any                 | N (or none) + C (silent) |
| At home + skip-at-home on | yes         | yes       | fails              | any                 | N (or none) — no P |

`M-audio` fires whenever **synth happened AND the bridge is
publishable**. Synth is decided pre-alignment by `needsSynth`
(`geminiAvailable && (mqttPublishable || ((phoneRequested ||
willCast) && !emptyEveningSkip))`) at step 3 and run in step 4; the
publish itself happens at step 12, in parallel with the notification,
cast load, and the other MQTT publishes at the alignment moment.
A later cast failure (display asleep / busy / load rejected) doesn't
retract the MQTT audio payload, and neither does a
`castSkipPhoneSpeech` suppression of P after a successful cast.
Image-only cast paths (`willCast && !castWillHaveAudio`) don't drive
synth on their own, so they don't force an M-audio publish either.

That means M-audio fires in every matrix row above where C (audio)
is attempted, every row where P fires, and every row where P would
have fired but was suppressed by `castSkipPhoneSpeech` after a
successful audio-carrying cast — provided the bridge is publishable.
Skip-at-home with `mqttPublishable` still produces M-audio (the
bridge is its own consumer); skip-at-home with no cast and no
publishable bridge produces no M-audio (nothing wants synth).
Empty-evening tonight runs publish M-audio too when the bridge is
publishable. The configurations that produce no M-audio are
therefore: any run where the bridge isn't publishable AND no
audio-consuming destination fires (no consumer), every C (silent) /
image-only cast row (Gemini unavailable, no PCM exists), and any
device-TTS path (no PCM buffer to publish).

### Notifications

Two notification channels for forecasts:

- **`CHANNEL_DAILY_INSIGHT`** — morning forecast. Default importance,
  the user's chosen sound, heads-up allowed. ID `1001`. Persists
  until the user dismisses or taps; tapping opens Today.
- **`CHANNEL_TONIGHT_INSIGHT_DEFAULT`** — evening forecast on
  event-bearing evenings. Default importance.
- **`CHANNEL_TONIGHT_INSIGHT_SILENT`** — evening forecast on empty
  evenings when `tonightNotifyOnlyOnEvents` is off. Low importance,
  silent. The tonight notifier picks between these two by
  `Insight.hasEvents`.

The Cast destination does **not** add new notifications. We considered
promoting the insight notification to a `setForeground(ForegroundInfo)`
slot during cast playback for the SDK-officially-supported main-thread
guarantees that `MediaRouter.selectRoute` likes — but `WorkManager`
calls `stopForeground(STOP_FOREGROUND_REMOVE)` on worker completion,
which would yank the persistent insight notification out of the tray
on cast-enabled runs. The trade-off is documented; the chosen path is
"no FGS, cast runs as plain background work, accept some OEM
flakiness." If real-world reports show enough flakiness on aggressive
OEMs we may revisit with a separate placeholder FGS notification.

### Services and threading

- **`FetchAndNotifyWorker`** — `CoroutineWorker`, runs on
  `Dispatchers.Default`. The whole flow above lives here.
- **No app-owned `Service`.** TTS playback uses `AudioFocus`, not a
  Foreground Service. The `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SHORT_SERVICE`
  permissions are declared (manifest) for forward compatibility but
  unused on `main`.
- **WorkManager's `SystemForegroundService`** — library-managed. Not
  used by our worker on `main`; we don't override its
  `foregroundServiceType` since we don't call `setForeground`.
- **`AlarmReceiver`** — broadcast receiver fired by `AlarmManager` at
  the scheduled time. Enqueues the worker.
- **`ScheduleRefreshReceiver`** — re-arms alarms on
  `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` / `TIMEZONE_CHANGED` /
  `LOCALE_CHANGED`.
- **`OutfitWidgetReceiver`** — exported app-widget provider.
- **`FileProvider`** — internal-only, for the bug-report share path.
- **Cast SDK threading.** `MediaRouter.addCallback` /
  `selectRoute`, `SessionManager.addSessionManagerListener`, and
  `RemoteMediaClient.load` are documented main-thread-only. The
  cast controller wraps every interaction with those APIs in
  `withContext(Dispatchers.Main.immediate)` so worker-side calls
  (background dispatcher) are hopped to Main; settings-side calls
  (already on Main) pay no overhead.

### Permissions

Declared in the manifest, with the destination each one enables:

| Permission                              | Required for                                              | Runtime grant? |
| --------------------------------------- | --------------------------------------------------------- | -------------- |
| `INTERNET`                              | Open-Meteo, Gemini TTS, MQTT broker, LAN media server     | No (install)   |
| `ACCESS_NETWORK_STATE`                  | LAN IP resolution for Cast, network class detection       | No (install)   |
| `ACCESS_WIFI_STATE`                     | Cast SDK gates discovery on Wi-Fi availability            | No (install)   |
| `CHANGE_WIFI_MULTICAST_STATE`           | Cast mDNS discovery uses multicast                        | No (install)   |
| `ACCESS_COARSE_LOCATION`                | Foreground weather fetch                                  | Yes            |
| `ACCESS_BACKGROUND_LOCATION`            | Scheduled worker reads location when backgrounded         | Yes            |
| `POST_NOTIFICATIONS`                    | Insight + tonight notifications (Android 13+)             | Yes (API 33+)  |
| `READ_CALENDAR`                         | Enriching the insight with today's events                 | Yes (opt-in)   |
| `RECEIVE_BOOT_COMPLETED`                | Re-arming the daily alarm after reboot                    | No             |
| `USE_EXACT_ALARM`                       | Scheduled-time alarm (API 33+, no-prompt)                 | No             |
| `SCHEDULE_EXACT_ALARM` (maxSdk=32)      | Same, API 31–32 path                                      | No             |
| `FOREGROUND_SERVICE`                    | Future TTS-during-background path; unused on `main`       | No             |
| `FOREGROUND_SERVICE_SHORT_SERVICE`      | Same as above; declared, currently unused                 | No             |
| `com.google.android.gms.permission.AD_ID` | **Removed** via `tools:node="remove"` (Firebase pulls it in transitively; we don't use ad IDs) | n/a |

No new permissions are required to add Cast: `ACCESS_WIFI_STATE` and
`CHANGE_WIFI_MULTICAST_STATE` are normal-level (no runtime prompt) and
cover Cast SDK's mDNS discovery.

### Failure modes and fallbacks

- **Open-Meteo unreachable** → worker returns `Result.retry`; backoff
  handles the next attempt. The user-visible notification is suppressed
  on the first failure.
- **Gemini synth fails** (network, missing key, rate limit) → the
  worker falls back to the on-device TTS engine for phone playback.
  Cast still fires when configured, but as **image-only** with the
  silent-WAV carrier (the Cast contract from the settings section);
  the smart display shows the outfit PNG, the phone speaker handles
  the audio via Device TTS. MQTT audio publish is skipped (no PCM
  to route).
- **Cast load fails** (route not found, session timeout, receiver
  rejects the media) → persisted as a `CastFailure` outcome on the
  Settings status row. Phone TTS plays as a fallback per
  `Delivery mode` (which means it stays silent for `NOTIFICATION_ONLY`
  users — they opted out of phone audio).
- **MQTT publish fails** → swallowed and logged. `mqttLastError` is
  surfaced on the Settings status row.
- **Worker cancelled mid-run** (`REPLACE` from a manual refresh, OS
  stop) → cancellation propagates through every catch / `runCatching`
  rescue; no further side effects fire after the cancellation point.
  The LAN media server is stopped eagerly if the cast was mid-flight.
- **App killed mid-cast** → process death takes the LAN media server
  with it. The receiver's load fails; the user sees nothing.
- **Smart display loses LAN connectivity mid-playback** → the
  receiver's media URL fetch times out. Out of our control.

### What's intentionally NOT in this spec

- **A "Cast now" button anywhere outside Settings.** Cast triggers
  exclusively from the worker (scheduled) or the Settings test
  button (manual). No toolbar / Today-screen Cast affordance.
- **A custom Web Receiver.** We use Google's Default Media Receiver
  (free, no developer-console registration). The trade-off is that
  the outfit poster renders at album-art size (~¼ of a Nest Hub
  screen) rather than full-screen. Two future paths to revisit if
  this is a problem: experimenting with `MediaMetadata.MEDIA_TYPE_PHOTO`,
  or packing the PNG + WAV into a still-frame MP4 and loading as
  `video/mp4`.
- **An "ambient info card" presence on Nest Hub.** Cast SDK doesn't
  expose this; it's controlled by Google Assistant / Home Graph
  integrations, which are a separate surface area entirely.
- **A per-cast "mirror to phone" toggle.** The existing
  `Delivery mode` already controls phone TTS independent of cast.
- **A "podcast / audiobook" media type.** Cast SDK's audio
  playback gets the music-player UI by default; the metadata type
  doesn't reliably change that on most receivers.

