package app.clothescast.ui.today

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.clothescast.R
import app.clothescast.core.domain.model.HourlyForecast
import com.patrykandpatrick.vico.core.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.core.cartesian.decoration.Decoration
import com.patrykandpatrick.vico.core.common.Fill
import com.patrykandpatrick.vico.core.common.component.LineComponent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Shared indicator/scrub controller. One instance lives on each page of
 * the Today/Tomorrow pager (via `remember` in `TodayPage`) and is read
 * by every chart on that page through [LocalChartScrub].
 *
 * The indicator is sticky: once the user has scrubbed away from "now",
 * the indicator stays at the dragged time across release, recompositions,
 * and the pager swiping back to this page — until the user navigates
 * away from the screen (state dies with the composable), taps refresh
 * ([reset]), or hits the per-chart restore icon ([reset]).
 *
 * [setNow] is called once a minute by `TodayPage` so the indicator keeps
 * tracking the clock in the idle state. Charts on the Tomorrow page get
 * null here — there's no "now" inside that window — so their indicator
 * stays hidden until the user taps somewhere on them.
 */
@Stable
internal class ChartScrubController {
    /** The wall-clock time the indicator points at. Null = hide it. */
    var activeTime by mutableStateOf<LocalDateTime?>(null)
        private set
    /** True once the user has scrubbed; flips back on [reset]. */
    var isScrubbed by mutableStateOf(false)
        private set

    private var nowTime: LocalDateTime? = null

    fun setNow(now: LocalDateTime?) {
        nowTime = now
        if (!isScrubbed) activeTime = now
    }

    fun scrubTo(time: LocalDateTime) {
        activeTime = time
        isScrubbed = true
    }

    fun reset() {
        isScrubbed = false
        activeTime = nowTime
    }
}

internal val LocalChartScrub = compositionLocalOf<ChartScrubController?> { null }

/**
 * Optional handoff hook: when the user starts a horizontal scrub gesture
 * inside a chart and drags far enough past the left / right plot-grid edge,
 * [Modifier.chartScrub] calls this with `toNextPage = true` for "drag
 * continues toward the next page" (finger exited the leading edge in LTR /
 * trailing edge in RTL) or `false` for the opposite direction. The caller
 * — wired in `TodayScreen` around the page pager — animates the pager
 * accordingly, so swiping from inside a chart all the way across feels
 * continuous with a normal page swipe instead of bumping against an
 * invisible wall once the pointer leaves the chart. Null disables the
 * handoff (e.g. previews, single-page layouts).
 */
internal val LocalChartPageSwipe = compositionLocalOf<((toNextPage: Boolean) -> Unit)?> { null }

@Composable
internal fun rememberChartScrubController(): ChartScrubController =
    remember { ChartScrubController() }

/**
 * Per-chart record of where in canvas pixels the chart area sits.
 * Written from [ChartScrubIndicator]'s draw pass on every frame (canvas
 * pixels coincide with the parent Box's Compose pixels here, so the
 * gesture handler and tooltip overlay use them directly). Read by
 * [Modifier.chartScrub] to convert pointer-x → chart-x → time and by
 * [BoxScope.ChartScrubOverlay] to position itself at the indicator.
 *
 * [layerLeftPx]/[layerRightPx] are the canvas-x extents of the
 * chart's plot layer, *including* Vico's start/end padding (the few
 * pixels of empty space between the leftmost/rightmost data point and
 * the plot edge). The gesture handler uses these — rather than the
 * tighter data extents — so a tap inside that padding registers as a
 * scrub a bit before the first hour / a bit after the last hour
 * instead of getting filtered out.
 *
 * [chartZeroPx] is the canvas-x of `chartX = ranges.minX`, and
 * [pxPerUnit] converts chart-x units to canvas pixels — negative in
 * RTL, where the chart draws right-to-left. The pair lets the gesture
 * invert pointer-x → chart-x without re-deriving Vico's layout
 * direction logic.
 */
@Stable
internal class ChartScrubBounds {
    var layerLeftPx by mutableFloatStateOf(0f)
    var layerRightPx by mutableFloatStateOf(0f)
    var layerTopPx by mutableFloatStateOf(0f)
    var layerBottomPx by mutableFloatStateOf(0f)
    var chartZeroPx by mutableFloatStateOf(0f)
    var pxPerUnit by mutableFloatStateOf(0f)
}

@Composable
internal fun rememberChartScrubBounds(): ChartScrubBounds = remember { ChartScrubBounds() }

/**
 * Inverse of [currentTimeChartX]: given a fractional chart-x value,
 * reconstruct the wall-clock [LocalDateTime] — needed when translating
 * a pointer position back into a time we can publish to the shared
 * [ChartScrubController]. Handles tonight's midnight wrap the same
 * way [currentTimeChartX] does outbound, by advancing the date on
 * each backwards hour-of-day step in the list.
 *
 * Range: chartX is clamped to `[-0.5, lastIndex + 0.5]` — half a
 * cell of slack on each side of the data, matching Vico's start/end
 * padding (visible empty space between the plot edges and the
 * first/last data points, which the user has measured at ~half an
 * hour). A tap in that leading padding maps to a time a few minutes
 * before the first data point's hour; a tap in the trailing padding
 * maps to a time inside the half-hour past the last data point. At
 * 06:30 on a TONIGHT chart whose last data point is 06:00, a tap
 * near the right edge yields ~06:30 instead of snapping back to
 * 06:00; at 06:30 before a TODAY chart's 07:00 start, a tap near
 * the left edge yields ~06:30 instead of snapping forward to 07:00.
 */
internal fun chartXToTime(
    hourly: List<HourlyForecast>,
    startDate: LocalDate,
    chartX: Double,
): LocalDateTime? {
    if (hourly.isEmpty()) return null
    val maxChartX = hourly.lastIndex.toDouble() + 0.5
    val clamped = chartX.coerceIn(-0.5, maxChartX)
    // Time before the first data point: walk backwards from the first
    // sample's full wall-clock by the fractional hour. No date-wrap
    // logic needed here — the leading padding can't span more than an
    // hour, so the result is always on the same date as `hourly[0]`
    // (or the previous day if the period starts at 00:00).
    if (clamped < 0.0) {
        val base = LocalDateTime.of(startDate, hourly.first().time)
        return base.plusSeconds((clamped * 3600.0).toLong())
    }
    val idx = clamped.toInt().coerceIn(0, hourly.lastIndex)
    val fraction = clamped - idx
    var date = startDate
    var prevHour: Int? = null
    for (i in 0..idx) {
        val hour = hourly[i].time.hour
        if (prevHour != null && hour < prevHour) date = date.plusDays(1)
        prevHour = hour
    }
    val base = LocalDateTime.of(date, hourly[idx].time)
    return base.plusSeconds((fraction * 3600.0).toLong())
}

/**
 * Pointer handler. A down event that lands inside the chart's plot grid
 * (between the y- and x-axis labels) starts a candidate gesture. From
 * there we wait to see what the user is actually doing:
 *
 *  - **Tap** (release before any meaningful movement): publish a scrub
 *    at the down position. Preserves the tap-to-jump behaviour.
 *  - **Clearly vertical drag** (vertical movement exceeds touch slop
 *    and dominates the horizontal component): return without consuming
 *    so the parent `verticalScroll` picks the gesture up — the user
 *    can scroll the page even when their finger started inside a chart.
 *  - **Clearly horizontal drag** (horizontal movement exceeds touch
 *    slop): claim the gesture for scrubbing, publish at the down
 *    position, then track each move.
 *
 * Once we've claimed a horizontal drag, if the pointer keeps moving and
 * exits the chart's left or right plot-grid edge by more than
 * [edgeHandoffDp], we hand the gesture off to [onSwipeAcross] (typically
 * the page pager) and stop scrubbing — so a wide cross-chart swipe
 * flows continuously into a page turn instead of bumping against an
 * invisible wall. The handoff is one-shot: once we've called
 * [onSwipeAcross], the gesture is done and the page animation takes
 * over even if the finger keeps moving.
 *
 * Down events *outside* the plot grid — on Vico's axis labels, on the
 * card padding above/below the chart, on the legend strip — are left
 * unconsumed exactly as before.
 */
internal fun Modifier.chartScrub(
    controller: ChartScrubController,
    bounds: ChartScrubBounds,
    hourly: List<HourlyForecast>,
    startDate: LocalDate,
    onFirstContact: () -> Unit,
    onSwipeAcross: ((toNextPage: Boolean) -> Unit)? = null,
): Modifier = pointerInput(controller, bounds, hourly, startDate, onSwipeAcross) {
    val touchSlop = viewConfiguration.touchSlop
    val edgeHandoffPx = edgeHandoffDp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        val downPos = down.position
        // Hit-test against the plot *layer* (left/right edges of
        // Vico's chart area, including its start/end padding) rather
        // than the tighter data extent — that's the visible empty
        // space before the first data point and after the last,
        // where a tap should map to a fractional time just outside
        // the period's hour boundaries instead of getting filtered
        // out. [publishScrub] handles the chart-x → time math from
        // here; [chartXToTime] clamps to an hour of leading / trailing
        // slack so taps far outside the chart don't produce
        // pathological times.
        val inGrid = bounds.layerRightPx > bounds.layerLeftPx &&
            bounds.layerBottomPx > bounds.layerTopPx &&
            downPos.x in bounds.layerLeftPx..bounds.layerRightPx &&
            downPos.y in bounds.layerTopPx..bounds.layerBottomPx
        if (!inGrid) return@awaitEachGesture
        // Don't consume the down yet — we need movement (or lack of it)
        // to decide whether this is a tap, a vertical scroll, or a
        // horizontal scrub. Consuming early traps every drag inside
        // the chart and blocks page scroll entirely.
        var totalDx = 0f
        var totalDy = 0f
        var claimed = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!claimed) {
                val delta = change.positionChange()
                totalDx += delta.x
                totalDy += delta.y
                val absDx = abs(totalDx)
                val absDy = abs(totalDy)
                if (absDy > touchSlop && absDy > absDx) {
                    // Clear vertical drag — let the parent verticalScroll
                    // handle it by leaving events unconsumed and bailing
                    // out of the gesture entirely.
                    return@awaitEachGesture
                }
                if (absDx > touchSlop) {
                    // Clear horizontal drag — claim the gesture.
                    claimed = true
                    down.consume()
                    change.consume()
                    publishScrub(controller, bounds, hourly, startDate, downPos.x)
                    onFirstContact()
                    publishScrub(controller, bounds, hourly, startDate, change.position.x)
                }
                // Otherwise ambiguous: stay uncommitted and watch the
                // next event. If the finger lifts here, the `pressed`
                // check below catches it and we fall through to tap.
            } else {
                change.consume()
                publishScrub(controller, bounds, hourly, startDate, change.position.x)
                if (onSwipeAcross != null) {
                    val px = change.position.x
                    val exitedRight = px > bounds.layerRightPx + edgeHandoffPx
                    val exitedLeft = px < bounds.layerLeftPx - edgeHandoffPx
                    if (exitedLeft || exitedRight) {
                        // Pointer dragged well past the plot edge — hand
                        // off to the pager. In LTR the chart draws
                        // left-to-right (positive pxPerUnit) and exiting
                        // the left edge means the finger is moving toward
                        // the *next* page (rightward content scrolls in);
                        // in RTL the chart and the pager both flip, so
                        // exiting the right edge means next-page. Using
                        // `bounds.pxPerUnit`'s sign avoids importing
                        // LayoutDirection — it's already direction-aware.
                        val ltr = bounds.pxPerUnit >= 0f
                        val toNextPage = (exitedLeft && ltr) || (exitedRight && !ltr)
                        onSwipeAcross(toNextPage)
                        return@awaitEachGesture
                    }
                }
            }
            if (!change.pressed) {
                if (!claimed) {
                    // Released before reaching touch slop — treat as a
                    // tap and publish a scrub at the original down
                    // position. Preserves tap-to-scrub even though we
                    // deferred consumption to disambiguate from scroll.
                    publishScrub(controller, bounds, hourly, startDate, downPos.x)
                    onFirstContact()
                }
                break
            }
        }
    }
}

/** Pointer distance past the chart's left / right edge that triggers a page-swipe handoff. */
private val edgeHandoffDp = 32.dp

private fun publishScrub(
    controller: ChartScrubController,
    bounds: ChartScrubBounds,
    hourly: List<HourlyForecast>,
    startDate: LocalDate,
    pointerX: Float,
) {
    if (hourly.isEmpty() || bounds.pxPerUnit == 0f) return
    // Inverse of canvasX = chartZeroPx + chartX * pxPerUnit. Works in
    // both LTR (positive pxPerUnit) and RTL (negative) — no fraction
    // arithmetic, so a single-point chart (lastIndex = 0) doesn't
    // divide by zero either. No clamp here — [chartXToTime] applies
    // its own ±1 hour slack to keep taps far outside the chart from
    // producing pathological times, and the gesture handler's
    // layer-bounds hit-test already filtered out taps outside the
    // plot area.
    val chartX = ((pointerX - bounds.chartZeroPx) / bounds.pxPerUnit).toDouble()
    val time = chartXToTime(hourly, startDate, chartX) ?: return
    controller.scrubTo(time)
}

/**
 * Vico [Decoration] that draws a vertical line at the controller's
 * active time — same canvas-x formula as the original VerticalLine in
 * `CurrentTimeIndicator.kt`, but driven dynamically by
 * [ChartScrubController.activeTime]. Always writes the chart's layer
 * bounds back into [bounds] every draw so the gesture handler and
 * tooltip overlay can position themselves; draws nothing when the
 * controller is null (e.g. previews) or the active time falls outside
 * this chart's hourly window.
 */
@Composable
internal fun rememberChartScrubIndicator(
    controller: ChartScrubController?,
    bounds: ChartScrubBounds,
    hourly: List<HourlyForecast>,
    startDate: LocalDate,
): Decoration {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    return remember(controller, bounds, hourly, startDate, color) {
        ChartScrubIndicator(controller, bounds, hourly, startDate, color)
    }
}

private class ChartScrubIndicator(
    private val controller: ChartScrubController?,
    private val bounds: ChartScrubBounds,
    private val hourly: List<HourlyForecast>,
    private val startDate: LocalDate,
    color: Color,
) : Decoration {
    private val line = LineComponent(fill = Fill(color.toArgb()), thicknessDp = 1.5f)

    override fun drawUnderLayers(context: CartesianDrawingContext) {
        with(context) {
            val multiplier = layoutDirectionMultiplier.toFloat()
            val drawingStart =
                (if (isLtr) layerBounds.left else layerBounds.right) +
                    multiplier * layerDimensions.startPadding - scroll
            // pxPerUnit is signed: negative in RTL (multiplier = -1) so
            // canvas-x decreases as chart-x increases. The gesture handler
            // and the overlay both invert via this single linear map,
            // which is direction-agnostic.
            val pxPerUnit = (multiplier * layerDimensions.xSpacing / ranges.xStep).toFloat()
            val chartZero = drawingStart - pxPerUnit * ranges.minX.toFloat()
            bounds.layerLeftPx = layerBounds.left
            bounds.layerRightPx = layerBounds.right
            bounds.chartZeroPx = chartZero
            bounds.pxPerUnit = pxPerUnit
            bounds.layerTopPx = layerBounds.top
            bounds.layerBottomPx = layerBounds.bottom

            val time = controller?.activeTime ?: return
            val chartX = currentTimeChartX(hourly, startDate, time) ?: return
            // Gate on the actual canvas pixel — chartX can fall outside
            // the data-range bounds (`ranges.minX..maxX`) when the user
            // scrubs into Vico's start / end padding, but it should
            // still draw as long as the resulting line lands on the
            // visible plot area.
            val canvasX = chartZero + pxPerUnit * chartX.toFloat()
            if (canvasX < layerBounds.left || canvasX > layerBounds.right) return
            line.drawVertical(context, canvasX, layerBounds.top, layerBounds.bottom)
        }
    }
}

/**
 * Restore-icon affordance — top-right corner of each chart card,
 * outside the chart canvas so it doesn't overlap data lines or axis
 * labels. Call inside a `Box` that wraps the card's `Column` content
 * (the Box gives the IconButton a parent to align against). Only
 * renders when the user has scrubbed away from "now"; a tap snaps the
 * indicator back via [ChartScrubController.reset].
 *
 * The readout text (time + value at the indicator) is rendered
 * separately by [ChartReadout] in the card's text column.
 */
@Composable
internal fun BoxScope.ChartRestoreOverlay(controller: ChartScrubController) {
    if (!controller.isScrubbed) return
    IconButton(
        onClick = controller::reset,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(end = 4.dp, top = 4.dp),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_chart_restore),
            contentDescription = stringResource(R.string.today_chart_reset_to_now),
        )
    }
}

/**
 * Returns the card-side readout string (time + value at the shared
 * indicator) for the caller to splice into its existing subtitle —
 * the readout lives on the same line as the subtitle, separated by a
 * mid-dot. Returns null when no controller is wired, when the
 * indicator's active time falls outside this chart's window (Tomorrow
 * page before a tap), or when the caller's [format] lambda returns
 * null (sparse data at this hour).
 *
 * Each card supplies its own [format] because the formatting and
 * source field differ — temp cards read `hourly[idx]`, precip reads
 * the probability, diagnostics read the consensus mainLine. Time
 * formatting is shared via [rememberScrubTimeFormatter].
 */
@Composable
internal fun rememberChartReadout(
    hourly: List<HourlyForecast>,
    startDate: LocalDate,
    format: @Composable (hourIndex: Int) -> String?,
): String? {
    val controller = LocalChartScrub.current ?: return null
    val activeTime = controller.activeTime ?: return null
    if (hourly.isEmpty()) return null
    val chartX = currentTimeChartX(hourly, startDate, activeTime) ?: return null
    val idx = chartX.roundToInt().coerceIn(0, hourly.lastIndex)
    return format(idx)
}

/** Concatenate a subtitle with an optional readout, separated by " · ". */
internal fun appendReadout(subtitle: String?, readout: String?): String? = when {
    subtitle.isNullOrEmpty() -> readout
    readout.isNullOrEmpty() -> subtitle
    else -> "$subtitle · $readout"
}

// `formatScrubHour` and `LocalTimeFormat` live in `ui/TimeFormatLocal.kt`
// so the chart cards and the settings screens share the same plumbing.
