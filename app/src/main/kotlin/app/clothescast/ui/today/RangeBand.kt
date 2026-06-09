package app.clothescast.ui.today

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.PerModelHour
import app.clothescast.core.domain.model.PerModelHourly
import app.clothescast.core.domain.model.PerModelHourly.Companion.BEST_MATCH_MODEL_ID
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
import java.time.LocalDate
import java.time.LocalDateTime

// Opacity of the shaded min–max range band drawn beneath the consensus line on
// the default chart view. Low enough to read as a soft "spread" shading without
// muddying the line or the gridlines; first-pass value to eyeball on-device.
internal const val RANGE_BAND_ALPHA = 0.12f

/**
 * Maps each entry of a chart's hourly window to its list position, keyed by
 * the entry's full wall-clock timestamp. [HourlyForecast] carries only a
 * `LocalTime`, so the date is reconstructed by starting at [startDate] (the
 * date of `hourly[0]`) and advancing it whenever the hour-of-day steps
 * backwards relative to the previous entry — the same midnight-wrap walk
 * `chartXToTime` uses, so the tonight window's post-midnight hours land on
 * the next date. Walking the window's actual entries (rather than adding
 * `i` hours to the window start) keeps positions correct on DST-transition
 * weeks, where a 23- or 25-hour day makes "hours since window start"
 * diverge from list position. On a fall-back day whose duplicated
 * wall-clock hour appears twice, the first occurrence wins — the same
 * ambiguity the per-model timestamps carry, so lookups stay consistent.
 *
 * Per-model series ([PerModelHour.time]) are positioned on the charts by
 * looking their timestamps up here, so every per-model value lands on the
 * x position the consensus main line plots that wall-clock hour at. In the
 * common case (no dropped hours, no DST) every per-model entry resolves to
 * exactly its list position, so the rendered output is unchanged.
 */
internal fun hourlyTimestampIndices(
    hourly: List<HourlyForecast>,
    startDate: LocalDate,
): Map<LocalDateTime, Int> {
    val indices = LinkedHashMap<LocalDateTime, Int>(hourly.size)
    var date = startDate
    var prevHour: Int? = null
    for (i in hourly.indices) {
        val hour = hourly[i].time.hour
        if (prevHour != null && hour < prevHour) date = date.plusDays(1)
        prevHour = hour
        val timestamp = LocalDateTime.of(date, hourly[i].time)
        if (timestamp !in indices) indices[timestamp] = i
    }
    return indices
}

/**
 * Per-hour min and max of [picker] across the consulted models — the envelope
 * the [rememberRangeBandDecoration] band fills. Returned as `(hourIndex, value)`
 * lists at the series' original indices, computed only where at least two models
 * reported (a one-model "band" has zero width — skip it). Min and max share the
 * same index set, so the band path can walk the max envelope out and the min
 * envelope back.
 *
 * `best_match` is excluded so the band reflects the named consulted models, the
 * same set [app.clothescast.core.domain.model.ConfidenceInfo] measures agreement
 * over. TODO(range-band): revisit including best_match in the envelope — it would
 * widen the band by the auto-pick's deviation; decide once we've seen it on-device.
 *
 * Values are raw: the caller passes a [picker] that already applies the same unit
 * conversion it uses for the chart's series, so the band lands on the same scale.
 *
 * Indexed against the chart's own hourly window via [indexByTime] — the
 * timestamp → list-position map [hourlyTimestampIndices] builds from the
 * window's entries — by looking up each entry's full [PerModelHour.time], not
 * its list position. A model can drop an hour (the parser omits an entry when
 * its `temperature_2m` is null), which would shift its tail and make a
 * position-keyed band merge mismatched hours across models. The timestamp
 * lookup keeps every value under the hour the consensus main line plots it at,
 * and a leading hour that *every* consulted model dropped stays an empty gap
 * rather than sliding the band left. Resolving against the window's actual
 * entries (not naive hour arithmetic from the window start) also keeps the
 * band aligned with the position-plotted main line on DST-transition weeks,
 * where a 23- or 25-hour day makes position ≠ hours-since-window-start. The
 * full `LocalDateTime` (not time-of-day) is essential for the 168 h
 * `SevenDayPage` window, where a `LocalTime` would alias every day's 09:00
 * onto one index. An entry whose timestamp isn't in the map (outside the
 * window) is skipped.
 */
internal fun perModelEnvelope(
    perModelHourly: PerModelHourly,
    indexByTime: Map<LocalDateTime, Int>,
    picker: (PerModelHour) -> Double?,
): Pair<List<Pair<Int, Double>>, List<Pair<Int, Double>>> {
    val byIndex = mutableMapOf<Int, MutableList<Double>>()
    perModelHourly.byModel.forEach { (model, entries) ->
        if (model == BEST_MATCH_MODEL_ID) return@forEach
        entries.forEach { entry ->
            val idx = indexByTime[entry.time] ?: return@forEach
            picker(entry)?.let { byIndex.getOrPut(idx) { mutableListOf() } += it }
        }
    }
    return byIndex.toEnvelope()
}

/**
 * Envelope variant for callers that already hold per-model `(index, value)`
 * series (the diagnostic cards build `seriesByModel` once and reuse it). Same
 * contract as [perModelEnvelope]: best_match excluded, indices where ≥2 models
 * reported.
 */
internal fun envelopeFromSeries(
    seriesByModel: Map<String, List<Pair<Int, Double>>>,
): Pair<List<Pair<Int, Double>>, List<Pair<Int, Double>>> {
    val byIndex = mutableMapOf<Int, MutableList<Double>>()
    seriesByModel.forEach { (model, entries) ->
        if (model == BEST_MATCH_MODEL_ID) return@forEach
        entries.forEach { (idx, value) -> byIndex.getOrPut(idx) { mutableListOf() } += value }
    }
    return byIndex.toEnvelope()
}

private fun Map<Int, MutableList<Double>>.toEnvelope():
    Pair<List<Pair<Int, Double>>, List<Pair<Int, Double>>> {
    val sorted = entries.filter { it.value.size >= 2 }.sortedBy { it.key }
    return sorted.map { it.key to it.value.min() } to sorted.map { it.key to it.value.max() }
}

/**
 * Splits the (ascending) [indices] into maximal runs of consecutive hour
 * indices, returned as ranges of *positions* into the list. The band fills one
 * polygon per run so it doesn't span an index the envelope skipped (an hour with
 * too few models for a spread). `[0,1,2,3]` → `[0..3]`; `[0,2]` → `[0..0, 1..1]`;
 * `[0,1,3,4]` → `[0..1, 2..3]`.
 */
internal fun contiguousRuns(indices: List<Int>): List<IntRange> {
    if (indices.isEmpty()) return emptyList()
    val runs = mutableListOf<IntRange>()
    var start = 0
    for (k in 1 until indices.size) {
        if (indices[k] != indices[k - 1] + 1) {
            runs += start..(k - 1)
            start = k
        }
    }
    runs += start..indices.lastIndex
    return runs
}

/**
 * A translucent fill between the per-hour [minSeries] and [maxSeries] envelopes,
 * drawn *beneath* the chart's lines (it's a [Decoration] using `drawUnderLayers`,
 * not a Vico series, so it doesn't perturb the pinned series-index animation).
 * [visible] is `!showModelSpread` — on the default view the band conveys the
 * spread; once the user expands the per-model overlay the individual lines do,
 * so the band hides.
 */
@Composable
internal fun rememberRangeBandDecoration(
    minSeries: List<Pair<Int, Double>>,
    maxSeries: List<Pair<Int, Double>>,
    color: Color,
    visible: Boolean,
): Decoration =
    remember(minSeries, maxSeries, color, visible) {
        RangeBandDecoration(minSeries, maxSeries, color, visible)
    }

private class RangeBandDecoration(
    private val minSeries: List<Pair<Int, Double>>,
    private val maxSeries: List<Pair<Int, Double>>,
    color: Color,
    private val visible: Boolean,
) : Decoration {
    private val paint = Paint().also { it.color = color; it.isAntiAlias = true }
    private val path = Path()

    override fun drawUnderLayers(context: CartesianDrawingContext) {
        // Need at least two points on each envelope to fill an area; min/max
        // share indices, so checking one is enough but check both defensively.
        if (!visible || minSeries.size < 2 || maxSeries.size < 2) return
        with(context) {
            // x-map: identical to ChartScrubIndicator — signed pxPerUnit keeps
            // it correct in RTL (multiplier = -1). scroll is 0 on these fit-to-
            // content charts but kept for parity.
            val multiplier = layoutDirectionMultiplier.toFloat()
            val drawingStart =
                (if (isLtr) layerBounds.left else layerBounds.right) +
                    multiplier * layerDimensions.startPadding - scroll
            val pxPerUnit = (multiplier * layerDimensions.xSpacing / ranges.xStep).toFloat()
            val chartZero = drawingStart - pxPerUnit * ranges.minX.toFloat()
            fun canvasX(index: Int): Float = chartZero + pxPerUnit * index

            // y-map: the live y-range the chart pinned via its rangeProvider, so
            // the band tracks the same scale as the lines. Clamp into the plot in
            // case a value sits a hair outside the rounded axis bounds.
            val yRange = ranges.getYRange(Axis.Position.Vertical.Start)
            val top = layerBounds.top
            val bottom = layerBounds.bottom
            fun canvasY(value: Double): Float {
                val frac = if (yRange.length == 0.0) 0.0 else (value - yRange.minY) / yRange.length
                return (bottom - frac * (bottom - top)).toFloat().coerceIn(top, bottom)
            }

            // Fill each contiguous run separately so the band doesn't bridge an
            // hour the envelope intentionally left out (< 2 models reported it) —
            // a gap is a gap, not a phantom spread. Common case: one run spanning
            // the whole window, identical to a single polygon.
            for (run in contiguousRuns(maxSeries.map { it.first })) {
                if (run.last == run.first) continue // a lone point can't fill an area
                path.reset()
                for (k in run) {
                    val (index, value) = maxSeries[k]
                    val x = canvasX(index)
                    val y = canvasY(value)
                    if (k == run.first) path.moveTo(x, y) else path.lineTo(x, y)
                }
                for (k in run.reversed()) {
                    val (index, value) = minSeries[k]
                    path.lineTo(canvasX(index), canvasY(value))
                }
                path.close()
                canvas.drawPath(path, paint)
            }
        }
    }
}
