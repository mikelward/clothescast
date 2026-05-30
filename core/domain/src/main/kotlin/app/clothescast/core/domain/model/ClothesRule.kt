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

    data class PrecipitationProbabilityAbove(val percent: Double) : Condition {
        override fun matches(forecast: DailyForecast) = forecast.precipitationProbabilityMaxPct > percent
    }

    companion object {
        // Item keys are en-US-flavoured ("sweater", not "jumper") so they
        // align with the `today_outfit_top_*` labels in values/strings.xml.
        // Per-language phrasers translate at format time
        // (e.g. GermanClothesPhraser maps "sweater" → "Pullover").
        // Wet-weather accessories live on the format option [RainAccessory]
        // instead of [DEFAULTS]: NONE preserves the historical silence on
        // umbrellas, UMBRELLA opts in to "bring an umbrella" alongside the
        // existing rain mention. We still ship no precip-keyed default
        // because "bring an umbrella" is the wrong answer for users who'd
        // reach for a rain jacket or hood instead.
        // TODO(rain-accessory-variants): broaden [RainAccessory] beyond
        //  UMBRELLA (rain jacket, hood, rain boots, …) once the resource
        //  strings and per-locale phrasers cover them.
        val DEFAULTS: List<ClothesRule> = listOf(
            ClothesRule(Garment.SWEATER, TemperatureBelow(16.0)),
            ClothesRule(Garment.JACKET, TemperatureBelow(10.0)),
            ClothesRule(Garment.COAT, TemperatureBelow(4.0)),
            ClothesRule(Garment.GLOVES, TemperatureBelow(4.0)),
            ClothesRule(Garment.SHORTS, TemperatureAbove(23.0)),
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
 * which unit the user typed it in. Returns `null` for [ClothesRule.PrecipitationProbabilityAbove].
 */
fun ClothesRule.thresholdC(): Double? = when (val c = condition) {
    is ClothesRule.TemperatureBelow -> c.value.fromUnit(c.unit)
    is ClothesRule.TemperatureAbove -> c.value.fromUnit(c.unit)
    is ClothesRule.PrecipitationProbabilityAbove -> null
}

