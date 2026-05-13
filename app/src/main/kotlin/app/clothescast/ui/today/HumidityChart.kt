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
 * Diagnostic chart of 2 m relative humidity per model. Low-signal at the cool
 * temperatures Europe sees most of the year — apparent-temperature's humidity
 * term only kicks in above ~20 °C — but worth surfacing for hot days where
 * it's the dominant feels-like contributor and for catching the rare cool-day
 * frontal-passage case where models disagree sharply on dew point.
 *
 * Pinned 0–100% y-axis (same shape as [PrecipitationChart] / [CloudChart]).
 * No "main" humidity line — [HourlyForecast] doesn't carry humidity, so we
 * draw the per-model overlays alone.
 */
@Composable
fun HumidityChart(
    times: List<LocalTime>,
    perModelHourly: PerModelHourly,
    modifier: Modifier = Modifier,
) {
    val overlays = perModelHourly.byModel
        .mapValues { (_, entries) -> entries.filter { it.relativeHumidityPct != null } }
        .filterValues { it.size == times.size }
    val visibleModels = MODEL_DRAW_ORDER.filter { it in overlays }
    if (visibleModels.isEmpty() || times.isEmpty()) return

    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(overlays) {
        producer.runTransaction {
            lineSeries {
                visibleModels.forEach { modelId ->
                    overlays.getValue(modelId).let { entries ->
                        series(entries.map { it.relativeHumidityPct!! })
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
