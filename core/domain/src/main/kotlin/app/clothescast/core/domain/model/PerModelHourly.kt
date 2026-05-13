package app.clothescast.core.domain.model

import java.time.LocalTime

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
)

data class PerModelHour(
    val time: LocalTime,
    /** Apparent ("feels-like") temperature for the hour, °C. */
    val apparentTemperatureC: Double,
    /** Raw 2 m air temperature for the hour, °C — the same series the blended
     *  [HourlyForecast.temperatureC] line carries, but per model. Surfaced so
     *  the chart can overlay model lines in air-temp mode (tap-to-toggle) too,
     *  not just in feels-like mode. */
    val temperatureC: Double,
    /** Probability of measurable precipitation for the hour, 0–100. */
    val precipitationProbabilityPct: Double,
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
    /** Total cloud cover, percent (0–100). Not a feels-like input, but the
     *  upstream driver of air-temp divergence when models disagree on solar
     *  gain (one predicts a mid-day clearing, the other keeps it overcast).
     *  Nullable — see [windSpeedKmh]. */
    val cloudCoverPct: Double? = null,
)

// TODO(model-divergence-summary): per-hour diagnostic panel — "this hour
//   ICON predicts 6.5 °C warmer than GFS; biggest contributing factor:
//   cloud cover (ICON 20% vs GFS 85%)" or "wind speed (12 vs 22 km/h)".
//   We now carry the per-model wind / humidity / cloud series; the missing
//   piece is the heuristic that ranks them and the bit of UI that surfaces
//   it. Probably belongs as a tappable hint under the temp chart when the
//   instantaneous spread exceeds the LOW-confidence threshold.
// TODO(model-spread-stat): cross-model spread is currently reported as
//   `max - min` (see [ConfidenceInfo.tempSpreadC]). One outlying model
//   swings that disproportionately; consider RMSE / std-dev across the
//   consulted models as an alternative confidence input.
// TODO(main-line-model): OpenMeteoClient's `forecast` call doesn't pass a
//   `models=` parameter, so the blended "main" line on the temperature
//   chart is whatever Open-Meteo's `best_match` picks for the location —
//   it visibly tracks one of the consulted models rather than a midpoint.
//   That's confusing when models diverge: the clothes recommendation
//   effectively picks one side. Consider either explicitly averaging the
//   per-model series for the main line, or surfacing which model
//   best_match chose so the legend reads honestly.
// TODO(model-spread-stat): cross-model spread is currently reported as
//   `max - min` (see [ConfidenceInfo.tempSpreadC]). That's intuitive but
//   one outlying model swings it disproportionately; explore RMSE or std-dev
//   across the consulted models as an alternative confidence input, plotted
//   alongside the existing spread so we can A/B them before switching.
