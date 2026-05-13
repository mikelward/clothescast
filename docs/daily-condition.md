# `DailyForecast.condition`: what to do

Brief design discussion. Not a decision yet — context + options + edge cases.

## What it is

`DailyForecast.condition: WeatherCondition` is the day-level summary bucket
(`CLEAR`, `RAIN`, `THUNDERSTORM`, …). Originally lifted straight from
Open-Meteo's daily `weather_code` field for `best_match`.

After the consensus blend landed (#388 → #396), the *hourly* condition is
already aggregated cross-model (modal with severity tiebreak). The
day-level `condition` field is the last bit that wasn't touched.

## Audit: where it's actually used

Smaller surface than I expected:

1. **`GenerateDailyInsight` rewrites it** during the daytime/tonight slice
   (`slicedForToday`, `slicedForTonight`). The post-slice value is *the
   condition of the highest-precip hour in the window*, falling back to the
   day-level value only if the wettest hour has `UNKNOWN`. So most
   downstream readers see a value that's already derived from hourly data
   — which since #396 is consensus-blended.

2. **`RenderInsightSummary.perModelConditionAt`** (line 241): reads
   `today.hourly[time].condition` first, then falls back to
   `today.condition` if the matched hour's condition isn't precipitating.
   So `today.condition` only matters when **the precip-peak hour's own
   condition is `CLEAR`/`UNKNOWN`/`PARTLY_CLOUDY`** but the day-level
   field *is* a precipitation bucket.

3. **`ClothesRule.appliesTo`** (line 24): some rules match on
   `forecast.condition` (e.g. the umbrella rule's
   `WeatherCondition.matches`). This is real and not derived from hourly
   — the day-level field directly drives rule firing.

4. **Cache round-trip** via `InsightCache` (line 440 of cache): just
   serialises whatever it was given. Doesn't reinterpret.

So the *practical* impact of "fix" `DailyForecast.condition` is:

- (#1) Wettest-hour rewrite already largely solves the chart-vs-summary
  mismatch the user originally flagged — the rewrite uses the
  consensus-blended hourly conditions.
- (#2) The fallback path matters on dry-ish days where the peak hour is
  `CLEAR` but the daily field claims `RAIN` (or vice versa).
- (#3) Clothes-rule firing on the day-level field is where the day-level
  condition actually *changes behaviour*. Worth fixing properly.

## Options

### 1. Leave it; rely on the existing hourly-derived rewrites

The `slicedForToday` / `slicedForTonight` rewrites already paper over
most of the problem. The remaining gap is the pre-slice value
(`OpenMeteoMapper` straight from best_match) feeding the
`ClothesRule.appliesTo(forecast)` path and the fallback in
`perModelConditionAt`.

*Pro:* zero change.
*Con:* clothes rules that key off `forecast.condition` still see
best_match's lone vote.

### 2. Rewrite once at `OpenMeteoClient.fetchForecast` — most-severe blended hourly

After `blendConsensusHourly`, scan the blended hours and pick the
most-severe condition (`max(severityRank)`) as the day's `condition`.
Simple, matches Open-Meteo's own daily-summary intuition ("the day's
worst weather event").

*Pro:* dovetails with the consensus blend; lifts the same numeric
recompute (`withAggregatesFrom`) to the condition field.
*Con:* on a clear day with one hour of light drizzle, the day reads
"Drizzly". Maybe-actionable / maybe-noisy.

### 3. Modal aggregation across the day's hours, severity tiebreak

Same heuristic the per-hour aggregation uses in #396, just lifted up a
level: mode of the day's 24 blended hourly conditions, severity break
on ties.

*Pro:* consistent with the per-hour aggregation.
*Con:* a half-rainy / half-clear day reads as `CLOUDY` if `CLOUDY`
hours dominate. Might miss the actionable rain event.

### 4. Per-model daily-code mode

Add `weather_code` to the multi-model fetcher's `daily` block, get one
condition per model per day, modal-aggregate across models (severity
tiebreak).

*Pro:* cleanest conceptually — each model already produced a daily
summary, we just consensus across them. No "summarise 24 hours into 1
bucket" arbitrariness.
*Con:* extra plumbing (multi-model `daily` parsing, cache field, tests).
Adds ~80 lines.

### 5. Severity-floor hybrid

"If ≥`N` hours in the window hit precipitation (or worse), call the
day precipitating. Else fall back to mode." `N` tunable. Bypasses the
"single-hour drizzle pollutes the day" problem of (2) without losing
real rain events like (3).

*Pro:* tuned to the actual product question ("should the day's prose /
clothes rules treat this as a rainy day?").
*Con:* hand-tuned threshold. Bikesheddable.

### 6. Hybrid: change consumers, not the field

Leave `DailyForecast.condition` as best_match's day-level value (or
whatever upstream gave us), and *change the clothes-rule + insight
consumers* to read from `today.hourly` directly with their own
aggregation per consumer. The day-level field becomes "what does Open-
Meteo think the day is, full stop", and each consumer derives what it
needs.

*Pro:* most accurate per-use-case (umbrella rule cares about "any rain
window in the daytime"; insight prose cares about "biggest weather
event").
*Con:* spreads the aggregation logic. Repeats. More to test.

## Edge cases each option has to answer

Same edge cases, different answers per option:

- **One hour of drizzle in an otherwise clear day** — (1) ignores it
  (rewrite picks the wettest hour, which is the drizzle, but the prose
  may already mention "Light rain at 13:00" via `PrecipClause`). (2)
  calls the day `DRIZZLE`. (3) calls the day `CLEAR` (mode). (4)
  defers to per-model daily mode. (5) calls it `CLEAR` if 1 hour
  doesn't clear the threshold.

- **Morning fog burns off to clear afternoon** — (1) picks the wettest
  hour's condition; fog has 0% precip → may fall back to day-level
  `FOG`. (2) → `FOG` (most severe of `FOG` and `CLEAR`). (3) →
  whichever has more hours. (4) → per-model daily.

- **Mixed snow-then-rain day** — (1) picks the wettest hour's
  condition; usually `RAIN`. (2) → `SNOW` (more severe). (3) → mode.
  (4) → per-model daily aggregator.

- **Thunderstorm afternoon on a clear morning** — (1) wettest hour is
  the thunderstorm → `THUNDERSTORM`. (2) → `THUNDERSTORM` (severity).
  (3) → `CLEAR` if more clear hours, then severity break → could go
  either way. (4) per-model daily.

- **Models disagree on the day's character itself** — (1) doesn't care
  (uses the already-consensus hourly). (2) inherits hourly consensus.
  (3) inherits hourly consensus. (4) does its own cross-model
  aggregation at the daily level — strictly different signal.

## My recommendation

**(2) most-severe blended hourly,** plus a small follow-up to
double-check the `ClothesRule.appliesTo` consumers actually do what
the user expects on edge cases (esp. the "1 hour drizzle on a clear
day" pattern). Reasons:

- Already-blended hourly is the right input; #396 did the work.
- "Worst weather event of the day" matches what users expect from a
  day-summary icon (matches Open-Meteo's own approach too).
- Doesn't require new fetch plumbing like (4); doesn't pick an
  arbitrary threshold like (5); doesn't spread aggregation across
  consumers like (6).
- The "one hour of drizzle" edge case is mostly mitigated by the fact
  that the *insight prose* already carries a `PrecipClause` mentioning
  the specific peak hour, so the day-level condition reading
  "Drizzly" alongside "Light rain at 13:00" reads honestly rather
  than redundantly.

(1) is also defensible — the existing hourly-derived rewrite handles
most cases. The remaining gap (clothes-rule firing on the un-rewritten
day-level field) is small but real on divergent days, which is the
exact scenario the user flagged. (2) closes that gap with minimal
extra code.

(4) is the "purest" option, and the right one to pick if we later add
more daily-level cross-model fields (or if (2) feels noisy in practice
and we want to delegate to each model's own daily aggregator).

## Open question worth your call

How important is **clothes-rule correctness on divergent days vs.
prose readability on quiet days**? If the former wins, lean (2)/(4)/
(5). If the latter, (1)/(3) suffice.
