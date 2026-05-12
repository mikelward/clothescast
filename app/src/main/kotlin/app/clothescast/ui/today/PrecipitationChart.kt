package app.clothescast.ui.today

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.clothescast.core.domain.model.HourlyForecast
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
) {
    if (hourly.isEmpty()) return

    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(hourly) {
        producer.runTransaction {
            lineSeries { series(hourly.map { it.precipitationProbabilityPct }) }
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

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(rangeProvider = rangeProvider),
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
