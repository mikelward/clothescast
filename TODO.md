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

- [x] **Firebase App Distribution — set up, then removed.** It shipped the
      debug APK to testers on every push to `main`. Retired once the Play
      internal track proved sufficient: same audience, one channel instead
      of two, and no second signing identity to keep alive. Its setup guide
      went with it, and so did the `app-debug-apk` CI artifact that
      outlived it — build a debug APK locally instead. CI no longer runs
      `assembleDebug` at all.
- [x] **Play Store internal track.** Push to `main` builds and signs the
      release AAB and uploads it. Now the only automated channel — see
      [docs/play-store-internal-testing.md](docs/play-store-internal-testing.md).
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
- [x] **GFS dropped from every default set** (2026-08-31). Verified against
      ERA5 over 85 days at five locations: GFS came last of the five
      candidates on both MAE and ETS for daily rainfall and detected barely
      half of wet days (POD 0.49, FAR 0.04 — systematically dry, not noisy).
      A mean-based consensus cannot discount a member that is wrong in one
      consistent direction, so it drags the blended chance of rain toward dry
      on every wet day. North America took ICON in its place; the global
      fallback dropped to four models. GFS remains selectable in the picker.
      Weaker members are still worth keeping when their errors are
      decorrelated — this one's were not.
- [ ] **De-duplicate `best_match` from the consensus vote.** Open-Meteo's
      `best_match` overlay resolves to a listed model at many locations
      (verified byte-identical to GFS at a North American point, across both
      the precipitation and precipitation-probability series), and
      `blendConsensusHourly` counts it as a regular equal-weight member — so
      that model votes twice. In a four-model set that is half the ballot.
      `ConsensusBlend`'s KDoc accepts the double-weighting on the reasoning
      that best_match adds location-tuned signal; where it is an exact
      duplicate it adds none. Drop it from the candidate set per hour when
      its values match another consulted model, or de-weight it generally.
      Deferred by the maintainer 2026-08-31; the GFS swap above was taken
      first and reduces, but does not remove, the exposure.
- [ ] **Confidence chip: spread is a range, so it mis-reads a systematically
      offset member as uncertainty — and punishes extra models.** Deferred by
      the maintainer 2026-08-31; recorded here rather than built.

      `ConfidenceInfo.computeFrom` scores agreement as
      `tempHighs.max() - tempHighs.min()` against 1.5 °C (HIGH) / 3.0 °C
      (MEDIUM), and 15 / 30 pp for precipitation. Two problems fall out of the
      range:

      1. **A lone offset member sets the tier by itself.** Google resolves
         urban heat island and reads consistently warmer in cities than a
         0.25° global model that smears the city across ~25 km, so the chip
         reports disagreement every day over exactly the model that is right.
         Same failure shape as the GFS rainfall case, opposite sign.
      2. **A range is monotonically non-decreasing in the number of models.**
         Adding a forecaster can only ever lower confidence. So the chip is
         not comparable between a user with 2 models and one with 5, and
         dropping GFS from the defaults will have raised apparent confidence
         on its own, without the forecast improving.

      Candidate statistics, measured on scenario vectors (°C):

      | case | range | MAD | IQR | trim-1 |
      |---|---|---|---|---|
      | all agree | 0.30 | 0.10 | 0.15 | 0.20 |
      | one offset +2.4 (the Google case) | 2.70 | 0.10 | 0.90 | 0.50 |
      | 3 agree, 1 wild +8 | 8.00 | 0.10 | 2.07 | 0.20 |
      | genuine 4-way spread | 6.00 | 2.00 | 3.00 | 4.00 |
      | bimodal 2-v-2 split | 5.40 | 2.50 | 4.88 | 5.00 |

      - **Per-model, per-variable weights** (Google up on temp, GFS down on
        rain). Rejected as a first move: a hand-maintained matrix that goes
        stale silently as models change, evidence for only a couple of its
        cells, and it leaves the n-dependence untouched. It also misdiagnoses
        Google, whose advantage is resolution, not general skill.
      - **Olympic trim** (drop min and max). Rejected: with 4-5 members it
        discards a third to a half of the data, and it culls whoever is
        extreme *today* rather than whoever is persistently offset — so on a
        genuinely uncertain day it would hide the spread the chip exists to
        report. Quietly overconfident is the wrong failure direction.
      - **MAD** (median absolute deviation). Fixes the Google case but goes
        too far: rows 2 and 3 above both read 0.10, identical to full
        agreement, because with three members clustered the outlier's
        distance falls outside the middle and vanishes. A model 8 °C off
        would read as perfect agreement.
      - **IQR.** The preferred statistic if this is picked up. Damps the
        Google case ~3x while still registering the wild one, because with
        four models the quartiles interpolate between adjacent values, so an
        outlier pulls a quartile without owning it.
      - **Rolling per-model offset** (learn each model's persistent bias
        against the ensemble median, subtract before measuring spread). The
        principled fix, and the only option that separates "Google is always
        warmer in cities" from "this model broke this morning". Needs stored
        history per model / variable / location plus cold-start behavior.

      The limitation worth remembering: **no purely statistical measure can
      tell a trustworthy outlier from an untrustworthy one.** Rows 2 and 3
      have the same shape and differ only in which model it is and whether it
      is always like that. Any statistic that forgives one forgives the
      other; only the rolling-offset option uses model identity.

      Whichever is chosen, most of the work is **recalibration, not the
      statistic**: the 1.5 / 3.0 / 15 / 30 thresholds are calibrated for a
      range, and every robust alternative is numerically smaller, so swapping
      one in without re-deriving them would push nearly everything to HIGH —
      a quieter, more confident app, which is the worst direction to fail in.
      Derive new thresholds from real per-model series before shipping.

- [ ] **BOM (`bom_access_global`) is still suspended — re-check periodically.**
      Status checked 2026-08-31: the model id is still accepted by Open-Meteo
      but every field returns null (0/384 hourly, 0/16 daily at Sydney).
      Open-Meteo's BOM docs page still carries the "open-data delivery has
      been temporarily suspended" notice with no date, and their tracking
      issue (open-meteo/open-meteo#1416) has been open since 2025-07-19 with
      no timeline. The official routes back are BOM Registered User Services
      (subscription (S)FTP GRIB2/NetCDF — a bulk gridded feed needing a
      server to ingest, which this client-only app does not have) or BOM's
      public precis XML products, which are official town forecasts rather
      than ACCESS-G output and so cannot join the multi-model spread as a
      peer. Nothing actionable until Open-Meteo resumes it.

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

- [x] **Reporting is opt-in — nothing goes off device until the user turns it
      on** (maintainer, 2026-08-28; the standard for all four sibling apps).
      Both SDKs are started with collection disabled via manifest `meta-data`,
      which is what makes it true on the first launch: `FirebaseInitProvider`
      runs before `Application.onCreate`, so no code of ours can get there
      first. A stored `true` from a consenting user overrides them at startup.
      An install that predates the toggle carries no key and is read as *not*
      consented, since it never was asked.

      **This retired two findings rather than fixing them** (Codex, PR #1161).
      Both were the same shape — a window in which a report is captured or
      queued before the opt-out reaches the SDK — and both are answered by
      there being nothing to capture until consent exists:

      - `deleteUnsentReports()` cannot retract a report the SDK already
        scheduled. Verified from the shipped bytecode rather than the docs:
        `CrashlyticsController.deleteUnsentReports()` is
        `reportActionProvided.trySetResult(false)` plus a wait, and
        `trySetResult` no-ops once that source is completed — which
        *automatic* collection does itself at startup. With collection
        starting disabled the source is no longer pre-resolved, so the call
        does what its name says.
      - `setTelemetryEnabled(false)` commits its DataStore edit and returns
        while the SDK-disable calls happen in the downstream collector, so a
        crash in between was captured with collection still on and the
        persisted flag never flipped. There is now no such state to start
        from. (Moving the SDK call into `SettingsRepository` was considered
        and rejected: it puts Firebase into a data layer that must keep
        working on builds with no `google-services.json`, and it would not
        have touched the first case anyway.)

      The discard debt from that PR stays and is still needed: an opt-out
      *after* a consented period still has reports to purge. What changed is
      that the promise no longer has a "except what was already queued"
      clause covering a period the user never agreed to.

- [x] **Migrate installs that never chose, and discard what was captured before
      consent** (maintainer, 2026-08-28: "if there's no preference, ask, don't
      send (and maybe don't store) until they opt in"). Two findings, one shape
      — something collected before the user agreed being released rather than
      dropped (Codex, PR #1161):

      - The manifest alone only covers fresh installs. `DataCollectionArbiter
        .isAutomaticDataCollectionEnabled()` returns Crashlytics' own persisted
        override whenever it is set and reaches the manifest default only when
        it is not; every install from the default-on era has `true` there.
        `migrateToOptInTelemetry()` runs once, before the collector, and owes a
        discard where no choice was ever stored — so that launch stops both
        SDKs, overwriting the stored `true`, and purges.
      - The manifest disables *sending*, not capture, so a crash from before
        the user was asked sits on disk as unsent. `setTelemetryEnabled` now
        owes a discard on **any** crossing of the consent line, so the first
        opt-in discharges before enabling. Re-affirming an existing opt-in owes
        nothing, which is what stops it deleting consented reports.

      What is still not closed: the window between `FirebaseInitProvider` and
      `Application.onCreate` on the migrating launch. Narrowed and purged after
      the fact, not prevented. Preventing it means getting ahead of the
      provider — a startup-ordering change, not taken here. It is also not
      strictly *one* launch: what durably turns the SDKs off is the collector's
      `stop()`, so a process that dies before reaching it leaves the persisted
      override on and the next launch opens the same window before closing it
      (Codex, PR #1161). Bounded either way — the debt stays owed, so nothing
      pre-consent is released — and `PRIVACY.md` says so rather than promising
      a single launch.

- [ ] **The residual enable-window race, and the deletion API it comes from.**
      Declined on the PR rather than fixed, and it wants a maintainer decision
      (Codex, PR #1161, fourth finding in the same area).

      The claim: between `safeToEnable()` returning and the two collection
      setters running, a rapid off-then-on could record a newer discard debt,
      so the stale pass enables collection with that newer debt outstanding.
      `clearTelemetryDiscardOwed` correctly declines to retire a token it did
      not consume, and the next emission discharges — but for the length of one
      DataStore edit, collection is on with a report from the opted-out gap
      still on disk.

      Why it was not fixed: there is no suspension point between the check and
      the setters, so the window is a coroutine resumption, and reaching it
      needs the user to toggle the switch twice inside a discharge. The
      proposed fix — serializing preference writes with the enable sequence —
      means a mutex spanning `SettingsRepository` and the telemetry collector,
      and it collides with the ordering an *earlier* finding on this same PR
      required: the clear must come after the enable, because
      `deleteUnsentReports()` is fire-and-forget and the debt is the only thing
      that makes a later launch retry the delete.

      That collision is the real content. Every ordering leaves some window
      while the deletion cannot be awaited; the orderings only choose which
      window. The class closes properly with the manual Crashlytics reporting
      flow — `setCrashlyticsCollectionEnabled(false)` permanently, and
      `checkForUnsentReports()`'s `Task<Boolean>` awaited before either sending
      or deleting — which is a real behavior change: a consenting user gets no
      reporting until the next launch. That is the decision to make, and it is
      the same one recorded on simmo.

- [ ] **"Maybe don't store" is only half-done.** The maintainer's ask included
      not *storing* pre-consent crashes, not merely not sending them. What
      landed discards them at first opt-in; the library still writes them to
      disk in the meantime, because collection-disabled does not uninstall its
      uncaught-exception handler. Genuinely never storing them means not
      initializing Crashlytics until consent exists — a startup change worth
      costing out separately.

- [ ] **Decide whether a re-enabled analytics identity should be reconfigured in
      the same process.** Removed rather than fixed a sixth time (autopilot,
      2026-08-29). `resetAnalyticsData()` wipes the identity's user properties,
      and the snapshot collectors will not resend them in that process because
      their values are unchanged and `distinctUntilChanged` drops them. So
      events between an opt-in and the next launch go out unsegmented. It
      self-heals: the next launch's collectors emit their initial values to the
      new identity.

      Three attempts to buy back those minutes produced five findings (Codex,
      PR #1161): replaying unconditionally duplicated both configuration events
      on every opted-in launch; keying it on the discard debt lost the replay
      when a process death landed between clearing that debt and enabling; and
      a dedicated durable flag reintroduced duplication across the
      interrupted-enable window *and* cleared itself after a replay that had
      thrown. Exactly-once here means coordinating a durable flag with three
      independent startup collectors across arbitrary process deaths, which is
      a distributed-systems problem accepted for dashboard accuracy.

      Reversible, and cheap to revisit: nothing else depends on it, and the
      cost of leaving it out is bounded to one app session's segmentation. If
      it returns it should be designed — most likely by having the collectors
      themselves re-emit on a collection-enabled transition, rather than
      bolting a second emission path onto the consent path.

- [ ] **Fan the two new banner strings out to the 47 locales.**
      `today_telemetry_invite_title` / `_body` landed English-only with
      `tools:ignore="MissingTranslation"`, per the English-first rule. They are
      new keys rather than reworded ones on purpose: the old
      `today_telemetry_notice_*` strings asserted reporting was already on, and
      a locale still carrying that would tell its reader something false about
      their privacy — an untranslated fallback is merely untranslated. The old
      keys are deleted from every locale.

Product analytics ships in all builds but collects nothing until the user
turns it on — both SDKs start disabled from the manifest, and a one-time
non-blocking Today banner invites them to Settings → Privacy;
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

## Review and merge gates

- [ ] **Reconcile the docs-lane classifier with the release-notes-skip
      classifier for `PRIVACY.md`, and reconsider forcing it onto the code
      lane at all.** `PRIVACY.md` is carved out of the `docs/` housekeeping
      rule in `classify`'s lane check (see `.github/lanes.conf`), which
      means a PRIVACY.md-only change always runs the full build/test/lint
      pipeline, even a pure wording fix that changes nothing about the app —
      but PRIVACY.md isn't actually code, so that's a heavier CI cost than
      the change needs; being release-worthy and needing heavy CI are two
      separate questions the lane rule currently conflates. Separately, the
      deploy job's "Prepare release notes" step has two *independent* skip
      conditions — a non-user-facing subject prefix (`ci:`/`test:`/
      `internal:`/`docs:`) skips a commit regardless of what it touched, and
      a housekeeping-path check (which PRIVACY.md is carved out of as
      "non-docs") skips a commit whose diff is all `docs/`/dotfiles/`.md`.
      A commit like `docs: clarify data retention wording` touching only
      `PRIVACY.md` would still be dropped by the prefix check, even though
      the lane rule's whole point is that PRIVACY.md is never "just docs" —
      and `docs:` is precisely the prefix this repo's own convention says
      to use for a documentation-only change, PRIVACY.md exception aside.
      The two classifiers can silently diverge on this one case.
- [ ] **Require a `PRIVACY.md` update in the same commit as the practice
      change it documents**, and stop treating every PRIVACY.md touch as
      automatically release-worthy — a pure wording/typo fix with no actual
      change in practice shouldn't force a release the way a genuine new
      disclosure should. Needs a real distinction between "the policy text
      changed" and "what the policy describes changed," which the current
      mechanism can't make on its own.
- [ ] **Two lint findings are suppressed with a `TODO`/`@Suppress` rather
      than fixed**, because both sit on locale/Cast-routing surfaces that
      took several iterations to get right and shouldn't be reworked blind
      in a dependency-bump-adjacent PR:
      - `RestrictedApi` on `CastDeviceClass.kt`'s `classifyRoute` —
        `MediaRouter.RouteInfo.isGroup` is `@RestrictTo(LIBRARY)` inside
        `androidx.mediarouter`, so lint flags calling it from app code even
        though it's a public getter with no in-library alternative for this
        classification. Worth revisiting if a future `mediarouter` release
        either lifts the restriction or exposes an intended replacement.
      - `NonObservableLocale` on `TodayPreviews.kt`'s
        `FollowingWeekChartDeckPreview` — reads `Locale.getDefault()`
        directly instead of through Compose's observable
        `LocalConfiguration`/`LocalLocale`. Low-risk as-is (a Roborazzi/IDE
        preview rendered once, no live locale switching to miss), but the
        proper fix is switching to the observable API, which should happen
        alongside a real audit of how the app's other locale-sensitive
        formatting handles runtime locale changes — not as a one-line
        swap here.
- [x] **`zizmor` is in the ruleset's required set.** The flip happened
      ahead of the preconditions recorded here, and precondition (1) then
      bit exactly as written: the snapshot job's `ci: regenerate UI
      snapshots` push is made with `GITHUB_TOKEN`, whose events start no
      workflows, and ci.yml's helper dispatched only ci.yml — so `zizmor`
      never reported on a regenerated head. PR #1166 sat unmergeable on it
      ("2 of 3 required status checks are expected") until a PR body edit
      fired `pull_request: edited`, which zizmor listens for.
      - Fixed differently from the plan above, and better: the snapshot job
        pushes with `push-token` (a PAT), so the head gets an ordinary
        authenticated push and the whole `pull_request` round re-runs over
        every workflow. Giving zizmor.yml a `workflow_dispatch` trigger and
        dispatching it alongside ci.yml would have worked for zizmor alone
        and left the next required check to rediscover this; it would also
        have broken `mikelward/ci-commit-artifact`'s policy test, which
        pins zizmor.yml's `on:` block byte-for-byte precisely to stop that.
      - Precondition (2) — a future token-authored automation PR — is
        unaffected by that fix and still stands. Any automation opening a
        PR with `GITHUB_TOKEN` produces a head no workflow reports on, so
        it needs the same PAT treatment before its PRs can satisfy the
        ruleset.
      - The weekly npm batch was that automation, and now has it:
        `npm-update.yml` supplies `NPM_UPDATE_PAT` (mikelward/npm-update#33
        added the optional `token` secret), matching what `gradle-update.yml`
        already did. Its PRs now get the ordinary `pull_request` round like
        any other. The alternative the hub also offers — naming each
        required check in a `dispatch-workflows` input — was rejected here
        for the reason above: it needs a `workflow_dispatch:` trigger on
        `zizmor.yml`, which `mikelward/ci-commit-artifact`'s policy test
        pins byte-for-byte, and it would leave the next required check to
        rediscover the same gap.
- [ ] **Finish the gate → lanes check rename** once `lanes` has reported on
      a `pull_request` run: flip the ruleset to require `lanes` instead of
      `gate`, then delete the now-redundant `gate` job in a follow-up PR.

## Testing & quality

- [ ] **Move this app onto the shared debug log** (`mikelward/androidlog`).
      The four apps had each grown a copy of the same mechanism and the copies
      were drifting — `LogValue.kt` byte-identical between two of them, the two
      `DebugFileSink.kt` copies 525 lines apart, and each copy carrying a
      review finding the others never heard about. The library is the fix, and
      it is now consumed as a **published coordinate** — androidlog serves its
      own Maven repository from its `maven` branch over
      raw.githubusercontent.com, and `gradle/libs.versions.toml` pins the
      version like any other dependency. `settings.gradle.kts` declares the
      repository (scoped to that one group) and keeps a `-PandroidlogLocal`
      opt-in for the composite build when both repositories are being changed
      at once.
      - **Done: the privacy floor.** `safe`, `sensitive`,
        `logArgumentMayLeaveDevice` and `formatLogMessage` come from the
        library now, and this repo's `LogValue.kt` is gone. `LogValueTest`
        stays as this app's own conformance test — consumers track `@main`, so
        a floor that shifted upstream reaches this APK with nothing in
        between, and the cases this app's call sites depend on are asserted
        here rather than left to that repo's suite.
      - **Done: off the composite build, onto the coordinate.** That is what
        ended the AGP lockstep — a composite puts androidlog's toolchain
        alongside this one in a single Gradle invocation, and AGP refuses to
        compare two versions at all, so a patch bump there broke every build
        here at once (2026-08-30). A resolved AAR carries no `AgpVersionAttr`,
        so the versions are now independent.
        The checkout is gone from CI's three jobs, from the session-start
        hook, and from the `gradle-update` caller's `checks` — every workflow
        that ran Gradle needed it, which was itself the tell. That caller now
        declares the repository (`extra-repositories`) and waives the
        release-age cooldown for the coordinate (`no-cooldown-for`), since
        raw.githubusercontent.com serves no `Last-Modified` and the batch
        would otherwise defer it forever rather than merely delay it.
        snoozemo, typelauncher and simmo each need the same migration when
        their turn comes.
      - **Done: `DiagLog` itself.** Its ring buffer, rotation, crash file and
        acknowledgement state are now `DebugLog` + `DebugFileSink`, with
        `DiagLog` a thin facade so all 278 call sites stayed untouched. The
        legacy `diag.log` / `last-crash.txt` / `.ack` files are deleted on
        first run after the migration rather than read: the reduced rendering
        is not retroactive, so removing them is the only way lines written
        under the old full rendering stop being readable.
      - **The licenses screen now lists androidlog, with a blank license.**
        A composite build contributed *project* dependencies, which
        AboutLibraries does not treat as bundled libraries; a resolved
        coordinate is one, so `app/src/main/res/raw/aboutlibraries.json` gained
        `logging-android` and `logging-core` — and both carry
        `"licenses": []`, because androidlog's publication declares no license
        metadata. Harmless (it is our own code in our own app) but it reads
        oddly beside 37 attributed entries. The fix belongs in androidlog: add
        a `<licenses>` block to its `maven-publish` POM, after which this file
        regenerates with it. The same will happen in each of the other three
        apps as they migrate.
      - **Next: the other three apps**, in order — snoozemo, typelauncher,
        simmo last. Each takes the coordinate the same way clothescast did,
        and each needs a sweep of *every* workflow that runs Gradle, not just
        CI; clothescast's weekly `gradle-update` caller was the gap here and
        would have stopped its dependency batch silently.
      - **`LogSummary` went with the swap**, and was already dead code — no
        production call site. The library renders each entry once, at
        ingestion, so a value carrying separate on-device and off-device
        renderings has nowhere to put the second one; a call site that needs
        a reduced form passes `safe(reducedForm)` instead.
      - **The floor stays per-repo regardless**: uniformity must not loosen
        any repo's privacy rules, and app-specific scrubbing stays out of the
        shared core.


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
- Play Store submission. Sideload only for v1 (this predates both the
  internal track and Firebase App Distribution).

## Review and merge gates

- [x] Add the shared consumer check (`codex-review-check.yml` from
      mikelward/codex-review) if it applies to this repository's
      codex-review setup — see its `docs/CONSUMER.md`. `codex-review.yml`
      already publishes the `codex` status here, and it must remain the
      only workflow holding `statuses: write`; the consumer check holds
      only `contents: read` and verifies the workflow pin, it publishes
      nothing. Cost: one short `ubuntu-latest` job per push, seconds of
      the Actions allowance — effectively zero. Reliability: if GitHub or
      the shared repo is unreachable the check fails closed on that PR
      and a re-run clears it; nothing else is blocked.
- [ ] **Gate the release AAB on whether anything will be published, not on
      whether the diff is docs-only.** A markdown-only push to `main` still
      runs `bundleRelease` (~6 min, R8) and then publishes nothing. The
      obvious `docs_only` gate is a no-op: `classify` deliberately reports
      every push as code, because `mikelward/lanes` classifies via a PR
      number and has no push-range mode, so `docs_only` is never `true`
      where `release-build` runs (Codex on #1171, verified against that
      action's `action.yml`). The verdict that actually decides is
      `RELEASE_NOTES_SKIP`, which `deploy`'s "Prepare release notes for
      Play" step already computes and which also covers the `ci:` /
      `docs:` / `internal:`-prefixed pushes a path test would miss —
      so hoisting that step into its own job and having both
      `release-build` and `deploy` consume it removes a duplicate rather
      than adding one. A cheaper path-based gate is now viable too, since
      #1171 removed the `PRIVACY.md` carve-out that made a second path
      test a divergence hazard. Which of the three (hoist, path gate, or
      leave it) is the maintainer's call — it is a release-pipeline
      change, not a one-line condition.
- [x] **Fix the `main`-queue hazard in `ci.yml`'s `concurrency:` block.**
      `cancel-in-progress: false` governs the *running* run, not the queue,
      and GitHub holds at most one pending run per group — so three pushes to
      `main` in quick succession evicted the second while it waited, and that
      commit reached `deploy` never. Fixed as two changes rather than one:
      `deploy` gets its own `deploy-main-release` group (queueing, never
      canceling — this repo had none, so the shared workflow group was the
      only thing serializing Play uploads), which frees the workflow-level
      group to key each `main` push by commit. Ported from
      mikelward/snoozemo#136, where `deploy` already had its group and the
      fix was therefore a single change.
- [x] **Decided: no ordered release queue; port the superseded-run guard**
      (maintainer, 2026-08-30). A concurrency group holds exactly one pending
      slot wherever it sits, so the fix above did not remove the eviction — it
      moved it from the workflow to `deploy` and changed its consequence
      (Codex, #1172). An evicted workflow run lost its tests and its release;
      an evicted deploy loses only the intermediate publish, and because
      "Prepare release notes" bases its range on the last run that actually
      *published*, those commits ship in the next release with their subjects
      intact. The maintainer accepted that — no repo in this fleet has an
      ordered queue — so the real queue (an external lock, or chaining via
      `workflow_run`) is not being built. snoozemo's superseded-run guard is
      ported instead, in #1172: `deploy` skips when a later push has already
      published, so an out-of-order deploy retires quietly instead of failing
      Play's stale-`versionCode` rejection on a billed run. It removes the
      failure, not the eviction.
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

## Decisions needing review

- **Per-call logcat tags are gone; the tag now rides in the message**
  (autopilot, 2026-08-30). Every `DiagLog` call still takes its `TAG` and the
  rendered line is byte-identical in shape — the old logger wrote
  `timestamp LEVEL TAG: msg`, and folding the tag into the format literal
  produces the same thing from the library's `timestamp LEVEL message`. What is
  lost is the **logcat filter key**: every line now carries one fixed logcat tag
  (`ClothesCastDebug`), so `adb logcat -s MqttPublisher` no longer selects. The
  tag is still *in* the line, so the on-device log and any shared report are
  unchanged, and `adb logcat -s ClothesCastDebug | grep MqttPublisher` gets it
  back.
  **Alternative:** add per-entry tag support to `mikelward/androidlog`, the way
  the five levels were added — the level is already handed to each sink, and a
  tag would follow the same path.
  **Why this way:** 278 call sites needed no library API to keep working, and
  the loss is a developer-facing filter rather than information. That is a
  smaller loss than the level flattening was, where the information itself
  would have gone.
  **Reversible:** entirely, and additively — adding the API later changes no
  call site here, only what the facade forwards.

- **Two `v`/`i` call sites that carried a throwable are now `w`**
  (autopilot, 2026-08-30). `LocationResolver` ("removeUpdates threw …;
  ignoring.") and `CalendarContractEventReader` ("Provider rejected
  `eventType` column…"). The shared library gives a throwable form to
  `warning` and `error` only, and both sites are "something threw and we
  degraded", which is what a warning *is*. The facade therefore offers no
  `v`/`i` overload taking a throwable, so the compiler finds any future one
  rather than a facade rule silently promoting it.
  **Cost:** those two lines move from `V`/`I` to `W` in logcat severity.
  **Alternative:** add `verbose(t, …)` / `info(t, …)` to the library — API
  surface for two call sites out of 278.
  **Reversible:** yes; adding those overloads later is additive.

- **`BugReport` pairs the prior-run read with its own clear, rather than using
  `DebugReport.deliver`** (autopilot, 2026-08-30). The library's
  `collect`/`deliver` pair does this correctly and is the documented path, but
  `CollectedReport` keeps its handle and sink `internal`, so it can only be
  settled by `deliver` — and `deliver` builds its own chooser, which cannot
  carry the screenshot this app attaches to the share intent. So the report
  reads through the public `readPreviousRun()` handle and calls
  `clearPreviousRun` itself, gated on the clipboard copy landing.
  **Alternative:** widen androidlog so `deliver` can take an attachment, or so
  a `CollectedReport` can be settled by a caller running its own share.
  **Reversible:** yes — the pairing is in one place (`CollectedPayload` and the
  `if (copied)` line in `share`), so adopting `deliver` later is a local change.

- **`functions/README.md` is now code, not docs** (autopilot, 2026-08-30).
  Narrowing `.github/lanes.conf` from `docs **/*.md` to `docs *.md` +
  `docs docs/*.md` — the standard lanes' README now states — moves the one
  markdown file that is neither at the root, nor under `docs/`, nor already
  matched by a `code` rule onto the code lane. (`core/parent-project/README.md`
  was already code via `code core/**`.) **Alternative:** a `docs functions/*.md`
  rule to keep it on the docs lane. **Not taken** — a per-path exception list
  is what lanes' README warns decays silently, and `functions/` is deployed
  code, so erring toward the full lane is the safe direction. **Reversible**
  by adding that one line.

Autopilot guesses from the CI-slowness pass. Each is cheap to undo.

- [ ] **`ReceiverWork` shares one process-lifetime scope across broadcasts**,
      replacing the per-broadcast `CoroutineScope(SupervisorJob() +
      Dispatchers.Default)` each alarm receiver built and cancelled. A shared
      scope is what gives a test something to join; the alternative was to
      leave the scopes alone and give each receiver its own exposed `Job`,
      which is three seams instead of one. `SupervisorJob` keeps the isolation
      the per-receiver scopes had. Reversible: the receivers can go back to
      building their own scope without touching the tests' shape.
- [ ] **`AlarmReceiverRoutingTest.enqueuedWorker` now asserts the worker was
      enqueued.** Its polling predecessor asserted nothing — it returned
      whatever it had once the deadline passed, so a receiver that enqueued
      nothing read as a pass. Adding the assertion is a widening of what the
      test checks, made on the way past rather than as its own decision.
      Reversible: drop the one `shouldBe` line.
- [ ] **Left `CastMediaServerTest`'s `Thread.sleep(25L * (attempt + 1))`
      alone** — it is a retry backoff against a real local HTTP server rather
      than a wait on in-process async work, and the class runs in 0.8s. It is
      the last `Thread.sleep` in `app/src/test`. Worth revisiting only if it
      ever flakes.
- [ ] **The release build moved into its own `release-build` job rather than
      being made cheaper inside `deploy`.** Measured first: R8 is ~150s of the
      release build's ~183s, so sharing compile outputs between the jobs (a
      cross-job Gradle build cache) would have bought almost nothing —
      `gradle/actions/setup-gradle` keys its cache per job and there is no
      input to change that, which is what the old comment in `ci.yml` said.
      The rejected alternative was merging the bundle into `unit-tests`, which
      would hand the test job the signing secrets. Reversible: move the three
      steps back into `deploy` and delete the job.
- [ ] **A signed AAB can now exist for a commit whose tests then fail.**
      `release-build` no longer waits for `unit-tests`. Nothing publishes it —
      `deploy` still needs the tests, and only `deploy` talks to Play — and it
      gives a red main run something to inspect. Reversible by adding
      `unit-tests` to `release-build`'s `needs:`, at the cost of putting the
      whole build back on the critical path.
- [ ] **Two jobs now enter `environment: production` per main run.** Safe as
      things stand: every recent `deploy` started within ~3s of its `needs:`
      completing, so the environment has no required reviewers today. If one is
      ever added, a main run would ask for approval twice — at which point the
      keystore secrets want moving to repository scope, or the jobs want
      merging again.
- [ ] Noticed in passing, not changed: `clothescast`'s `ci.yml` pins
      `gradle/actions/setup-gradle@v5` by tag, while `typelauncher`, `simmo`
      and `snoozemo` all pin it to SHA `4c125117…`. Every other action in this
      file is SHA-pinned with a comment about why. Worth aligning.
