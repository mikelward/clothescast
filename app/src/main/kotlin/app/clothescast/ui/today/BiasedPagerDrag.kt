package app.clothescast.ui.today

import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Drives [pagerState] horizontally when a gesture is *mostly* horizontal, even
 * if its vertical component would otherwise win the standard
 * `Modifier.scrollable` direction lock and route the drag into the inner
 * vertical scroll instead.
 *
 * The default Compose behaviour locks a gesture to whichever axis crosses
 * touch slop first — so a lazy diagonal swipe with even a slightly greater
 * vertical component locks vertical, and the pager never sees the rest of
 * the motion. With this modifier, the lock is angle-biased: any gesture
 * where `|dx| > biasK · |dy|` at the moment touch slop is crossed is claimed
 * for the pager, the inner scroll never gets it, and the user feels the
 * page move.
 *
 *  - `biasK = 1.0` reproduces the default 45° split.
 *  - `biasK = 0.5` lets gestures up to ~63° below horizontal still commit
 *    horizontal — what "lazy sideways swipes" tend to land at.
 *  - `biasK = 0` would steal *every* gesture with any horizontal component,
 *    which kills vertical scrolling. Don't go below ~0.3.
 *
 * When the bias check picks horizontal, the gesture is consumed in
 * [PointerEventPass.Initial], so the pager's own internal scrollable and
 * the inner column's vertical scrollable both see consumed events and stay
 * quiet. We then feed pointer deltas straight into
 * [PagerState.dispatchRawDelta] and, on release, hand the tracked velocity
 * to [flingBehavior] via [PagerState.scroll] — preserving the same snap
 * physics the pager would have used natively.
 *
 * When the bias check picks vertical, we don't consume anything; the events
 * fall through to the inner scroll as if this modifier weren't here.
 */
internal fun Modifier.horizontalBiasedPagerDrag(
    pagerState: PagerState,
    flingBehavior: TargetedFlingBehavior,
    scope: CoroutineScope,
    biasK: Float = 0.5f,
): Modifier = pointerInput(pagerState, biasK) {
    val slop = viewConfiguration.touchSlop
    val velocityTracker = VelocityTracker()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        velocityTracker.resetTracking()
        velocityTracker.addPointerInputChange(down)
        var dxAccum = 0f
        var dyAccum = 0f
        // Phase 1 — pre-slop tracking. Read events without consuming so the
        // inner scroll can claim if vertical wins.
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            velocityTracker.addPointerInputChange(change)
            if (!change.pressed) return@awaitEachGesture
            dxAccum += change.position.x - change.previousPosition.x
            dyAccum += change.position.y - change.previousPosition.y
            val absX = abs(dxAccum)
            val absY = abs(dyAccum)
            if (absX < slop && absY < slop) continue
            if (absX <= biasK * absY) {
                // Vertical wins. Let the inner scroll handle it; we're done.
                return@awaitEachGesture
            }
            // Edge probe — before we consume anything, try to push the
            // accumulated horizontal delta into the pager.
            // ScrollableState.dispatchRawDelta returns the *consumed*
            // portion of the delta, so a return of 0f means the pager
            // refused (already at the appropriate edge for this swipe
            // direction). Bail in that case so the gesture isn't consumed
            // and the inner vertical scroll receives the rest of the
            // drag — e.g. a down-right swipe on page 0 in LTR still
            // scrolls the page vertically instead of becoming dead motion.
            // Going through dispatchRawDelta rather than checking
            // canScrollForward / canScrollBackward directly is layout-
            // direction agnostic: the pager's own internal direction model
            // decides whether the delta moves it, so LTR vs RTL is handled
            // by the pager rather than by mirroring the sign here.
            val consumed = pagerState.dispatchRawDelta(-dxAccum)
            if (consumed == 0f) return@awaitEachGesture
            change.consume()
            break
        }
        // Phase 2 — pointer-driven drag. Every horizontal delta goes straight
        // to the pager; consumed so the inner scroll stays out.
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            velocityTracker.addPointerInputChange(change)
            if (!change.pressed) {
                change.consume()
                break
            }
            val dx = change.position.x - change.previousPosition.x
            pagerState.dispatchRawDelta(-dx)
            change.consume()
        }
        // Phase 3 — fling with the tracked release velocity. Negated to match
        // dispatchRawDelta's convention (positive delta = scroll forward = next
        // page, so a leftward flick reads as a positive velocity for the
        // pager).
        val velocity = -velocityTracker.calculateVelocity().x
        scope.launch {
            pagerState.scroll {
                with(flingBehavior) { performFling(velocity) }
            }
        }
    }
}
