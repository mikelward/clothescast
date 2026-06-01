package app.clothescast.diag

import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.thresholdC
import kotlin.math.roundToInt

/**
 * Aggregate-analytics view of the user's clothes-rule customisation, intended
 * as the params of a Firebase Analytics `clothes_rules_snapshot` event.
 *
 * Per-category delta is the integer Celsius difference from the catalog's
 * default threshold ([ClothesRule.DEFAULTS]), clamped to ±[DELTA_CLAMP_C]°C
 * and prefixed with sign — `"0"`, `"+1"`, `"-3"`, `"+5+"` (saturated above
 * the clamp), `"-5-"` (saturated below). Categories the user has deleted
 * from their list report [MISSING].
 *
 * No raw thresholds and no per-rule values — just the delta bucket relative
 * to default, so the analytics stream stays aggregate per the PRIVACY.md
 * "aggregate counts only" line.
 */
data class ClothesRulesSnapshot(
    val customisedCount: Int,
    val extraRulesCount: Int,
    val categoriesCustomised: String,
    val allDefaults: Boolean,
    val sweaterDeltaC: String,
    val jacketDeltaC: String,
    val coatDeltaC: String,
    val glovesDeltaC: String,
    val shortsDeltaC: String,
    val umbrellaDeltaPct: String,
) {
    companion object {
        const val MISSING = "MISSING"

        /** Largest absolute integer Celsius delta reported verbatim; beyond this saturates. */
        const val DELTA_CLAMP_C: Int = 5

        /**
         * Largest absolute percentage-point delta reported for the precip-keyed
         * umbrella default; beyond this saturates. Wider than [DELTA_CLAMP_C]
         * because a probability gate spans 0–100%, but still bucketed (rounded to
         * the nearest [PCT_BUCKET]) so the aggregate stays coarse.
         */
        const val DELTA_CLAMP_PCT: Int = 50

        /** Rounding granularity for the umbrella percentage-point delta bucket. */
        private const val PCT_BUCKET: Int = 10

        /** Firebase Analytics caps event-param strings at 100 chars; this stays well clear. */
        private const val CATEGORIES_MAX_LEN = 36

        /**
         * Builds a snapshot from the user's current rule list against the catalog
         * defaults. The default-category set is [ClothesRule.DEFAULTS] keyed by
         * `item` — extra rules in [rules] beyond the defaults bump
         * [extraRulesCount] but don't appear individually.
         */
        fun from(rules: List<ClothesRule>): ClothesRulesSnapshot {
            val defaults = ClothesRule.DEFAULTS.associateBy { it.item.itemKey }
            val byItem = rules.associateBy { it.item.itemKey }
            val perCategory: Map<String, String> = defaults.mapValues { (item, defaultRule) ->
                val userRule = byItem[item]
                when {
                    userRule == null -> MISSING
                    // The umbrella default is precip-keyed (no Celsius threshold),
                    // so bucket its probability-gate delta separately — routing it
                    // through deltaBucket would always report "0" and hide every
                    // gate customisation. See precipDeltaBucket.
                    defaultRule.condition is ClothesRule.PrecipitationProbabilityAbove ->
                        precipDeltaBucket(userRule, defaultRule)
                    else -> deltaBucket(userRule, defaultRule)
                }
            }
            val customisedKeys = perCategory.entries
                .filter { it.value != "0" }
                .map { it.key }
                .sorted()
            val extras = rules.count { it.item.itemKey !in defaults }
            return ClothesRulesSnapshot(
                customisedCount = customisedKeys.size,
                extraRulesCount = extras,
                categoriesCustomised = customisedKeys.joinToString(",").takeCategories(),
                allDefaults = customisedKeys.isEmpty() && extras == 0,
                sweaterDeltaC = perCategory["sweater"] ?: MISSING,
                jacketDeltaC = perCategory["jacket"] ?: MISSING,
                coatDeltaC = perCategory["coat"] ?: MISSING,
                glovesDeltaC = perCategory["gloves"] ?: MISSING,
                shortsDeltaC = perCategory["shorts"] ?: MISSING,
                umbrellaDeltaPct = perCategory["umbrella"] ?: MISSING,
            )
        }

        /**
         * Integer °C delta of [userRule] from [defaultRule], formatted as a signed
         * bucket. Non-temperature rules (precipitation) and type-mismatch cases
         * report `"0"` only when both thresholds are absent — otherwise the bucket
         * reflects whichever side has a temperature. Default rules in the current
         * catalog are all temperature, so the type-mismatch arm is defensive.
         */
        private fun deltaBucket(userRule: ClothesRule, defaultRule: ClothesRule): String {
            val userC = userRule.thresholdC()
            val defaultC = defaultRule.thresholdC() ?: return if (userC == null) "0" else MISSING
            if (userC == null) return MISSING
            val raw = (userC - defaultC).roundToInt()
            return when {
                raw >= DELTA_CLAMP_C + 1 -> "+${DELTA_CLAMP_C}+"
                raw <= -(DELTA_CLAMP_C + 1) -> "-${DELTA_CLAMP_C}-"
                raw > 0 -> "+$raw"
                raw < 0 -> "$raw"
                else -> "0"
            }
        }

        /**
         * Signed percentage-point delta of a precip-keyed [userRule] from its
         * precip-keyed [defaultRule] (the umbrella's rain-probability gate),
         * rounded to the nearest [PCT_BUCKET] and clamped to ±[DELTA_CLAMP_PCT] —
         * `"0"`, `"+10"`, `"-20"`, `"+50+"` (saturated above), `"-50-"` (below).
         * A user rule that isn't precip-keyed (shouldn't happen — the umbrella
         * editor only writes precip conditions) reports [MISSING].
         */
        private fun precipDeltaBucket(userRule: ClothesRule, defaultRule: ClothesRule): String {
            val defaultPct = (defaultRule.condition as? ClothesRule.PrecipitationProbabilityAbove)?.percent
                ?: return MISSING
            val userPct = (userRule.condition as? ClothesRule.PrecipitationProbabilityAbove)?.percent
                ?: return MISSING
            val bucketed = ((userPct - defaultPct) / PCT_BUCKET).roundToInt() * PCT_BUCKET
            return when {
                bucketed >= DELTA_CLAMP_PCT + PCT_BUCKET -> "+${DELTA_CLAMP_PCT}+"
                bucketed <= -(DELTA_CLAMP_PCT + PCT_BUCKET) -> "-${DELTA_CLAMP_PCT}-"
                bucketed > 0 -> "+$bucketed"
                bucketed < 0 -> "$bucketed"
                else -> "0"
            }
        }

        private fun String.takeCategories(): String =
            if (length <= CATEGORIES_MAX_LEN) this else take(CATEGORIES_MAX_LEN)
    }
}
