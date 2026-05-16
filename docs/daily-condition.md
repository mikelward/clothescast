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

3. **`ClothesRule.appliesTo`** (line 24): ~~some rules match on
   `forecast.condition`~~. **Misread on my part — Codex caught it.**
   `ClothesRule.condition` is the *rule's own predicate field* (e.g.
   "feels-like below 5 °C"), not `forecast.condition`. The rule
   variants — `TemperatureBelow`, `TemperatureAbove`,
   `PrecipitationProbabilityAbove` — read `feelsLikeMinC`,
   `feelsLikeMaxC` and `precipitationProbabilityMaxPct` respectively.
   There is no `WeatherCondition.matches`. **Clothes-rule firing does
   not depend on `DailyForecast.condition` at all.**

4. **Cache round-trip** via `InsightCache` (line 440 of cache): just
   serialises whatever it was given. Doesn't reinterpret.

So the *practical* impact of "fixing" `DailyForecast.condition` —
revised after the audit error — is much smaller than the original
recommendation made out:

- (#1) Wettest-hour rewrite already solves the chart-vs-summary
  mismatch — the rewrite uses the consensus-blended hourly
  conditions.
- (#2) The remaining concrete consumer is `RenderInsightSummary`'s
  fallback paths in `PrecipClause` rendering: when the precip-peak
  hour's own condition is `CLEAR` / `UNKNOWN` / non-precipitating
  but the day-level field *is* a precipitation bucket, the prose's
  rain/snow/etc. mention falls back to `today.condition`. Marginal
  improvement available, not a behaviour-changing gap.
- ~~(#3) Clothes-rule firing.~~ Doesn't exist.

## Options

### 1. Leave it; rely on the existing hourly-derived rewrites

The `slicedForToday` / `slicedForTonight` rewrites already do the
heavy lifting — `today.condition` post-slice is the condition of the
wettest hour in the consensus-blended hourly, except when that hour's
condition is `UNKNOWN` (which is when the slice rewrite falls through
to the pre-slice day-level value).

*Pro:* zero change. **This is the leading recommendation post-audit
correction.**
*Con:* on edge cases, `RenderInsightSummary`'s two day-level
fallbacks read the pre-slice (best_match-derived) condition that the
slice rewrite couldn't replace. There are two distinct sites:

  - **`peakPrecip`** (line 197): falls back to `today.condition`
    when no hourly hits `POSSIBLE_THRESHOLD` (and daily precipMax
    does), or when the peak hour's own condition is `UNKNOWN`.
    Audible when the wettest hour came back as `UNKNOWN` from
    every consulted model — by then the slice rewrite has already
    fallen through to best_match's day-level value, and
    `peakPrecip` reads that.
  - **`perModelConditionAt`** (line 246): falls back to
    `today.condition` when the matched hour's condition isn't a
    precipitating bucket but daily precipMax indicates rain.
    Audible whenever the wettest sliced hour's condition is
    non-precip (e.g. `CLOUDY`) — the slice rewrite stores that
    `CLOUDY` as `today.condition`, but `perModelConditionAt`
    re-reads it and treats it as the non-precip fallback path,
    landing on the default `RAIN`. The day-level field's role
    here is to satisfy the `isPrecipitation()` short-circuit; if
    the slice has already replaced it with `CLOUDY`, that
    short-circuit can't kick in either, so the fallback to
    `RAIN` is the actual behaviour.

Neither failure mode is loud, but they exist.

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
whatever upstream gave us) and change the (single) insight-prose
consumer to derive what it needs from `today.hourly` directly
rather than reading the day-level field. `RenderInsightSummary`'s
`PrecipClause` fallback becomes self-contained instead of leaning
on a value that may or may not be the right summary.

*Pro:* the aggregation lives where it's used; no
"`DailyForecast.condition` must be exactly right" contract for
downstream.
*Con:* one more place to keep in step with future condition-
related changes. Marginal vs. just leaving the existing
wettest-hour rewrite alone.

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

## My recommendation (revised after the audit correction)

**(1) Leave it.** The original recommendation was (2), justified by
a claimed clothes-rule dependency that doesn't actually exist (see
the strikethrough on #3 above — Codex's catch). Removing that
motivation, the case for (2) reduces to a marginal cosmetic
improvement on the `RenderInsightSummary` fallback, against a small
but real cost (one more place to maintain, one more set of edge
cases to think about).

The existing wettest-hour rewrite in `slicedForToday` /
`slicedForTonight` is consensus-aware (post-#396) and produces a
defensible value in the cases that matter. The remaining gap — the
`PrecipClause` fallback when the peak hour's own condition is
non-precipitating — is small enough that "fixing" it would
trade complexity for an inaudible product win.

If we *do* later have a reason to pick a different daily condition
heuristic — e.g. for a future day-summary icon that diverges
visibly from the prose — option (2) is the natural place to start.
Until then, keep the change cost where it belongs (zero).

## What changed since this doc was first drafted

- Audit point #3 was wrong (clothes rules don't read
  `forecast.condition`). Strikethrough preserved so the history is
  legible.
- Recommendation flipped from (2) to (1) as a consequence.
- Options (2)–(6) left intact as reference — useful if the picture
  changes again. Note that (4) is the "purest" alternative if we
  later add daily-level cross-model fields or if the wettest-hour
  rewrite turns out to read wrong in practice.

## What would justify revisiting

Concrete signals to watch for that would flip the recommendation
back toward (2)/(4)/(6):

- A future day-summary icon that reads `DailyForecast.condition`
  directly and visibly disagrees with the chart on divergent days.
- A new `ClothesRule` subtype that actually does key off
  `forecast.condition` (today's variants don't, but nothing
  stops a future one).
- Recurring user reports of the `PrecipClause` fallback prose
  surfacing the wrong condition on dry days where the peak hour
  isn't precipitating.

Until any of those land, leaving the existing rewrite alone is the
right call.
