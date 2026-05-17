package app.clothescast.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Wraps [content] in a Box and overlays two 32 dp gradient fades — one at the
 * top, one at the bottom — to hint at off-screen content. Each fades in when
 * its visibility flag is true and out when false, animated via
 * [animateFloatAsState], so the page lands in the right state on first paint
 * and transitions smoothly thereafter. The caller usually wires
 * [topFadeVisible] / [bottomFadeVisible] to a `ScrollState`'s
 * `canScrollBackward` / `canScrollForward` (see the convenience overload
 * below), but accepting raw booleans keeps the composable testable from
 * previews that drive the alphas directly.
 *
 * The overlays have no `clickable` modifier, so drag gestures fall through to
 * the content underneath — wrapping a scrollable column doesn't break its
 * scroll, and a pager swipe still works.
 */
@Composable
internal fun EdgeFadeOverlay(
    topFadeVisible: Boolean,
    bottomFadeVisible: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.background,
    content: @Composable () -> Unit,
) {
    val topAlpha by animateFloatAsState(
        targetValue = if (topFadeVisible) 1f else 0f,
        label = "topFade",
    )
    val bottomAlpha by animateFloatAsState(
        targetValue = if (bottomFadeVisible) 1f else 0f,
        label = "bottomFade",
    )
    // Color.Transparent is fully transparent *black*, so a linear gradient
    // between it and the (light) background colour passes through a darker
    // mid-tone — a faint band the eye picks up where it crosses the cards.
    // Keep RGB constant through the gradient by fading the same colour's
    // alpha instead.
    val transparentColor = color.copy(alpha = 0f)
    Box(modifier = modifier.fillMaxSize()) {
        content()
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(32.dp)
                .alpha(topAlpha)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(color, transparentColor),
                    ),
                ),
        )
        // Bottom fade: a 32 dp gradient in the content area, *continued* as a
        // solid band of [color] through the nav-bar inset down to the screen
        // edge. The fade marks the visible-content edge (transparent → opaque
        // across 32 dp just above the nav bar), and the solid extension keeps
        // any scrollable content that's been clipped at the viewport bottom
        // from bleeding through behind the translucent nav bar — without it,
        // the boundary where the gradient stops and the unfaded card surface
        // resumes reads as a hard horizontal line right at the top of the nav
        // area. In contexts with no nav-bar inset (e.g. Robolectric snapshots),
        // the extension collapses to zero height and the fade behaves the same
        // as a plain 32 dp gradient anchored to the bottom edge.
        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val fadeHeight = 32.dp
        val totalHeight = fadeHeight + navBarBottom
        val fadeFraction = if (navBarBottom > 0.dp) fadeHeight.value / totalHeight.value else 1f
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(totalHeight)
                .alpha(bottomAlpha)
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to transparentColor,
                            fadeFraction to color,
                            1f to color,
                        ),
                    ),
                ),
        )
    }
}

/**
 * Convenience overload that wires [scrollState]'s `canScrollBackward` /
 * `canScrollForward` to the fade-visibility flags, so callers don't have to
 * thread them by hand. The caller still owns the scrollable child (usually a
 * `Column.verticalScroll(scrollState)`) so this stays composable-friendly when
 * the same scroll position needs to drive other behaviours (e.g. animated
 * scroll-to on a tap).
 */
@Composable
internal fun EdgeFadeOverlay(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.background,
    content: @Composable () -> Unit,
) {
    EdgeFadeOverlay(
        topFadeVisible = scrollState.canScrollBackward,
        bottomFadeVisible = scrollState.canScrollForward,
        modifier = modifier,
        color = color,
        content = content,
    )
}
