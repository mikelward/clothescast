package app.clothescast.ui.nav

import androidx.activity.BackEventCompat
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry

// Duration of the push/pop slide between destinations. Short enough to feel
// snappy (and to keep the can't-tap-yet window small), long enough to read as a
// directional transition rather than a jump.
internal const val NAV_ANIM_MS = 200

/**
 * How far the page shrinks under a back swipe: the platform's own in-app
 * predictive-back peek, which shrinks the page to 90% so the one beneath shows
 * around its edges.
 */
internal const val PREDICTIVE_BACK_SCALE = 0.9f

/**
 * The page's drift away from the edge the swipe started at, as a fraction of
 * its width. Small, so it reads as following the finger rather than leaving.
 */
private const val PREDICTIVE_BACK_SHIFT = 1f / 16f

/**
 * The transitions every destination uses.
 *
 * A tap navigates with a short directional slide. The library default is a
 * 700ms crossfade, which both reads as "appearing from nowhere" and overlaps
 * the outgoing and incoming screens in the same spot — a low-alpha layer still
 * eats touches, so a quick tap on a freshly-shown list can hit the wrong
 * screen. Start/End (not Left/Right) so the motion mirrors correctly in RTL
 * locales (ar/fa/iw).
 *
 * A back **swipe** uses the standard predictive-back peek instead: the page
 * shrinks toward [PREDICTIVE_BACK_SCALE] and drifts away from the swiped edge,
 * with the page it returns to already in place beneath it. No fade: the
 * transition is seeked by the swipe's progress, so any fade would also run
 * while the finger is still down on a long drag (Codex, #1275); the page stays
 * opaque and is removed once the pop commits. Without these, the
 * swipe scrubbed the pop slide, so the whole page slid sideways under the
 * finger instead of peeking (maintainer, 2026-10-11). A back button or the
 * toolbar's up arrow still plays the slide, since neither is a gesture to
 * follow.
 */
internal object NavTransitions {
    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideIntoContainer(SlideDirection.Start, tween(NAV_ANIM_MS))
    }
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutOfContainer(SlideDirection.Start, tween(NAV_ANIM_MS))
    }
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideIntoContainer(SlideDirection.End, tween(NAV_ANIM_MS))
    }
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutOfContainer(SlideDirection.End, tween(NAV_ANIM_MS))
    }

    /** Already in place beneath the page being swiped away; nothing to animate. */
    val predictivePopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> EnterTransition = {
        EnterTransition.None
    }

    val predictivePopExit: AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> ExitTransition = { edge ->
        // Away from the edge the swipe started at: right for the left edge,
        // left for the right one. The edge is physical, so this needs no RTL
        // mirroring.
        val direction = if (edge == BackEventCompat.EDGE_RIGHT) -1 else 1
        scaleOut(tween(NAV_ANIM_MS), targetScale = PREDICTIVE_BACK_SCALE) +
            slideOutHorizontally(tween(NAV_ANIM_MS)) { width ->
                (width * PREDICTIVE_BACK_SHIFT).toInt() * direction
            }
    }
}
