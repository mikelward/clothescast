package app.clothescast.ui

import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.clothescast.R

// Renders the two-layer adaptive icon composition (background + foreground)
// clipped to a circle to approximate how the launcher displays the icon.
// Captured by PreviewSnapshots so badge and icon changes are visible in PR diffs.
//
// The dev icon foreground is a layer-list, which painterResource() doesn't support
// (only VectorDrawables and rasters). AndroidView + ImageView handles it correctly.
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

@Composable
private fun AdaptiveIconFrame(foreground: @Composable () -> Unit) {
    Box(modifier = Modifier.padding(24.dp)) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_background),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
            foreground()
        }
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
            modifier = Modifier.fillMaxSize(),
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
            modifier = Modifier.fillMaxSize(),
        )
    }
}
