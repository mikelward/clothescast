# Privacy Policy

_Last updated: 2026-08-28_

ClothesCast is a daily weather-insight app for Android. This policy
describes what data the app handles, where it goes, and what control you
have over it.

## TL;DR

- ClothesCast has no login and no personal account. The free online
  voice signs in **anonymously** — a random ID with no name, email, or
  password — purely to count your daily usage; no backend holds personal
  data about you.
  The app can send anonymous crash reports and aggregate usage analytics
  to a third-party reporting service so the developer can fix bugs and
  decide which features to keep — but **only if you turn that on**.
  Nothing is sent until you do, with one exception if you are updating
  from a version where it was on: the reporting libraries start before
  any of the app's own code, so a report they were already holding — or
  an event they record themselves in that moment — can go before the app
  turns them off. The app does that on its first
  launch after the update, and they start off from then on — unless
  that launch ends before the app gets that far, in which case the next
  one tries again. A fresh install is never in this position.
  If the app crashes while it is off, the
  reporting library still writes that crash to your phone; it is never
  sent, and it is discarded when you first turn the switch on. See
  "Analytics and crash reporting" below for what those payloads include
  once it is on and (more importantly) the hard limits on what they
  don't.
- Your **approximate location** is sent to
  [Open-Meteo](https://open-meteo.com) to fetch the weather forecast,
  and (on Play Services devices) to Google's geocoding service via
  Android's `Geocoder` API to look up the city name shown next to
  the date. If you search for a place name (from the Settings
  location picker), the text you type is also sent to Open-Meteo
  so it can suggest matching places.
  That is the only user-content data the app sends off your device
  by default.
- If you opt in to **online text-to-speech**, the short spoken sentence
  (e.g. _"50% chance of rain at 3pm — take an umbrella"_) is sent to the
  TTS provider you chose (Google Gemini) so it can return the audio.
- If you opt in to **calendar extras**, the app reads today's events on
  your device. The event title may appear inside the spoken sentence
  (e.g. _"Bring a jacket for your concert tonight"_), and is therefore
  also sent to the TTS provider in that one case — only when both calendar
  extras and online TTS are enabled. If you also enable the MQTT bridge
  below, one calendar-derived boolean — whether the window has a located
  event, with no titles, times, or locations — is published to your own
  broker.
- If you opt in to the **Smart Home / Home Assistant MQTT bridge**
  (Settings → Smart Home, off by default), the rendered forecast
  sentence — plus a small calendar-derived `has_events` boolean — is
  published as a retained MQTT message to a broker you configure —
  typically the Mosquitto add-on running on your own Home Assistant. The
  broker host and credentials are entirely yours; nothing ClothesCast
  sends goes to a service the developer operates. See "Smart Home / Home
  Assistant bridge" below for what's in the payload.
- Nothing is sold, no advertising, no ad-targeting profiles, no
  third-party data sharing beyond the services listed below. (The
  aggregate usage analytics + crash reports above _do_ count as
  cross-session telemetry — that's what the install identifier in the
  crash payload is for. The "Analytics and crash reporting" section
  spells out exactly what's in and out.)

## Who we are

ClothesCast is an open-source Android app developed by Mikel Ward.
The source code is at <https://github.com/mikelward/clothescast>.

## Data the app handles

### Approximate location

- **What:** Coarse latitude / longitude (city-block level, never precise
  GPS — the app declares only `ACCESS_COARSE_LOCATION`).
- **Why:** To fetch the weather forecast for where you are and to look up
  a human-readable place name to show in the UI.
- **Where it goes:**
  - [Open-Meteo](https://open-meteo.com) — receives the coarse
    coordinate to return the weather forecast, and (separately) the
    raw text you type into a location search ("Edinburgh", "新宿")
    so Open-Meteo can match it to candidate place names. The search
    text leaves the device only when you actually type into the
    Settings location picker.
  - **Google's geocoding service**, via Android's
    [`Geocoder`](https://developer.android.com/reference/android/location/Geocoder)
    API on Play Services devices — receives the coarse coordinate so
    we can look up the city name (e.g. "London") and the country code
    (used to pre-select holidays in your country). On AOSP / non-Play
    devices `Geocoder` reports no backend and we skip the lookup;
    the Today header falls back to a date-only label.
  - **Google's Weather API** ([Google Maps Platform](https://developers.google.com/maps/documentation/weather)) —
    receives the coarse coordinate to return a weather forecast, **only
    if** you turn on the "Google" forecaster on the Settings → Forecasters
    page. That forecaster reuses your own Gemini API key (Google's keys
    are project-scoped, so the same key calls the Weather API when your
    project has it enabled), so the key is sent to Google with the
    request. Off by default; nothing goes to Google's Weather API unless
    you enable the forecaster and have set your own Gemini key.

  None of these services receives an account, device identifier, or any
  other payload beyond the items listed above.
- **Stored on device:** Your chosen location is saved in app settings so
  the daily refresh can run without re-prompting you.
- **Retention by us:** Until you uninstall the app or change locations.
- **Retention by Open-Meteo:** See <https://open-meteo.com/en/terms>.
- **Retention by Google:** See
  <https://policies.google.com/privacy>.

### Calendar events _(optional, off by default)_

- **What:** Today's events — title, start/end time, location, all-day
  flag — read via Android's `CalendarContract` only when you grant
  `READ_CALENDAR` and enable the calendar extras setting.
- **Why:** To pair a clothes recommendation with a meeting that overlaps
  bad weather (e.g. _"Bring a jacket for your concert tonight"_).
- **Where it goes:** Calendar reading happens entirely on your device.
  Two narrow paths can let calendar-derived data leave it: (a) if the
  extras fires for today's forecast _and_ you have online TTS enabled, the
  rendered sentence (which can include the event title) is sent to your
  chosen TTS provider for vocalization; and (b) if you enable the Smart
  Home MQTT bridge, a single event-presence boolean (`has_events` — no
  titles, times, or locations) is published to your own broker. See "Smart
  Home / Home Assistant bridge" below.
- **Stored on device:** The most recent forecast snapshot is cached for
  up to one day so the app doesn't refetch on every launch. Event titles
  and locations are never written to disk; only event timing (start,
  end, all-day) and a "has a location" flag are persisted, since that's
  all the insight renderer needs to re-derive the prose.
- **Retention by us:** Replaced on the next daily refresh; cleared on
  uninstall.

### Calendar-sourced theming _(optional, off by default)_

- **What:** When you enable "Holidays" or "Birthdays" in the Holiday
  settings screen, ClothesCast reads your synced device calendars to
  detect holiday and birthday events. Detection runs entirely on-device
  (event title pattern matching + source-calendar account inspection).
- **Why:** To theme today's outfit on holidays your curated catalog
  doesn't cover (Diwali, Eid, Lunar New Year, etc.) and on detected
  birthdays — a celebratory palette + a banner naming the day.
- **Where it goes:** Detected event titles — including contact names on
  birthday events — stay on your device. They are shown only in the themed
  banner on your screen: **never** read aloud through online TTS, **never**
  sent to Gemini, and **never** written to Firebase Analytics or
  Crashlytics.

  One narrow exception, and only if you use the online (Gemini) voice: on a
  day with a detected birthday, the spoken forecast opens with a generic
  "Happy birthday" — a fixed phrase from the app's own translations, with
  no name, no title, and no time. So the request carries the fact that
  *some* birthday falls today; it carries nothing about whose, or what the
  event was called. Detected public holidays from your calendar get no
  greeting at all — those titles are never spoken. On the device voice
  nothing leaves the phone either way.
- **Permission:** Both toggles require `READ_CALENDAR`, prompted by
  the toggle itself. Revoking permission later from system Settings
  doesn't auto-flip the toggle off; the reader simply returns no events
  until permission is restored.

### Notifications

- ClothesCast posts a local notification at the time you choose. The
  notification is generated on your device. Nothing is sent to a push
  service.

### Diagnostic logs and crash reports

- **What:** ClothesCast keeps a rolling on-device file of the most
  recent log lines — errors, warnings, info — at `cacheDir/diag.log`
  (plus one rotated predecessor `cacheDir/diag.log.1`, capped at
  ~200 KB each). Lines may include the rendered insight prose
  ("Insight delivered for …") and device-resolved coordinates ("Using
  device-resolved location at lat, lon"). At each launch the log also
  records **why ClothesCast's previous runs ended** — Android's own
  reason (a crash, an out-of-memory reclaim, an app update, the system
  stopping it), the exit code or signal the process ended on, how
  important Android considered the app at that moment, when it
  happened, and the system's own
  short description of it — together with
  **when ClothesCast was installed and last updated**. That is the app's
  account of itself rather than anything about you: an insight that never
  arrived looks the same whether the fetch failed or the process was
  killed before it ran, and without this there is nothing afterwards to
  tell those apart. Android's description text is written by the system
  and can name the component that stopped the app — for an update, the
  installer. If the app crashes, recent
  lines plus the stack trace are also written to
  `cacheDir/last-crash.txt`. The local bug-report payload includes the
  most recent ~300 lines of the diag file plus your current settings
  (schedule, units, TTS choice, clothes rules) and the most recently
  rendered insight prose.
- **Why:** So you can hand a complete diagnostic snapshot to the
  developer when something goes wrong, and so the developer can be
  alerted to crashes affecting users in the wild.
- **Where it goes:**
  - **Locally, always:** after a crash the home screen surfaces a
    banner offering to share the report; tapping **Share report** opens
    Android's system share sheet so you can pick a destination (email,
    Slack, Drive, etc.). The on-device file includes the full payload
    described above (settings + insight prose + log buffer).
  - **Automatically, possibly:** the app may also send a trimmed crash
    report — stack trace, app version, Android version, device model,
    and a non-resettable install identifier used only to group duplicate
    crashes — to a third-party crash-reporting service. This automatic
    payload does **not** include insight prose, calendar data, location,
    API keys, or the in-memory log buffer. (Your settings values may
    travel separately as part of the aggregate analytics described
    below.) See "Analytics and crash reporting" below for the full
    limits.
- **Stored on device:** The diag file (`cacheDir/diag.log` plus one
  rotated predecessor) persists across launches and app upgrades until
  rotated out by newer lines or cleared on uninstall / cache-clear, so
  bug reports filed after a process restart may include lines from
  earlier runs. A `---- process start <version> ----` marker line at
  each launch lets readers see where the current process began. The
  crash file persists across launches until a fresh crash overwrites
  it, and is cleared on uninstall.
- **Retention by us:** The on-device file is yours — whatever you do
  with it is governed by the destination you share to. Anything sent
  automatically to the crash-reporting service is governed by that
  service's privacy policy.

### Smart Home / Home Assistant bridge _(optional, off by default)_

- **What:** The rendered forecast sentence — the same one displayed in
  the notification, e.g. _"Today, cool and mild. Wear a sweater. Chance
  of rain at 3pm."_ The bridge publishes this string as a retained MQTT
  message after each scheduled twice-daily refresh, so Home Assistant
  (or any MQTT-aware consumer) can read the latest value and speak it
  on a trigger of your choice (a wardrobe door opening, a humidity
  spike, a fixed time of day, etc.).
- **Why:** To let you hear your tuned ClothesCast forecast on a
  Google Home, Nest Hub, Echo, or any other speaker your home
  automation can reach — without reimplementing the clothes-rule and
  insight logic in Home Assistant.
- **Where it goes:** The MQTT broker you configure in Settings → Smart
  Home. That's typically the Mosquitto add-on running inside your own
  Home Assistant instance on your local network. The broker host,
  port, optional TLS, optional username, and topic prefix are all
  values you supply; ClothesCast never sends this payload anywhere you
  haven't pointed it at. The broker is **not** a service the developer
  operates; it's your infrastructure under your control.
- **What's in the payload:** The rendered sentence string, UTF-8
  encoded — the same text you see in the notification — and, on their
  own sibling topics when available, the outfit-card image (the same
  800 × 480 PNG shown on a Nest Hub), the spoken TTS audio clip (the
  same bytes the phone speaks, Gemini engine only), and a combined MP4
  that simply muxes that image and audio together for one-shot casting.
  The image and audio carry the same insight — the card renders the
  sentence, the audio speaks it — so the MP4 introduces no new data; it
  only repackages what the image and audio topics already publish. A
  small `now/timestamp` topic carries the epoch-millis time of the most
  recent publish so a consumer can tell a fresh forecast from a retained
  one. A `has_events` topic carries a single boolean — `true`/`false` for
  whether that window has a located calendar event — so an automation can
  tell a deliberately-quiet evening (when "notify only on event nights"
  keeps the phone silent) from an outage. It's the only calendar-derived
  field here, and it carries no event titles, times, or locations. The
  payload includes any calendar-event extras clause that fires
  (e.g. _"Bring a jacket for your concert tonight"_) when you have the
  calendar extras enabled, because that clause is part of the rendered
  sentence. No coordinates, no API keys, no settings values, no device
  identifiers travel with any of these messages.
- **Authentication:** If you set a broker username, the bridge sends
  it on connect; the corresponding password is stored on your device
  encrypted at rest (same Tink-AEAD pattern as the Gemini API key
  storage above) and only sent to the broker you configured.
- **Retention:** Retained MQTT messages persist on your broker until
  you delete them or publish a new value to the same topic. The next
  twice-daily refresh overwrites the previous payload. Retention by
  the broker is governed by your own broker configuration.
- **Setup guide:** See [docs/smart-home.md](docs/smart-home.md) for
  step-by-step configuration on both the ClothesCast side and the
  Home Assistant side.
- **Local-network discovery:** When you tap "Find broker" on the
  Smart Home settings page, ClothesCast issues standard mDNS / DNS-SD
  queries (`_home-assistant._tcp.` and `_mqtt._tcp.`) over your local
  network multicast group so it can offer to pre-fill the broker
  host. Nothing about you or your forecast is included in the queries
  — they're indistinguishable from any other mDNS browser on your
  network — and discovery stops as soon as you leave the page or pick
  a result. No discovery happens unless you tap the button.

### Online TTS: shared key by default, your own key on request

Online TTS (the Gemini voice) has two paths.

- **Default — shared key via the ClothesCast TTS proxy.** When you have
  not set your own Gemini key, online TTS requests go to a small
  ClothesCast-operated Cloud Function instead of directly to Google.
  The function holds a Google Gemini API key paid for by the developer,
  forwards your request to Google, and returns the audio. It does not
  log the spoken prose or the audio response.
  - **First use — an anonymous identifier from Google.** Before the
    first shared-key request, the app signs in anonymously to Firebase
    Authentication (a Google service). This is **not** a user account:
    there is no name, email, password, or sign-in screen. It returns a
    random, app-generated identifier — just a string of letters and
    digits, e.g. `a1B2c3…` — that is **not** linked to your name,
    email, phone number, device, or location, and is never used for
    advertising or tracking across apps. It exists only so the proxy
    can count how many free clips this app install has used today. On
    the shared path your data is handled by ClothesCast and Google
    servers.
  - **Sent on each request:** the rendered insight sentence (the same
    text the phone speaks), the anonymous identifier above (carried as
    a signed Firebase ID token in the `Authorization` header, which the
    proxy verifies to read the identifier — the client cannot forge or
    swap it), a Firebase App Check attestation token (proves the request
    came from a genuine install — verified in-memory and discarded), and
    the model name. During the current rollout the app *also* sends a
    Firebase Installation ID for backward compatibility (so older proxy
    versions still recognize the install); it is likewise an anonymous,
    random identifier with no personal information, and is dropped once
    the rollout completes. No coordinates, calendar metadata as fields,
    user account, advertising ID, or hardware / device identifiers are
    added.
  - **Stored:** a Firestore document at `quota/<your anonymous ID>`
    with a first-use timestamp, a successful-call counter, and a
    most-recent-use timestamp, capped at 5 successful clips per UTC day.
    Used to enforce the free daily limit and for capacity planning. Not
    used for advertising or cross-app tracking. Your anonymous
    identifier resets if you reinstall the app or use "Clear data" in
    Android Settings.
  - **Cloud Functions infrastructure logs** (operated by Google Cloud,
    not configured by us) record the client IP and HTTP status of
    each call. We do not associate the IP with your anonymous
    identifier.
- **Your own key (BYOK).** Open Settings → Voice → Gemini API key and
  paste your own key. Keys are stored on your device, encrypted at
  rest using a key sealed by the Android Keystore. With your own key
  set, online TTS requests skip the ClothesCast proxy entirely and
  go straight to Google — no anonymous identifier, no App Check token,
  no anonymous sign-in, no proxy involvement. Your key is never shared
  with us or any third party.

## Third-party services

ClothesCast talks to these services on your behalf. Their own privacy
policies apply to anything they receive:

| Service | What we send | When |
|---|---|---|
| [Open-Meteo](https://open-meteo.com/en/terms) | Coarse coordinate (forecast); your typed search text when you use the Settings location picker | Always for forecast; only when you search for a place name |
| Google's geocoding service (via Android's [`Geocoder`](https://developer.android.com/reference/android/location/Geocoder) on Play Services devices), governed by [Google's Privacy Policy](https://policies.google.com/privacy) | Coarse coordinate | Always on Play Services devices (city / country lookup for the Today header); skipped on AOSP devices |
| [Google Weather API](https://developers.google.com/maps/documentation/weather) (Google Maps Platform), governed by [Google's Privacy Policy](https://policies.google.com/privacy) | Coarse coordinate and your own Gemini API key | Only if you enable the "Google" forecaster on the Forecasters page and have set your own Gemini key |
| Google Firebase Authentication | An App Check attestation, to mint and refresh an anonymous identifier (no name, email, or password) | First use of the shared-key path, then periodic token refreshes |
| ClothesCast TTS proxy (Cloud Function, ClothesCast-operated) | The short rendered insight sentence, an anonymous app identifier (as a signed Firebase ID token; plus a Firebase Installation ID during the current rollout), a Firebase App Check token, and the Gemini model name | Online TTS with the shared key (default) |
| [Google Gemini API](https://ai.google.dev/gemini-api/terms) | The short rendered insight sentence (forwarded by the proxy, or sent directly when you use your own key) | Online TTS, either path |
| Your self-hosted MQTT broker (e.g. Mosquitto inside Home Assistant) | The short rendered insight sentence; the outfit-card image, TTS audio, and muxed MP4 on sibling topics; an update timestamp; and a calendar-derived `has_events` boolean (no event titles, times, or locations) — all as retained MQTT messages | Only if you opt in to the Smart Home bridge and configure a broker |
| Analytics / crash-reporting service (e.g. Firebase Crashlytics + Google Analytics for Firebase) | Aggregate usage events and crash diagnostics — see "Analytics and crash reporting" below for what's in and out | Only if you turn on crash and usage reporting in Settings → Privacy, plus the one upgrade exception described there |

These providers act as service providers fulfilling a single request and
returning the result. When the ClothesCast TTS proxy is in the path, it
adds the anonymous identifier and App Check token described above; when
you use your own Gemini key, the request goes straight to Google and no
anonymous identifier is attached.

Note on Gemini API: request inputs are not retained for training by
default. See the provider's policy linked above for the authoritative
terms.

## What we do _not_ collect

- No user accounts and no personal sign-in — no name, email, password,
  or login screen. (The free online-TTS path uses an anonymous,
  randomly-generated identifier to count daily usage, described under
  "Online TTS" above; it identifies an app install, not you, and carries
  no personal information.)
- No advertising identifiers, no ad networks, no ad targeting.
- No precise GPS location.
- No contacts, photos, microphone, or files.
- No data is sold or shared for advertising, profiling, or model training
  by us. (Provider terms govern what they may do; the linked policies
  above describe their commitments for API access.)

## Your controls

- **Location:** Revoke the Location permission in Android Settings, or
  change / clear it in ClothesCast's Settings.
- **Calendar:** Revoke `READ_CALENDAR` in Android Settings, or turn off
  "Use calendar events" in ClothesCast's Settings.
- **Online TTS:** Switch the voice engine to "Device" in Settings → Voice
  to keep all spoken text on-device.
- **API keys:** Clear them from Settings → API Keys at any time.
- **Everything:** Uninstalling the app deletes all locally stored data
  (settings, cached insight, API keys).

## Analytics and crash reporting

To spot bugs and decide which features are worth keeping, ClothesCast
can send two kinds of payload to a third-party reporting service (e.g.
Firebase Crashlytics + Google Analytics for Firebase, or equivalent).

**This is off until you turn it on**, in Settings → Privacy. Both SDKs
are started with collection disabled, before any of the app's own code
runs, so on a fresh install nothing is sent and nothing is queued to be
sent.

Being exact about one thing, because "off" does not mean the same for
both: analytics collects nothing at all, but the crash reporter still
writes a crash to your phone even while it is off — turning collection
off stops it *sending*, not catching. Such a report is never sent, and
it is discarded the moment you first turn the switch on rather than
being released. The goal, once you do turn it on, is to inform product
decisions, not to identify you.

What's sent, once it is on:

- **Crash reports:** stack trace, app version, Android version, device
  model, and a non-resettable install identifier used to group duplicate
  crashes. Sent automatically when a crash occurs.
- **Aggregate usage events:** the values of your in-app settings — TTS
  engine, schedule cadence, delivery mode, units, notification time,
  clothes-rule customisations, and the like — plus basic lifecycle
  events such as app open and daily refresh, so unused options can be
  pruned and defaults tuned.
- **API-call outcomes:** one event per outgoing network request to
  Open-Meteo, Google's Weather API (only if you enable the Google
  forecaster), or Gemini, carrying the endpoint identifier (e.g.
  `open_meteo_forecast`, `google_weather`), the HTTP status code, an outcome bucket
  (`success` / `http_error` / `timeout` / `network_error` /
  `other_error`), and the request latency in milliseconds. No URL
  parameters, no request or response bodies — the endpoint identifier
  is a fixed string from a short enum, not a captured URL. Used to spot
  rate-limit hits and transient server issues in aggregate. Failures
  that look like "device offline / airplane mode" are filtered out
  before the event is sent so the stream reflects real provider issues
  rather than the user's local radio state.
- **Notification delivery timing:** when the daily / tonight alarm
  actually posts its notification, one event carries the slot
  (`today` / `tonight`) and two delay numbers in milliseconds — alarm
  fire delay (how late the OS released the alarm after the scheduled
  trigger, an indicator of Doze deferral) and total delay (how late
  the notification reached the system, which also folds in WorkManager
  waits for connectivity). No timestamps, no location, no calendar or
  insight content. Used to spot regressions in delivery punctuality.
  Powered-off / airplane-mode misses simply don't appear in the
  stream — the event only fires when a notification is actually
  posted, so unfired alarms aren't reported.
- **Daily refresh outcomes:** when the scheduled fetch + insight + notify
  pipeline reaches a terminal result, one event carries the slot
  (`today` / `tonight`), an outcome bucket (`success`, `forecast_error`,
  `no_location`, `cancelled`, or `other_error`), and the wall-clock
  latency from worker start to terminal result in milliseconds. No
  forecast content, no insight prose, no location coordinates, no error
  messages. Used to spot regressions in delivery reliability ("what
  percentage of scheduled refreshes actually completed today?").
  WorkManager retries between transient failures (no connectivity, 5xx
  from upstream) aren't reported individually — only the terminal
  outcome the user actually sees.
- **Settings snapshot:** once per app process start, and again each time
  the user changes a non-voice setting later in the same process, one
  event carries the resolved values: unit settings (auto vs. explicit
  choice + the unit in effect), delivery mode for the daily and
  tonight slots, theme mode, colour palette, the default-bottom
  fallback choice, the four schedule booleans (tonight enabled,
  notify-only-on-events, daily-mention-evening-events, use-calendar-
  events), the day-of-week counts for daily and tonight schedules
  (1..7), and the schedule times bucketed to the hour ("00".."23").
  No exact local time, no calendar content, no location, no insight
  prose. The launch-time emission is intentional — it samples the
  population's default behaviour without requiring the user to
  interact, so reports can answer "which features do users actually
  configure and which defaults serve them best?"
- **Clothes-rule customisation snapshot:** same cadence as the settings
  snapshot — once per app process start, then again whenever the user
  edits their clothes rules in the same process. One event carries:
  the count of catalog defaults the user has changed; the count of extra
  rules added beyond the catalog defaults; a sorted comma-joined list
  of which catalog categories were customised; an `all_defaults` flag;
  per-category integer Celsius deltas from the default threshold for the
  temperature rules (`sweater` / `jacket` / `coat` / `gloves` / `shorts`),
  clamped to ±5°C (so e.g. moving the jacket threshold from 10°C down to
  8°C reports `-2`); and, for the two rain-gear rules (`umbrella`,
  `rain jacket`), a signed percentage-point delta of their rain-probability
  gate from the default, rounded to the nearest 10 and clamped to ±50 (so
  raising the umbrella gate from 20% to 60% reports `+40`). A rain-gear
  rule whose only change is its weather-code floor (drizzle / rain / off)
  still shows up in the customised-categories list, but the floor value
  itself is never sent. No raw thresholds, no weather-code values, no
  user-added rule items.

What's **not** sent — these are hard limits, not "best-effort":

- **No calendar event data.** Not titles, not times, not locations, not
  attendees, not whether you have any events at all. The calendar-sourced
  holiday/birthday titles described above stay on-device — the themed
  banner is rendered locally, never read aloud through online TTS, never
  sent to Gemini, and never written to Firebase. (The generic "Happy
  birthday" greeting noted in that section does reach Gemini on the online
  voice; it never reaches Firebase.)
- **No user names**, account identifiers, email addresses, or contact
  info. This explicitly includes contact names that may appear on
  birthday-themed banners on the Today screen.
- **No location coordinates** or geocoded place names.
- **No insight prose**, notification text, or anything else that could
  carry free-form user content. _Exception:_ if you opt in to the Smart
  Home / Home Assistant MQTT bridge in Settings → Smart Home and
  configure a broker, the rendered insight sentence is sent to **that
  broker** — your own infrastructure, not the analytics service.
- **No API keys** or other credentials.
- **No precise GPS** or advertising identifiers.

**It is off until you turn it on, and you can turn it back off.**
Settings → Privacy has the switch. Leave it alone and nothing is sent —
with one exception, if you are updating from a version where reporting was
on: the reporting libraries start before any of the app's own code, so a
report they were already holding — or an event they record themselves in that
moment — can still go before the app turns them off. The app does that on its first launch after the update, and they
start off from then on — unless that launch ends before the app gets that
far, in which case the next one tries again. A fresh install is never in
this position. The Changelog entry for 2026-08-28 describes it in full.

Turn it on and then off again and the app stops collecting, resets the
analytics identifier so a later re-enable starts a new stream rather than
resuming the old one, and asks the crash reporter to discard the reports
it is still holding.

Two things worth being exact about. If the app crashes while reporting is
off, the reporting library still writes that crash to your phone — it is
never sent, and it is discarded the moment you first turn the switch on,
rather than being released. And within a period you *had* switched on,
anything already queued can't be recalled, and a crash or event landing
in the moment you flip the switch is handed over before the new setting
reaches the library; either of those may still go.

The reporting service receives only what's described above and is bound
by its own privacy policy (linked from the third-party services table
above once a specific provider is chosen). ClothesCast is open source,
so you can audit exactly what's instrumented at
<https://github.com/mikelward/clothescast>.

## Children

ClothesCast is not directed at children under 13 and does not knowingly
collect personal data from them.

## Changes

If this policy changes, the updated version is committed to
<https://github.com/mikelward/clothescast/blob/main/PRIVACY.md> and the
"Last updated" date at the top reflects the change. A short summary of
each material change is added to the [Changelog](#changelog) below, and
material changes are also noted in the app's release notes.

The full revision history of this file is viewable on GitHub:
**[View all changes to PRIVACY.md →](https://github.com/mikelward/clothescast/commits/main/PRIVACY.md)**

## Contact

Open an issue at <https://github.com/mikelward/clothescast/issues> or
email the address listed on the Play Store listing.

## Changelog

- **2026-08-28** — **Crash and usage reporting is now off until you turn it
  on.** It used to be on by default, with a one-time banner telling you how to
  switch it off. Both SDKs now start with collection disabled, before any of
  the app's own code runs, so on a fresh install nothing is sent and nothing is
  queued to be sent. An existing install that never made a choice is treated as
  not having consented, so reporting stops there too until it is turned on. The
  Today banner is now an invitation rather than a notice.

  **Existing installs are migrated, not grandfathered.** An install that had
  never made a choice is treated as not having agreed: on its first launch
  after the update the app turns both reporting SDKs off and discards whatever
  they were holding, rather than leaving them as they were. That step is
  necessary rather than belt-and-braces — the crash reporter keeps its own
  copy of the collection setting, and it outranks the app's new default.

  One exception, and only if you had reporting on before: the reporting
  libraries start before any of the app's own code, so for the moment between
  them starting and the app turning them off, a report they were already
  holding — or an event they record themselves in that moment — can still go.
  Nothing the app itself records reaches that window: everything that produces
  usage events waits until the stored choice has been applied to both
  libraries. That moment is the first launch after the update, and
  they start off from then on — but a launch that ends before the app reaches
  that step leaves them on, so the next launch opens the same moment again and
  turns them off there. There is no such window on a fresh install, where they
  have never been on.

  **A crash captured before you first turn it on is discarded, not sent.**
  Discarding held reports is new in this release, in both directions:
  previously the switch set the two collection flags and nothing else, so
  turning reporting off left whatever was already captured on the phone.
  Now turning it off discards what is still held, and turning it on for the
  first time does too — because switching the reporter to "don't send" never
  stopped it writing crashes to the phone.

  Turning it off after a period with it on also **resets the analytics
  identifier**; previously the switch set the collection flags and nothing
  more, and those take effect only from the next launch. One limit remains and
  applies only to a period you had switched on: a crash report or usage event
  already queued during that period cannot be recalled.

- **2026-06-21** — The **Smart Home / Home Assistant MQTT bridge** now
  publishes a `has_events` boolean (`true`/`false`) on each window's topic
  (and the `now` mirror): whether that forecast window has a located
  calendar event, so a Home Assistant automation can tell a deliberately-
  quiet evening (when "notify only on event nights" keeps the phone silent)
  from an outage. This is a new calendar-derived field crossing the device
  boundary — but only to your own broker, only when you have both the
  calendar extras and the bridge enabled, and it carries no event titles,
  times, or locations, just the single boolean. See the updated "Smart Home
  / Home Assistant bridge" and "Calendar events" sections above.
- **2026-06-17** — Rain gear (umbrella, rain jacket) now also fires on the
  forecast weather code, catching drizzle the probability gate misses. The
  `clothes_rules_snapshot` analytics event gained one aggregate param,
  `rain_jacket_delta_pct`: the rain jacket's rain-probability gate as a
  signed percentage-point delta from its 50% default, bucketed exactly like
  `umbrella_delta_pct` (rounded to the nearest 10, clamped to ±50, `MISSING`
  if deleted). No raw thresholds, no weather-code values, no calendar /
  location / insight content — same hard limits as the rest of the event.
- **2026-06-01** — The umbrella is now a default clothes rule keyed on
  rain probability. The `clothes_rules_snapshot` analytics event gained
  one aggregate param, `umbrella_delta_pct`: the user's umbrella
  rain-probability gate as a signed percentage-point delta from the 30%
  default, rounded to the nearest 10 and clamped to ±50 (e.g. `"+30"`,
  `"-20"`, `MISSING` if the rule was deleted). Same hard limits as the
  rest of the event — no raw thresholds, no calendar / location / insight
  content.
- **2026-05-31** — Transitional note for the identifier change below:
  during the staged rollout the app sends *both* the new anonymous
  Firebase Authentication identifier and the older Firebase Installation
  ID, so older versions of the proxy keep working while clients update.
  Both are anonymous, random identifiers with no personal information.
  The Installation ID is dropped once the rollout completes.
- **2026-05-31** — Hardened the shared-key TTS identifier. The free
  online-TTS path now identifies each install by an anonymous Firebase
  Authentication identifier, verified on the server, instead of the
  Firebase Installation ID the app previously sent in a header. This
  stops a modified client from rotating the identifier to bypass the
  daily free-clip limit. The identifier is still random and carries no
  personal information; the daily counter moved from
  `installs/<install ID>` to `quota/<anonymous ID>`. To obtain the
  identifier the app now signs in anonymously to Firebase Authentication
  (a Google service) — no name, email, or password, and no user-facing
  account. See "Online TTS: shared key by default, your own key on
  request" and the third-party services table above.
- **2026-05-31** — Online TTS now has a shared-key default: when you
  haven't supplied your own Gemini API key, the spoken sentence is sent
  to a small Cloud Function operated by the developer, which forwards
  it to Google's Gemini API and returns the audio. The function holds
  the Gemini key, doesn't log the spoken prose, and records only a
  per-install counter (keyed by your Firebase Installation ID) for
  capacity planning. A Firebase App Check token is sent alongside each
  request to prove it came from a genuine install; it's verified and
  discarded. The BYOK path (your own key in Settings) is unchanged and
  bypasses the proxy entirely. See "Online TTS: shared key by default,
  your own key on request" and the updated third-party services table
  above for the full breakdown.
- **2026-05-29** — The optional usage-analytics settings snapshot now
  records one analytics event per Settings page (Schedule, Clothes,
  Format, Region, Display, Calendar) instead of a single combined event.
  Content-neutral: the same coarsened settings values are reported, just
  grouped by page; nothing new leaves the device and the same privacy
  toggle still gates it.
- **2026-05-23** — Internal change to how the daily insight is cached on
  device: the cache now stores the upstream forecast snapshot plus
  minimal event timing (start, end, all-day, a "has a location" flag),
  and re-derives the insight against your current settings on read.
  Event titles and free-form locations are not written to disk. No
  material change to what leaves the device. See the updated "Calendar
  events" section above.
- **2026-05-16** — Added an optional **Smart Home / Home Assistant MQTT
  bridge** (Settings → Smart Home; off by default). When enabled, the
  app publishes the rendered forecast sentence as a retained MQTT
  message to a broker you configure (typically the Mosquitto add-on
  inside your own Home Assistant). This relaxes the existing "insight
  prose never leaves the device" hard limit — but only to your own
  broker, never to a developer-operated service or analytics endpoint.
  The broker password, if you set one, is stored on-device encrypted
  with the same Tink-AEAD pattern as the Gemini API key. See the new
  "Smart Home / Home Assistant bridge" section above and
  [docs/smart-home.md](docs/smart-home.md) for the full setup.
- **2026-05-14** — Added three new aggregate analytics events:
  `daily_refresh` (slot + outcome bucket + latency, one per terminal
  result of the scheduled fetch + notify pipeline; WorkManager retries
  between transient failures aren't individually reported),
  `settings_snapshot` (the resolved values of the non-voice user
  settings — units, delivery mode, theme / palette / default-bottom,
  the four schedule booleans, day counts, and schedule times bucketed
  to the hour), and `clothes_rules_snapshot` (counts of customised
  defaults and extra rules, sorted list of customised categories, plus
  per-category integer Celsius delta from the default threshold,
  clamped to ±5°C). No raw thresholds, no exact local times, no
  calendar / location / insight content; same hard limits in
  "Analytics and crash reporting" apply.
- **2026-05-12** — Added two new aggregate analytics events: per-request
  API-call outcomes (endpoint identifier, HTTP status, outcome bucket,
  latency) for Open-Meteo and Gemini, and notification-delivery delay
  (slot + two millisecond delays) when the daily alarm actually posts.
  Neither event carries request bodies, URL parameters, location,
  timestamps, or calendar / insight content; the offline-failure
  filter on API events drops airplane-mode noise. Same hard limits in
  "Analytics and crash reporting" apply; the toggle in Settings →
  Privacy still controls whether anything is sent.
- **2026-05-12** — Diagnostic logs are now persisted to
  `cacheDir/diag.log` (with one rotated predecessor, ~200 KB each)
  instead of living only in a process-memory ring buffer. This lets
  errors logged by background workers reach a bug report filed after
  the OS later kills the process. The categories of data the diag log
  may contain (rendered insight prose, device-resolved coordinates,
  error / warning text) are unchanged — they were already shown in the
  bug-report payload from the in-memory buffer; what changes is that
  they now live on disk between launches, similar to the existing
  crash file. Nothing new leaves the device; the bug-report share-sheet
  flow is unchanged.
- **2026-05-06** — Removed OpenAI and ElevenLabs as TTS provider options;
  Google Gemini is now the only online TTS provider. Removed the
  corresponding rows from the third-party services table and the note on
  OpenAI's 30-day input retention. Updated the API-key storage description
  accordingly. No change to what data leaves the device or when.
- **2026-05-05** — Wired the previously-anticipated Firebase Crashlytics
  + Google Analytics for Firebase integration into the app. Default-on
  with a one-time non-blocking notice on the Today screen pointing the
  user at the new Settings → Privacy toggle to turn it off. The hard
  limits described in "Analytics and crash reporting" above are
  unchanged; this entry just records that the SDKs are now actually
  loaded (provided the developer has supplied `app/google-services.json`).
- **2026-05-03** — Permitted automatic crash reporting and aggregate
  usage analytics in all builds and for all users, with hard limits on
  what those payloads may include (no calendar data, location, insight
  prose, API keys, precise GPS, or ad identifiers). Broadened analytics
  to cover in-app settings values. Simplified the API-key storage
  description.
- **2026-05-01** — Initial publication.

[View full revision history on GitHub →](https://github.com/mikelward/clothescast/commits/main/PRIVACY.md)
