package app.clothescast.widget

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.get
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.clothescast.diag.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "WidgetRender"

// How long to keep pulling frames while the chart loads its data and the line
// animates in, and how often to sample. The caller also imposes a hard timeout.
private const val RENDER_BUDGET_MS = 2500L
private const val FRAME_INTERVAL_MS = 50L

// Accept the picture once it holds steady for this many consecutive samples.
private const val STABLE_SAMPLES = 2

/**
 * Rasterises a Compose [content] to a [Bitmap] from a non-Activity context (the
 * widget worker), returning null if it can't produce a non-blank frame.
 *
 * Unlike an unattached `ComposeView` — which composes but never actually paints,
 * the cause of the blank widget — this renders into a **real window**: a
 * [Presentation] hosted on a [VirtualDisplay] backed by an [ImageReader]. That
 * gives Compose a live window + frame clock, so `LaunchedEffect`s run, Vico
 * loads its series, and the chart draws. We sample the ImageReader until the
 * frame stabilises (Vico's async load + draw-in animation settle a few frames
 * after first composition).
 *
 * Every failure path logs via [DiagLog] (tag `$TAG`) — a blank widget must never
 * fail silently again.
 *
 * Can't be exercised by Robolectric (no real display/window), so it's verified
 * on-device; the chart's *appearance* is locked by the WidgetForecastChart
 * snapshots.
 *
 * Why not Vico's compose-glance (`CartesianChartImage`) and drop all this? It
 * was evaluated and deliberately not adopted — it can't reuse the real themed
 * chart, so it reintroduces the off-brand-lookalike drift this path avoids, for
 * no testability win. The full trade-off, plus the other alternatives
 * considered, lives in `docs/widgets.md` ("Alternatives considered").
 */
internal suspend fun renderComposableToBitmap(
    context: Context,
    widthPx: Int,
    heightPx: Int,
    content: @Composable () -> Unit,
): Bitmap? = withContext(Dispatchers.Main) {
    require(widthPx > 0 && heightPx > 0) { "render size must be positive, got ${widthPx}x$heightPx" }
    val imageReader = ImageReader.newInstance(widthPx, heightPx, PixelFormat.RGBA_8888, 2)
    val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        ?: run {
            DiagLog.e(TAG, "No DisplayManager; cannot render widget chart")
            imageReader.close()
            return@withContext null
        }
    val owner = HeadlessOwner().also { it.create() }
    var virtualDisplay: VirtualDisplay? = null
    var presentation: Presentation? = null
    try {
        virtualDisplay = displayManager.createVirtualDisplay(
            "clothescast-widget-chart",
            widthPx,
            heightPx,
            context.resources.displayMetrics.densityDpi,
            imageReader.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
        )
        presentation = Presentation(context.applicationContext, virtualDisplay.display).apply {
            // Render onto a transparent window so only the content's own opaque
            // pixels (the chart card) land in the bitmap; everything around it —
            // and the card's rounded-corner cutouts — stays transparent, letting
            // the Glance widgetBackground show through when the bitmap is drawn
            // (matching how the Outfit widget composites its transparent icons).
            // The Presentation's default window background is opaque and light,
            // which previously baked white corners into the dark-mode widget.
            // The RGBA_8888 ImageReader preserves the alpha channel.
            window?.apply {
                setLayout(widthPx, heightPx)
                setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            }
        }
        val composeView = ComposeView(presentation.context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setContent(content)
        }
        presentation.setContentView(composeView, ViewGroup.LayoutParams(widthPx, heightPx))
        presentation.show()

        var result: Bitmap? = null
        var lastHash = 0
        var stable = 0
        var frames = 0
        val deadline = System.currentTimeMillis() + RENDER_BUDGET_MS
        while (System.currentTimeMillis() < deadline) {
            delay(FRAME_INTERVAL_MS)
            val image = imageReader.acquireLatestImage() ?: continue
            frames++
            val frame = runCatching { image.toBitmap(widthPx, heightPx) }
                .onFailure { DiagLog.w(TAG, it, "Failed to copy ImageReader frame") }
                .getOrNull()
            image.close()
            if (frame == null) continue
            val hash = frame.coarseHash()
            if (hash == lastHash && hash != 0) {
                if (++stable >= STABLE_SAMPLES) {
                    result = frame
                    break
                }
            } else {
                stable = 0
                lastHash = hash
            }
            result = frame
        }
        when {
            result == null -> DiagLog.w(TAG, "No frame produced in %sms (%s seen)", RENDER_BUDGET_MS, frames)
            result.isBlank() -> {
                DiagLog.w(TAG, "Rendered frame blank after %s frames (%sx%s)", frames, widthPx, heightPx)
                result = null
            }
            else -> DiagLog.i(TAG, "Rendered widget chart in %s frames", frames)
        }
        result
    } catch (t: Throwable) {
        // Don't convert a coroutine cancellation (e.g. WorkManager/Glance
        // stopping the update while delay() is suspended) into a logged null —
        // that breaks structured concurrency. Rethrow it before the blanket
        // catch, per AGENTS.md's error-handling rule.
        if (t is CancellationException) throw t
        DiagLog.e(TAG, t, "Off-screen widget chart render failed")
        null
    } finally {
        runCatching { presentation?.dismiss() }
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader.close() }
        owner.destroy()
    }
}

// Copies an RGBA_8888 ImageReader frame into an ARGB_8888 bitmap, handling the
// reader's row padding (rowStride can exceed width * pixelStride).
private fun Image.toBitmap(width: Int, height: Int): Bitmap {
    val plane = planes[0]
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride
    val rowPadding = rowStride - pixelStride * width
    val padded = createBitmap(width + rowPadding / pixelStride, height)
    padded.copyPixelsFromBuffer(plane.buffer)
    return if (rowPadding == 0) padded else Bitmap.createBitmap(padded, 0, 0, width, height)
}

// Coarse fingerprint of a frame (sparse pixel grid) — cheap "still changing vs
// settled" check between samples.
private fun Bitmap.coarseHash(): Int {
    var hash = 17
    val stepX = (width / 24).coerceAtLeast(1)
    val stepY = (height / 24).coerceAtLeast(1)
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            hash = hash * 31 + this[x, y]
            x += stepX
        }
        y += stepY
    }
    return hash
}

// True when every sampled pixel matches the first — a fully transparent or
// single-colour frame, i.e. nothing meaningful drew.
private fun Bitmap.isBlank(): Boolean {
    val first = this[0, 0]
    val stepX = (width / 16).coerceAtLeast(1)
    val stepY = (height / 16).coerceAtLeast(1)
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            if (this[x, y] != first) return false
            x += stepX
        }
        y += stepY
    }
    return true
}

// Minimal owner trio Compose requires to host a composition in the Presentation
// window: a RESUMED lifecycle, a restored SavedStateRegistry, a ViewModelStore.
private class HeadlessOwner : SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()

    fun create() {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
