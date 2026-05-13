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
)

// TODO(model-divergence-diagnostics): when models disagree, the cause is
//   usually one of two things — disagreement on raw 2 m air temperature, or
//   disagreement on the wind / humidity inputs that get folded into the
//   apparent-temperature calculation. Carrying [temperatureC] alongside
//   [apparentTemperatureC] is the first half; a follow-up should add the
//   per-model wind-speed and relative-humidity hourly series so we can
//   visualise where the divergence is *coming from*, and a small Insight-
//   detail panel that summarises which factor is the biggest contributor.
// TODO(model-spread-stat): cross-model spread is currently reported as
//   `max - min` (see [ConfidenceInfo.tempSpreadC]). That's intuitive but
//   one outlying model swings it disproportionately; explore RMSE or std-dev
//   across the consulted models as an alternative confidence input, plotted
//   alongside the existing spread so we can A/B them before switching.
