package app.clothescast.ui.garment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
