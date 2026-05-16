package app.clothescast.ui.garment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import app.clothescast.R
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.symbol
import app.clothescast.core.domain.model.toUnit
import app.clothescast.insight.InsightFormatter
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap

/**
 * Renders a top-tier garment icon with the user's chosen [customFill]
 * (or the baked-in default when [customFill] is null). The auto-derived
 * stroke colour comes from [deriveStroke] so the two-tone look survives
 * the recolour.
 */
@Composable
internal fun GarmentTopIcon(
    top: app.clothescast.core.domain.model.OutfitSuggestion.Top,
    customFill: Color?,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val defaults = outfitTopDefaults.getValue(top)
    GarmentIconImpl(
        drawableRes = topDrawable(top),
        defaults = defaults,
        customFill = customFill,
        contentDescription = contentDescription,
        modifier = modifier,
    )
}

@Composable
internal fun GarmentBottomIcon(
    bottom: app.clothescast.core.domain.model.OutfitSuggestion.Bottom,
    customFill: Color?,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val defaults = outfitBottomDefaults.getValue(bottom)
    GarmentIconImpl(
        drawableRes = bottomDrawable(bottom),
        defaults = defaults,
        customFill = customFill,
        contentDescription = contentDescription,
        modifier = modifier,
    )
}

@Composable
private fun GarmentIconImpl(
    @DrawableRes drawableRes: Int,
    defaults: GarmentDefaults,
    customFill: Color?,
    contentDescription: String,
    modifier: Modifier,
) {
    // Default-colour fast path: render the original vector via painterResource
    // so the existing snapshot tests stay byte-identical for users who haven't
    // customised. The Canvas-based recolour path only kicks in when a custom
    // fill is set.
    if (customFill == null) {
        Image(
            painter = painterResource(drawableRes),
            contentDescription = contentDescription,
            modifier = modifier,
        )
        return
    }
    val context = LocalContext.current
    val vector = remember(drawableRes) { loadOutfitVector(context, drawableRes) }
    val recolor = remember(customFill, defaults) { buildRecolorMap(defaults, customFill) }
    val composePaths = remember(vector) {
        // Convert pathData to Compose Path once; re-rendering on customFill change
        // only swaps the colour entries, not the geometry.
        vector.paths.map { p ->
            ComposePathSpec(
                path = PathParser().parsePathString(p.pathData).toPath(),
                originalFill = p.fillArgb,
                originalStroke = p.strokeArgb,
                strokeWidth = p.strokeWidth,
                strokeCap = p.strokeCap.toComposeCap(),
                strokeJoin = p.strokeJoin.toComposeJoin(),
            )
        }
    }
    ComposeCanvas(
        // Pin the aspect ratio to the drawable's viewport so a width-only
        // modifier (the common case from callers) still produces the right
        // height — matching the previous painterResource(Image) behaviour
        // where the painter's intrinsic size handled this implicitly.
        modifier = modifier
            .aspectRatio(vector.viewportWidth / vector.viewportHeight)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Image
            },
    ) {
        scale(
            scaleX = size.width / vector.viewportWidth,
            scaleY = size.height / vector.viewportHeight,
            pivot = Offset.Zero,
        ) {
            composePaths.forEach { spec ->
                spec.originalFill?.let { fillArgb ->
                    val color = recolor[fillArgb] ?: fillArgb
                    drawPath(spec.path, Color(color))
                }
                spec.originalStroke?.let { strokeArgb ->
                    val color = recolor[strokeArgb] ?: strokeArgb
                    drawPath(
                        path = spec.path,
                        color = Color(color),
                        style = Stroke(
                            width = spec.strokeWidth,
                            cap = spec.strokeCap,
                            join = spec.strokeJoin,
                        ),
                    )
                }
            }
        }
    }
}

private data class ComposePathSpec(
    val path: Path,
    val originalFill: Int?,
    val originalStroke: Int?,
    val strokeWidth: Float,
    val strokeCap: StrokeCap,
    val strokeJoin: StrokeJoin,
)

private fun Paint.Cap.toComposeCap(): StrokeCap = when (this) {
    Paint.Cap.ROUND -> StrokeCap.Round
    Paint.Cap.SQUARE -> StrokeCap.Square
    Paint.Cap.BUTT -> StrokeCap.Butt
}

private fun Paint.Join.toComposeJoin(): StrokeJoin = when (this) {
    Paint.Join.ROUND -> StrokeJoin.Round
    Paint.Join.BEVEL -> StrokeJoin.Bevel
    Paint.Join.MITER -> StrokeJoin.Miter
}

/**
 * Rasterizes the recoloured outfit icon into a [Bitmap] for surfaces that
 * can't render Compose composables — notification large icons (via
 * [androidx.core.app.NotificationCompat.Builder.setLargeIcon]) and the
 * Glance widget (via [androidx.glance.ImageProvider]). Memoised by
 * `(drawableRes, fillArgb, sizePx)` so rapid widget refreshes don't
 * re-rasterize. [customFillArgb] = null preserves the baked-in two-tone.
 */
internal fun renderOutfitBitmap(
    context: Context,
    @DrawableRes drawableRes: Int,
    defaults: GarmentDefaults,
    customFillArgb: Long?,
    sizePx: Int,
): Bitmap {
    require(sizePx > 0) { "sizePx must be positive, got $sizePx" }
    val cacheKey = BitmapCacheKey(drawableRes, customFillArgb, sizePx)
    bitmapCache[cacheKey]?.let { return it }
    val bitmap = if (customFillArgb == null) {
        // Default-colour fast path: lean on the platform's VectorDrawable
        // rasterizer so the bitmap matches what users have always seen on
        // notifications + widgets pre-customisation.
        renderDefaultBitmap(context, drawableRes, sizePx)
    } else {
        renderRecoloredBitmap(context, drawableRes, defaults, customFillArgb, sizePx)
    }
    bitmapCache[cacheKey] = bitmap
    return bitmap
}

private fun renderDefaultBitmap(
    context: Context,
    @DrawableRes drawableRes: Int,
    sizePx: Int,
): Bitmap {
    val drawable = ResourcesCompat.getDrawable(context.resources, drawableRes, context.theme)
        ?: error("Unable to load drawable $drawableRes")
    return drawable.toBitmap(width = sizePx, height = sizePx)
}

private fun renderRecoloredBitmap(
    context: Context,
    @DrawableRes drawableRes: Int,
    defaults: GarmentDefaults,
    customFillArgb: Long,
    sizePx: Int,
): Bitmap {
    val vector = loadOutfitVector(context, drawableRes)
    val recolor = buildRecolorMap(defaults, Color(customFillArgb.toInt()))
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val scaleX = sizePx / vector.viewportWidth
    val scaleY = sizePx / vector.viewportHeight
    canvas.scale(scaleX, scaleY)
    val paint = Paint().apply { isAntiAlias = true }
    vector.paths.forEach { p ->
        val androidPath = p.toAndroidPath()
        p.fillArgb?.let { argb ->
            paint.style = Paint.Style.FILL
            paint.color = recolor[argb] ?: argb
            canvas.drawPath(androidPath, paint)
        }
        p.strokeArgb?.let { argb ->
            paint.style = Paint.Style.STROKE
            paint.color = recolor[argb] ?: argb
            paint.strokeWidth = p.strokeWidth
            paint.strokeCap = p.strokeCap
            paint.strokeJoin = p.strokeJoin
            canvas.drawPath(androidPath, paint)
        }
    }
    return bitmap
}

private data class BitmapCacheKey(
    @DrawableRes val drawableRes: Int,
    val customFillArgb: Long?,
    val sizePx: Int,
)

/**
 * Renders a Nest-Hub-ready outfit card as a PNG.
 *
 * Layout (800 × 480 px, white background, landscape):
 * ```
 * ┌──────────────────────────────────────────┐
 * │  [top icon]  TODAY'S CLOTHESCAST         │  ← header over the prose
 * │  [        ]  A warm one today. Wear a    │    column, not the icons
 * │  [bot icon]  t-shirt and shorts. High…   │
 * │  [        ]                               │
 * │              🌡 18–28°C                   │  ← feels-like low/high
 * │              💧 Peak 60% at 3pm           │  ← only when peak ≥ 30%
 * └──────────────────────────────────────────┘
 * ```
 * [header] is the localised, mixed-case "Today's ClothesCast" string from
 * resources — the renderer uppercases it. [tempLine] and [rainLine] are
 * pre-formatted by the caller (units and "3pm"-style time come from
 * `InsightFormatter`). Pass `rainLine = null` to hide the rain row.
 */
internal fun renderOutfitCard(
    context: Context,
    outfit: OutfitSuggestion,
    header: String,
    prose: String,
    tempLine: String,
    rainLine: String?,
    topColors: Map<OutfitSuggestion.Top, Long>,
    bottomColors: Map<OutfitSuggestion.Bottom, Long>,
): ByteArray {
    val bmp = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(android.graphics.Color.WHITE)

    val proseX = CARD_PAD + ICON_PX + ICON_H_GAP

    // Icons go in the left column from the top, independent of the header
    // which lives above the prose on the right.
    val topBmp = renderOutfitBitmap(context, topDrawable(outfit.top), outfitTopDefaults.getValue(outfit.top), topColors[outfit.top], ICON_PX)
    val botBmp = renderOutfitBitmap(context, bottomDrawable(outfit.bottom), outfitBottomDefaults.getValue(outfit.bottom), bottomColors[outfit.bottom], ICON_PX)
    canvas.drawBitmap(topBmp, CARD_PAD.toFloat(), CARD_PAD.toFloat(), null)
    canvas.drawBitmap(botBmp, CARD_PAD.toFloat(), (CARD_PAD + ICON_PX + ICON_V_GAP).toFloat(), null)

    // Period-aware header along the top of the right column — sits over
    // the prose rather than the icons. Fixed at proseX so it left-aligns
    // with the prose underneath.
    val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = HEADER_PX
        color = android.graphics.Color.BLACK
    }
    val headerBaseline = CARD_PAD - headerPaint.fontMetrics.ascent
    canvas.drawText(header.uppercase(), proseX.toFloat(), headerBaseline, headerPaint)
    val proseTop = (headerBaseline + headerPaint.fontMetrics.descent + HEADER_GAP_PX).toInt()

    // Prose wraps in the column to the right of the icon stack.
    if (prose.isNotBlank()) {
        val prosePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
            textSize = PROSE_PX
            color = 0xFF444444.toInt()
        }
        val layout = StaticLayout.Builder
            .obtain(prose, 0, prose.length, prosePaint, CARD_W - proseX - CARD_PAD)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setMaxLines(PROSE_MAX_LINES)
            .build()
        canvas.save()
        canvas.translate(proseX.toFloat(), proseTop.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    // Info rows anchored to the bottom of the right column so they sit in
    // the same place whether the prose is short or long.
    val infoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
        textSize = INFO_PX
        color = 0xFF444444.toInt()
    }
    val rainRowTop = CARD_H - INFO_BOTTOM_PAD - INFO_ICON_PX
    val tempRowTop = rainRowTop - INFO_ICON_PX - INFO_ROW_GAP_PX
    drawInfoRow(canvas, context, R.drawable.ic_outfit_card_thermometer, tempLine, proseX, tempRowTop, infoPaint)
    if (rainLine != null) {
        drawInfoRow(canvas, context, R.drawable.ic_outfit_card_rain, rainLine, proseX, rainRowTop, infoPaint)
    }

    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    bmp.recycle()
    return out.toByteArray()
}

private fun drawInfoRow(
    canvas: Canvas,
    context: Context,
    @DrawableRes iconRes: Int,
    text: String,
    x: Int,
    y: Int,
    paint: TextPaint,
) {
    val icon = ResourcesCompat.getDrawable(context.resources, iconRes, context.theme)
        ?: return
    val iconBmp = icon.toBitmap(width = INFO_ICON_PX, height = INFO_ICON_PX)
    canvas.drawBitmap(iconBmp, x.toFloat(), y.toFloat(), null)
    // Centre the text vertically against the icon.
    val textX = x + INFO_ICON_PX + INFO_ICON_GAP_PX
    val textCenterY = y + INFO_ICON_PX / 2f
    val textBaseline = textCenterY - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
    canvas.drawText(text, textX.toFloat(), textBaseline, paint)
}

/**
 * Computes the two info-row strings shown beneath the prose on the outfit
 * card. Pulled out so [MainActivity] and [FetchAndNotifyWorker] share the
 * same min/max feels-like and peak-rain logic. Returns `tempLine` empty
 * when [hourly] is empty (no horizontal info to show), and `rainLine`
 * null when the windowed peak rain probability is below
 * [RAIN_PEAK_THRESHOLD_PCT] — the renderer then hides that row entirely.
 */
internal data class OutfitCardInfoLines(val tempLine: String, val rainLine: String?)

internal fun outfitCardInfoLines(
    context: Context,
    formatter: InsightFormatter,
    hourly: List<HourlyForecast>,
    temperatureUnit: TemperatureUnit,
): OutfitCardInfoLines {
    val lowC = hourly.minOfOrNull { it.feelsLikeC }
    val highC = hourly.maxOfOrNull { it.feelsLikeC }
    val tempLine = if (lowC != null && highC != null) {
        context.getString(
            R.string.outfit_card_temperature_range,
            lowC.toUnit(temperatureUnit).roundToInt(),
            highC.toUnit(temperatureUnit).roundToInt(),
            temperatureUnit.symbol(),
        )
    } else {
        ""
    }
    val peak = hourly.maxByOrNull { it.precipitationProbabilityPct }
    val rainLine = peak?.let {
        val pct = it.precipitationProbabilityPct.roundToInt()
        if (pct < RAIN_PEAK_THRESHOLD_PCT) null
        else formatter.formatPeakRain(pct, it.time)
    }
    return OutfitCardInfoLines(tempLine, rainLine)
}

private const val RAIN_PEAK_THRESHOLD_PCT = 30

// Card: 800×480 px (Nest Hub 7" display resolution).
// Layout: icons fill the left column from the top; the header sits on
// top of the right column (above the prose, at proseX); info rows are
// anchored to the bottom of the right column so their position is
// stable regardless of how long the prose runs. Info rows use
// INFO_BOTTOM_PAD (larger than CARD_PAD) so they clear the Nest Hub's
// bottom bezel / status overlay and don't read as cut off.
private const val CARD_W = 800
private const val CARD_H = 480
private const val CARD_PAD = 36
private const val HEADER_PX = 38f
private const val HEADER_GAP_PX = 28   // gap between header bottom and prose top
private const val ICON_PX = 160
private const val ICON_V_GAP = 8       // vertical gap between top and bottom icon
private const val ICON_H_GAP = 24      // horizontal gap from icon column to prose
private const val PROSE_PX = 22f
private const val PROSE_MAX_LINES = 7  // leaves room for the two info rows
private const val INFO_PX = 26f        // larger than prose so it reads at-a-glance
private const val INFO_ICON_PX = 36
private const val INFO_ICON_GAP_PX = 12
private const val INFO_ROW_GAP_PX = 10
// Bottom-of-card padding for the info rows — larger than CARD_PAD so the
// rain / temp lines clear the Nest Hub's bezel + bottom status overlay.
private const val INFO_BOTTOM_PAD = 60

/**
 * LRU-ish bitmap cache. Most users have ≤2 widget cells × ≤4 garment slots ×
 * a couple of size variants, so a small bound covers the realistic working
 * set without holding onto stale bitmaps after a colour change. Customising
 * a colour invalidates only that garment's entry (different cache key).
 */
private val bitmapCache = object : LinkedHashMap<BitmapCacheKey, Bitmap>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<BitmapCacheKey, Bitmap>): Boolean = size > MAX
    private val MAX = 32
}

/**
 * Builds the original-ARGB → new-ARGB substitution map for one garment.
 * [customFill] of null returns an empty map — the renderer then leaves
 * every path's colour untouched, which is byte-identical to the unchanged
 * XML.
 */
private fun buildRecolorMap(defaults: GarmentDefaults, customFill: Color?): Map<Int, Int> {
    if (customFill == null) return emptyMap()
    val newFill = customFill.toArgb()
    val newStroke = deriveStroke(customFill).toArgb()
    return mapOf(
        defaults.fillArgb to newFill,
        defaults.strokeArgb to newStroke,
    )
}
