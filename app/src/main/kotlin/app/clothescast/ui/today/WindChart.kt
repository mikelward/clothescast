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
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Diagnostic chart of 10 m wind speed per model — the most useful follow-up to
 * the temp chart when models diverge on feels-like, since wind chill is the
 * dominant feels-like contributor below ~20 °C. There's no blended "main"
 * wind line because [HourlyForecast] doesn't carry wind (yet), so this chart
 * is purely per-model overlays; we hide it when no model returned wind data.
 *
 * Y-axis pinned to start at 0 (calm conditions are meaningful — a flat
 * baseline reads as "no wind today") and auto-scaled upward with a 10 km/h
 * minimum span so a near-still day doesn't get amplified into a noisy zigzag.
 *
 * Matches [PrecipitationChart]'s scaffolding (CartesianChartHost +
 * CartesianChartModelProducer + LaunchedEffect) and reuses
 * [rememberPinnedLineProvider] for colour pinning so the ECMWF / GFS / ICON
 * lines stay the same hue they do on the temp + rain charts.
 */
@Composable
fun WindChart(
    times: List<LocalTime>,
    perModelHourly: PerModelHourly,
    modifier: Modifier = Modifier,
) {
    val overlays = perModelHourly.byModel
        .mapValues { (_, entries) -> entries.filter { it.windSpeedKmh != null } }
        .filterValues { it.size == times.size }
    val visibleModels = MODEL_DRAW_ORDER.filter { it in overlays }
    if (visibleModels.isEmpty() || times.isEmpty()) return

    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(overlays) {
        producer.runTransaction {
            lineSeries {
                visibleModels.forEach { modelId ->
                    overlays.getValue(modelId).let { entries ->
                        series(entries.map { it.windSpeedKmh!! })
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

    // Pin the bottom at 0 (calm is meaningful; a stiff breeze and a flat-zero
    // day should read differently). Top auto-scales from the data, padded up to
    // a 10 km/h minimum span so a 1–2 km/h still day doesn't get zoomed into
    // noise. We also pad the max slightly upward so the highest sample isn't
    // jammed against the top edge.
    val rangeProvider = remember(overlays) {
        val all = overlays.values.flatMap { entries -> entries.mapNotNull { it.windSpeedKmh } }
        val rawMax = all.maxOrNull() ?: 0.0
        val dataMax = ceil(rawMax).coerceAtLeast(MIN_Y_SPAN_KMH)
        object : CartesianLayerRangeProvider {
            override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) = 0.0
            override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) = dataMax
        }
    }

    val startFormatter = remember {
        CartesianValueFormatter { _, value, _ -> value.roundToInt().toString() }
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

// Smallest y-axis span the wind chart will display. A near-still day with a
// 1 km/h breeze padded to this still shows the line near the bottom of the
// chart rather than amplified to a peak — same MIN_Y_SPAN reasoning as the
// temperature chart, just in km/h.
private const val MIN_Y_SPAN_KMH = 10.0
