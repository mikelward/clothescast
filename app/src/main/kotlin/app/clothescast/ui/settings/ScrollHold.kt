package app.clothescast.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.offset
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.filter

/**
 * Holds a control still on screen while tapping it resizes the page around it: the text-size minus and
 * plus grow or shrink every row above them, which would carry the
 * buttons out from under a finger tapping them again. Once [hold] is called, each time the held control
 * ([holding]) is placed somewhere new, the page scrolls by the difference, so it stays where it was when
 * first held, however many taps follow. The rider's own scroll lets go ([rememberScrollHold]).
 *
 * Where the scroll can't go far enough (near the top as the rows above shrink, or the end as they grow),
 * [topSlack] and [bottomSlack] add room at the page's ends ([holdSlack]) so it still can. Room at the top
 * is taken back when the hold lets go if the page is scrolled past it, which shows the same; the rest
 * stays until Settings closes, out of sight below the last row or above the first.
 *
 * Main thread only; constant work per placement.
 */
@Stable
internal class ScrollHold(private val scroll: ScrollState, top: Int = 0, bottom: Int = 0) {
    private var target = Float.NaN
    private var last = Float.NaN

    // The column's own height, slack aside, as [holdSlack] last measured it.
    internal var contentHeight = 0

    var topSlack by mutableIntStateOf(top)
        private set
    var bottomSlack by mutableIntStateOf(bottom)
        private set

    /** Keeps the held control where it is now; a hold already in force keeps where it began. */
    fun hold() {
        if (target.isNaN()) target = last
    }

    /** Lets the page move freely again, taking back room added at its top when that shows the same. */
    fun release() {
        target = Float.NaN
        if (topSlack > 0 && scroll.value >= topSlack) {
            scroll.dispatchRawDelta(-topSlack.toFloat())
            topSlack = 0
        }
    }

    /** The held control was placed at [y], in root coordinates. */
    fun placed(y: Float) {
        last = y
        if (target.isNaN()) return
        val moved = (y - target).roundToInt()
        if (moved > 0) {
            // Pushed down: the page moves up, out of room added at its top first, then by scrolling.
            val fromSlack = min(moved, topSlack)
            topSlack -= fromSlack
            val rest = moved - fromSlack
            if (rest > 0) {
                val unscrolled = rest - scroll.dispatchRawDelta(rest.toFloat()).roundToInt()
                if (unscrolled > 0) {
                    // Past the end, or a page too short to scroll: room below, enough for the scroll
                    // still owed, scrolled into by the next placement.
                    val room = contentHeight + topSlack + bottomSlack - scroll.viewportSize
                    val owed = scroll.value + unscrolled
                    if (owed > room) bottomSlack += owed - room
                }
            }
        } else if (moved < 0) {
            // Pulled up: the page moves down, scrolled back, and past the top by room added there.
            val unscrolled = -moved + scroll.dispatchRawDelta(moved.toFloat()).roundToInt()
            if (unscrolled > 0) topSlack += unscrolled
        }
    }
}

/** A [ScrollHold] on [scroll], let go whenever the rider scrolls the page themselves. */
@Composable
internal fun rememberScrollHold(scroll: ScrollState): ScrollHold {
    // Saved with the scroll position it goes with: restored to a page without its slack, that position
    // would show the rows shifted by however much room the hold had added.
    val hold = rememberSaveable(
        scroll,
        saver = Saver(save = { listOf(it.topSlack, it.bottomSlack) }, restore = { ScrollHold(scroll, it[0], it[1]) }),
    ) { ScrollHold(scroll) }
    LaunchedEffect(hold) {
        // The hold's own scrolls are raw deltas, never a scroll in progress, so only the rider's count.
        snapshotFlow { scroll.isScrollInProgress }.filter { it }.collect { hold.release() }
    }
    return hold
}

/** Reports where this control is placed to [hold], which keeps it there while held. */
internal fun Modifier.holding(hold: ScrollHold?): Modifier =
    if (hold == null) this else onPlaced { hold.placed(it.positionInRoot().y) }

/**
 * The room [hold] adds at the page's ends, as padding on the scrolled column (after `verticalScroll`). Read
 * as it's laid out, so a change relays the page out and recomposes nothing.
 */
internal fun Modifier.holdSlack(hold: ScrollHold): Modifier = layout { measurable, constraints ->
    val top = hold.topSlack
    val bottom = hold.bottomSlack
    val placeable = measurable.measure(constraints.offset(vertical = -(top + bottom)))
    hold.contentHeight = placeable.height
    layout(placeable.width, constraints.constrainHeight(placeable.height + top + bottom)) {
        placeable.place(0, top)
    }
}
