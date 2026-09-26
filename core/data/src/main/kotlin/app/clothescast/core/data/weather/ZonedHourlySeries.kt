package app.clothescast.core.data.weather

import app.clothescast.core.domain.model.PerModelHour

/**
 * A forecaster's hourly series plus the IANA time zone its local wall-clock
 * [PerModelHour.time]s are in, as the forecaster itself reported it.
 *
 * The zone travels with the hours because nothing else pins it down: the
 * series can be served from a cache stored for a nearby place, and a series
 * whose local times are in a different zone from the Open-Meteo bundle it's
 * blended with would sit an hour (or a date) off every other model.
 * [OpenMeteoClient] compares the two and leaves the series out on a mismatch.
 * Null when the forecaster didn't say, which is accepted as-is.
 */
data class ZonedHourlySeries(
    val hours: List<PerModelHour>,
    val zoneId: String?,
)
