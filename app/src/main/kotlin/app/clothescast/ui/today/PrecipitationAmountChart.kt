package app.clothescast.ui.today

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.PerModelHourly
import app.clothescast.ui.theme.AppTheme
import java.time.LocalDate
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineSeries
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders today's hourly rainfall amount (mm) as a single line over an
 * auto-scaling y-axis pinned to zero. Companion to [PrecipitationChart] —
 * same scrub / per-model-overlay scaffolding, but the y-axis grows with the
 * day's peak instead of being pinned 0–100 (amounts have no natural
 * ceiling: 0.2 mm drizzle and a 20 mm/h tropical downpour both need to
 * read cleanly). The [PRECIPITATION_AMOUNT_MIN_SPAN_MM] floor keeps a dry
 * day from collapsing the chart onto the x-axis with no visible scale.
 *
 * The hourly list is sourced from the cached Insight, populated by the
 * morning worker. When the cache predates the precipitation-amount field
 * the series is wholesale zero — the chart still renders (flat baseline)
 * and the surrounding [PrecipitationAmountCard] surfaces the dry-day
 * blurb instead of a peak callout.
 */
@Composable
fun PrecipitationAmountChart(
    hourly: List<HourlyForecast>,
    startDate: LocalDate = LocalDate.now(),
    modifier: Modifier = Modifier,
    perModelHourly: PerModelHourly? = null,
    showModelSpread: Boolean = false,
) {
    if (hourly.isEmpty()) return

    val overlays = perModelHourly?.byModel.orEmpty()
    val visibleModels = if (showModelSpread) {
        MODEL_DRAW_ORDER.filter { modelId ->
            overlays[modelId]?.any { it.precipitationMm != null } == true
        }
    } else {
        emptyList()
    }
    val mainLineColor = AppTheme.mainLineColor

    val mainLine = remember(hourly, overlays) { consensusRainfallMainLine(hourly, perModelHourly) }

    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(hourly, overlays, showModelSpread) {
        producer.runTransaction {
            lineSeries {
                series(mainLine)
                visibleModels.forEach { modelId ->
                    overlays.getValue(modelId).let { entries ->
                        val points = entries.mapIndexedNotNull { i, e ->
                            e.precipitationMm?.let { i to it }
                        }
                        series(x = points.map { it.first }, y = points.map { it.second })
                    }
                }
            }
        }
    }

    val defaultBottomFormatter = remember(hourly) {
        CartesianValueFormatter { _, value, _ ->
            val idx = value.toInt().coerceIn(0, hourly.lastIndex)
            "%02d".format(hourly[idx].time.hour)
        }
    }
    val bottomFormatter = LocalChartBottomFormatter.current ?: defaultBottomFormatter

    // Y: 0..max(peak across main + per-model, floor). The floor stops a dry
    // day (all-zero series) from collapsing the chart onto the bottom axis;
    // including the per-model peak in the ceiling keeps the y-axis the same
    // whether the overlay's drawn or not, so toggling [showModelSpread]
    // doesn't shift the labels under the data.
    //
    // X: pinned to the full hourly window for the same reason
    // [PrecipitationChart] does it — a sparse per-model series with leading
    // or trailing nulls shouldn't get stretched across the card by
    // Zoom.Content.
    val yMax = remember(mainLine, overlays) {
        val mainPeak = mainLine.maxOrNull() ?: 0.0
        val overlayPeak = overlays.values
            .flatten()
            .mapNotNull { it.precipitationMm }
            .maxOrNull() ?: 0.0
        max(max(mainPeak, overlayPeak), PRECIPITATION_AMOUNT_MIN_SPAN_MM)
    }
    val xMax = hourly.lastIndex.toDouble()
    val rangeProvider = remember(xMax, yMax) {
        object : CartesianLayerRangeProvider {
            override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) = 0.0
            override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) = yMax
            override fun getMinX(minX: Double, maxX: Double, extraStore: ExtraStore) = 0.0
            override fun getMaxX(minX: Double, maxX: Double, extraStore: ExtraStore) = xMax
        }
    }

    val startFormatter = remember {
        CartesianValueFormatter { _, value, _ -> formatPrecipitationMmAxis(value) }
    }

    val lineProvider = rememberPinnedLineProvider(visibleModels, mainLineColor)

    val scrubController = LocalChartScrub.current
    val scrubBounds = rememberChartScrubBounds()
    val scrubIndicator = rememberChartScrubIndicator(scrubController, scrubBounds, hourly, startDate)
    // Shaded min–max band on the default view, hidden once the per-model overlay
    // is on. Same rainfall-mm picker as the lines; shares the 0..yMax range.
    val (bandMin, bandMax) = remember(overlays, hourly) {
        if (perModelHourly == null) {
            emptyList<Pair<Int, Double>>() to emptyList()
        } else {
            perModelEnvelope(perModelHourly, hourly.map { it.time }) { it.precipitationMm }
        }
    }
    val rangeBand = rememberRangeBandDecoration(
        minSeries = bandMin,
        maxSeries = bandMax,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = RANGE_BAND_ALPHA),
        visible = !showModelSpread,
    )
    val decorations = listOf(rangeBand, scrubIndicator)
    val axisLabel = rememberOnSurfaceAxisLabel()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp)
            .let { mod ->
                if (scrubController != null) {
                    mod.chartScrub(scrubController, scrubBounds, hourly, startDate)
                } else {
                    mod
                }
            },
    ) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(
                    lineProvider = lineProvider,
                    rangeProvider = rangeProvider,
                ),
                startAxis = VerticalAxis.rememberStart(label = axisLabel, valueFormatter = startFormatter),
                bottomAxis = LocalChartBottomItemPlacer.current?.let { placer ->
                    HorizontalAxis.rememberBottom(
                        label = axisLabel,
                        itemPlacer = placer,
                        valueFormatter = bottomFormatter,
                    )
                } ?: HorizontalAxis.rememberBottom(label = axisLabel, valueFormatter = bottomFormatter),
                decorations = decorations,
            ),
            modelProducer = producer,
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
            animateIn = CHART_ANIMATE_IN,
            animationSpec = CHART_ANIMATION_SPEC,
            modifier = Modifier.matchParentSize(),
        )
    }
}

/**
 * Floor for the y-axis on the precipitation-amount chart, mm. Stops a dry
 * day's all-zero series from collapsing onto the bottom axis with no
 * visible scale. 5 mm spans light-to-moderate rainfall in a single hour —
 * heavier days auto-scale beyond it.
 */
internal const val PRECIPITATION_AMOUNT_MIN_SPAN_MM: Double = 5.0

/**
 * Per-hour main-line series for the precipitation-amount chart and its
 * scrub readout — cross-model mean of `precipitationMm` across whichever
 * consulted models reported at each hour. Falls back to the best-match
 * line ([HourlyForecast.precipitationMm]) at hours where no per-model
 * reported, so the main line stays continuous over the full window and
 * aligns with the readout's hour-by-hour indexing.
 *
 * When [perModelHourly] is null or no consulted model carries any
 * precipitation at all, collapses to the best-match line outright. The
 * card subtitle derives its total by summing this series, so the
 * "Combined" chart line and the "X mm of rain today" figure above it
 * are always consistent — they come from the same data.
 *
 * Output length matches `hourly.size`; per-model entries are assumed to
 * align by list position (same date-sliced fetch), the convention used by
 * the existing [PerModelDiagnosticCard].
 */
internal fun consensusRainfallMainLine(
    hourly: List<HourlyForecast>,
    perModelHourly: PerModelHourly?,
): List<Double> {
    val overlays = perModelHourly?.byModel.orEmpty()
    val anyPerModel = overlays.values.any { entries -> entries.any { it.precipitationMm != null } }
    if (!anyPerModel) return hourly.map { it.precipitationMm }
    return hourly.indices.map { idx ->
        val perModelValues = overlays.values.mapNotNull { entries ->
            entries.getOrNull(idx)?.precipitationMm
        }
        if (perModelValues.isEmpty()) hourly[idx].precipitationMm
        else perModelValues.average()
    }
}

/**
 * Formats a y-axis value (mm) for the precipitation-amount chart and its
 * scrub readout. Sub-millimetre values keep one decimal so drizzle reads
 * as "0.4 mm" rather than rounding to "0 mm"; whole-mm-and-up values
 * round to the nearest integer because "12 mm" is what the user actually
 * thinks in. Nearest-integer rounding (not truncation) keeps the
 * displayed amount faithful — a 1.9 mm hour reads as "2 mm", not "1 mm".
 * Exact zero drops the decimal too — the y-axis floor label reads "0 mm",
 * not "0.0 mm", since there's no precision to convey at zero.
 */
internal fun formatPrecipitationMmAxis(value: Double): String =
    when {
        value == 0.0 -> "0 mm"
        value < 1.0 -> "%.1f mm".format(value)
        else -> "%d mm".format(value.roundToInt())
    }
