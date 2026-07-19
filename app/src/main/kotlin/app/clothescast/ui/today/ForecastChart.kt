package app.clothescast.ui.today

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.clothescast.core.domain.model.ForecastModel
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.PerModelHour
import app.clothescast.core.domain.model.PerModelHourly
import app.clothescast.core.domain.model.PerModelHourly.Companion.BEST_MATCH_MODEL_ID
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.toUnit
import app.clothescast.ui.theme.AppTheme
import java.time.LocalDate
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

// Render order for the per-model overlays — fixed so the legend's color
// mapping stays stable across recompositions and renders. Internal so the
// adjacent [PrecipitationChart] and the legend in [TodayScreen] iterate the
// models in the same left-to-right order the chart draws them. The
// best_match overlay sits *first* among the per-model series so it draws
// underneath the consulted models — paired with a thicker stroke in
// [rememberPinnedLineProvider] it reads as a reference baseline that the
// thinner consulted-model lines sit on top of. The blended "Combined" main
// line is emitted *before* these overlays (series index 0) so it keeps
// the same index — and the same legend position — whether or not the
// per-model spread is showing; that pins Vico's identity-by-index
// animation so the combined line no longer fades out and back in when
// the overlay toggles. The trade-off is that the per-model overlays now
// draw over the combined line in the spread view.
//
// Derived from [ForecastModel] so adding a new entry in the domain enum
// automatically widens the chart without an additional edit here — and so
// every model the user can pick in Settings ▸ Forecasters has a stable
// position. Enum declaration order doubles as the chart's left-to-right
// legend order; the [AppPalette.modelColors] maps follow the same order.
internal val MODEL_DRAW_ORDER: List<String> =
    listOf(BEST_MATCH_MODEL_ID) + ForecastModel.entries.map { it.openMeteoId }

// Pinned per-model overlay colours live on the active [app.clothescast.ui.theme.AppPalette]
// rather than as a static map here, so the colour-blind palette can swap the
// trio without each chart needing its own branch. Lookup is identity-based
// (keyed by model id) so ECMWF stays its pinned hue even when GFS happens to
// be missing on a given run — Vico's default palette cycles by series index,
// which means a dropped model would otherwise shift every remaining model's
// colour. Mid-tone hues are picked in `AppPalette.kt` for legibility on both
// light and dark surfaces and for distinctness from the theme primary that
// the blended main line uses (currently a blue — see [app.clothescast.ui.theme]).
// The best_match overlay gets a neutral grey: it's a side comparison rather
// than a consulted model, and pairing it with a vibrant hue would imply
// equivalence with the real models on the chart.

// Smallest y-axis span we'll display. A 1-degree-variation day padded to this
// still shows clear hour-to-hour movement, but the chart never collapses to a
// flat line nor exaggerates rounding noise into peaks. Units are the user's
// display unit, so the same 4 means "4°C" or "4°F" — neither extreme is
// unreasonable to fill the chart at minimum.
//
// Why 4 specifically: it's even, so when the deficit (MIN_Y_SPAN - actual span)
// is even the symmetric pad — `ceil(deficit/2)` below, `floor(deficit/2)`
// above — splits evenly. A constant-temperature day gets +2 / -2 around the
// reading, perfectly centred. It also pairs cleanly with [niceStep]: span 4
// picks step 1, yielding 5 labels — readable without the line stuck in the
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
 *
 * When [perModelHourly] is supplied, the y-axis is always sized to the full
 * per-model envelope — even when [showModelSpread] is off and only the main
 * line is drawn — so tapping the card to toggle the overlay adds or removes
 * lines without shifting the axis labels underneath the data. Matches the
 * [PerModelDiagnosticCard] pattern.
 */
@Composable
fun ForecastChart(
    hourly: List<HourlyForecast>,
    temperatureUnit: TemperatureUnit,
    showFeelsLike: Boolean,
    // Date of `hourly[0]` — needed by the shared scrub indicator to map
    // between wall-clock time and chart-x across the tonight midnight
    // wrap. Falls back to today's date for previews / non-screen callers
    // that don't drive the indicator from a real insight.
    startDate: LocalDate = LocalDate.now(),
    modifier: Modifier = Modifier,
    // Optional per-model data. When non-null, used to size the y-axis to the
    // full envelope regardless of [showModelSpread] so the toggle only adds /
    // removes lines, never shifts the scale. Drawn against whichever series
    // the parent has the toggle on for: apparent-temperature when
    // [showFeelsLike], raw 2 m air otherwise. The per-model entries carry
    // both, so the overlay stays semantically aligned with the main line in
    // either mode.
    perModelHourly: PerModelHourly? = null,
    // When true, draw the individual model lines underneath the main line.
    // When false, only the main consensus line is rendered. Y-axis range is
    // unchanged in either case (see [perModelHourly]).
    showModelSpread: Boolean = false,
    // In-app the chart is a fixed 180.dp tall inside a scrolling column. The
    // home-screen widget instead rasterises into a bitmap sized to the cell, so
    // it asks the chart to fill the height it's given (via a weighted [modifier])
    // — letting the chart scale with the available space rather than pinning a
    // height that would letterbox or clip inside the cell.
    fillHeight: Boolean = false,
) {
    if (hourly.isEmpty()) return

    // Timestamp → list-position map for the chart's hourly window, shared by
    // the per-model overlays and the range band so every per-model value plots
    // at the x position the main line plots that wall-clock hour at — robust
    // to a model dropping an hour and to DST weeks where position ≠ naive
    // hour offset. See [hourlyTimestampIndices].
    val indexByTime = remember(hourly, startDate) { hourlyTimestampIndices(hourly, startDate) }
    val overlays = perModelHourly?.byModel.orEmpty()
    // Only models with at least one point inside this chart's window.
    // [rememberPinnedLineProvider] assigns line colors by position in
    // this list against the series emitted below, so the two must stay
    // in lockstep — emitting fewer series than entries here would shift
    // every later model onto the wrong color. Shared with the cards'
    // legends via [visibleSpreadModelIds] so the legend lists exactly
    // the plotted models.
    val visibleModels = if (showModelSpread) {
        visibleSpreadModelIds(perModelHourly, indexByTime)
    } else {
        emptyList()
    }
    val mainLineColor = AppTheme.mainLineColor

    val pickModel: (PerModelHour) -> Double =
        if (showFeelsLike) { e -> e.apparentTemperatureC } else { e -> e.temperatureC }
    val pickHourly: (HourlyForecast) -> Double =
        if (showFeelsLike) { h -> h.feelsLikeC } else { h -> h.temperatureC }

    val producer = remember { CartesianChartModelProducer() }
    // Key on [overlays] (the underlying map) rather than [visibleModels] (just
    // the ID list) so a refresh that updates per-model values while keeping
    // the same model IDs still re-emits the series. [showModelSpread] is
    // included separately so flipping the toggle on/off re-fires the effect
    // even when [overlays] hasn't changed.
    LaunchedEffect(hourly, temperatureUnit, showFeelsLike, overlays, showModelSpread, indexByTime) {
        producer.runTransaction {
            lineModel {
                // Main blended line first so it occupies series index 0 in
                // both single and per-model views — keeps Vico's identity-
                // by-index animation stable when the spread toggles, so the
                // combined line no longer fades out and back in. Per-model
                // overlays follow in MODEL_DRAW_ORDER (and end up drawn on
                // top of the combined line); empty when [showModelSpread] is
                // off, in which case only the main line is emitted.
                series(hourly.map { pickHourly(it).toUnit(temperatureUnit) })
                visibleModels.forEach { modelId ->
                    overlays.getValue(modelId).let { entries ->
                        // Plot each per-model point at the window position of
                        // its timestamp (explicit x) rather than letting Vico
                        // assign x by list position — a model that dropped an
                        // hour would otherwise draw its tail an hour early,
                        // disagreeing with the (timestamp-keyed) range band
                        // and the bottom axis. Entries whose timestamps fall
                        // outside the window are skipped; in the common case
                        // every entry maps to its old position, so the
                        // rendered line is unchanged.
                        val points = entries.mapNotNull { e ->
                            indexByTime[e.time]?.let { it to pickModel(e).toUnit(temperatureUnit) }
                        }
                        // Never empty: [visibleModels] is pre-filtered to
                        // models with ≥1 in-window point, which keeps the
                        // emitted series count equal to the line provider's
                        // color list (and Vico rejects an empty series).
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

    // Vico's default rangeProvider clamps minY toward 0, so on a Fahrenheit day
    // with feels-like 52–62°F the axis spans 0–62 and the auto step-picker —
    // forced to fit that 62-unit range into ~3 label slots — lands on step 31,
    // giving a useless "0, 31, 62" axis. Tighten the y-range to both lines'
    // actual min/max — both, not just the visible line, so the axis doesn't
    // shift when the user toggles between feels-like and air. Floor / ceil to
    // integers and enforce [MIN_Y_SPAN] in the user's display unit so a calm
    // day with a 1-degree variation doesn't get amplified into a noisy zigzag.
    // Then snap to [niceStep] multiples and pin the placer to that step, so a
    // 10.3°C peak reads as "…10, 12" not "…12, 14".
    val yBounds = remember(hourly, temperatureUnit, overlays) {
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
        // floor(deficit/2). Odd deficits land slightly bottom-heavy, which is
        // fine — extra headroom below is less visually disruptive than above.
        val deficit = (MIN_Y_SPAN - (rawMax - rawMin)).coerceAtLeast(0.0)
        val padBelow = ceil(deficit / 2.0)
        val padAbove = floor(deficit / 2.0)
        val paddedMin = rawMin - padBelow
        val paddedMax = rawMax + padAbove
        alignToStep(paddedMin, paddedMax, niceStep(paddedMax - paddedMin))
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

    val modelColors = AppTheme.palette.modelColors
    val lineProvider = rememberPinnedLineProvider(visibleModels, mainLineColor, modelColors)
    val axisLabel = rememberOnSurfaceAxisLabel()

    val scrubController = LocalChartScrub.current
    val scrubBounds = rememberChartScrubBounds()
    val scrubIndicator = rememberChartScrubIndicator(scrubController, scrubBounds, hourly, startDate)
    // Shaded min–max range band on the default view (hidden once the per-model
    // overlay is on — the individual lines convey the spread then). Built from
    // the same picker + unit as the main line so it lands on the same scale.
    val (bandMin, bandMax) = remember(overlays, showFeelsLike, temperatureUnit, indexByTime) {
        if (perModelHourly == null) {
            emptyList<Pair<Int, Double>>() to emptyList()
        } else {
            perModelEnvelope(perModelHourly, indexByTime) {
                pickModel(it).toUnit(temperatureUnit)
            }
        }
    }
    val rangeBand = rememberRangeBandDecoration(
        minSeries = bandMin,
        maxSeries = bandMax,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = RANGE_BAND_ALPHA),
        visible = !showModelSpread,
    )
    val decorations = listOf(rangeBand, scrubIndicator)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .let { if (fillHeight) it.fillMaxHeight() else it.height(180.dp) }
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
                startAxis = VerticalAxis.rememberStart(
                    label = axisLabel,
                    itemPlacer = yItemPlacer,
                    valueFormatter = startFormatter,
                ),
                bottomAxis = LocalChartBottomItemPlacer.current?.let { placer ->
                    HorizontalAxis.rememberBottom(
                        label = axisLabel,
                        itemPlacer = placer,
                        valueFormatter = bottomFormatter,
                    )
                } ?: HorizontalAxis.rememberBottom(label = axisLabel, valueFormatter = bottomFormatter),
                decorations = decorations,
            ),
            modelProducer = producer,
            // Vico's default initial zoom is `max(fixed, content)`, which on a 24-point
            // hourly series renders only the first ~10 hours and hides the rest behind
            // a scroll. Force-fit instead so the full day is visible at a glance — this
            // is a glanceable summary card, not an interactive explorer.
            //
            // Disable scroll and zoom gestures: the chart fits, so there's nothing
            // to scroll or zoom — and Vico's default gesture handlers would otherwise
            // swallow horizontal drags. The scrub gesture lives on the parent Box.
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
            animateIn = CHART_ANIMATE_IN,
            animationSpec = CHART_ANIMATION_SPEC,
            modifier = Modifier.matchParentSize(),
        )
    }
}

// Axis tick labels (the 00/03/06… hours and 0%/20%… values) in the theme's
// onSurface color. Vico defaults axis labels to a fixed ~54%-opacity black,
// which it doesn't derive from the Material color scheme: readable on the
// light card, but low-contrast on the dark one. onSurface follows the theme
// and matches the card's own title/value text. Shared by every forecast
// chart's start and bottom axes — all in this package, so internal.
@Composable
internal fun rememberOnSurfaceAxisLabel() =
    // Passing `style` replaces Vico's whole default axis-label TextStyle, not
    // just the color — so re-state its default size (Defaults.AXIS_LABEL_SIZE =
    // 12sp) or the labels fall back to Compose's larger default and shrink the
    // plot. Color is the only thing we mean to change (see the note above).
    rememberAxisLabelComponent(
        style = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp),
    )

// Builds a [LineCartesianLayer.LineProvider] whose Line list lines up with the
// series order both charts emit into their model producer: when [mainLineColor]
// is non-null the blended main line comes first (matching the index-0 series
// the callers emit), then each entry in [visibleModels] (in MODEL_DRAW_ORDER)
// gets its pinned hue from [modelColors]. Pass null for charts that have no
// main line (e.g. the wind diagnostic chart, where there's no single-model
// blended series to pair the overlays with) — in that case only the per-model
// lines are returned and they start at index 0. LineProvider.series matches
// lines to series by index, so this ordering must mirror the lineModel
// builder in the caller.
@Composable
internal fun rememberPinnedLineProvider(
    visibleModels: List<String>,
    mainLineColor: Color?,
    modelColors: Map<String, Color> = AppTheme.palette.modelColors,
): LineCartesianLayer.LineProvider {
    // Key the remember on the colours actually used (mainline + per-visible-model)
    // rather than the whole [modelColors] map. The map widened to cover all eight
    // [ForecastModel] entries when the Forecasters picker landed, so its identity
    // changed; without this projection, every chart in the tree would invalidate
    // its LineProvider on first composition after the upgrade — re-running Vico's
    // line setup yields visually identical output but flickers any in-flight
    // line-fade animation and rewrites Roborazzi snapshots even though the
    // visible lines are the same. Including only the visible colours keeps the
    // key stable when the underlying map gains entries we don't read.
    val visibleColors = visibleModels.map { modelColors.getValue(it) }
    return remember(visibleModels, mainLineColor, visibleColors) {
        val mainLine = mainLineColor?.let {
            LineCartesianLayer.Line(fill = LineCartesianLayer.LineFill.single(Fill(it)))
        }
        // best_match draws at 4 dp so it reads as a reference baseline
        // through the thinner 2-dp consulted-model lines on top of it.
        // Combined and the consulted models keep Vico's default 2 dp.
        val perModelLines = visibleModels.map { modelId ->
            val color = modelColors.getValue(modelId)
            val stroke = if (modelId == BEST_MATCH_MODEL_ID) {
                LineCartesianLayer.LineStroke.Continuous(thickness = 4.dp)
            } else {
                LineCartesianLayer.LineStroke.Continuous()
            }
            LineCartesianLayer.Line(
                fill = LineCartesianLayer.LineFill.single(Fill(color)),
                stroke = stroke,
            )
        }
        val lines = listOfNotNull(mainLine) + perModelLines
        LineCartesianLayer.LineProvider.series(lines)
    }
}
