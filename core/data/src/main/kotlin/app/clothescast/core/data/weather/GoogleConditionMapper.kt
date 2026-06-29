package app.clothescast.core.data.weather

import app.clothescast.core.domain.model.WeatherCondition

/**
 * Google Weather API `weatherCondition.type` enum → coarse [WeatherCondition]
 * bucket, the same coarse buckets [WmoCodeMapper] produces for Open-Meteo so a
 * Google hour can vote in the consensus blend's modal-condition aggregation
 * alongside the Open-Meteo models.
 *
 * Reference: the `WeatherCondition.Type` enum on
 * https://developers.google.com/maps/documentation/weather/reference/rest/v1/forecast.hours/lookup
 *
 * Mapping notes for the judgment calls:
 *  - Google has no "drizzle" tier; its lightest wet type is `LIGHT_RAIN`, which
 *    maps to [WeatherCondition.RAIN] (mirroring WMO code 61 → RAIN). The DRIZZLE
 *    bucket is simply never produced from Google data.
 *  - `RAIN_AND_SNOW` maps to [WeatherCondition.SNOW] so the domain's
 *    frozen-precipitation gate (which suppresses rain gear on snow days) fires —
 *    there's frozen precipitation in the mix.
 *  - `HAIL` / `HAIL_SHOWERS` map to [WeatherCondition.THUNDERSTORM]: hail is
 *    convective and "take shelter" is the actionable read, and THUNDERSTORM is
 *    the most severe bucket so it carries that weight in the severity tiebreak.
 *  - `WINDY` maps to [WeatherCondition.UNKNOWN]: there's no wind bucket and the
 *    type asserts nothing about cloud or precipitation, so it sits out the
 *    condition vote (UNKNOWN has the lowest severity rank and never wins a
 *    modal tie). Wind itself rides the separate `wind.speed` field.
 *  - Anything unrecognized (a future type, `TYPE_UNSPECIFIED`) returns
 *    [WeatherCondition.UNKNOWN]; the caller logs the raw type so a new value is
 *    traceable rather than silently bucketed.
 */
internal object GoogleConditionMapper {
    fun map(type: String?): WeatherCondition = when (type) {
        "CLEAR", "MOSTLY_CLEAR" -> WeatherCondition.CLEAR
        "PARTLY_CLOUDY" -> WeatherCondition.PARTLY_CLOUDY
        "MOSTLY_CLOUDY", "CLOUDY" -> WeatherCondition.CLOUDY

        "LIGHT_RAIN_SHOWERS", "CHANCE_OF_SHOWERS", "SCATTERED_SHOWERS",
        "RAIN_SHOWERS", "HEAVY_RAIN_SHOWERS",
        "LIGHT_TO_MODERATE_RAIN", "MODERATE_TO_HEAVY_RAIN", "RAIN",
        "LIGHT_RAIN", "HEAVY_RAIN", "RAIN_PERIODICALLY_HEAVY",
        "WIND_AND_RAIN",
        -> WeatherCondition.RAIN

        "LIGHT_SNOW_SHOWERS", "CHANCE_OF_SNOW_SHOWERS", "SCATTERED_SNOW_SHOWERS",
        "SNOW_SHOWERS", "HEAVY_SNOW_SHOWERS",
        "LIGHT_TO_MODERATE_SNOW", "MODERATE_TO_HEAVY_SNOW", "SNOW",
        "LIGHT_SNOW", "HEAVY_SNOW", "SNOWSTORM", "SNOW_PERIODICALLY_HEAVY",
        "HEAVY_SNOW_STORM", "BLOWING_SNOW", "RAIN_AND_SNOW",
        -> WeatherCondition.SNOW

        "HAIL", "HAIL_SHOWERS",
        "THUNDERSTORM", "THUNDERSHOWER", "LIGHT_THUNDERSTORM_RAIN",
        "SCATTERED_THUNDERSTORMS", "HEAVY_THUNDERSTORM",
        -> WeatherCondition.THUNDERSTORM

        else -> WeatherCondition.UNKNOWN
    }
}
