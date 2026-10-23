# Severe weather alerts

**Status, 2026-10-23.** Alerts shipped on Google's `publicAlerts:lookup`
(Settings → Alerts, cards on Today, the strip icon, notifications) and came
off it a day later (maintainer): Google's terms allow caching alerts for an
hour and may bar weather apps outright (Sources → Google). The app now
reads only the server ingester's files (Architecture B; "The published
files"), with no Google anywhere: a place no source covers says alerts
aren't available. The `weatherAlerts` proxy and the Today alerts promo are
gone, and alerts need no sign-in.

Proposal, 2026-10-22. An earlier attempt called an
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
   an adaptive background check for notifications: every 3 hours, hourly
   while the forecast looks severe or an alert is active (maintainer,
   2026-10-22; see "Cadence, freshness and cost"). The proxy stores nothing
   and, until the caching terms are checked, caches nothing either.
3. **Fill Google's gaps later with one generic CAP ingester** on a server
   cron, reading the WMO/Alert-Hub directory of 231 national feeds rather
   than integrating authorities one by one (see "One integration for many
   countries"). Each source is switched on after vetting its terms and area
   precision. Victoria needs VicEmergency, which isn't CAP; it can follow
   later (maintainer, 2026-10-22). The same cron can take over NWS,
   MeteoAlarm and Environment Canada if cost or scale matters, since its
   cost doesn't grow with users. Since 2026-10-23 it replaces Google
   instead (maintainer): built, with no Google in it (Architecture B).
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

  The same icon marks each alert card, the notification (small icon and
  accent), and the head of the conditions strip on Today, in the conditions
  widget and on the outfit card sent to Cast and Home Assistant (the highest
  level, yellow and up; on a card, of the alerts overlapping its own
  forecast window). Its content description names the level ("Severe
  weather alert").
- **Today**: one single-row card per alert at the top of the page: the
  severity icon, the title, the end time ("Until Sat 9 AM"; "From 6 PM"
  before it starts only once start times are known to be onset, see
  "Coverage") and an expand arrow. Tapping expands the full time range
  and issuer, the area, the issuer's description and instruction (web
  addresses in them tappable), the issue time, ClothesCast's disclaimer, a
  link to the issuer's page, the source's credit, and Dismiss. Every source
  gets both disclaimers (maintainer, 2026-10-23), kept to one line ("No
  guarantees, no liability.") until "Show details" opens ours in full (no
  guarantee alerts are accurate, complete or up to date, no liability for
  relying on them) and a link to the source's, named for its author
  ("MeteoAlarm disclaimer", which isn't always the issuer) from the index
  (`disclaimer`). Where a source's terms require its wording shown, it
  comes first, verbatim: MeteoAlarm's §5 requires every redistributor to
  publish "Time delays between this website and the www.meteoalarm.org
  website are possible. For the most up-to-date awareness information as
  published by the participating National Meteorological and Hydrological
  Services, please refer to www.meteoalarm.org." (read 2026-10-23).
  Cards are outlined and neutral so the icon carries the color (the yellow
  tint can't be read on a yellow card). More than three alerts collapse
  behind "2 more alerts".
- **The daily ClothesCast** ends with the title of each warning in force
  over its forecast window, yellow and up (the icon's rule), a sentence
  each: "… Wear a thick jacket. Amber wind warning." That covers Today's
  insight card (the current period's page, as the strip), the
  notification, the spoken briefing (titles in the voice's language) and
  the smart-display card and Smart Home text (maintainer, 2026-10-22: at
  the end). The title rides the prose to the online voice and the user's
  broker; the app adds no area name to it, though an issuer's own title
  (used where it doesn't grade by color) is sent as written.
- **Dismiss** hides that alert's card until the issuer changes anything the
  app shows of it (title, level, area, text, link or times); a reissued or
  new alert gets its own card (maintainer, 2026-10-22). The strip, widget,
  outfit card and any notification keep the level while the warning is in
  force. Dismissals live in the alerts store, so turning alerts off or a
  new place forgets them.
- **Titles**: "{level} {hazard} warning" ("Amber wind warning") where the
  issuer grades by color (MeteoAlarm members, including the UK: in practice,
  where the issuer's own title leads with a color word), with
  "Amber" for UK issuers and "Orange" for the rest of Europe; the issuer's
  own event name elsewhere ("High Wind Warning" from NWS, which has no
  colors). Maintainer's choice (2026-10-22) over the Met Office's verbatim
  "Amber warning of wind"; see "Met Office display rules". The issuer's own
  headline and text stay verbatim in the expanded card.
- **Notifications** (Severe and Extreme only), on their own channel at
  default importance (sound, no heads-up):
  - Only once the warning is in force: one that starts tomorrow notifies
    when it starts, not when it's issued (maintainer, 2026-10-22). Today's
    card shows it meanwhile. This rests on the alert's start time: where
    `startTime` is the issue time (the UK so far, see "Before building"),
    the notification still goes at issue until the onset is found.
  - Decided in one place, `WeatherAlertShade`, from the stored alerts, the
    settings and the permission, and settled after every check, settings
    change, app resume and alert start (maintainer, 2026-10-22). Nothing else
    posts or cancels an alert notification.
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
- **No sign-in needed** since alerts came off Google (2026-10-23); a place
  no source covers says alerts aren't available.

### Settings

Maintainer's design (2026-10-22):

- **A new "Alerts" section in Settings**, its own page like Forecasters.
- **Master switch "Weather alerts", off by default.** On Google it started
  a Google sign-in; since 2026-10-23 it needs none.
- **"Notify me for"**: which levels post a notification, defaulting to
  orange and red. Options, each shown with its severity icon: red only;
  orange and red; all levels (yellow too); none. Today shows every level
  whatever this says.
- **"Alert sound and lock screen"** opens Android's settings for the alerts
  channel.
- **The status line under the switch**, while alerts are on, says what the
  last check found: "No alerts for this location. Last checked 10:05" when
  nothing is in force, "Alerts aren't available for this location." where
  no source covers it (maintainer, 2026-10-23: the rule now, with Google,
  and once the ingester replaces it), or that a check failed or is on its
  way.
- **ClothesCast's disclaimer** under the switch, shown whether alerts are
  on or off: the same one line and "Show details" as on each card, without
  a source link.
- **No promo card on Today**: the "Get weather alerts" card shown to
  signed-in users with alerts off was removed (maintainer, 2026-10-23).
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
| `settings_alerts_notify_title` | Notify me for | |
| `settings_alerts_notify_red` | Red only | |
| `settings_alerts_notify_orange_red` | Orange and red | Amber and red |
| `settings_alerts_notify_all` | All levels | |
| `settings_alerts_notify_none` | None | |
| `settings_alerts_channel` | Alert sound and lock screen | Warning sound and lock screen |
| `settings_alerts_status_none` | No alerts for this location. Last checked %1$s | No warnings for this location. Last checked %1$s |
| `weather_alert_issued` | Issued %1$s | |
| `weather_alert_disclaimer_short` | No guarantees, no liability. | |
| `weather_alert_disclaimer_show`, `_hide` | Show details, Hide details | |
| `weather_alert_disclaimer` | Alerts come from official weather services. ClothesCast doesn't guarantee they're accurate, complete or up to date, and may miss some. Check the issuing service before you act, and in an emergency follow your local authorities. ClothesCast accepts no liability for any loss from relying on these alerts. | Warnings come from… these warnings. |
| `weather_alert_named_disclaimer` | %1$s disclaimer *(links the source's own: "MeteoAlarm disclaimer")* | |
| `weather_alert_source_disclaimer` | Source disclaimer *(the same, when the index gives no name)* | |

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
  in October. Titles are composed from the event type only where the
  issuer's own title leads with a color word (MeteoAlarm members, whose
  hazard types are reliable); elsewhere the card shows the issuer's own
  title.
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
- **The rest of Australia**: see "Australia" below.
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

### Australia

Checked 2026-10-23 from search excerpts of each page (the sandbox couldn't
open them); prices in Australian dollars include GST. There is no official
national feed: the Australian Warning System is a framework of levels and
icons, not data, and Google carries only state fire services. Showing the
Bureau's warnings in a distributed app, free or not, needs a licence. Its
default terms ([copyright](https://www.bom.gov.au/copyright)) allow
"personal use, or use within your organisation. You must not supply it to
any other person or use it for any commercial purpose", and its anonymous
FTP, RSS feeds and the WMO mirror all carry them; its undocumented API
needs express permission.

| Option | Covers | Cost | Terms | Links |
|---|---|---|---|---|
| Bureau Registered User | Every Bureau warning, CAP-AU, nationwide (bundle IDBZ0011) | A$1,335 once, A$1,282 a year, plus the warnings subscription (price *unverified*); fees can change on two months' notice | May supply the data only "as part of a Licensee Product"; warnings verbatim and as prominent as the original; the Bureau's attribution image; no "BoM" or "Bureau" in the product name | [Registered users](https://reg.bom.gov.au/reguser/), [charges](https://reg.bom.gov.au/other/charges.shtml), [Data Licence](https://www.bom.gov.au/sites/default/files/2026-04/bureau-of-meteorology-data-licence-agreement-2026.pdf), [brand policy](https://www.bom.gov.au/data-access/brand-trademark-display-policy.shtml); apply to webreg@bom.gov.au |
| Xweather | Bureau warnings "under BOM's third-party attribution license", plus the US, Canada and Europe | 15,000 accesses a month free (pauses at the limit); about US$300 a month for 1M (*unverified*) | Vendor terms; confirm a free consumer app may show them | [pricing](https://www.xweather.com/pricing), [alerts](https://www.xweather.com/docs/weather-api/endpoints/alerts), [credits](https://www.xweather.com/docs/weather-api/resources/credits) |
| WillyWeather API | A Bureau-based "Warnings" product, Australia only | First 5,000 requests free, then per request (about A$0.000033 each, *unverified*) | Rights to show them in an app *unverified* | [API](https://www.willyweather.com.au/info/api.html), [terms](https://www.willyweather.com.au/terms.html) |
| DataQuoll | 34 state and agency sources as GeoJSON or CAP-AU, licence on each record; Bureau content contradictory | 5,000 requests a month free; A$9, A$99 or A$299 a month | Each source's | [pricing](https://dataquoll.io/pricing), [compliance](https://dataquoll.io/compliance) |
| VicEmergency | Victoria, Bureau warnings included as incidents | Free | CC BY 3.0 AU: credit the State of Victoria, link the notice, show the last update time; ask EMV whether that covers Bureau-written text | [EMV data notice](https://www.emv.vic.gov.au/responsibilities/victorias-warning-system/emergency-data) |
| Emergency WA | WA's own (DFES) bushfire, cyclone, storm and flood warnings; Bureau ones *unverified* | Free | CC BY-ND 4.0 (no changes) | `api.emergency.wa.gov.au/v1/capau`, [licence](https://catalogue.data.wa.gov.au/dataset/dfes-emergencywa-warning-points-dfes-070) |
| TasALERT | Tasmania, Bureau severe weather and coastal warnings included | Free | Permission needed: info@alert.tas.gov.au | [about](https://alert.tas.gov.au/about-app) |
| NSW SES HazardWatch | NSW SES flood, storm and tsunami warnings; Bureau ones *unverified* | Free | By request, republished unaltered: warnings.capability@ses.nsw.gov.au | [HazardWatch](https://hazardwatch.gov.au/) |
| Queensland QFD | QFD's own storm, flood and cyclone warnings (not the Bureau's), every 30 minutes | Free | CC BY 4.0 | [dataset](https://www.data.qld.gov.au/dataset/storm-flood-and-cyclone-warnings-capau-xml) |

Fire only, and partly in Google already: NSW RFS (CC BY) and SA CFS (CC
BY-NC-ND). Nothing usable for the NT (SecureNT, undocumented) or the ACT
(licence unclear). DTN/Weatherzone, The Weather Company, EWN, AccuWeather
and OpenWeather list the Bureau as a source but sell by quote or with an
unverified licence.

The paths, cheapest first:

1. **A licensed vendor**, Xweather first: nationwide Bureau warnings with no
   Bureau contract of our own. If it answers one query for all of Australia
   (*unverified*), the server cron polls it within the free tier (8,640
   calls a month at 5 minutes) and no device location reaches it.
2. **State feeds**: Victoria and WA, Tasmania with permission. Bureau
   weather warnings stay missing in NSW, Queensland, SA, the ACT and the NT.
3. **The Registered User licence**: at least A$2,617 in the first year plus
   the subscription, pulled by the server cron.

With any of them, ask the Bureau in writing whether a free app may use the
anonymous-FTP CAP feed.

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

**Terms found so far** (2026-10-23; from search excerpts of each official
page unless the source's own feed says it; the national feeds in "Sources"
are covered there):

| Source | Terms | Verdict |
|---|---|---|
| DWD (Germany) | CC BY 4.0: "Source: Deutscher Wetterdienst" next to the warning, removed if the warning is changed; send a User-Agent | Fine |
| KMA (Korea) | KOGL Type 1: credit the source, commercial use and changes allowed; the CAP feed's own marking *unverified* | Probably fine |
| Roshydromet | Feed says "public domain"; the site requires a link to meteoinfo.ru | Probably fine; check sanctions exposure |
| SMN (Argentina) | Feed says CC BY 4.0; an older legal page reserves graphics rights | Fine with credit |
| MetService (NZ) | Code of Practice: pass on unmodified within 10 minutes of receipt, credit the alerting authority | Fine, at push speed |
| NEMA (NZ) | Public for "smartphone apps, websites or other alerting hubs"; no licence found | Intent favorable; ask |
| Environment Agency | Flood-monitoring API: Open Government Licence; the CAP Atom feed's own licence contradicts itself | API fine |
| CMA (China) | Only a "public domain" string in the feed; no terms found | Ask |
| SAWS (South Africa) | No feed terms; the SAWS Act reserves issuing warnings to SAWS, which also sells data | Ask |
| IMD / NDMA SACHET | No reuse terms; IMD's data forms bar third-party and commercial use | Ask |
| IFRC Alert Hub | "All rights reserved"; prior permission needed | Ask |
| Pelmorex NAAD (Canada) | User guide binds users to terms not found in full | Ask, or use ECCC |

Rules that recur: relay the warning text unaltered under the issuer's name
(several countries reserve issuing warnings to their weather service by
law), show the issue time and a link beside it, no logos or implied
endorsement, and relay within minutes (MeteoAlarm and MetService both cap
it at 10). A feed's `<rights>` tag isn't proof: DWD's says public domain
while its legal notice says CC BY 4.0.

**Checking it** (the script went with Google's alerts on 2026-10-23):
`functions/scripts/check-alert-coverage.sh <projectId>` asked Google for
alerts at about 30 test cities with the proxy's key and printed what came
back, from which agency, when it was valid, and each polygon's size, point
count and whether it contained the city. An empty answer meant either no
active alert or no coverage; it was run while a national warning map showed
something at a test point to tell the two apart.

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
- **Terms** (Maps Platform service terms §21, last modified 2026-10-15, read
  2026-10-23). §21.2.1 lets "Public Alert values" be cached for "one hour,
  after which Customer must delete the cached Google Maps Content"; the same
  hour applies to hourly forecast values and current conditions (daily
  forecast values: 24 hours). The app keeps alerts until the next successful
  check (3 hours or more) and the Google hourly forecast for 12 hours
  (`GoogleForecastCache`), both past that limit (`TODO.md`). §21.1: "Customers
  may not use Google Maps Content retrieved from Weather API to recreate a
  Google service or product (e.g. use data retrieved from Weather API in a
  weather app or weather model whose primary purpose is to provide weather
  information)". Whether an outfit app counts is the maintainer's call; if it
  does, it covers the Google forecast too. The general terms (§3.2.3(a)) also
  bar pre-fetching, storing or resharing outside those allowances, so a server
  sweep is out and a server cache must stay within the hour. Attribution
  required.
- **Bulk**: none; see "Why not a worldwide Google sweep".

### Free government feeds

| Source | Coverage | Access | Cost and limits | Freshness | Notes |
|---|---|---|---|---|---|
| NWS `api.weather.gov/alerts` | US and territories | Pull: all active alerts in one call, or `?point=lat,lon`; GeoJSON | Free, no key; User-Agent with contact required (403 without); poll no more than every 30 seconds | Cache headers follow each alert's life cycle | Some alerts reference forecast zones instead of a polygon (zone geometry from `/zones`). Public domain; don't imply endorsement or present altered text as official. |
| NWS NWWS-OI | US | Push over XMPP | Credentials from NWS; sign-up can take 30+ days; cost *unverified* | Seconds | Raw text products; needs an always-on client. |
| Met Office NSWWS public API | UK | Pull: Atom feed linking GeoJSON (MultiPolygon) warnings for the next 7 days | Free within 20,000 calls a day, 200 per second burst; registration *unverified* | They recommend polling the Atom feed once a minute | Strict style guide, see below. Onward redistribution *unverified*; MeteoAlarm carries the same warnings under clearer terms. |
| Met Office RSS | UK, per region | Pull: RSS summaries | Free; website terms | As issued | The API suits apps better. |
| MeteoAlarm | ~38 European countries (EUMETNET) | Pull: per-country Atom and JSON feeds, open, no key, WebSub; the EDR API is for members and redistributors on request | Free; "terms equivalent to CC BY 4.0" plus its T&C §5: keep the unmodified original with any changed version, show the issue time, link to meteoalarm.org, publish a disclaimer, redistribute within 5 minutes on average and 10 at most | As issued by each member | Credit "EUMETNET – MeteoAlarm", or the national service for one country's warnings. Warnings reference EMMA region codes, so geometry may need a separate lookup (the UK's carry polygons). Color levels yellow, orange, red. |
| MET Norway MetAlerts 2.0 | Norway | Pull: `/current`, lat/lon queries; CAP, GeoJSON, RSS | Free; User-Agent required; licence *unverified* | As issued | GeoJSON was beta at launch. Also in MeteoAlarm. |
| ECCC (Canada) | Canada | Pull CAP files from Datamart, or push over AMQPS (`anonymous` at `dd.weather.gc.ca:5671`) | Free; End-use Licence v2.1.1 allows commercial use if alerts aren't altered; contact them above 86,400 requests a day, use AMQPS for real time, send a User-Agent | Push: seconds | "Data Source: Environment and Climate Change Canada"; no logos. Only free push feed found that needs no sign-up besides WIS2. |
| JMA | Japan (a Google gap) | Pull: XML feeds (high-frequency one updated every minute) and per-prefecture JSON | Free; Public Data License 1.0 (CC BY 4.0-compatible); an IP pulling 10 GB a day is blocked, don't re-fetch files | As issued | "Source: Japan Meteorological Agency website". Japanese text. One client library reports the Atom feeds drop older in-force warnings. |
| WMO WIS2 Global Broker | Members publishing CAP through WIS2 (partial, growing) | Push over MQTT (`origin/a/wis2/#`, public read-only login) | Free; "core" data unrestricted | Seconds | Coverage depends on each country adopting WIS2; check which publish alerts. |
| WMO Alert Hub / Alert-Hub.org | ~500 alerting authorities' CAP feeds | Registry of feed URLs; aggregated display (SWIC); ESRI republishes it as a GIS feature service | Free. SWIC: "free for re-use by media or other websites", crediting the issuing service; a mirror doesn't relicense, so each issuer's terms govern | Per feed | Google Public Alerts and AccuWeather use a subset of the same list. A feed directory more than an API. SWIC's `/v2/cap-alerts/` RSS mirrors are being retired (most 404 since 2026-10-08). |
| FEMA IPAWS public feed | US | CAP, pull only; alerts stay 30 minutes | Free; an agreement (MOA) with FEMA may still be needed (`ipaws@fema.dhs.gov`) | | Mostly duplicates NWS for weather. |
| BOM | Australia | Pull: CAP-AU warnings on anonymous FTP | Free for personal or in-organization use only; publishing needs a paid Registered User agreement | As issued | See "Australia". |
| VicEmergency | Victoria | Pull: one GeoJSON of warnings and incidents | Free, CC BY 3.0 AU with attribution and a last-updated time | About a minute | The Melbourne fix; see "Filling Google's gaps". |

### Paid or account-based (rejected: another vendor and key)

| Source | Coverage | Cost found | Notes |
|---|---|---|---|
| OpenWeather One Call 3.0 | Global, national agencies | 1,000 calls a day free, then per call | Alerts included in the response. |
| OpenWeather Global Weather Alerts | Global | Monthly subscription, arranged by email | Push; up to 30 minutes' delay. |
| Xweather | US, Canada, Europe incl. Met Office, BOM | 15,000 accesses a month free; paid around $300 a month for 1M (third-party figures) | The only vendor found that states a Bureau licence; see "Australia". |
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

### A. Per-user lookup through the proxy (phase 1; removed 2026-10-23)

- **Proxy**: a new `weatherAlerts` function next to `weather`, sharing its
  `GOOGLE_WEATHER_API_KEY` secret. Same gates: POST form body (the
  coordinate stays out of request logs), App Check, Google sign-in (403
  `sign_in_required` for anonymous tokens). Forwards only latitude,
  longitude, a validated language code and a bounded page size. Its own daily
  cap per account (`weatherAlertsQuota/g_<hex>`, 120 a day) so forecast
  walks can't starve it. Never logs or stores the coordinate.
- **Server cache**: none at first; the Maps Platform caching terms for
  alerts are unchecked and `SPEC.md` requires checking them before any
  server-side cache (`TODO.md` → Decisions needing review). Once they allow
  it: in memory per instance, keyed by the 2-decimal coordinate the app
  already sends, 15-minute TTL, never written anywhere. It helps exactly when
  traffic is high (warm instances, many users per cell) and costs nothing
  when it's low. A coarser key would save more calls but miss small polygons
  (US storm-based warnings), so it isn't used.
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

- Cloud Scheduler triggers a function every 5 minutes that pulls the
  vetted feeds (MeteoAlarm, which carries the Met Office, first; then NWS,
  Environment Canada and the gap-fillers), normalizes them from CAP, and
  publishes them to a public Cloud Storage bucket as one file per 1° tile
  plus an index (see "The published files"). The phone downloads its own
  tile and checks containment itself, so no coordinate leaves it. It
  replaces Google rather than filling its gaps (maintainer, 2026-10-23):
  no Google in it, and a place no source covers reads as unavailable.
- **Cost**: effectively nil. Cloud Scheduler includes 3 free jobs per
  billing account, then $0.10 a job a month; invocations, compute and storage
  sit in free tiers. It does not grow with users.
- **Hosting on GCP** (us-central1 list prices, read 2026-10-23). The ingest
  job (1 vCPU, 512 MiB, 10 to 20 seconds a run, feeds fetched in parallel, a pool per host,
  with conditional GETs) as a Cloud Run function: $0 at 15 or 5 minutes
  ($2 to $4 once the free tier, shared with the weather proxy, is used up),
  $6 to $17 at 1 minute. State as one gzipped Cloud Storage object written
  with a generation precondition, so overlapping runs can't clobber it:
  cents a month. Per-cell files in a public bucket with `Cache-Control`
  60 to 300 seconds (the default is an hour). What a device poll costs to
  serve, per month at 5-minute ingest:

  | Devices | Cloud Storage direct | Firebase Hosting | Cloud CDN + load balancer | FCM push only |
  |---|---|---|---|---|
  | 1,000 | ~$0.30 | $0 | ~$19 | ~$0–0.50 |
  | 10,000 | ~$2.90 | $0–1.60 | ~$24–25 | ~$0.30–1 |
  | 100,000 | $29–43 | $5–31 | ~$76–90 | ~$1–3 |

  Not a fit: Firestore (reading every alert each run is about $7 a month at
  5 minutes, and polygons hit its 1 MiB document limit), Cloud SQL (about
  $9.40 a month for a shared core with no SLA), Memorystore (from about
  $36). Firebase Hosting clears its CDN cache on every deploy. Pub/Sub would
  be free but isn't needed.
- **Feed rules that shape it**: a User-Agent with a contact on every fetch
  (NWS, ECCC, DWD); NWS no more than every 30 seconds; ECCC by AMQPS rather
  than polling; JMA's per-minute feed without re-fetching files; MeteoAlarm by
  WebSub or a poll every 5 minutes at most, which keeps its relay delay near
  2.5 minutes on average and under its 10-minute cap. Keep each warning's
  original text, issuer, issue time and link.
- **Freshness**: no better than A on its own, because the device still polls.
- **Effort**: one parser per feed, region-code geometry for MeteoAlarm and
  NWS zones, and per-feed terms to honor.

#### The published files

Built 2026-10-23 as `alertsIngest` (`functions/src/alerts/`); the bucket is
`<projectId>-alerts`, made once with `make alerts-bucket PROJECT=…`. Files
are gzipped JSON, readable by name by anyone (the bucket can't be listed),
and every path carries the format version:

- **`v1/index.json`**, rewritten every run, `Cache-Control: public,
  max-age=60`: `generatedAt`, `tileDegrees` (1), each source's `id`,
  `countries` (ISO 3166-1 alpha-2), `disclaimer` (`name` of its author,
  `url` of the source's own, linked after ClothesCast's, and `text` where
  the source's terms require its wording shown), `attribution`
  (name and link to show
  beside its alerts), `lastSuccessAt` (when the feed and every document it
  listed last loaded), `error` (why the latest check was incomplete, or
  null) and `servingLastSuccess` (whether its alerts are still exactly the
  check at `lastSuccessAt`'s, as when only the feed has failed since), and `tiles`, mapping each tile that has alerts to its file's hash.
  A tile not listed has none. The app trusts the server for a country only
  when a source lists it, and treats a source with an `error`, or an index
  whose `generatedAt` is old, as unknown, not quiet. The exception is a
  source whose feed alone is failing (`servingLastSuccess`) and whose
  `lastSuccessAt` is under 30 minutes old: its last alerts, which the
  ingester keeps serving, are shown. After an incomplete document check an
  alert may be missing, so that one reads as unknown at once.
- **`v1/tiles/<lat>/<lon>/<hash>.json`**, keyed by the tile's south-west
  corner (`51/-1` holds 51–52°N, 1°W–0°) and named by the first 16 hex
  digits of its contents' SHA-256, so a file never changes once written
  (`Cache-Control: public, max-age=86400, immutable`): `tile` and
  `alerts`. Each alert carries
  `id` (`<source>:<CAP identifier>`), `source`, `sent` (issue time), `onset`,
  `expires`, `severity` (CAP's, or MeteoAlarm's color grade where it sends
  one), `certainty`, `urgency`, `eventType` (a hazard keyword such as
  `WIND`, the vocabulary `AlertHazards` reads), `issuer`, `polygons` (the
  area is their union, as `AlertArea` reads it, since CAP shapes may
  overlap; each is closed `[lat, lon]` rings, the outer boundary then any
  holes, even-odd) and `texts`, one per language the issuer sent: `title`
  (CAP `event`), `headline`, `description`, `instruction`, `areaName` and
  `web`, all verbatim. An alert sits in every tile its bounding box touches,
  widened by the app's 1 km containment slack so a phone just outside its
  edge, across a tile line, still finds it; one wider than 1,000 tiles is
  left out and logged.
- A run writes only tile files that don't exist yet, then the index that
  names them; a file is deleted an hour after the last index that named it,
  so a phone holding any index from the last hour finds every tile it names.

What it serves: MeteoAlarm's feeds for 34 countries, placed from their
polygons or from MeteoAlarm's region shapes where they name regions (all
of MeteoAlarm but Germany, Croatia, Bosnia and Herzegovina, Andorra and
Israel); the US with its territories from NWS
(`api.weather.gov/alerts/active`, actual alerts and updates); and Germany
from DWD. A test run on 2026-10-23 published 121 MeteoAlarm alerts in 244
tiles in about 6 seconds; the index was 3.4 KB gzipped, the median tile
4 KB, the largest (Switzerland's municipal polygons) 79 KB. A run that only
rereads the feeds takes about 2 seconds.

**NWS.** Storm-based warnings carry a polygon; most other alerts name
forecast or fire zones instead. Their shapes come from
`api.weather.gov/zones/<type>/<id>`, simplified to within 0.002° (about
200 m, inside the app's 1 km containment slack), kept in the bucket under
`zones/v1/` and refetched after a month; a stale copy stands in when a
refetch fails, and a zone that can't be had marks the check incomplete.
Only api.weather.gov zone addresses are followed. Marine zones are left
out, and an alert naming only them (most small-craft and gale alerts) with
them. An alert that another in the response updates is left out as
superseded, and an alert's end is NWS's `ends` (the hazard's end) where it
gives one, not the message's `expires`. NWS wraps its text at a fixed
width; the ingester rejoins those lines so it flows on a phone, keeping
paragraphs and list lines, and changes no words. A test run on 2026-10-23, with
NWS added: 300 alerts (180 from NWS, 312 marine-only left out) in 885
tiles in about 18 seconds, 421 zone shapes fetched once (2.5 MB); a rerun
took about 4 seconds. The index was 12 KB gzipped, the median tile 7 KB,
the 90th percentile 34 KB and the largest 190 KB, where many zoned alerts
overlap; the run's state was 1.4 MB gzipped. The feed itself is about
2 MB a run.

**DWD.** Germany comes straight from DWD's open data rather than
MeteoAlarm, whose German feed names regions: the commune-level zip of
every warning in force (`COMMUNEUNION_DWD_STAT`, the `MUL` file with all
eight languages), about 2 MB a run. Each warning carries its drawn
polygon, with islands and lakes left out as holes (`EXCLUDE_POLYGON`
geocodes), simplified like zone shapes. A file seen before keeps its
alert, and one gone from the zip is over. DWD grades a step below CAP's
usual meaning (its yellow, "level 1 of 4", is `Minor`), so severities are
read up a step; the hazard comes from its `GROUP` event code. The commune
list (often thousands) isn't shown as the area name past five. Coastal-
waters warnings from its marine service carry no polygon and are left
out. Credit "Deutscher Wetterdienst, © GeoBasis-DE / BKG": CC BY 4.0 with
the source named, and the areas are drawn on the federal mapping agency's
data. A test run on 2026-10-23: 46 warnings (7 coastal left out) in 65
tiles in about 5 seconds, 2 on a rerun; the median tile 28 KB gzipped
(about 20 overlapping wind warnings, each in eight languages), the
largest 55 KB.

**MeteoAlarm regions.** Most MeteoAlarm countries name regions instead of
drawing areas: by `EMMA_ID`, or by NUTS or FIPS code (France, Ireland,
Belgium, Bulgaria, Hungary, North Macedonia, Romania). MeteoAlarm's
Redistribution Hub page (read through its CMS,
`cms-visualization.meteoalarm.org/api/v1/content-pages/redistribution-hub`)
links the current files: the region shapes on GitLab
(`MeteoAlarm_Geocodes_<date>.json`: 33 MB, 2,006 regions in 30 countries)
and an aliases table on Google Drive mapping NUTS and FIPS codes to them.
The shapes are simplified to within 0.002° (143,000 points, a median of 34
a region) and kept in the bucket by geocode type and code
(`regions/v1/<type>/<code>.json`, 2,773 in all), refreshed after a month.
The files are downloaded at most once a day, so a region missing from them
marks the check incomplete without a download every run; the download
peaks near 360 MB of memory, hence the function's 1 GiB. Left out
(`TODO.md`): Croatia names county regions (HR011–HR031) the shapes don't
have; Bosnia and Herzegovina's feed is one entity's service only; Andorra
and Israel have no shapes. A live run of every source on 2026-10-23: 596
alerts in 1,256 tiles in about 29 seconds (with the region download), 7
on a rerun; the median tile 10 KB gzipped, the 90th percentile 34 KB, the
largest 190 KB (NWS); the index 18 KB gzipped and the state 1.7 MB.

Only public data goes in the bucket, the run's own state
(`ingest-state/v1.json`: what each feed listed, so unchanged documents
aren't fetched again, and when each tile file was last named) included. A feed that fails keeps its last alerts
until they expire, and the index says so; so does a document that won't
load, which is retried next run. Fetching stops 180 seconds into a run:
a source still fetching then fails that run the same way, so one slow host
can't hold back the others' updates. The index is written with a generation
precondition: a run that overlaps a newer one is refused before it
publishes or deletes anything, and the newer view stands.

### C. Push (phase 3)

- The cron (or an always-on listener on WIS2 MQTT, ECCC AMQP or NWWS-OI)
  detects new alerts and sends FCM messages to topics named for coarse cells
  the alert polygon covers (for example geohash-4, about 40 × 20 km).
  Devices subscribe to their cell's topic.
- **Freshness**: minutes (cron) or seconds (listener), independent of polling
  and Doze.
- **Cost**: FCM is free. A listener needs an always-on host: a free-tier
  e2-micro VM costs about $3.65 a month for its public IPv4 address (we patch
  it, no SLA); a Cloud Run service pinned to one instance with CPU always on
  costs about $45. A request-billed Cloud Run service can't listen, since
  its CPU is throttled between requests. Keep the cron as the backstop, so
  a dead listener means cron speed, not silence.
- **Listener feeds**: WIS2 Global Brokers over MQTT, anonymous login
  `everyone`/`everyone` (Météo-France `globalbroker.meteo.fr:8883`, NOAA
  `globalbroker.globaldata.nws.noaa.gov:8883`), subscribed to
  `origin/a/wis2/hk-hko-swic/#` for the Hong Kong Observatory's dataset of
  SWIC's CAP alerts; ECCC over AMQPS; NWWS-OI over XMPP, with credentials
  that take 30 days or more and outages up to three times a month. No push
  was found for DWD, JMA or the Bureau.
- **FCM limits** (Firebase docs, read 2026-10-23): payloads up to 4,096
  bytes, but 2,048 for a message sent to a topic (larger ones fail with
  `INVALID_ARGUMENT`), too small for a CAP document, so a message carries
  the ID, level and times and the device fetches the rest; one send targets
  at most 5 topics; at most 2,000 topics per app instance; 600,000 messages a minute
  per project; topic fan-out is "optimized for throughput rather than
  latency", with no delivery SLA. High priority reaches a device in Doze,
  but FCM may demote an app's high-priority messages when they don't lead
  to a visible notification, which coarse cells invite (an alert reaching
  phones outside its polygon). Phones without Google Play services get
  nothing, and a phone that joins a cell misses alerts sent before it
  joined, so push still needs a catch-up pull.
- **Privacy**: the topic subscription tells Firebase the device's coarse
  cell for as long as it's subscribed (with the date it was added; tokens
  lapse after 270 days offline). Firebase sees the device's IP address on
  every subscribe, and each cell change is a new record. We can read a
  token's subscriptions only if the app uploads that token, which it
  needn't; still, this is a durable record
  where today there is only a per-request one; it needs a `PRIVACY.md`
  change and a decision. Adds Firebase Cloud Messaging to the app.

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
      Office page's "valid from" for the same warning. The Birmingham run
      (2026-10-22) gave 09:03 on the 22nd for a warning the Met Office had for
      the 23rd, so issue time. The coverage script now prints every field and
      the description's start, to find the onset elsewhere; notifications wait
      for the start time, so the UK needs it to stop notifying at issue.
- [ ] VicEmergency: the feed is public, carries the Bureau's severe weather
      warnings with polygons, and the Bureau's content in it may be
      republished under EMV's CC BY 3.0 AU notice.
- [ ] Catch an amber or red warning to confirm Met Office levels map to
      Severe and Extreme, and see the title wording at each level.
- [x] Which event types to show (weather, flood, fire, coastal; not AMBER
      alerts, earthquakes or air quality): `AlertHazards.isExcluded`, logged
      in `TODO.md` → Decisions needing review.
- [ ] Billing → Reports by SKU after a few calls: which SKU alerts bill
      under, and whether they share the forecast's free calls.
- [x] Maps Platform caching terms for alerts: one hour (§21.2.1), shorter
      than the app keeps them (see Sources → Google; `TODO.md`).
- [x] Opt-in: its own "Alerts" settings section, off by default (see
      "Settings").
- [x] Background check cadence: adaptive (maintainer, 2026-10-22; see
      "Cadence, freshness and cost").
- [ ] Whether alerts also open the morning and evening insight ("Amber
      wind warning until 9 AM.") and the spoken forecast.
