# Severe weather alerts

Proposal, 2026-10-22. Nothing here is built yet. An earlier attempt called an
Open-Meteo `/v1/warnings` endpoint that does not exist; every fetch 404'd into
an empty list, and it was removed in `4b9f9aa8`. Open-Meteo still has no
warnings product, so the data has to come from somewhere else.

Facts marked *unverified* could not be checked from the research sandbox
(Google's developer docs, the Met Office, MeteoAlarm and NWS sites were
unreachable); confirm them before building on them.

## Recommendation

1. **Start with Google's `publicAlerts:lookup`, through our proxy.** It is
   the same Weather API (`weather.googleapis.com`) and the same Maps
   Platform key the Google forecast already uses, so there is nothing new to
   enable. Google sign-in required, like the forecast proxy. Users with their
   own key call Google directly. The app keeps only alerts whose polygon
   contains the user's point; if Google's UK alerts turn out to be whole
   MeteoAlarm regions, the UK comes from the Met Office's own API instead
   (see "Location").
2. **Fetch alerts with every forecast refresh** (the app's 1-hour rule), plus
   a background check for notifications whose cadence is still open (3
   hours, adaptive or hourly; see "Cadence, freshness and cost"). Keep a
   short in-memory cache on the proxy; store nothing.
3. **Fill Google's gaps later with one generic CAP ingester** on a server
   cron, reading the WMO/Alert-Hub directory of 231 national feeds rather
   than integrating authorities one by one (see "One integration for many
   countries"). Each source is switched on after vetting its terms and area
   precision. Victoria needs VicEmergency, which isn't CAP; it can follow
   later (maintainer, 2026-10-22). The same cron can take over NWS,
   MeteoAlarm and Environment Canada if cost or scale matters, since its
   cost doesn't grow with users.
4. **If notifications need to be minutes-fresh, push instead of poll**:
   the cron (or a feed listener) sends FCM messages to coarse-area topics.
   This is the only change that improves freshness; a server cron alone
   does not, because the device's poll dominates.

No new subscriptions: every recommended source is Google (the account and
key we already have) or free. Paid options are recorded below for completeness.

## What the user sees

Mocks: [Weather alerts mocks](https://claude.ai/artifact/At8xMVnBa6ZARY99qAfS4q)
(private to the maintainer).

- **Levels follow the alert's CAP severity**, never our own reading of
  colors in the text:

  | CAP severity | Usual color | Icon (Material) | Notification |
  |---|---|---|---|
  | Extreme | Red | Filled octagon (`report`) | Yes |
  | Severe | Orange (amber in the UK) | Filled triangle (`warning`) | Yes |
  | Moderate | Yellow | Outlined triangle (`warning_amber`) | No |
  | Minor, unknown | (statements, advisories) | Outlined circle (`info`) | No |

  *Unverified*: that the Met Office maps yellow, amber and red to Moderate,
  Severe and Extreme in Google's data. Check against live alerts.
- **One severity icon, usable anywhere without text.** Shape and fill rise
  with severity, and each level has a fixed tint, so the icon reads on its
  own in color and in monochrome: Android draws status-bar notification
  icons in one color, and hue alone fails color-blind users. Tints, chosen
  for at least 3:1 against both themes' surfaces:

  | Level | Light theme | Dark theme |
  |---|---|---|
  | Red | `#C62828` | `#FF8A80` |
  | Orange | `#E65100` | `#FFB74D` |
  | Yellow | `#B58800` | `#FFD54F` |
  | Neutral | `#6E6A75` | `#CAC4D0` |

  The same icon marks the insight card's header (highest level), each alert
  card, the notification (small icon and accent), and later the widget.
  Its content description names the level ("Severe weather alert").
- **Today**: one single-row card per alert at the top of the page: the
  severity icon, the title, the end time ("Until Sat 9 AM"; "From 6 PM"
  before it starts only once start times are known to be onset, see
  "Coverage") and an expand arrow. Tapping expands the full time range
  and issuer, the area, the issuer's description and instruction, and a link
  to the issuer's page. Cards are outlined and neutral so the icon carries
  the color (the yellow tint can't be read on a yellow card). More than
  three alerts collapse behind "2 more alerts".
- **Titles**: "{level} {hazard} warning" ("Amber wind warning") where the
  issuer grades by color (MeteoAlarm members, including the UK), with
  "Amber" for UK issuers and "Orange" for the rest of Europe; the issuer's
  own event name elsewhere ("High Wind Warning" from NWS, which has no
  colors). Maintainer's choice (2026-10-22) over the Met Office's verbatim
  "Amber warning of wind"; see "Met Office display rules". The issuer's own
  headline and text stay verbatim in the expanded card.
- **Notifications** (Severe and Extreme only), on their own channel at
  default importance (sound, no heads-up):
  - Title: the same title as the card ("Amber wind warning"). The issuer's
    name stays out of the title.
  - Text: time range and issuer ("Until Sat 9 AM · Met Office"); expanded
    adds the area.
  - Small icon: the severity icon, so the status bar shows the level.
  - One notification per alert ID, updated in place (no second sound unless
    the severity rises), removed when the alert expires
    (`setTimeoutAfter`) or disappears from a later check.
  - Lock screen shows the title, times and issuer only; the area name (a
    region, so a coarse location) is in the private version.
  - Channel ID `weather_alerts_v2`: `weather_alerts_v1` belongs to the
    removed feature and `NotificationChannelRegistrar` deletes it on every
    start. Recreating a deleted ID restores its old user settings.
- **No "all clear".** When a check fails, Today shows nothing new and keeps
  any alert still inside its validity window; it never says there are no
  alerts. Failures are logged and shown in the bug report, never swallowed
  into an empty list (the bug that hid the last attempt).
- **Signed out, no own key**: no alerts. Settings says how to get them.

### Settings

Maintainer's design (2026-10-22):

- **A new "Alerts" section in Settings**, its own page like Forecasters.
- **Master switch "Weather alerts", off by default.** Turning it on while
  signed out starts Google sign-in, as the free voice and Google forecast
  switches do; signing in alone doesn't turn it on. Users with their own key
  need no sign-in.
- **"Notify me for"**: which levels post a notification, defaulting to
  orange and red. Options, each shown with its severity icon: red only;
  orange and red; all levels (yellow too); none. Today shows every level
  whatever this says.
- **"Alert sound and lock screen"** opens Android's settings for the alerts
  channel.
- **A promo card on Today** once the user is signed in with Google and
  alerts are off: "Get weather alerts", opening the Alerts settings,
  dismissible, shown until followed or dismissed (like "Try Google
  forecasts").
- **Later: on by default**, if it can be done without a sign-in prompt and
  at an acceptable cost (`TODO.md`).

### Strings

Base `values/strings.xml` (en-US); `values-en-rGB` overrides say "warning"
where en-US says "alert", matching UK usage. Descriptions and instructions
are the issuer's text, shown unedited and possibly not translated.

| Name | en-US | en-GB |
|---|---|---|
| `notification_channel_weather_alerts_name` | Weather alerts | Weather warnings |
| `notification_channel_weather_alerts_description` | Severe weather alerts for your location from official weather services. | Severe weather warnings for your location from official weather services. |
| `weather_alert_title` | %1$s %2$s warning *(level, hazard)* | |
| `weather_alert_level_yellow` | Yellow | |
| `weather_alert_level_orange` | Orange | |
| `weather_alert_level_amber` | Amber *(UK issuers, any locale)* | |
| `weather_alert_level_red` | Red | |
| `weather_alert_hazard_*` | wind, rain, snow and ice, thunderstorm, fog, heat, cold, coastal, flood, wildfire, avalanche, weather *(fallback)* | |
| `weather_alert_level_description_*` | Minor weather alert, Weather alert, Severe weather alert, Extreme weather alert *(icon content descriptions)* | …warning |
| `weather_alert_notification_text` | %1$s · %2$s *(time range · issuer)* | |
| `weather_alert_notification_summary` (plural) | %d weather alerts | %d weather warnings |
| `weather_alert_until` | Until %1$s | |
| `weather_alert_from` | From %1$s | |
| `weather_alert_range` | %1$s to %2$s | |
| `weather_alert_open_source` | Open %1$s *(issuer)* | |
| `weather_alert_more` (plural) | %d more alerts | %d more warnings |
| `settings_alerts_section` | Alerts | Warnings |
| `settings_weather_alerts_title` | Weather alerts | Weather warnings |
| `settings_weather_alerts_summary` | Official alerts for your location on Today. | Official warnings for your location on Today. |
| `settings_weather_alerts_sign_in` | Sign in with Google to get weather alerts. | Sign in with Google to get weather warnings. |
| `settings_alerts_notify_title` | Notify me for | |
| `settings_alerts_notify_red` | Red only | |
| `settings_alerts_notify_orange_red` | Orange and red | Amber and red |
| `settings_alerts_notify_all` | All levels | |
| `settings_alerts_notify_none` | None | |
| `settings_alerts_channel` | Alert sound and lock screen | Warning sound and lock screen |
| `alerts_promo_title` | Get weather alerts | Get weather warnings |
| `alerts_promo_body` | Official alerts for where you are, with notifications for severe ones. | Official warnings for where you are, with notifications for severe ones. |
| `alerts_promo_action` | Alerts settings | Warnings settings |

Times: time only for today, weekday and time otherwise ("Sat 9 AM"), in the
device's 12/24-hour setting.

## Location

An alert for somewhere 50 miles away is wrong, not just noisy. Rules:

- **Same place as the forecast.** Alerts use the forecast's location (device
  fix or chosen place), at the same 2-decimal precision (about 1 km). When
  it changes, alerts for the old place are dropped and their notifications
  canceled; the background check never uses a different location from the
  one on screen.
- **Inside the area, not near it.** Keep an alert only if its polygon
  contains the point, with about 1 km of tolerance for the rounding. Don't
  trust the lookup alone: Google returns each alert's polygon, so the app
  checks containment itself.
- **No polygon, no alert** by default: an area name alone ("South East
  England") can't be checked. Revisit per source once the coverage script
  shows which sources send polygons.
- **Too broad to be about you**: a polygon more than about 1,000 km across
  (Tokyo's typhoon alert spanned about 2,900 km) shows on Today with the
  neutral icon and never notifies, whatever its severity. Met Office
  warnings, a few hundred km across, stay well inside the limit.
- **Event types can be wrong**: Google reported freezing rain in São Paulo
  in October. Titles are composed from the event type only where the source
  is MeteoAlarm, whose hazard types are reliable; elsewhere the card shows
  the issuer's own title.
- **Region-sized polygons are the real risk.** A warning drawn around one
  corner of a region but attached to the whole region contains the user
  while the hazard is far away. How each source draws areas decides
  whether it's usable:

  | Source | Area | Verdict |
  |---|---|---|
  | NWS | Storm-based polygons for short-fuse warnings; forecast zones (about county-sized) otherwise | Fine |
  | Met Office NSWWS API | Hand-drawn MultiPolygons | Fine; the precise UK source |
  | MeteoAlarm | Whole EMMA regions: French départements; for the UK, regions like "South East England" (*unverified*) | Too coarse where regions are large |
  | Google | Whatever its source sent; the UK and most of Europe via MeteoAlarm | Check with the script |
  | DWD (direct to Google) | District-level or polygons (*unverified*) | Likely fine |
  | Environment Canada | Forecast regions with polygons (*unverified*) | Likely fine |

  If Google's UK alerts come back with region-sized boxes (the script
  prints each polygon's size), the Met Office's own API becomes the UK
  source and Google stays for the rest.

## Coverage

Google's alerts come from its Public Alerts partners
([list](https://support.google.com/publicalerts/), read 2026-10-22). That
list is the best coverage evidence available; *unverified* that the Weather
API serves every one of them.

- **Europe arrives through MeteoAlarm**: the UK (Met Office, plus flood
  warnings from the Environment Agency, SEPA and Natural Resources Wales),
  Ireland, France, Spain, Italy, the Netherlands, Belgium, Portugal,
  Switzerland, Austria, the Nordics, Poland, Greece and most of the rest of
  Europe. Germany comes from DWD directly.
- **Direct national services** elsewhere: the US (NWS, plus USGS, EPA, the
  National Tsunami Warning Center, AMBER child-abduction alerts and
  Everbridge), Canada (Environment Canada), India, Brazil, Mexico, the
  Philippines, Indonesia, New Zealand (MetService, GeoNet), Taiwan,
  Thailand, Vietnam, and many services across Africa, the Middle East, Latin
  America and the Caribbean.
- **Gaps**: Australia has only state fire services (ACT, NSW, SA, Tasmania,
  Queensland), no Bureau of Meteorology weather warnings, and Victoria isn't
  served at all. Being a partner doesn't mean the Weather API serves the
  country: India (IMD) and South Korea (KMA) come back "not supported".
  China, Russia, Turkey, Ukraine, Argentina, Peru, South Africa, Egypt,
  Malaysia and the UAE are not listed at all.
- **Not all alerts are weather**: the US feed carries AMBER alerts,
  earthquakes and air quality; the UK carries river flood warnings. Filter
  by event type (weather, flood, fire, coastal) before showing anything.

**No single free feed is global.** NWS covers only the US, its territories
and US waters; it has nothing for Europe. Without Google, matching its reach
for likely users means combining feeds: NWS (US), MeteoAlarm (the UK and
Europe, the same data Google gets), Environment Canada, and then country by
country (state feeds for Australia, which Google lacks; JMA for Japan,
which the Weather API mostly lacks). The WMO's register of about 500 alerting authorities is
the directory for the rest.

### Unsupported at launch

With Google as the only source, these get no useful alerts. "Tested" means
the coverage script asked; the rest come from Google's partner list.

| Country or region | At launch | Later option |
|---|---|---|
| Australia: Victoria (Melbourne) | Nothing (tested: "not supported") | VicEmergency; not needed at launch (maintainer, 2026-10-22) |
| Australia: everywhere else | Fire warnings only (NSW, ACT, SA, Tasmania, Queensland); no Bureau weather warnings; nothing for WA or the NT | State feeds; the Bureau's own feed needs a paid agreement |
| Japan | Broad typhoon alerts only (tested), too broad to notify | JMA's own feeds (not in the WMO/Alert-Hub directory) |
| South Korea | Nothing (tested) | KMA's CAP feed (in the directory) |
| India | Nothing (tested), though IMD is a Google partner | IMD and NDMA CAP feeds (in the directory; state-level areas, often too coarse) |
| South Africa | Nothing (tested) | SAWS's CAP feed (in the directory) |
| Turkey | Nothing (tested) | None found |
| China | Not a Google partner | CMA's CAP feed (in the directory; region codes only, needs boundary data). Not a blocker (maintainer, 2026-10-22). |
| Russia | Not a Google partner | Roshydromet's CAP feed (in the directory) |
| Argentina | Not a Google partner | SMN's CAP feed (in the directory) |
| Egypt | Not a Google partner | EMA's CAP feed (in the directory) |
| Ukraine | Not a Google partner | Ukraine's MeteoAlarm feed (in the directory) |
| Malaysia, UAE, Peru | Not Google partners | None found |
| Anywhere else not on Google's partner list | Assume nothing | Check the WMO/Alert-Hub directory |

Rows marked "in the directory" become one switch each in the generic CAP
ingester (see "One integration for many countries"), after vetting.

### Filling Google's gaps

Each gap has a national feed, merged in by the server cron (Architecture
B) for points Google answers "not supported". Most are also in the
WMO/Alert-Hub directory, so one CAP ingester reads them (see "One
integration for many countries"); VicEmergency isn't CAP. Terms are
*unverified* unless stated.

- **Victoria (Melbourne), the most important gap**, not needed at launch
  (maintainer, 2026-10-22). The VicEmergency feed
  (`emergency.vic.gov.au/public/events-geojson.json`, per community
  projects) is one GeoJSON file of warnings and incidents (fire, flood,
  storm), updated about every minute, with areas as polygons. Emergency
  Management Victoria's notice licenses it CC BY 3.0 AU: credit the State of
  Victoria, link the notice, and show when the data was last updated, so the
  card's source line becomes "VicEmergency · updated 10:05". An old EMV
  support page says the feed isn't public; community projects have read it
  for years. To check: that it carries the Bureau's severe weather warnings
  (not just fire), and whether Bureau content in it carries the Bureau's
  own terms.
- **The rest of Australia.** The Bureau's own CAP-AU warnings sit on its
  anonymous FTP, but free only for personal or in-organization use;
  publishing them needs a paid Registered User agreement, so it's out.
  State feeds instead: Queensland's storm, flood and cyclone warnings
  (GeoJSON and CAP-AU, every 30 minutes, licence not stated), Tasmania's
  TasAlert warning polygons and NSW's Hazard Watch feed (both ArcGIS,
  GeoJSON); nothing found for WA, SA or the NT. Google already carries
  fire warnings for NSW, the ACT, SA, Tasmania and Queensland.
- **India**: NDMA's SACHET platform carries IMD warnings as RSS with a CAP
  1.2 message per item, at national and state level (not local); terms for
  apps not published.
- **South Korea**: KMA publishes an English CAP warning RSS
  (`weather.go.kr/w/rss/cap/warning.do`, from the WMO register).
- **South Africa**: SAWS publishes a CAP RSS
  (`caps.weathersa.co.za/Home/RssFeed`, plain HTTP, from the WMO
  register); liveness unknown.
- **Turkey**: no machine-readable feed found.
- **Japan**: JMA's feeds (see Sources), if Google's typhoon-only answers
  aren't enough.

A per-country feed can also keep the location on the device: the cron
publishes each region's active alerts as one small file (usually empty),
and the app downloads its region's file and checks containment itself,
so no coordinate is sent at all.

### One integration for many countries

Nearly every national service publishes CAP 1.2, and others already
aggregate it, so global reach doesn't mean hundreds of integrations. It
means one CAP parser plus a vetting checklist per source.

- **The WMO/Alert-Hub source directory**
  (`alert-hub-sources.s3.amazonaws.com/json`, read 2026-10-22): 231 CAP
  feeds from 158 countries, all marked operating, 225 of them official.
  It covers most of Google's gaps: the Bureau of Meteorology (mirrored by
  the WMO), IMD and NDMA (India), KMA (Korea), SAWS (South Africa), CMA
  (China), Roshydromet, Argentina's SMN and Egypt's EMA. Turkey, Japan,
  Malaysia, the UAE and Peru are absent. A cron reads the directory, polls
  the allowed feeds (231 feeds every 5 minutes is about 66,000 fetches a
  day, inside the free tier) and fetches each new CAP document.
- **WMO Severe Weather Information Centre on WIS2**: since mid-2025 the
  Hong Kong Observatory publishes SWIC's aggregate of members' CAP
  warnings as one WIS2 dataset, so a single MQTT subscription is global
  push. The data policy is "recommended" (may carry conditions), and
  copyright stays with each authority. Needs an always-on listener.
- **IFRC Alert Hub** (`alerthub.ifrc.org`): aggregates every CAP alert
  from authorities in the WMO register and advertises an API for
  rebroadcasting (REST and GraphQL in its open-source code). Docs and terms
  weren't reachable from the sandbox.
- **ESRI's CAP Connector**: republishes everything Alert-Hub aggregates as
  an ArcGIS feature service, which might answer point queries directly.
  ESRI's terms; *unverified*.
- **Paid aggregators** (OpenWeather, Xweather, meteoblue) do the same work
  for a fee; rejected above.

The catch is per source, not per integration:

- **Area precision varies.** A sample of China's CMA alerts from the
  aggregated feed (bilingual, CAP severities, sensible event names) carried
  no polygons, only national region codes. Under "No polygon, no alert"
  they'd all be dropped until we add boundary data for those codes:
  per-country data work, not integration work. Sources with polygons (NWS,
  most of MeteoAlarm) need nothing extra.
- **Terms stay with each authority.** The Bureau's restriction on
  commercial republication still applies when the WMO mirrors its feed.
- **Language** is whatever the source sends.

So the vetting checklist per source is: terms allow use in an app; alerts
carry polygons, or region codes we can map; language; severity matches
CAP's meaning.

**Checking it**: `functions/scripts/check-alert-coverage.sh <projectId>`
asks Google for alerts at about 30 test cities with the proxy's key and
prints what comes back, from which agency, when it's valid, and each
polygon's size, point count and whether it contains the city. An empty
answer means either no active alert or no coverage; run it while a national
warning map shows something at a test point to tell the two apart.

**First run, 2026-10-22** (highlights; the Met Office showed yellow warnings
around Birmingham, east of Glasgow and over Wales):

| Test point | Result |
|---|---|
| Birmingham | Moderate wind, Met Office. Polygon about 419 × 324 km, matching the Met Office map (Manchester to the south coast and London); the area name lists every region it touches. |
| Glasgow | Moderate rain, Met Office. |
| Paris | Moderate wind, Météo-France. |
| São Paulo | Moderate "freezing rain": implausible there in October, so Google's event type is probably mis-mapped from the source. |
| Tokyo | Typhoon, severity unknown, polygon about 2,913 × 2,477 km. Japan answers despite the FAQ, but too broadly to be about Tokyo. |
| Melbourne | 404, "not supported for this location". |
| Seoul, Mumbai, Johannesburg, Istanbul | Not supported. |

**Second run, same day**, with validity and containment:

- Birmingham and Manchester share one wind warning, 09:03 UTC today to
  20:00 UTC tomorrow; Cardiff has a separate one, 09:00 UTC today to 16:00
  UTC tomorrow. Every returned alert's polygon contains its test city,
  consistent with the lookup matching on the polygon rather than on the
  region names in `areaName`; the app checks containment itself anyway.
- **Start times look like issue times**, not onset: 09:03 is not a Met
  Office start time, and the wind was forecast to pick up overnight. Until
  that's ruled out, the card shows only the end time ("Until Fri 9 PM") and
  never "From …".

Still open: whether UK polygons are the Met Office's drawn areas (tens of
points) or traced regions (thousands of points). Birmingham's box matching
the Met Office map, rather than the larger union of the regions it lists,
points to drawn areas; the point count would confirm it.

## Sources

### Google Weather API: `publicAlerts:lookup`

- **Coverage**: see "Coverage". Alerts come from Google Public Alerts'
  partners, minus what the Weather API doesn't serve: its FAQ excludes Japan,
  Korea and prohibited territories (Korea and India answer "not supported";
  Japan returned a typhoon alert anyway).
- **Endpoint**: `GET /v1/publicAlerts:lookup` exists (an unauthenticated
  call from the sandbox got Google's "use an API key" 403, not a 404).
- **Shape**: point lookup only (latitude, longitude, language code). Each
  alert has an ID, title, event type, area name, polygon, start and
  expiration times, description, instruction, safety recommendations, CAP
  severity, certainty and urgency, and the publishing agency's name and
  link. Text is served "as-is" from the source, so it may not be localized.
- **Cost**: Weather Usage is an Essentials SKU: 10,000 free calls a month,
  then $0.15 per 1,000 (to 100k), $0.12, $0.09, $0.06, and $0.038 above 5M.
  *Unverified*: whether alerts bill under that SKU and share its free calls
  with the hourly forecast, which already uses about 420 calls a month per
  device (`SPEC.md`).
- **Freshness**: whatever Google's ingest lag is (*unverified*), plus our
  poll interval.
- **Terms**: the Maps Platform service terms allow temporary caching of some
  Weather API content (one hour for current conditions and hourly forecasts,
  per search snippets); *unverified* for alerts. The same terms bar using
  cached content to recreate a Google product such as a general weather app.
  Attribution required.
- **Bulk**: none; see "Why not a worldwide Google sweep".

### Free government feeds

| Source | Coverage | Access | Cost and limits | Freshness | Notes |
|---|---|---|---|---|---|
| NWS `api.weather.gov/alerts` | US and territories | Pull: all active alerts in one call, or `?point=lat,lon`; GeoJSON | Free, no key; User-Agent with contact required; rate limits unpublished | Cache headers follow each alert's life cycle | Some alerts reference forecast zones instead of a polygon (zone geometry from `/zones`). Public domain (*unverified*). |
| NWS NWWS-OI | US | Push over XMPP | Credentials from NWS; sign-up can take 30+ days; cost *unverified* | Seconds | Raw text products; needs an always-on client. |
| Met Office NSWWS public API | UK | Pull: Atom feed linking GeoJSON (MultiPolygon) warnings for the next 7 days | Free within 20,000 calls a day, 200 per second burst; registration *unverified* | They recommend polling the Atom feed once a minute | Strict style guide, see below. |
| Met Office RSS | UK, per region | Pull: RSS summaries | Free; website terms | As issued | The API suits apps better. |
| MeteoAlarm EDR API | ~38 European countries (EUMETNET) | Pull: OGC API EDR (position or area queries) | Free token after registration review; page lists CC BY 4.0; commercial-use terms *unverified* | As issued by each member | Legacy per-country CAP Atom feeds also exist. Warnings reference EMMA region codes, so geometry may need a separate lookup. Color levels yellow, orange, red. |
| MET Norway MetAlerts 2.0 | Norway | Pull: `/current`, lat/lon queries; CAP, GeoJSON, RSS | Free; User-Agent required; licence *unverified* | As issued | GeoJSON was beta at launch. Also in MeteoAlarm. |
| ECCC (Canada) | Canada | Pull CAP files from Datamart, or push over AMQP (Sarracenia, anonymous queues) | Free open data; licence *unverified* | Push: seconds | Only free push feed found that needs no sign-up besides WIS2. |
| JMA | Japan (a Google gap) | Pull: Atom feeds and per-prefecture JSON | Free; terms *unverified* | As issued | Japanese text. One client library reports the Atom feeds drop older in-force warnings. |
| WMO WIS2 Global Broker | Members publishing CAP through WIS2 (partial, growing) | Push over MQTT (`origin/a/wis2/#`, public read-only login) | Free; "core" data unrestricted | Seconds | Coverage depends on each country adopting WIS2; check which publish alerts. |
| WMO Alert Hub / Alert-Hub.org | ~500 alerting authorities' CAP feeds | Registry of feed URLs; aggregated display (SWIC); ESRI republishes it as a GIS feature service | Free; machine-access terms *unverified* | Per feed | Google Public Alerts and AccuWeather use a subset of the same list. A feed directory more than an API. |
| FEMA IPAWS public feed | US | CAP | *Unverified* | | Mostly duplicates NWS for weather. |
| BOM | Australia | Pull: CAP-AU warnings on anonymous FTP | Free for personal or in-organization use only; publishing needs a paid Registered User agreement | As issued | Out unless we pay; see "Filling Google's gaps" for state feeds. |
| VicEmergency | Victoria | Pull: one GeoJSON of warnings and incidents | Free, CC BY 3.0 AU with attribution and a last-updated time | About a minute | The Melbourne fix; see "Filling Google's gaps". |

### Paid or account-based (rejected: another vendor and key)

| Source | Coverage | Cost found | Notes |
|---|---|---|---|
| OpenWeather One Call 3.0 | Global, national agencies | 1,000 calls a day free, then per call | Alerts included in the response. |
| OpenWeather Global Weather Alerts | Global | Monthly subscription, arranged by email | Push; up to 30 minutes' delay. |
| Xweather | US, Canada, Europe incl. Met Office, BOM | 15,000 accesses a month free; paid around $300 a month for 1M (third-party figures) | |
| meteoblue Warnings API | National CAP warnings | Price not found; beta | |
| WeatherAPI.com | Global | Alerts reportedly on the free plan | |
| Weatherbit | Global | Alerts tier unclear | |
| Tomorrow.io | Global | Free 500 requests a day; alerting limited; paid by quote | Its own alerts are threshold rules, not official warnings. |

### Build our own from Open-Meteo

Free and global, from variables we already fetch or could add (gusts, rain
totals, snowfall, feels-like extremes, CAPE and thunderstorm codes). Not
recommended as alerts: they are not official, they would contradict official
warnings at the edges, the Met Office style guide asks redistributors not to
mix other warning sources with theirs, and Open-Meteo's free API is
non-commercial. Fine as clothes advice ("Gusts to 70 km/h, an umbrella won't
help"), never labeled a warning.

### Android's own alerts

Wireless Emergency Alerts, EU-Alert and UK Emergency Alerts arrive by cell
broadcast for the most dangerous, short-fuse events (tornado warnings, a red
warning with risk to life). Apps can't read them; they cover the
minutes-ahead cases a polling app can't.

## Met Office display rules

The Met Office's NSWWS style guide (v1.3) binds anyone redistributing its
warnings: show them "without exception or modification", call them "Met
Office Weather Warnings", don't mix in other warning sources, and follow
its attribution rules. Highlighting the ones that matter is allowed.

Most of the design complies: every warning shows on Today, descriptions and
instructions are verbatim, notifications pick the severe ones, the issuer is
named in the body, and there is one source per country. **The titles don't**:
"Amber wind warning" rewords the Met Office's "Amber warning of wind", by the
maintainer's choice. The risk is lower while the data arrives through Google
and MeteoAlarm (*unverified* whether the guide binds us at two removes), and
higher if the UK switches to the Met Office's own API (see "Location"), where
its terms apply directly; revisit the titles then.

## Architecture

### A. Per-user lookup through the proxy (phase 1)

- **Proxy**: a new `weatherAlerts` function next to `weather`, sharing its
  `GOOGLE_WEATHER_API_KEY` secret. Same gates: POST form body (the
  coordinate stays out of request logs), App Check, Google sign-in (403
  `sign_in_required` for anonymous tokens). Forwards only latitude,
  longitude, a validated language code and a bounded page size. Its own daily
  cap per account (`weatherAlertsQuota/g_<hex>`, about 30 a day) so forecast
  walks can't starve it. Never logs or stores the coordinate.
- **Server cache**: in memory per instance, keyed by the 2-decimal
  coordinate the app already sends, 15-minute TTL, never written anywhere.
  It helps exactly when traffic is high (warm instances, many users per
  cell) and costs nothing when it's low. A coarser key would save more calls
  but miss small polygons (US storm-based warnings), so it isn't used. Check
  the caching terms first (`SPEC.md` requires it).
- **Client**: `:core:domain` gets a `WeatherAlert` model with CAP severity
  and the pure-Kotlin filters (point in polygon, event type, expiry);
  `:core:data` a client over `GoogleWeatherAccess` (own key or proxy) that
  returns success or a logged failure, never an empty list on failure;
  `:app` a periodic worker (cadence per "Cadence, freshness and cost",
  network required), a cache of
  alerts plus which IDs were notified at which severity, the notifier and
  the Today card.
- **GCP setup**: none beyond what the forecast proxy has. The proxy uses the
  API key in the secret, not a service account, so the key's API
  restrictions must allow the Weather API (they already do if the Google
  forecast works). The function's runtime account only reads the secret.

### B. Server cron over free feeds (phase 2)

- Cloud Scheduler triggers a function every 5 minutes that pulls NWS (all
  active alerts), MeteoAlarm (which carries the Met Office), Environment
  Canada and the gap-fillers (VicEmergency first), normalizes them to CAP
  fields, and writes one compact index (alerts plus polygons) to Cloud
  Storage. The lookup function loads it and answers point-in-polygon
  queries; Google handles the countries the feeds don't cover.
- **Cost**: effectively nil. Cloud Scheduler includes 3 free jobs per
  billing account, then $0.10 a job a month; invocations, compute and storage
  sit in free tiers. It does not grow with users.
- **Freshness**: no better than A on its own, because the device still polls.
- **Effort**: one parser per feed, region-code geometry for MeteoAlarm and
  NWS zones, and per-feed terms to honor.

### C. Push (phase 3)

- The cron (or an always-on listener on WIS2 MQTT, ECCC AMQP or NWWS-OI)
  detects new alerts and sends FCM messages to topics named for coarse cells
  the alert polygon covers (for example geohash-4, about 40 × 20 km).
  Devices subscribe to their cell's topic.
- **Freshness**: minutes (cron) or seconds (listener), independent of polling
  and Doze.
- **Cost**: FCM is free. A listener needs an always-on host (a free-tier
  e2-micro VM, or Cloud Run with a minimum instance, which isn't free).
- **Privacy**: the topic subscription tells Firebase the device's coarse
  cell for as long as it's subscribed. Our server never sees it, but this is
  a durable record where today there is only a per-request one; it needs a
  `PRIVACY.md` change and a decision. Adds Firebase Cloud Messaging to the
  app.

### Why not a worldwide Google sweep

Google only answers point lookups. Covering the world's land on a 0.25° grid
is about 300,000 points; hourly, that is about 220 million calls a month,
roughly $8,000 a month even at the cheapest tier, and it would still miss
polygons smaller than the grid. Sweeping only where users are means the
server must know where they are, and costs at least as much as per-user
lookups at our scale.

## Cadence, freshness and cost

Warnings in Europe are issued hours to days ahead (Met Office yellow up to
7 days, red usually closer to the event); US warnings such as tornado and flash
flood have minutes to an hour of lead time, which cell broadcast covers
better than any app.

The app's existing freshness rules, for comparison:

| What | Freshness | Where |
|---|---|---|
| Open-Meteo forecast | 1-hour in-memory cache | `CachingWeatherRepository` |
| App open | Silent refresh when the snapshot is 1 hour old | `FetchAndNotifyWorker.SILENT_REFRESH_MIN_AGE` |
| Widgets | Repaint hourly from the cache; refetch when 6 hours old; refresh at each window boundary | `WidgetRefreshReceiver`, `WidgetRefreshScheduler` |
| Scheduled briefings | Exact alarms at the user's times | `DailyAlarmScheduler` |
| Google 7-day forecast | 12 hours (7 billed calls a walk), refetched only on app open | `GoogleForecastCache` |
| Location | Last-known fix up to 1 hour old (5 minutes for the at-home voice check) | `LocationResolver` |

Alerts differ from the forecast in one way that matters: their whole point
is to reach someone who isn't looking. So the forecast's on-screen rules
(app open, widget ticks) are necessary but not enough; notifications need a
background check of their own. One alert lookup is a single billed call,
against seven for a Google forecast walk.

- **On screen**: fetch alerts with every forecast refresh, under the same
  1-hour rule as app open, so Today is never staler than the forecast. An
  alert past its end time disappears locally without a fetch.
- **In the background**, the options:

  | Background check | Calls per device a month (with opens) | Cost per device a month past the free tier | A warning issued at 22:00 notifies by |
  |---|---|---|---|
  | Every 3 hours | ~270 | ~$0.04 | 01:00 at worst |
  | Adaptive: 3 hours, hourly while the next 48 hours look severe or an alert is active | ~320 | ~$0.05 | 23:00 on a bad-weather day, 01:00 otherwise |
  | Every hour | ~750 | ~$0.11 | 23:00 |
  | Every 30 minutes | ~1,470 | ~$0.22 | 22:30 |

  "Look severe" reads the forecast we already have: gusts, heavy rain or
  snow, thunderstorm codes, or extreme feels-like temperatures in the next
  48 hours. Warnings mostly follow the forecast, so the adaptive check gets
  the hourly freshness on the days it matters for about the 3-hour cost. It
  can miss hazards the forecast doesn't show (river floods from upstream
  rain, fire danger), which the 3-hour baseline still catches.

At $0.15 per 1,000, before the proxy cache and assuming alerts bill as
Weather Usage (*unverified*). 1,000 signed-in devices at 3 hours: about
270,000 calls, roughly $35 a month; hourly, about 750,000 calls, roughly $85
with the volume tiers. Android may defer
periodic work for rarely used apps, so the real interval can stretch past
the nominal one whatever we choose.

| Path | Issuance to server | Server to device | Typical total |
|---|---|---|---|
| A: Google, 3-hour poll | Google's lag | Up to 3 hours or more | Minutes to hours |
| B: cron every 5 minutes, 3-hour poll | Up to 5 minutes | Up to 3 hours or more | Minutes to hours |
| C: cron + FCM push | Up to 5 minutes | Seconds | About 5 minutes |
| C: feed listener + FCM push | Seconds | Seconds | About a minute |

## Privacy

- A: the 2-decimal coordinate and the device language go through our proxy to
  Google, as the Google forecast already does; own-key users send them to
  Google directly. Nothing stored. Needs a `PRIVACY.md` line.
- B: no new egress from the device beyond A's lookup to our proxy (Google
  only for uncovered countries).
- C: see above.
- Alert text is public and contains no user data; the area name is a region,
  kept off the lock screen.

## Before building

- [x] Run `functions/scripts/check-alert-coverage.sh` while warnings are
      active (2026-10-22: UK, France, Brazil and Japan answer; see
      "Coverage").
- [x] Re-run for containment and validity (every returned alert contains
      its test city; start times look like issue times).
- [ ] UK polygon point counts: traced regions would mean the Met Office's
      own API for the UK (see "Location").
- [ ] Whether `startTime` is issue time or onset: compare with the Met
      Office page's "valid from" for the same warning.
- [ ] VicEmergency: the feed is public, carries the Bureau's severe weather
      warnings with polygons, and the Bureau's content in it may be
      republished under EMV's CC BY 3.0 AU notice.
- [ ] Catch an amber or red warning to confirm Met Office levels map to
      Severe and Extreme, and see the title wording at each level.
- [ ] Which event types to show (weather, flood, fire, coastal; not AMBER
      alerts, earthquakes or air quality).
- [ ] Billing → Reports by SKU after a few calls: which SKU alerts bill
      under, and whether they share the forecast's free calls.
- [ ] Maps Platform caching terms for alerts.
- [x] Opt-in: its own "Alerts" settings section, off by default (see
      "Settings").
- [ ] Background check cadence: 3 hours, adaptive or hourly (see "Cadence,
      freshness and cost").
- [ ] Whether alerts also open the morning and evening insight ("Amber
      wind warning until 9 AM.") and the spoken forecast.
