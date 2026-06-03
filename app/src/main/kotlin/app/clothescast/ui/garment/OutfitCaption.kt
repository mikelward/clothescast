package app.clothescast.ui.garment

import android.content.Context
import app.clothescast.R
import app.clothescast.core.domain.model.OutfitSuggestion

/**
 * The garment caption shown under an outfit's icons — the same text on the Today
 * card and the home-screen widget. Names *every* piece the icon shows as one
 * flowing "· "-joined run: the worn outfit (top, the rain-jacket outer shell
 * layered over it, then the bottom) followed by the optional accessories (gloves,
 * the carried umbrella) at the end. The optional tiers are listed only when they
 * actually fired, so a plain outfit reads "Sweater · Jeans" and a cold rainy day
 * reads "Thick coat · Long pants · Gloves · Umbrella".
 *
 * There's no forced break between worn and carried — the run just soft-wraps to
 * as many lines as it needs. The Today card lets it grow freely (it reserves a
 * floor with `minLines = 2` and stretches the side-by-side cards to equal height
 * so a wrapping card doesn't run taller than its neighbour). The widget can't
 * grow into a fixed cell, so it caps and reserves icon room via
 * [outfitGarmentCaptionLineCount]; the text it renders is identical.
 *
 * [topLabel] / [bottomLabel] are passed in already-resolved because each surface
 * resolves them through its own `today_outfit_top_*` / `today_outfit_bottom_*`
 * lookups; the optional tiers each have a single catalog member, so their labels
 * come straight from the `garment_*` strings.
 */
internal fun outfitGarmentCaption(
    context: Context,
    outfit: OutfitSuggestion,
    topLabel: String,
    bottomLabel: String,
): String = buildList {
    add(topLabel)
    if (outfit.outer != null) add(context.getString(R.string.garment_rain_jacket))
    add(bottomLabel)
    if (outfit.hands != null) add(context.getString(R.string.garment_gloves))
    if (outfit.carried != null) add(context.getString(R.string.garment_umbrella))
}.joinToString(" · ")

/**
 * How many lines the widget should reserve for (and cap) the caption: 2 once any
 * extra piece beyond the base top + bottom joins it — a rain-jacket shell, gloves,
 * or the umbrella — because the longer flowing run wraps in the narrow widget
 * cell, otherwise 1. The Today card doesn't use this: it has no cap and grows to
 * fit. Kept here next to [outfitGarmentCaption] so the text and the line budget
 * that frames it can't drift apart.
 */
internal fun outfitGarmentCaptionLineCount(outfit: OutfitSuggestion): Int =
    if (outfit.outer != null || outfit.hands != null || outfit.carried != null) 2 else 1
