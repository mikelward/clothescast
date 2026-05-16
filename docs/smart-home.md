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
5. Topic prefix defaults to `clothescast/insight`. Today's forecast
   publishes to `<prefix>/today`; tonight's to `<prefix>/tonight`.
6. Tap **Save**.

The next scheduled refresh (07:00 by default for today, 19:00 for
tonight — configurable in Settings → Schedule) will publish a retained
MQTT message to each topic.

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
   topic write clothescast/insight/#
   ```
   That grants write-only access on the topic prefix and nothing else.
   Adjust the topic if you customised the prefix in the ClothesCast
   Smart Home settings.
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

## Home Assistant — reading the sensor

Add the following to `configuration.yaml` (or anywhere your
`mqtt:` block lives):

```yaml
mqtt:
  sensor:
    - name: "Clothescast today"
      unique_id: clothescast_today
      state_topic: "clothescast/insight/today"
      value_template: "{{ value }}"
    - name: "Clothescast tonight"
      unique_id: clothescast_tonight
      state_topic: "clothescast/insight/tonight"
      value_template: "{{ value }}"
```

Restart Home Assistant or reload MQTT entries; the sensors should
populate within seconds of the next ClothesCast refresh (or instantly,
if you've already done one — retained messages are delivered to new
subscribers on connect).

## Home Assistant — outfit image on Nest Hub

Alongside the prose sensor, ClothesCast publishes a PNG outfit card to
`<prefix>/<period>/image` (e.g. `clothescast/insight/today/image`). The
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
    - name: "Clothescast today outfit"
      topic: "clothescast/insight/today/image"
    - name: "Clothescast tonight outfit"
      topic: "clothescast/insight/tonight/image"
```

Reload MQTT (Developer Tools → YAML → Reload MQTT) or restart HA so
the entities appear as `camera.clothescast_today_outfit` and
`camera.clothescast_tonight_outfit`.

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
      media_content_id: "http://192.168.x.x:8123/api/camera_proxy/camera.clothescast_today_outfit?token=<access_token>"
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
picture *and* speaks the forecast at the same moment.

> **Note on external URLs.** If your Nest Hub cannot reach your HA
> instance's local IP directly, use HA's external URL
> (`https://your-ha.duckdns.org`) instead. The camera proxy path and
> access token are the same either way.

## Home Assistant — TTS audio clip on the audio topic

When the Gemini TTS engine is selected (Settings → Voice → Gemini),
ClothesCast publishes the synthesised audio as a WAV clip to
`<prefix>/<period>/audio` (e.g. `clothescast/insight/today/audio`).
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
B / C below: trigger an automation on the prose sensor's update and
let HA's TTS service synthesise — that path is already paved end to
end. The audio topic is here for setups that already prefer a fixed
voice clip over re-synthesising in HA (e.g. mass.announce → media URL
flows), and for users who want to capture the rendered briefing for
their own pipelines.

## Home Assistant — speaking the sensor on Google Home

As of mid-2026, getting Google Home / Nest devices to actually *speak*
arbitrary text on demand is genuinely fiddly — Google has been actively
churning the Cast pipeline and several "obvious" paths are flaky on
Nest Minis specifically. Three options below; quick comparison first.

| | **A. notify.google_assistant_sdk** | **B. mass.announce** (Music Assistant) | **C. tts.cloud_say / tts.google_translate_say** |
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
`tts.cloud_say` and Music Assistant `mass.announce` currently fail.

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
      message: "{{ states('sensor.clothescast_today') }}"
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

### Option B (preamble-free, locale-independent): Music Assistant `mass.announce`

Drives speakers via their HA `media_player.*` entities directly,
bypassing Google's broadcast pipeline — so no "There's a message, it
says…" preamble, and targeting works regardless of HA's language.
Music Assistant additionally wraps Cast TTS with state-restoration
(resumes whatever was playing on the speaker before the announcement).

The price of admission is one add-on install: **Settings → Add-ons →
Add-on Store → Music Assistant → Install → Start**. Once running, it
auto-discovers your Cast devices and exposes them as
`media_player.*` entities. Then:

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
  - action: mass.announce
    data:
      message: "{{ states('sensor.clothescast_today') }}"
      target_player: media_player.master_bathroom_display
```

See the
[Music Assistant announcement docs](https://www.music-assistant.io/integration/announcements/)
for the full surface (volume override, "use pre-announce chime",
multiple targets, etc.). Still rides the Cast pipe under the hood, so
subject to the early-2026 "Nest Mini intermittently silent" reports —
but Music Assistant's player-state handling masks a lot of the
flakiness, and Nest Hubs (with displays) generally behave better on
the Cast path than the smaller Minis do.

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
      message: "{{ states('sensor.clothescast_today') }}"
```

(`tts.google_translate_say` is the free alternative if you don't have
Nabu Casa — same shape, robot-Google-Translate voice.)

## Troubleshooting

- **No payload arriving.** Subscribe from a terminal that's on the
  same network as the broker:
  ```
  mosquitto_sub -h <broker-host> -u <user> -P <pass> -t 'clothescast/insight/#' -v
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
