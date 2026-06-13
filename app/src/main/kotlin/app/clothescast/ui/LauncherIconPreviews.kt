package app.clothescast.ui

import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.clothescast.R

// Renders the adaptive icon (background + foreground) the way a real launcher
// does, so badge / artwork changes are reviewable as PR image diffs and a
// crop regression can't hide.
//
// A launcher composites the 108dp adaptive-icon layers, reserves the outer
// 18dp on every side as bleed, and masks the inner 72dp viewport to the
// device shape — a circle on Pixel. We reproduce that as a CENTER-CROP: draw
// each layer at its full 108dp and let a 72dp circular clip discard the bleed.
// This is deliberately NOT a scale-to-fit (ContentScale.FillBounds into 72dp):
// scaling would drag bottom-edge content up into the visible circle and make a
// badge pinned to the bleed look like it survives masking when it doesn't.
// With the center-crop, anything outside the inner 72dp (or outside the circle
// within it) is clipped here exactly as the launcher clips it.
//
// The dev icon foreground is a layer-list, which painterResource() doesn't
// support (only VectorDrawables and rasters). AndroidView + ImageView handles
// it correctly.
//
// Foreground resources are referenced through drawable-nodpi copies
// (R.drawable.ic_launcher_foreground_pinned and the matching construction
// layer-list) rather than R.mipmap.ic_launcher_foreground. The mipmap
// resolver picks density buckets inconsistently between Robolectric
// runs even with @Config(qualifiers = "...xhdpi") on the test, so the
// captured launcher_icon{,_dev}.png oscillated between a ~80x70 and
// ~116x103 visible bbox — same artwork at the resource level, different
// anti-aliasing in the rasterised output, defeating the
// captureUntilStable noise budget and producing churn in every PR's
// snapshot regen. Pinning to a single PNG file makes the render
// deterministic.

// Full adaptive-icon canvas; the outer 18dp on each side is launcher bleed.
private val LayerSize = 108.dp

// The inner viewport the launcher actually masks and shows (108 - 2*18).
private val ViewportSize = 72.dp

// Masks layers to the launcher's circle. Each layer is drawn at its full
// [LayerSize] and centered, so the [ViewportSize] circular clip center-crops
// the 18dp bleed instead of scaling it into view.
@Composable
private fun AdaptiveIconMask(content: @Composable () -> Unit) {
    Box(modifier = Modifier.padding(24.dp)) {
        Box(
            modifier = Modifier
                .size(ViewportSize)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
private fun AdaptiveIconFrame(foreground: @Composable () -> Unit) {
    AdaptiveIconMask {
        Image(
            painter = painterResource(R.drawable.ic_launcher_background),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.requiredSize(LayerSize),
        )
        foreground()
    }
}

@Preview(name = "Launcher icon · release", widthDp = 120)
@Composable
internal fun LauncherIconPreview() {
    AdaptiveIconFrame {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground_pinned),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.requiredSize(LayerSize),
        )
    }
}

// Approximates the themed (monochrome) icon launchers render on Android 13+
// when the user turns on themed app icons: a single tintable silhouette laid
// over a tinted background, both drawn from the user's wallpaper palette. The
// real tints come from the system; the stand-in colors here just verify the
// monochrome layer's silhouette (t-shirt with the sun knocked out) reads.
@Preview(name = "Launcher icon · themed (monochrome)", widthDp = 120)
@Composable
internal fun LauncherIconMonochromePreview() {
    AdaptiveIconMask {
        Box(modifier = Modifier.requiredSize(LayerSize).background(Color(0xFF004A77)))
        Image(
            painter = painterResource(R.drawable.ic_launcher_monochrome),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            colorFilter = ColorFilter.tint(Color(0xFFC2E7FF)),
            modifier = Modifier.requiredSize(LayerSize),
        )
    }
}

@Preview(name = "Launcher icon · dev (local build)", widthDp = 120)
@Composable
internal fun LauncherIconDevPreview() {
    AdaptiveIconFrame {
        AndroidView(
            factory = { context ->
                ImageView(context).also {
                    it.setImageResource(R.drawable.ic_launcher_foreground_construction_pinned)
                    it.scaleType = ImageView.ScaleType.FIT_XY
                }
            },
            modifier = Modifier.requiredSize(LayerSize),
        )
    }
}

// The dev icon under themed icons: same monochrome treatment as the release
// preview above, but rendering the construction silhouette so the "DEV"
// knockout in the lower torso is reviewable and its safe-zone placement is
// confirmed to survive the circle crop.
@Preview(name = "Launcher icon · dev themed (monochrome)", widthDp = 120)
@Composable
internal fun LauncherIconDevMonochromePreview() {
    AdaptiveIconMask {
        Box(modifier = Modifier.requiredSize(LayerSize).background(Color(0xFF004A77)))
        Image(
            painter = painterResource(R.drawable.ic_launcher_monochrome_construction),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            colorFilter = ColorFilter.tint(Color(0xFFC2E7FF)),
            modifier = Modifier.requiredSize(LayerSize),
        )
    }
}
