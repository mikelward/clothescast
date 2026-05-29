package app.clothescast.core.domain.model

/**
 * The temperature window in which the user's "If no rules match" fallback would
 * actually apply — i.e. when no top or bottom rule fires. Bounds are in Celsius
 * (the canonical unit; the Settings UI converts at render time via
 * [TemperatureUnit]) and inclusive on the fallback's side: at `lowerC` and at
 * `upperC` the fallback is what gets picked.
 *
 *  - `lowerC` is set when at least one [ClothesRule.TemperatureBelow] gates the
 *    same tier — the fallback can't appear until the temperature climbs to the
 *    *warmest* such threshold.
 *  - `upperC` is set when at least one [ClothesRule.TemperatureAbove] gates the
 *    same tier — the fallback can't appear past the *coldest* such threshold.
 *  - Both null means no temperature-keyed tier rules exist; the fallback applies
 *    at every temperature (the Settings row omits the secondary description in
 *    that case).
 *  - [empty] is true when the bounds invert — rules cover every realistic
 *    temperature and the fallback would never appear. The Settings row renders
 *    "never" so the user sees their picker selection is shadowed.
 */
data class FallbackRange(val lowerC: Double? = null, val upperC: Double? = null) {
    val empty: Boolean get() = lowerC != null && upperC != null && lowerC > upperC
}

/** Which slot (top vs bottom) to compute the fallback range for. */
enum class FallbackTier { TOP, BOTTOM }

/**
 * Computes the temperature range where the [tier]'s fallback would apply,
 * given the user's current [rules] list. Precipitation rules are ignored —
 * they don't constrain the fallback in temperature space.
 *
 * Only rules whose item belongs to the tier's slot count (resolved via
 * [Garment.fromKey], so legacy `"Jacket"` / `" jacket "` items normalize
 * the same as `"jacket"`); a `sweater` rule narrows the top fallback, but
 * a `shorts` rule doesn't.
 */
fun fallbackRange(rules: List<ClothesRule>, tier: FallbackTier): FallbackRange {
    val expectedSlot = when (tier) {
        FallbackTier.TOP -> Garment.Slot.TOP
        FallbackTier.BOTTOM -> Garment.Slot.BOTTOM
    }
    var lower: Double? = null
    var upper: Double? = null
    for (rule in rules) {
        if (rule.item.slot != expectedSlot) continue
        val threshold = rule.thresholdC() ?: continue
        when (rule.condition) {
            is ClothesRule.TemperatureBelow -> {
                if (lower == null || threshold > lower) lower = threshold
            }
            is ClothesRule.TemperatureAbove -> {
                if (upper == null || threshold < upper) upper = threshold
            }
            is ClothesRule.PrecipitationProbabilityAbove -> Unit
        }
    }
    return FallbackRange(lowerC = lower, upperC = upper)
}
