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
 * How many lines the widget should reserve for (and cap) the [caption]: an
 * estimate of how many lines the flowing run wraps to in a column
 * [availableWidthDp] wide at [fontSizeSp]. The Today card doesn't use this — it
 * has no cap and grows to fit — but the widget renders into a fixed cell, so it
 * must cap `maxLines` and reserve icon room to match; capping at a constant 2
 * clipped the final accessory once a side-by-side column was narrow enough to
 * wrap the run to a third line ("Thick coat · Long pants · Gloves · Umbrella" in
 * a ~120 dp column). Deriving the cap from the wrapped budget keeps the last
 * piece visible: 2 lines in a single-column cell, 3 once the column halves.
 *
 * The estimate greedily packs the space-separated tokens into lines of about
 * [availableWidthDp] / (`fontSizeSp` · [AVG_CHAR_EM]) characters — a deliberately
 * rough proxy for the real glyph widths, biased to not *under*-count (an
 * over-count only adds a little icon-shrinking headroom, an under-count clips).
 * It only frames the text; the actual wrapping is the renderer's. Capped at
 * [MAX_CAPTION_LINES] so a degenerate cell can't shrink the icons away.
 *
 * Kept here next to [outfitGarmentCaption] so the text and the line budget that
 * frames it can't drift apart.
 */
internal fun outfitGarmentCaptionLineCount(
    caption: String,
    availableWidthDp: Float,
    fontSizeSp: Float,
): Int {
    val usableWidth = availableWidthDp * USABLE_WIDTH_FRACTION
    val approxCharWidth = (fontSizeSp * AVG_CHAR_EM).coerceAtLeast(1f)
    val charsPerLine = (usableWidth / approxCharWidth).toInt().coerceAtLeast(1)
    return greedyWrappedLineCount(caption, charsPerLine).coerceIn(1, MAX_CAPTION_LINES)
}

/**
 * Lines [text] takes when its space-separated tokens are greedily packed into
 * [charsPerLine]-wide lines — a token that doesn't fit moves whole to the next
 * line (and a token wider than a line still claims one). Counting at word
 * boundaries (rather than `length / charsPerLine`) matches how the renderer
 * wraps, so a long final word like "Umbrella" correctly tips the run onto a new
 * line instead of being averaged away.
 */
private fun greedyWrappedLineCount(text: String, charsPerLine: Int): Int {
    if (text.isEmpty()) return 1
    var lines = 1
    var lineLen = 0
    for (token in text.split(' ')) {
        val withToken = if (lineLen == 0) token.length else lineLen + 1 + token.length
        if (lineLen == 0 || withToken <= charsPerLine) {
            lineLen = withToken
        } else {
            lines++
            lineLen = token.length
        }
    }
    return lines
}

// Fraction of the column width the caption text actually fills (it's bound with
// fillMaxWidth, minus the widget's small outer padding).
private const val USABLE_WIDTH_FRACTION = 0.9f
// Average glyph advance as a fraction of the font size, for the proxy
// char-per-line budget. Tuned (with USABLE_WIDTH_FRACTION) to track what the
// renderer actually wraps to: the run's narrow "·" separators and spaces pull
// the real average well under a full em, so 0.5 over-counted — a four-piece
// accessory run ("Thick coat · Long pants · Gloves · Umbrella") that renders on
// two lines in a halved side-by-side column was estimated at three, which then
// over-reserved icon room and shrank the figure into a sea of empty space.
// 0.46 lands the estimate on the rendered line count for the catalogue's
// captions; it stays a deliberate slight over-estimate vs. the proportional
// glyph widths so it rounds up rather than down (an over-count only adds a
// little headroom, an under-count clips the final accessory).
private const val AVG_CHAR_EM = 0.46f
// A widget cell never has room for more than this many caption lines on top of
// the icons; past it the run is left to ellipsize rather than shrinking the
// icons into nothing.
private const val MAX_CAPTION_LINES = 3
