package app.clothescast.core.domain.model

import java.util.Locale

/**
 * Catalog of garments the user can pick from when adding or editing a
 * [ClothesRule] in Settings. Each entry has a stable, en-US-flavoured
 * [itemKey] that the rule's `item` field stores — so a German user with the
 * "Sweater" rule still has `item = "sweater"` on disk, and the German phraser
 * translates to "Pullover" at render time.
 *
 * The catalog is intentionally finite: free-form garment names defeat
 * translation (the German phraser can't translate arbitrary user input), so
 * the editor UI only lets the user pick from this list. Today the list covers
 * tops and bottoms — headwear, accessories, and rain / sun gear can land in a
 * follow-up PR (and will likely come paired with their own outfit-card icons).
 *
 * Stays in `:core:domain` so the rule-evaluation tests and the future
 * `ClothesRule.item` migration to a typed field can reach it without pulling
 * Android in. Localised display labels live in `app/src/main/res/values/`
 * (and the per-locale `values-de/`, `values-en-rGB/`, `values-en-rAU/`);
 * `:app` owns the enum→resource mapping. Resource names are an
 * implementation detail there — they don't always mirror [itemKey] directly
 * (e.g. TSHIRT's "t-shirt" key is exposed as `garment_tshirt`, since hyphens
 * are illegal in Android resource names).
 */
enum class Garment(val itemKey: String, val slot: Slot, val layer: Layer? = null) {
    // Tops, coldest-leaning first. Sweater + jacket are shipped as defaults.
    // [layer] tags each top with where it sits in the layering order so the
    // rule engine can reduce overlapping firing rules to a stack that reads
    // like a real outfit ("sweater under a jacket"), not a flat list of
    // every match ("sweater, jacket, and t-shirt"). See [Layer].
    SWEATER("sweater", Slot.TOP, Layer.MID),
    HOODIE("hoodie", Slot.TOP, Layer.MID),
    JACKET("jacket", Slot.TOP, Layer.SHELL),
    COAT("coat", Slot.TOP, Layer.SHELL),
    PUFFER("puffer", Slot.TOP, Layer.SHELL),
    THIN_JACKET("thin-jacket", Slot.TOP, Layer.MID),
    TSHIRT("t-shirt", Slot.TOP, Layer.BASE),
    POLO("polo", Slot.TOP, Layer.BASE),
    SHIRT("shirt", Slot.TOP, Layer.BASE),

    // Bottoms. Shorts is shipped as a default; en-GB renders "pants" as "Trousers".
    // Bottoms substitute rather than stack today (you don't layer pants
    // over shorts), so [layer] is null.
    SHORTS("shorts", Slot.BOTTOM),
    SKIRT("skirt", Slot.BOTTOM),
    PANTS("pants", Slot.BOTTOM),
    JEANS("jeans", Slot.BOTTOM);

    /** Which outfit slot this garment occupies. Drives "does any matched rule
     *  cover this slot?" decisions in [EvaluateClothesRules] and "what
     *  temperature window does the fallback apply in?" in [FallbackRange],
     *  without those callers re-encoding the tier classification as string
     *  key sets. */
    enum class Slot { TOP, BOTTOM }

    /**
     * Where this garment sits in the layering order. The rule engine uses
     * this to collapse multiple firing top-tier rules into a coherent
     * stack:
     *  - At most one garment per layer (heaviest firing tier within the
     *    layer wins — picked via the priority order encoded in
     *    [OutfitSuggestion]'s key sets), and
     *  - the [BASE] layer is suppressed when [MID] or [SHELL] also fires,
     *    because the base is implicit when you're wearing something over
     *    it (no one says "wear a t-shirt under your coat" — they just
     *    name the coat).
     *
     * Bottoms substitute today (shorts vs. pants is an either-or, not a
     * stack), so they don't carry a layer.
     */
    enum class Layer { BASE, MID, SHELL }

    companion object {
        /**
         * Resolves a stored `ClothesRule.item` string back to a [Garment].
         * Tolerates a few common spelling variants ("tshirt", "trousers",
         * "jumper") so older rule data and any free-form items the user typed
         * before the catalog landed still map cleanly. Returns `null` for
         * anything else — callers that need to display unknown items should
         * fall back to the raw string.
         */
        fun fromKey(key: String): Garment? {
            val normalized = key.trim().lowercase(Locale.ROOT)
            entries.firstOrNull { it.itemKey == normalized }?.let { return it }
            return when (normalized) {
                "tshirt" -> TSHIRT
                "trousers", "long pants" -> PANTS
                "jumper" -> SWEATER
                else -> null
            }
        }

        /**
         * Within-layer priority for top garments — heaviest-tier first. When
         * multiple firing rules belong to the same [Layer], the one earliest
         * in this list wins and the rest are suppressed: if both a `jacket`
         * rule and a `coat` rule fire on the same cold day, the user is
         * wearing the coat that day, not both. Mirrors the cold-first
         * dispatch in [OutfitSuggestion.fromForecast] so the prose stack
         * (driven by the rule engine via [layerReduce]) and the home-screen
         * icon agree on which top tier "wins".
         *
         * Garments are listed in the order [OutfitSuggestion.fromForecast]
         * already encodes:
         *  - SHELL: COAT → PUFFER → JACKET
         *  - MID:   THIN_JACKET → SWEATER → HOODIE
         *  - BASE:  POLO → TSHIRT → SHIRT
         */
        private val TOP_LAYER_PRIORITY: Map<Layer, List<Garment>> = mapOf(
            Layer.SHELL to listOf(COAT, PUFFER, JACKET),
            Layer.MID to listOf(THIN_JACKET, SWEATER, HOODIE),
            Layer.BASE to listOf(POLO, TSHIRT, SHIRT),
        )

        /**
         * Filters a list of firing [ClothesRule]s down to a layered top
         * stack plus the rest (bottoms, accessories, unclassified items).
         * The "rest" passes through untouched and in input order; the top
         * stack picks at most one rule per [Layer] using the priority
         * encoded in [TOP_LAYER_PRIORITY], then drops [Layer.BASE]
         * entirely if anything in [Layer.MID] or [Layer.SHELL] also fired.
         *
         * Operates on `ClothesRule`s (not item strings) so callers that
         * still need the originating rule — for the rationale dialog, the
         * tie-in delta, etc. — can keep the linkage instead of round-
         * tripping through `Garment.fromKey`.
         *
         * Rule of thumb: the returned list, mapped through `.item`, is
         * exactly what should appear in the prose "Wear …" sentence (sans
         * fallbacks). Anything dropped here is something the user *might*
         * have on but the insight doesn't need to name — the t-shirt
         * under a coat, the jacket also-ran when a coat was warmer.
         */
        fun layerReduce(rules: List<ClothesRule>): List<ClothesRule> {
            // Map each top-slot rule to its (index, layer, priority) so we
            // can pick a winner per layer in one pass without re-querying
            // Garment.fromKey for every rule. Tracked by *index*, not by
            // rule equality: `ClothesRule` is a data class, so two
            // separately configured rules with identical item + condition
            // compare equal — using value equality for the keep-set would
            // let duplicates leak through and surface twice in the prose
            // ("Wear a sweater, sweater, and jeans"). Indexing is the
            // identity we need.
            data class Indexed(val index: Int, val layer: Layer, val priority: Int)

            val classifiedTops = mutableListOf<Indexed>()
            rules.forEachIndexed { index, rule ->
                val g = fromKey(rule.item)
                val layer = g?.layer
                if (g?.slot == Slot.TOP && layer != null) {
                    val order = TOP_LAYER_PRIORITY[layer].orEmpty()
                    val priority = order.indexOf(g).let { if (it < 0) order.size else it }
                    classifiedTops += Indexed(index, layer, priority)
                }
            }

            val winnerIndexByLayer: Map<Layer, Int> = classifiedTops
                .groupBy { it.layer }
                .mapValues { (_, group) -> group.minBy { it.priority }.index }
            val effective: Map<Layer, Int> = if (
                Layer.MID in winnerIndexByLayer || Layer.SHELL in winnerIndexByLayer
            ) {
                winnerIndexByLayer.filterKeys { it != Layer.BASE }
            } else {
                winnerIndexByLayer
            }
            val keepIndices = effective.values.toSet()

            // Preserve original input order across the union (top winners +
            // rest) so callers that care about the user's configured
            // ordering — the prose phraser, the tie-in delta — see the same
            // sequence they'd have seen before the reduction.
            return rules.filterIndexed { index, rule ->
                val g = fromKey(rule.item)
                val isClassifiedTop = g?.slot == Slot.TOP && g.layer != null
                if (isClassifiedTop) index in keepIndices else true
            }
        }
    }
}
