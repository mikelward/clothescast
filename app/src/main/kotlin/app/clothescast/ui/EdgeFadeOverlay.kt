package app.clothescast.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
                        colors = listOf(color, Color.Transparent),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(32.dp)
                .alpha(bottomAlpha)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, color),
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
