package app.clothescast.core.domain.model

/**
 * True when this condition represents falling precipitation (rain, drizzle,
 * snow, thunderstorm) as opposed to a dry sky state (clear, cloud, fog) or
 * the unknown sentinel. Shared by the today/tonight and week-ahead insight
 * paths so they agree on what counts as "it's precipitating".
 */
internal fun WeatherCondition.isPrecipitation(): Boolean = when (this) {
    WeatherCondition.DRIZZLE,
    WeatherCondition.RAIN,
    WeatherCondition.SNOW,
    WeatherCondition.THUNDERSTORM -> true
    WeatherCondition.CLEAR,
    WeatherCondition.PARTLY_CLOUDY,
    WeatherCondition.CLOUDY,
    WeatherCondition.FOG,
    WeatherCondition.UNKNOWN -> false
}

/**
 * Per-model agreement thresholds for surfacing rain, in precipitation-
 * probability percent. The user's mental model is "1 model says rain → hedge
 * it as a chance; majority of models say a lot of rain → just say rain". 30%
 * is the historical base-only trigger threshold; 50% is the per-model bar for
 * a *confident* announcement. Shared so the week-ahead headline only mentions
 * rain the today / tonight insight would also call.
 */
object PrecipProbability {
    const val POSSIBLE_THRESHOLD: Double = 30.0
    const val LIKELY_THRESHOLD: Double = 50.0
}
