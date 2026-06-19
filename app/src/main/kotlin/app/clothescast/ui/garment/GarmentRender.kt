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
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import app.clothescast.R
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.TemperatureBand
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.WeatherCondition
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

/**
 * Renders an extremity-gear icon (today only gloves) for the optional
 * [OutfitSuggestion.hands] slot. Drawn at the same width as — and overlaid on
 * top of — the top garment icon, so the gloves land at the sides of the body;
 * see [renderTopWithHandsBitmap] for the bitmap-surface equivalent.
 */
@Composable
internal fun GarmentHandsIcon(
    hands: OutfitSuggestion.Hands,
    customFill: Color?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    customStroke: Color? = null,
) {
    val defaults = outfitHandsDefaults.getValue(hands)
    GarmentIconImpl(
        drawableRes = handsDrawable(hands),
        defaults = defaults,
        customFill = customFill,
        customStroke = customStroke,
        contentDescription = contentDescription,
        modifier = modifier,
    )
}

/**
 * Renders a carried-gear icon (today only the umbrella) for the optional
 * [OutfitSuggestion.carried] slot. The umbrella vector is authored at a
 * full-figure 96×192 viewport — held at the hip and hanging down past the legs
 * — so callers overlay it across the *whole* top+bottom figure (at width W it is
 * 2·W tall), leaving it in the empty space beside the body; see
 * [renderCarriedFigureBitmap] for the bitmap-surface equivalent.
 */
@Composable
internal fun GarmentCarriedIcon(
    carried: OutfitSuggestion.Carried,
    customFill: Color?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    customStroke: Color? = null,
) {
    val defaults = outfitCarriedDefaults.getValue(carried)
    GarmentIconImpl(
        drawableRes = carriedDrawable(carried),
        defaults = defaults,
        customFill = customFill,
        customStroke = customStroke,
        contentDescription = contentDescription,
        modifier = modifier,
    )
}

/**
 * Renders the optional outer-shell icon (today only the rain jacket) for the
 * [OutfitSuggestion.outer] slot. Authored at the same 96×96 viewport as the
 * tops, it's drawn at the top garment's width and overlaid on top of it, so the
 * shell sits over whatever warmth tier the rules picked; see
 * [renderTopWithHandsBitmap] for the bitmap-surface equivalent.
 */
@Composable
internal fun GarmentOuterIcon(
    outer: OutfitSuggestion.Outer,
    customFill: Color?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    customStroke: Color? = null,
) {
    val defaults = outfitOuterDefaults.getValue(outer)
    GarmentIconImpl(
        drawableRes = outerDrawable(outer),
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
    val bitmap = createBitmap(sizePx, sizePx)
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
 * The top garment bitmap with the optional [outer] (rain-jacket) shell and
 * [hands] (gloves) accessory composited over it at the same `sizePx`-square
 * footprint — the overlay look gloves use on the bitmap render surfaces
 * (notification / widget via [androidx.glance.ImageProvider], Nest-Hub card).
 * The gloves vector sits at the lower sides of its 96×96 viewport, so a
 * same-size overlay lands the gloves at the body's hands without any
 * per-surface alignment maths; the rain jacket shares the tops' 96×96 viewport,
 * so it lands over the torso the same way.
 *
 * Composite order is top → outer → hands: the rain jacket covers the torso of
 * the underlying top (it's the outer shell), and the gloves paint last so they
 * stay visible at the body's sides.
 *
 * When both [outer] and [hands] are null this returns the (cached) plain top
 * bitmap untouched, so the no-overlay path stays byte-identical to the previous
 * single-icon render — existing snapshots don't move. Only the overlay cases
 * allocate a composite.
 */
internal fun renderTopWithHandsBitmap(
    context: Context,
    top: OutfitSuggestion.Top,
    hands: OutfitSuggestion.Hands?,
    sizePx: Int,
    topFillArgb: Long? = null,
    topStrokeArgb: Long? = null,
    handsFillArgb: Long? = null,
    handsStrokeArgb: Long? = null,
    outer: OutfitSuggestion.Outer? = null,
    outerFillArgb: Long? = null,
    outerStrokeArgb: Long? = null,
): Bitmap {
    val topBmp = renderOutfitBitmap(
        context = context,
        drawableRes = topDrawable(top),
        defaults = outfitTopDefaults.getValue(top),
        customFillArgb = topFillArgb,
        sizePx = sizePx,
        customStrokeArgb = topStrokeArgb,
    )
    if (hands == null && outer == null) return topBmp
    val outerBmp = outer?.let {
        renderOutfitBitmap(
            context = context,
            drawableRes = outerDrawable(it),
            defaults = outfitOuterDefaults.getValue(it),
            customFillArgb = outerFillArgb,
            sizePx = sizePx,
            customStrokeArgb = outerStrokeArgb,
        )
    }
    val handsBmp = hands?.let {
        renderOutfitBitmap(
            context = context,
            drawableRes = handsDrawable(it),
            defaults = outfitHandsDefaults.getValue(it),
            customFillArgb = handsFillArgb,
            sizePx = sizePx,
            customStrokeArgb = handsStrokeArgb,
        )
    }
    val composite = createBitmap(sizePx, sizePx)
    Canvas(composite).apply {
        drawBitmap(topBmp, 0f, 0f, null)
        outerBmp?.let { drawBitmap(it, 0f, 0f, null) }
        handsBmp?.let { drawBitmap(it, 0f, 0f, null) }
    }
    return composite
}

/**
 * Renders the carried [carried] (umbrella) overlay as a transparent
 * [widthPx]×[heightPx] bitmap, for the bitmap surfaces (Nest-Hub card, widget)
 * to composite over the *whole* figure. The umbrella vector is authored at a
 * full-figure 96×192 viewport (held at the hip, hanging down past the legs), so
 * callers render it at `heightPx = 2·widthPx` — matching the vector's 1:2
 * aspect — and draw it spanning both the top and bottom icon positions, leaving
 * the umbrella in the empty space beside the body. Non-square because the
 * figure overlay isn't square; the recolour matches by original colour exactly
 * as [renderOutfitBitmap] does.
 */
internal fun renderCarriedFigureBitmap(
    context: Context,
    carried: OutfitSuggestion.Carried,
    widthPx: Int,
    heightPx: Int,
    customFillArgb: Long? = null,
    customStrokeArgb: Long? = null,
): Bitmap {
    require(widthPx > 0 && heightPx > 0) { "carried bitmap size must be positive, got ${widthPx}×$heightPx" }
    val vector = loadOutfitVector(context, carriedDrawable(carried))
    val recolor = buildRecolorMap(
        outfitCarriedDefaults.getValue(carried),
        customFillArgb?.let { Color(it.toInt()) },
        customStrokeArgb?.let { Color(it.toInt()) },
    )
    val bitmap = createBitmap(widthPx, heightPx)
    val canvas = Canvas(bitmap)
    canvas.scale(widthPx / vector.viewportWidth, heightPx / vector.viewportHeight)
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
 * │              🌡 18–28°C   🌬 35 km/h      │  ← feels-like low/high · wind
 * │              💧 60% at 3pm  ☀ UV 8        │  ← rain ≥ 20% or coded · UV ≥ 6
 * └──────────────────────────────────────────┘
 * ```
 * [header] is the localised, mixed-case "Today's ClothesCast" string from
 * resources — the renderer uppercases it. [info] carries the pre-formatted
 * temperature / rain / wind / UV lines and fill levels (from
 * [outfitCardInfoLines]); the rain row is hidden when `rainLine` is null and
 * the wind / UV second column appears only when those are notable.
 */
internal fun renderOutfitCard(
    context: Context,
    outfit: OutfitSuggestion,
    header: String,
    prose: String,
    info: OutfitCardInfoLines,
    topColors: Map<OutfitSuggestion.Top, Long>,
    bottomColors: Map<OutfitSuggestion.Bottom, Long>,
    topStrokes: Map<OutfitSuggestion.Top, Long> = emptyMap(),
    bottomStrokes: Map<OutfitSuggestion.Bottom, Long> = emptyMap(),
    handsColors: Map<OutfitSuggestion.Hands, Long> = emptyMap(),
    handsStrokes: Map<OutfitSuggestion.Hands, Long> = emptyMap(),
    carriedColors: Map<OutfitSuggestion.Carried, Long> = emptyMap(),
    carriedStrokes: Map<OutfitSuggestion.Carried, Long> = emptyMap(),
    outerColors: Map<OutfitSuggestion.Outer, Long> = emptyMap(),
    outerStrokes: Map<OutfitSuggestion.Outer, Long> = emptyMap(),
): ByteArray {
    val bmp = createBitmap(CARD_W, CARD_H)
    val canvas = Canvas(bmp)
    canvas.drawColor(android.graphics.Color.WHITE)

    val proseX = CARD_PAD + ICON_PX + ICON_H_GAP

    // Icons go in the left column from the top, independent of the header
    // which lives above the prose on the right. The top icon carries the
    // optional gloves overlay so the hands sit at the body's sides.
    val topBmp = renderTopWithHandsBitmap(
        context = context,
        top = outfit.top,
        hands = outfit.hands,
        sizePx = ICON_PX,
        topFillArgb = topColors[outfit.top],
        topStrokeArgb = topStrokes[outfit.top],
        handsFillArgb = outfit.hands?.let { handsColors[it] },
        handsStrokeArgb = outfit.hands?.let { handsStrokes[it] },
        outer = outfit.outer,
        outerFillArgb = outfit.outer?.let { outerColors[it] },
        outerStrokeArgb = outfit.outer?.let { outerStrokes[it] },
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

    // The umbrella is a full-figure overlay (held at the hip, hanging past the
    // legs), so it spans both icon bitmaps rather than sitting on one. Authored
    // at a 96×192 viewport, it renders at ICON_PX×(2·ICON_PX) over the top
    // icon's origin so its canopy runs down beside the legs. Only drawn when a
    // carried (umbrella) rule fired.
    outfit.carried?.let { carried ->
        val carriedBmp = renderCarriedFigureBitmap(
            context = context,
            carried = carried,
            widthPx = ICON_PX,
            heightPx = ICON_PX * 2,
            customFillArgb = carriedColors[carried],
            customStrokeArgb = carriedStrokes[carried],
        )
        canvas.drawBitmap(carriedBmp, CARD_PAD.toFloat(), CARD_PAD.toFloat(), null)
    }

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
        canvas.withTranslation(proseX.toFloat(), proseTop.toFloat()) {
            layout.draw(this)
        }
    }

    // A single conditions row anchored to the bottom of the right column —
    // same horizontal strip the home-screen widget shows, drawn here onto the
    // card so every surface reads identically. Temp + rain are the common case;
    // wind / UV extend the row only when notable. Left-aligned with the prose;
    // shrinks to fit the column width when all four cells are present.
    val infoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = INFO_PX
        color = 0xFF1A1A1A.toInt()
    }
    val rowCenterY = (CARD_H - INFO_BOTTOM_PAD - INFO_ICON_PX / 2).toFloat()
    drawConditionsRow(
        canvas = canvas,
        cells = conditionsCells(info),
        areaX = proseX.toFloat(),
        areaWidth = (CARD_W - proseX - CARD_PAD).toFloat(),
        centerY = rowCenterY,
        baseIconPx = INFO_ICON_PX,
        textPaint = infoPaint,
        interiorArgb = android.graphics.Color.WHITE,
        outlineArgb = INFO_ICON_OUTLINE_ARGB,
        center = false,
    )

    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    bmp.recycle()
    return out.toByteArray()
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
// Outline width for the fill-metaphor glyphs (thermometer, droplet) in 24-unit
// viewport coordinates; ≈1.5 px at INFO_ICON_PX=36. Their broad silhouettes
// carry a heavier edge than the thin-featured sun / wind, but staying under the
// old 1.5 keeps the outline from reading as chunky against the fill.
private const val INFO_ICON_STROKE_WIDTH = 1.0f
// The solid scale-tinted glyphs are thin-featured: the sun (Material
// `wb_sunny`) is a disc plus eight ~1.4-unit rays, and the wind (`air`) is
// three flowing lines of similar width. At the full INFO_ICON_STROKE_WIDTH a
// centered outline nearly swallows each one, so the glyph reads as mostly
// black outline rather than a tinted shape. A thinner edge keeps the
// silhouette legible while letting the scale-tinted fill show through.
private const val THIN_GLYPH_STROKE_WIDTH = 0.75f

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
 * (clamped to 0..1) — at low peak chances (a drizzle-coded hour, or a peak just
 * over the 20 % gate) the droplet shows only a faint sliver of blue (the outline
 * and the "%" label carry the reading); at 100 % the whole droplet is filled.
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
    outlineWidth: Float = INFO_ICON_STROKE_WIDTH,
) {
    val path = AndroidPathParser.createPathFromPathData(pathData)
    val scale = size.toFloat() / 24f
    canvas.withTranslation(x.toFloat(), y.toFloat()) {
        scale(scale, scale)
        drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = fillArgb })
        drawPath(
            path,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = outlineArgb
                strokeWidth = outlineWidth
            },
        )
    }
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
    // Day's high feels-like mapped over 0..40 °C, clamped — drives the
    // thermometer's red liquid height. Calculation runs in °C regardless of
    // the user's display unit (which only affects [tempLine]).
    val tempFillFraction: Float,
    // Peak precipitation probability / 100 — drives the droplet's blue fill.
    // Null whenever [rainLineShort] is null (cell hidden when the peak chance of
    // rain is below the probability gate).
    val rainFillFraction: Float?,
    // Peak chance-of-rain label ("60%"), shown on the conditions strip. Null when
    // the cell is hidden. Default null for callers that don't compute it.
    val rainLineShort: String? = null,
    // Wind / UV cells. Each label is null unless the period's peak is "notable"
    // (wind >= WIND_NOTABLE_KMH; UV once it rounds to >= UV_NOTABLE, matching the
    // integer the label shows); the raw maximum rides alongside so the renderer
    // can pick the Beaufort / WHO scale tint.
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
    // Peak *chance of rain*: snow is excluded so a snowy day never lights the
    // rain droplet. Open-Meteo's precipitation_probability is general (rain or
    // snow), and snow WMO codes map to WeatherCondition.SNOW, so taking the peak
    // over non-snow hours keeps a 25%-snow hour from reading as "25% rain". A
    // dry-coded but likely hour (the "overcast at 88%" case) is still a chance of
    // rain and stays in.
    val rainPeak = hourly
        .filter { it.condition != WeatherCondition.SNOW }
        .maxByOrNull { it.precipitationProbabilityPct }
    val peakPct = rainPeak?.precipitationProbabilityPct?.roundToInt()
    val rainLineShort: String?
    val rainFillFraction: Float?
    // Show the rain cell purely on the blended-consensus peak chance of rain at/above
    // the gate — the same number the prose's chance-of-rain bar and the umbrella
    // default key off, so all three surface rain together. The label shows the
    // honest peak chance, so a low-probability hour reads as the percentage it is.
    if (rainPeak != null && peakPct != null && peakPct >= RAIN_PEAK_THRESHOLD_PCT) {
        rainLineShort = formatter.formatPeakRainShort(peakPct)
        rainFillFraction = (peakPct / 100f).coerceIn(0f, 1f)
    } else {
        rainLineShort = null
        rainFillFraction = null
    }
    // Wind / UV: take the period's peak and only surface it when notable. The
    // hourly series is already the cross-model consensus blend (wind / UV folded
    // in at fetch time — see blendConsensusHourly), so a plain max over it
    // matches the wind / UV diagnostic charts' "Combined" line rather than a
    // lone best_match spike.
    val windMaxKmh = hourly.mapNotNull { it.windSpeedKmh }.maxOrNull()
        ?.takeIf { it >= WIND_NOTABLE_KMH }
    val windLabel = windMaxKmh?.let {
        context.getString(
            R.string.conditions_wind,
            it.toWindSpeedUnit(windSpeedUnit).roundToInt(),
            windSpeedUnit.symbol(),
        )
    }
    // UV gates on the *rounded* peak so the cell appears for exactly the values
    // the label renders: a 5.5–5.9 peak reads as "UV 6" once rounded, so testing
    // the raw value against UV_NOTABLE (6.0) would hide a UV the user is told is 6.
    val uvMax = hourly.mapNotNull { it.uvIndex }.maxOrNull()
        ?.takeIf { it.roundToInt() >= UV_NOTABLE }
    val uvLabel = uvMax?.let { context.getString(R.string.conditions_uv, it.roundToInt()) }
    return OutfitCardInfoLines(
        tempLine = tempLine,
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

    val bitmap = createBitmap(w, h)
    val canvas = Canvas(bitmap) // transparent — the widget Box paints its own background

    val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textArgb
        textSize = h * STRIP_TEXT_HEIGHT_FRACTION
        typeface = Typeface.DEFAULT_BOLD
    }
    drawConditionsRow(
        canvas = canvas,
        cells = cells,
        areaX = w * (1f - STRIP_USABLE_WIDTH_FRACTION) / 2f,
        areaWidth = w * STRIP_USABLE_WIDTH_FRACTION,
        centerY = h / 2f,
        baseIconPx = (h * STRIP_ICON_HEIGHT_FRACTION).roundToInt().coerceAtLeast(1),
        textPaint = textPaint,
        interiorArgb = interiorArgb,
        outlineArgb = outlineArgb,
        center = true,
    )
    conditionsStripCache[key] = bitmap
    return bitmap
}

/**
 * Builds the conditions cells from computed [info]: thermometer always, then
 * the rain / wind / UV cells when their label is present (gated upstream in
 * [outfitCardInfoLines]). Wind / UV carry their Beaufort / WHO scale tint.
 * Shared by the widget strip and the outfit card so every surface reads the
 * same set in the same order.
 */
internal fun conditionsCells(info: OutfitCardInfoLines): List<ConditionsCell> = buildList {
    add(ConditionsCell(ConditionsGlyph.THERMOMETER, info.tempLine, fillFraction = info.tempFillFraction))
    info.rainLineShort?.let {
        add(ConditionsCell(ConditionsGlyph.DROPLET, it, fillFraction = info.rainFillFraction ?: 0f))
    }
    info.windLabel?.let {
        add(ConditionsCell(ConditionsGlyph.WIND, it, tintArgb = windScaleColorArgb(info.windMaxKmh ?: 0.0)))
    }
    info.uvLabel?.let {
        add(ConditionsCell(ConditionsGlyph.UV, it, tintArgb = uvScaleColorArgb(info.uvMax ?: 0.0)))
    }
}

/**
 * Lays [cells] in one horizontal row, glyph + label per cell, vertically
 * centred on [centerY]. The row fits within [areaWidth] starting at [areaX]:
 * icon, text and gaps shrink together (down to [STRIP_MIN_FIT_SCALE]) if the
 * content would overflow, and the row is centred within the area when [center]
 * is set (the widget) or left-aligned when not (the outfit card). [baseIconPx]
 * is the unscaled glyph size; [textPaint] supplies the unscaled text size /
 * colour and is scaled in place. Solid wind / UV glyphs use [ConditionsCell.tintArgb];
 * thermometer / droplet fill against [interiorArgb] with an [outlineArgb] edge.
 * The single source of truth for the strip's look across every surface.
 */
private fun drawConditionsRow(
    canvas: Canvas,
    cells: List<ConditionsCell>,
    areaX: Float,
    areaWidth: Float,
    centerY: Float,
    baseIconPx: Int,
    textPaint: TextPaint,
    interiorArgb: Int,
    outlineArgb: Int,
    center: Boolean,
) {
    if (cells.isEmpty()) return
    var iconPx = baseIconPx
    var iconGap = iconPx * STRIP_ICON_TEXT_GAP_FRACTION
    var sectionGap = iconPx * STRIP_SECTION_GAP_FRACTION

    fun contentWidth(): Float =
        cells.sumOf { (iconPx + iconGap + textPaint.measureText(it.label)).toDouble() }.toFloat() +
            sectionGap * (cells.size - 1)

    val fitScale = (areaWidth / contentWidth()).coerceIn(STRIP_MIN_FIT_SCALE, 1f)
    if (fitScale < 1f) {
        iconPx = (iconPx * fitScale).roundToInt().coerceAtLeast(1)
        iconGap *= fitScale
        sectionGap *= fitScale
        textPaint.textSize *= fitScale
    }

    val cellWidths = cells.map { iconPx + iconGap + textPaint.measureText(it.label) }
    val totalWidth = cellWidths.sum() + sectionGap * (cells.size - 1)
    var x = if (center) areaX + (areaWidth - totalWidth) / 2f else areaX
    x = x.coerceAtLeast(areaX)
    val iconTop = (centerY - iconPx / 2f).roundToInt()
    val baseline = centerY - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f

    cells.forEachIndexed { i, cell ->
        val ix = x.roundToInt()
        when (cell.glyph) {
            ConditionsGlyph.THERMOMETER ->
                drawThermometerIcon(canvas, ix, iconTop, iconPx, cell.fillFraction, interiorArgb, outlineArgb)
            ConditionsGlyph.DROPLET ->
                drawRainDropletIcon(canvas, ix, iconTop, iconPx, cell.fillFraction, interiorArgb, outlineArgb)
            ConditionsGlyph.WIND ->
                drawSolidGlyph(
                    canvas, ix, iconTop, iconPx, AIR_PATH, cell.tintArgb ?: outlineArgb, outlineArgb,
                    outlineWidth = THIN_GLYPH_STROKE_WIDTH,
                )
            ConditionsGlyph.UV ->
                drawSolidGlyph(
                    canvas, ix, iconTop, iconPx, SUN_PATH, cell.tintArgb ?: outlineArgb, outlineArgb,
                    outlineWidth = THIN_GLYPH_STROKE_WIDTH,
                )
        }
        canvas.drawText(cell.label, ix + iconPx + iconGap, baseline, textPaint)
        x += cellWidths[i] + sectionGap
    }
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

// The conditions strip's rain cell shows when the blended-consensus peak chance
// of rain is at/above this — the same 10% bar the prose's chance-of-rain wording
// and the umbrella default ([ClothesRule.DEFAULTS]) key off, so all three surface
// rain together. The cell always shows the actual peak percentage, so it never
// overstates a low chance. Below the gate the row is hidden entirely.
private const val RAIN_PEAK_THRESHOLD_PCT = 10

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
 * (extreme). The cell only shows once the peak rounds to ≥ [UV_NOTABLE], so the
 * user sees high-yellow-or-worse (a 5.5–5.9 peak reads "UV 6" but still tints
 * yellow until the raw value crosses 6.0).
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
// Bottom-of-card padding for the info row — larger than CARD_PAD so the
// conditions row clears the Nest Hub's bezel + bottom status overlay.
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
