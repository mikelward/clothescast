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
2. Enter your broker's hostname (e.g. `homeassistant.local`).
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

## Home Assistant — speaking the sensor on Google Home

As of mid-2026, getting Google Home / Nest devices to actually *speak*
arbitrary text on demand is genuinely fiddly — Google has been actively
churning the Cast pipeline and several "obvious" paths are flaky on
Nest Minis specifically. Three options, ranked by "should work":

### Option A (recommended): `notify.google_assistant_sdk`

Uses Google's *own* broadcast pipeline — the same backend as a spoken
"Hey Google, broadcast …" — rather than pushing audio over Cast.
Because it sidesteps Cast TTS entirely, it tends to work where
`tts.cloud_say` and Music Assistant `mass.announce` currently fail.

Setup needs a Google Cloud project with OAuth credentials added to
Home Assistant (one-time setup, documented in the
[google_assistant_sdk integration page](https://www.home-assistant.io/integrations/google_assistant_sdk/)).

**Known quirk:** the `target:` field only honours specific speakers
when Home Assistant's language is `en-US`; non-en-US installs broadcast
to all speakers regardless of `target:`. For a wardrobe-door
announcement that's probably fine.

```yaml
automation:
  - alias: "Speak forecast when wardrobe opens"
    trigger:
      platform: state
      entity_id: binary_sensor.wardrobe_door
      to: "on"
    action:
      service: notify.google_assistant_sdk
      data:
        message: "{{ states('sensor.clothescast_today') }}"
```

### Option B: Music Assistant `mass.announce`

Cast-based, but wraps Cast TTS with state-restoration (it resumes
whatever the speaker was playing) and is the community-recommended
modern replacement for ad-hoc `tts.cloud_say` automations. Still rides
the Cast pipe, so subject to the early-2026 "Nest Mini intermittently
silent" reports — but Music Assistant's player-state handling masks a
lot of the flakiness. See the
[Music Assistant announcement docs](https://www.music-assistant.io/integration/announcements/).

### Option C: `tts.cloud_say` → `media_player.*` Cast

The classic, simplest setup — but currently the **least** reliable on
Nest Mini specifically. Listed for completeness; not recommended as
the first thing to try. If it works on your hardware, great; if it
doesn't, jump to Option A.

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
