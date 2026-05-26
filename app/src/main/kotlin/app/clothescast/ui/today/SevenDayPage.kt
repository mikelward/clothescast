package app.clothescast.ui.today

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.clothescast.R
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.DistanceUnit
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.PerModelHourly
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.windSpeedUnit
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Page 3 of the Today pager — a 7-day chart deck.
 *
 * Renders the same chart stack the per-period pages use (forecast / air
 * temp / precipitation probability / amount, plus the wind / humidity /
 * cloud / solar / UV / sunshine diagnostic cards), but fed a flat 168-hour
 * list of hourly samples covering today + the next six days, with a
 * day-of-week x-axis (`Mon Tue …`) instead of the per-period hour-of-day
 * ticks. The bottom-axis swap rides on [LocalChartBottomFormatter] so the
 * underlying chart composables stay byte-identical on the per-period
 * pages.
 *
 * The diagnostic cards (wind onward) and the primary cards' model-spread
 * overlays auto-hide when [weekPerModelHourly] is null OR when the
 * supplied series doesn't yet cover every date in [days] — i.e. on
 * legacy cached payloads from before the multi-model fetcher widened to
 * `forecast_days=7`, on upgraded-but-stale-cache users mid-refresh, or
 * when the side-band fetch failed. The page still renders the four
 * primary-data charts on the cheap path.
 *
 * No scrub controller is provided here, so chart readouts and the scrub
 * indicator stay off on this page — scrub is hourly-on-a-day semantics
 * that don't translate cleanly to a week.
 *
 * Empty / short [days] (e.g. legacy cached snapshots from before the
 * 7-day fetch) collapse to a short stand-in message so the page still
 * has a back button instead of going blank.
 */
@Composable
internal fun SevenDayPage(
    days: List<DailyForecast>,
    temperatureUnit: TemperatureUnit,
    distanceUnit: DistanceUnit,
    weekPerModelHourly: PerModelHourly?,
    showModelSpread: Boolean,
    scrollState: ScrollState,
    onChevronTap: () -> Unit,
) {
    // Flatten every day's hourly stream into a single list. The chart
    // composables read [hourly[idx].time.hour] only for the bottom-axis
    // default formatter (which we override below) and for the scrub
    // readout (which is disabled on this page), so a 168-entry list of
    // LocalTime-keyed entries is sufficient — no LocalDateTime reshape
    // needed.
    val flatHourly: List<HourlyForecast> = remember(days) { days.flatMap { it.hourly } }
    val startDate = days.firstOrNull()?.date

    // Bottom-axis label per day: short day-of-week at the noon tick.
    // Paired with [itemPlacer] below, which restricts Vico to placing
    // ticks at multiples of 24 — Vico rejects empty strings from a
    // formatter, so suppression has to come from the placer instead.
    val dayOfWeekFormatter: CartesianValueFormatter? = remember(days) {
        if (days.isEmpty()) return@remember null
        val locale = Locale.getDefault()
        val labels = days.map { it.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) }
        CartesianValueFormatter { _, value, _ ->
            // The placer hands us values at idx = noon + 24·day, so map
            // back to the day index and look up the label.
            val day = ((value.toInt() - 12) / 24).coerceIn(0, labels.lastIndex)
            labels[day]
        }
    }
    // Place one tick per day, anchored at noon (idx = 12, 36, 60, ...).
    // Uniform 24-hour spacing assumes every day contributes exactly 24
    // hourly entries; around a DST transition the affected day is 23 or
    // 25 hours, so the label's pixel position drifts by ≤1 hour after
    // that day. That stays well within a 24-hour-wide day region, so the
    // weekday name printed at each tick still matches the day region it
    // sits in — only the visual nudge is slightly off-noon. A full
    // variable-position placer requires subclassing
    // [BaseHorizontalAxisItemPlacer] (5+ method overrides for margin /
    // measurement); not worth the engineering for a 1-hour cosmetic
    // shift on the ~10 days/year a 7-day window straddles a DST
    // transition. Revisit if the visual drift ever feels meaningful.
    val dayItemPlacer: HorizontalAxis.ItemPlacer? = remember(days) {
        if (days.size < 2) null else HorizontalAxis.ItemPlacer.aligned(spacing = { 24 }, offset = { 12 })
    }
    // Newly-fetched 7-day series carries ~168 hourly entries per model
    // spanning every date in [days]. Older cached payloads (from before the
    // forecast_days=7 bump) carry only ~48 hours covering 2 dates. Without
    // this gate, an upgraded user whose cache hasn't refreshed yet sees the
    // 7-day primary charts (already widened) plus diagnostic cards whose
    // per-model lines stop short at day 2 — which reads as "the models
    // gave up" rather than "the cache is stale." Counting distinct
    // [LocalDate]s across every model lets the gate hold whether the
    // series is sparse-but-week-covering or dense-but-2-day-covering.
    val weekPerModelDiagnostics: PerModelHourly? = remember(weekPerModelHourly, days) {
        weekPerModelHourly?.takeIf { perModel ->
            val expected = days.map { it.date }.toSet()
            val covered = perModel.byModel.values.asSequence()
                .flatten()
                .map { it.time.toLocalDate() }
                .toSet()
            expected.all { it in covered }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header card with the back chevron + page title. Kept separate
        // from the chart stack so the chevron sits at the top of the
        // viewport regardless of how many charts render below.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onChevronTap,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.today_back_to_primary),
                        )
                    }
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.today_week_card_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (days.size < 2) {
                    Text(
                        text = stringResource(R.string.today_week_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (days.size < 2 || flatHourly.isEmpty() || startDate == null) return@Column

        // The full chart stack mirrors what TodayPage renders for a
        // per-period view. Each card reads [LocalChartBottomFormatter] for
        // its x-axis labels; here we provide a day-of-week formatter so
        // 168 hours don't render as the same 00..23 axis repeated seven
        // times. Scrub controller is intentionally not provided so the
        // scrub indicator + readout stay off — scrub is hourly-on-a-day
        // semantics that don't translate cleanly to a week.
        CompositionLocalProvider(
            LocalChartBottomFormatter provides dayOfWeekFormatter,
            LocalChartBottomItemPlacer provides dayItemPlacer,
        ) {
            ForecastCard(
                hourly = flatHourly,
                temperatureUnit = temperatureUnit,
                distanceUnit = distanceUnit,
                startDate = startDate,
                perModelHourly = weekPerModelDiagnostics,
                showModelSpread = showModelSpread,
            )
            AirTemperatureCard(
                hourly = flatHourly,
                temperatureUnit = temperatureUnit,
                startDate = startDate,
                perModelHourly = weekPerModelDiagnostics,
                showModelSpread = showModelSpread,
            )
            PrecipitationCard(
                hourly = flatHourly,
                startDate = startDate,
                perModelHourly = weekPerModelDiagnostics,
                showModelSpread = showModelSpread,
            )
            PrecipitationAmountCard(
                hourly = flatHourly,
                forDate = startDate,
                // Period is used by this card only to pick the subtitle
                // template ("X mm today" vs "tonight"). For a week-wide
                // view neither template is quite right; pass TODAY so the
                // copy at least reads naturally for the steady-state.
                // Polish: a dedicated weekly subtitle is a follow-up.
                period = ForecastPeriod.TODAY,
                perModelHourly = weekPerModelDiagnostics,
                showModelSpread = showModelSpread,
            )
            weekPerModelDiagnostics?.let { perModelData ->
                WindCard(
                    hourly = flatHourly,
                    perModelHourly = perModelData,
                    windSpeedUnit = distanceUnit.windSpeedUnit(),
                    startDate = startDate,
                    showModelSpread = showModelSpread,
                )
                HumidityCard(
                    hourly = flatHourly,
                    perModelHourly = perModelData,
                    startDate = startDate,
                    showModelSpread = showModelSpread,
                )
                CloudCard(
                    hourly = flatHourly,
                    perModelHourly = perModelData,
                    startDate = startDate,
                    showModelSpread = showModelSpread,
                )
                SolarRadiationCard(
                    hourly = flatHourly,
                    perModelHourly = perModelData,
                    startDate = startDate,
                    showModelSpread = showModelSpread,
                )
                UvIndexCard(
                    hourly = flatHourly,
                    perModelHourly = perModelData,
                    startDate = startDate,
                    showModelSpread = showModelSpread,
                )
                SunshineCard(
                    hourly = flatHourly,
                    perModelHourly = perModelData,
                    forDate = startDate,
                    // Same caveat as PrecipitationAmountCard above — the
                    // subtitle reads "X h of sun today" rather than "X h
                    // this week"; the chart itself is the value here.
                    period = ForecastPeriod.TODAY,
                    showModelSpread = showModelSpread,
                )
            }
        }
    }
}
