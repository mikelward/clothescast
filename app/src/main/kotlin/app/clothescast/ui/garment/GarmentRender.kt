package app.clothescast.ui.garment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.PathParser as AndroidPathParser
import app.clothescast.R
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.TemperatureBand
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.WindSpeedUnit
import app.clothescast.core.domain.model.symbol
import app.clothescast.core.domain.model.toUnit
import app.clothescast.core.domain.model.toWindSpeedUnit
import app.clothescast.insight.InsightFormatter
import java.io.ByteArrayOutputStream
import kotlin.math.pow
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
 * (or the baked-in default when [customFill] is null). The stroke / outline
 * detail colour normally auto-derives as a darker shade of [customFill]
 * (the two-tone look that survives the recolour); pass a non-null
 * [customStroke] to override that with a chosen colour — used by the
 * holiday-theme palette to put a contrasting accent (e.g. a green collar
 * on a yellow Australia-Day shirt) on top of the primary fill.
 */
@Composable
internal fun GarmentTopIcon(
    top: app.clothescast.core.domain.model.OutfitSuggestion.Top,
    customFill: Color?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    customStroke: Color? = null,
) {
    val defaults = outfitTopDefaults.getValue(top)
    GarmentIconImpl(
        drawableRes = topDrawable(top),
        defaults = defaults,
        customFill = customFill,
        customStroke = customStroke,
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
    customStroke: Color? = null,
) {
    val defaults = outfitBottomDefaults.getValue(bottom)
    GarmentIconImpl(
        drawableRes = bottomDrawable(bottom),
        defaults = defaults,
        customFill = customFill,
        customStroke = customStroke,
        contentDescription = contentDescription,
        modifier = modifier,
    )
}

@Composable
private fun GarmentIconImpl(
    @DrawableRes drawableRes: Int,
    defaults: GarmentDefaults,
    customFill: Color?,
    customStroke: Color?,
    contentDescription: String,
    modifier: Modifier,
) {
    // Default-colour fast path: render the original vector via painterResource
    // so the existing snapshot tests stay byte-identical for users who haven't
    // customised. The Canvas-based recolour path only kicks in when a custom
    // fill (or stroke) is set. A customStroke without a customFill is a
    // misconfiguration — there's no original-colour fast-path that mixes a
    // baked fill with a chosen stroke, so we still need the recolour path.
    if (customFill == null && customStroke == null) {
        Image(
            painter = painterResource(drawableRes),
            contentDescription = contentDescription,
            modifier = modifier,
        )
        return
    }
    val context = LocalContext.current
    val vector = remember(drawableRes) { loadOutfitVector(context, drawableRes) }
    val recolor = remember(customFill, customStroke, defaults) {
        buildRecolorMap(defaults, customFill, customStroke)
    }
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
 * `(drawableRes, fillArgb, strokeArgb, sizePx)` so rapid widget refreshes
 * don't re-rasterize. [customFillArgb] = null preserves the baked-in
 * two-tone; [customStrokeArgb] when non-null overrides the auto-derived
 * darker stroke with a chosen accent colour (e.g. the contrasting third
 * colour in tricolour holiday themes).
 */
internal fun renderOutfitBitmap(
    context: Context,
    @DrawableRes drawableRes: Int,
    defaults: GarmentDefaults,
    customFillArgb: Long?,
    sizePx: Int,
    customStrokeArgb: Long? = null,
): Bitmap {
    require(sizePx > 0) { "sizePx must be positive, got $sizePx" }
    val cacheKey = BitmapCacheKey(drawableRes, customFillArgb, customStrokeArgb, sizePx)
    bitmapCache[cacheKey]?.let { return it }
    val bitmap = if (customFillArgb == null && customStrokeArgb == null) {
        // Default-colour fast path: lean on the platform's VectorDrawable
        // rasterizer so the bitmap matches what users have always seen on
        // notifications + widgets pre-customisation.
        renderDefaultBitmap(context, drawableRes, sizePx)
    } else {
        renderRecoloredBitmap(context, drawableRes, defaults, customFillArgb, customStrokeArgb, sizePx)
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
    customFillArgb: Long?,
    customStrokeArgb: Long?,
    sizePx: Int,
): Bitmap {
    val vector = loadOutfitVector(context, drawableRes)
    val recolor = buildRecolorMap(
        defaults,
        customFillArgb?.let { Color(it.toInt()) },
        customStrokeArgb?.let { Color(it.toInt()) },
    )
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
    val customStrokeArgb: Long?,
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
    tempFillFraction: Float,
    rainFillFraction: Float?,
    topColors: Map<OutfitSuggestion.Top, Long>,
    bottomColors: Map<OutfitSuggestion.Bottom, Long>,
    topStrokes: Map<OutfitSuggestion.Top, Long> = emptyMap(),
    bottomStrokes: Map<OutfitSuggestion.Bottom, Long> = emptyMap(),
): ByteArray {
    val bmp = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(android.graphics.Color.WHITE)

    val proseX = CARD_PAD + ICON_PX + ICON_H_GAP

    // Icons go in the left column from the top, independent of the header
    // which lives above the prose on the right.
    val topBmp = renderOutfitBitmap(
        context = context,
        drawableRes = topDrawable(outfit.top),
        defaults = outfitTopDefaults.getValue(outfit.top),
        customFillArgb = topColors[outfit.top],
        sizePx = ICON_PX,
        customStrokeArgb = topStrokes[outfit.top],
    )
    val botBmp = renderOutfitBitmap(
        context = context,
        drawableRes = bottomDrawable(outfit.bottom),
        defaults = outfitBottomDefaults.getValue(outfit.bottom),
        customFillArgb = bottomColors[outfit.bottom],
        sizePx = ICON_PX,
        customStrokeArgb = bottomStrokes[outfit.bottom],
    )
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
    drawInfoRow(canvas, tempLine, proseX, tempRowTop, infoPaint) { c, ix, iy ->
        drawThermometerIcon(c, ix, iy, INFO_ICON_PX, tempFillFraction)
    }
    if (rainLine != null && rainFillFraction != null) {
        drawInfoRow(canvas, rainLine, proseX, rainRowTop, infoPaint) { c, ix, iy ->
            drawRainDropletIcon(c, ix, iy, INFO_ICON_PX, rainFillFraction)
        }
    }

    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    bmp.recycle()
    return out.toByteArray()
}

private fun drawInfoRow(
    canvas: Canvas,
    text: String,
    x: Int,
    y: Int,
    paint: TextPaint,
    drawIcon: (Canvas, Int, Int) -> Unit,
) {
    drawIcon(canvas, x, y)
    // Centre the text vertically against the icon.
    val textX = x + INFO_ICON_PX + INFO_ICON_GAP_PX
    val textCenterY = y + INFO_ICON_PX / 2f
    val textBaseline = textCenterY - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
    canvas.drawText(text, textX.toFloat(), textBaseline, paint)
}

// Material thermostat / thermometer silhouette in a 24×24 viewport. Same
// path data that previously lived in res/drawable/ic_outfit_card_thermometer.xml,
// inlined here so the renderer can draw it procedurally with a partial fill.
private const val THERMOMETER_PATH =
    "M15,13V5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v8c-1.21,0.91 -2,2.37 -2,4 0,2.76 " +
        "2.24,5 5,5s5,-2.24 5,-5c0,-1.63 -0.79,-3.09 -2,-4z"
// y-extent of the full thermometer silhouette in the 24-unit viewport.
// Top of the rounded cap sits at y=2; the bulb bottoms out at y≈22. Used
// as the liquid-column travel so the visible red area is proportional to
// fillFraction across the *whole* icon — a 19 % fill reads as 19 % red,
// not "stem 19 % full + bulb 100 % full" which previously made anything
// above freezing look at least half-coloured because the bulb alone is
// roughly 45 % of the icon's height.
private const val THERMOMETER_FILL_TOP = 2f
private const val THERMOMETER_FILL_BOTTOM = 22f

// Material rain-droplet silhouette in a 24×24 viewport. Previously lived in
// res/drawable/ic_outfit_card_rain.xml.
private const val DROPLET_PATH =
    "M12,3.77L11.25,4.61C11.25,4.61 9.97,6.06 8.68,7.94C7.39,9.82 6,12.07 6," +
        "14.23A6,6 0 0,0 12,20.23A6,6 0 0,0 18,14.23C18,12.07 16.61,9.82 15.32," +
        "7.94C14.03,6.06 12.75,4.61 12.75,4.61L12,3.77Z"
private const val DROPLET_TOP = 3.77f
private const val DROPLET_BOTTOM = 20.23f
// Exponent applied to (1 − fill) before scaling across the droplet's
// y-range. < 1 raises [liquidTopY] for high fill values to compensate
// for the teardrop's narrow tip carrying very little area — 0.7 is a
// rough fit to the path's cumulative-area-vs-height curve so 50 % fill
// looks ≈ 50 % blue and 85 % fill looks meaningfully less than 100 %.
private const val DROPLET_AREA_EXPONENT = 0.7

// Material `air` (wind) silhouette in a 24×24 viewport — the three flowing
// lines that universally read as wind. Drawn as a solid glyph tinted by the
// Beaufort scale rather than partially filled.
private const val AIR_PATH =
    "M14.5,17c0,1.65 -1.35,3 -3,3s-3,-1.35 -3,-3h2c0,0.55 0.45,1 1,1s1,-0.45 1,-1 " +
        "-0.45,-1 -1,-1H2v-2h9.5C13.15,14 14.5,15.35 14.5,17zM19,6.5C19,4.57 17.43,3 " +
        "15.5,3S12,4.57 12,6.5h2C14,5.67 14.67,5 15.5,5S17,5.67 17,6.5S16.33,8 15.5,8H2v2h13.5" +
        "C17.43,10 19,8.43 19,6.5zM18.5,11H2v2h16.5c0.83,0 1.5,0.67 1.5,1.5S19.33,17 18.5,17" +
        "S17,16.33 17,15.5h-2c0,1.93 1.57,3.5 3.5,3.5s3.5,-1.57 3.5,-3.5S20.43,11 18.5,11z"
// Material `wb_sunny` (sun + rays) in a 24×24 viewport, tinted by the WHO UV
// colour scale and paired with a "UV n" label so it doesn't read as a plain
// "sunny" condition glyph.
private const val SUN_PATH =
    "M6.76,4.84l-1.8,-1.79 -1.41,1.41 1.79,1.79 1.42,-1.41zM4,10.5L1,10.5v2h3v-2zM13,0.55h-2" +
        "L11,3.5h2L13,0.55zM20.45,4.46l-1.41,-1.41 -1.79,1.79 1.41,1.41 1.79,-1.79zM17.24,18.16" +
        "l1.79,1.8 1.41,-1.41 -1.8,-1.79 -1.4,1.4zM20,10.5v2h3v-2h-3zM12,5.5c-3.31,0 -6,2.69 " +
        "-6,6s2.69,6 6,6 6,-2.69 6,-6 -2.69,-6 -6,-6zM11,22.45h2L13,19.5h-2v2.95zM3.55,18.54" +
        "l1.41,1.41 1.79,-1.8 -1.41,-1.41 -1.79,1.8z"

// Coloured icon palette for the outfit-card info rows. Outline reads as a
// thin dark line against the white card; fill colours pop against it.
private const val THERMOMETER_FILL_ARGB = 0xFFE53935.toInt()
private const val DROPLET_FILL_ARGB = 0xFF1E88E5.toInt()
private const val INFO_ICON_OUTLINE_ARGB = 0xFF333333.toInt()
// Stroke width in 24-unit viewport coordinates; ≈2.25 px at INFO_ICON_PX=36.
private const val INFO_ICON_STROKE_WIDTH = 1.5f

/**
 * Draws a coloured thermometer at ([x], [y]) sized [size]×[size]. The
 * whole silhouette — bulb included — fills upward in proportion to
 * [fillFraction] (clamped to 0..1), so the visible red area tracks
 * temperature linearly. A thin outline traces the silhouette so the icon
 * stays legible even when the column is empty; [interiorArgb] / [outlineArgb]
 * default to the white-card palette but can be themed for a dark widget.
 */
private fun drawThermometerIcon(
    canvas: Canvas,
    x: Int,
    y: Int,
    size: Int,
    fillFraction: Float,
    interiorArgb: Int = android.graphics.Color.WHITE,
    outlineArgb: Int = INFO_ICON_OUTLINE_ARGB,
) {
    val fill = fillFraction.coerceIn(0f, 1f)
    val liquidTopY = THERMOMETER_FILL_TOP +
        (1f - fill) * (THERMOMETER_FILL_BOTTOM - THERMOMETER_FILL_TOP)
    drawFillableInfoIcon(
        canvas = canvas,
        x = x,
        y = y,
        size = size,
        pathData = THERMOMETER_PATH,
        fillArgb = THERMOMETER_FILL_ARGB,
        liquidTopY = liquidTopY,
        interiorArgb = interiorArgb,
        outlineArgb = outlineArgb,
    )
}

/**
 * Draws a coloured rain droplet at ([x], [y]) sized [size]×[size]. The
 * droplet fills from the bottom upward in proportion to [fillFraction]
 * (clamped to 0..1) — at the 30 % display threshold the droplet still
 * carries a clear sliver of blue; at 100 % the whole droplet is filled.
 *
 * The teardrop's area is concentrated in the rounded bottom and tapers
 * to a narrow point at the top, so a linear y-fill made high values
 * (e.g. 85 %) read as visually ~97 % filled — only the thin tip stayed
 * empty. The [DROPLET_AREA_EXPONENT] correction lifts [liquidTopY] so
 * the visible blue area tracks fillFraction more closely; e.g. 85 %
 * fill now leaves a visible empty cap, distinct from a 100 % full
 * droplet.
 */
private fun drawRainDropletIcon(
    canvas: Canvas,
    x: Int,
    y: Int,
    size: Int,
    fillFraction: Float,
    interiorArgb: Int = android.graphics.Color.WHITE,
    outlineArgb: Int = INFO_ICON_OUTLINE_ARGB,
) {
    val fill = fillFraction.coerceIn(0f, 1f)
    val emptyFraction = (1f - fill).toDouble().pow(DROPLET_AREA_EXPONENT).toFloat()
    val liquidTopY = DROPLET_TOP + emptyFraction * (DROPLET_BOTTOM - DROPLET_TOP)
    drawFillableInfoIcon(
        canvas = canvas,
        x = x,
        y = y,
        size = size,
        pathData = DROPLET_PATH,
        fillArgb = DROPLET_FILL_ARGB,
        liquidTopY = liquidTopY,
        interiorArgb = interiorArgb,
        outlineArgb = outlineArgb,
    )
}

/**
 * Shared render path for the partial-fill info-row glyphs. Order:
 * white interior → coloured fill clipped to `[liquidTopY, 24]` → dark
 * outline on top. Doing the outline last keeps the silhouette crisp at
 * every fill level — drawing it underneath would let the fill paint hide
 * the inner edge.
 */
private fun drawFillableInfoIcon(
    canvas: Canvas,
    x: Int,
    y: Int,
    size: Int,
    pathData: String,
    fillArgb: Int,
    liquidTopY: Float,
    liquidBottomY: Float = 24f,
    interiorArgb: Int = android.graphics.Color.WHITE,
    outlineArgb: Int = INFO_ICON_OUTLINE_ARGB,
) {
    val path = AndroidPathParser.createPathFromPathData(pathData)
    val scale = size.toFloat() / 24f
    val interiorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = interiorArgb
    }
    val colourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillArgb
    }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = outlineArgb
        strokeWidth = INFO_ICON_STROKE_WIDTH
    }
    canvas.save()
    canvas.translate(x.toFloat(), y.toFloat())
    canvas.scale(scale, scale)
    canvas.drawPath(path, interiorPaint)
    canvas.save()
    canvas.clipRect(0f, liquidTopY, 24f, liquidBottomY)
    canvas.drawPath(path, colourPaint)
    canvas.restore()
    canvas.drawPath(path, strokePaint)
    canvas.restore()
}

/**
 * Draws a fully-filled glyph (no partial-fill metaphor) at ([x], [y]) sized
 * [size]×[size], tinted [fillArgb] with a thin [outlineArgb] edge for legibility
 * against either a light or dark background. Used by the wind / UV cells, whose
 * value is conveyed by the scale tint + label rather than a fill level.
 */
private fun drawSolidGlyph(
    canvas: Canvas,
    x: Int,
    y: Int,
    size: Int,
    pathData: String,
    fillArgb: Int,
    outlineArgb: Int,
) {
    val path = AndroidPathParser.createPathFromPathData(pathData)
    val scale = size.toFloat() / 24f
    canvas.save()
    canvas.translate(x.toFloat(), y.toFloat())
    canvas.scale(scale, scale)
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = fillArgb })
    canvas.drawPath(
        path,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = outlineArgb
            strokeWidth = INFO_ICON_STROKE_WIDTH
        },
    )
    canvas.restore()
}

/**
 * Computes the two info-row strings shown beneath the prose on the outfit
 * card. Pulled out so [MainActivity] and [FetchAndNotifyWorker] share the
 * same min/max feels-like and peak-rain logic. Returns `tempLine` empty
 * when [hourly] is empty (no horizontal info to show), and `rainLine`
 * null when the windowed peak rain probability is below
 * [RAIN_PEAK_THRESHOLD_PCT] — the renderer then hides that row entirely.
 */
internal data class OutfitCardInfoLines(
    val tempLine: String,
    val rainLine: String?,
    // Day's high feels-like mapped over 0..40 °C, clamped — drives the
    // thermometer's red liquid height. Calculation runs in °C regardless of
    // the user's display unit (which only affects [tempLine]).
    val tempFillFraction: Float,
    // Peak precipitation probability / 100 — drives the droplet's blue fill.
    // Null whenever [rainLine] is null (row hidden below the 30 % threshold).
    val rainFillFraction: Float?,
    // Compact peak-rain label without the "Peak" lead-in, for the conditions
    // widget. Null whenever [rainLine] is. Default null for card callers.
    val rainLineShort: String? = null,
    // Conditions-widget wind / UV cells. Each label is null unless the period's
    // peak is "notable" (>= WIND_NOTABLE_KMH / UV_NOTABLE); the raw maximum is
    // carried alongside so the renderer can pick the Beaufort / WHO scale tint.
    // Defaults keep the outfit card (which ignores these) unaffected.
    val windLabel: String? = null,
    val windMaxKmh: Double? = null,
    val uvLabel: String? = null,
    val uvMax: Double? = null,
)

internal fun outfitCardInfoLines(
    context: Context,
    formatter: InsightFormatter,
    hourly: List<HourlyForecast>,
    temperatureUnit: TemperatureUnit,
    windSpeedUnit: WindSpeedUnit = WindSpeedUnit.KMH,
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
    val tempFillFraction = highC?.let { thermometerFillFractionFor(it) } ?: 0f
    val peak = hourly.maxByOrNull { it.precipitationProbabilityPct }
    val peakPct = peak?.precipitationProbabilityPct?.roundToInt()
    val rainLine: String?
    val rainLineShort: String?
    val rainFillFraction: Float?
    if (peak != null && peakPct != null && peakPct >= RAIN_PEAK_THRESHOLD_PCT) {
        rainLine = formatter.formatPeakRain(peakPct, peak.time)
        rainLineShort = formatter.formatPeakRainShort(peakPct, peak.time)
        rainFillFraction = (peakPct / 100f).coerceIn(0f, 1f)
    } else {
        rainLine = null
        rainLineShort = null
        rainFillFraction = null
    }
    // Wind / UV: take the period's peak and only surface it when notable.
    val windMaxKmh = hourly.mapNotNull { it.windSpeedKmh }.maxOrNull()
        ?.takeIf { it >= WIND_NOTABLE_KMH }
    val windLabel = windMaxKmh?.let {
        context.getString(
            R.string.conditions_wind,
            it.toWindSpeedUnit(windSpeedUnit).roundToInt(),
            windSpeedUnit.symbol(),
        )
    }
    val uvMax = hourly.mapNotNull { it.uvIndex }.maxOrNull()
        ?.takeIf { it >= UV_NOTABLE }
    val uvLabel = uvMax?.let { context.getString(R.string.conditions_uv, it.roundToInt()) }
    return OutfitCardInfoLines(
        tempLine = tempLine,
        rainLine = rainLine,
        rainLineShort = rainLineShort,
        tempFillFraction = tempFillFraction,
        rainFillFraction = rainFillFraction,
        windLabel = windLabel,
        windMaxKmh = windMaxKmh,
        uvLabel = uvLabel,
        uvMax = uvMax,
    )
}

/** Which glyph a [ConditionsCell] draws. */
internal enum class ConditionsGlyph { THERMOMETER, DROPLET, WIND, UV }

/**
 * One indicator on the conditions strip: a glyph plus its label. Thermometer /
 * droplet read [fillFraction] (a partial fill); the solid wind / UV glyphs read
 * [tintArgb] (their Beaufort / WHO scale colour, null → theme outline colour).
 */
internal data class ConditionsCell(
    val glyph: ConditionsGlyph,
    val label: String,
    val fillFraction: Float = 0f,
    val tintArgb: Int? = null,
)

/**
 * Rasterizes a horizontal "conditions strip" — a row of [cells] (feels-like
 * thermometer, rain droplet, and optionally wind / UV) — into a [Bitmap] for
 * the home-screen conditions widget (via [androidx.glance.ImageProvider]).
 * Reuses the same glyph primitives the outfit card draws, so the widget and the
 * card stay in lockstep. Cells are laid out left-to-right and centred; content
 * wider than the bitmap is scaled down to fit rather than clipped.
 *
 * [darkTheme] swaps the glyph interior / outline / text colours so the strip
 * reads on a dark widget background; the thermometer red / droplet blue and the
 * wind / UV scale tints stay constant since they pop on either background.
 * Memoised on the full input tuple (cells + size + theme) so frequent widget
 * refreshes don't re-rasterize.
 */
internal fun renderConditionsStripBitmap(
    context: Context,
    cells: List<ConditionsCell>,
    widthPx: Int,
    heightPx: Int,
    darkTheme: Boolean,
): Bitmap {
    require(widthPx > 0 && heightPx > 0) {
        "conditions strip size must be positive, got ${widthPx}×$heightPx"
    }
    require(cells.isNotEmpty()) { "conditions strip needs at least one cell" }
    val key = ConditionsStripCacheKey(cells, widthPx, heightPx, darkTheme)
    conditionsStripCache[key]?.let { return it }

    // Defensively cap the raster height; the Image upscales the bounded bitmap
    // to fill the cell, keeping a stretched widget from allocating an outsized
    // ARGB buffer. Width scales with it to preserve the strip's aspect.
    val scale = if (heightPx > MAX_STRIP_HEIGHT_PX) MAX_STRIP_HEIGHT_PX.toFloat() / heightPx else 1f
    val w = (widthPx * scale).roundToInt().coerceAtLeast(1)
    val h = (heightPx * scale).roundToInt().coerceAtLeast(1)

    val interiorArgb = if (darkTheme) STRIP_DARK_INTERIOR_ARGB else android.graphics.Color.WHITE
    val outlineArgb = if (darkTheme) STRIP_DARK_OUTLINE_ARGB else INFO_ICON_OUTLINE_ARGB
    val textArgb = if (darkTheme) STRIP_DARK_TEXT_ARGB else STRIP_LIGHT_TEXT_ARGB

    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap) // transparent — the widget Box paints its own background

    val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textArgb
        textSize = h * STRIP_TEXT_HEIGHT_FRACTION
        typeface = Typeface.DEFAULT_BOLD
    }
    var iconPx = (h * STRIP_ICON_HEIGHT_FRACTION).roundToInt().coerceAtLeast(1)
    var iconGap = iconPx * STRIP_ICON_TEXT_GAP_FRACTION
    var sectionGap = iconPx * STRIP_SECTION_GAP_FRACTION

    fun contentWidth(): Float =
        cells.sumOf { (iconPx + iconGap + textPaint.measureText(it.label)).toDouble() }.toFloat() +
            sectionGap * (cells.size - 1)

    // Shrink icon + text together so a full row (thermometer + rain + wind + UV)
    // on a short-wide cell scales down to fit rather than clipping off the edge.
    val usableWidth = w * STRIP_USABLE_WIDTH_FRACTION
    val fitScale = (usableWidth / contentWidth()).coerceIn(STRIP_MIN_FIT_SCALE, 1f)
    if (fitScale < 1f) {
        iconPx = (iconPx * fitScale).roundToInt().coerceAtLeast(1)
        iconGap *= fitScale
        sectionGap *= fitScale
        textPaint.textSize *= fitScale
    }

    val cellWidths = cells.map { iconPx + iconGap + textPaint.measureText(it.label) }
    val totalWidth = cellWidths.sum() + sectionGap * (cells.size - 1)
    var x = ((w - totalWidth) / 2f).coerceAtLeast(0f)
    val iconTop = (h - iconPx) / 2
    val textCenterY = iconTop + iconPx / 2f
    val baseline = textCenterY - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f

    cells.forEachIndexed { i, cell ->
        val ix = x.roundToInt()
        when (cell.glyph) {
            ConditionsGlyph.THERMOMETER ->
                drawThermometerIcon(canvas, ix, iconTop, iconPx, cell.fillFraction, interiorArgb, outlineArgb)
            ConditionsGlyph.DROPLET ->
                drawRainDropletIcon(canvas, ix, iconTop, iconPx, cell.fillFraction, interiorArgb, outlineArgb)
            ConditionsGlyph.WIND ->
                drawSolidGlyph(canvas, ix, iconTop, iconPx, AIR_PATH, cell.tintArgb ?: outlineArgb, outlineArgb)
            ConditionsGlyph.UV ->
                drawSolidGlyph(canvas, ix, iconTop, iconPx, SUN_PATH, cell.tintArgb ?: outlineArgb, outlineArgb)
        }
        canvas.drawText(cell.label, ix + iconPx + iconGap, baseline, textPaint)
        x += cellWidths[i] + sectionGap
    }

    conditionsStripCache[key] = bitmap
    return bitmap
}

private data class ConditionsStripCacheKey(
    val cells: List<ConditionsCell>,
    val widthPx: Int,
    val heightPx: Int,
    val darkTheme: Boolean,
)

// Small bound: a placed conditions widget hits a handful of distinct
// (size, theme, label) tuples across a refresh cycle. Mirrors [bitmapCache].
private val conditionsStripCache = object : LinkedHashMap<ConditionsStripCacheKey, Bitmap>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<ConditionsStripCacheKey, Bitmap>): Boolean = size > MAX
    private val MAX = 16
}

// Geometry of the conditions strip, expressed as fractions of the render
// height so the layout scales cleanly with the widget cell.
private const val STRIP_ICON_HEIGHT_FRACTION = 0.62f
private const val STRIP_TEXT_HEIGHT_FRACTION = 0.34f
private const val STRIP_ICON_TEXT_GAP_FRACTION = 0.18f
private const val STRIP_SECTION_GAP_FRACTION = 0.7f
// Fraction of the bitmap width the content may occupy before it's scaled to
// fit, leaving a small horizontal margin so icons/text don't touch the edge.
private const val STRIP_USABLE_WIDTH_FRACTION = 0.94f
// Don't shrink below this — past it a tiny illegible strip is worse than light
// clipping, and the user can resize the widget wider.
private const val STRIP_MIN_FIT_SCALE = 0.5f
private const val MAX_STRIP_HEIGHT_PX = 240

// Dark-theme glyph palette for the strip: a near-background interior so the
// thermometer/droplet's empty region disappears into the widget, a light
// outline to trace the silhouette, and near-white text. Light theme keeps the
// outfit card's white interior + #333 outline.
private const val STRIP_DARK_INTERIOR_ARGB = 0xFF2B2B2B.toInt()
private const val STRIP_DARK_OUTLINE_ARGB = 0xFFCCCCCC.toInt()
private const val STRIP_DARK_TEXT_ARGB = 0xFFECECEC.toInt()
private const val STRIP_LIGHT_TEXT_ARGB = 0xFF1A1A1A.toInt()

// Widget surface (Box background) behind the strip. The renderer draws on a
// transparent bitmap — the widget paints these — but they're the single source
// of truth so [ConditionsWidget] and the snapshot tests stay in lockstep. M3
// light/dark surface neutrals.
internal const val STRIP_SURFACE_LIGHT_ARGB = 0xFFFEF7FF.toInt()
internal const val STRIP_SURFACE_DARK_ARGB = 0xFF1C1B1F.toInt()

private const val RAIN_PEAK_THRESHOLD_PCT = 30

// "Notable" thresholds — below these the wind / UV cells are hidden, matching
// the strip's principle of surfacing a metric only when it's worth acting on.
internal const val WIND_NOTABLE_KMH = 30.0
internal const val UV_NOTABLE = 6.0

/**
 * Beaufort-flavoured colour for a wind speed in km/h: amber (fresh breeze) →
 * orange (strong) → red (gale) → violet (storm). Since the cell only shows ≥
 * [WIND_NOTABLE_KMH] the user always sees amber-or-worse, escalating with
 * strength. Reads on both light and dark widget backgrounds.
 */
internal fun windScaleColorArgb(kmh: Double): Int = when {
    kmh < 40.0 -> 0xFFF9A825.toInt() // amber — Beaufort 5, fresh breeze
    kmh < 55.0 -> 0xFFEF6C00.toInt() // orange — Beaufort 6–7, strong/near gale
    kmh < 75.0 -> 0xFFD50000.toInt() // red — Beaufort 8, gale
    else -> 0xFF6A1B9A.toInt() // violet — Beaufort 9+, storm
}

/**
 * WHO UV-index colour scale: green (low) → yellow → orange → red → violet
 * (extreme). The cell only shows ≥ [UV_NOTABLE], so in practice the user sees
 * orange-or-worse.
 */
internal fun uvScaleColorArgb(index: Double): Int = when {
    index < 3.0 -> 0xFF558B2F.toInt() // green — low
    index < 6.0 -> 0xFFF9A825.toInt() // yellow — moderate
    index < 8.0 -> 0xFFEF6C00.toInt() // orange — high
    index < 11.0 -> 0xFFD50000.toInt() // red — very high
    else -> 0xFF6A1B9A.toInt() // violet — extreme
}

// Anchors for the saturated tails of the thermometer scale — below
// [THERMOMETER_FREEZING_FLOOR_C] the column reads empty, above
// [THERMOMETER_HOT_CAP_C] it reads full. The interior breakpoints come
// from [TemperatureBand].
private const val THERMOMETER_FREEZING_FLOOR_C = -10.0
private const val THERMOMETER_HOT_CAP_C = 40.0

/**
 * Maps a feels-like temperature in °C to a 0..1 thermometer fill that
 * lines up with the domain's [TemperatureBand] classification — the same
 * bands the user sees in their clothing rules (FREEZING, COLD, COOL,
 * MILD, WARM, HOT). Each band occupies one sixth of the column, so the
 * "MILD" band (18–24 °C) reads as 50–67 % full, "WARM" as 67–83 %, and
 * so on. Within a band the fill interpolates linearly, so a barely-HOT
 * 28 °C reads lower than a 38 °C scorcher.
 *
 * Mirrors the breakpoints in [TemperatureBand.forCelsius] (4 / 12 / 18 /
 * 24 / 28 °C). The outer anchors clamp anything below
 * [THERMOMETER_FREEZING_FLOOR_C] or above [THERMOMETER_HOT_CAP_C].
 */
internal fun thermometerFillFractionFor(c: Double): Float {
    val band = TemperatureBand.forCelsius(c)
    val (lower, upper) = when (band) {
        TemperatureBand.FREEZING -> THERMOMETER_FREEZING_FLOOR_C to 4.0
        TemperatureBand.COLD -> 4.0 to 12.0
        TemperatureBand.COOL -> 12.0 to 18.0
        TemperatureBand.MILD -> 18.0 to 24.0
        TemperatureBand.WARM -> 24.0 to 28.0
        TemperatureBand.HOT -> 28.0 to THERMOMETER_HOT_CAP_C
    }
    val withinBand = ((c - lower) / (upper - lower)).coerceIn(0.0, 1.0)
    val bandCount = TemperatureBand.values().size
    return ((band.ordinal + withinBand) / bandCount).toFloat().coerceIn(0f, 1f)
}

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
 *
 * - Both args null → empty map; renderer leaves the vector's colours
 *   untouched (byte-identical to the unchanged XML).
 * - [customFill] only → fill is swapped, stroke auto-derives as a darker
 *   shade of the fill via [deriveStroke] (the long-standing two-tone look).
 * - [customStroke] non-null → stroke is set to that exact colour, overriding
 *   the auto-derive. Used by holiday themes to paint a contrasting accent
 *   (yellow shirt with green collar / sleeves; red bottom with white trim).
 * - [customStroke] without [customFill] is supported for completeness but
 *   leaves the original fill colour intact.
 */
private fun buildRecolorMap(
    defaults: GarmentDefaults,
    customFill: Color?,
    customStroke: Color? = null,
): Map<Int, Int> {
    if (customFill == null && customStroke == null) return emptyMap()
    val newStroke = (customStroke ?: customFill?.let { deriveStroke(it) })?.toArgb()
    val entries = mutableMapOf<Int, Int>()
    customFill?.let { entries[defaults.fillArgb] = it.toArgb() }
    newStroke?.let { entries[defaults.strokeArgb] = it }
    return entries
}
