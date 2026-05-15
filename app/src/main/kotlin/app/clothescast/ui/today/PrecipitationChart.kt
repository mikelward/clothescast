package app.clothescast.ui.today

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
import kotlin.math.roundToInt

/**
 * Renders today's hourly chance-of-rain as a single line over a fixed 0–100%
 * y-axis. Mirrors [ForecastChart]'s scaffolding (CartesianChartHost +
 * CartesianChartModelProducer + LaunchedEffect) but without the temperature
 * chart's MIN_Y_SPAN dance — probability is bounded, so we pin the axis to
 * 0–100 and let Vico's auto-stepper pick label positions.
 *
 * The hourly list is sourced from the cached Insight, populated by the morning
 * worker. When the cache predates this feature [hourly] is empty and the chart
 * hides itself; the next worker run will fill it in.
 */
@Composable
fun PrecipitationChart(
    hourly: List<HourlyForecast>,
    modifier: Modifier = Modifier,
    // Optional per-model data; see [ForecastChart] for the same pattern.
    perModelHourly: PerModelHourly? = null,
    // When true, draw the individual model lines underneath the main line.
    // The y-axis is pinned 0..100 regardless, so this only affects which
    // series are emitted.
    showModelSpread: Boolean = false,
) {
    if (hourly.isEmpty()) return

    val overlays = perModelHourly?.byModel.orEmpty()
    val visibleModels = if (showModelSpread) {
        MODEL_DRAW_ORDER.filter { it in overlays }
    } else {
        emptyList()
    }
    val mainLineColor = MaterialTheme.colorScheme.primary

    val producer = remember { CartesianChartModelProducer() }
    // Key on [overlays] (the underlying map) rather than [visibleModels] (just
    // the ID list) so a refresh that updates per-model values while keeping
    // the same model IDs still re-emits the series. [showModelSpread] is
    // included separately so flipping the toggle on/off re-fires the effect
    // even when [overlays] hasn't changed.
    LaunchedEffect(hourly, overlays, showModelSpread) {
        producer.runTransaction {
            lineSeries {
                // Main blended line first so it stays at series index 0 in
                // both single and per-model views — see [ForecastChart] for
                // the rationale (keeps Vico's by-index animation stable so
                // the combined line doesn't fade in/out on toggle). Per-model
                // overlays follow and end up drawn on top; empty when
                // [showModelSpread] is off.
                series(hourly.map { it.precipitationProbabilityPct })
                visibleModels.forEach { modelId ->
                    overlays.getValue(modelId).let { entries ->
                        series(entries.map { it.precipitationProbabilityPct })
                    }
                }
            }
        }
    }

    val bottomFormatter = remember(hourly) {
        CartesianValueFormatter { _, value, _ ->
            val idx = value.toInt().coerceIn(0, hourly.lastIndex)
            "%02d".format(hourly[idx].time.hour)
        }
    }

    // Fixed 0..100 axis — probability is a percentage, so a calm day with a
    // few-percent peak should still show a flat baseline near the bottom of
    // the chart rather than autoscaling to amplify the noise. Keeps the chart
    // visually comparable across days too: a 20% line on a quiet day and a 20%
    // line on a wet day land at the same height.
    val rangeProvider = remember {
        object : CartesianLayerRangeProvider {
            override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) = 0.0
            override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) = 100.0
        }
    }

    val startFormatter = remember {
        CartesianValueFormatter { _, value, _ -> "${value.roundToInt()}%" }
    }

    val lineProvider = rememberPinnedLineProvider(visibleModels, mainLineColor)

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
        // Match ForecastChart: force-fit the full 24-hour series instead of
        // leaving the user to scroll horizontally on a glanceable summary card.
        zoomState = rememberVicoZoomState(initialZoom = Zoom.Content),
        modifier = modifier
            .fillMaxWidth()
            // Shorter than ForecastCard's 180.dp temperature chart — probability
            // is bounded 0–100 and doesn't need as much vertical room for the
            // line to be readable.
            .height(140.dp),
    )
}
