package app.clothescast.ui.today

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
import androidx.compose.ui.unit.dp
import app.clothescast.core.domain.model.PerModelHour
import app.clothescast.core.domain.model.PerModelHourly
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
 * Shared "diagnostic card" used by the per-model wind / cloud / humidity
 * surfaces. Each metric differs in three ways — which field of [PerModelHour]
 * to plot, what y-axis range to pin, and how to format the y values — and
 * everything else (title + subtitle copy aside) is identical: filter the
 * per-model overlays to the entries that have the metric, render those
 * sparse series at their original time indices so a single missing hour
 * doesn't drop the whole model, and surface a [ModelSpreadLegend] keyed to
 * exactly the models that ended up on the chart.
 *
 * Sparse handling: when only some of a model's hours carry the metric (the
 * upstream API can return nulls for individual hours of a model whose run
 * is still warming up), we plot the non-null hours at their original
 * indices and let Vico bridge the gap. The alternative — dropping the whole
 * model — loses an entire diagnostic line for the sake of one missing
 * sample. The card auto-hides when *every* consulted model is missing the
 * metric outright.
 *
 * Used by the [WindCard], [CloudCard] and [HumidityCard] wrappers below.
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
    val visibleModels = MODEL_DRAW_ORDER.filter { it in seriesByModel }
    if (visibleModels.isEmpty() || times.isEmpty()) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium)
            PerModelDiagnosticChart(
                times = times,
                seriesByModel = seriesByModel,
                visibleModels = visibleModels,
                yAxis = yAxis,
            )
            // Legend tracks the same visibleModels the chart actually drew —
            // pre-refactor, the legend was derived from byModel and could
            // list models whose lines had silently been filtered out.
            ModelSpreadLegend(visibleModelIds = visibleModels)
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
    seriesByModel: Map<String, List<Pair<Int, Double>>>,
    visibleModels: List<String>,
    yAxis: YAxis,
) {
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(seriesByModel, visibleModels) {
        producer.runTransaction {
            lineSeries {
                visibleModels.forEach { modelId ->
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

    val lineProvider = rememberPinnedLineProvider(visibleModels, mainLineColor = null)

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
