package app.clothescast.ui.today

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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

@Composable
internal fun rememberChartScrubController(): ChartScrubController =
    remember { ChartScrubController() }

/**
 * Per-chart record of where in canvas pixels the data area sits.
 * Written from [ChartScrubIndicator]'s draw pass on every frame (canvas
 * pixels coincide with the parent Box's Compose pixels here, so the
 * gesture handler and tooltip overlay use them directly). Read by
 * [Modifier.chartScrub] to convert pointer-x → chart-x → time and by
 * [BoxScope.ChartScrubOverlay] to position itself at the indicator.
 *
 * [dataMinPx]/[dataMaxPx] are the *normalised* extents of the plot
 * grid (min ≤ max) so the in-grid hit test works in both LTR and RTL.
 * [chartZeroPx] is the canvas-x of `chartX = ranges.minX`, and
 * [pxPerUnit] converts chart-x units to canvas pixels — negative in
 * RTL, where the chart draws right-to-left. The pair lets the gesture
 * invert pointer-x → chart-x without re-deriving Vico's layout
 * direction logic.
 */
@Stable
internal class ChartScrubBounds {
    var dataMinPx by mutableFloatStateOf(0f)
    var dataMaxPx by mutableFloatStateOf(0f)
    var layerTopPx by mutableFloatStateOf(0f)
    var layerBottomPx by mutableFloatStateOf(0f)
    var chartZeroPx by mutableFloatStateOf(0f)
    var pxPerUnit by mutableFloatStateOf(0f)
}

@Composable
internal fun rememberChartScrubBounds(): ChartScrubBounds = remember { ChartScrubBounds() }

/**
 * Inverse of [currentTimeChartX]: given a fractional chart-x value in
 * 0..hourly.lastIndex, reconstruct the wall-clock [LocalDateTime] —
 * needed when translating a pointer position back into a time we can
 * publish to the shared [ChartScrubController]. Handles tonight's
 * midnight wrap the same way [currentTimeChartX] does outbound, by
 * advancing the date on each backwards hour-of-day step in the list.
 */
internal fun chartXToTime(
    hourly: List<HourlyForecast>,
    startDate: LocalDate,
    chartX: Double,
): LocalDateTime? {
    if (hourly.isEmpty()) return null
    val clamped = chartX.coerceIn(0.0, hourly.lastIndex.toDouble())
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
 * Pointer handler. A down event that falls inside the chart's plot
 * grid (between the y- and x-axis labels) claims the gesture: publish
 * the pointer's wall-clock time to [controller], fire [onFirstContact]
 * (used to reveal model spread), and keep updating on every move.
 * Release leaves the indicator sticky — no snap-back.
 *
 * Down events *outside* the plot grid — on Vico's axis labels, on the
 * card padding above/below the chart, on the legend strip — are left
 * unconsumed so they bubble to the parent `verticalScroll` and the
 * page keeps scrolling normally. The plot grid is the only area where
 * vertical drag is repurposed.
 */
internal fun Modifier.chartScrub(
    controller: ChartScrubController,
    bounds: ChartScrubBounds,
    hourly: List<HourlyForecast>,
    startDate: LocalDate,
    onFirstContact: () -> Unit,
): Modifier = pointerInput(controller, bounds, hourly, startDate) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        val pos = down.position
        val inGrid = bounds.dataMaxPx > bounds.dataMinPx &&
            bounds.layerBottomPx > bounds.layerTopPx &&
            pos.x in bounds.dataMinPx..bounds.dataMaxPx &&
            pos.y in bounds.layerTopPx..bounds.layerBottomPx
        if (!inGrid) return@awaitEachGesture
        down.consume()
        publishScrub(controller, bounds, hourly, startDate, pos.x)
        onFirstContact()
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            change.consume()
            if (!change.pressed) break
            publishScrub(controller, bounds, hourly, startDate, change.position.x)
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
    // arithmetic, so a single-point chart (lastIndex = 0) doesn't divide
    // by zero either.
    val chartX = ((pointerX - bounds.chartZeroPx) / bounds.pxPerUnit).toDouble()
        .coerceIn(0.0, hourly.lastIndex.toDouble())
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
            val dataAtMin = drawingStart
            val dataAtMax = drawingStart +
                (multiplier * layerDimensions.xSpacing *
                    ((ranges.maxX - ranges.minX) / ranges.xStep)).toFloat()
            bounds.dataMinPx = minOf(dataAtMin, dataAtMax)
            bounds.dataMaxPx = maxOf(dataAtMin, dataAtMax)
            bounds.chartZeroPx = chartZero
            bounds.pxPerUnit = pxPerUnit
            bounds.layerTopPx = layerBounds.top
            bounds.layerBottomPx = layerBounds.bottom

            val time = controller?.activeTime ?: return
            val chartX = currentTimeChartX(hourly, startDate, time) ?: return
            if (chartX < ranges.minX || chartX > ranges.maxX) return
            val canvasX = chartZero + pxPerUnit * chartX.toFloat()
            line.drawVertical(context, canvasX, layerBounds.top, layerBounds.bottom)
        }
    }
}

/**
 * Floating tooltip + restore affordance rendered on top of each chart's
 * plot area. Call inside the same `Box` as the `CartesianChartHost` so
 * the canvas and Compose share a coordinate space — the bounds written
 * by the decoration are directly usable for [Modifier.offset].
 *
 * The tooltip names the indicator's time and (via [content]) the
 * chart's value at that time; visible whenever the controller has an
 * active time inside the chart's window. The vendored restore icon
 * (`ic_chart_restore`) appears top-right only when scrubbed — a tap
 * returns the indicator to "now".
 */
@Composable
internal fun BoxScope.ChartScrubOverlay(
    controller: ChartScrubController,
    bounds: ChartScrubBounds,
    hourly: List<HourlyForecast>,
    startDate: LocalDate,
    content: @Composable (hourIndex: Int) -> Unit,
) {
    val activeTime = controller.activeTime
    val chartX = activeTime?.let { currentTimeChartX(hourly, startDate, it) }
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }

    if (chartX != null && hourly.isNotEmpty() && bounds.pxPerUnit != 0f) {
        // Compute indicator pixel via the same signed linear map the
        // decoration draws with — works for single-point charts (no
        // fractional divide) and RTL (negative pxPerUnit).
        val canvasX = bounds.chartZeroPx + bounds.pxPerUnit * chartX.toFloat()
        val gapPx = with(density) { 8.dp.toPx() }
        val tooltipWidth = size.width.toFloat()
        val offsetX = when {
            canvasX + gapPx + tooltipWidth <= bounds.dataMaxPx -> canvasX + gapPx
            canvasX - gapPx - tooltipWidth >= bounds.dataMinPx -> canvasX - gapPx - tooltipWidth
            else -> (bounds.dataMaxPx - tooltipWidth).coerceAtLeast(bounds.dataMinPx)
        }
        val offsetY = bounds.layerTopPx + gapPx
        val hourIdx = chartX.roundToInt().coerceIn(0, hourly.lastIndex)
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .onSizeChanged { size = it },
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 2.dp,
        ) {
            Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                content(hourIdx)
            }
        }
    }

    if (controller.isScrubbed) {
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
}

/**
 * Locale-aware short-form time formatter — same pattern as the existing
 * `MissingPeriodPlaceholder` in `TodayScreen.kt`. 12h on en-US, 24h on
 * en-GB, etc., so the scrub tooltip matches the user's clock format.
 */
@Composable
internal fun rememberScrubTimeFormatter(): (LocalTime) -> String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    }
    return remember(formatter) { { time -> time.format(formatter) } }
}
