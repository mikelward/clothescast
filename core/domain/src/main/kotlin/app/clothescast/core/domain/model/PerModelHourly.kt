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
) {
    companion object {
        /**
         * Sentinel model id for Open-Meteo's `best_match` auto-selection —
         * stored alongside the consulted models in [byModel] so the chart
         * can render it as a labelled overlay. Consensus + spread / confidence
         * computations exclude this entry; only the real consulted models
         * (ECMWF, GFS, ICON, etc.) feed those.
         */
        const val BEST_MATCH_MODEL_ID = "best_match"
    }
}

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

// TODO(model-spread-stat): cross-model spread is currently reported as
//   `max - min` (see [ConfidenceInfo.tempSpreadC]). One outlying model
//   swings that disproportionately; explore RMSE / std-dev across the
//   consulted models as an alternative confidence input, plotted
//   alongside the existing spread so we can A/B them before switching.
// TODO(weather-code-aggregation): [blendConsensusHourly] currently keeps
//   the best_match weather code (CLEAR / RAIN / etc.) even when it
//   blends the numeric fields, so a "Combined" line at 90% rain can
//   coexist with a "Clear" condition icon. Modal aggregation of weather
//   codes across the consulted models would close that loop.
