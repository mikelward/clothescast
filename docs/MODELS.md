# Forecast accuracy & multi-model ideas

A scratchpad of ideas for getting more out of weather data than a single
forecast at 7am. Not committed to. Most of these need a small amount of
groundwork (logging predictions, comparing to outcomes) before the
user-facing features become tractable.

## Background — what Open-Meteo gives us

Open-Meteo isn't a single forecasting model: their endpoint aggregates the
output of multiple national weather services and lets the caller pick which
one(s) to use. The same `/v1/forecast` URL accepts `&models=…` to select
from:

| Model | Origin | Notes |
|---|---|---|
| `ecmwf_ifs025` | ECMWF | Generally best long-range global model; 0.25° open feed, reaches ~day 15 (replaced the older 0.4° `ecmwf_ifs04` in Feb 2024) |
| `ecmwf_aifs025_single` | ECMWF | AI model (graph neural net, "AIFS Single"); skillful to ~day 15; exposes a reduced hourly field set, so it may not draw a line on every diagnostic chart; carries the second-week charts |
| `icon_seamless` / `icon_eu` | DWD (Germany) | Strong European coverage; short-range, stops at ~day 7 |
| `gfs_seamless` | NOAA (US) | Broad American coverage, reaches ~day 16; weakest of the set on rainfall (last on MAE and ETS against ERA5, POD 0.49 on wet days), so it is no longer in any default set — selectable, but never a default |
| `meteofrance_seamless` | Météo-France | Strong for France/W. Europe (ARPEGE); short-range |
| `gem_seamless` | ECCC (Canada) | Strong for North America; reaches ~day 10 |
| `ukmo_seamless` | UK Met Office | Strong for the British Isles; short-range |
| `jma_seamless` | JMA (Japan) | Strong for Asia-Pacific; short-range |
| `bom_access_global` | BOM (Australia) | Strong for Australasia (open-data delivery currently suspended) |

Horizon matters for the two-week forecast: ICON and the regional locals
(UKMO, ARPEGE, JMA) fade after ~day 7, so the second-week ("Following 7
days") charts lean on the longer-horizon models — ECMWF IFS 0.25° and AIFS
reach into days 8-14, with GEM reinforcing days 8-10. (GFS reaches ~day 16
too, but is no longer a default anywhere; see its row above.) The default
model set per region (see `ForecastModelDefaults`) is built to keep at least
two models reporting across the whole fortnight.

Default `best_match` picks the model believed to be most accurate for the
requested coordinates. We currently don't pass `models=` at all, so we get
`best_match`.

The interesting fact: when the user requests several models in one call,
Open-Meteo returns each model's output side-by-side. **When models
disagree, the forecast is least trustworthy** — that's actionable
information we can surface, and it's free.

## Ideas

### 1. Multi-model "forecast confidence" badge ✅ shipped

Fetch 3-5 models in the daily forecast call. Compute disagreement (e.g.
range of `temperature_2m_max` across models, or stdev of
`precipitation_probability_max`) and surface a small badge:

- "High confidence" — models agree within 1°C / 10pp precip probability
- "Medium" — models within 2-3°C
- "Low — forecasts disagree" — wider spread

LLM prompt rule: if confidence is low, append "Forecasts disagree today —
check again before heading out" to the daily prose. Otherwise stay quiet.

Cost: free (one Open-Meteo call, a bit more JSON).
Complexity: small (parse extra arrays + a confidence helper).

**As shipped:** three parallel calls to ECMWF (`ecmwf_ifs04`), GFS
(`gfs_seamless`), and DWD ICON (`icon_seamless`) — each requesting only
`apparent_temperature_max` and `precipitation_probability_max`. Best-effort:
any failure falls through to a null badge. Thresholds in
`MultiModelConfidenceFetcher` are first-pass guesses (≤1.5°C/15pp = HIGH,
≤3°C/30pp = MEDIUM, else LOW); refine with real data. The "feed into
LLM prompt" half is still TODO.

### 2. End-of-day accuracy survey

At a configurable evening time (e.g. 18:00), post a notification:

> "How was today's forecast? 🌤️ Spot on / Mostly right / Off"

Tapping records the answer + the morning's stored prediction(s) into Room
or DataStore. Over weeks the user builds a personal "this app is right N%
of the time" stat surfaced on Today.

Stretch: ask which *aspect* was wrong (temp / rain timing / wind / "felt
colder than predicted"). That data is most valuable for tuning the
clothes rules — if a user's "feels-like" model is consistently off in one
direction, the thresholds can be auto-shifted.

Cost: small. Just storage + UI for the prompt.
Complexity: medium — schedule the evening notification, persist
predictions long enough to compare, handle missed days.

### 3. Background multi-provider fetch

Once a day, also fetch from one or two alternative providers (WeatherAPI,
MET Norway, Tomorrow.io) and stash all forecasts in Room. At end-of-day,
compare each provider's morning prediction to actual observations
(Open-Meteo also exposes historical/observed data via `/v1/archive`).

Result: a personal leaderboard of which provider is most accurate for
*this* user's location. Could feed into picking the default model in idea
1.

Cost: tiny on the API side; some providers need keys (so this is BYOK
again, opt-in).
Complexity: medium-high — schema for storing predictions vs observations,
provider-agnostic mapping layer (we already have one for Open-Meteo —
generalising it is real work), comparison logic.

### 4. User-initiated incorrect-forecast reporting

Today screen has a "Report incorrect forecast" button. Tapping captures
the current insight + the underlying forecast values + the user's
free-text reason ("rained when it said 0%", "much colder than predicted")
locally. No backend.

Pure logging at first; later you could:
- Surface a "your most common complaints" stat ("you flagged temp as too
  high 14 times this month").
- Auto-shift clothes thresholds based on flagged patterns.

Cost: minimal.
Complexity: small — a button, a Room table, optional UI to review past
reports.

## Open questions

- **Privacy.** Idea 3 sends location to extra providers. Each one's
  privacy policy is yours to read; the BYOK pattern at least means *we*
  have no backend logging it.
- **Storage growth.** Predictions + observations + survey answers grow
  over time. A weekly aggregate would keep the schema bounded.
- **What counts as "accurate"?** For temperature, abs-diff is fine. For
  precipitation, more nuanced — predicting 30% chance of rain isn't
  "wrong" if it doesn't rain. We'd want Brier score or similar.

## Sequencing suggestion

If we do any of this, the natural order is:

1. **Idea 1** (confidence badge) is the cheapest and uses data we already
   fetch with one extra parameter. Could ship in a half-day.
2. **Idea 4** (user-flagged incorrect forecasts) is a small UX add and
   builds the storage we'd need for ideas 2 and 3.
3. **Idea 2** (end-of-day survey) builds on 4's storage shape.
4. **Idea 3** (multi-provider comparison) is the most work and only
   pays off after 1-2 weeks of data — last on the list.
