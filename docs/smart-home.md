# Smart Home setup guide

ClothesCast can publish the rendered forecast sentence to your MQTT
broker after each scheduled twice-daily refresh, so Home Assistant (or
any other MQTT-aware consumer) can speak it on a trigger of your choice
— a wardrobe door opening, a bathroom humidity spike after a shower, a
fixed time of day, or any other automation you wire up.

This is the "single source of truth lives in the app" path: your tuned
clothes rules and the rain / calendar / evening-event heuristics all
stay in ClothesCast, and Home Assistant is just the dumb plumbing that
reads a string and speaks it.

The bridge is **off by default**. Turning it on relaxes the
"insight prose never leaves the device" guarantee documented in
[PRIVACY.md](../PRIVACY.md) — but only to a broker you configure
yourself; no developer-operated service ever sees the payload.

## App-side setup

1. Open **Settings → Smart Home → Home Assistant bridge (MQTT)** and
   toggle "Publish forecast to MQTT" on.
2. Tap **Find broker** if your Home Assistant instance or MQTT broker
   advertises itself over mDNS — the typical Home Assistant OS install
   does. Tap "Use" on the result and the host (and port, for an MQTT
   advert) populate themselves. If nothing shows up, fall back to
   typing the broker's hostname (e.g. `homeassistant.local`) manually.
3. Set the port. Defaults to `1883` plain / `8883` TLS — the toggle
   swaps the port for you when you flip TLS.
4. Optionally enter a username + password. The password is stored
   encrypted on-device under the same Tink-AEAD slot the Gemini API
   key uses.
5. Topic prefix defaults to `clothescast/default`. The daytime forecast
   publishes to `<prefix>/day/text`; the overnight one to
   `<prefix>/night/text`. The outfit image, the TTS audio, and a combined
   card-plus-announcement MP4 (when published) land on
   `<prefix>/<period>/image`, `<prefix>/<period>/audio`, and
   `<prefix>/<period>/video` respectively (where `<period>` is `day` or
   `night`). **Each scheduled run publishes both windows** — the current one
   and the next — so `day` and `night` always carry the current and upcoming
   cast rather than one going stale. Each `day`/`night` bundle is
   self-coherent: when a window has no audio or video this run (you're on
   device TTS, or a render/synth failed), its `<prefix>/<period>/audio` and
   `<prefix>/<period>/video` are cleared with an empty retained payload, so a
   consumer reading the bundle directly never pairs fresh text with a previous
   run's leftover clip. Each bundle also carries its own commit marker,
   `<prefix>/<period>/timestamp` (epoch-millis), written **last** and only once
   the whole `day`/`night` set has settled — the per-period equivalent of
   `now/timestamp`. **If you read `day`/`night` directly, trigger on
   `<prefix>/<period>/timestamp`** and dedupe on its value, exactly as you would
   on `now/timestamp`. The current window's bundle is also
   mirrored to `<prefix>/now/<kind>` — `<prefix>/now/text`,
   `<prefix>/now/image`, `<prefix>/now/audio`, `<prefix>/now/video`, and
   `<prefix>/now/timestamp` — so a consumer can subscribe to a single
   "latest" topic without having to switch on day vs night. `now` always
   reflects the *current* window; the next window lands only on its own
   `day`/`night` segment.

   **Trigger your automations on `<prefix>/now/timestamp`.** The `now`
   set updates as an atomic bundle, and `now/timestamp` (epoch
   milliseconds, as a string) is written *last of all* — only after
   `now/image`, `now/audio`, `now/video`, and `now/text` have each
   settled. So a change on `now/timestamp` is the single signal that the
   whole bundle is in place: trigger on it and read the sibling `now/*`
   topics, and you'll never pick up a `now/image` / `now/audio` /
   `now/video` / `now/text` left over from a different forecast. It also
   doubles as a dedupe key — every topic is **retained**, so a consumer
   that reconnects (HA restart, network blip) is re-delivered the last
   bundle on connect; remembering the last `now/timestamp` you acted on
   lets you skip re-announcing it rather than replaying a stale clip at
   reboot.

   Modalities absent from the current delivery (e.g. an outfit render
   failed, or you're on device TTS so no audio bytes exist) clear their
   `now/*` slot with an empty retained payload — MQTT's convention for
   "delete the retained message" — so you never read a stale
   image/audio/video left over from a previous bundle. `now/text` is held
   back if any of `now/image` / `now/audio` / `now/video` fails, and
   `now/timestamp` is held back if `now/text` fails, so a `now/timestamp`
   update always implies a fully coherent bundle settled successfully. If
   any period publish in the bundle fails outright, the entire `now`
   mirror is skipped — the previous fully-successful `now` set stays
   intact rather than being half-overwritten. MQTT itself has no
   transactional primitive across topics, so a fresh-reconnect consumer
   reading the retained `now/*` topics during a partial-failure window may
   briefly observe a mismatch; triggering on `now/timestamp` — the last
   write — and gating reads on its updates sidesteps this.
6. Tap **Save**.

The next scheduled refresh (07:00 by default for the daytime window, 19:00
for the overnight one — configurable in Settings → Schedule) will publish
retained MQTT messages to each topic. After a successful forecast publish,
ClothesCast also publishes retained Home Assistant MQTT discovery
configs under `homeassistant/.../config`, so HA can create the text,
timestamp, and image entities automatically. The discovery configs point
at the normal state topics above — they replace YAML setup, not the
forecast publishes themselves.

## Broker

### Install Mosquitto

If you don't already have an MQTT broker, install the **Mosquitto
broker** add-on inside Home Assistant
(Settings → Add-ons → Add-on Store → Mosquitto broker → Install →
Start). You don't need to touch the **Configuration** tab — Log
Destinations and Log Types can stay on their defaults.

### Create a dedicated user for ClothesCast

The Mosquitto HA add-on uses Home Assistant's own user system for MQTT
authentication by default, so you create the MQTT account the same
way as any other HA user:

1. **Settings → People → Users → Add User**.
2. Display name: `ClothesCast`. Username: `clothescast`.
3. Set a strong password — this is what you'll paste into the
   ClothesCast app, not your everyday HA login.
4. **Leave "Administrator" unchecked.** The publisher only needs to
   publish on a single topic; admin rights would give it the run of
   the broker.
5. Save. Then in the ClothesCast app: Settings → Smart Home → enter
   your broker host, leave port `1883` (or flip TLS for `8883`), tick
   the username field and paste the password, save.

Don't reuse your own HA user for this. A dedicated account makes the
publisher's traffic easy to spot in MQTT logs and makes the access
revocable in one click if you ever want to turn the bridge off
permanently.

### (Optional) Least-privilege ACL

By default the Mosquitto add-on grants every authenticated HA user
full access to every topic. For a personal LAN-only setup that's
fine. If you'd rather lock the `clothescast` user down to only the
topics ClothesCast actually writes:

1. Create `/share/mosquitto/acl.conf` (via the **File Editor**
   add-on, the **Studio Code Server** add-on, or SSH) with:
   ```
   user clothescast
   topic write clothescast/default/#
   topic write homeassistant/#
   ```
   That grants write-only access on the topic prefix plus Home
   Assistant's MQTT discovery config topics, and nothing else. Adjust
   `clothescast/default/#` if you customised the prefix in the
   ClothesCast Smart Home settings.
2. Open the Mosquitto broker add-on's **Configuration** tab and set:
   ```yaml
   customize:
     active: true
     folder: mosquitto
   ```
   Save.
3. **Restart** the add-on so it picks up the new ACL.

Home Assistant's MQTT integration itself reads via the broker's own
internal connection (a separate, privileged client), so the ACL above
only constrains the ClothesCast publisher — it doesn't break HA's
ability to subscribe to the topic for the automation that speaks the
forecast.

> If you're running a **standalone Mosquitto** outside Home Assistant
> instead of the HA add-on, the user creation step uses
> `mosquitto_passwd -c /etc/mosquitto/passwd clothescast` and the
> same ACL file syntax in
> `mosquitto.conf` (`acl_file /etc/mosquitto/acl`). HA-side YAML for
> reading the sensor is identical.

## Home Assistant — auto-discovered entities

With MQTT discovery enabled in Home Assistant (the default for the MQTT
integration), ClothesCast creates these entities automatically after the
next successful publish:

- `sensor.clothescast_day` from `<prefix>/day/text`
- `sensor.clothescast_night` from `<prefix>/night/text`
- `sensor.clothescast_now` from `<prefix>/now/text`
- `sensor.clothescast_now_updated` from `<prefix>/now/timestamp`
- `sensor.clothescast_day_updated` from `<prefix>/day/timestamp`
- `sensor.clothescast_night_updated` from `<prefix>/night/timestamp`
- `image.clothescast_day_image` from `<prefix>/day/image`
- `image.clothescast_night_image` from `<prefix>/night/image`
- `image.clothescast_now_image` from `<prefix>/now/image`

All entities are grouped under a single ClothesCast device in Home
Assistant. If a broker ACL rejects `homeassistant/#`, the forecast
topics still publish normally, but HA will not auto-create the entities;
allow the discovery topic or use the manual YAML below.

## Home Assistant — manual YAML fallback

Add the following to `configuration.yaml` (or anywhere your
`mqtt:` block lives) only if you do not want to use MQTT discovery or
your broker ACL blocks the discovery config topics:

<!-- raw guard: the snippets below are Home Assistant Jinja templates, not
Jekyll Liquid. Without this guard GitHub Pages runs Liquid over them first and
fails the build on the set/if tags. Keep everything through the matching
endraw verbatim. -->
{% raw %}
```yaml
mqtt:
  sensor:
    - name: "Clothescast day"
      unique_id: clothescast_day
      state_topic: "clothescast/default/day/text"
      value_template: "{{ value }}"
    - name: "Clothescast night"
      unique_id: clothescast_night
      state_topic: "clothescast/default/night/text"
      value_template: "{{ value }}"
    # Single "latest" sensor — mirrors the current window, so an automation
    # can speak the most recent forecast without branching on day vs night.
    - name: "Clothescast now"
      unique_id: clothescast_now
      state_topic: "clothescast/default/now/text"
      value_template: "{{ value }}"
    # Commit marker — epoch-millis written *last* once the whole `now`
    # bundle is in place. Trigger your automations on a state change of
    # this sensor (not on the prose sensor) when you want them to fire on
    # each new forecast: by the time it changes, now/text, now/image,
    # now/audio, and now/video are all settled and coherent.
    - name: "Clothescast now updated"
      unique_id: clothescast_now_updated
      state_topic: "clothescast/default/now/timestamp"
      device_class: timestamp
      # Topic carries epoch milliseconds; the timestamp device_class wants a
      # timezone-aware datetime, which as_datetime() returns (UTC) for a
      # numeric unix timestamp in seconds.
      value_template: "{{ as_datetime((value | int) / 1000) }}"
```

Restart Home Assistant or reload MQTT entries; the sensors should
populate within seconds of the next ClothesCast refresh (or instantly,
if you've already done one — retained messages are delivered to new
subscribers on connect).

To fire an automation each time a fresh forecast lands, trigger on the
commit marker rather than the prose sensor:

```yaml
triggers:
  - trigger: state
    entity_id: sensor.clothescast_now_updated
```

This guarantees every `now/*` topic is already in place when the
automation runs, and — because the marker only advances on a genuinely
new bundle — it won't re-fire when HA reconnects and re-reads the
retained set.

## Home Assistant — don't speak a stale forecast

The `now/*` topics are **retained**, so the prose sensor always holds
the last forecast ClothesCast published — even if that was two days ago
because your phone was off, the app was killed, or the broker was
unreachable. An automation that triggers on the commit marker
(`sensor.clothescast_now_updated` state-change, above) is fresh by
construction: it only fires when a genuinely new bundle lands. But an
automation triggered by **time of day** ("speak the forecast at 07:01")
reads whatever's retained — and that can be stale.

ClothesCast's twice-daily worker means a current forecast is at most
~12 hours old, so gate any time-triggered automation on the age of the
commit marker. `sensor.clothescast_now_updated` is a `timestamp`
device-class entity, so its state is already a timezone-aware datetime
you can subtract from `now()`:

```yaml
triggers:
  - trigger: time
    at: "07:01:00"

conditions:
  # Skip if the latest forecast is more than 12 hours old — the worker
  # refreshes roughly twice a day, so anything older means a missed run.
  - condition: template
    value_template: >
      {{ has_value('sensor.clothescast_now_updated')
         and (now() - states('sensor.clothescast_now_updated') | as_datetime).total_seconds() < 12 * 3600 }}

actions:
  - action: tts.speak
    # … your speak / cast action here …
```

The `has_value(...)` guard covers the cold-start case where the sensor
hasn't received a retained message yet (`unknown` / `unavailable`),
which would otherwise throw in the `as_datetime` subtraction. Tune the
`12 * 3600` threshold to taste — drop it lower if you'd rather stay
silent than read a forecast from the previous run.

## Home Assistant — a dashboard card with a freshness footer

To put the forecast on a Lovelace dashboard *and* make staleness
obvious at a glance, a Markdown card reads the prose sensor and appends
a relative "generated at" footer from the commit marker. A relative age
("Updated 2 hours ago") beats a fixed label like "Sunday's forecast":
the `now` sensor mirrors whichever period last published, so there's no
single day to name — and "Updated 2 days ago" is self-evidently stale in
a way a weekday name never is.

```yaml
type: markdown
content: >
  {{ states('sensor.clothescast_now') }}

  {% set updated = states('sensor.clothescast_now_updated') %}
  {% if has_value('sensor.clothescast_now_updated') %}
    {% set age = (now() - updated | as_datetime).total_seconds() %}
    {% if age > 12 * 3600 %}
  ⚠️ *Stale — updated {{ relative_time(updated | as_datetime) }} ago*
    {% else %}
  *Updated {{ relative_time(updated | as_datetime) }} ago*
    {% endif %}
  {% else %}
  *No forecast received yet*
  {% endif %}
```

`relative_time()` renders the age as a human phrase ("2 hours", "1 day")
and the `age > 12 * 3600` branch swaps in a ⚠️ warning once the forecast
crosses the same 12-hour line the automation above uses to stay silent —
so the dashboard and your spoken briefing agree on what counts as stale.

## Home Assistant — outfit image on Nest Hub

Alongside the prose sensor, ClothesCast publishes a PNG outfit card to
`<prefix>/<period>/image` (e.g. `clothescast/default/day/image`). The
card is 800 × 480 px (Nest Hub 7" native resolution) and shows:

- Period label ("TODAY" / "TONIGHT") in Roboto Bold at the top
- Top and bottom garment icons stacked in the left column
- The full insight prose sentence wrapped in Roboto Regular on the right

HA's `camera.mqtt` integration turns the retained binary payload into a
`camera.*` entity; a one-line automation then pushes it to the Nest Hub
display via `media_player.play_media`.

The result: at your morning alarm time the Hub shows the outfit picture
alongside the spoken briefing — "T-shirt and shorts" on screen while
the voice reads the full forecast.

### Add the camera entities to `configuration.yaml`

Add under your existing `mqtt:` block (no `platform: mqtt` line — that
is implied when the entry sits under `mqtt:`):

```yaml
mqtt:
  camera:
    - name: "Clothescast day outfit"
      topic: "clothescast/default/day/image"
    - name: "Clothescast night outfit"
      topic: "clothescast/default/night/image"
```

Reload MQTT (Developer Tools → YAML → Reload MQTT) or restart HA so
the entities appear as `camera.clothescast_day_outfit` and
`camera.clothescast_night_outfit`.

Then find each entity's access token in Developer Tools → States →
search `camera.clothescast` → open Details. Copy the `access_token`
value — you'll need it in the automation below.

### Automation to push the image to the Hub

```yaml
alias: Show outfit on kitchen Hub at 07:01
description: ""
mode: single

triggers:
  - trigger: time
    at: "07:01:00"

conditions: []

actions:
  - action: media_player.play_media
    target:
      entity_id: media_player.kitchen_hub
    data:
      media_content_id: "http://192.168.x.x:8123/api/camera_proxy/camera.clothescast_day_outfit?token=<access_token>"
      media_content_type: image/jpeg
```

Replace `media_player.kitchen_hub` with your actual Nest Hub entity ID
(find it under Settings → Devices & Services → Google Cast),
`192.168.x.x` with your HA instance's local IP address, and
`<access_token>` with the token you copied from the entity details.

**Use the IP address, not `homeassistant.local`.** mDNS does not
resolve across VLANs or subnets, so the Hub will fail to fetch the
image if you use a hostname. The IP address works regardless of network
topology as long as the Hub can reach your HA instance on port 8123.

The access token is long-lived and stable across refreshes — unlike the
`entity_picture` session token it does not rotate on each payload
change, so you only need to paste it once. You can combine this action
with Option A/B/C below in a single automation so the Hub shows the
picture *and* speaks the forecast — either at the same moment, or
serially (speak first, then show the picture). See "Chaining the
spoken forecast and the outfit image" further down for the YAML
shape.

> **Note on external URLs.** If your Nest Hub cannot reach your HA
> instance's local IP directly, use HA's external URL
> (`https://your-ha.duckdns.org`) instead. The camera proxy path and
> access token are the same either way.

## Home Assistant — combined card + announcement video

For a Nest Hub, the tidiest result is one media item that shows the
outfit card *and* plays the spoken briefing over it, with no parallel
TTS + image dance. ClothesCast publishes exactly that to
`<prefix>/<period>/video` (mirrored to `<prefix>/now/video`): an MP4
with the 800 × 480 outfit card as a static video frame and the Gemini
TTS audio as the audio track. Hand it to a Cast receiver and the card
fills the screen while the forecast plays.

This is published only when **both** an outfit image and Gemini TTS
audio exist for the delivery (Gemini engine selected, audio not
suppressed by "don't speak at home"); otherwise the slot is cleared
with an empty retained payload like any other absent modality.

Like the audio clip, `media_player.play_media` needs an HTTP URL and HA
won't read an MQTT binary payload into the media player directly — so
the MP4 has to land somewhere HA serves over HTTP (e.g.
`config/www/clothescast_now.mp4`, fetchable at
`http://<ha-ip>:8123/local/clothescast_now.mp4`). Use the same
write-to-file recipe as the audio clip below (Node-RED `mqtt in` →
`file`, or a `shell_command` running `mosquitto_sub`), pointed at
`clothescast/default/now/video`, then cast it:

```yaml
triggers:
  - trigger: state
    entity_id: sensor.clothescast_now_updated

actions:
  - action: media_player.play_media
    target:
      entity_id: media_player.kitchen_hub
    data:
      media_content_id: "http://192.168.x.x:8123/local/clothescast_now.mp4"
      media_content_type: video/mp4
```

> **The card disappears when the clip ends.** A Cast receiver returns to
> its idle / photo screen once the MP4 finishes, so the card stays up
> only for the length of the announcement. Keeping it up longer is a
> known follow-up (a configurable post-speech hold baked into the clip).

## Home Assistant — TTS audio clip on the audio topic

When the Gemini TTS engine is selected (Settings → Voice → Gemini),
ClothesCast publishes the synthesised audio as a WAV clip to
`<prefix>/<period>/audio` (e.g. `clothescast/default/day/audio`).
The payload is signed 16-bit mono PCM at the sample rate Gemini
returned, wrapped in a canonical 44-byte RIFF/WAVE header — playable
as-is by ffmpeg, browsers, and `media_player.play_media` when handed
via an HTTP fetch (HA doesn't read MQTT binary payloads directly into
the media player). It's exactly the bytes the phone speaks, so any
"speak it on the Hub" automation built around the prose sensor or one
of the TTS options below stays in lockstep with the phone.

Two things to know before you wire anything up:

- **Gemini-only.** Device TTS doesn't expose its audio buffer before
  playback, so the audio topic stays empty when the on-device engine
  is selected. Switch to Gemini in Settings → Voice to populate it.
- **Skip-TTS-at-home applies.** The audio is published from the same
  code path that plays it on the phone, so if "Don't speak the
  forecast at home" is on and you're at home, neither the phone nor
  the Hub gets the clip. Toggle that off if you want the Hub to speak
  even when you're home.

The simplest way to make the Hub actually speak is still options A /
B / C below: trigger an automation on the `now/timestamp` commit marker
(the `sensor.clothescast_now_updated` sensor above) and let HA's TTS
service synthesise — that path is already paved end to end. Triggering
on the commit marker rather than the prose sensor guarantees the audio
and image are already in place when the automation runs. The audio topic is here for setups that already prefer a fixed
voice clip over re-synthesising in HA (e.g. `music_assistant.play_announcement`
→ media URL flows), and for users who want to capture the rendered
briefing for their own pipelines.

### Recipe: serve the clip as a static file (Node-RED)

`media_player.play_media` needs an HTTP URL, and HA won't read an MQTT
binary payload into the media player directly — so the WAV has to land
on disk somewhere HA serves over HTTP. HA exposes the `config/www/`
directory at `/local/`, so a file written to
`config/www/clothescast_now.wav` is fetchable at
`http://<ha-ip>:8123/local/clothescast_now.wav`.

The Node-RED add-on is the least-fiddly way to do that write — it holds
an always-on subscription and dumps the raw bytes in three nodes:

1. **`mqtt in`** — topic `clothescast/default/now/audio`, **Output** set
   to *a Buffer* (not "a parsed JSON object" or "a String"). The payload
   is binary WAV; decoding it as a string corrupts it.
2. **`switch`** — drop the empty retained "clear" payloads. Add one rule,
   `msg.payload` → *length* (or a `function` node returning the message
   only when `msg.payload.length > 0`). Without this guard a clear
   message (see the empty-retained-payload note near the top of this
   doc) truncates `clothescast_now.wav` to zero bytes the moment a
   period publishes with no audio.
3. **`file`** (write) — filename `/config/www/clothescast_now.wav`,
   action *overwrite file*, "add newline to each payload" **off**. Wire
   `mqtt in` → `switch` → `file`.

Create `config/www/` first if it doesn't already exist (HA only
auto-creates it in some installs). After the next ClothesCast refresh —
or immediately, since the audio topic is retained — the file appears and
is served at `http://<ha-ip>:8123/local/clothescast_now.wav`.

Then play it from any automation:

```yaml
- action: media_player.play_media
  target:
    entity_id: media_player.kitchen_hub
  data:
    media_content_id: "http://192.168.x.x:8123/local/clothescast_now.wav"
    media_content_type: music
```

Use the IP address, not `homeassistant.local`, for the same cross-VLAN
mDNS reason as the outfit-image automation above. `music` is the most
broadly accepted `media_content_type` for a raw audio URL on Cast
targets; `audio/x-wav` also works on most.

**No-Node-RED alternative.** A `shell_command` wrapping
`mosquitto_sub -C 1 -t clothescast/default/now/audio > /config/www/clothescast_now.wav`
does the same write, but it re-subscribes on every call and you have to
guard the empty-payload clears yourself (a zero-byte capture overwrites
the good clip). Node-RED's persistent subscription plus the `switch`
guard handle both cleanly, which is why it's the recommended path.

## Home Assistant — speaking the sensor on Google Home

As of mid-2026, getting Google Home / Nest devices to actually *speak*
arbitrary text on demand is genuinely fiddly — Google has been actively
churning the Cast pipeline and several "obvious" paths are flaky on
Nest Minis specifically. Three options below; quick comparison first.

| | **A. notify.google_assistant_sdk** | **B. `tts.speak` → MA `media_player`** | **C. tts.cloud_say / tts.google_translate_say** |
|---|---|---|---|
| Setup cost | Google Cloud project + OAuth in HA | One add-on install | Nabu Casa sub (`cloud_say`) or none (`google_translate_say`) |
| Preamble before the text | **Yes** — Google's broadcast service prepends "There's a message, it says…" / "Here's a message for X: …" with no flag to suppress | **No** | No |
| Targets a specific speaker | Yes — if HA language is bare "English" (en-US). Other "English (XX)" entries fall back to broadcast-to-all. | Yes — always, via `media_player.*` entity ID; no locale dependency | Yes — always, via `entity_id: media_player.*` |
| Target value format | Bare room name from Google Home (`Master Bathroom`) | HA entity ID (`media_player.master_bathroom_display`) | HA entity ID (`media_player.master_bathroom_display`) |
| Voice quality | Google Assistant voice (good) | Cast TTS — either Google Translate (free) or Nabu Casa (good) | Same as Music Assistant uses, just without the player-state restoration |
| Reliability on Nest Mini | Best (sidesteps Cast pipe entirely) | OK — wraps Cast with state-restoration, masks some flakiness | Worst on Nest Mini specifically as of early 2026 |
| Reliability on Nest Hub | Good | Good — Hubs tolerate Cast TTS better than Minis do | OK |
| State restoration (resumes previous playback) | N/A — broadcast doesn't take over playback | **Yes** | No |

Rough decision tree:

- Want the **simplest setup** and don't mind a preamble: **Option A**.
- Want the speaker to **just speak the forecast** (no preamble), or
  you're on a non-en-US English variant, or you have Nest Hubs with
  displays: **Option B**.
- You already have `tts.cloud_say` working on your hardware and don't
  want to install Music Assistant: **Option C**.

Three options in detail:

The YAML below is in the **UI-editor format** Home Assistant 2024.10+
uses: top-level `alias:` / `triggers:` / `actions:`, with `trigger:`
inside each trigger item and `action:` inside each action item. Paste
directly into Settings → Automations → New automation → Edit in YAML.
If you maintain `configuration.yaml` by hand, wrap each example in
`automation:` and convert `triggers:` / `trigger:` / `actions:` /
`action:` to the old plural-less form yourself.

### Option A (simplest): `notify.google_assistant_sdk`

Uses Google's *own* broadcast pipeline — the same backend as a spoken
"Hey Google, broadcast …" — rather than pushing audio over Cast.
Because it sidesteps Cast TTS entirely, it tends to work where
`tts.cloud_say` and the Music Assistant TTS-via-announce path
currently fail.

Setup needs a Google Cloud project with OAuth credentials added to
Home Assistant (one-time setup, documented in the
[google_assistant_sdk integration page](https://www.home-assistant.io/integrations/google_assistant_sdk/)).

```yaml
alias: Speak forecast when wardrobe opens
description: ""
mode: single

triggers:
  - trigger: state
    entity_id: binary_sensor.wardrobe_door
    to: "on"

conditions: []

actions:
  - action: notify.google_assistant_sdk
    data:
      message: "{{ states('sensor.clothescast_day') }}"
      target:
        - Master Bathroom
```

**Two caveats specific to this path:**

1. **A Google broadcast preamble is baked into the service** ("There's
   a message, it says…", or for a targeted broadcast something like
   "Here's a message for Master Bathroom: …") and there is no flag to
   suppress it. If you want the speaker to just speak the forecast
   with no preamble, use Option B below.

2. **The `target:` field is the room name as it appears in Google
   Home, on its own.** Just `Master Bathroom` — not the combined
   "Master Bathroom Display - Master Bathroom" string Google Home
   shows in its device listing, and not the device name on its own.
   Targeting only routes when HA's interface language resolves to
   English (US). Settings → System → General → Language: **"English"**
   (no country suffix) resolves to en-US under the hood — that's the
   one that works, and matches what most installs default to.
   "English (United Kingdom)" is a *distinct* option in HA's language
   picker — if you've explicitly chosen that one, the integration
   silently appends the target string to the broadcast text instead
   of routing, and you'll hear "to master bathroom" spoken out loud
   followed by the forecast going to every speaker.

   (HA's **Country** field on the Home Information page is separate
   metadata — sun position, currency, etc. — and doesn't affect the
   SDK locale.)

   If you've intentionally picked "English (United Kingdom)" for date
   / number formatting and want to keep it, use Option B below; it
   routes via `media_player.*` entities and is locale-independent.

### Option B (preamble-free, locale-independent): HA `tts.speak` → Music Assistant `media_player`

Plays HA TTS through a Music Assistant-owned `media_player.*` entity.
MA exposes the player's `MEDIA_ANNOUNCE` capability, so HA's `tts.speak`
routes through MA's announcement queue automatically — you get state
restoration (whatever was playing before the announcement resumes
after) without naming an MA-specific service. No "There's a message,
it says…" preamble; targeting is by HA entity ID so it's
locale-independent.

The price of admission is the **Music Assistant Server** add-on
(Settings → Add-ons → Add-on Store → Music Assistant → Install →
Start) plus the **Music Assistant** core HA integration
(Settings → Devices & Services → Add Integration → Music Assistant —
auto-detects a Server running on the same HA instance). Once both are
up, MA auto-discovers your Cast devices and exposes them as
`media_player.*` entities. You'll also need at least one TTS engine
configured — `tts.home_assistant_cloud` if you have Nabu Casa,
otherwise the free Google Translate integration (Settings →
Devices & Services → Add Integration → Google Translate text-to-speech).
It generates a per-language `tts.*` entity (e.g. `tts.google_en_com`),
which is what you'll target in the example below.

```yaml
alias: Speak forecast when wardrobe opens
description: ""
mode: single

triggers:
  - trigger: state
    entity_id: binary_sensor.wardrobe_door
    to: "on"

conditions: []

actions:
  - action: tts.speak
    target:
      entity_id: tts.home_assistant_cloud
    data:
      media_player_entity_id: media_player.master_bathroom_display
      message: "{{ states('sensor.clothescast_day') }}"
```

`tts.home_assistant_cloud` is the canonical entity for Nabu Casa
TTS. If you're using a different TTS engine, replace the
`target.entity_id` with whatever `tts.*` entity HA created for it —
the Google Translate integration, for instance, generates a
per-language entity like `tts.google_en_com`, not a bare
`tts.google_translate`; find the actual ID in Developer Tools →
States → search `tts.`.

The `media_player_entity_id` here is the **MA-owned** entity for that
speaker (it appears under HA's Music Assistant integration after
discovery completes). Pointing at it is what triggers MA's
announcement-queue / state-restoration behaviour; pointing the same
field at the raw Cast `media_player.*` entity instead works too,
that's Option C below, but skips the state-restoration wrapper.

> **Note on legacy `mass.announce`.** Older guides (including earlier
> revisions of this one) use `mass.announce` with `message:` +
> `target_player:`. That was the HACS-era custom integration's
> service and is no longer available in the Music Assistant core
> integration as of 2025 — swap in the `tts.speak` shape above.

Still rides the Cast pipe under the hood, so subject to the early-2026
"Nest Mini intermittently silent" reports — but Music Assistant's
player-state handling masks a lot of the flakiness, and Nest Hubs
(with displays) generally behave better on the Cast path than the
smaller Minis do.

### Option C: `tts.cloud_say` → `media_player.*` Cast

The classic, simplest setup — but currently the **least** reliable on
Nest Mini specifically. Listed for completeness; not recommended as
the first thing to try. If it works on your hardware, great; if it
doesn't, jump to Option A or B.

```yaml
alias: Speak forecast when wardrobe opens
description: ""
mode: single

triggers:
  - trigger: state
    entity_id: binary_sensor.wardrobe_door
    to: "on"

conditions: []

actions:
  - action: tts.cloud_say
    data:
      entity_id: media_player.master_bathroom_display
      message: "{{ states('sensor.clothescast_day') }}"
```

(`tts.google_translate_say` is the free alternative if you don't have
Nabu Casa — same shape, robot-Google-Translate voice.)

## Home Assistant — chaining the spoken forecast and the outfit image

Two automations side by side work fine, but a single automation is
easier to keep in sync (one trigger time, one entity to retarget when
you move the Hub). Two patterns, depending on whether you want them
at the same moment or one after the other.

Both examples below use the Option A (`notify.google_assistant_sdk`)
shape, since that's the path verified end-to-end on this project's
own setup. The picker below the serial example covers what to swap
in if you're on Option B or C instead. Each TTS service expects a
different `data:` shape — `target:` for Option A,
`media_player_entity_id:` for B, `entity_id:` for C — so don't
hot-swap the service inside an example; copy the YAML for the
service you're actually using from the option section above and
slot it into the chaining shape.

**Parallel — speak and show together.** Drop both actions in the same
`actions:` list with no wait between them. Cast pipes the TTS to the
speaker channel and `play_media` to the display channel; on a Nest
Hub they don't collide:

```yaml
alias: ClothesCast on kitchen Hub at 07:01 (parallel)
mode: single

triggers:
  - trigger: time
    at: "07:01:00"

actions:
  - action: notify.google_assistant_sdk
    data:
      message: "{{ states('sensor.clothescast_day') }}"
      target:
        - Kitchen
  - action: media_player.play_media
    target:
      entity_id: media_player.kitchen_hub
    data:
      media_content_id: "http://192.168.x.x:8123/api/camera_proxy/camera.clothescast_day_outfit?token=<access_token>"
      media_content_type: image/jpeg
```

**Serial — speak first, then show.** Cast's `media_player.play_media`
with `image/jpeg` interrupts whatever's currently rendering, including
in-flight TTS, so for the serial pattern you wait for the speech to
finish before pushing the picture. A fixed `delay:` sized to your
longest spoken briefing is the simplest reliable wait, and works
regardless of which TTS service you've picked — see the picker
under the example for the small B/C swap-in note (and why a
state-based wait isn't recommended even when the player exposes
state).

```yaml
alias: ClothesCast on kitchen Hub at 07:01 (serial)
mode: single

triggers:
  - trigger: time
    at: "07:01:00"

actions:
  # 1. Speak the forecast.
  - action: notify.google_assistant_sdk
    data:
      message: "{{ states('sensor.clothescast_day') }}"
      target:
        - Kitchen

  # 2. Wait for the spoken clip to finish before swapping the display.
  #    Pad a few seconds past your longest briefing — the SDK includes
  #    a "There's a message…" preamble, so allow for that too.
  - delay: "00:00:18"

  # 3. Push the outfit picture.
  - action: media_player.play_media
    target:
      entity_id: media_player.kitchen_hub
    data:
      media_content_id: "http://192.168.x.x:8123/api/camera_proxy/camera.clothescast_day_outfit?token=<access_token>"
      media_content_type: image/jpeg
```
{% endraw %}

**Picker — swapping in Option B or C.** Replace the step-1 action
with the Option B or Option C YAML from those sections. The fixed
`delay:` in step 2 stays as-is — it works for any TTS service that
takes roughly a known time to speak.

> **Edge case — speaker was already playing music (Option B
> specifically).** Music Assistant restores the prior playback
> *after* the announcement finishes, so if the Hub was playing
> music when the automation fired, the image step at the end of
> the serial recipe will fire while MA is back to playing music
> and interrupt it. This applies regardless of wait mechanism
> (fixed `delay:` or state-based) because the restored playback
> happens before the delay even runs out. If your setup can have
> the Hub playing music at trigger time, gate the automation with
> `condition: state … idle` on the player up front, or pause the
> player before step 1.

If you're tempted to swap the `delay:` for a state-based wait on
`media_player.*` instead, don't, at least not without testing it
end-to-end on your specific setup. Options B and C *do* expose
`playing` → `idle` transitions on the player, but the obvious
two-stage `wait_template` (wait for non-idle, then wait for idle)
has two failure modes specific to the state-based approach:
>
> 1. **Slow TTS start.** If the TTS service takes more than the
>    initial wait's timeout to actually start playing, the first
>    `wait_template` times out with `continue_on_timeout: true`, the
>    second one then sees `idle` (because TTS hasn't started yet) and
>    trips immediately, and the image fires just as the spoken clip
>    finally arrives. You'd need a `wait.completed` check to abort
>    cleanly when the start wait times out.
> 2. **Variable briefing length saves at most a few seconds.** A
>    fixed `delay:` is conservative by design; the state-based wait
>    only saves you the slack you padded into the delay. Rarely
>    worth the complexity for a once-a-day automation.

(The "already playing" edge case above isn't on this list because
it's not specific to wait_template — it's an MA-restoration
property that applies to any wait shape.)

> **What's verified.** The Option A (`notify.google_assistant_sdk`)
> path above is the one this project's author runs day-to-day. The
> Option B and C variants follow current HA service shapes but
> haven't been tested end-to-end on this setup — if you wire one up
> and find a wrinkle, a PR-fix is welcome.

## Troubleshooting

- **No payload arriving.** Subscribe from a terminal that's on the
  same network as the broker:
  ```
  mosquitto_sub -h <broker-host> -u <user> -P <pass> -t 'clothescast/default/#' -v
  ```
  Then trigger a refresh from the ClothesCast Today screen. The
  payload should appear within ~5 seconds of the
  `Insight delivered for …` log line.
- **MQTT publish failed in the diag log.** The broker host or port is
  wrong, the credentials are wrong, or the device isn't on the same
  network as the broker. The publisher uses a 5-second timeout, so a
  silently-unreachable broker logs a single "MQTT publish timed out"
  line and the worker continues to its next run.
- **Sensor exists but stays "unknown" in Home Assistant.** The retained
  message hasn't been published yet. Either the bridge is off in
  ClothesCast settings, or the broker hasn't seen any refresh since you
  added the sensor. Trigger a manual refresh from the ClothesCast Today
  screen.
- **Sensor populates, but Google Home doesn't speak.** Skip to the
  three-option list above. The "speak the value" step is independent
  of the "read the value" step and is where most Google Home /
  Home Assistant deployments hit their wall.

## Privacy

See [PRIVACY.md](../PRIVACY.md) for the full data-handling boundary.
Quickly: the bridge is off by default, and turning it on sends the
rendered forecast sentence to **your own broker** — not to a
developer-operated service. The payload is the same string you see in
the notification, including any calendar-event tie-in clause if you
have the calendar tie-in enabled.
