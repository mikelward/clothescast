package app.clothescast.ui.today

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.clothescast.R
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.DistanceUnit
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.PerModelHourly
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.windSpeedUnit
import app.clothescast.core.domain.usecase.DeriveWeekAheadInsight
import app.clothescast.insight.InsightFormatter
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay

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
 * A [ChartScrubController] is wired here so taps on any chart publish a
 * shared indicator across the stack — same gesture / readout behaviour as
 * the per-period pages, with the readout's time portion prefixed with a
 * short day-of-week label (`Wed 2pm`) via [LocalScrubMomentFormat] so a
 * reading isn't ambiguous between the seven identical hour-of-day points
 * in the window. A [SpreadCoordinator] is wired into the controller too,
 * matching the per-period pages: a tap on the chart plot enters scrub
 * mode *and* auto-reveals the per-model spread (with the matching restore
 * tap auto-hiding it again). The tap-hint card above the chart stack
 * plays the role the confidence chip plays on the per-period pages — an
 * explicit on/off toggle, labelled "Tap to see / hide each model's
 * forecast" (see [onToggleModelSpread]).
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
    onToggleModelSpread: () -> Unit = {},
    onRevealModelSpread: () -> Unit = {},
    onHideModelSpread: () -> Unit = {},
    forecastZone: ZoneId? = null,
    region: Region = Region.SYSTEM,
    /**
     * Mirrors `TodayState.deltaThresholdC` — the feels-like swing (°C) the
     * week-ahead temperature-shift rule must clear before emitting "X°
     * cooler/warmer …". `null` disables the rule (the user's "Temperature
     * change: Off" setting), keeping the headline in sync with the today /
     * tonight delta clause that already honours the same preference.
     *
     * TODO: revisit whether the weekly headline should follow the today /
     * tonight `Temperature change` setting at all. Sharing the pref keeps
     * the two surfaces consistent for the "I don't want temperature noise"
     * user, but the 7-day page may be exactly where that user does want a
     * cooler-than-today signal (it's the only place such a signal can come
     * from on a future day). If we end up wanting an independent gate, the
     * cleanest move is a separate `weeklyDeltaThresholdC` pref + state
     * field, plumbed through here in place of this one.
     */
    deltaThresholdC: Double? = 3.0,
    /**
     * The forecast location, shown next to the "Next 7 days" header label
     * (and tappable to open Location settings), matching the per-period
     * [InsightCard]'s header. Null on legacy cached payloads / previews
     * without a location, in which case the header renders the label alone.
     */
    location: Location? = null,
    /**
     * Opens the Location settings page. Wired to the location label in the
     * card's header — same role as [InsightCard]'s `onNavigateToLocation`.
     * Null keeps the label non-tappable (used by previews / tests).
     */
    onNavigateToLocation: (() -> Unit)? = null,
    /**
     * The current-period insight whose outfit pair is rendered at the top of
     * the page. Same row the per-period pages show; surfacing it here too
     * keeps the outfit pair pinned to the same vertical offset on all three
     * pager pages, so swiping between Today / Tonight / 7-day doesn't jump
     * the rest of the content up or down. Null on legacy previews / tests
     * that don't wire an outfit pair, in which case the row collapses.
     */
    outfitInsight: Insight? = null,
    clothesRules: List<ClothesRule> = emptyList(),
    outfitTopColors: Map<OutfitSuggestion.Top, Long> = emptyMap(),
    outfitBottomColors: Map<OutfitSuggestion.Bottom, Long> = emptyMap(),
    outfitTopStrokes: Map<OutfitSuggestion.Top, Long> = emptyMap(),
    outfitBottomStrokes: Map<OutfitSuggestion.Bottom, Long> = emptyMap(),
    onAdjustThreshold: (String, Double) -> Unit = { _, _ -> },
    onNavigateToClothes: () -> Unit = {},
) {
    // Flatten every day's hourly stream into a single list. The chart
    // composables read [hourly[idx].time.hour] only for the bottom-axis
    // default formatter (which we override below); the scrub readout
    // gets its date from the controller's active [LocalDateTime] rather
    // than reconstructing it from this list, so 168 LocalTime-keyed
    // entries are sufficient — no LocalDateTime reshape needed here.
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

    // The week-ahead headline (rain / temperature shift / persistence) is
    // derived from the same day list the charts read. The renderer returns
    // null on a calm flat week, in which case we skip the card entirely —
    // no headline beats a vacuous one. Today is days[0] (the page composes
    // [currentDay] + [upcomingDays] in that order); upstream callers that
    // pass <2 days have already been short-circuited above.
    val weekAheadInsight = remember(days, deltaThresholdC) {
        val todayDay = days.firstOrNull() ?: return@remember null
        DeriveWeekAheadInsight()(todayDay, days.drop(1), deltaThresholdC = deltaThresholdC)
    }
    val context = LocalContext.current
    val weekAheadFormatter = remember(context, region, temperatureUnit) {
        InsightFormatter.forRegion(context, region, temperatureUnit)
    }
    val weekAheadText = weekAheadInsight?.let { weekAheadFormatter.formatWeekAhead(it) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Outfit row at the top of the page — same composable the per-period
        // pages render. Surfacing it here too keeps the outfit pair pinned to
        // the same vertical offset on all three pager pages, so swiping
        // between Today / Tonight / 7-day doesn't jump the rest of the
        // content up or down. The pair shown is this-period's outfit +
        // nextOutfit (not a 7-day timeline), matching what pages 0 / 1
        // display — the at-a-glance "what to wear" summary travels with
        // the user regardless of which chart deck they're reading.
        if (outfitInsight != null) {
            OutfitPreviewRow(
                insight = outfitInsight,
                temperatureUnit = temperatureUnit,
                clothesRules = clothesRules,
                outfitTopColors = outfitTopColors,
                outfitBottomColors = outfitBottomColors,
                outfitTopStrokes = outfitTopStrokes,
                outfitBottomStrokes = outfitBottomStrokes,
                onAdjustThreshold = onAdjustThreshold,
                onNavigateToClothes = onNavigateToClothes,
            )
        }
        // Page header — same visual treatment as [InsightCard] on pages 0 / 1:
        // a 20.dp-padded Card with the chevron in a 28.dp slot on the left,
        // a "Next 7 days" label in the centered position the per-period
        // label occupies on those pages, and the prose headline below in
        // headlineSmall (matching the per-period insight prose typography).
        // Reserving 28.dp on the right keeps the label in the same horizontal
        // position as the per-period label on pages 0 / 1, so swiping between
        // pages doesn't jitter the centered label sideways.
        //
        // The prose slot shows the week-ahead headline when one fires (rain,
        // a notable temperature shift, or a persistent hot / cold run); a
        // generic "Steady week ahead." line on a calm flat week so the card
        // always carries content; and the legacy "Will be ready after the
        // next forecast." line on pre-7-day-fetch cached payloads where
        // [days] is empty or has fewer than 2 entries.
        //
        // TODO: extract an `InsightCardShell` composable shared with
        // [InsightCard] so the chevron-row geometry / padding / spacing live
        // in one place. Today this block is a hand-rolled copy of
        // InsightCard's Card+Row+Column scaffolding (TodayScreen.kt:1784–1859);
        // if someone retunes the chevron slot size, the inter-row spacing, or
        // the centered-label typography there, this card silently drifts.
        // The refactor needs the shell flexible enough for both shapes
        // (optional center content: date+location chip vs. plain label;
        // optional generated-at footer; optional right chevron) and touches
        // the today/tonight code path + every snapshot through `InsightCard`,
        // which is why it's deferred to a follow-up rather than landed here.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(28.dp)) {
                        IconButton(
                            onClick = onChevronTap,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = stringResource(R.string.today_back_to_primary),
                            )
                        }
                    }
                    val locationLabel = shortLocationLabel(location?.displayName)
                        ?: location?.let { stringResource(R.string.today_location_unknown) }
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.today_title_week),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (location != null && locationLabel != null) {
                            Text(
                                text = " · ",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = locationLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                // Tapping the city name opens the Location
                                // settings page — same affordance as the
                                // per-period [InsightCard] header. Falls back
                                // to inert text when no nav callback is wired
                                // (previews / tests).
                                modifier = if (onNavigateToLocation != null) {
                                    Modifier.clickable { onNavigateToLocation() }
                                } else {
                                    Modifier
                                },
                            )
                        }
                    }
                    Box(modifier = Modifier.size(28.dp))
                }
                val proseText = when {
                    days.size < 2 -> stringResource(R.string.today_week_empty)
                    weekAheadText != null -> weekAheadText
                    else -> stringResource(R.string.today_week_ahead_steady)
                }
                Text(
                    text = proseText,
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        }

        if (days.size < 2 || flatHourly.isEmpty() || startDate == null) return@Column

        // Shared scrub controller — same role as on the per-period pages.
        // A tap on any chart in the stack publishes an indicator at the
        // tapped time, every card draws the matching vertical line and
        // surfaces a readout. The live "now" reference is ticked once a
        // minute from the *forecast* zone (matching TodayPage), so the
        // idle indicator slides smoothly across today's hours. On a
        // legacy caller that doesn't yet pass [forecastZone], fall back
        // to the device zone — same downside as the per-period pages
        // (an indicator-offset for manual locations in a different zone
        // until the caller is updated).
        val scrubController = rememberChartScrubController()
        val zone = forecastZone ?: ZoneId.systemDefault()
        LaunchedEffect(scrubController, zone, flatHourly, startDate) {
            while (true) {
                val now = LocalDateTime.now(zone)
                val inWindow = currentTimeChartX(flatHourly, startDate, now) != null
                scrubController.setNow(if (inWindow) now else null)
                delay(60_000L)
            }
        }
        // Bridge the controller to the per-model-spread state so a tap on
        // any chart auto-reveals the spread (and tapping restore undoes
        // that reveal) — same wiring the per-period pages use. Reassigned
        // on every recomposition so the closures see the live
        // [showModelSpread] value. Stays null when per-model data isn't
        // available, in which case the controller skips the reveal and
        // the charts just scrub.
        val perModelAvailable = weekPerModelDiagnostics != null
        val showSpread = showModelSpread
        SideEffect {
            scrubController.spreadCoordinator = if (!perModelAvailable) null else {
                object : SpreadCoordinator {
                    override fun isSpreadVisible(): Boolean = showSpread
                    override fun revealSpread() = onRevealModelSpread()
                    override fun hideSpread() = onHideModelSpread()
                }
            }
        }

        // Explicit toggle for the tap-hint card — the chip-equivalent
        // affordance for users who want to switch the spread on / off
        // without scrubbing. Gated on [weekPerModelDiagnostics]: when
        // per-model data isn't available the hint card is skipped
        // entirely, so the modifier is unused in that branch.
        val hintToggleModifier: Modifier = if (perModelAvailable) {
            val onClickLabel = stringResource(
                if (showModelSpread) R.string.today_confidence_tap_to_hide
                else R.string.today_confidence_tap_to_show,
            )
            Modifier.clickable(
                onClickLabel = onClickLabel,
                role = Role.Button,
                onClick = onToggleModelSpread,
            )
        } else {
            Modifier
        }

        // The full chart stack mirrors what TodayPage renders for a
        // per-period view. Each card reads [LocalChartBottomFormatter] for
        // its x-axis labels; here we provide a day-of-week formatter so
        // 168 hours don't render as the same 00..23 axis repeated seven
        // times. [LocalScrubMomentFormat] flips the readout from "2pm" to
        // "Wed 2pm" so a reading isn't ambiguous across the seven
        // identical hour-of-day positions in the window.
        CompositionLocalProvider(
            LocalChartBottomFormatter provides dayOfWeekFormatter,
            LocalChartBottomItemPlacer provides dayItemPlacer,
            LocalChartScrub provides scrubController,
            LocalScrubMomentFormat provides ScrubMomentFormat.DayPlusHour,
        ) {
            // Tap-hint card matching the affordance the per-period pages
            // surface inside the confidence chip ("Tap to see / hide each
            // model's forecast"). The page has no chip to carry that line
            // itself, and the chart cards don't carry it either (the
            // hint would compete with each chart's own subtitle / legend
            // row), so a dedicated hint card sits at the top of the
            // chart deck. Skipped entirely when per-model data isn't
            // available (nothing for the user to toggle).
            if (weekPerModelDiagnostics != null) {
                Card(modifier = Modifier.fillMaxWidth().then(hintToggleModifier)) {
                    Text(
                        text = stringResource(
                            if (showModelSpread) R.string.today_confidence_tap_to_hide
                            else R.string.today_confidence_tap_to_show,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }
            }
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
