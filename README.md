# ClothesCast

A daily weather-insight app for Android. Each morning, at a time you set,
ClothesCast posts a one-sentence comparative summary —
_"4°C warmer than yesterday, leave the sweater at home"_ or _"Rain likely
around 3pm."_ — derived on-device from yesterday's actual weather, today's
forecast, and the clothes thresholds you've configured.

It can also speak the insight aloud — through the platform TTS engine, or
through an online voice (Gemini) if you'd rather a more natural read.

See [PRIVACY.md](PRIVACY.md) for what data leaves the device and when.

## Status

Working v1: full ClothesCast pipeline (Open-Meteo → on-device rendering →
notification, phone TTS, Cast, MQTT, widgets, and cached Today refreshes),
Compose Settings UI for location, schedule, delivery toggles, units, clothes
rules, voice, API keys, calendar tie-in, smart-home outputs, privacy, and
telemetry, runtime permission UX, boot/timezone/locale alarm re-arm, and debug
/ manual actions for testing without waiting until the scheduled time.

Distribution: every push to `main` ships a signed AAB to the Play Store
internal track for testers, and a debug APK is also available from the CI
artifact (see below) for sideload installs.

## Tech stack

- **Android-only Kotlin** for v1. Domain and data modules are pure Kotlin
  so they can be promoted to Kotlin Multiplatform `commonMain` later
  without a rewrite.
- **Jetpack Compose** + Material 3 UI.
- **AlarmManager (`USE_EXACT_ALARM`) → BroadcastReceiver → WorkManager**
  for daily scheduling that survives Doze, reboot, timezone and locale
  changes.
- **Open-Meteo** for forecast and city geocoding (no key, free).
- **Deterministic on-device rendering** for the insight sentence —
  template-fillable rules over the forecast, clothes thresholds, weather
  alerts, and (optionally) today's calendar events. No LLM round trip.
- **TTS — your choice of engine**: the platform `TextToSpeech` engine
  (default, fully on-device), or an online voice via the Gemini API.
  Online engines are BYOK; keys are encrypted on-device via Tink +
  Android Keystore + DataStore Preferences.
- **Optional calendar tie-in**: with `READ_CALENDAR` granted, calendar
  events can gate event-relevant weather advice without turning event titles
  into notification, voice, MQTT, or Cast content.
- **Optional device location**: with `ACCESS_COARSE_LOCATION` granted, the
  worker uses the phone's coarse location at notify-time instead of the
  city you picked.
- **Tests**: JUnit5 + Kotest assertions + Ktor MockEngine for HTTP, plus
  golden-file fixtures for parser tests and Roborazzi snapshots for
  Compose previews.

## Modules

| Module | Status |
|---|---|
| `:core:domain` | Pure-Kotlin models, use cases (insight rendering, clothes rules), repository interfaces |
| `:core:data` | Open-Meteo forecast + geocoding clients, Gemini TTS client, parser tests |
| `:app` | Compose UI, manifest, receivers, worker, alarm scheduler, DI, platform TTS, calendar reader, encrypted key store, widgets, Cast, MQTT, telemetry |

## Installing on a phone

Three options, in roughly increasing order of friction:

- **Play Store internal track** (testers): added in the Play Console, you
  get the latest `main` build automatically. Every push to `main` ships a
  signed AAB to this track.
- **Firebase App Distribution** (testers): added to the `testers` group in
  the FAD console, you get the debug APK from every push to `main` with a
  one-tap install link.
- **Sideload from CI artifact** (anyone with repo read access): for any
  branch or PR, not just `main`.
  1. Push your work or open a PR. CI builds a debug APK on every commit
     (see `.github/workflows/ci.yml`).
  2. From the GitHub Actions run, download the `app-debug-apk` artifact.
  3. Unzip it; transfer `app-debug.apk` to the phone (Drive, USB, etc.).
  4. Tap to install. Android will prompt about installing from unknown
     sources — accept once for your file manager / browser.

## First-run setup

1. **Notifications**: on Android 13+ a banner at the top of Settings will
   ask for `POST_NOTIFICATIONS`. Without it the daily insight has nowhere
   to go.
2. **Location**: tap _Set location_ and search for a city, _or_ enable
   _Use device location_ (Settings → Data Sources) to pick up the phone's
   coarse location at notify-time. Until you do one of these, the worker
   falls back to London.
3. **Schedule**: pick a time and the days of the week you want the
   notification.
4. **Clothes rules** (optional): the defaults (`sweater`, `jacket`,
   `coat`, `gloves`, `shorts`) are a sensible starting set. Add your own or
   tune the thresholds to match how you dress.
5. **Voice** (optional): the platform TTS engine works out of the box. To
   use a more natural online voice, pick Gemini in Settings → Voice and
   paste your API key in Settings → API Keys.
6. **Calendar tie-in** (optional): toggle _Use calendar events_ in
   Settings → Data Sources and grant `READ_CALENDAR` to let calendar presence
   gate event-relevant advice without putting event titles in rendered output.
7. **Verify**: tap the **Fire insight now** button (About → Debug card,
   only in debug builds) to exercise the full pipeline without waiting
   until the scheduled time.

## Building locally

Requires JDK 21. Pure-Kotlin modules build without an Android SDK:

```sh
./gradlew :core:domain:test :core:data:test
```

The `:app` module needs the standard Android SDK (`compileSdk 35`,
`build-tools;35.0.0`) on the local Android Studio install path. The CI
workflow shows the exact setup.

## OEM background restrictions

Some Android OEMs (Xiaomi, Oppo, OnePlus, aggressive Samsung profiles) kill
backgrounded apps and prevent the alarm from firing on schedule. See
[dontkillmyapp.com](https://dontkillmyapp.com) for per-OEM workarounds —
typically "exclude from battery optimisations" and "allow autostart". The
underlying scheduling primitive (`setExactAndAllowWhileIdle` with
`USE_EXACT_ALARM`) is the strictest the platform offers; anything that
suppresses it is a vendor-side override.

## Roadmap

- **Recommendation tuning**: refine clothing advice with more real-world
  forecast cases, including how humidity and wind should influence the
  current feels-like-driven thresholds.
- **More garments**: add wet-weather items and a broader everyday catalogue,
  with matching drawables, editable defaults, and clear copy boundaries
  between rain warnings and clothing advice.
- **Gemini trial path**: explore a way for users to try Gemini voice without
  bringing their own API key, such as a limited free trial, paid trial, or
  other owner-funded quota with explicit cost caps and abuse controls.
- **iOS**: deferred until a Mac is available _and_ a small APNs backend
  is in place — iOS cannot self-wake at a precise local time.
