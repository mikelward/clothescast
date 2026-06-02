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
 * True when a "bring an umbrella" carried accessory reads correctly for this
 * condition — wet, rain-shaped precipitation the user shelters under. RAIN and
 * DRIZZLE qualify; THUNDERSTORM does too — it's rain with lightning, and a
 * thunderstorm forecast is still a wet one the user wants an umbrella for. SNOW
 * isn't wet-in-the-umbrella sense, and FOG / dry-sky / UNKNOWN states aren't
 * precipitation at all.
 *
 * The single source of truth shared by every umbrella surface so they can't
 * drift: the rule engine ([EvaluateClothesRules]) gates the carried-accessory
 * rule on it (so the home-screen icon, the bulleted recommendations, and the
 * outfit card all agree), and the prose formatter gates the "bring an umbrella"
 * clause on it too.
 */
fun WeatherCondition.warrantsRainAccessory(): Boolean = when (this) {
    WeatherCondition.RAIN,
    WeatherCondition.DRIZZLE,
    WeatherCondition.THUNDERSTORM -> true
    WeatherCondition.SNOW,
    WeatherCondition.FOG,
    WeatherCondition.CLEAR,
    WeatherCondition.PARTLY_CLOUDY,
    WeatherCondition.CLOUDY,
    WeatherCondition.UNKNOWN -> false
}

/**
 * True when this condition is frozen precipitation — snow — which an umbrella
 * doesn't shelter against.
 *
 * This is the carried-accessory gate the *rule engine* uses
 * ([EvaluateClothesRules], mirrored in [OutfitSuggestion.fromForecast]), and
 * it's deliberately the inverse-minus-snow of [warrantsRainAccessory] rather
 * than `!warrantsRainAccessory()`. The rule engine reads the raw daily
 * `condition` — the weather code of the day's peak-*probability* hour — which
 * routinely under-calls the precipitation *type*: an hour can sit at 88% chance
 * of rain yet carry an "overcast" code because its modeled accumulation is ~0
 * (high probability, little rain). Gating the umbrella on "is the code wet"
 * there drops it on exactly those days, even though the insight prose still
 * announces the rain (the prose coerces a high-probability hour to RAIN via
 * `perModelConditionAt`, then gates its "bring an umbrella" clause on
 * [warrantsRainAccessory] against that coerced condition). The rule engine
 * can't coerce, so it gates on "not snow" instead — the umbrella's purpose is
 * solely to keep it off a *snowy* day (snow can clear the probability gate too,
 * and you don't umbrella snow). Any rain-shaped or dry-coded-but-likely day
 * keeps the umbrella, so the card and the prose agree.
 */
internal fun WeatherCondition.isFrozenPrecipitation(): Boolean = this == WeatherCondition.SNOW

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

    /**
     * Fraction of a period's reported hours that must clear [LIKELY_THRESHOLD]
     * before the insight stops naming a single peak hour ("Rain at 4pm.") and
     * says the rain runs the whole window ("Rain all day."). 0.6 = a clear
     * majority of the hours are wet, so a single time would undersell it — the
     * exact case where the chart shows rain ≥ 50% across nearly every hour.
     * Pairs with a separate "two or more separated rainy spells also count as
     * all-day" rule in the renderer; either condition trips the all-day wording.
     */
    const val ALL_DAY_COVERAGE_FRACTION: Double = 0.6
}
