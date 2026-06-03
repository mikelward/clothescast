package app.clothescast.ui.garment

import android.content.Context
import app.clothescast.R
import app.clothescast.core.domain.model.OutfitSuggestion

/**
 * The garment caption shown under an outfit's icons on the Today card and the
 * home-screen widget. Names *every* piece the icon shows, split across (up to)
 * two lines: the worn outfit on the first line — top, the rain-jacket outer
 * shell layered over it, then the bottom — and the optional accessories (gloves
 * at the hands, the carried umbrella) on a second line, joined by an explicit
 * break. The optional tiers are listed only when they actually fired, so a plain
 * outfit is a single line ("Sweater · Jeans") while a cold rainy day reads
 * "Thick coat · Long pants" / "Gloves · Umbrella".
 *
 * The two-line split (rather than one long "· "-joined run) keeps the caption
 * from clipping in the narrow widget cell and groups "what you wear" apart from
 * "what you carry"; the break is explicit so the layout is deterministic rather
 * than relying on each surface's soft-wrap. Callers reserve room for the second
 * line (the Today card via `minLines = 2`, the widget via its icon-size reserve)
 * and bind the text width so each line centres.
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
): String = outfitGarmentCaptionLines(context, outfit, topLabel, bottomLabel).joinToString("\n")

/**
 * The caption as its individual lines — `[worn]` or `[worn, accessories]`. The
 * Today card joins them with a newline into one `minLines = 2` Text (its tuned
 * `bodySmall` line height keeps the pair tight), while the widget renders one
 * Text per line stacked with no gap, because Glance's [TextStyle] exposes no
 * line height to tighten a multi-line Text and the font's default spacing reads
 * too loose. Both end up matching.
 */
internal fun outfitGarmentCaptionLines(
    context: Context,
    outfit: OutfitSuggestion,
    topLabel: String,
    bottomLabel: String,
): List<String> {
    val worn = buildList {
        add(topLabel)
        if (outfit.outer != null) add(context.getString(R.string.garment_rain_jacket))
        add(bottomLabel)
    }.joinToString(" · ")
    val accessories = buildList {
        if (outfit.hands != null) add(context.getString(R.string.garment_gloves))
        if (outfit.carried != null) add(context.getString(R.string.garment_umbrella))
    }.joinToString(" · ")
    return if (accessories.isEmpty()) listOf(worn) else listOf(worn, accessories)
}
