package app.clothescast.ui.today

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.PerModelHourly
import app.clothescast.core.domain.model.windSpeedUnit
import app.clothescast.core.domain.usecase.DeriveWeekAheadInsight
import app.clothescast.insight.InsightFormatter
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * A 7-day chart deck — used for two pager pages: "Next 7 days" (page 3,
 * forecast days 1-7) and "Following 7 days" (page 4, days 8-14). The caller
 * picks which week by passing the matching [days] slice plus [titleRes] and
 * [weekAheadBaseline]; the body is identical otherwise.
 *
 * Renders the same chart stack the per-period pages use (forecast / air
 * temp / precipitation probability / amount, plus the wind / humidity /
 * cloud / solar / UV / sunshine diagnostic cards), but fed a flat 168-hour
 * list of hourly samples covering the page's seven days, with a
 * day-of-week x-axis (`Mon Tue …`) instead of the per-period hour-of-day
 * ticks. The bottom-axis swap rides on [LocalChartBottomFormatter] so the
 * underlying chart composables stay byte-identical on the per-period
 * pages.
 *
 * The diagnostic cards (wind onward) and the primary cards' model-spread
 * overlays auto-hide when [weekPerModelHourly] is null OR when the
 * supplied series doesn't yet cover every date in [days] — i.e. on
 * legacy cached payloads from before the multi-model fetcher carried the
 * window, on upgraded-but-stale-cache users mid-refresh, or
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
    state: TodayState,
    weekPerModelHourly: PerModelHourly?,
    scrollState: ScrollState,
    onChevronTap: () -> Unit,
    /**
     * Forward chevron in the right-hand header slot. Non-null on the "Next 7
     * days" page so it advances to the "Following 7 days" page; null on that
     * second page (and on previews/tests), leaving the reserved slot empty so
     * the centered label stays put.
     */
    onForwardChevronTap: (() -> Unit)? = null,
    /**
     * Centered header label. Defaults to "Next 7 days"; the fourth pager page
     * (forecast days 8-14) passes [R.string.today_title_following_week].
     */
    titleRes: Int = R.string.today_title_week,
    /**
     * Baseline day the week-ahead headline compares against. Null (the default)
     * uses `days.first()` as the baseline and `days.drop(1)` as the look-ahead
     * window — the "Next 7 days" page, where `days[0]` is today. The "Following
     * 7 days" page passes today's [DailyForecast] here so its headline reads as
     * a shift relative to *today* (the user's anchor) rather than relative to
     * day 8, and the whole [days] list (days 8-14) is the look-ahead window.
     */
    weekAheadBaseline: DailyForecast? = null,
    /**
     * Whether the per-model diagnostic deck (wind / humidity / cloud / solar /
     * UV / sunshine) may render when real models cover only *part* of [days].
     * False (the default, "Next 7 days") requires every plotted day covered —
     * partial coverage there means a stale cache, so the deck stays hidden.
     * True ("Following 7 days") lets the deck show on partial coverage, since
     * models legitimately thin out past day 7; the per-model lines then stop on
     * the last day each model forecasts. See [weekPerModelDiagnostics].
     */
    allowPartialModelCoverage: Boolean = false,
    workStatusToShow: WorkStatus = WorkStatus.Idle,
    locationActionRequired: Boolean = false,
    onToggleModelSpread: () -> Unit = {},
    onRevealModelSpread: () -> Unit = {},
    onHideModelSpread: () -> Unit = {},
    forecastZone: ZoneId? = null,
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
    onNavigateToClothes: () -> Unit = {},
    onOpenPrivacy: () -> Unit = {},
    onOpenCalendarSettings: () -> Unit = {},
    onOpenSchedule: () -> Unit = {},
    onDismissCelebrationCard: () -> Unit = {},
    onDismissClothesPromoCard: () -> Unit = {},
    onDismissSchedulePromoCard: () -> Unit = {},
    onDismissPlayPromoCard: () -> Unit = {},
    onOpenVoice: () -> Unit = {},
    onDismissGeminiTtsPromoCard: () -> Unit = {},
    onDismissGeminiTtsLimitCard: () -> Unit = {},
    onDismissMqttError: () -> Unit = {},
    onDismissCastError: () -> Unit = {},
) {
    // Shared display settings come from [TodayState]; the chart deck and the
    // week-ahead headline below read these locals so sourcing them from state
    // doesn't churn the rest of the body.
    val temperatureUnit = state.temperatureUnit
    val distanceUnit = state.distanceUnit
    val showModelSpread = state.showModelSpread
    val region = state.region
    val deltaThresholdC = state.deltaThresholdC
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
    // Restrict the per-model series to the dates this page plots before any
    // chart sees it. The chart cards position each per-model point by its
    // *index* in the series against the page-local [flatHourly] window (see
    // [PerModelDiagnosticCard] / [ForecastChart]), so the series must start on
    // the same day as [flatHourly]. The fetched series always starts at today;
    // on the "Following 7 days" page [flatHourly] starts at day 8, so passing
    // the unsliced series would plot days 1-7 under the day-8 axis and push the
    // day 8-14 points off the right edge. Slicing to the window dates realigns
    // index 0 with the first plotted hour — and on the "Next 7 days" page it
    // also keeps the now-14-day fetch from stretching the chart's y-bounds with
    // values the page doesn't show. Models that don't reach the window at all
    // (ICON past day 7) drop out here.
    val weekPerModelInWindow: PerModelHourly? = remember(weekPerModelHourly, days) {
        val windowDates = days.map { it.date }.toSet()
        weekPerModelHourly?.let { perModel ->
            PerModelHourly(
                byModel = perModel.byModel
                    .mapValues { (_, hours) -> hours.filter { it.time.toLocalDate() in windowDates } }
                    .filterValues { it.isNotEmpty() },
            )
        }
    }
    // A current series carries ~168 hourly entries per model spanning every
    // date in [days]. Older cached payloads (from before the multi-model
    // fetcher carried the window) carry only ~48 hours covering 2 dates, and on
    // the "Following 7 days" page some models (ICON past day 7, ECMWF before
    // day 14) never reach the far dates at all. The gate keeps the diagnostic
    // deck hidden until per-model data is genuinely present — without it, a
    // stale cache shows diagnostic cards whose lines stop short at day 2, which
    // reads as "the models gave up" rather than "the cache is stale."
    //
    // [allowPartialModelCoverage] picks how strict that is. On the "Next 7
    // days" page (false) every plotted day must be covered by a real model:
    // near-week models all reach day 7, so partial coverage there means a stale
    // cache and the deck stays hidden. On the "Following 7 days" page (true) it
    // only needs *some* real coverage: models legitimately thin past day 7, so
    // requiring all 7 days would hide the deck for most regions (the user
    // accepted the resulting ragged lines, where each per-model series stops on
    // the last day it forecasts). best_match (Auto) is excluded from the check
    // either way — it always spans the window, so counting it would light the
    // surfaces up backed only by Auto (mirrors ConfidenceInfo.computeFrom).
    // (PerModelHour temperatures are non-null, so a model that doesn't reach a
    // date contributes no entry there — presence in byModel means real coverage.)
    val weekPerModelDiagnostics: PerModelHourly? = remember(weekPerModelInWindow, days, allowPartialModelCoverage) {
        weekPerModelInWindow?.takeIf { perModel ->
            val expected = days.map { it.date }.toSet()
            val covered = perModel.byModel
                .filterKeys { it != PerModelHourly.BEST_MATCH_MODEL_ID }
                .values.asSequence()
                .flatten()
                .map { it.time.toLocalDate() }
                .toSet()
            if (allowPartialModelCoverage) covered.isNotEmpty() else expected.all { it in covered }
        }
    }

    // The week-ahead headline (rain / temperature shift / persistence) is
    // derived from the same day list the charts read. The renderer returns
    // null on a calm flat week, in which case we skip the card entirely —
    // no headline beats a vacuous one. Today is days[0] (the page composes
    // [currentDay] + [upcomingDays] in that order); upstream callers that
    // pass <2 days have already been short-circuited above.
    val weekAheadInsight = remember(days, deltaThresholdC, weekAheadBaseline) {
        val baseline = weekAheadBaseline ?: days.firstOrNull() ?: return@remember null
        // With an explicit baseline (the second-week page) the whole day list is
        // the look-ahead window; otherwise days[0] is the baseline and the rest
        // is the window.
        val window = if (weekAheadBaseline != null) days else days.drop(1)
        DeriveWeekAheadInsight()(baseline, window, deltaThresholdC = deltaThresholdC)
    }
    val context = LocalContext.current
    val weekAheadFormatter = remember(context, region, temperatureUnit) {
        InsightFormatter.forRegion(context, region, temperatureUnit)
    }
    val weekAheadText = weekAheadInsight?.let { weekAheadFormatter.formatWeekAhead(it) }

    HomePageScaffold(
        state = state,
        scrollState = scrollState,
        workStatusToShow = workStatusToShow,
        locationActionRequired = locationActionRequired,
        // Same today + tonight outfit pair the per-period pages pin at the top
        // — the scaffold renders it (and the banner stack) so all three pager
        // pages carry the same cards in the same place.
        outfitInsight = outfitInsight,
        onSetUpLocation = onNavigateToLocation ?: {},
        onOpenPrivacy = onOpenPrivacy,
        onOpenCalendarSettings = onOpenCalendarSettings,
        onOpenSchedule = onOpenSchedule,
        onDismissCelebrationCard = onDismissCelebrationCard,
        onDismissClothesPromoCard = onDismissClothesPromoCard,
        onDismissSchedulePromoCard = onDismissSchedulePromoCard,
        onDismissPlayPromoCard = onDismissPlayPromoCard,
        onOpenVoice = onOpenVoice,
        onDismissGeminiTtsPromoCard = onDismissGeminiTtsPromoCard,
        onDismissGeminiTtsLimitCard = onDismissGeminiTtsLimitCard,
        onNavigateToClothes = onNavigateToClothes,
        onDismissMqttError = onDismissMqttError,
        onDismissCastError = onDismissCastError,
        homeSectionOrder = state.homeSectionOrder,
        // The conditions strip on this page summarizes the whole 7-day window:
        // [flatHourly] is every day's hourly stream, so the strip reads the
        // week's feels-like range and its peak rain / wind / UV rather than
        // repeating today's figures.
        conditionsHourly = flatHourly,
        // The week-headline card is this page's "insight" section, so it
        // reorders against the outfit row exactly like the per-period insight
        // card does on pages 0 / 1 — keeping the outfit row at a consistent
        // vertical offset across a swipe whichever order the user picked.
        insightSlot = {
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
        // optional right chevron) and touches
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
                            text = stringResource(titleRes),
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
                    Box(modifier = Modifier.size(28.dp)) {
                        if (onForwardChevronTap != null) {
                            IconButton(
                                onClick = onForwardChevronTap,
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription =
                                        stringResource(R.string.today_forward_to_following_week),
                                )
                            }
                        }
                    }
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
        },
        // The chart deck — its own reorderable section, same as on the
        // per-period pages. Renders nothing until the 7-day forecast is cached.
        chartsSlot = chartsSlot@{
            if (days.size < 2 || flatHourly.isEmpty() || startDate == null) return@chartsSlot

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
        },
    ) { }
}
