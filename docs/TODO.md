# ClothesCast — TODO

Living to-do list. Items are roughly ordered by priority within each section.
Code TODOs in source files are linked from here when they exist.

## Pre-publishing blockers

- [x] **Pick a stable namespace + applicationId.** Pinned to `app.clothescast`
      (reverse-DNS of the planned `clothescast.app` domain). Renamed from
      `app.adaptweather` as part of the product rename — Android treats the
      new applicationId as a different app, so existing FAD testers had to
      uninstall + reinstall and lose their stored settings.
- [x] **Pick a stable product name.** Settled on "ClothesCast" (user-visible
      strings, icon, and applicationId all updated to `app.clothescast`).

## Distribution

- [x] **Firebase App Distribution setup.** Push to `main` triggers a debug
      APK build signed with the stable keystore, uploaded to FAD with the
      commit message as release notes. Setup steps in
      [docs/firebase-app-distribution.md](firebase-app-distribution.md).
- [ ] **`.github/workflows/release.yml`** — tag-triggered, runs Maestro on
      Firebase Test Lab + cuts a GitHub Release with a release-signed APK.

## Voice / TTS

- [x] Gemini TTS as opt-in voice engine (PR #27)
- [x] Diagnostic "Test Gemini voice" button in Settings (PR #28)
- [x] **Voice picker** for Gemini. Curated list in `TtsVoices.kt`; Despina is the current default (validated across en-GB / en-AU / en-US / de-*).
- [ ] **Re-translate the Voice engine subhead + per-engine descriptions
      once the English copy is locked in.** PR #874 rewrote the base
      `settings_tts_engine_description`, `settings_api_key_gemini_header`,
      and added `settings_tts_device_header` to make the data-boundary
      tradeoff explicit ("low-quality on-device" vs. "high-quality via
      ClothesCast and Gemini servers"). The pre-existing `values-*`
      overrides (44 locales each for the first two strings) still carry
      the old copy, so non-English devices fall back to the stale
      translation rather than the new disclosure. Hold the re-translation
      until the English wording is settled, then either delete the stale
      overrides (forcing en-US fallback) or push fresh translations
      through the usual pipeline.

## Calendar integration (next-up after TTS)

- [x] **Read today's calendar events** (`CalendarContract`) so the daily
      insight can suggest items keyed to events: *"Bring an umbrella for your
      3pm park run."* Opt-in via Settings → Calendar (off by default), runtime
      `READ_CALENDAR` granted from the same card. The reader projects only
      titles, times, and locations; the rendered summary uses only title and
      time. The 6th sentence in `RenderInsightSummary` fires only when a
      clothes rule + a precip-peak event window both apply, preferring
      "umbrella" when on the clothes list. Reader failures degrade silently
      to no events.

## Forecast & alerts

- [x] **Severe weather alerts.** Open-Meteo's `/v1/warnings` is now wired up:
      alerts feed into `BuildPrompt`, and SEVERE / EXTREME alerts also fire a
      separate high-priority notification on a dedicated channel.
- [x] **Hourly forecast UI** on Today. Vico chart of temperature + feels-like
      across today's hours. Multi-day extension still possible.
- [ ] **Forecast accuracy ideas** — end-of-day accuracy survey, user-flagged
      incorrect forecasts, background multi-provider comparison. Sketched in
      [docs/MODELS.md](MODELS.md) (ideas 2-4). Idea 1 (confidence badge)
      shipped — see below.
- [x] **Multi-model confidence badge** (MODELS.md idea #1) — Today shows
      a chip indicating how much ECMWF / GFS / ICON disagree about today's
      apparent high and peak precip probability.
- [x] **User-selectable models for the spread overlay.** Multi-select
      Forecasters picker in Settings; threads the chosen set through
      `OpenMeteoClient`'s pluggable `confidenceModelsProvider`. Auto
      mode (default for fresh installs) picks a region-appropriate trio
      via `ForecastModel.defaultsFor(location)`. Picker capped at 5
      models for chart readability; BOM shown but disabled while
      Open-Meteo's BOM open-data feed is suspended.

## Smart Home / Home Assistant bridge

Opt-in MQTT bridge that publishes the rendered insight prose to a
user-hosted broker (typically the Mosquitto add-on inside HA) so
automations can speak the forecast on a sensor trigger — wardrobe
door, bathroom humidity, fixed time of day, etc. Setup guide at
[docs/smart-home.md](smart-home.md); the data-handling note is in
PRIVACY.md.

Done:

- [x] **MQTT publisher in the worker.** Twice-daily refresh publishes
      `clothescast/default/today/text` and
      `clothescast/default/tonight/text` as retained QoS 1 messages. Configured from a new Settings → Smart
      Home page (host, port, TLS, username, password, topic prefix).
      Password stored in SecureKeyStore under a separate Tink AEAD
      slot from the Gemini key. Initial PR #504, hardened against R8
      in #506 / #508 / #509, then the HiveMQ + Netty + JCTools +
      RxJava graph swapped for a hand-rolled MQTT 3.1.1 publisher
      (`RawMqttClient.kt`) on `java.net.Socket` /
      `javax.net.ssl.SSLSocketFactory` — saved 5.9 MB on the APK,
      removed ~70 lines of keep rules, RFC-6125 hostname verification
      enabled on the TLS socket so a CA-trusted cert for the wrong
      name fails before CONNECT is sent (PR #510).

Open:

- [ ] **"Publish now" button + persistent last-error card** on the
      Smart Home settings page. The bridge's failure modes (broker
      unreachable, IPv6 link-local mDNS resolution, TLS on a
      plain-only broker, wrong credentials) all surface today only
      via the diag log after a manual Refresh + Share-bug-report
      round-trip. A direct trigger that runs the publisher against
      the most recent cached insight, plus a card that pins the most
      recent error (or "last published OK at HH:MM" on success),
      collapses that loop to one tap. Architecturally needs an
      observable status flow on `MqttPublisher` or wrapping
      VM-state, plus a VM action that calls the publisher directly
      against `InsightCache.deliveredForToday(...)`. Worth combining
      with a "test connection" pre-flight (CONNECT/CONNACK only, no
      PUBLISH) so a misconfigured broker fails on Save without
      surfacing as a delivered-but-not-published surprise on the
      next refresh.
- [ ] **Publish the outfit image alongside the prose.** Nest Hubs and
      Nest Hub Maxes have displays; HA's
      [`image.mqtt`](https://www.home-assistant.io/integrations/image.mqtt/)
      platform consumes binary image payloads off MQTT and exposes
      them as `image.*` entities. The shape: rasterise the
      OutfitWidget composition (top + bottom icons, ~1280x800 for
      Hub Max scaling) to PNG, publish to
      `clothescast/default/<period>/image` as a retained binary
      payload. HA picks it up via `mqtt: image: - state_topic:` and a
      downstream automation calls `media_player.play_media` with the
      entity's `entity_picture` URL targeting the Hub's
      `media_player.*`. New code: ~50 lines (PNG rasteriser +
      binary MQTT publish branch in `RawMqttClient`; the wire format
      is identical to the text publish, just `byte[]` payload).
      Voice + visual on the kitchen / bathroom Hub at 07:00 is a
      much nicer UX than a disembodied audio broadcast. Privacy
      story is identical to the prose bridge — same
      "user-hosted-broker only" caveat, same opt-in.
- [ ] **HA MQTT discovery for the sensors.** Publish a discovery
      payload on `homeassistant/sensor/clothescast_today/config` so
      a fresh HA install picks up the `sensor.clothescast_today` and
      `sensor.clothescast_tonight` entities without the user editing
      `configuration.yaml` or pasting YAML into Devices & Services.
      The discovery JSON is small (`{"name": "...", "state_topic":
      "...", "unique_id": "..."}`). Same trick for `image.*` when
      the image feature lands above. Worth it for "drop in the
      Mosquitto add-on + flip the toggle in ClothesCast" zero-YAML
      setup.
- [ ] **Music Assistant `mass.announce` quick-start in the setup
      guide.** docs/smart-home.md now describes the three speaking
      options at a high level, but for users picking Option B the
      install-and-discover flow ("Add-on Store → Music Assistant →
      auto-discovers Cast devices → use `media_player.<entity>` as
      the `target_player`") could be a numbered walkthrough rather
      than a paragraph.
- [ ] **Standalone Mosquitto-broker setup walkthrough.** The current
      doc has a one-sentence aside on `mosquitto_passwd` /
      `mosquitto.conf` ACL syntax for users not running HA's add-on.
      Worth either expanding into a sibling sub-section or splitting
      to a dedicated `docs/smart-home-standalone-broker.md`.

## Feature ideas (queued)

- [ ] **Multiple daily insights** — morning + evening, configurable per slot.
      Needs a second alarm slot and the calendar reader above for the evening
      "what to bring tomorrow" briefing.
- [ ] **Notification actions** — "read aloud" / "snooze for today" buttons in
      the notification.
- [ ] **Tap-to-replay TTS** on Today.
- [ ] **Past 7 days history** on Today — pull from `InsightCache`, persist
      beyond the current single slot.
- [ ] **Clothes rule presets** ("Cyclist", "Commuter", "Dog walker") — pick a
      preset, customise from there.
- [ ] **Quiet hours** — don't fire if the device is in DND.
- [x] **Per-locale defaults** — Fahrenheit / miles when the system locale is
      en-US. Shipped via `TemperatureUnitSetting.AUTO` / `DistanceUnitSetting.AUTO`
      (the defaults in `UserPreferences`), which resolve from the device/region
      locale at read time.
- [ ] **Multiple schedule profiles** — weekday vs weekend.
- [x] **Gemini model picker** — Flash Lite (cheapest), Flash (default), Pro
      (highest quality, slowest, costliest). User picks from Settings; the
      Worker passes the chosen id into a per-call `DirectGeminiClient`.

## Analytics & telemetry

Product analytics ships in all builds. Default-on with a one-time
non-blocking Today banner pointing at Settings → Privacy to turn it off;
PRIVACY.md → "Analytics and crash reporting" has the contract. Backend
is Firebase Analytics + Crashlytics, gated on `app/google-services.json`
so CI builds no-op. The hard "do not transmit" list (calendar data,
location, insight prose, API keys, precise GPS, ad identifiers) lives in
PRIVACY.md and is mirrored inline in the Settings → Privacy card.

Live events: `api_call` (endpoint, outcome, status, latency;
offline-filtered), `notification_delivery` (slot, alarm delay, total
delay), `daily_refresh` (slot, outcome, latency), the non-voice
configuration split into one event per Settings page (`settings_schedule`
/ `settings_clothes` / `settings_format` / `settings_region` /
`settings_display` / `settings_calendar` — one combined event would
exceed Firebase's 25-param cap), and `clothes_rules_snapshot` (customisation
summary + per-category integer Celsius delta from default, clamped to
±5°C). Live user properties: language / region / TTS-engine / TTS-style
/ voice settings as default / override / effective triples — kept to a
small set since Firebase caps custom user properties at 25 per app.

Open work:

- [x] **Codify the do-not-transmit list in tests.** `TelemetryPrivacyContractTest`
      structurally locks the surface: event-name / param-key / user-property
      allowlists in `Telemetry.kt`, literal-only Crashlytics custom keys, no
      `setUserId`, `logEvent` / `setUserProperty` channelled through
      `Telemetry.kt`, and the three snapshot data classes constrained to
      primitive fields so a `Location` / `CalendarEvent` / insight prose can't
      be passed in at the type level. New entries trip the allowlists and
      force a conscious PR-time decision.
- [ ] **Insight-composition events — decide separately.** Which evening
      tie-in path fired (clothes-rule vs per-model rain bare warning vs
      none), which confidence tier emitted, whether tonight was
      suppressed. Useful but a new category of "what the engine
      decided" telemetry; revisit once the settings + refresh streams
      have answered the bigger questions.

## Testing & quality

- [x] **Robolectric tests** for the alarm + notification path. First
      coverage landed for `DailyAlarmScheduler` (exact-alarm trigger time,
      TODAY/TONIGHT slot independence, cancel), `ScheduleRefreshReceiver`
      (the boot / package-replaced / timezone / locale re-arm path,
      tonight-enabled vs disabled branches), and the two
      notifiers — `InsightNotifier` and `TonightInsightNotifier` —
      covering channel routing, title/body/big-text, the tap intent,
      POST_NOTIFICATIONS gating on API 33+, and the outfit-driven
      small-icon mapping.
- [ ] **Compose UI tests** for `SettingsScreen` (state transitions, dialog
      flow). No `app/src/androidTest/` exists today.
- [ ] **Maestro flows** — `.maestro/first_launch.yaml`,
      `.maestro/daily_insight_debug_fire.yaml`. Plan called for both; need
      Firebase Test Lab in CI to run them automatically.
- [ ] **JaCoCo coverage** — plan target ≥85% on `:core:domain` +
      `:core:data`. No coverage measurement wired up.
- [ ] **`docs/acceptance.md`** — manual checklist (TTS audio, real 7am fire,
      lock-screen visibility, OEM background-killer).

## Deferred to v2 (out of scope for v1)

- iOS port (needs a Mac + KMP-promotion of the core modules).
- Backend Gemini proxy (interface in place; swap before Play Store).
- Google Home / alarm-clock-app integration.
- Play Store submission. Sideload + FAD only for v1.
