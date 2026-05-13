package app.clothescast.ui.today

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import kotlin.math.roundToInt

/**
 * Diagnostic chart of total cloud cover per model — the most useful diagnostic
 * when models disagree on raw air temperature: one model predicts a mid-day
 * clearing (more solar gain → warmer surface), the other keeps it overcast
 * (less gain → cooler). Cloud isn't a feels-like input but it's the upstream
 * driver of the air-temp divergence that propagates into feels-like.
 *
 * Pinned 0–100% y-axis (same shape as [PrecipitationChart]) so an overcast
 * day and a clear day are visually comparable across days. No "main" cloud
 * line — [HourlyForecast] doesn't carry cloud cover, so we draw the per-model
 * overlays alone.
 */
@Composable
fun CloudChart(
    times: List<LocalTime>,
    perModelHourly: PerModelHourly,
    modifier: Modifier = Modifier,
) {
    val overlays = perModelHourly.byModel
        .mapValues { (_, entries) -> entries.filter { it.cloudCoverPct != null } }
        .filterValues { it.size == times.size }
    val visibleModels = MODEL_DRAW_ORDER.filter { it in overlays }
    if (visibleModels.isEmpty() || times.isEmpty()) return

    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(overlays) {
        producer.runTransaction {
            lineSeries {
                visibleModels.forEach { modelId ->
                    overlays.getValue(modelId).let { entries ->
                        series(entries.map { it.cloudCoverPct!! })
                    }
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

    // Fixed 0..100 axis — cloud cover is a percentage, so a clear day's flat
    // baseline near zero and an overcast day's flat top near 100 should both
    // read cleanly. Same logic as PrecipitationChart's fixed range.
    val rangeProvider = remember {
        object : CartesianLayerRangeProvider {
            override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) = 0.0
            override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) = 100.0
        }
    }

    val startFormatter = remember {
        CartesianValueFormatter { _, value, _ -> "${value.roundToInt()}%" }
    }

    val lineProvider = rememberPinnedLineProvider(visibleModels, mainLineColor = null)

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                lineProvider = lineProvider,
                rangeProvider = rangeProvider,
            ),
            startAxis = VerticalAxis.rememberStart(valueFormatter = startFormatter),
            bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = bottomFormatter),
        ),
        modelProducer = producer,
        zoomState = rememberVicoZoomState(initialZoom = Zoom.Content),
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp),
    )
}
