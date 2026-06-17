package app.clothescast.core.domain.model

/**
 * A user-configured rule that suggests an item of clothing or gear when a forecast
 * crosses a threshold.
 *
 * Temperature thresholds are checked against "feels like" (apparent) values rather
 * than raw 2 m air temperature — what the user actually experiences when stepping
 * outside, factoring in wind chill and humidity. Precipitation rules check the day's
 * peak probability.
 *
 * [item] is a typed [Garment]; the on-disk DTO stores its key as a string and
 * drops any stored rule whose key isn't in the catalog (legacy free-form items
 * from before the catalog existed). The editor picks from the fixed catalog, so
 * new rules are always catalog garments.
 *
 * TODO(rules-redesign): broaden the [Garment] catalog beyond tops + bottoms
 * (headwear, scarf / gloves, footwear, rain / sun gear — gated on the outfit
 * display model), and add a "user-defined" path with an explicit translation
 * hook for items outside the catalog.
 *
 * TODO(rules-in-layers): companion to [ClothesFormat.LAYER_COUNT] — let the
 * user *author* rules in layer-count terms too ("below 12°C → 3 layers")
 * instead of always picking a specific garment. Sugar over the existing
 * garment-keyed path: each layer-count target maps to a representative
 * [Garment] of that [Garment.layerCount] under the hood (e.g. 2 → SWEATER,
 * 3 → JACKET, 4 → PUFFER), so the rule engine, outfit card, and item
 * persistence stay unchanged. The editor would gain a "type" toggle
 * (specific garment vs. layer count) and the rationale dialog needs a
 * matching phrasing.
 */
data class ClothesRule(
    val item: Garment,
    val condition: Condition,
) {
    fun appliesTo(forecast: DailyForecast): Boolean = condition.matches(forecast)

    sealed interface Condition {
        fun matches(forecast: DailyForecast): Boolean
    }

    /**
     * Below-threshold temperature condition. [value] is denominated in [unit] —
     * the rule remembers what the user typed so a 65°F threshold stays exactly
     * 65°F across °C↔°F switches in Region settings (no integer round-trip drift).
     * [unit] defaults to [TemperatureUnit.CELSIUS] so legacy on-disk rules
     * (written before the unit field existed) and existing call sites keep
     * working unchanged.
     */
    data class TemperatureBelow(
        val value: Double,
        val unit: TemperatureUnit = TemperatureUnit.CELSIUS,
    ) : Condition {
        override fun matches(forecast: DailyForecast) =
            forecast.feelsLikeMinC < value.fromUnit(unit)
    }

    /** Above-threshold temperature condition. See [TemperatureBelow] for the [unit] semantics. */
    data class TemperatureAbove(
        val value: Double,
        val unit: TemperatureUnit = TemperatureUnit.CELSIUS,
    ) : Condition {
        override fun matches(forecast: DailyForecast) =
            forecast.feelsLikeMaxC > value.fromUnit(unit)
    }

    /**
     * Fires when the day's peak rain probability is at least [percent]. The
     * comparison is inclusive (≥) so the boundary lines up exactly with the
     * prose's chance-of-rain bar ([PrecipProbability.POSSIBLE_THRESHOLD]) and the
     * conditions strip — a forecast sitting right on the gate (e.g. 20%) fires the
     * umbrella the same moment the prose says "chance of rain" and the strip shows
     * a droplet. (So a `> N` reading of "above" is really "at or above N".)
     */
    data class PrecipitationProbabilityAbove(val percent: Double) : Condition {
        override fun matches(forecast: DailyForecast) = forecast.precipitationProbabilityMaxPct >= percent
    }

    /**
     * Fires when the day's per-model rain is at least as heavy as [floor] on the
     * drizzle < rain < thunderstorm scale ([rainShapedSeverity]). [floor] is a
     * [WeatherCondition] — only [WeatherCondition.DRIZZLE] or [WeatherCondition.RAIN]
     * are meaningful (the editor offers just those two); any non-rain-shaped floor
     * (snow, a dry-sky state) ranks 0 and so matches any rainy day, which is why
     * the editor never authors one.
     *
     * Unlike [PrecipitationProbabilityAbove] — which only sees the daily aggregate
     * probability — this reads the *weather code*, so it catches drizzle that sits
     * below every probability gate (drizzle from shallow stratus routinely reports
     * a code + a trace of rain but a low or absent probability of *measurable*
     * rain).
     *
     * It keys **only** on [DailyForecast.peakRainCondition] — the per-model coded
     * evidence the morning insight's drizzle tier renders
     * ([RenderInsightSummary]'s coded fallback) — deliberately *not* the base daily
     * [DailyForecast.condition] or [DailyForecast.hourly] codes. Reading the base
     * code would fire rain gear in the exact case the prose stays silent: per-model
     * data missing (a failed multi-model fetch or a pre-per-model cached bundle)
     * and a base drizzle/rain code below the prose's probability threshold — the
     * umbrella would drop out of speech and the rain jacket would be suggested with
     * no rain clause. Keying on the same evidence the summary renders keeps the
     * outfit, recommendations, and prose in agreement; when there's no per-model
     * signal [peakRainCondition] is [WeatherCondition.UNKNOWN] and the rule leans on
     * its probability arm alone.
     *
     * Snow scores 0 on the rain scale, so a snowy day never fires a rain-gear
     * rule (you don't umbrella snow — mirrors [isFrozenPrecipitation], the gate in
     * [EvaluateClothesRules]). TODO(snow-gear): snow is out of scope here by
     * design; frozen-precip gear should arrive as its own snow-keyed condition,
     * not by widening this rain floor to rank snow.
     */
    data class RainCode(val floor: WeatherCondition) : Condition {
        override fun matches(forecast: DailyForecast): Boolean {
            val floorSeverity = floor.rainShapedSeverity()
            return floorSeverity > 0 && forecast.peakRainCondition.rainShapedSeverity() >= floorSeverity
        }
    }

    /**
     * Composite OR: matches when *any* child condition matches. Lets a single
     * rule fire on either of two independent signals — e.g. the umbrella's "≥ 20%
     * chance of rain *or* a drizzle weather code", which catches both the
     * confident-but-probability-driven day and the quiet drizzle day probability
     * alone misses. Empty list never matches (no arm to satisfy). Children can be
     * any [Condition], including nested composites, though the editor only ever
     * builds the two-arm probability-or-rain-code shape.
     */
    data class AnyOf(val conditions: List<Condition>) : Condition {
        override fun matches(forecast: DailyForecast) = conditions.any { it.matches(forecast) }
    }

    companion object {
        // Item keys are en-US-flavoured ("sweater", not "jumper") so they
        // align with the `today_outfit_top_*` labels in values/strings.xml.
        // Per-language phrasers translate at format time
        // (e.g. GermanClothesPhraser maps "sweater" → "Pullover").
        // Two rain-gear defaults ship as precip-keyed rules, each an OR of a
        // probability gate and a weather-code floor ([AnyOf]):
        //  - Umbrella (carried): ≥ 20% chance OR a drizzle-or-worse code. Light
        //    rain you carry an umbrella for starts low, and drizzle slips under
        //    every probability gate (probability-of-precip is the chance of
        //    *measurable* rain ≥ ~0.1 mm; shallow-stratus drizzle reports a code
        //    + a trace but a low / absent probability — the AIFS case), so the
        //    code arm catches what the 20% arm can't. Folded into the insight's
        //    rain clause ("Tonight, rain, bring an umbrella.").
        //  - Rain jacket (outer shell): ≥ 50% chance OR a rain-or-worse code.
        //    A heavier bar than the umbrella — you reach for a jacket when rain
        //    is likely or already coded as real rain, not for a passing drizzle.
        // The code arm reads rain-shaped severity ([rainShapedSeverity]), so snow
        // never fires either rule. Like every default both are editable — the
        // user can retune the percent, change the code floor (drizzle / rain /
        // none), or delete the rule from the clothes-rule editor.
        // TODO(rain-accessory-variants): broaden the rain-gear rules further
        //  (hood, rain boots, …) once the resource strings and per-locale
        //  phrasers cover them.
        val DEFAULTS: List<ClothesRule> = listOf(
            ClothesRule(Garment.SWEATER, TemperatureBelow(16.0)),
            ClothesRule(Garment.JACKET, TemperatureBelow(10.0)),
            ClothesRule(Garment.COAT, TemperatureBelow(4.0)),
            ClothesRule(Garment.GLOVES, TemperatureBelow(4.0)),
            ClothesRule(Garment.SHORTS, TemperatureAbove(23.0)),
            ClothesRule(
                Garment.UMBRELLA,
                AnyOf(
                    listOf(
                        PrecipitationProbabilityAbove(20.0),
                        RainCode(WeatherCondition.DRIZZLE),
                    ),
                ),
            ),
            ClothesRule(
                Garment.RAIN_JACKET,
                AnyOf(
                    listOf(
                        PrecipitationProbabilityAbove(50.0),
                        RainCode(WeatherCondition.RAIN),
                    ),
                ),
            ),
        )

        /** Sanity bounds in °C for the rationale dialog's `+1°` / `−1°` taps. Wide enough
         * that no realistic weather rule ever touches them; narrow enough that a runaway
         * tap can't stamp a billion degrees into preferences. The Settings clothes-rule
         * editor doesn't enforce these — typing 100°C by hand is still allowed — they
         * exist only to bound the inline-nudge path. */
        const val THRESHOLD_MIN_C: Double = -20.0
        const val THRESHOLD_MAX_C: Double = 40.0
    }
}

/**
 * The Celsius-equivalent threshold of a temperature-keyed rule, regardless of
 * which unit the user typed it in. Returns `null` for the precip-keyed
 * conditions ([ClothesRule.PrecipitationProbabilityAbove],
 * [ClothesRule.RainCode], [ClothesRule.AnyOf]), which carry no
 * temperature.
 */
fun ClothesRule.thresholdC(): Double? = when (val c = condition) {
    is ClothesRule.TemperatureBelow -> c.value.fromUnit(c.unit)
    is ClothesRule.TemperatureAbove -> c.value.fromUnit(c.unit)
    is ClothesRule.PrecipitationProbabilityAbove -> null
    // Precip-keyed conditions carry no temperature; the rationale / fallback-range
    // callers drop them (a composite rain rule has no single °C cutoff to show).
    is ClothesRule.RainCode -> null
    is ClothesRule.AnyOf -> null
}

