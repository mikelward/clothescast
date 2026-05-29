package app.clothescast.core.domain.model

import java.time.LocalTime

/**
 * A glanceable two-piece outfit pairing — one [Top] and one [Bottom] — derived from the
 * forecast so the home screen can render two big icons instead of a comma-separated word
 * list.
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
 * [Bottom.JEANS]; otherwise the user's chosen
 * `defaultBottom` ([Bottom.LONG_PANTS] by default). The Settings "If no rules match"
 * card lets the user point each fallback at any catalog garment so the home-screen
 * icon matches what they actually wear when no rule fires.
 *
 * Rule conditions are checked against feels-like temperatures (wind chill / humidity
 * adjusted) — that's what people experience on the way out the door, and it's already
 * what [ClothesRule.appliesTo] does.
 */
data class OutfitSuggestion(
    val top: Top,
    val bottom: Bottom,
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
        // LONG_SKIRT, jeans → JEANS, fallback → user's chosen defaultBottom
        // (LONG_PANTS by default). Within the skirt family, the shorter
        // garment is checked first so that when both a `short-skirt` and a
        // `skirt` rule fire on the same warm day, the mini icon wins.
        private val THICK_JACKET_KEYS = listOf("jacket")
        private val THICK_COAT_KEYS = listOf("coat")
        private val PUFFER_JACKET_KEYS = listOf("puffer")
        private val THIN_JACKET_KEYS = listOf("thin-jacket")
        private val SWEATER_KEYS = listOf("sweater", "hoodie")
        private val POLO_KEYS = listOf("polo")
        private val TSHIRT_KEYS = listOf("t-shirt")
        private val SHORTS_KEYS = listOf("shorts")
        private val SHORT_SKIRT_KEYS = listOf("short-skirt")
        private val LONG_SKIRT_KEYS = listOf("skirt")
        private val JEANS_KEYS = listOf("jeans")

        // TODO(outfit-from-triggered): fromForecast re-walks the raw clothesRules
        //   against the forecast (firstFiring per icon tier), duplicating the rule
        //   evaluation EvaluateClothesRules already did to build TriggeredOutfit and
        //   encoding the coat>puffer>jacket>… priority a second time alongside
        //   Garment.layerReduce. Derive OutfitSuggestion from the already-computed
        //   TriggeredOutfit instead, so there's a single rule evaluation and one
        //   priority order. Watch the icon-only tiers (the explicit t-shirt tier;
        //   shorter-skirt-wins) and the defaultTop/defaultBottom vs fallbacks
        //   mapping; home-screen icons are Roborazzi-snapshotted, so diff them.
        fun fromForecast(
            forecast: DailyForecast,
            clothesRules: List<ClothesRule>,
            defaultBottom: Bottom = Bottom.LONG_PANTS,
            defaultTop: Top = Top.TSHIRT,
        ): OutfitSuggestion {
            val top = when {
                clothesRules.firstFiring(forecast, THICK_COAT_KEYS) != null -> Top.THICK_COAT
                clothesRules.firstFiring(forecast, PUFFER_JACKET_KEYS) != null -> Top.PUFFER_JACKET
                clothesRules.firstFiring(forecast, THICK_JACKET_KEYS) != null -> Top.THICK_JACKET
                clothesRules.firstFiring(forecast, THIN_JACKET_KEYS) != null -> Top.THIN_JACKET
                clothesRules.firstFiring(forecast, SWEATER_KEYS) != null -> Top.SWEATER
                clothesRules.firstFiring(forecast, POLO_KEYS) != null -> Top.POLO
                clothesRules.firstFiring(forecast, TSHIRT_KEYS) != null -> Top.TSHIRT
                else -> defaultTop
            }
            val bottom = when {
                clothesRules.firstFiring(forecast, SHORTS_KEYS) != null -> Bottom.SHORTS
                clothesRules.firstFiring(forecast, SHORT_SKIRT_KEYS) != null -> Bottom.SHORT_SKIRT
                clothesRules.firstFiring(forecast, LONG_SKIRT_KEYS) != null -> Bottom.LONG_SKIRT
                clothesRules.firstFiring(forecast, JEANS_KEYS) != null -> Bottom.JEANS
                else -> defaultBottom
            }
            return OutfitSuggestion(top, bottom)
        }

        /**
         * Returns the human-readable reasons that [fromForecast] picked this outfit —
         * one [GarmentReason] for the top slot and one for the bottom. The "Why this
         * outfit?" sheet on the home screen uses this to surface the deciding numbers
         * (and the time of the deciding hour) so the user can sanity-check the call.
         *
         * Each fact's [Fact.ruleItem] points at the `ClothesRule.item` key that
         * supplied the threshold — the rationale dialog uses that to wire its
         * `−1° / +1°` controls back to the right rule.
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
                ?: ClothesRule.DEFAULTS.first { it.item == "sweater" }
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
                ?: tempRules.firstByKey(SHORTS_KEYS)
                ?: tempRules.firstByKey(SHORT_SKIRT_KEYS)
                ?: tempRules.firstByKey(LONG_SKIRT_KEYS)
                ?: tempRules.firstByKey(JEANS_KEYS)
                ?: ClothesRule.DEFAULTS.first { it.item == "shorts" }
            return rule.toConditionFact(forecast, coldestHour, warmestHour)
        }

        private fun List<ClothesRule>.firstFiring(
            forecast: DailyForecast,
            keys: List<String>,
        ): ClothesRule? = keys.firstNotNullOfOrNull { key ->
            firstOrNull { it.matchesKey(key) && it.appliesTo(forecast) }
        }

        private fun List<ClothesRule>.firstByKey(keys: List<String>): ClothesRule? =
            keys.firstNotNullOfOrNull { key -> firstOrNull { it.matchesKey(key) } }

        /**
         * Whether a rule names the catalog garment [key], canonicalised the
         * same way the prose path canonicalises it. [Garment.fromKey] folds
         * the supported legacy spellings and case / whitespace variants
         * ("tshirt" / " T-Shirt " → t-shirt, "jumper" → sweater) onto their
         * catalog [Garment.itemKey], so a stored legacy rule drives the icon
         * tier it already drives in the bullets — matching on the raw
         * `item` string would leave the icon on the default while the prose
         * named the garment. Falls back to a trimmed, case-insensitive
         * compare for items outside the catalog so nothing regresses.
         */
        private fun ClothesRule.matchesKey(key: String): Boolean {
            Garment.fromKey(item)?.let { return it.itemKey == key }
            return item.trim().equals(key, ignoreCase = true)
        }

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
 * [ruleItem] names *which* [ClothesRule] this fact came from (by its `item` key), so
 * the rationale dialog can wire its `−1°` / `+1°` buttons back to the same rule and
 * persist user adjustments to the right entry on disk.
 */
data class Fact(
    val metric: Metric,
    val observedC: Double,
    val observedAt: LocalTime?,
    val thresholdC: Double,
    val ruleItem: String,
    val comparison: Comparison,
) {
    enum class Metric { FEELS_LIKE_MIN, FEELS_LIKE_MAX }

    /** How [observedC] relates to [thresholdC]. */
    enum class Comparison { BELOW, AT_OR_ABOVE }
}
