package app.clothescast.ui.today

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.PerModelHour
import app.clothescast.core.domain.model.PerModelHourly
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.toUnit
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

// Render order for the per-model overlays — fixed so the legend's color
// mapping stays stable across recompositions and renders. Internal so the
// adjacent [PrecipitationChart] and the legend in [TodayScreen] iterate the
// models in the same left-to-right order the chart draws them.
internal val MODEL_DRAW_ORDER = listOf("ecmwf_ifs04", "gfs_seamless", "icon_seamless")

// Pinned per-model overlay colours. Identity-based (keyed by model id), not
// position-based, so ECMWF stays pink even when GFS happens to be missing on
// a given run — Vico's default palette cycles by series index, which means a
// dropped model would otherwise shift every remaining model's colour. Mid-tone
// 600-weight hues chosen for legibility on both light and dark surfaces and
// for distinctness from the theme primary that the blended main line uses
// (currently a blue — see [app.clothescast.ui.theme]). Update the legend
// swatches in [TodayScreen.ModelSpreadLegend] if these change.
internal val MODEL_COLORS: Map<String, Color> = mapOf(
    "ecmwf_ifs04" to Color(0xFFD81B60),
    "gfs_seamless" to Color(0xFFFB8C00),
    "icon_seamless" to Color(0xFF43A047),
)

// Smallest y-axis span we'll display. A 1-degree-variation day padded to this
// still shows clear hour-to-hour movement, but the chart never collapses to a
// flat line nor exaggerates rounding noise into peaks. Units are the user's
// display unit, so the same 4 means "4°C" or "4°F" — neither extreme is
// unreasonable to fill the chart at minimum.
//
// Why 4 specifically: it's even, so when the deficit (MIN_Y_SPAN - actual span)
// is even the symmetric pad — `ceil(deficit/2)` below, `floor(deficit/2)`
// above — splits evenly. A constant-temperature day gets +2 / -2 around the
// reading, perfectly centred. With span 4, Vico's auto-stepper reliably picks
// step 1 (5 labels), which reads cleanly without leaving the line stuck in the
// middle 40% of the chart.
private const val MIN_Y_SPAN = 4.0

/**
 * Renders today's hourly temperature as a single line — feels-like or raw 2 m
 * air, controlled by [showFeelsLike]. Defaults to feels-like because that's
 * what the clothes rules and band sentence ("Today will be cool to mild")
 * are evaluated against; surfacing the raw line by default invited "which
 * line is which?" confusion. The parent toggles which series is shown.
 *
 * The hourly list is sourced from the cached Insight, populated by the morning
 * worker. When the cache predates this feature, [hourly] is empty and the chart
 * hides itself — the next worker run will fill it in.
 *
 * Underlying values are always Celsius (that's what HourlyForecast carries);
 * we convert at the edge with [toUnit] so the chart matches the user's
 * temperatureUnit preference. The legend, rendered by the parent, carries the
 * unit symbol — the axis stays unitless to avoid "10°C, 15°C, 20°C" repetition.
 */
@Composable
fun ForecastChart(
    hourly: List<HourlyForecast>,
    temperatureUnit: TemperatureUnit,
    showFeelsLike: Boolean,
    modifier: Modifier = Modifier,
    // Optional per-model overlays — passed by the caller when the user has
    // the "Show model spread" Display setting on. Drawn against whichever
    // series the parent has the toggle on for: apparent-temperature when
    // [showFeelsLike], raw 2 m air otherwise. The per-model entries carry
    // both, so the overlay stays semantically aligned with the main line in
    // either mode.
    perModelHourly: PerModelHourly? = null,
) {
    if (hourly.isEmpty()) return

    val overlays = perModelHourly?.byModel.orEmpty()
    val visibleModels = MODEL_DRAW_ORDER.filter { it in overlays }
    val mainLineColor = MaterialTheme.colorScheme.primary

    val pickModel: (PerModelHour) -> Double =
        if (showFeelsLike) { e -> e.apparentTemperatureC } else { e -> e.temperatureC }
    val pickHourly: (HourlyForecast) -> Double =
        if (showFeelsLike) { h -> h.feelsLikeC } else { h -> h.temperatureC }

    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(hourly, temperatureUnit, showFeelsLike, overlays) {
        producer.runTransaction {
            lineSeries {
                // Overlays first so they render *under* the main blended line.
                // Iterate in a fixed model order so the legend's color mapping
                // is stable across recompositions.
                visibleModels.forEach { modelId ->
                    overlays.getValue(modelId).let { entries ->
                        series(entries.map { pickModel(it).toUnit(temperatureUnit) })
                    }
                }
                series(hourly.map { pickHourly(it).toUnit(temperatureUnit) })
            }
        }
    }

    val bottomFormatter = remember(hourly) {
        CartesianValueFormatter { _, value, _ ->
            val idx = value.toInt().coerceIn(0, hourly.lastIndex)
            "%02d".format(hourly[idx].time.hour)
        }
    }

    // Vico's default rangeProvider clamps minY toward 0, so on a Fahrenheit day
    // with feels-like 52–62°F the axis spans 0–62 and the auto step-picker —
    // forced to fit that 62-unit range into ~3 label slots — lands on step 31,
    // giving a useless "0, 31, 62" axis. (Celsius hides this because 6–18 is
    // a smaller absolute range, so 0–18 with step 3 still looks fine.) Tighten
    // the y-range to both lines' actual min/max — both, not just the visible
    // line, so the axis doesn't shift when the user toggles between feels-like
    // and air. Floor / ceil to integers and enforce [MIN_Y_SPAN] in the user's
    // display unit so a calm day with a 1-degree variation doesn't get
    // amplified into a noisy zigzag — the data still fills more than half the
    // chart, but the labels stay on whole-degree gridlines.
    val rangeProvider = remember(hourly, temperatureUnit, overlays) {
        val main = hourly.flatMap {
            listOf(it.feelsLikeC.toUnit(temperatureUnit), it.temperatureC.toUnit(temperatureUnit))
        }
        val extras = overlays.values.flatMap { entries ->
            entries.flatMap {
                listOf(
                    it.apparentTemperatureC.toUnit(temperatureUnit),
                    it.temperatureC.toUnit(temperatureUnit),
                )
            }
        }
        val all = main + extras
        val rawMin = floor(all.min())
        val rawMax = ceil(all.max())
        // Symmetric integer pad: extra below = ceil(deficit/2), extra above =
        // floor(deficit/2). When deficit is even (the common case with an even
        // MIN_Y_SPAN — e.g., a constant-temperature day, deficit = 4) the
        // pad is exactly symmetric (+2 / -2). Odd deficits land slightly
        // bottom-heavy, which is fine — extra headroom below is less visually
        // disruptive than above, where it would push the line off the bottom.
        val deficit = (MIN_Y_SPAN - (rawMax - rawMin)).coerceAtLeast(0.0)
        val padBelow = ceil(deficit / 2.0)
        val padAbove = floor(deficit / 2.0)
        val dataMin = rawMin - padBelow
        val dataMax = rawMax + padAbove
        object : CartesianLayerRangeProvider {
            override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) = dataMin
            override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) = dataMax
        }
    }

    // Format y-axis labels as integers. With integer bounds and a 4-unit
    // minimum span Vico's auto-stepper lands on sensible whole-number steps
    // (1, 2) so we don't need to override the item placer — adjacent labels
    // never collapse onto the same rounded value.
    val startFormatter = remember {
        CartesianValueFormatter { _, value, _ -> value.roundToInt().toString() }
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
        // Vico's default initial zoom is `max(fixed, content)`, which on a 24-point
        // hourly series renders only the first ~10 hours and hides the rest behind
        // a scroll. Force-fit instead so the full day is visible at a glance — this
        // is a glanceable summary card, not an interactive explorer.
        zoomState = rememberVicoZoomState(initialZoom = Zoom.Content),
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp),
    )
}

// Builds a [LineCartesianLayer.LineProvider] whose Line list lines up with the
// series order both charts emit into their model producer: each entry in
// [visibleModels] (in MODEL_DRAW_ORDER) gets its pinned [MODEL_COLORS] hue,
// then — when [mainLineColor] is non-null — the blended main line gets that
// colour appended. Pass null for charts that have no main line (e.g. the wind
// diagnostic chart, where there's no single-model blended series to pair the
// overlays with). LineProvider.series matches lines to series by index, so
// this ordering must mirror the lineSeries builder in the caller.
@Composable
internal fun rememberPinnedLineProvider(
    visibleModels: List<String>,
    mainLineColor: Color?,
): LineCartesianLayer.LineProvider =
    remember(visibleModels, mainLineColor) {
        val perModelLines = visibleModels.map { modelId ->
            LineCartesianLayer.Line(
                fill = LineCartesianLayer.LineFill.single(
                    fill(MODEL_COLORS.getValue(modelId)),
                ),
            )
        }
        val mainLine = mainLineColor?.let {
            LineCartesianLayer.Line(fill = LineCartesianLayer.LineFill.single(fill(it)))
        }
        val lines = perModelLines + listOfNotNull(mainLine)
        LineCartesianLayer.LineProvider.series(lines)
    }
