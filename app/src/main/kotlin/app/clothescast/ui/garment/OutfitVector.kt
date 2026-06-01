package app.clothescast.ui.garment

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Paint
import android.graphics.Path
import android.util.Xml
import androidx.annotation.DrawableRes
import androidx.core.graphics.PathParser
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.ConcurrentHashMap

/**
 * Light-weight in-memory representation of one of the `ic_outfit_*.xml`
 * vector drawables, parsed once and reused for both the Compose render path
 * (Today screen) and the bitmap render path (notification large icon, Glance
 * widget). Only the subset of [android.graphics.drawable.VectorDrawable] this
 * project actually uses is supported — flat list of `<path>` elements, no
 * `<group>`, no gradients — so the parser stays small. New attributes that
 * land on a future drawable need a parser-side update.
 */
internal data class OutfitVector(
    val viewportWidth: Float,
    val viewportHeight: Float,
    val paths: List<OutfitPath>,
)

internal data class OutfitPath(
    val pathData: String,
    /** ARGB int from `android:fillColor`; null when the path has no fill. */
    val fillArgb: Int?,
    val strokeArgb: Int?,
    val strokeWidth: Float,
    val strokeCap: Paint.Cap,
    val strokeJoin: Paint.Join,
)

private val ANDROID_NS = "http://schemas.android.com/apk/res/android"

private val cache = ConcurrentHashMap<Int, OutfitVector>()

/**
 * Parses [resId] into an [OutfitVector] the first time it's requested and
 * memoises the result. Resource parsing is cheap (<1ms per drawable on
 * a midrange phone) but caching keeps the widget refresh path free of
 * repeated XML pull-parser allocations during rapid tile updates.
 */
internal fun loadOutfitVector(context: Context, @DrawableRes resId: Int): OutfitVector =
    cache.getOrPut(resId) { parseOutfitVector(context.resources, resId) }

// resId is a vector drawable (@DrawableRes), and we deliberately read its raw
// XML with getXml() to parse the <path> elements ourselves. Lint wants an
// @XmlRes for getXml() and so flags a false positive — vector drawables are
// XML resources, and parsing one this way is intentional.
@SuppressLint("ResourceType")
private fun parseOutfitVector(res: Resources, @DrawableRes resId: Int): OutfitVector {
    val parser = res.getXml(resId)
    val attrs = Xml.asAttributeSet(parser)
    var viewportWidth = 0f
    var viewportHeight = 0f
    val paths = mutableListOf<OutfitPath>()
    try {
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "vector" -> {
                        viewportWidth = floatAttr(attrs, "viewportWidth") ?: 0f
                        viewportHeight = floatAttr(attrs, "viewportHeight") ?: 0f
                    }
                    "path" -> paths += parsePath(attrs)
                }
            }
            event = parser.next()
        }
    } finally {
        parser.close()
    }
    require(viewportWidth > 0f && viewportHeight > 0f) {
        "Vector drawable $resId is missing viewportWidth / viewportHeight"
    }
    return OutfitVector(viewportWidth, viewportHeight, paths)
}

private fun parsePath(attrs: android.util.AttributeSet): OutfitPath {
    val pathData = stringAttr(attrs, "pathData")
        ?: error("Vector <path> missing android:pathData")
    return OutfitPath(
        pathData = pathData,
        fillArgb = colorAttr(attrs, "fillColor"),
        strokeArgb = colorAttr(attrs, "strokeColor"),
        strokeWidth = floatAttr(attrs, "strokeWidth") ?: 0f,
        strokeCap = capAttr(attrs, "strokeLineCap") ?: Paint.Cap.BUTT,
        strokeJoin = joinAttr(attrs, "strokeLineJoin") ?: Paint.Join.MITER,
    )
}

private fun stringAttr(attrs: android.util.AttributeSet, name: String): String? =
    attrs.getAttributeValue(ANDROID_NS, name)

private fun floatAttr(attrs: android.util.AttributeSet, name: String): Float? =
    stringAttr(attrs, name)?.toFloatOrNull()

private fun colorAttr(attrs: android.util.AttributeSet, name: String): Int? {
    val raw = stringAttr(attrs, name) ?: return null
    return runCatching { android.graphics.Color.parseColor(raw) }.getOrNull()
}

private fun capAttr(attrs: android.util.AttributeSet, name: String): Paint.Cap? =
    when (stringAttr(attrs, name)) {
        "round" -> Paint.Cap.ROUND
        "square" -> Paint.Cap.SQUARE
        "butt" -> Paint.Cap.BUTT
        else -> null
    }

private fun joinAttr(attrs: android.util.AttributeSet, name: String): Paint.Join? =
    when (stringAttr(attrs, name)) {
        "round" -> Paint.Join.ROUND
        "bevel" -> Paint.Join.BEVEL
        "miter" -> Paint.Join.MITER
        else -> null
    }

/** Cached [Path] for [OutfitPath.pathData] — parsing the SVG-like data string is the
 *  per-render hot loop, so we memoise once per unique pathData string. */
private val pathCache = ConcurrentHashMap<String, Path>()

internal fun OutfitPath.toAndroidPath(): Path =
    pathCache.getOrPut(pathData) { PathParser.createPathFromPathData(pathData) }
