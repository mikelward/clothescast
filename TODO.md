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
      [docs/firebase-app-distribution.md](docs/firebase-app-distribution.md).
- [ ] **`.github/workflows/release.yml`** — tag-triggered, runs Maestro on
      Firebase Test Lab + cuts a GitHub Release with a release-signed APK.

## Paid tier (exploration)

Nothing built; the tier shape isn't settled. Design notes — how a Play
subscription would reach the Gemini proxy, why Smart Home can't be
enforced the same way, costs, and the open questions — are in
[ROADMAP.md](docs/ROADMAP.md).

- [ ] **Decide what the subscription sells.** Working recommendation is
      Gemini quota only — it's the one thing with a real marginal cost
      and the one thing genuinely enforceable. Blocks everything else.
- [ ] **Confirm Smart Home stays free.** Its `audio` and `video` topics
      already require a Gemini synthesis (`DeliveryGates.kt` — Gemini is
      the only producer of routable PCM), so they're metered by the
      proxy already; a separate entitlement would be redundant and its
      grandfathering has no durable implementation. See ROADMAP.md.
- [ ] **PRIVACY.md section for billing** before any of it ships: the
      purchase token would be new off-device data stored against the
      anonymous uid, plus a hash of that uid sent to Google as
      `obfuscatedAccountId`. Deliberately *not* stored: the Play order
      ID — nothing consumes it, so it's exposure for no benefit. See
      ROADMAP.md.

## Voice / TTS

- [x] Gemini TTS as opt-in voice engine (PR #27)
- [x] Diagnostic "Test Gemini voice" button in Settings (PR #28)
- [x] **Voice picker** for Gemini. Curated list in `TtsVoices.kt`; Despina is the current default (validated across en-GB / en-AU / en-US / de-*).
- [x] **Re-translate the Voice engine subhead + per-engine descriptions.** PR
      #874 rewrote `settings_tts_engine_description` and
      `settings_api_key_gemini_header`, added `settings_tts_device_header`,
      and folded the Speech setup sheet's lead-in onto the same string.
      Follow-up: translated the three strings across all 44 `values-*`
      overrides so the new on-device vs. online tradeoff (and the
      "ClothesCast and Gemini servers" disclosure) ships in every locale.
- [ ] **Append the "X settings" suffix to every localized
      `settings_root_*` value and to `speech_setup_title`.** Base
      English now reads "Schedule settings", "Clothes settings",
      "Speech settings", etc. on the root settings list, every
      sub-page's app-bar, and the speech-setup bottom sheet, but
      the 44 `values-*` overrides still translate the old one-word
      labels. Stale-but-translated is the deliberate choice — see
      the prior precedent — so localized devices keep the
      shorter form until a translation pass lands. The Forecasters
      label drops to singular ("Forecaster settings") in the
      English source for readability; localizations should follow
      whatever singular/plural reads naturally in their language.
- [ ] **Translate `settings_api_key_status_unset`** ("2 free each
      day, get a key for more.") across all 44 `values-*` overrides.
      Shown on Voice settings and the Speech setup sheet whenever
      no BYOK key is set. The old
      translations were stripped when this string's meaning changed
      from "no key configured, go get one" to surfacing the free
      daily allowance, so non-English devices currently fall back to
      the English copy. The "2" understates the actual
      `DAILY_LIMIT` in `functions/src/index.ts` (currently 5) on
      purpose — users get more than promised, not less. Re-translate
      if the source number changes. Translation also needs a
      per-locale `AISTUDIO_LINK_LABEL` (currently the hard-coded
      English "get a key" in `SettingsCommon.kt`) so the linkified
      substring matches the translated string.
- [ ] **Reorder the Gemini section: Test voice above the API key field.**
      Today (and in the snapshot for the no-key / shared-proxy state) the
      key entry — `Paste your Gemini API key` + `Save Gemini key` —
      occupies the prime real estate just under the engine subhead, even
      though the user can already hear Gemini for free by hitting Test
      voice at the bottom of the card. Moving the voice / style pickers
      and the Test voice button up, with the key field collapsed below
      as an "unlimited use" upgrade, would let "try it now" actually
      lead the section. Mirror in `SpeechSetupSheet` so the first-opt-in
      flow matches.

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

- [x] **Hourly forecast UI** on Today. Vico chart of temperature + feels-like
      across today's hours. Multi-day extension still possible.
- [ ] **Forecast accuracy ideas** — end-of-day accuracy survey, user-flagged
      incorrect forecasts, background multi-provider comparison. Sketched in
      [docs/MODELS.md](docs/MODELS.md) (ideas 2-4). Idea 1 (confidence badge)
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
[docs/smart-home.md](docs/smart-home.md); the data-handling note is in
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
- [x] **HA MQTT discovery for the sensors/images.** After a successful
      publish, ClothesCast now emits retained discovery configs under
      `homeassistant/.../config` for today / tonight / now text sensors,
      the `now/timestamp` timestamp sensor, and today / tonight / now
      image entities. A fresh HA install can pick them up without YAML;
      audio/video remain retained publish topics consumed by automations
      or media actions rather than first-class HA MQTT entities.

Open:

- [x] **Publish a calendar-events MQTT topic.** `has_events` (`true`/`false`)
      now publishes on every window — `<prefix>/day/has_events`,
      `<prefix>/night/has_events`, and the `<prefix>/now/has_events` mirror —
      gated into the bundle like the other modalities and exposed as Home
      Assistant `binary_sensor` discovery entities. So an automation can tell a
      deliberately-silent (event-free, `tonightNotifyOnlyOnEvents`) evening from
      an outage without parsing prose. `event_count` wasn't included — a boolean
      covered the use case; revisit if a count is ever needed.
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
- [x] **Tap-to-replay TTS** on Today — the play button replays the cached
      insight through TTS / MQTT / Cast (`TodayScreen` play action).
- [ ] **Past 7 days history** on Today — pull from `InsightCache`, persist
      beyond the current single slot.
- [ ] **Clothes rule presets** ("Cyclist", "Commuter", "Dog walker") — pick a
      preset, customise from there.
- [ ] **Quiet hours** — don't fire if the device is in DND.
- [x] **Per-locale defaults** — Fahrenheit / mph when the system locale is
      en-US. Shipped via `TemperatureUnitSetting.AUTO` / `WindSpeedUnitSetting.AUTO`
      (the defaults in `UserPreferences`), which resolve from the device/region
      locale at read time.
- [x] **Wind speed unit picker** — km/h / mph / knots / m/s, independent of
      locale (knots & m/s are opt-in, never an auto default). Replaced the old
      distance-unit setting, which only ever drove the wind unit.
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
delay), `daily_refresh` (slot, outcome, latency),
`scheduled_delivery_timeout` (slot — fires when the FGS shepherd's
pre-RUNNING 5-min cap kicks in because the worker stayed queued), the non-voice
configuration split into one event per Settings page (`settings_schedule`
/ `settings_clothes` / `settings_format` / `settings_region` /
`settings_display` / `settings_calendar` — one combined event would
exceed Firebase's 25-param cap), and `clothes_rules_snapshot` (customisation
summary + per-category integer Celsius delta from default, clamped to
±5°C). Live user properties: language / region / TTS-engine / TTS-style
/ voice settings as default / override / effective triples — kept to a
small set since Firebase caps custom user properties at 25 per app.

Open work:

- [ ] **Route caught non-crash errors to Crashlytics.** Crashes already
      flow via `FirebaseCrashlytics`, but the long tail of caught
      exceptions logged through `DiagLog.e` / `.w` only lands in the
      on-device diag file. Routing them through
      `FirebaseCrashlytics.recordException` would surface real-world
      failure modes (network outages, parsing errors, TTS synth failures)
      without waiting for a crashing fork. The privacy work is the gating
      cost: log strings are free-form and can contain prose / locations /
      keys, so we'd need an audit + filtering layer (or a structured
      replacement for the free-form messages) before piping them through.
      PRIVACY.md → "Analytics and crash reporting" lists the hard
      do-not-transmit set.
- [x] **Codify the do-not-transmit list in tests.** `TelemetryPrivacyContractTest`
      structurally locks the surface: event-name / param-key / user-property
      allowlists in `Telemetry.kt`, literal-only Crashlytics custom keys, no
      `setUserId`, `logEvent` / `setUserProperty` channelled through
      `Telemetry.kt`, and the three snapshot data classes constrained to
      primitive fields so a `Location` / `CalendarEvent` / insight prose can't
      be passed in at the type level. New entries trip the allowlists and
      force a conscious PR-time decision.
- [ ] **Insight-composition events — decide separately.** Which evening
      extras path fired (clothes-rule vs per-model rain bare warning vs
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
- [ ] **Stop searching for the sticky PR comments altogether.** The four
      comment steps in `ci.yml` now paginate the comment list to find their
      marker, which is correct but O(comments) API calls per run on a long
      PR, and still a search where an exact lookup would do. Stashing each
      comment's id somewhere stable (a workflow-run output, a branch note,
      the check-run summary) would make the upsert a direct PATCH. Not
      urgent — the paginated form is correct at any comment count, this is
      only about cost and simplicity. `mikelward/typelauncher` and
      `mikelward/simmo` still carry the underlying pagination gap as an open
      item, since paginating their `curl`-based lookups is real work.

## Dependency updates

- [x] **Adopt `mikelward/gradle-update`** — the weekly Gradle catalog updater.
      Wired via the caller workflow in `.github/workflows/dependency-update.yml`,
      with `ci-workflow: ci.yml` since this repo's CI file is not the shared
      default name.

## Deferred to v2 (out of scope for v1)

- iOS port (needs a Mac + KMP-promotion of the core modules).
- [x] **Bind the TTS proxy quota to a server-verified identity**
  rather than the client-chosen `X-Install-Id` header. Done: the
  client now sends an anonymous Firebase Authentication ID token
  (`Authorization: Bearer …`) and the function verifies it
  (`getAuth().verifyIdToken`) and keys the daily quota on the
  resulting `uid` (`quota/<uid>`). The earlier idea of verifying a
  Firebase Installations *auth token* was dropped — there's no
  documented way for a custom backend to verify one (the Installations
  REST API only creates/deletes installations and generates tokens; no
  verify/introspect method, and the Admin SDK has no installations-token
  verifier). Requires Anonymous sign-in enabled and App Check enforced
  on Authentication in the Firebase project. Surfaced by Codex review
  on #893.
- Google Home / alarm-clock-app integration.
- Play Store submission. Sideload + FAD only for v1.

## Review and merge gates

- [ ] Add the shared consumer check (`codex-review-check.yml` from
      mikelward/codex-review) if it applies to this repository's
      codex-review setup — see its `docs/CONSUMER.md`. `codex-review.yml`
      already publishes the `codex` status here, and it must remain the
      only workflow holding `statuses: write`; the consumer check holds
      only `contents: read` and verifies the workflow pin, it publishes
      nothing. Cost: one short `ubuntu-latest` job per push, seconds of
      the Actions allowance — effectively zero. Reliability: if GitHub or
      the shared repo is unreachable the check fails closed on that PR
      and a re-run clears it; nothing else is blocked.
- [ ] Verify the settings half of the fleet's bar: a ruleset on the
      default branch requiring the CI gate, the `codex` status,
      conversation resolution and up-to-date branches, the auto-merge
      setting enabled, and "Allow GitHub Actions to create and approve
      pull requests" on — without that last one the weekly batch pushes
      its branch and then fails to open the PR (simmo's first run did
      exactly this).
- [ ] Put the `functions/` npm tree (TypeScript Cloud Functions — the
      tree holding all seven current Dependabot alerts, one critical) on
      the weekly npm batch. The checker's cwd-relative manifest fix
      (npm-update#7) is merged; `.github/workflows/npm-update.yml` with
      the `working-directory: functions` wiring is in review as #1131;
      this item closes when that lands. Cost: two short `ubuntu-latest`
      jobs once a week, minutes well inside the Actions allowance —
      effectively zero. Reliability: a failed run (runner, npm registry,
      or checker outage) skips that week's batch and the next Saturday
      retries; no other work is blocked.
- [ ] `npm-update.yml`'s check-outcome report (the `passed`/`results` job
      outputs the update job writes) is trusted evidence, not proof: a
      lifecycle script in a newly selected dependency runs before those
      outputs are written and could in principle locate the runner's real
      `$GITHUB_OUTPUT` path (past the per-check subshell's `/dev/null`
      override, which closes the casual channel but not a determined
      same-machine search) and append a forged line. The interpolation
      path this could have exploited into actual code execution is
      closed (every such value crosses through `env:`, never spliced
      into script text — see #1131's review). What's left is narrower:
      a forged `passed=true` could make the PR's title and verdict text
      claim a clean batch when the underlying checks failed. Concretely
      bounded — this workflow never arms auto-merge, so every batch
      lands as an open PR a human decides on regardless of what the text
      says — but the report itself isn't authenticated. Closing it fully
      needs either running the check suite on a machine the update job's
      own dependency code never touches (defeats the point of keeping
      the write-token job clean) or a stronger channel than a job output
      (signing, or a second independent verification job) — bigger scope
      than this PR, flagged for whoever wants to take it further.
