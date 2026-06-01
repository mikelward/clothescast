package app.clothescast.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.remember
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.TimeFormat
import app.clothescast.ui.LocalTimeFormat
import app.clothescast.ui.today.ChartScrubController
import app.clothescast.ui.today.ForecastCard
import app.clothescast.ui.today.LocalChartBottomFormatter
import app.clothescast.ui.today.LocalChartBottomItemPlacer
import app.clothescast.ui.today.LocalChartScrub
import app.clothescast.ui.today.LocalScrubMomentFormat
import app.clothescast.ui.today.ScrubMomentFormat
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * The in-app feels-like chart card ([ForecastCard]), reused verbatim for the
 * home-screen widgets — same `ForecastChart`, same "Feels like temperature"
 * title, min–max subhead and current-time readout — with the legend dropped
 * (`showLegend = false`). Rendering the *real* composable rather than a
 * hand-drawn lookalike is what keeps the widget's colours, font sizes, tick
 * spacing and line shape identical to the screen (the whole reason a replica
 * kept reading as "off-brand").
 *
 * Two render paths, one composable:
 *  - **Snapshot tests** drive it through `PreviewSnapshots`. `WidgetFrame`
 *    provides `LocalInspectionMode = true`, so Vico's producer runs on its
 *    synchronous in-inspection dispatcher and the chart is fully drawn by
 *    the time `captureUntilStable` rasterises — no extra timing dance.
 *  - **Runtime** rasterises it to a bitmap via [renderComposableToBitmap] for
 *    the Glance widget (Glance can't host Compose/Vico directly).
 *
 * The caller supplies the theme ([app.clothescast.ui.theme.ClothesCastTheme]):
 * the runtime widget uses the user's palette + dark mode so it matches the app;
 * previews pin `dynamicColor = false` for deterministic captures.
 *
 * @param hourly the series to plot — the period slice for the per-period widget,
 *   or the flattened 168-hour week for the 7-day widget.
 * @param days non-null only for the 7-day widget; drives the day-of-week x-axis
 *   (the same noon-tick scheme `SevenDayPage` uses). Null = hour-of-day axis.
 * @param now current time in the forecast zone (drives the current-time line +
 *   readout), or null when "now" is outside the window.
 */
@Composable
internal fun WidgetForecastChart(
    hourly: List<HourlyForecast>,
    days: List<DailyForecast>?,
    temperatureUnit: TemperatureUnit,
    timeFormat: TimeFormat,
    startDate: LocalDate,
    now: LocalDateTime?,
    // When true the chart fills the height it's given (the widget bitmap is sized
    // to the cell). Defaults false so the snapshot previews keep wrapping the
    // fixed-height card.
    fillHeight: Boolean = false,
) {
    val controller = remember(now) {
        ChartScrubController().apply { now?.let { setNow(it) } }
    }
    val providers = buildList {
        add(LocalTimeFormat provides timeFormat)
        add(LocalChartScrub provides controller)
        if (days != null && days.size >= 2) {
            add(LocalScrubMomentFormat provides ScrubMomentFormat.DayPlusHour)
            add(LocalChartBottomFormatter provides dayOfWeekFormatter(days))
            add(LocalChartBottomItemPlacer provides dayItemPlacer())
        }
    }
    // Provide the M3 Vico theme explicitly (derived from the MaterialTheme the
    // caller wraps us in). The in-app screen relies on Vico's default theme
    // resolution, which the off-screen widget render context doesn't reproduce
    // — without this the axis labels came out dark-on-dark in dark mode. Pinning
    // it here makes the colours deterministic and correct in both render paths.
    CompositionLocalProvider(*providers.toTypedArray<ProvidedValue<*>>()) {
        ProvideVicoTheme(rememberM3VicoTheme()) {
            ForecastCard(
                hourly = hourly,
                temperatureUnit = temperatureUnit,
                startDate = startDate,
                showHeader = false,
                showLegend = false,
                fillHeight = fillHeight,
            )
        }
    }
}

// Day-of-week label at each day's noon tick — mirrors SevenDayPage so the 7-day
// widget's x-axis reads "Mon Tue …" instead of the same 00..23 repeated seven
// times. Vico hands the formatter values at idx = noon + 24·day.
private fun dayOfWeekFormatter(days: List<DailyForecast>): CartesianValueFormatter {
    val labels = days.map { it.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    return CartesianValueFormatter { _, value, _ ->
        val day = ((value.toInt() - 12) / 24).coerceIn(0, labels.lastIndex)
        labels[day]
    }
}

// One tick per day, anchored at noon (idx 12, 36, 60, …) — same placer SevenDayPage uses.
private fun dayItemPlacer(): HorizontalAxis.ItemPlacer =
    HorizontalAxis.ItemPlacer.aligned(spacing = { 24 }, offset = { 12 })
