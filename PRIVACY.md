# Privacy Policy

_Last updated: 2026-05-12_

ClothesCast is a daily weather-insight app for Android. This policy
describes what data the app handles, where it goes, and what control you
have over it.

## TL;DR

- ClothesCast has no user accounts and no backend that holds your data.
  The app may send anonymous crash reports and aggregate usage analytics
  to a third-party reporting service so the developer can fix bugs and
  decide which features to keep — see "Analytics and crash reporting"
  below for what those payloads include and (more importantly) the hard
  limits on what they don't.
- Your **approximate location** is sent to [Open-Meteo](https://open-meteo.com)
  to fetch the weather forecast and a place name. That is the only
  user-content data the app sends off your device by default.
- If you opt in to **online text-to-speech**, the short spoken sentence
  (e.g. _"50% chance of rain at 3pm — take an umbrella"_) is sent to the
  TTS provider you chose (Google Gemini) so it can return the audio.
- If you opt in to **calendar tie-in**, the app reads today's events on
  your device. The event title may appear inside the spoken sentence
  (e.g. _"Bring a jacket for your concert tonight"_), and is therefore
  also sent to the TTS provider in that one case — only when both calendar
  tie-in and online TTS are enabled.
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
- **Where it goes:** [Open-Meteo](https://open-meteo.com) (weather +
  geocoding APIs). Open-Meteo receives only a coordinate — no account,
  no device identifier.
- **Stored on device:** Your chosen location is saved in app settings so
  the daily refresh can run without re-prompting you.
- **Retention by us:** Until you uninstall the app or change locations.
- **Retention by Open-Meteo:** See <https://open-meteo.com/en/terms>.

### Calendar events _(optional, off by default)_

- **What:** Today's events — title, start/end time, location, all-day
  flag — read via Android's `CalendarContract` only when you grant
  `READ_CALENDAR` and enable the calendar tie-in setting.
- **Why:** To pair a clothes recommendation with a meeting that overlaps
  bad weather (e.g. _"Bring a jacket for your concert tonight"_).
- **Where it goes:** Calendar reading happens entirely on your device.
  The only way calendar data leaves the device is if (a) the tie-in fires
  for today's forecast _and_ (b) you have online TTS enabled — in which
  case the rendered sentence (which can include the event title) is sent
  to your chosen TTS provider for vocalization.
- **Stored on device:** The most recent rendered insight (which may
  contain that sentence) is cached for up to one day so the app doesn't
  recompute it on every launch.
- **Retention by us:** Replaced on the next daily refresh; cleared on
  uninstall.

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
  device-resolved location at lat, lon"). If the app crashes, recent
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

### API keys you provide

- If you use online TTS, you supply your own Google Gemini API key.
  Keys are stored on your device, encrypted at rest using a key sealed
  by the Android Keystore. They are sent only to the corresponding
  provider on requests you initiate, and are never shared with us or
  any third party.

## Third-party services

ClothesCast talks to these services on your behalf. Their own privacy
policies apply to anything they receive:

| Service | What we send | When |
|---|---|---|
| [Open-Meteo](https://open-meteo.com/en/terms) | Coarse coordinate | Always (forecast + geocoding) |
| [Google Gemini API](https://ai.google.dev/gemini-api/terms) | The short rendered insight sentence | Only if you select Gemini TTS |
| Analytics / crash-reporting service (e.g. Firebase Crashlytics + Google Analytics for Firebase) | Aggregate usage events and crash diagnostics — see "Analytics and crash reporting" below for what's in and out | Possibly always, in all builds |

These providers act as service providers fulfilling a single request and
returning the result. The TTS providers receive only the sentence to be
spoken, with no user identifier attached beyond the API key you supplied.

Note on Gemini API: request inputs are not retained for training by
default. See the provider's policy linked above for the authoritative
terms.

## What we do _not_ collect

- No accounts, no sign-in.
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
may send two kinds of payload to a third-party reporting service (e.g.
Firebase Crashlytics + Google Analytics for Firebase, or equivalent).
This may be present in all builds and run for all users by default; the
goal is to inform product decisions, not to identify you.

What's sent:

- **Crash reports:** stack trace, app version, Android version, device
  model, and a non-resettable install identifier used to group duplicate
  crashes. Sent automatically when a crash occurs.
- **Aggregate usage events:** the values of your in-app settings — TTS
  engine, schedule cadence, delivery mode, units, notification time,
  clothes-rule customisations, and the like — plus basic lifecycle
  events such as app open and daily refresh, so unused options can be
  pruned and defaults tuned.
- **API-call outcomes:** one event per outgoing network request to
  Open-Meteo or Gemini, carrying the endpoint identifier (e.g.
  `open_meteo_forecast`), the HTTP status code, an outcome bucket
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

What's **not** sent — these are hard limits, not "best-effort":

- **No calendar event data.** Not titles, not times, not locations, not
  attendees, not whether you have any events at all.
- **No user names**, account identifiers, email addresses, or contact
  info.
- **No location coordinates** or geocoded place names.
- **No insight prose**, notification text, or anything else that could
  carry free-form user content.
- **No API keys** or other credentials.
- **No precise GPS** or advertising identifiers.

The reporting service receives only what's described above and is bound
by its own privacy policy (linked from the third-party services table
above once a specific provider is chosen). ClothesCast is open source,
so you can audit exactly what's instrumented at
<https://github.com/mikelward/clothescast> — or build a copy with the
reporting calls stripped out, if you'd rather not participate.

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
