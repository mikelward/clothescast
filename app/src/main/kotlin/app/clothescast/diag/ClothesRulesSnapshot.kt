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
    val shortsDeltaC: String,
) {
    companion object {
        const val MISSING = "MISSING"

        /** Largest absolute integer Celsius delta reported verbatim; beyond this saturates. */
        const val DELTA_CLAMP_C: Int = 5

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
                if (userRule == null) MISSING else deltaBucket(userRule, defaultRule)
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
                shortsDeltaC = perCategory["shorts"] ?: MISSING,
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

        private fun String.takeCategories(): String =
            if (length <= CATEGORIES_MAX_LEN) this else take(CATEGORIES_MAX_LEN)
    }
}
