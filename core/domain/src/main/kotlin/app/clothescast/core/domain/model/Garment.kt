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
 * tops, bottoms, the gloves hands slot, and one carried accessory (umbrella);
 * headwear and further rain / sun gear can land in follow-ups (and will likely
 * come paired with their own outfit-card icons).
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
enum class Garment(
    val itemKey: String,
    val slot: Slot,
    val layer: Layer? = null,
    /**
     * Perceived-warmth rating for the "layer count" prose format
     * ([app.clothescast.core.domain.model.ClothesFormat.LAYER_COUNT]). A
     * t-shirt feels like one layer of warmth; a puffer feels like four.
     * The number is *not* a literal count of garments — wearing both a
     * sweater (2) and a jacket (3) doesn't land you at 5, the renderer
     * takes the max because the heavier garment defines the warmth tier.
     * Bottoms aren't layers in this model and report 0; the renderer
     * names them separately ("Wear 2 layers and shorts.").
     */
    val layerCount: Int = 0,
) {
    // Tops, coldest-leaning first. Sweater + jacket are shipped as defaults.
    // [layer] tags each top with where it sits in the layering order so the
    // rule engine can reduce overlapping firing rules to a stack that reads
    // like a real outfit ("sweater under a jacket"), not a flat list of
    // every match ("sweater, jacket, and t-shirt"). See [Layer].
    SWEATER("sweater", Slot.TOP, Layer.MID, layerCount = 2),
    HOODIE("hoodie", Slot.TOP, Layer.MID, layerCount = 2),
    JACKET("jacket", Slot.TOP, Layer.SHELL, layerCount = 3),
    COAT("coat", Slot.TOP, Layer.SHELL, layerCount = 3),
    // A puffer is a (warm) shell, not a literal fourth layer — its layerCount
    // matches the other shells. "Extra cold" beyond a full shell stack is
    // signalled by extremity gear (gloves), not a higher layer count.
    PUFFER("puffer", Slot.TOP, Layer.SHELL, layerCount = 3),
    // A rain jacket is a waterproof *outer* shell worn over whatever top the
    // other rules pick — it sits in its own [Layer.OUTER] so it stacks on top
    // of a sweater / jacket / coat rather than replacing it (the prose reads
    // "Wear a jacket and a rain jacket", and the icon paints it over the top
    // tier). Its [layerCount] is a light shell's 2: it adds weather protection,
    // not much warmth, so it never inflates the perceived-warmth tier past the
    // garment underneath. Ships off by default (absent from [ClothesRule.DEFAULTS]).
    RAIN_JACKET("rain-jacket", Slot.TOP, Layer.OUTER, layerCount = 2),
    THIN_JACKET("thin-jacket", Slot.TOP, Layer.MID, layerCount = 2),
    TSHIRT("t-shirt", Slot.TOP, Layer.BASE, layerCount = 1),
    POLO("polo", Slot.TOP, Layer.BASE, layerCount = 1),
    SHIRT("shirt", Slot.TOP, Layer.BASE, layerCount = 1),

    // Bottoms. Shorts is shipped as a default; en-GB renders "pants" as "Trousers".
    // Bottoms substitute rather than stack today (you don't layer pants
    // over shorts), so [layer] is null.
    SHORTS("shorts", Slot.BOTTOM),
    SHORT_SKIRT("short-skirt", Slot.BOTTOM),
    SKIRT("skirt", Slot.BOTTOM),
    PANTS("pants", Slot.BOTTOM),
    JEANS("jeans", Slot.BOTTOM),

    // Extremity gear — worn alongside the top/bottom stack, not part of it.
    // Gloves are the "extra cold" signal: they fire below the coat threshold
    // rather than inflating the layer count.
    GLOVES("gloves", Slot.HANDS),

    // Carried rain/sun gear — held, not worn. An umbrella is keyed off
    // precipitation probability rather than temperature; it claims its own
    // [Slot.CARRIED] so it never competes with the worn top/bottom/hands
    // stack, and the prose names it with "bring/carry" rather than "wear"
    // (see [isAccessoryKey]).
    UMBRELLA("umbrella", Slot.CARRIED);

    /**
     * Canonical relative warmth for outfit comparisons — "is the evening's top
     * warmer than the day's?" and the like. Higher is warmer; bottoms report 0.
     *
     * Currently the same rating as [layerCount] (which, despite its name, is
     * already a perceived-warmth value rather than a literal garment count), but
     * named for the comparison so call sites read by intent. The catalog
     * redesign will give garments a deliberate warmth and align the layerReduce
     * / icon priority order ([TOP_LAYER_PRIORITY], today coldest-first
     * coat → puffer → jacket) to it, so the warmth signals can't disagree on
     * e.g. coat vs puffer the way they do now.
     */
    val warmth: Int get() = layerCount

    /**
     * Rain gear — the umbrella (a carried accessory) and the rain jacket (a
     * waterproof [Layer.OUTER] shell). The snow gate in [EvaluateClothesRules]
     * (mirrored in [OutfitSuggestion.fromForecast]) drops these on a snowy day:
     * you don't umbrella or rain-jacket snow. Worn warmth garments — even one a
     * user keys off the precipitation gate (e.g. "gloves when rain ≥ 80%") — are
     * deliberately *not* rain gear and stay put on a snowy forecast.
     */
    val isRainGear: Boolean get() = slot == Slot.CARRIED || layer == Layer.OUTER

    /** Which outfit slot this garment occupies. Drives "does any matched rule
     *  cover this slot?" decisions in [EvaluateClothesRules] and "what
     *  temperature window does the fallback apply in?" in [FallbackRange],
     *  without those callers re-encoding the tier classification as string
     *  key sets. Each slot also declares how [layerReduce] collapses
     *  overlapping firing rules within it — see [Reduction]. */
    enum class Slot(val reduction: Reduction) {
        TOP(Reduction.LAYERED),
        BOTTOM(Reduction.SUBSTITUTE),
        HANDS(Reduction.SUBSTITUTE),
        // Carried, not worn — a single umbrella; you don't carry two.
        CARRIED(Reduction.SUBSTITUTE),
    }

    /**
     * How [layerReduce] collapses multiple firing rules that share a [Slot].
     * Declaring the strategy on the slot — rather than branching on slot
     * identity inside the reducer — means a new slot only has to pick a
     * strategy (and, for the priority-ordered ones, supply its ranking); the
     * reducer itself stays slot-agnostic.
     */
    enum class Reduction {
        /** Garments stack: keep at most one winner per [Layer], dropping the
         *  [Layer.BASE] winner when a [Layer.MID] / [Layer.SHELL] one also fires
         *  (the base is implicit under an outer layer). Tops today. */
        LAYERED,

        /** Garments substitute: keep a single highest-priority winner — you wear
         *  one pair of bottoms, not two. Bottoms today. */
        SUBSTITUTE,
    }

    /**
     * Where this garment sits in the layering order. The rule engine uses
     * this to collapse multiple firing top-tier rules into a coherent
     * stack:
     *  - At most one garment per layer (heaviest firing tier within the
     *    layer wins — picked via the priority order encoded in
     *    [OutfitSuggestion]'s key sets), and
     *  - the [BASE] layer is suppressed when an *insulating* covering layer
     *    ([MID] / [SHELL]) also fires, because the base is implicit when
     *    you're wearing something warm over it (no one says "wear a t-shirt
     *    under your coat" — they just name the coat).
     *
     * [OUTER] sits above [SHELL]: a waterproof rain jacket worn over the
     * sweater / jacket / coat the warmth rules pick, so it coexists with the
     * SHELL / MID winner in the stack rather than displacing it. Unlike a warm
     * covering layer it does *not* suppress [BASE] on its own — a thin rain
     * shell doesn't subsume the base top, so a t-shirt + rain jacket keeps both
     * ("a t-shirt and a rain jacket"). Bottoms substitute today (shorts vs.
     * pants is an either-or, not a stack), so they don't carry a layer.
     */
    enum class Layer { BASE, MID, SHELL, OUTER }

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
         * Whether a rule-item key names a *carried* accessory (rather than a
         * worn garment). Carried gear sits in [Slot.CARRIED]: it doesn't layer
         * and the wear-clause prose filters it out of "Wear …" ("Wear an
         * umbrella" reads wrong — it's named with "bring/carry" instead). Today
         * it's only `umbrella`; a future rain jacket / hood / sunscreen would
         * join [Slot.CARRIED] and be picked up here automatically.
         */
        fun isAccessoryKey(key: String): Boolean =
            fromKey(key)?.slot == Slot.CARRIED

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
         *  - OUTER: RAIN_JACKET
         *  - SHELL: COAT → PUFFER → JACKET
         *  - MID:   THIN_JACKET → SWEATER → HOODIE
         *  - BASE:  POLO → TSHIRT → SHIRT
         */
        private val TOP_LAYER_PRIORITY: Map<Layer, List<Garment>> = mapOf(
            Layer.OUTER to listOf(RAIN_JACKET),
            Layer.SHELL to listOf(COAT, PUFFER, JACKET),
            Layer.MID to listOf(THIN_JACKET, SWEATER, HOODIE),
            Layer.BASE to listOf(POLO, TSHIRT, SHIRT),
        )

        /**
         * Priority for bottom garments — most-exposed first. Bottoms
         * substitute rather than stack (you don't wear pants over shorts),
         * so when multiple firing rules belong to the bottom slot the one
         * earliest in this list wins and the rest are suppressed. Mirrors
         * the bottom dispatch in [OutfitSuggestion.fromForecast] so the
         * prose and the home-screen icon agree on which bottom "wins" when
         * a user has overlapping rules (e.g. `short-skirt > 22°C` and
         * `skirt > 16°C` both firing on a 25°C day → mini wins, the long
         * skirt is silent).
         */
        private val BOTTOM_PRIORITY: List<Garment> = listOf(SHORTS, SHORT_SKIRT, SKIRT, JEANS, PANTS)

        /**
         * Within-[Layer] priority per [Reduction.LAYERED] slot. A new layered
         * slot adds its own entry here; the reducer reads it by slot rather
         * than naming [TOP_LAYER_PRIORITY] directly.
         */
        private val LAYERED_PRIORITY: Map<Slot, Map<Layer, List<Garment>>> =
            mapOf(Slot.TOP to TOP_LAYER_PRIORITY)

        /** Winner priority per [Reduction.SUBSTITUTE] slot. */
        private val SUBSTITUTE_PRIORITY: Map<Slot, List<Garment>> =
            mapOf(
                Slot.BOTTOM to BOTTOM_PRIORITY,
                Slot.HANDS to listOf(GLOVES),
                Slot.CARRIED to listOf(UMBRELLA),
            )

        /** Rank of this garment within a priority list — earlier wins; anything
         *  off the list sorts last so it only wins if nothing ranked fired. */
        private fun Garment.rankIn(priority: List<Garment>): Int =
            priority.indexOf(this).let { if (it < 0) priority.size else it }

        /**
         * Filters a list of firing [ClothesRule]s down to a coherent
         * outfit: a layered top stack plus a single bottom plus the rest
         * (accessories, unclassified items). The "rest" passes through
         * untouched and in input order; the top stack picks at most one
         * rule per [Layer] using [TOP_LAYER_PRIORITY] (and drops
         * [Layer.BASE] entirely if anything in [Layer.MID] or
         * [Layer.SHELL] also fired); the bottom slot picks a single rule
         * using [BOTTOM_PRIORITY] because bottoms substitute rather than
         * stack.
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
         * under a coat, the jacket also-ran when a coat was warmer, the
         * full-length skirt when a mini also crossed its threshold.
         */
        fun layerReduce(rules: List<ClothesRule>): List<ClothesRule> {
            // Collect the indices to keep, per slot, then filter preserving
            // input order. Tracked by *index*, not by rule equality:
            // `ClothesRule` is a data class, so two separately configured rules
            // with identical item + condition compare equal — a value-keyed
            // keep-set would let a duplicate leak through and surface twice in
            // the prose ("Wear a sweater, sweater, and jeans").
            val keep = mutableSetOf<Int>()
            rules.withIndex()
                .groupBy { it.value.item.slot }
                .forEach { (slot, indexed) ->
                    when (slot.reduction) {
                        Reduction.LAYERED -> keep += layeredWinners(slot, indexed)
                        Reduction.SUBSTITUTE -> {
                            val priority = SUBSTITUTE_PRIORITY[slot].orEmpty()
                            indexed.minByOrNull { it.value.item.rankIn(priority) }
                                ?.let { keep += it.index }
                        }
                    }
                }
            return rules.filterIndexed { index, _ -> index in keep }
        }

        /**
         * The kept indices for one [Reduction.LAYERED] slot: at most one winner
         * per [Layer] (earliest in the slot's priority wins), dropping the base
         * layer when a mid / shell winner also fired. Garments with no
         * [Garment.layer] aren't part of the stack, so they pass through.
         *
         * The base is dropped only under an *insulating* covering layer ([MID] /
         * [SHELL]) — "wear a t-shirt under your coat" goes without saying, so we
         * name just the coat. A bare [OUTER] shell (the rain jacket) is the
         * exception: it's a thin waterproof layer that doesn't subsume the base
         * top, so a `t-shirt > 20°C` + rain-jacket pairing keeps both and reads
         * "a t-shirt and a rain jacket" — matching the icon, which paints the
         * shell over that same base top.
         */
        private fun layeredWinners(
            slot: Slot,
            indexed: List<IndexedValue<ClothesRule>>,
        ): Set<Int> {
            val priorityByLayer = LAYERED_PRIORITY[slot].orEmpty()
            val keep = indexed
                .filter { it.value.item.layer == null }
                .mapTo(mutableSetOf()) { it.index }
            val winnerByLayer: Map<Layer, Int> = indexed
                .mapNotNull { iv -> iv.value.item.layer?.let { it to iv } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (layer, group) ->
                    group.minBy { it.value.item.rankIn(priorityByLayer[layer].orEmpty()) }.index
                }
            val hasInsulatingLayer = winnerByLayer.keys.any { it == Layer.MID || it == Layer.SHELL }
            val effective =
                if (hasInsulatingLayer) {
                    winnerByLayer.filterKeys { it != Layer.BASE }
                } else {
                    winnerByLayer
                }
            keep += effective.values
            return keep
        }
    }
}
