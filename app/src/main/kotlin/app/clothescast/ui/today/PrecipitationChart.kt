package app.clothescast.ui.today

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
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
    // Date of `hourly[0]` — needed by the shared scrub indicator. See
    // [ForecastChart] for the same parameter.
    startDate: LocalDate = LocalDate.now(),
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
    // A model whose precip series is all-null (Open-Meteo doesn't expose
    // `precipitation_probability_<model>` for it) has nothing to plot on
    // this chart — skip it so we don't draw an empty Vico series and the
    // legend doesn't list a phantom line. The model still appears on the
    // temperature chart via [ForecastChart], which only needs air-temp.
    val visibleModels = if (showModelSpread) {
        MODEL_DRAW_ORDER.filter { modelId ->
            overlays[modelId]?.any { it.precipitationProbabilityPct != null } == true
        }
    } else {
        emptyList()
    }
    val mainLineColor = AppTheme.mainLineColor

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
                        // Build (x, y) pairs so a sparse precip series plots
                        // at its real hour positions on the chart, mirroring
                        // [PerModelDiagnosticCard]. A bare `series(y)` would
                        // compact surviving y values to indices 0..n which
                        // misaligns the line under the bottom axis when a
                        // model omits its leading or trailing hours. The
                        // x index is the entry's position in the parser's
                        // hourly list — entries.size == hours-in-window
                        // because the parser only drops hours when
                        // temperature_2m itself is null, so iteration index
                        // equals hour-since-window-start. Nulls inside the
                        // precip series are skipped via mapIndexedNotNull;
                        // Vico bridges the gap visually between surviving
                        // points without drawing a fake-0 baseline.
                        val points = entries.mapIndexedNotNull { i, e ->
                            e.precipitationProbabilityPct?.let { i to it }
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

    // Y: fixed 0..100 — probability is a percentage, so a calm day with a
    // few-percent peak should still show a flat baseline near the bottom of
    // the chart rather than autoscaling to amplify the noise. Keeps the chart
    // visually comparable across days too: a 20% line on a quiet day and a 20%
    // line on a wet day land at the same height.
    //
    // X: pinned to the full hourly window so a sparse per-model precip series
    // that's missing the leading or trailing hours doesn't get stretched across
    // the whole card by Zoom.Content. Without this anchor, if every visible
    // model is missing (say) hours 0..18 of the day, the chart only sees
    // x = 19..23 and fits those four points to the full width, visually
    // misaligning the line with the temperature card's 0..23 axis and hiding
    // the gap that's the whole point of plotting at original indices.
    val xMax = hourly.lastIndex.toDouble()
    val rangeProvider = remember(xMax) {
        object : CartesianLayerRangeProvider {
            override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) = 0.0
            override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) = 100.0
            override fun getMinX(minX: Double, maxX: Double, extraStore: ExtraStore) = 0.0
            override fun getMaxX(minX: Double, maxX: Double, extraStore: ExtraStore) = xMax
        }
    }

    val startFormatter = remember {
        CartesianValueFormatter { _, value, _ -> "${value.roundToInt()}%" }
    }

    val lineProvider = rememberPinnedLineProvider(visibleModels, mainLineColor)

    val scrubController = LocalChartScrub.current
    val scrubBounds = rememberChartScrubBounds()
    val scrubIndicator = rememberChartScrubIndicator(scrubController, scrubBounds, hourly, startDate)
    val decorations = listOf(scrubIndicator)

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Shorter than ForecastCard's 180.dp temperature chart — probability
            // is bounded 0–100 and doesn't need as much vertical room for the
            // line to be readable.
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
                startAxis = VerticalAxis.rememberStart(valueFormatter = startFormatter),
                bottomAxis = LocalChartBottomItemPlacer.current?.let { placer ->
                    HorizontalAxis.rememberBottom(itemPlacer = placer, valueFormatter = bottomFormatter)
                } ?: HorizontalAxis.rememberBottom(valueFormatter = bottomFormatter),
                decorations = decorations,
            ),
            modelProducer = producer,
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
            // No entry animation — appears at final shape on compose, snappier
            // on page swipes. Diff animation (animationSpec) kept. See
            // ForecastChart for the rationale.
            animateIn = false,
            modifier = Modifier.matchParentSize(),
        )
    }
}
