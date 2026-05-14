package app.clothescast.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.clothescast.R
import app.clothescast.core.domain.model.PerModelHour
import app.clothescast.core.domain.model.PerModelHourly
import app.clothescast.ui.theme.AppTheme
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import java.time.LocalTime
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Shared "diagnostic card" used by every per-model metric (wind / cloud /
 * humidity / solar / sunshine / UV). The chart always draws a single
 * consensus main line — the per-hour mean across consulted models — and
 * optionally overlays the individual model lines when [showOverlay] is on.
 * This matches the temp / feels-like / precip cards' "main line first,
 * spread on tap" pattern so every chart on the Today screen reads the same
 * way.
 *
 * Each metric differs in three ways: which field of [PerModelHour] to plot,
 * what y-axis range to pin, and how to format the y values. The y-axis is
 * always sized to the per-model envelope (not just the main line) so
 * toggling [showOverlay] doesn't shift the axis underneath the data — the
 * spread fits in the same space whether or not it's visible.
 *
 * Sparse handling: when only some of a model's hours carry the metric (the
 * upstream API can return nulls for individual hours of a model whose run
 * is still warming up), we plot the non-null hours at their original
 * indices and let Vico bridge the gap. The main consensus line is computed
 * per-index from whichever models reported at that hour, so a single
 * missing sample doesn't punch a hole in the main line either. The card
 * auto-hides when *every* consulted model is missing the metric outright.
 *
 * Used by the [WindCard], [CloudCard], [HumidityCard], [SolarRadiationCard],
 * [SunshineCard] and [UvIndexCard] wrappers in [TodayScreen].
 */
@Composable
internal fun PerModelDiagnosticCard(
    title: String,
    subtitle: String,
    times: List<LocalTime>,
    perModelHourly: PerModelHourly,
    picker: (PerModelHour) -> Double?,
    yAxis: YAxis,
    /**
     * Extra cache key for callers whose [picker] closes over mutable state
     * (e.g. the wind card converts km/h → mph using the user's unit). Defaults
     * to [Unit] so plain field-accessor pickers (cloud, humidity) keep their
     * old single-key behaviour. Passing the lambda itself wouldn't work —
     * Compose treats fresh lambda instances as unequal, which would invalidate
     * the cache every recomposition.
     */
    pickerKey: Any? = Unit,
    /**
     * When true, overlay the per-model lines underneath the main consensus
     * line. When false, only the main line is drawn. Y-axis range stays the
     * same in both cases (sized to the per-model envelope) so toggling
     * doesn't shift the axis labels.
     */
    showOverlay: Boolean = false,
    /**
     * Wires the card up as tap-to-toggle the overlay. Null means the card is
     * not clickable — used for previews and any wrapper that wants to opt out.
     */
    onToggleOverlay: (() -> Unit)? = null,
) {
    // Build (originalIndex, value) pairs per model so a sparse series plots at
    // its real positions on the x-axis instead of getting compacted left and
    // misaligned with the rest of the screen's hourly axes.
    val seriesByModel = remember(perModelHourly, pickerKey) {
        perModelHourly.byModel
            .mapValues { (_, entries) ->
                entries.mapIndexedNotNull { i, e -> picker(e)?.let { i to it } }
            }
            .filterValues { it.isNotEmpty() }
    }
    val availableModels = MODEL_DRAW_ORDER.filter { it in seriesByModel }
    if (availableModels.isEmpty() || times.isEmpty()) return

    // Per-hour mean of [picker] across whichever models reported at that
    // hour. Plotted at the model's original index so the main line stays
    // aligned with the per-model overlay underneath it (when shown) and
    // with the rest of the screen's 0..23 axes.
    val mainLine = remember(seriesByModel) {
        val byIndex = mutableMapOf<Int, MutableList<Double>>()
        seriesByModel.values.forEach { entries ->
            entries.forEach { (idx, v) -> byIndex.getOrPut(idx) { mutableListOf() } += v }
        }
        byIndex.entries
            .sortedBy { it.key }
            .map { (idx, vs) -> idx to vs.average() }
    }

    val cardModifier = Modifier
        .fillMaxWidth()
        .let { if (onToggleOverlay != null) it.clickable(onClick = onToggleOverlay) else it }
    Card(modifier = cardModifier) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium)
            PerModelDiagnosticChart(
                times = times,
                mainLine = mainLine,
                seriesByModel = seriesByModel,
                overlayModels = if (showOverlay) availableModels else emptyList(),
                yAxis = yAxis,
            )
            ModelSpreadLegend(
                visibleModelIds = if (showOverlay) availableModels else emptyList(),
                mainLine = MainLineLegend(
                    color = AppTheme.mainLineColor,
                    label = stringResource(R.string.today_chart_main_line_label),
                ),
            )
        }
    }
}

/** Y-axis configuration for a [PerModelDiagnosticChart]. */
internal sealed class YAxis {
    /** Pinned 0..100 range — for percentage metrics (cloud cover, humidity). */
    object Percent : YAxis()

    /**
     * Auto-scaled 0..max axis with a minimum span — for unbounded metrics like
     * wind speed where 0 (calm) is meaningful but the top floats with the day.
     */
    data class AutoZeroBased(val minSpan: Double) : YAxis()
}

@Composable
private fun PerModelDiagnosticChart(
    times: List<LocalTime>,
    mainLine: List<Pair<Int, Double>>,
    seriesByModel: Map<String, List<Pair<Int, Double>>>,
    overlayModels: List<String>,
    yAxis: YAxis,
) {
    val mainLineColor = AppTheme.mainLineColor
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(seriesByModel, overlayModels, mainLine) {
        producer.runTransaction {
            lineSeries {
                // Main consensus line first so it stays at series index 0
                // whether or not the overlay is on — see [ForecastChart] for
                // the rationale (Vico animates by index, so pinning the main
                // line stops it fading in/out on toggle). Per-model overlays
                // follow and end up drawn on top of the consensus line. When
                // the overlay is off, [overlayModels] is empty and only the
                // main line is emitted — but the y-bounds below still use the
                // full per-model envelope so the axis labels don't jump.
                series(
                    x = mainLine.map { it.first },
                    y = mainLine.map { it.second },
                )
                overlayModels.forEach { modelId ->
                    val data = seriesByModel.getValue(modelId)
                    series(
                        x = data.map { it.first },
                        y = data.map { it.second },
                    )
                }
            }
        }
    }

    val bottomFormatter = remember(times) {
        CartesianValueFormatter { _, value, _ ->
            val idx = value.toInt().coerceIn(0, times.lastIndex)
            "%02d".format(times[idx].hour)
        }
    }

    // Pin the x-range to the full times window so a sparse series that's
    // missing the leading or trailing hours doesn't get stretched across the
    // whole card by Zoom.Content. Without this anchor, if every visible model
    // is missing (say) hours 0..18 of the day, the chart only sees x = 19..23
    // and fits those four points to the full width — visually misaligning the
    // line with the other cards' 0..23 axes and hiding the gap that's the
    // whole point of plotting at original indices.
    val xMin = 0.0
    val xMax = times.lastIndex.toDouble()
    // Wind picks a [niceStep]-aligned ceiling and a matching tick step so a
    // 26 km/h day reads as "0, 5, 10, …, 30" instead of Vico's default "0,
    // 13, 26". Percent stays pinned 0..100 with step 20.
    val yBounds = remember(yAxis, seriesByModel) {
        when (yAxis) {
            is YAxis.Percent -> YAxisBounds(min = 0.0, max = 100.0, step = 20.0)
            is YAxis.AutoZeroBased -> {
                val rawMax = seriesByModel.values
                    .flatten()
                    .maxOfOrNull { it.second } ?: 0.0
                val paddedMax = ceil(rawMax).coerceAtLeast(yAxis.minSpan)
                alignToStep(rawMin = 0.0, rawMax = paddedMax, step = niceStep(paddedMax))
            }
        }
    }
    val rangeProvider = remember(yBounds, xMax) {
        object : CartesianLayerRangeProvider {
            override fun getMinX(minX: Double, maxX: Double, extraStore: ExtraStore) = xMin
            override fun getMaxX(minX: Double, maxX: Double, extraStore: ExtraStore) = xMax
            override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) = yBounds.min
            override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) = yBounds.max
        }
    }
    val yItemPlacer = remember(yBounds.step) {
        VerticalAxis.ItemPlacer.step({ yBounds.step })
    }

    val startFormatter = remember(yAxis) {
        when (yAxis) {
            is YAxis.Percent -> CartesianValueFormatter { _, value, _ ->
                "${value.roundToInt()}%"
            }
            is YAxis.AutoZeroBased -> CartesianValueFormatter { _, value, _ ->
                value.roundToInt().toString()
            }
        }
    }

    // The line provider matches lines to series by index: overlay lines
    // first (keyed by [overlayModels] order), then the main consensus line
    // on top. Pass the theme primary as [mainLineColor] so the consensus
    // line matches the temp / precip cards' "main line is theme primary"
    // convention.
    val lineProvider = rememberPinnedLineProvider(overlayModels, mainLineColor)

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                lineProvider = lineProvider,
                rangeProvider = rangeProvider,
            ),
            startAxis = VerticalAxis.rememberStart(
                itemPlacer = yItemPlacer,
                valueFormatter = startFormatter,
            ),
            bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = bottomFormatter),
        ),
        modelProducer = producer,
        zoomState = rememberVicoZoomState(initialZoom = Zoom.Content),
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
    )
}
