package app.clothescast.core.domain.model

import java.time.LocalDateTime

/**
 * Per-model hourly apparent-temperature and precipitation-probability series
 * for the same day [ConfidenceInfo] summarises, pulled from the same Open-Meteo
 * batched multi-model request. Used by the Today screen's "show model spread"
 * power-user setting to overlay each model's curve on the forecast and
 * precipitation charts.
 *
 * Null on every `ForecastBundle` / `Insight` produced by a fetch that didn't
 * request hourly fields, by an older app version pulled from cache, or by an
 * implementation that doesn't support it. UI should treat null as "no overlay
 * data available" rather than "models all agree."
 *
 * Per-model entries can be a strict subset of hours (the upstream API
 * occasionally returns nulls for a model whose run is still warming up); we
 * drop missing hours from a model's series rather than carrying nulls
 * through.
 */
data class PerModelHourly(
    /** Open-Meteo model id (e.g. `ecmwf_ifs04`) -> hourly series. */
    val byModel: Map<String, List<PerModelHour>>,
) {
    companion object {
        /**
         * Sentinel model id for Open-Meteo's `best_match` auto-selection —
         * stored alongside the consulted models in [byModel] so the chart
         * can render it as a labelled overlay. Treated as a regular model
         * in the consensus blend + divergence summary: it's one of the
         * forecasts in play and folding it in keeps Open-Meteo's
         * (presumably location-tuned) signal in the mean. ConfidenceInfo's
         * tempSpreadC / precipSpreadPp still come from the consulted-only
         * subset because best_match isn't reported via the side-band
         * `daily` block.
         */
        const val BEST_MATCH_MODEL_ID = "best_match"
    }
}

data class PerModelHour(
    /**
     * Wall-clock timestamp for the hour in the location's local zone. Carries
     * the calendar date alongside the hour-of-day so the tonight insight can
     * wrap past midnight without aliasing today's 02:00 against tomorrow's
     * 02:00 in time-keyed lookups (e.g. [PerModelHourly] slicing, consensus
     * blending). The hour-of-day plotted on the Today chart is simply
     * `time.hour` — the date discriminator costs nothing for the UI.
     */
    val time: LocalDateTime,
    /** Apparent ("feels-like") temperature for the hour, °C. */
    val apparentTemperatureC: Double,
    /** Raw 2 m air temperature for the hour, °C — the same series the blended
     *  [HourlyForecast.temperatureC] line carries, but per model. Surfaced so
     *  the chart can overlay model lines in air-temp mode (tap-to-toggle) too,
     *  not just in feels-like mode. */
    val temperatureC: Double,
    /** Probability of measurable precipitation for the hour, 0–100. Nullable
     *  because Open-Meteo doesn't expose `precipitation_probability_<model>`
     *  for every model in its `&models=` set — UKMO / JMA / GEM / ARPEGE
     *  routinely omit it. `null` means "this model didn't provide a precip
     *  forecast for this hour", which consumers handle distinctly from
     *  "this model predicted 0% rain": precip-specific aggregates
     *  (consensus blend, insight rain prose, precipitation chart) skip
     *  null values rather than treating them as zero, so a missing-data
     *  model doesn't silently downgrade the morning rain forecast or
     *  draw a misleading flat-0% line on the precip chart. */
    val precipitationProbabilityPct: Double?,
    /** Expected precipitation amount for the hour, mm liquid-equivalent.
     *  Nullable for the same reason as [precipitationProbabilityPct]:
     *  Open-Meteo's per-model `precipitation_<model>` is absent for several
     *  consulted models, and a missing series shouldn't be silently
     *  conflated with "this model predicts a dry hour". The hourly-rainfall
     *  chart skips null values so a model without coverage doesn't pin its
     *  line to zero. */
    val precipitationMm: Double? = null,
    /** 10 m wind speed for the hour, km/h. Drives wind-chill in the
     *  feels-like formula — when models agree on air temp but disagree on
     *  feels-like, this is usually the culprit. Nullable: older cache
     *  payloads and model runs that didn't return the field carry no value,
     *  and the diagnostic wind chart hides that model. */
    val windSpeedKmh: Double? = null,
    /** 2 m relative humidity, percent (0–100). Low-signal at the cool
     *  temperatures Europe sees most of the year (apparent-temp's humidity
     *  term only kicks in above ~20 °C) but worth carrying for hot days
     *  where it's the dominant feels-like contributor. Nullable — see
     *  [windSpeedKmh]. */
    val relativeHumidityPct: Double? = null,
    /** Low-deck cloud cover (below ~2 km), percent (0–100). We pick the low
     *  deck rather than total column because cirrus inflates the total but
     *  barely shades the surface — the user-facing "feels gloomy" question
     *  tracks the low deck. Still the upstream driver of air-temp divergence
     *  when models disagree on solar gain (one predicts a mid-day clearing,
     *  the other keeps it overcast). Nullable — see [windSpeedKmh]. */
    val cloudCoverPct: Double? = null,
    /** Surface shortwave (solar) radiation for the hour, W/m². Already bakes
     *  in cloud attenuation, so it captures "cloudy but bright" days better
     *  than cloud cover alone. Nullable — see [windSpeedKmh]. */
    val shortwaveRadiationWm2: Double? = null,
    /** Sunshine duration for the hour, in seconds (0–3600). Open-Meteo
     *  thresholds the per-minute direct irradiance so this isn't quite the
     *  same as "1 - cloud cover" — a partly cloudy hour can still report
     *  most of its minutes as sunshine. Summed across the day for the
     *  Today screen's "Xh of sun today" blurb. Nullable — see [windSpeedKmh]. */
    val sunshineDurationSec: Double? = null,
    /** UV index for the hour, 0–~12+. Derived from cloud cover, ozone and
     *  solar zenith angle; surfaced so a hat / sunscreen rule can fire
     *  even when the temperature itself doesn't trigger anything. Nullable
     *  — see [windSpeedKmh]. */
    val uvIndex: Double? = null,
    /** Coarse weather bucket for the hour, mapped from the model's WMO
     *  weather code. Carried per-model so [blendConsensusHourly] can pick
     *  a defensible condition for the blended main line (modal aggregation
     *  with a severity tiebreak); without it the blended numeric series
     *  would coexist with whichever model the [HourlyForecast] originally
     *  came from for the icon/label, which on divergence days produced
     *  "90% rain, Clear conditions" inconsistencies. Nullable for the same
     *  back-compat reasons as the other diagnostic fields. */
    val condition: WeatherCondition? = null,
)

// TODO(model-spread-stat): cross-model spread is currently reported as
//   `max - min`, computed independently in two places —
//   [ConfidenceInfo.computeFrom] (tier title, excludes best_match) and
//   [ModelDivergenceSummary.computeFrom] (peak-hour factor attribution,
//   includes best_match). One outlying model swings max-min
//   disproportionately; explore RMSE / std-dev across the consulted models
//   as an alternative confidence input, plotted alongside the existing
//   spread so we can A/B them before switching. When that lands, factor the
//   feels-like spread into one shared helper both call sites use (the
//   *policy* — which models, which threshold, what to emit — stays separate;
//   only the spread primitive is shared).
