package app.clothescast.ui.today

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.toUnit
import app.clothescast.ui.theme.AppTheme
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

// Smallest y-axis span we'll display — same role as [ForecastChart]'s
// MIN_Y_SPAN. 4 picks step 1 via [niceStep], so a flat week reads as
// "…14, 15, 16" not "…14, 16" or worse a single-tick degenerate axis.
private const val SEVEN_DAY_MIN_Y_SPAN = 4.0

/**
 * Simple 7-day feels-like temperature line chart. Plots two lines —
 * daily high and daily low — across [days] (expected length ≤ 7). The
 * x-axis shows short weekday labels ("Mon", "Tue", …). Mirrors
 * [ForecastChart]'s Vico scaffolding (model producer + LineProvider +
 * pinned y-step) but without per-model overlays, scrub, or insight
 * coupling — this is the minimum chart for the "This week" pager page.
 *
 * Hides itself when [days] has fewer than two entries (a degenerate
 * chart isn't worth rendering).
 */
@Composable
fun SevenDayChart(
    days: List<DailyForecast>,
    temperatureUnit: TemperatureUnit,
    modifier: Modifier = Modifier,
) {
    if (days.size < 2) return

    val highColor = AppTheme.mainLineColor
    // Faded secondary tone for the low so the high stays the visual anchor;
    // 60% alpha on the same hue keeps both legible on either theme without
    // pulling in a new palette entry.
    val lowColor = highColor.copy(alpha = 0.6f)

    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(days, temperatureUnit) {
        producer.runTransaction {
            lineSeries {
                series(days.map { it.feelsLikeMaxC.toUnit(temperatureUnit) })
                series(days.map { it.feelsLikeMinC.toUnit(temperatureUnit) })
            }
        }
    }

    val bottomFormatter = remember(days) {
        CartesianValueFormatter { _, value, _ ->
            val idx = value.toInt().coerceIn(0, days.lastIndex)
            days[idx].date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        }
    }

    // Pin the y-axis to the full week's envelope (both highs and lows) so
    // both lines sit cleanly inside the chart and the axis labels line up
    // on integer ticks. Same approach as [ForecastChart] — see the comment
    // block there for the why.
    val yBounds = remember(days, temperatureUnit) {
        val all = days.flatMap {
            listOf(it.feelsLikeMinC.toUnit(temperatureUnit), it.feelsLikeMaxC.toUnit(temperatureUnit))
        }
        val rawMin = floor(all.min())
        val rawMax = ceil(all.max())
        val deficit = (SEVEN_DAY_MIN_Y_SPAN - (rawMax - rawMin)).coerceAtLeast(0.0)
        val padBelow = ceil(deficit / 2.0)
        val padAbove = floor(deficit / 2.0)
        alignToStep(rawMin - padBelow, rawMax + padAbove, niceStep((rawMax + padAbove) - (rawMin - padBelow)))
    }

    val rangeProvider = remember(yBounds) {
        object : CartesianLayerRangeProvider {
            override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) = yBounds.min
            override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) = yBounds.max
        }
    }
    val yItemPlacer = remember(yBounds.step) {
        VerticalAxis.ItemPlacer.step({ yBounds.step })
    }
    val startFormatter = remember {
        CartesianValueFormatter { _, value, _ -> value.roundToInt().toString() }
    }

    val lineProvider = rememberSevenDayLineProvider(highColor, lowColor)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp),
    ) {
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
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
            modifier = Modifier.matchParentSize(),
        )
    }
}

@Composable
private fun rememberSevenDayLineProvider(
    highColor: Color,
    lowColor: Color,
): LineCartesianLayer.LineProvider = remember(highColor, lowColor) {
    LineCartesianLayer.LineProvider.series(
        listOf(
            LineCartesianLayer.Line(fill = LineCartesianLayer.LineFill.single(fill(highColor))),
            LineCartesianLayer.Line(fill = LineCartesianLayer.LineFill.single(fill(lowColor))),
        ),
    )
}
