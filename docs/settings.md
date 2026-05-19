# Settings reference

ClothesCast can deliver each twice-daily forecast to three destinations,
in any combination:

- **Phone notification** in the system tray
- **Phone speaker** — the spoken forecast plays via on-device TTS or Gemini TTS
- **Smart display** — the outfit picture + spoken forecast on a Google Cast receiver on your LAN
- **MQTT bridge** — the prose, the rendered outfit image, and the WAV-wrapped audio published to a user-hosted broker for Home Assistant automations

Each destination has its own enablement; the worker decides per-run
which apply. This doc explains every setting that affects the
worker's "where does today's forecast go?" decision, the order things
happen in, and the most common configurations.

Settings live in **Settings → Smart Displays and Home Automation** (the
section formerly called "Smart Home") for Cast and MQTT, and
**Settings → Voice** for everything that affects TTS synthesis.

## At a glance: what fires when

| Setting / state                              | Default     | Gates                                       |
| -------------------------------------------- | ----------- | ------------------------------------------- |
| Delivery mode (Today)                        | NOTIFICATION_AND_TTS | Phone notification, phone TTS               |
| Delivery mode (Tonight)                      | NOTIFICATION_AND_TTS | Phone notification, phone TTS (evening)     |
| Tonight enabled                              | on          | Whether the tonight run happens at all      |
| Tonight: only when events                    | off         | Skip tonight run entirely on empty evenings |
| TTS engine                                   | Device      | Gemini TTS network call (only path that works for cast) |
| Skip TTS at home                             | off         | Phone TTS only — silenced when home pin matches current location |
| MQTT bridge enabled                          | off         | All three MQTT topics (prose, image, audio) |
| Cast: smart display picked                   | none        | Cast eligibility                            |
| Cast: morning forecast                       | on          | Cast on the morning run                     |
| Cast: tonight forecast                       | on          | Cast on the evening run                     |
| Cast: skip phone speech if cast succeeded    | on          | Phone speaker silenced when cast succeeds   |
| Cast: power on smart display                 | on          | When off, skip cast if display is asleep    |
| Cast: interrupt if already playing           | on          | When off, skip cast if route is in use      |

## Destinations

### Phone notification

Controlled by **Delivery mode** in Settings → Schedule:

- `NOTIFICATION_ONLY` — show the tray notification, no spoken forecast.
- `TTS_ONLY` — speak the forecast, no notification.
- `NOTIFICATION_AND_TTS` (default) — both.

The notification is also gated for the **Tonight** run by **"Only when
events"**: when the evening calendar window has no events, the worker
skips the notification, the spoken forecast, and the cast — your Hub
stays quiet at 7 pm if you've got nothing on.

### Phone speaker

Speaks via the engine picked in Settings → Voice:

- **Device** voice — the on-device Android TTS. No network call, no
  Gemini bill. Cast and MQTT-audio don't get audio on Device-TTS runs
  because the on-device engine doesn't expose a raw PCM buffer we
  could route off-device.
- **Gemini** voice — calls Gemini TTS over your own API key, returns a
  PCM buffer that gets WAV-wrapped and routed to phone, cast, and MQTT
  as appropriate.

Phone TTS additionally honours:

- The **Skip TTS at home** toggle in Settings → Location. When on, the
  worker compares the current coarse fix to your saved home pin; if
  you're within ~100 m of home, phone TTS is silenced (the assumption
  is you can hear other things in the house and don't need a 7 am
  forecast read out loud). Cast and MQTT-audio are *not* gated by
  skip-at-home — those are the explicit "play it somewhere else"
  destinations and are exactly what you want when you're at home.

### Smart display (Cast)

Targets the saved Cast route via Google's Default Media Receiver — no
custom Web Receiver, no developer-console fee. Settings →
"Smart Displays and Home Automation" → Cast lets you pick a smart
display and tap **Cast now** to test it end-to-end.

Cast requires the **Gemini** TTS engine; the on-device engine doesn't
produce a PCM buffer we can route off-device. The Settings card shows
a warning row + "Open voice settings" button when a display is picked
but Gemini isn't ready (engine is Device or API key isn't set).

Per-run cast eligibility:

1. A display is picked (`castRouteId != null`).
2. The per-period toggle is on (`castMorningEnabled` / `castTonightEnabled`).
3. The TTS engine is Gemini.
4. The outfit PNG rendered successfully.

After eligibility passes, the dispatch then checks **wake** and
**interrupt** gates against the saved route's current state:

- **Power on smart display** (default on). When off, the dispatch is
  skipped if we don't already have a session on the saved route. The
  status row shows "Skipped — smart display is asleep." Use this to
  stop the daily forecast waking the kitchen Nest Hub at 7 am.
- **Interrupt if already playing** (default on). When off, the
  dispatch is skipped if the route's connection state is `CONNECTED`
  but the session belongs to another app (Spotify, YouTube, etc.).
  Status row shows "Skipped — smart display is in use."

When a cast load succeeds *and* **Skip phone speech if cast succeeded**
(default on) is on, the phone speaker is silenced for that run — the
smart display does the talking. The phone notification still fires per
your delivery-mode preference. If the cast fails for any reason
(display off the LAN, route disappeared, receiver rejects the media),
the phone falls back to speaking locally **provided your delivery mode
allows phone TTS** — `NOTIFICATION_AND_TTS` and `TTS_ONLY` users get
the fallback; `NOTIFICATION_ONLY` users opted out of phone TTS at the
mode level and stay silent.

### MQTT bridge

When the bridge is enabled in Settings → Smart Displays and Home
Automation → Home Assistant bridge (MQTT), each worker run publishes
three retained topics under `${baseTopic}/<period>/`:

- `<period>` — the prose sentence (e.g. `clothescast/insight/today`).
- `<period>/image` — the rendered outfit PNG.
- `<period>/audio` — the WAV-wrapped TTS audio (only fires when the
  worker also synthesised audio for some other destination — phone
  speaker or cast).

The prose and image topics fire whenever the bridge is on, regardless
of which other destinations are enabled. The audio topic
*piggybacks*: it only carries content on runs where the worker
synthesised TTS for the phone or cast pipeline. A
`NOTIFICATION_ONLY`-only configuration with cast off won't produce
audio for the MQTT topic to publish.

## The shared flow

Each scheduled run (alarm-driven, or triggered by the Today screen's
**Refresh** button) goes through `FetchAndNotifyWorker.deliver()`:

1. Fetch the weather + generate the insight + render the outfit PNG.
2. Publish prose and image to MQTT (the bridge gates itself).
3. Wait for `awaitDeliveryAlignment` — keeps the user-visible parts
   60 seconds behind the alarm fire timestamp so a multi-device home
   sees the same forecast at the same wall-clock instant.
4. **For the tonight period only:** if "only when events" is on *and*
   the evening calendar window has no events, skip the entire delivery
   (notification, phone TTS, cast). The cache + widget are already
   updated upstream of this step.
5. Post the phone notification (if delivery mode allows).
6. Decide whether to synthesise:
   - Phone TTS requested *or* cast eligible → synth via Gemini.
   - Else → skip the synth step entirely.
7. With audio in hand, dispatch in parallel:
   - **MQTT audio** publish if bridge is on.
   - **Cast** if eligible, including the wake / interrupt gates.
   - **Phone speaker** playback if requested *and not* suppressed by a
     successful cast + skip-phone-speech.

Cast, MQTT audio, and phone speaker each gate themselves on their own
enablement — synth runs once and the buffer fans out to whichever
endpoints are ready.

## Common configurations

### "Phone only" — defaults out of the box

- Delivery mode: `NOTIFICATION_AND_TTS`
- TTS engine: Device
- MQTT bridge: off
- Cast: no display picked

Just the phone tray notification + the device's on-device TTS. No
network calls beyond Open-Meteo for the forecast.

### "Phone + smart display" — talk to the kitchen Hub

- Delivery mode: `NOTIFICATION_ONLY` (no phone TTS)
- TTS engine: Gemini, API key set
- Cast: display picked, both periods on, skip-phone-speech on (default)
- Cast: wake on, interrupt on (defaults)

The phone shows the tray notification; the kitchen Nest Hub plays the
spoken forecast. If the Hub is off the LAN at alarm time, the phone
falls back to Gemini TTS through its speaker so you still hear it.

### "TV in the bedroom, leave the kitchen alone at 7am"

- Cast: display picked (bedroom)
- Cast: morning forecast on, tonight forecast on
- **Cast: power on smart display — off**

Same as above but with the wake gate off. The forecast only casts when
the display is already on (you're using it for something). When the
cast is skipped, the phone falls back per your delivery mode — phone
TTS plays if you've left it on, silent otherwise.

### "Show on the TV but only if I'm not already watching something"

- Cast: display picked
- **Cast: interrupt if already playing — off**

The cast is skipped if the smart display is currently in a Cast
session owned by another app (Spotify, YouTube, etc.) and the worker
falls back per your delivery mode. Note this gate is best-effort — the
Cast SDK doesn't expose *which* app owns another session, only the
route's connection state.

### "Quiet tonight runs unless I have something on"

- Tonight: enabled
- **Tonight: only when events — on**

On empty evenings the worker skips notification, phone TTS, **and**
cast. The Today screen still updates the next time you open the app.

### "Home Assistant on a sensor trigger"

- MQTT bridge: on, broker configured
- Phone TTS: optional (drives audio topic — see below)
- Cast: optional

The HA installation gets `clothescast/insight/today` (prose),
`clothescast/insight/today/image` (PNG), and — when the worker
synthesised audio for phone or cast — `clothescast/insight/today/audio`
(WAV). Wire your "speak this on the bedroom Sonos when the wardrobe
opens" automation against whichever topic you prefer. See
[smart-home.md](smart-home.md) for the HA-side glue.

### "Skip TTS at home, but cast there"

- Home pin: set, within 100 m of your kitchen
- Skip TTS at home: on
- Cast: kitchen Nest Hub picked

Phone TTS is silenced when you're home (skip-at-home matches), but
cast still fires — that's exactly the case skip-at-home is built for.

## Where each gate lives in code

For future-me reading this from a PR diff:

- **Delivery mode gating**: `FetchAndNotifyWorker.deliverToday` /
  `deliverTonight` — `shouldNotify`, `ttsRequested`,
  `shouldSpeakNow`.
- **Skip TTS at home**: `shouldSpeakNow(...)` →
  `core.domain.usecase.shouldSpeak(...)`.
- **Empty-evening skip**: top of `deliverTonight`.
- **Cast eligibility (config-level)**: `willCastForPeriod(...)`.
- **Cast wake / interrupt gates (runtime)**:
  `CastInsightController.checkRouteGates(...)` — applied
  post-discovery in `dispatchToSavedRoute`.
- **MQTT topic enablement**:
  `MqttPublisher.publishIfEnabled / publishImageIfEnabled /
  publishAudioIfEnabled` — each checks `mqttBridgeEnabled` and a
  non-blank host.
- **Cast load result** (the receiver actually accepted the media):
  `CastInsightController.loadOnSession` awaits the
  `PendingResult<MediaChannelResult>` and throws
  `CastFailure.LoadRejected` on a non-success status so the phone
  fallback can still fire.

## See also

- [smart-home.md](smart-home.md) — Home Assistant + MQTT setup walkthrough.
- [../PRIVACY.md](../PRIVACY.md) — what leaves the device, when,
  and to whom.
