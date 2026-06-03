package app.clothescast.core.domain.model

/**
 * The clothes the user is "wearing today" given a forecast — the rules that
 * matched for each slot. The home-screen icon, the bulleted recommendations,
 * and the prose `clothes` clause all read from this so they agree on the same
 * outfit byte-for-byte.
 *
 * The user has two kinds of [ClothesRule]: *threshold* rules they configure
 * in Settings (sweater <16°C, shorts >23°C, …), and a *default* rule per
 * outfit tier — the "If no rules match" picker's [UserPreferences.defaultTop]
 * / [UserPreferences.defaultBottom] — that matches when no threshold rule in
 * the tier did. Both kinds are rules; the default's condition is just "no
 * threshold rule in my tier matched today" rather than a numeric threshold.
 *
 * [rules] is the subset of the user's threshold rules whose condition
 * matched, in input order — every firing rule, including within-layer
 * also-rans (e.g. when both `jacket` and `coat` fire on a very cold day).
 * Consumers that want a coherent outfit run [rules] through [Garment.layerReduce]
 * first; this class does that internally when building [items].
 *
 * [fallbacks] is the per-tier default rule's item key (e.g. `"t-shirt"`,
 * `"pants"`) for each slot the threshold rules left uncovered, top first
 * then bottom.
 *
 * The split is preserved (rather than a single flat items list) because the
 * evening-event tie-in's delta — "extra clothing the night needs that the
 * morning didn't already announce" — compares only threshold-rule matches on
 * each side. The per-tier default is the user's baseline outfit, not "extra"
 * for the evening, so folding it into the comparison would surface a spurious
 * "Bring a t-shirt tonight" whenever the morning matched a cold-weather
 * threshold rule and the night's tier resolved to the default top.
 */
data class TriggeredOutfit(
    val rules: List<ClothesRule>,
    val fallbacks: List<String>,
) {
    /**
     * Every item the user is wearing today, ordered tops → bottoms → unknown
     * (accessories, free-form). Tops always lead so the prose's article picker
     * applies to a singular top ("a t-shirt") rather than a leading bare-plural
     * bottom ("shorts"), and so a t-shirt sourced from the default fallback
     * reads the same as one sourced from a user rule — the user's mental
     * model is "what am I wearing", not "which clause produced it".
     *
     * Within each slot, reduced threshold matches keep their input order,
     * then defaults follow in the order [EvaluateClothesRules] queued
     * them. [Garment.layerReduce] picks at most one top per
     * [Garment.Layer] (dropping [Garment.Layer.BASE] when MID or SHELL
     * also fired) and at most one bottom (bottoms substitute rather than
     * stack), so the prose reads "Wear a sweater and jacket." rather
     * than "Wear a sweater, jacket, and a t-shirt.", and a user with
     * both `short-skirt > 22°C` and `skirt > 16°C` firing at 25°C reads
     * "Wear a short skirt." rather than "Wear a short skirt and skirt."
     */
    val items: List<String>
        get() {
            val raw = Garment.layerReduce(rules).map { it.item.itemKey } + fallbacks
            // Stable sort into reading order: warmth tops (and unclassified
            // free-form items) first, then the OUTER rain shell worn over them,
            // then accessories (gloves / umbrella), then bottoms last. Within a
            // group the input order is preserved (reduced rules, then defaults),
            // so only the cross-group order is imposed. This keeps a rain jacket
            // reading *after* the base top it layers over ("a t-shirt and a rain
            // jacket") rather than ahead of it when the base top comes from the
            // default fallback, and still floats bottoms to the end so a warm-day
            // "shorts rule + t-shirt default" reads "Wear a t-shirt and shorts."
            // Accessories are filtered out before prose rendering anyway, so
            // their exact slot only matters for the recommended-items list.
            return raw.sortedBy {
                val garment = Garment.fromKey(it)
                when {
                    garment == null -> 0
                    garment.slot == Garment.Slot.TOP && garment.layer == Garment.Layer.OUTER -> 1
                    garment.slot == Garment.Slot.TOP -> 0
                    garment.slot == Garment.Slot.BOTTOM -> 3
                    else -> 2
                }
            }
        }
}
