package app.clothescast.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Cap on frames pumped while waiting for the render to settle. Vico loads its
// line series via a coroutine the first composed frame doesn't cover and then
// animates the line in, so we draw each frame and stop once two consecutive
// frames are byte-identical. ~60 frames ≈ 1s of real frames is plenty for the
// data load + draw-in animation; the caller also wraps this in a timeout.
private const val MAX_FRAMES = 60

// Require the picture to hold steady for this many consecutive frames before
// accepting it — guards against catching a mid-animation frame that happens to
// repeat once.
private const val STABLE_FRAMES = 2

/**
 * Rasterises a Compose [content] to a [Bitmap] off-screen, with no Activity or
 * attached window — the path the home-screen widgets use to show the real
 * [WidgetForecastChart] (Glance emits RemoteViews and can't host Compose/Vico).
 *
 * Composition runs against a self-managed [Recomposer] on the Android UI
 * dispatcher (which supplies the frame clock Vico's animations need). We pump
 * frames, drawing each one, until the output stops changing — the runtime
 * analogue of the snapshot harness's `captureUntilStable`, which solves the same
 * "Vico's series arrives a frame or two after first composition" race. Must run
 * inside a timeout: a composable that never settles would otherwise pump all
 * [MAX_FRAMES] and, if the frame clock stalls, block.
 *
 * Note: this can't be exercised by Robolectric (no real frame clock / window),
 * so the *visual* is validated by the `WidgetForecastChart` snapshots and this
 * harness is verified on-device.
 */
internal suspend fun renderComposableToBitmap(
    context: Context,
    widthPx: Int,
    heightPx: Int,
    content: @Composable () -> Unit,
): Bitmap = withContext(AndroidUiDispatcher.Main) {
    require(widthPx > 0 && heightPx > 0) { "render size must be positive, got ${widthPx}x$heightPx" }
    val owner = HeadlessOwner().also { it.create() }
    val recomposer = Recomposer(coroutineContext)
    val composeView = ComposeView(context).also { view ->
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
        view.setParentCompositionContext(recomposer)
        view.setContent(content)
    }
    val recomposeJob = launch { recomposer.runRecomposeAndApplyChanges() }
    try {
        composeView.createComposition()
        val wSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        var lastHash = 0
        var stable = 0
        repeat(MAX_FRAMES) {
            withFrameNanos { /* let recomposition apply + Vico's coroutine run */ }
            composeView.measure(wSpec, hSpec)
            composeView.layout(0, 0, widthPx, heightPx)
            bitmap.eraseColor(0)
            composeView.draw(canvas)
            val hash = bitmap.coarseHash()
            if (hash == lastHash) {
                if (++stable >= STABLE_FRAMES) return@repeat
            } else {
                stable = 0
                lastHash = hash
            }
        }
        bitmap
    } finally {
        composeView.disposeComposition()
        recomposer.cancel()
        recomposeJob.cancel()
        owner.destroy()
    }
}

// Cheap fingerprint of the rendered frame: XOR of a sparse pixel grid. Enough to
// tell "still animating" from "settled" without hashing every pixel each frame.
private fun Bitmap.coarseHash(): Int {
    var hash = 17
    val stepX = (width / 24).coerceAtLeast(1)
    val stepY = (height / 24).coerceAtLeast(1)
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            hash = hash * 31 + getPixel(x, y)
            x += stepX
        }
        y += stepY
    }
    return hash
}

// Minimal owner trio Compose requires to compose off a real window: a RESUMED
// lifecycle, a restored SavedStateRegistry, and a ViewModelStore.
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
