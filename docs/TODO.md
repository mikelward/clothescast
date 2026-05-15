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
- [ ] **User-selectable models for the spread overlay.** The consulted set
      is hard-coded in `MultiModelConfidenceFetcher.DEFAULT_MODELS` to
      ECMWF + GFS + ICON (with `best_match` folded in as "Auto"). A user
      whose region is better served by MeteoFrance / GEM / JMA / BOM
      can't add them, and a model that returns no usable hourly data for
      a given cell (ECMWF over high latitudes or some coastal grid
      points) silently disappears from the chart legend — reads as a bug
      rather than an empty model. Plan: multi-select picker in Display
      settings under the existing "Show model spread" toggle, defaulting
      to today's trio; thread the chosen list through the fetcher's
      already-pluggable `models` constructor argument. `best_match` stays
      always-on (rides the primary call, no extra slot needed). Consensus
      blend in `ConsensusBlend.kt` already iterates whatever's in
      `PerModelHourly.byModel`, so no changes there. Same Open-Meteo
      endpoint either way — no privacy change.

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
delay), `daily_refresh` (slot, outcome, latency), `settings_snapshot`
(non-voice configuration), and `clothes_rules_snapshot` (customisation
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
- [ ] **`detekt` + `ktlintCheck` in CI.** Neither plugin applied today.
- [ ] **JaCoCo coverage** — plan target ≥85% on `:core:domain` +
      `:core:data`. No coverage measurement wired up.
- [ ] **`docs/acceptance.md`** — manual checklist (TTS audio, real 7am fire,
      lock-screen visibility, OEM background-killer).

## Deferred to v2 (out of scope for v1)

- iOS port (needs a Mac + KMP-promotion of the core modules).
- Backend Gemini proxy (interface in place; swap before Play Store).
- Google Home / alarm-clock-app integration.
- Play Store submission. Sideload + FAD only for v1.
