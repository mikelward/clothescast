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
import app.clothescast.core.domain.model.symbol
import app.clothescast.core.domain.model.toUnit
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
 * temperature linearly. A thin dark outline traces the silhouette so the
 * icon stays legible against the white card even when the column is empty.
 */
private fun drawThermometerIcon(canvas: Canvas, x: Int, y: Int, size: Int, fillFraction: Float) {
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
private fun drawRainDropletIcon(canvas: Canvas, x: Int, y: Int, size: Int, fillFraction: Float) {
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
) {
    val path = AndroidPathParser.createPathFromPathData(pathData)
    val scale = size.toFloat() / 24f
    val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.WHITE
    }
    val colourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillArgb
    }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = INFO_ICON_OUTLINE_ARGB
        strokeWidth = INFO_ICON_STROKE_WIDTH
    }
    canvas.save()
    canvas.translate(x.toFloat(), y.toFloat())
    canvas.scale(scale, scale)
    canvas.drawPath(path, whitePaint)
    canvas.save()
    canvas.clipRect(0f, liquidTopY, 24f, 24f)
    canvas.drawPath(path, colourPaint)
    canvas.restore()
    canvas.drawPath(path, strokePaint)
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
)

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
    val tempFillFraction = highC?.let { thermometerFillFractionFor(it) } ?: 0f
    val peak = hourly.maxByOrNull { it.precipitationProbabilityPct }
    val peakPct = peak?.precipitationProbabilityPct?.roundToInt()
    val rainLine: String?
    val rainFillFraction: Float?
    if (peak != null && peakPct != null && peakPct >= RAIN_PEAK_THRESHOLD_PCT) {
        rainLine = formatter.formatPeakRain(peakPct, peak.time)
        rainFillFraction = (peakPct / 100f).coerceIn(0f, 1f)
    } else {
        rainLine = null
        rainFillFraction = null
    }
    return OutfitCardInfoLines(tempLine, rainLine, tempFillFraction, rainFillFraction)
}

private const val RAIN_PEAK_THRESHOLD_PCT = 30

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
