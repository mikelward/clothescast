package app.clothescast.ui.garment

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.clothescast.R
import app.clothescast.core.domain.model.OutfitSuggestion
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The baked-in primary fill and stroke for each outfit drawable. The recolor
 * pipeline matches by *original* colour: every path whose fill equals
 * [GarmentDefaults.fillArgb] gets remapped to the user's chosen colour, and
 * every path whose fill or stroke equals [GarmentDefaults.strokeArgb] gets
 * remapped to a darker shade derived from the chosen colour. Accent colours
 * (white puffer panels, beige sweater neckline trim, etc.) aren't listed
 * here — they survive the recolour unchanged, which is what we want.
 *
 * The hex values must match the XML drawables. If a drawable is regenerated
 * with new primary colours, this table needs the same update or the recolour
 * silently no-ops on the customized icon.
 */
internal data class GarmentDefaults(val fillArgb: Int, val strokeArgb: Int)

internal val outfitTopDefaults: Map<OutfitSuggestion.Top, GarmentDefaults> = mapOf(
    OutfitSuggestion.Top.TSHIRT to GarmentDefaults(0xFFFFFFFF.toInt(), 0xFFE0E0E0.toInt()),
    OutfitSuggestion.Top.POLO to GarmentDefaults(0xFF26C6DA.toInt(), 0xFF00838F.toInt()),
    OutfitSuggestion.Top.SWEATER to GarmentDefaults(0xFF8D6E63.toInt(), 0xFF5D4037.toInt()),
    OutfitSuggestion.Top.THIN_JACKET to GarmentDefaults(0xFF3949AB.toInt(), 0xFF1A237E.toInt()),
    OutfitSuggestion.Top.THICK_JACKET to GarmentDefaults(0xFF7E57C2.toInt(), 0xFF4527A0.toInt()),
    OutfitSuggestion.Top.THICK_COAT to GarmentDefaults(0xFFFFB74D.toInt(), 0xFFEF6C00.toInt()),
    OutfitSuggestion.Top.PUFFER_JACKET to GarmentDefaults(0xFFF5F7FA.toInt(), 0xFF90A4AE.toInt()),
)

internal val outfitBottomDefaults: Map<OutfitSuggestion.Bottom, GarmentDefaults> = mapOf(
    OutfitSuggestion.Bottom.SHORTS to GarmentDefaults(0xFFC8B27A.toInt(), 0xFF8D743F.toInt()),
    OutfitSuggestion.Bottom.LONG_SKIRT to GarmentDefaults(0xFFF06292.toInt(), 0xFFC2185B.toInt()),
    OutfitSuggestion.Bottom.JEANS to GarmentDefaults(0xFF5C6BC0.toInt(), 0xFF303F9F.toInt()),
    OutfitSuggestion.Bottom.LONG_PANTS to GarmentDefaults(0xFF455A64.toInt(), 0xFF263238.toInt()),
)

@DrawableRes
internal fun topDrawable(top: OutfitSuggestion.Top): Int = when (top) {
    OutfitSuggestion.Top.TSHIRT -> R.drawable.ic_outfit_tshirt
    OutfitSuggestion.Top.POLO -> R.drawable.ic_outfit_polo
    OutfitSuggestion.Top.SWEATER -> R.drawable.ic_outfit_sweater
    OutfitSuggestion.Top.THIN_JACKET -> R.drawable.ic_outfit_thin_jacket
    OutfitSuggestion.Top.THICK_JACKET -> R.drawable.ic_outfit_thick_jacket
    OutfitSuggestion.Top.THICK_COAT -> R.drawable.ic_outfit_thick_coat
    OutfitSuggestion.Top.PUFFER_JACKET -> R.drawable.ic_outfit_puffer_jacket
}

@DrawableRes
internal fun bottomDrawable(bottom: OutfitSuggestion.Bottom): Int = when (bottom) {
    OutfitSuggestion.Bottom.SHORTS -> R.drawable.ic_outfit_shorts
    OutfitSuggestion.Bottom.LONG_SKIRT -> R.drawable.ic_outfit_skirt
    OutfitSuggestion.Bottom.JEANS -> R.drawable.ic_outfit_jeans
    OutfitSuggestion.Bottom.LONG_PANTS -> R.drawable.ic_outfit_long_pants
}

/**
 * Derives a darker stroke colour from a user-chosen fill. The XML drawables'
 * baked stroke colours are roughly 35-40% lower L than their matching fills,
 * so a 0.6× scale on L (in HSL) approximates the existing two-tone look.
 * Clamped to a minimum L so a near-black fill doesn't produce a stroke
 * indistinguishable from it.
 *
 * Pure Kotlin maths (no `androidx.core.graphics.ColorUtils` /
 * `android.graphics.Color`) so the function works under the project's
 * `unitTests.isReturnDefaultValues = true` config without Robolectric.
 */
internal fun deriveStrokeArgb(argb: Long): Long {
    val alpha = ((argb ushr 24) and 0xFFL).toInt()
    val r = ((argb ushr 16) and 0xFFL).toInt()
    val g = ((argb ushr 8) and 0xFFL).toInt()
    val b = (argb and 0xFFL).toInt()
    val (h, s, l) = rgbToHsl(r, g, b)
    val newL = (l * STROKE_LIGHTNESS_SCALE).coerceAtLeast(STROKE_MIN_LIGHTNESS)
    val (nr, ng, nb) = hslToRgb(h, s, newL)
    val packed = (alpha shl 24) or (nr shl 16) or (ng shl 8) or nb
    return packed.toLong() and 0xFFFFFFFFL
}

internal fun deriveStroke(fill: Color): Color =
    Color(deriveStrokeArgb(fill.toArgb().toLong() and 0xFFFFFFFFL).toInt())

private const val STROKE_LIGHTNESS_SCALE = 0.6f
private const val STROKE_MIN_LIGHTNESS = 0.08f

/** Standard sRGB → HSL conversion. Returns hue in degrees [0, 360). */
private fun rgbToHsl(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
    val rf = r / 255f
    val gf = g / 255f
    val bf = b / 255f
    val mx = max(rf, max(gf, bf))
    val mn = min(rf, min(gf, bf))
    val l = (mx + mn) / 2f
    if (mx == mn) return Triple(0f, 0f, l)
    val d = mx - mn
    val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
    val h = when (mx) {
        rf -> ((gf - bf) / d + (if (gf < bf) 6f else 0f)) * 60f
        gf -> ((bf - rf) / d + 2f) * 60f
        else -> ((rf - gf) / d + 4f) * 60f
    }
    return Triple(h, s, l)
}

/** Standard HSL → sRGB conversion. Returns each channel as 8-bit int [0, 255]. */
private fun hslToRgb(h: Float, s: Float, l: Float): Triple<Int, Int, Int> {
    val c = (1f - abs(2f * l - 1f)) * s
    val m = l - c / 2f
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val (rp, gp, bp) = when (((h / 60f).toInt()) % 6) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Triple(
        ((rp + m) * 255f).roundToInt().coerceIn(0, 255),
        ((gp + m) * 255f).roundToInt().coerceIn(0, 255),
        ((bp + m) * 255f).roundToInt().coerceIn(0, 255),
    )
}

/**
 * Curated swatches for the garment-colour picker. Hand-picked to span the
 * hue circle plus common wardrobe neutrals, while staying visually pleasant
 * when paired with their derived stroke. Avoids near-black entries — those
 * collapse against the derived-stroke clamp in [deriveStrokeArgb] and look
 * identical to mid-grey after the recolour.
 */
internal val garmentColorPresets: List<Color> = listOf(
    Color(0xFFE53935), // red
    Color(0xFFFF7043), // orange
    Color(0xFFFFB300), // amber
    Color(0xFFFDD835), // yellow
    Color(0xFF7CB342), // light green
    Color(0xFF43A047), // green
    Color(0xFF2E7D32), // dark green
    Color(0xFF26A69A), // teal
    Color(0xFF29B6F6), // light blue
    Color(0xFF1E88E5), // blue
    Color(0xFF1A237E), // navy blue
    Color(0xFF5E35B1), // purple
    Color(0xFFAB47BC), // magenta
    Color(0xFFEC407A), // pink
    Color(0xFF8D6E63), // brown
    Color(0xFFE8D5B7), // beige
    Color(0xFFFFFFFF), // white
    Color(0xFF9E9E9E), // grey
    Color(0xFF424242), // dark grey
    Color(0xFF78909C), // blue-grey
)
