package app.clothescast.core.domain.model

import java.time.LocalTime

/**
 * A glanceable outfit pairing — one [Top] and one [Bottom], plus an optional [Hands]
 * accessory and an optional [Carried] accessory (umbrella) — derived from the forecast
 * so the home screen can render big icons instead of a comma-separated word list.
 *
 * The picker is driven entirely by the user's [ClothesRule] list — the same rules that
 * populate [Insight.recommendedItems]. Top tier (coldest first): a firing `jacket` rule
 * promotes to [Top.THICK_JACKET]; a firing `coat` rule promotes to [Top.THICK_COAT]; a
 * firing `sweater`/`hoodie` rule promotes to [Top.SWEATER]; a firing `polo` rule lands
 * on [Top.POLO]; a firing `t-shirt` rule lands on [Top.TSHIRT]; otherwise the user's
 * chosen `defaultTop` ([Top.TSHIRT] by default). The explicit `t-shirt` tier keeps the
 * icon in step with the prose — [EvaluateClothesRules] already treats a firing `t-shirt`
 * rule as a matched top, so without it the bullet text would name a t-shirt while the
 * icon fell through to whatever `defaultTop` the user picked (e.g. POLO).
 * Bottom tier: a firing `shorts` rule picks [Bottom.SHORTS]; `short-skirt` picks
 * [Bottom.SHORT_SKIRT]; `skirt` picks [Bottom.LONG_SKIRT]; `jeans` picks
 * [Bottom.JEANS]; `pants` picks [Bottom.LONG_PANTS]; otherwise the user's chosen
 * `defaultBottom` ([Bottom.LONG_PANTS] by default). The explicit `pants` tier
 * mirrors the `t-shirt` one: [EvaluateClothesRules] treats a firing `pants` rule
 * as a matched bottom, so without it the prose would name pants while the icon
 * fell through to whatever `defaultBottom` the user picked (e.g. SHORTS). The Settings "If no rules match"
 * card lets the user point each fallback at any catalog garment so the home-screen
 * icon matches what they actually wear when no rule fires.
 *
 * [hands] is the odd one out: it is **nullable and has no default**. Unlike a top or a
 * bottom — which the user always wears, so an uncovered slot falls through to a sensible
 * default icon — extremity gear is opt-in. A firing `gloves` rule sets [hands] to
 * [Hands.GLOVES]; with no firing hands rule it stays null and no glove icon is shown.
 * That mirrors the prose, which surfaces gloves only as an "extra cold" escalation and
 * never recommends an accessory the user didn't ask for (see the comment on
 * [ClothesRule.DEFAULTS]).
 *
 * Rule conditions are checked against feels-like temperatures (wind chill / humidity
 * adjusted) — that's what people experience on the way out the door, and it's already
 * what [ClothesRule.appliesTo] does.
 *
 * Note: [top], [bottom], and [hands] (gloves) render on every icon surface
 * (home-screen card, widget, notification, Nest-Hub cast card). [outer] (rain
 * jacket) is an additional shell painted over the top icon at the same
 * footprint (like the gloves overlay), so it also rides every surface — it
 * stacks on top of whatever warmth tier the rules pick rather than replacing
 * it. [carried] (umbrella) renders as a full-figure overlay — held at the hip
 * and hanging straight down beside the legs — on the surfaces that show the
 * whole figure (Today cards, widget, cast card); the top-only notification
 * large icon omits it. On a cold rainy day the gloved hand reads as gripping
 * its crook. The [carried] and [outer] fills are user-recolorable via the same
 * per-row / GarmentColorsCard flow as the worn tiers (`outfit_carried_colors`
 * / `outfit_outer_colors`).
 */
data class OutfitSuggestion(
    val top: Top,
    val bottom: Bottom,
    val hands: Hands? = null,
    val carried: Carried? = null,
    val outer: Outer? = null,
) {
    enum class Top {
        TSHIRT, POLO, SWEATER, THIN_JACKET, THICK_JACKET, THICK_COAT, PUFFER_JACKET;

        /** Catalog key (matches [Garment.itemKey]) for prose / persistence. */
        fun itemKey(): String = when (this) {
            TSHIRT -> "t-shirt"
            POLO -> "polo"
            SWEATER -> "sweater"
            THIN_JACKET -> "thin-jacket"
            THICK_JACKET -> "jacket"
            THICK_COAT -> "coat"
            PUFFER_JACKET -> "puffer"
        }
    }

    // TODO: add a DRESS tier — a dress is a single-piece outfit so it'll need
    // either a new Top + Bottom pair (DRESS top / DRESS bottom) or a new
    // combined tier; settle the shape when we get there.
    enum class Bottom {
        SHORTS, SHORT_SKIRT, LONG_SKIRT, JEANS, LONG_PANTS;

        /** Catalog key (matches [Garment.itemKey]) for prose / persistence. */
        fun itemKey(): String = when (this) {
            SHORTS -> "shorts"
            SHORT_SKIRT -> "short-skirt"
            LONG_SKIRT -> "skirt"
            JEANS -> "jeans"
            LONG_PANTS -> "pants"
        }
    }

    /**
     * Optional extremity gear worn alongside the top/bottom stack — today just
     * gloves, the `Garment.Slot.HANDS` member. A single tier rather than a
     * default-bearing slot: it's present only when a hands rule fires (see the
     * `hands` note on [OutfitSuggestion]), so there's no "fallback hands" the
     * way there's a `defaultTop` / `defaultBottom`.
     */
    enum class Hands {
        GLOVES;

        /** Catalog key (matches [Garment.itemKey]) for prose / persistence. */
        fun itemKey(): String = when (this) {
            GLOVES -> "gloves"
        }
    }

    /**
     * Optional carried accessory shown alongside the worn outfit — today just
     * the umbrella, the `Garment.Slot.CARRIED` member. Like [hands] it's a
     * single tier with no fallback: present only when a carried-accessory rule
     * fires (the rain-keyed umbrella rule, which ships as a precip default — see
     * [ClothesRule.DEFAULTS]). It's independent of [hands], so a cold rainy day
     * can light both the glove icon and the umbrella.
     */
    enum class Carried {
        UMBRELLA;

        /** Catalog key (matches [Garment.itemKey]) for prose / persistence. */
        fun itemKey(): String = when (this) {
            UMBRELLA -> "umbrella"
        }
    }

    /**
     * Optional outer layer worn *over* the chosen [top] — today just the
     * rain jacket, the `Garment.Slot.TOP` member in `Garment.Layer.OUTER`.
     * Unlike a [Top] tier it doesn't replace what the warmth rules pick: it's
     * an additional shell painted over the top icon (the way [hands] gloves
     * overlay the top), present only when an outer-layer rule fires. Off by
     * default — no rule ships for it (see [ClothesRule.DEFAULTS]) — so it
     * stays null unless the user adds a rain-jacket rule. Its fill is
     * user-recolorable via the same per-row / GarmentColorsCard flow as the
     * worn tiers (`outfit_outer_colors`); it ships yellow by default.
     */
    enum class Outer {
        RAIN_JACKET;

        /** Catalog key (matches [Garment.itemKey]) for prose / persistence. */
        fun itemKey(): String = when (this) {
            RAIN_JACKET -> "rain-jacket"
        }
    }

    companion object {
        // Catalog item keys (see [Garment.itemKey]) that drive each icon tier.
        // Top tiers (coldest first): coat → THICK_COAT, puffer → PUFFER_JACKET,
        // jacket → THICK_JACKET, thin-jacket/sweater/hoodie → mid-layer, polo → POLO,
        // t-shirt → TSHIRT, fallback → user's chosen defaultTop (TSHIRT by default).
        // (`shirt` has no icon tier yet, so a firing shirt rule still falls to
        // the default — add a Top.SHIRT + drawable when the catalog grows one.)
        // Colder tiers are checked before warmer ones so that when multiple rules fire
        // simultaneously (e.g. both coat ≤4°C and jacket ≤10°C fire at 3°C),
        // the heavier garment icon wins rather than the first match.
        // Bottom tiers: shorts → SHORTS, short-skirt → SHORT_SKIRT, skirt →
        // LONG_SKIRT, jeans → JEANS, pants → LONG_PANTS, fallback → user's
        // chosen defaultBottom (LONG_PANTS by default). Within the skirt
        // family, the shorter garment is checked first so that when both a
        // `short-skirt` and a `skirt` rule fire on the same warm day, the
        // mini icon wins.
        private val THICK_JACKET_KEYS = listOf(Garment.JACKET)
        private val THICK_COAT_KEYS = listOf(Garment.COAT)
        private val PUFFER_JACKET_KEYS = listOf(Garment.PUFFER)
        private val THIN_JACKET_KEYS = listOf(Garment.THIN_JACKET)
        private val SWEATER_KEYS = listOf(Garment.SWEATER, Garment.HOODIE)
        private val POLO_KEYS = listOf(Garment.POLO)
        private val TSHIRT_KEYS = listOf(Garment.TSHIRT)
        private val SHORTS_KEYS = listOf(Garment.SHORTS)
        private val SHORT_SKIRT_KEYS = listOf(Garment.SHORT_SKIRT)
        private val LONG_SKIRT_KEYS = listOf(Garment.SKIRT)
        private val JEANS_KEYS = listOf(Garment.JEANS)
        private val LONG_PANTS_KEYS = listOf(Garment.PANTS)
        // Hands tier: a firing `gloves` rule lights the optional glove icon.
        // No fallback tier — uncovered hands stay null (see the `hands` note on
        // OutfitSuggestion), so this never promotes to a default the way the top
        // and bottom slots do.
        private val GLOVES_KEYS = listOf(Garment.GLOVES)
        // Carried tier: a firing `umbrella` rule lights the optional umbrella
        // icon. Like the hands tier it has no fallback — uncovered stays null.
        private val CARRIED_KEYS = listOf(Garment.UMBRELLA)
        // Outer tier: a firing `rain-jacket` rule lights the optional outer
        // shell painted over the top. Like hands / carried it has no fallback
        // — uncovered stays null (off by default; the user opts in by adding
        // a rain-jacket rule).
        private val OUTER_KEYS = listOf(Garment.RAIN_JACKET)

        /**
         * Two-piece icon outfit for [forecast] given the user's [clothesRules].
         * Evaluates the rules against the forecast once and maps the firing
         * rules to icon tiers via [pickOutfit].
         *
         * Prefer [fromTriggeredOutfit] at call sites that already hold the day's
         * [TriggeredOutfit] (the prose / recommendations path): reading the icon
         * from the same matched-rule set the prose uses keeps the two from
         * drifting apart. This forecast-driven entry point stays for callers
         * (and tests) that only have a forecast and rules in hand.
         */
        fun fromForecast(
            forecast: DailyForecast,
            clothesRules: List<ClothesRule>,
            defaultBottom: Bottom = Bottom.LONG_PANTS,
            defaultTop: Top = Top.TSHIRT,
        ): OutfitSuggestion =
            pickOutfit(
                // Mirror EvaluateClothesRules' carried-accessory gate: the
                // umbrella surfaces once its probability gate clears unless the
                // precipitation is frozen (snow), so the next-period icon agrees
                // with the primary path. See [isFrozenPrecipitation] for why this
                // excludes only snow rather than requiring a wet weather code.
                clothesRules.filter {
                    it.appliesTo(forecast) &&
                        !(it.item.slot == Garment.Slot.CARRIED && forecast.condition.isFrozenPrecipitation())
                },
                defaultTop,
                defaultBottom,
            )

        /**
         * Two-piece icon outfit derived from an already-evaluated
         * [TriggeredOutfit] — the same object that drives the bulleted
         * recommendations and the prose clothes clause. Only the firing
         * threshold rules ([TriggeredOutfit.rules]) drive the tier; uncovered
         * slots fall through to [defaultTop] / [defaultBottom], exactly as
         * [fromForecast] does (the per-tier default is not itself an icon tier).
         *
         * This is the preferred entry point: a new garment tier or a
         * rule-matching tweak then lands in one place ([pickOutfit]) instead of
         * having to be mirrored across a second forecast walk — the divergence
         * that previously let the icon fall to the default while the prose named
         * the garment.
         */
        fun fromTriggeredOutfit(
            triggered: TriggeredOutfit,
            defaultBottom: Bottom = Bottom.LONG_PANTS,
            defaultTop: Top = Top.TSHIRT,
        ): OutfitSuggestion = pickOutfit(triggered.rules, defaultTop, defaultBottom)

        // Maps the firing rules (those already known to apply to the forecast)
        // to icon tiers. The single source of the coldest-first / shorter-first
        // tier priority both entry points share: colder (or briefer) tiers are
        // checked first so the heavier garment / mini skirt wins when several
        // rules fire at once.
        private fun pickOutfit(
            firingRules: List<ClothesRule>,
            defaultTop: Top,
            defaultBottom: Bottom,
        ): OutfitSuggestion {
            val top = when {
                firingRules.hasKeyIn(THICK_COAT_KEYS) -> Top.THICK_COAT
                firingRules.hasKeyIn(PUFFER_JACKET_KEYS) -> Top.PUFFER_JACKET
                firingRules.hasKeyIn(THICK_JACKET_KEYS) -> Top.THICK_JACKET
                firingRules.hasKeyIn(THIN_JACKET_KEYS) -> Top.THIN_JACKET
                firingRules.hasKeyIn(SWEATER_KEYS) -> Top.SWEATER
                firingRules.hasKeyIn(POLO_KEYS) -> Top.POLO
                firingRules.hasKeyIn(TSHIRT_KEYS) -> Top.TSHIRT
                else -> defaultTop
            }
            val bottom = when {
                firingRules.hasKeyIn(SHORTS_KEYS) -> Bottom.SHORTS
                firingRules.hasKeyIn(SHORT_SKIRT_KEYS) -> Bottom.SHORT_SKIRT
                firingRules.hasKeyIn(LONG_SKIRT_KEYS) -> Bottom.LONG_SKIRT
                firingRules.hasKeyIn(JEANS_KEYS) -> Bottom.JEANS
                firingRules.hasKeyIn(LONG_PANTS_KEYS) -> Bottom.LONG_PANTS
                else -> defaultBottom
            }
            // Optional, no fallback: null unless a hands rule actually fired.
            val hands = if (firingRules.hasKeyIn(GLOVES_KEYS)) Hands.GLOVES else null
            // Same shape for carried gear (umbrella): independent of hands.
            val carried = if (firingRules.hasKeyIn(CARRIED_KEYS)) Carried.UMBRELLA else null
            // Outer shell (rain jacket) worn over the picked top: independent
            // of the top tier, so it adds to the stack rather than replacing it.
            val outer = if (firingRules.hasKeyIn(OUTER_KEYS)) Outer.RAIN_JACKET else null
            return OutfitSuggestion(top, bottom, hands, carried, outer)
        }

        /** True when any rule in the list is keyed on a garment in [keys]. */
        private fun List<ClothesRule>.hasKeyIn(keys: List<Garment>): Boolean =
            keys.any { garment -> any { it.item == garment } }

        /**
         * Returns the human-readable reasons that [fromForecast] picked this outfit —
         * one [GarmentReason] for the top slot and one for the bottom. The "Why this
         * outfit?" sheet on the home screen uses this to surface the deciding numbers
         * (and the time of the deciding hour) so the user can sanity-check the call.
         *
         * Each fact's [Fact.ruleItem] is the [Garment] of the [ClothesRule] that
         * supplied the threshold, so the rationale can match it back to the rule.
         */
        fun explainFromForecast(
            forecast: DailyForecast,
            clothesRules: List<ClothesRule>,
        ): OutfitRationale {
            val coldestHour = forecast.hourly.minByOrNull { it.feelsLikeC }?.time
            val warmestHour = forecast.hourly.maxByOrNull { it.feelsLikeC }?.time
            return OutfitRationale(
                top = GarmentReason(facts = listOf(topFact(forecast, clothesRules, coldestHour, warmestHour))),
                bottom = GarmentReason(facts = listOf(bottomFact(forecast, clothesRules, coldestHour, warmestHour))),
            )
        }

        private fun topFact(
            forecast: DailyForecast,
            rules: List<ClothesRule>,
            coldestHour: LocalTime?,
            warmestHour: LocalTime?,
        ): Fact {
            // Only temperature rules can produce a fact — it needs a threshold
            // and an observed temperature to compare against. A user may key any
            // garment (a top included) off precipitation instead; such a rule
            // can fire and drive the icon, but it has no temperature threshold,
            // so exclude it from the rationale's deciding-rule search and fall
            // through to the nearest temperature rule (or the catalog default)
            // rather than crash in toFact.
            val tempRules = rules.temperatureRules()
            // The deciding rule, in priority order across all top tiers. If no
            // cold rule fires (TSHIRT/POLO), cite the sweater threshold that
            // wasn't crossed so the rationale dialog still has something to show.
            val rule = tempRules.firstFiring(forecast, THICK_COAT_KEYS)
                ?: tempRules.firstFiring(forecast, PUFFER_JACKET_KEYS)
                ?: tempRules.firstFiring(forecast, THICK_JACKET_KEYS)
                ?: tempRules.firstFiring(forecast, THIN_JACKET_KEYS)
                ?: tempRules.firstFiring(forecast, SWEATER_KEYS)
                ?: tempRules.firstFiring(forecast, POLO_KEYS)
                ?: tempRules.firstFiring(forecast, TSHIRT_KEYS)
                ?: tempRules.firstByKey(SWEATER_KEYS)
                ?: tempRules.firstByKey(THIN_JACKET_KEYS)
                ?: tempRules.firstByKey(THICK_JACKET_KEYS)
                ?: tempRules.firstByKey(THICK_COAT_KEYS)
                ?: tempRules.firstByKey(PUFFER_JACKET_KEYS)
                ?: ClothesRule.DEFAULTS.first { it.item == Garment.SWEATER }
            return rule.toConditionFact(forecast, coldestHour, warmestHour)
        }

        private fun bottomFact(
            forecast: DailyForecast,
            rules: List<ClothesRule>,
            coldestHour: LocalTime?,
            warmestHour: LocalTime?,
        ): Fact {
            // Temperature rules only — a precipitation-keyed bottom rule can
            // fire and drive the icon but has no threshold to explain, so skip
            // it here (see [topFact]) rather than crash in toFact.
            val tempRules = rules.temperatureRules()
            // The deciding rule across all bottom tiers. If no warm rule fires
            // (LONG_PANTS), cite the shorts threshold that wasn't crossed.
            val rule = tempRules.firstFiring(forecast, SHORTS_KEYS)
                ?: tempRules.firstFiring(forecast, SHORT_SKIRT_KEYS)
                ?: tempRules.firstFiring(forecast, LONG_SKIRT_KEYS)
                ?: tempRules.firstFiring(forecast, JEANS_KEYS)
                ?: tempRules.firstFiring(forecast, LONG_PANTS_KEYS)
                ?: tempRules.firstByKey(SHORTS_KEYS)
                ?: tempRules.firstByKey(SHORT_SKIRT_KEYS)
                ?: tempRules.firstByKey(LONG_SKIRT_KEYS)
                ?: tempRules.firstByKey(JEANS_KEYS)
                ?: tempRules.firstByKey(LONG_PANTS_KEYS)
                ?: ClothesRule.DEFAULTS.first { it.item == Garment.SHORTS }
            return rule.toConditionFact(forecast, coldestHour, warmestHour)
        }

        // Walks [keys] in priority order (warmest tier first) and returns the
        // first rule keyed on one of them that fires for the forecast.
        private fun List<ClothesRule>.firstFiring(
            forecast: DailyForecast,
            keys: List<Garment>,
        ): ClothesRule? = keys.firstNotNullOfOrNull { garment ->
            firstOrNull { it.item == garment && it.appliesTo(forecast) }
        }

        private fun List<ClothesRule>.firstByKey(keys: List<Garment>): ClothesRule? =
            keys.firstNotNullOfOrNull { garment -> firstOrNull { it.item == garment } }

        /** The rules with a Celsius threshold — i.e. those the rationale can
         *  turn into a [Fact]. Drops precipitation-keyed rules, whose
         *  [thresholdC] is null. */
        private fun List<ClothesRule>.temperatureRules(): List<ClothesRule> =
            filter { it.thresholdC() != null }

        private fun ClothesRule.toFact(
            metric: Fact.Metric,
            observedC: Double,
            observedAt: LocalTime?,
        ): Fact {
            val thresholdC = thresholdC()
                ?: error("Outfit rationale only supports temperature rules; got $condition")
            return Fact(
                metric = metric,
                observedC = observedC,
                observedAt = observedAt,
                thresholdC = thresholdC,
                ruleItem = item,
                comparison = if (observedC < thresholdC) {
                    Fact.Comparison.BELOW
                } else {
                    Fact.Comparison.AT_OR_ABOVE
                },
            )
        }

        private fun ClothesRule.toMinFact(forecast: DailyForecast, observedAt: LocalTime?): Fact =
            toFact(Fact.Metric.FEELS_LIKE_MIN, forecast.feelsLikeMinC, observedAt)

        private fun ClothesRule.toMaxFact(forecast: DailyForecast, observedAt: LocalTime?): Fact =
            toFact(Fact.Metric.FEELS_LIKE_MAX, forecast.feelsLikeMaxC, observedAt)

        /**
         * Builds the rationale [Fact] from the rule's *condition*, not its
         * slot. A [ClothesRule.TemperatureAbove] rule fires on the day's high,
         * so it compares against the feels-like max at the warmest hour; a
         * [ClothesRule.TemperatureBelow] rule fires on the day's low, so it
         * compares against the feels-like min at the coldest hour. This matters
         * for warm base-layer tops (a `t-shirt`/`polo` rule configured as
         * `> N°C` lives in the top slot but keys off the high): a slot-fixed
         * metric would explain a firing warm top against the day's minimum and
         * report its threshold as uncrossed even though it drove the icon — and
         * the dialog's ±1° controls would attach to that misleading fact.
         */
        private fun ClothesRule.toConditionFact(
            forecast: DailyForecast,
            coldestHour: LocalTime?,
            warmestHour: LocalTime?,
        ): Fact = when (condition) {
            is ClothesRule.TemperatureAbove -> toMaxFact(forecast, warmestHour)
            // TemperatureBelow keys off the low; the precipitation case is
            // unreachable here (toFact rejects non-temperature rules) but falls
            // through to the low as a harmless default.
            else -> toMinFact(forecast, coldestHour)
        }
    }
}

/**
 * Why a particular [OutfitSuggestion] was picked. The UI renders this as bulleted text
 * under the garment icons on the "Why this outfit?" sheet.
 */
data class OutfitRationale(
    val top: GarmentReason,
    val bottom: GarmentReason,
)

/** Reasons for a single garment slot. One [Fact] per slot in the current picker. */
data class GarmentReason(
    val facts: List<Fact>,
)

/**
 * One observation-vs-threshold check. [observedAt] is null when the forecast was a
 * day-level aggregate without hourly entries (legacy fixtures, sparse caches) — the
 * UI omits the time clause in that case.
 *
 * [ruleItem] names *which* [ClothesRule] this fact came from (by its [Garment] item), so
 * the rationale dialog can wire its `−1°` / `+1°` buttons back to the same rule and
 * persist user adjustments to the right entry on disk.
 */
data class Fact(
    val metric: Metric,
    val observedC: Double,
    val observedAt: LocalTime?,
    val thresholdC: Double,
    val ruleItem: Garment,
    val comparison: Comparison,
) {
    enum class Metric { FEELS_LIKE_MIN, FEELS_LIKE_MAX }

    /** How [observedC] relates to [thresholdC]. */
    enum class Comparison { BELOW, AT_OR_ABOVE }
}
