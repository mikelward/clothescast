package app.clothescast.core.domain.model

import java.time.LocalDate
import java.time.LocalTime

/**
 * One day's forecast.
 *
 * Temperature is provided as both raw 2 m air temperature ([temperatureMinC],
 * [temperatureMaxC]) and apparent / "feels like" ([feelsLikeMinC], [feelsLikeMaxC]),
 * which factors in wind chill and humidity. The user-facing prompt and clothes
 * thresholds use feels-like — that's what people actually experience.
 */
data class DailyForecast(
    val date: LocalDate,
    val temperatureMinC: Double,
    val temperatureMaxC: Double,
    val feelsLikeMinC: Double,
    val feelsLikeMaxC: Double,
    val precipitationProbabilityMaxPct: Double,
    val precipitationMmTotal: Double,
    val condition: WeatherCondition,
    val hourly: List<HourlyForecast> = emptyList(),
    /**
     * The most-severe *rain-shaped* precipitation any consulted model implies
     * for the day, surfaced for the clothes-rule precip-code conditions
     * ([ClothesRule.PrecipitationCode]). [condition] is the peak-*probability*
     * hour's blended weather code, which routinely under-calls drizzle: a single
     * model's drizzle (a code + a trace of rain but no probability) never
     * survives the blend, so [condition] reads "cloudy" while a model quietly
     * forecasts drizzle. This field carries that per-model evidence
     * (see [PerModelHourly.peakPrecipCondition]) so a `code ≥ drizzle` umbrella
     * rule fires on it. Defaults to [WeatherCondition.UNKNOWN] — "no per-model
     * signal available" — for the many construction sites (mappers, cache
     * payloads, test fixtures) that don't have per-model data; the rule engine
     * then falls back to [condition] / [hourly] codes. Sits after [hourly] (both
     * defaulted) so positional constructions keep binding unchanged.
     */
    val peakRainCondition: WeatherCondition = WeatherCondition.UNKNOWN,
)

data class HourlyForecast(
    val time: LocalTime,
    val temperatureC: Double,
    val feelsLikeC: Double,
    val precipitationProbabilityPct: Double,
    val condition: WeatherCondition,
    /**
     * Expected precipitation amount for the hour, mm liquid-equivalent (snow
     * is reported as its melted depth). Defaults to 0 for back-compat with
     * older cached payloads and pre-existing test fixtures that don't carry
     * the field. Open-Meteo's `precipitation` covers rain, showers, and snow
     * in a single series.
     */
    val precipitationMm: Double = 0.0,
    /**
     * 10 m wind speed for the hour, km/h (Open-Meteo's native unit). Nullable /
     * defaulted for back-compat with older cached payloads and fixtures that
     * predate the field; the at-a-glance conditions widget takes the period
     * maximum and only surfaces it above a "notable" threshold.
     */
    val windSpeedKmh: Double? = null,
    /**
     * UV index for the hour (0–~12+). Nullable / defaulted as [windSpeedKmh].
     * The conditions widget shows the period's peak when it's high enough to be
     * worth acting on.
     */
    val uvIndex: Double? = null,
)

/** Coarse condition buckets sufficient for prompt construction and rule evaluation. */
enum class WeatherCondition {
    CLEAR,
    PARTLY_CLOUDY,
    CLOUDY,
    FOG,
    DRIZZLE,
    RAIN,
    SNOW,
    THUNDERSTORM,
    UNKNOWN,
}
