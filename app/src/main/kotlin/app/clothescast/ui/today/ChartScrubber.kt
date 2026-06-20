package app.clothescast.ui.today

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.clothescast.R
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.ui.formatScrubHour
import java.time.format.TextStyle
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.LineComponent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Coordinates per-model-spread visibility with scrub-mode entry and exit.
 * Wired by [TodayPage] when per-model data is available; left null when
 * it isn't (older cached payloads), in which case [ChartScrubController]
 * leaves spread state alone and the chart just scrubs.
 *
 * Contract:
 *  - [isSpreadVisible] reads the live spread state (the same `state.showModelSpread`
 *    the charts render from). The controller calls this at scrub-mode entry to
 *    decide whether to auto-reveal.
 *  - [revealSpread] / [hideSpread] route to the view-model's `revealModelSpread` /
 *    `hideModelSpread`. Both are one-way setters — see those for rationale.
 *
 * Scrub-mode entry auto-reveals the spread (via [revealSpread]); the per-chart
 * restore button ([ChartScrubController.reset]) clears it again — along with
 * any spread the user turned on themselves via the confidence/tap toggle,
 * since that button is only shown when there's a scrub or spread to clear.
 */
internal interface SpreadCoordinator {
    fun isSpreadVisible(): Boolean
    fun revealSpread()
    fun hideSpread()
}

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
 * Scrub-mode entry (the first [scrubTo] in a session — when [isScrubbed]
 * transitions from false to true) doubles as the per-model-spread reveal
 * trigger: if [spreadCoordinator] is wired and the spread isn't already
 * visible, we flip it on. [reset] (the per-chart restore button) clears the
 * scrub and hides the spread again — including spread the user turned on
 * themselves, since that button is only shown when there's something to clear.
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

    /**
     * Optional bridge to per-model-spread state. See [SpreadCoordinator].
     * Wired from the host page on every recomposition (the lambdas inside
     * the coordinator close over the latest spread state and view-model
     * methods); the gesture handler calls [scrubTo] / [reset] obliviously.
     */
    var spreadCoordinator: SpreadCoordinator? = null

    fun setNow(now: LocalDateTime?) {
        nowTime = now
        if (!isScrubbed) activeTime = now
    }

    fun scrubTo(time: LocalDateTime) {
        val firstScrub = !isScrubbed
        activeTime = time
        isScrubbed = true
        if (firstScrub) {
            val coord = spreadCoordinator
            if (coord != null && !coord.isSpreadVisible()) coord.revealSpread()
        }
    }

    fun reset() {
        isScrubbed = false
        activeTime = nowTime
        // Clear the spread too. The restore button this drives only appears
        // when the chart is scrubbed or the spread is on, so a tap returns the
        // chart to its resting "now / consensus-only" view regardless of how
        // the spread got turned on (scrub auto-reveal or the confidence/tap
        // toggle).
        spreadCoordinator?.hideSpread()
    }
}

internal val LocalChartScrub = compositionLocalOf<ChartScrubController?> { null }

/**
 * Optional override for the x-axis label formatter shared by every chart on
 * the Today screen. Null (the default) keeps the existing hour-of-day
 * formatter, which is what the per-period pages want. The 7-day page wraps
 * its chart stack in a `CompositionLocalProvider(LocalChartBottomFormatter
 * provides …)` with a day-of-week formatter so 168 hourly samples don't
 * read as the same 00–23 axis repeated seven times.
 *
 * Each chart composable consults this local at render time; passing it via
 * the CompositionLocal instead of an extra parameter on every chart +
 * card keeps the per-period call sites byte-identical and avoids
 * cascading signature churn through ~12 composables.
 */
internal val LocalChartBottomFormatter =
    compositionLocalOf<com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter?> { null }

/**
 * Paired with [LocalChartBottomFormatter]. Vico won't accept an empty string
 * from a value formatter, so on the 7-day page where we only want to label
 * one tick per day (the day-of-week at noon) we hand it an
 * [HorizontalAxis.ItemPlacer] that places ticks at multiples of 24 instead
 * of letting Vico's default placer try to render a label at every hour.
 * Null on the per-period pages so the existing default placer keeps its
 * behaviour byte-identical.
 */
internal val LocalChartBottomItemPlacer =
    compositionLocalOf<com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis.ItemPlacer?> { null }

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
 * Pointer handler. Scrub-mode entry is explicit: the user has to *tap*
 * the chart's plot grid (down + release with no meaningful movement) to
 * start scrubbing. Until then, drags pass through to the parent — a
 * vertical drag scrolls the page, a horizontal drag swipes the pager.
 * Once the controller is in scrub mode, taps and drags inside the plot
 * grid scrub the indicator; tap the restore icon to exit.
 *
 * Gesture flow on a down inside the plot grid:
 *
 *  - **Tap** (release before any meaningful movement, in either mode):
 *    publish a scrub at the down position. In idle mode this enters
 *    scrub mode and the controller's [SpreadCoordinator] flips on the
 *    per-model spread (if it wasn't already).
 *  - **Clearly vertical drag**: return without consuming so the parent
 *    `verticalScroll` picks the gesture up — the user can scroll the
 *    page even when their finger started inside a chart, regardless of
 *    scrub mode.
 *  - **Clearly horizontal drag**:
 *      - In idle mode: return without consuming so the pager can swipe
 *        between Today and Tomorrow. We do not auto-enter scrub mode on
 *        a drag — that would re-introduce the trap-the-page-swipe
 *        problem the previous design had.
 *      - In scrub mode (the user already tapped the chart): claim the
 *        gesture and scrub continuously as the finger moves.
 *
 * Down events *outside* the plot grid — on Vico's axis labels, on the
 * card padding above/below the chart, on the legend strip — are left
 * unconsumed in both modes.
 */
internal fun Modifier.chartScrub(
    controller: ChartScrubController,
    bounds: ChartScrubBounds,
    hourly: List<HourlyForecast>,
    startDate: LocalDate,
): Modifier = pointerInput(controller, bounds, hourly, startDate) {
    val touchSlop = viewConfiguration.touchSlop
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
        // Capture scrub-mode state once at the start of the gesture.
        // Reading [isScrubbed] mid-gesture would lock in a stale answer
        // if the user taps to enter and drags in the same motion (which
        // doesn't happen — a tap requires release first — but the
        // snapshot at gesture start is the cleanest invariant either way).
        val startedInScrubMode = controller.isScrubbed
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
                    // handle it. Applies in both modes: once the user has
                    // entered scrub mode they can still scroll the page
                    // vertically; tap restore (or any other chart) to
                    // re-target the indicator afterwards.
                    return@awaitEachGesture
                }
                if (absDx > touchSlop) {
                    if (!startedInScrubMode) {
                        // Idle + horizontal drag — let the pager handle
                        // it. Scrub mode requires an explicit tap to
                        // enter, deliberately, so dragging the chart
                        // doesn't trap a page-swipe attempt.
                        return@awaitEachGesture
                    }
                    // Already in scrub mode — claim the horizontal drag
                    // and scrub continuously. Publish the down position
                    // first so the indicator snaps to the finger before
                    // tracking the move.
                    claimed = true
                    down.consume()
                    change.consume()
                    publishScrub(controller, bounds, hourly, startDate, downPos.x)
                    publishScrub(controller, bounds, hourly, startDate, change.position.x)
                }
                // Otherwise ambiguous: stay uncommitted and watch the
                // next event. If the finger lifts here, the `pressed`
                // check below catches it and we fall through to tap.
            } else {
                change.consume()
                publishScrub(controller, bounds, hourly, startDate, change.position.x)
            }
            if (!change.pressed) {
                if (!claimed) {
                    // Released before reaching touch slop — a tap.
                    // Publish a scrub at the original down position; in
                    // idle this enters scrub mode (and the controller's
                    // coordinator reveals the per-model spread), in
                    // scrub mode it just retargets the indicator.
                    publishScrub(controller, bounds, hourly, startDate, downPos.x)
                }
                break
            }
        }
    }
}

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
    private val line = LineComponent(fill = Fill(color), thickness = 1.5.dp)

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
            // Clamp to the plot's visible edges instead of culling. See
            // [indicatorCanvasX] — the live "now" position can sit up to a
            // full hour past the last data point (the 06:30–07:00 gap on a
            // TONIGHT chart whose last point is 06:00), which lands beyond
            // Vico's ~half-cell end padding; pinning it to the edge keeps
            // the indicator visible through the final hour.
            val canvasX = indicatorCanvasX(
                chartZero = chartZero,
                pxPerUnit = pxPerUnit,
                chartX = chartX,
                layerLeftPx = layerBounds.left,
                layerRightPx = layerBounds.right,
            )
            line.drawVertical(context, canvasX, layerBounds.top, layerBounds.bottom)
        }
    }
}

/**
 * Restore-icon affordance — top-right corner of each chart card,
 * outside the chart canvas so it doesn't overlap data lines or axis
 * labels. Call inside a `Box` that wraps the card's `Column` content
 * (the Box gives the IconButton a parent to align against).
 *
 * Renders whenever there's something to clear — the chart is scrubbed
 * away from "now" ([ChartScrubController.isScrubbed]) *or* the per-model
 * spread is on ([spreadShown]) — and a tap returns the chart to its
 * resting view: snaps the indicator back to now and hides the spread, via
 * [ChartScrubController.reset]. So every chart carries its own way to clear
 * the per-model lines, not just the confidence/tap toggle elsewhere on the
 * page.
 *
 * Callers pass [spreadShown] only when *this* chart actually draws per-model
 * curves (its `perModelHourly` is non-null). The spread flag is global, but a
 * period without per-model data draws no lines and leaves the controller's
 * [SpreadCoordinator] null — so showing the button off the flag alone would
 * strand an icon that [ChartScrubController.reset] can't act on.
 *
 * The readout text (time + value at the indicator) is rendered
 * separately by [ChartReadout] in the card's text column.
 */
@Composable
internal fun BoxScope.ChartRestoreOverlay(
    controller: ChartScrubController,
    spreadShown: Boolean = false,
) {
    val scrubbed = controller.isScrubbed
    if (!scrubbed && !spreadShown) return
    // Describe what the tap will actually do — the button now serves two jobs
    // (snap a scrubbed chart back to "now" and/or hide the per-model lines), so
    // a fixed "Show now" would mislead screen-reader users in the spread-only
    // case (chart already at now, lines on from the confidence chip).
    val description = stringResource(
        when {
            scrubbed && spreadShown -> R.string.today_chart_reset_now_hide_models
            scrubbed -> R.string.today_chart_reset_to_now
            else -> R.string.today_chart_hide_model_forecasts
        },
    )
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
            contentDescription = description,
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
 * the probability, diagnostics read the consensus mainLine. The
 * [activeMoment] argument carries the date too, so cards on the 7-day
 * page can disambiguate "Wed 2pm" from "Thu 2pm" via [formatScrubMoment].
 */
@Composable
internal fun rememberChartReadout(
    hourly: List<HourlyForecast>,
    startDate: LocalDate,
    format: @Composable (hourIndex: Int, activeMoment: LocalDateTime) -> String?,
): String? {
    val controller = LocalChartScrub.current ?: return null
    val activeTime = controller.activeTime ?: return null
    if (hourly.isEmpty()) return null
    val chartX = currentTimeChartX(hourly, startDate, activeTime) ?: return null
    val idx = chartX.roundToInt().coerceIn(0, hourly.lastIndex)
    // Reconstruct the bucket's wall-clock moment from [idx] so the readout's
    // displayed date and hour-of-day match the value being read off, not the
    // raw scrub position (which can sit halfway into the bucket near 14:30
    // and would surface as "14:30" instead of the bucket's "14:00").
    val bucketMoment = chartXToTime(hourly, startDate, idx.toDouble()) ?: activeTime
    return format(idx, bucketMoment)
}

/**
 * Selects how [formatScrubMoment] renders the indicator's wall-clock time
 * inside a readout: `HourOnly` ("2pm" / "14:00") on the per-period
 * Today/Tomorrow pages where everything is on the same date, `DayPlusHour`
 * ("Wed 2pm") on the 7-day page where the same hour-of-day repeats across
 * seven days and a bare hour reading wouldn't say *which* day. The 7-day
 * page provides `DayPlusHour` via [CompositionLocalProvider] so the
 * per-period cards stay byte-identical on their normal pages.
 */
internal enum class ScrubMomentFormat { HourOnly, DayPlusHour }

internal val LocalScrubMomentFormat = compositionLocalOf { ScrubMomentFormat.HourOnly }

/**
 * The forecast-zone "today" on the multi-day deck, used to relativise peak-day
 * labels — a peak landing on this date reads "today", the next day "tomorrow",
 * otherwise the weekday name (see `peakDayLabel`). Null on the per-period pages
 * and in previews that don't set it, where the label always uses the weekday.
 * The following-week page may set it too; its window never contains today /
 * tomorrow, so the relativisation simply never fires there.
 */
internal val LocalForecastToday = compositionLocalOf<LocalDate?> { null }

/**
 * Formats the indicator's wall-clock moment for the readout line. Picks
 * the right variant by [LocalScrubMomentFormat]; on the 7-day page that
 * prepends a short day-of-week label so a Wednesday 2pm reading doesn't
 * look identical to a Thursday 2pm one.
 */
@Composable
internal fun formatScrubMoment(moment: LocalDateTime): String {
    val hour = formatScrubHour(moment.toLocalTime())
    return when (LocalScrubMomentFormat.current) {
        ScrubMomentFormat.HourOnly -> hour
        ScrubMomentFormat.DayPlusHour -> {
            val locale = LocalConfiguration.current.locales[0]
            val day = moment.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            "$day $hour"
        }
    }
}

/**
 * Subtitle row used above every chart card: the static summary on the
 * left, the scrub readout right-aligned on the right. The subtitle takes
 * weight so it stays in its lane as the readout grows; the readout sits
 * flush right so the user's eye doesn't have to track a moving comma to
 * find the live value. Renders nothing when both strings are blank so
 * dry-day cards without scrub state don't add empty vertical space.
 */
@Composable
internal fun ChartSubtitleRow(
    subtitle: String?,
    readout: String?,
    modifier: Modifier = Modifier,
) {
    if (subtitle.isNullOrEmpty() && readout.isNullOrEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = subtitle.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (!readout.isNullOrEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = readout,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// `formatScrubHour` and `LocalTimeFormat` live in `ui/TimeFormatLocal.kt`
// so the chart cards and the settings screens share the same plumbing.
