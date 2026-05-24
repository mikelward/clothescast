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
 * on [Top.POLO]; otherwise the user's chosen `defaultTop` ([Top.TSHIRT] by default).
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
        // fallback → user's chosen defaultTop (TSHIRT by default). Colder tiers
        // are checked before warmer ones so that when multiple rules fire
        // simultaneously (e.g. both coat ≤6°C and jacket ≤12°C fire at 4°C),
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
        private val SHORTS_KEYS = listOf("shorts")
        private val SHORT_SKIRT_KEYS = listOf("short-skirt")
        private val LONG_SKIRT_KEYS = listOf("skirt")
        private val JEANS_KEYS = listOf("jeans")

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
                top = GarmentReason(facts = listOf(topFact(forecast, clothesRules, coldestHour))),
                bottom = GarmentReason(facts = listOf(bottomFact(forecast, clothesRules, warmestHour))),
            )
        }

        private fun topFact(
            forecast: DailyForecast,
            rules: List<ClothesRule>,
            coldestHour: LocalTime?,
        ): Fact {
            // The deciding rule, in priority order across all top tiers. If no
            // cold rule fires (TSHIRT/POLO), cite the sweater threshold that
            // wasn't crossed so the rationale dialog still has something to show.
            val rule = rules.firstFiring(forecast, THICK_COAT_KEYS)
                ?: rules.firstFiring(forecast, PUFFER_JACKET_KEYS)
                ?: rules.firstFiring(forecast, THICK_JACKET_KEYS)
                ?: rules.firstFiring(forecast, THIN_JACKET_KEYS)
                ?: rules.firstFiring(forecast, SWEATER_KEYS)
                ?: rules.firstFiring(forecast, POLO_KEYS)
                ?: rules.firstByKey(SWEATER_KEYS)
                ?: rules.firstByKey(THIN_JACKET_KEYS)
                ?: rules.firstByKey(THICK_JACKET_KEYS)
                ?: rules.firstByKey(THICK_COAT_KEYS)
                ?: rules.firstByKey(PUFFER_JACKET_KEYS)
                ?: ClothesRule.DEFAULTS.first { it.item == "sweater" }
            return rule.toMinFact(forecast, coldestHour)
        }

        private fun bottomFact(
            forecast: DailyForecast,
            rules: List<ClothesRule>,
            warmestHour: LocalTime?,
        ): Fact {
            // The deciding rule across all bottom tiers. If no warm rule fires
            // (LONG_PANTS), cite the shorts threshold that wasn't crossed.
            val rule = rules.firstFiring(forecast, SHORTS_KEYS)
                ?: rules.firstFiring(forecast, SHORT_SKIRT_KEYS)
                ?: rules.firstFiring(forecast, LONG_SKIRT_KEYS)
                ?: rules.firstFiring(forecast, JEANS_KEYS)
                ?: rules.firstByKey(SHORTS_KEYS)
                ?: rules.firstByKey(SHORT_SKIRT_KEYS)
                ?: rules.firstByKey(LONG_SKIRT_KEYS)
                ?: rules.firstByKey(JEANS_KEYS)
                ?: ClothesRule.DEFAULTS.first { it.item == "shorts" }
            return rule.toMaxFact(forecast, warmestHour)
        }

        private fun List<ClothesRule>.firstFiring(
            forecast: DailyForecast,
            keys: List<String>,
        ): ClothesRule? = keys.firstNotNullOfOrNull { key ->
            firstOrNull { it.item == key && it.appliesTo(forecast) }
        }

        private fun List<ClothesRule>.firstByKey(keys: List<String>): ClothesRule? =
            keys.firstNotNullOfOrNull { key -> firstOrNull { it.item == key } }

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
