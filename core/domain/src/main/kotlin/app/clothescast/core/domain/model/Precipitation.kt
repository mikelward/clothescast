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
 * than `!warrantsRainAccessory()`. The rule engine reads the daily aggregate
 * `precipitationProbabilityMaxPct` — the blended-consensus chance of rain — so
 * the rain-gear rules ([ClothesRule.PrecipitationProbabilityAbove]) fire purely
 * on that probability; the only thing the condition gate adds is suppressing
 * rain gear on a *snowy* day (snow can clear the probability gate too, and you
 * don't umbrella snow). Any rain-shaped day keeps the umbrella, so the card,
 * the recommendations, and the prose agree.
 */
internal fun WeatherCondition.isFrozenPrecipitation(): Boolean = this == WeatherCondition.SNOW

/**
 * The single blended-consensus probability-of-precipitation bar shared across
 * every rain surface — the prose's "chance of rain", the umbrella / rain-jacket
 * defaults ([ClothesRule.DEFAULTS]), and the conditions strip's droplet
 * ([app.clothescast.ui.garment] `RAIN_PEAK_THRESHOLD_PCT`). It's a *consensus
 * blend*, not a per-model agreement vote: the day's
 * [DailyForecast.precipitationProbabilityMaxPct] and the hourly
 * [HourlyForecast.precipitationProbabilityPct] are already the cross-model
 * blended chance of rain (see ConsensusBlend.kt), so every surface keys off the
 * same number and can't disagree.
 *
 * Two tiers, in precipitation-probability percent:
 *  - [POSSIBLE_THRESHOLD] (10%) — the chance-of-rain bar: at/above it the prose
 *    says "chance of rain", the umbrella default fires, and the strip shows a
 *    droplet.
 *  - [LIKELY_THRESHOLD] (50%) — the confident-rain bar: at/above it the prose
 *    drops the hedge and just says "rain", and the rain-jacket default fires.
 */
object PrecipProbability {
    const val POSSIBLE_THRESHOLD: Double = 10.0
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
