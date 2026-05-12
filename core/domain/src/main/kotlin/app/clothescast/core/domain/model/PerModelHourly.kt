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
    /** Probability of measurable precipitation for the hour, 0–100. */
    val precipitationProbabilityPct: Double,
)
