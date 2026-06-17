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
 * Rank of this condition on the *rain* intensity scale the precipitation-code
 * clothes rules ([ClothesRule.PrecipitationCode]) compare against: drizzle <
 * rain < thunderstorm. Everything that isn't rain-shaped — snow, fog, the dry
 * sky states, and the unknown sentinel — scores 0 and never satisfies a code
 * floor, so a `code ≥ drizzle` umbrella rule stays silent on a snowy day (you
 * don't umbrella snow, mirroring [isFrozenPrecipitation]). Thunderstorm ranks
 * above plain rain so a `code ≥ rain` rain-jacket rule fires on a storm too.
 *
 * Deliberately distinct from the prose path's `precipSeverity`
 * (RenderInsightSummary), which ranks snow *among* the precip types because it
 * picks the most severe condition to *name*; here we're deciding whether to
 * recommend *rain* gear, so snow is off the scale entirely.
 */
internal fun WeatherCondition.rainShapedSeverity(): Int = when (this) {
    WeatherCondition.THUNDERSTORM -> 3
    WeatherCondition.RAIN -> 2
    WeatherCondition.DRIZZLE -> 1
    WeatherCondition.SNOW,
    WeatherCondition.FOG,
    WeatherCondition.CLEAR,
    WeatherCondition.PARTLY_CLOUDY,
    WeatherCondition.CLOUDY,
    WeatherCondition.UNKNOWN -> 0
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
 * Hourly precip amount (mm) at/under which an amount-only signal — a positive
 * expected amount with no precipitating weather code — reads as drizzle rather
 * than rain. Light drizzle deposits only a few tenths of a millimetre an hour
 * (WMO light-drizzle intensity tops out around here); above it the amount is too
 * heavy to call "drizzle". Shared by [effectivePrecipCondition].
 */
internal const val DRIZZLE_AMOUNT_CEILING_MM: Double = 0.5

/**
 * Severity ordering over the precipitating conditions, snow *included* — used
 * when picking the single most-severe condition any model implies, for the
 * insight prose ("storm over rain over drizzle").
 * Non-precip conditions (and the unknown sentinel) score 0 and never win.
 *
 * Contrast [rainShapedSeverity], which drops snow off the scale entirely
 * because it gates *rain* gear (umbrella / rain jacket), not "is it
 * precipitating at all".
 */
internal fun WeatherCondition.precipSeverity(): Int = when (this) {
    WeatherCondition.THUNDERSTORM -> 4
    WeatherCondition.SNOW -> 3
    WeatherCondition.RAIN -> 2
    WeatherCondition.DRIZZLE -> 1
    else -> 0
}

/**
 * The precipitating condition a per-model hour implies, or null when the hour
 * carries no precip signal. Prefers the model's own weather code when it's
 * precipitating; otherwise infers from the expected amount — a trace
 * (≤ [DRIZZLE_AMOUNT_CEILING_MM]) reads as drizzle, anything heavier as plain
 * rain, so a coverage-less multi-millimetre hour (a model that omitted
 * weather_code, or an older cached payload) isn't undersold as "chance of
 * drizzle". Snow can't be told from rain by amount alone, but a genuine snow
 * hour almost always carries its own code, so rain is the safe less-specific
 * default for an amount-only hit.
 *
 * Shared by the insight prose ([RenderInsightSummary]'s coded drizzle
 * fallback) and the clothes-rule precip-code signal
 * ([PerModelHourly.peakRainCondition]) so the umbrella the prose names and
 * the umbrella the rule fires read off the same per-model evidence.
 */
internal fun PerModelHour.effectivePrecipCondition(): WeatherCondition? {
    condition?.takeIf { it.isPrecipitation() }?.let { return it }
    val mm = precipitationMm ?: 0.0
    return when {
        mm <= 0.0 -> null
        mm <= DRIZZLE_AMOUNT_CEILING_MM -> WeatherCondition.DRIZZLE
        else -> WeatherCondition.RAIN
    }
}

/**
 * The most-severe *rain-shaped* condition (drizzle < rain < thunderstorm) any
 * model reports anywhere in this per-model window, or [WeatherCondition.UNKNOWN]
 * when no model carries a rain signal. This is the per-model rain evidence the
 * clothes-rule engine can't get from the daily-aggregate [DailyForecast.condition]
 * alone — a single model's drizzle code (the AIFS case: a drizzle code + a trace
 * of rain but no probability) is invisible in the blended daily condition yet
 * shows up here, so a `code ≥ drizzle` umbrella rule can fire on it.
 *
 * Snow is deliberately excluded ([rainShapedSeverity] scores it 0): the rain-gear
 * rules this feeds (umbrella, rain jacket) don't apply to snow, and the prose
 * names snow on its own [precipSeverity] path. TODO(snow-gear): when frozen-precip
 * gear lands (snow boots, a heavier coat keyed off snow), give it a parallel
 * `peakSnowCondition` signal and snow-keyed rules rather than folding snow back
 * into this rain-shaped peak.
 *
 * This is an *any-model* signal with no confidence gradation: one model's
 * rain-shaped code fires the rain-gear rules ([ClothesRule.RainCode]) as firmly
 * as a unanimous downpour. The insight prose, by contrast, hedges the same
 * single-model code as a *chance* (POSSIBLE) and reserves a confident "rain" for
 * a majority of models clearing [PrecipProbability.LIKELY_THRESHOLD] (see
 * RenderInsightSummary.pickPerModelPeak), and the conditions strip keys on the
 * blended/modal condition, which needs rough agreement to show at all — so the
 * three surfaces disagree on a lone-model drizzle. TODO(rain-confidence-reconcile):
 * unify how rules, prose, and strip treat single-model vs. majority rain — either
 * grade the rule (e.g. only the umbrella fires on one model; the heavier rain
 * jacket wants agreement) or accept the asymmetry explicitly and document why.
 */
internal fun PerModelHourly.peakRainCondition(): WeatherCondition =
    byModel.values.asSequence()
        .flatten()
        .mapNotNull { it.effectivePrecipCondition() }
        .filter { it.rainShapedSeverity() > 0 }
        .maxByOrNull { it.rainShapedSeverity() }
        ?: WeatherCondition.UNKNOWN

/**
 * Per-model agreement thresholds for surfacing rain, in precipitation-
 * probability percent. The user's mental model is "1 model says rain → hedge
 * it as a chance; majority of models say a lot of rain → just say rain". 20%
 * is the chance-of-rain ("possible") bar; 50% is the per-model bar for a
 * *confident* announcement. Shared so the week-ahead headline only mentions
 * rain the today / tonight insight would also call.
 *
 * [POSSIBLE_THRESHOLD] is deliberately aligned with the umbrella default's
 * probability gate ([ClothesRule.DEFAULTS]) and the conditions strip's rain-cell
 * gate ([app.clothescast.ui.garment] `RAIN_PEAK_THRESHOLD_PCT`), so the prose
 * mentions rain on exactly the days the umbrella fires and the strip shows a
 * droplet — no surface flags rain the others stay silent on.
 */
object PrecipProbability {
    const val POSSIBLE_THRESHOLD: Double = 20.0
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
