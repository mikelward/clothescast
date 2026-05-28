package app.clothescast.ui.today

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clothescast.R
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.ConfidenceInfo
import app.clothescast.core.domain.model.DistanceUnit
import app.clothescast.core.domain.model.Fact
import app.clothescast.core.domain.model.ForecastConfidence
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.GarmentReason
import app.clothescast.core.domain.model.HolidayTheme
import app.clothescast.core.domain.model.bannerTextKeyFor
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.OutfitRationale
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.ModelDivergenceSummary
import app.clothescast.core.domain.model.PerModelHour
import app.clothescast.core.domain.model.PerModelHourly
import app.clothescast.core.domain.model.consensusSunshineHours
import app.clothescast.core.domain.model.consensusSunshineHoursFor
import app.clothescast.core.domain.model.BottomsFormat
import app.clothescast.core.domain.model.ClothesFormat
import app.clothescast.core.domain.model.RainAccessory
import app.clothescast.core.domain.model.RangeFormat
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.WindSpeedUnit
import app.clothescast.core.domain.model.symbol
import app.clothescast.core.domain.model.thresholdC
import app.clothescast.core.domain.model.toUnit
import app.clothescast.core.domain.model.toWindSpeedUnit
import app.clothescast.core.domain.model.windSpeedUnit
import app.clothescast.ClothesCastApplication
import app.clothescast.tts.toJavaLocale
import app.clothescast.ui.EdgeFadeOverlay
import app.clothescast.ui.LocalTimeFormat
import app.clothescast.ui.formatHourMinute
import app.clothescast.ui.formatScrubHour
import app.clothescast.ui.garment.GarmentBottomIcon
import app.clothescast.ui.garment.GarmentTopIcon
import app.clothescast.diag.BugReport
import app.clothescast.diag.BugReportConsentDialog
import app.clothescast.diag.findActivity
import app.clothescast.insight.InsightFormatter
import app.clothescast.location.hasBackgroundLocationPermission
import app.clothescast.location.hasCoarseLocationPermission
import app.clothescast.ui.theme.AppTheme
import app.clothescast.work.FetchAndNotifyWorker
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    viewModel: TodayViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToLocation: () -> Unit = onNavigateToSettings,
    onNavigateToPrivacy: () -> Unit = onNavigateToSettings,
    onNavigateToClothes: () -> Unit = onNavigateToSettings,
    onNavigateToCalendar: () -> Unit = onNavigateToSettings,
    onNavigateToDeveloper: () -> Unit = onNavigateToSettings,
    onNavigateToFormat: () -> Unit = onNavigateToSettings,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context.findActivity()
    val coroutineScope = rememberCoroutineScope()
    val app = context.applicationContext as ClothesCastApplication
    val bugReportConsentAcked by app.settingsRepository.bugReportConsentAcknowledged
        .collectAsStateWithLifecycle(initialValue = false)
    // Both Running (fresh enqueue) and Retrying (post-failure backoff) suppress
    // Refresh — the worker still bills a Gemini call on resumption, and a tap
    // would REPLACE the in-flight retry chain. The banner copy distinguishes them.
    val isWorking = state.workStatus is WorkStatus.Running ||
        state.workStatus is WorkStatus.Retrying
    var overflowExpanded by remember { mutableStateOf(false) }
    var bugReportConsentVisible by remember { mutableStateOf(false) }

    val launchBugReport: () -> Unit = launchBugReport@{
        val act = activity ?: return@launchBugReport
        coroutineScope.launch { BugReport.share(act) }
    }

    // Hoisted out of TodayContent so the TopAppBar title can swap with the
    // visible page — page 0 is the user's current 12-hour window ("Today" or
    // "Tonight"), page 1 is the next window ("Tonight" or "Tomorrow"),
    // page 2 is the 7-day outlook. Page count is constant; when
    // thisPeriodInsight is null the pager doesn't render but the state is
    // harmlessly retained at page 0.
    val pagerState = rememberPagerState(initialPage = 0) { 3 }
    val titleRes = topBarTitleRes(
        period = state.thisPeriodInsight?.period,
        page = pagerState.currentPage,
    )
    // System back on page 1 or 2 steps back one page instead of exiting the
    // app — matches the on-screen left chevron. Page 0 falls through to the
    // platform default (finish the activity).
    BackHandler(enabled = pagerState.currentPage > 0) {
        coroutineScope.launch {
            pagerState.animateScrollToPage(pagerState.currentPage - 1)
        }
    }

    Scaffold(
        // Drop the default `safeDrawing` content insets so the pager extends
        // edge-to-edge under the (transparent) nav bar. The padding lambda
        // below still includes the TopAppBar's height; the empty-state Column
        // and each TodayPage's scrolling Column add
        // `windowInsetsPadding(WindowInsets.navigationBars)` themselves so
        // content stays reachable above the nav bar, and the bottom fade in
        // `EdgeFadeOverlay` does the same so it sits just above the bar.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(titleRes)) },
                actions = {
                    // While anything's active on the alarm or replay queues we
                    // disable Refresh and (during the fetch phase) swap the icon
                    // for a spinner. The work makes a billed Gemini insight call
                    // and (depending on the engine) a billed TTS call; re-tapping
                    // Refresh while one is in flight uses ExistingWorkPolicy.REPLACE,
                    // which kills the in-flight worker and starts another —
                    // re-issuing both requests. And since replays now live on
                    // their own unique-work queue, WorkManager no longer
                    // serializes a Refresh tap against a pending / mid-delivery
                    // Replay — without [anyWorkActive] gating both buttons,
                    // tapping Refresh during a Replay's TTS would deliver the
                    // refresh and the replay concurrently. The spinner icon
                    // still keys off [isWorking] so it shows during fetch but
                    // not during the post-fetch TTS / MQTT / Cast window
                    // (no fetching happening, no spinner).
                    IconButton(
                        onClick = { triggerRefresh(context, state.morningTime, state.tonightTime) },
                        enabled = !state.anyWorkActive,
                    ) {
                        if (isWorking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.today_refresh),
                            )
                        }
                    }
                    // Play replays the cached this-period insight through the
                    // full deliver() pipeline — notification, phone-speaker
                    // TTS, MQTT publish, cast — without a fetch. Disabled
                    // when there's nothing to play (no cached insight) or
                    // anything is active on the alarm / replay queues
                    // (state.anyWorkActive). The latter is broader than
                    // [isWorking]: it stays true through the post-fetch
                    // TTS / MQTT / Cast window that the spinner-banner
                    // logic intentionally treats as Idle, so a tap during
                    // a Refresh's announcement (or a Replay's own) can't
                    // start a second concurrent delivery now that Play
                    // runs on its own unique-work queue.
                    val playPeriod = state.thisPeriodInsight?.period
                    IconButton(
                        onClick = {
                            playPeriod?.let { triggerPlay(context, it) }
                        },
                        enabled = !state.anyWorkActive && playPeriod != null,
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.today_play),
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.today_open_settings),
                        )
                    }
                    IconButton(onClick = { overflowExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.today_more_options),
                        )
                    }
                    DropdownMenu(
                        expanded = overflowExpanded,
                        onDismissRequest = { overflowExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.today_report_a_bug)) },
                            onClick = {
                                overflowExpanded = false
                                if (bugReportConsentAcked) {
                                    launchBugReport()
                                } else {
                                    bugReportConsentVisible = true
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_root_about)) },
                            onClick = {
                                overflowExpanded = false
                                onNavigateToAbout()
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        TodayContent(
            state = state,
            padding = padding,
            isWorking = isWorking,
            pagerState = pagerState,
            onRefresh = { triggerRefresh(context, state.morningTime, state.tonightTime) },
            onSetUpLocation = onNavigateToLocation,
            onOpenPrivacy = onNavigateToPrivacy,
            onOpenCalendarSettings = onNavigateToCalendar,
            onDismissCelebrationCard = viewModel::dismissCelebrationCard,
            onDismissClothesPromoCard = viewModel::dismissClothesPromoCard,
            onCalendarPermissionChanged = viewModel::notifyCalendarPermissionChanged,
            onAdjustThreshold = viewModel::adjustClothesRuleThreshold,
            onNavigateToClothes = onNavigateToClothes,
            onNavigateToFormat = onNavigateToFormat,
            onToggleModelSpread = viewModel::toggleModelSpread,
            onRevealModelSpread = viewModel::revealModelSpread,
            onHideModelSpread = viewModel::hideModelSpread,
            onLongPressDate = onNavigateToDeveloper,
        )
    }

    if (bugReportConsentVisible) {
        BugReportConsentDialog(
            onConfirm = { dontShowAgain ->
                bugReportConsentVisible = false
                if (dontShowAgain) {
                    coroutineScope.launch {
                        app.settingsRepository.setBugReportConsentAcknowledged(true)
                    }
                }
                launchBugReport()
            },
            onDismiss = { bugReportConsentVisible = false },
        )
    }
}

@Composable
private fun TodayContent(
    state: TodayState,
    padding: PaddingValues,
    isWorking: Boolean,
    pagerState: PagerState,
    onRefresh: () -> Unit,
    onSetUpLocation: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenCalendarSettings: () -> Unit,
    onDismissCelebrationCard: () -> Unit,
    onDismissClothesPromoCard: () -> Unit,
    onCalendarPermissionChanged: () -> Unit,
    onAdjustThreshold: (String, Double) -> Unit,
    onNavigateToClothes: () -> Unit,
    onNavigateToFormat: () -> Unit,
    onToggleModelSpread: () -> Unit,
    onRevealModelSpread: () -> Unit,
    onHideModelSpread: () -> Unit,
    onLongPressDate: () -> Unit,
) {
    val context = LocalContext.current
    // Permission state is observed live, not snapshotted, so granting from system
    // Settings and returning to Today flips the banner off without a tap. The
    // worker re-checks at notify time anyway; this just keeps the home screen
    // honest while the user is looking at it.
    var coarseGranted by remember { mutableStateOf(hasCoarseLocationPermission(context)) }
    var backgroundGranted by remember { mutableStateOf(hasBackgroundLocationPermission(context)) }
    // Calendar themes: nudge the recheck tick on every ON_RESUME when at
    // least one theming toggle is on. We can't reliably detect a
    // permission *transition* here because `remember` re-initialises when
    // the user navigates away and back, so a true→false revoke while Today
    // is off-screen would re-init `calendarGranted=false` on return and
    // never see the transition — leaving a cached birthday/holiday banner
    // (with its event title) on screen until midnight. Unconditional nudge
    // is cheap (a DataStore long write off-thread) and only fires when the
    // user has opted into the feature.
    val currentUsesCalendarThemes by rememberUpdatedState(state.usesCalendarThemes)
    val currentOnCalendarPermissionChanged by rememberUpdatedState(onCalendarPermissionChanged)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                coarseGranted = hasCoarseLocationPermission(context)
                backgroundGranted = hasBackgroundLocationPermission(context)
                if (currentUsesCalendarThemes) {
                    currentOnCalendarPermissionChanged()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // The worker can produce a forecast iff it has a resolvable location at
    // notify time: either the device-location toggle is on AND background
    // permission is granted, OR a fallback city is saved. Anything else is
    // the "stuck" state — surface it as a banner so existing users who were
    // previously falling back to London (now: failing) understand why and
    // know what to tap.
    val locationActionRequired = !state.hasFallbackLocation &&
        !(state.useDeviceLocation && coarseGranted && backgroundGranted)
    val workStatusToShow = bannerStatus(
        workStatus = state.workStatus,
        hasInsight = state.thisPeriodInsight != null,
        locationActionRequired = locationActionRequired,
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        if (state.thisPeriodInsight == null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Match the nav-bar inset added inside each TodayPage's
                    // scroll viewport so the Fetch-now button isn't hidden
                    // by the (translucent) nav bar when the banner stack
                    // pushes it down.
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                BannerStack(
                    state = state,
                    workStatusToShow = workStatusToShow,
                    locationActionRequired = locationActionRequired,
                    onOpenPrivacy = onOpenPrivacy,
                    onOpenClothes = onNavigateToClothes,
                    onDismissClothesPromoCard = onDismissClothesPromoCard,
                    onOpenCalendarSettings = onOpenCalendarSettings,
                    onDismissCelebrationCard = onDismissCelebrationCard,
                    onSetUpLocation = onSetUpLocation,
                )
                EmptyState(onRefresh = onRefresh, isWorking = isWorking)
            }
        } else {
            // Two-page pager — page 0 is the this-period insight (the 12-hour
            // window the user is currently in), page 1 is the next-period
            // insight (or a placeholder when its slot hasn't been cached
            // yet). A chevron next to each page's InsightCard hints at
            // the affordance; side-swipe anywhere on the page navigates.
            //
            // `weight(1f)` (not `fillMaxSize()`) on the pager is load-
            // bearing: in a Column, `fillMaxSize` asks for the *full*
            // parent height, ignoring earlier siblings — so the pager
            // would render below the header but extend past the visible
            // bottom edge, and each page's `verticalScroll` would think
            // its viewport included the clipped-off area, leaving the
            // bottom of the chart stack unreachable. `weight(1f)` makes
            // the pager fill only what's left after the header so the
            // scroll viewport matches the visible region.
            val pagerScope = rememberCoroutineScope()
            // Shared across both pages so swiping from page 1 to page 2
            // lands at the same vertical offset — if the user is reading
            // the rain chart on Today, swiping to Tomorrow keeps them
            // on the rain chart rather than snapping back to the top.
            val scrollState = rememberScrollState()
            // Placeholder period for page 1 when its slot is empty —
            // whatever the next 12-hour window after `thisPeriodInsight` is.
            // The worker writes [InsightCache.Slot.NEXT_PERIOD] paired with
            // each delivery, so this fallback only fires before the first
            // post-upgrade worker run.
            val nextPeriodFallback = state.thisPeriodInsight.period.opposite()
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { page ->
                if (page == 2) {
                    // Plot today + upcomingDays so the chart starts on the
                    // user's current day. On legacy cached insights that
                    // predate either field, the page collapses to a stand-in
                    // message via [SevenDayPage]. The diagnostic cards
                    // (wind / humidity / cloud / solar / UV / sunshine) and
                    // model-spread overlays ride on [weekPerModelHourly] —
                    // null on payloads from before the multi-model fetcher's
                    // forecast_days=7 bump, in which case those cards
                    // auto-hide and only the primary-data charts render.
                    val weekDays = listOfNotNull(state.thisPeriodInsight.currentDay) +
                        state.thisPeriodInsight.upcomingDays
                    SevenDayPage(
                        days = weekDays,
                        state = state,
                        weekPerModelHourly = state.thisPeriodInsight.weekPerModelHourly,
                        scrollState = scrollState,
                        workStatusToShow = workStatusToShow,
                        locationActionRequired = locationActionRequired,
                        onChevronTap = {
                            pagerScope.launch { pagerState.animateScrollToPage(1) }
                        },
                        onToggleModelSpread = onToggleModelSpread,
                        onRevealModelSpread = onRevealModelSpread,
                        onHideModelSpread = onHideModelSpread,
                        forecastZone = state.thisPeriodInsight.forecastZone,
                        location = state.thisPeriodInsight.location,
                        onNavigateToLocation = onSetUpLocation,
                        // Same outfit pair pages 0 / 1 show. Pinning it at
                        // the top of every page keeps the rest of the
                        // content from jumping when the user swipes.
                        outfitInsight = state.thisPeriodInsight,
                        onAdjustThreshold = onAdjustThreshold,
                        onNavigateToClothes = onNavigateToClothes,
                        onOpenPrivacy = onOpenPrivacy,
                        onOpenCalendarSettings = onOpenCalendarSettings,
                        onDismissCelebrationCard = onDismissCelebrationCard,
                        onDismissClothesPromoCard = onDismissClothesPromoCard,
                    )
                    return@HorizontalPager
                }
                val pageInsight = if (page == 0) state.thisPeriodInsight else state.nextPeriodInsight
                val pagePeriod = if (page == 0) state.thisPeriodInsight.period else nextPeriodFallback
                TodayPage(
                    insight = pageInsight,
                    fallbackPeriod = pagePeriod,
                    state = state,
                    scrollState = scrollState,
                    // Same outfit row on all three pages — pages 0 / 1 and
                    // the 7-day page all show this period's pair, not the
                    // current page's period's pair. Pinning the row at the
                    // top of every page keeps the rest of the content from
                    // jumping when the user swipes; it also stays the
                    // at-a-glance today+tonight summary rather than
                    // morphing into a 7-day outfit timeline on page 2.
                    outfitInsight = state.thisPeriodInsight,
                    // Chevron layout across the three pages:
                    //   page 0 (this period) — right chevron → page 1
                    //   page 1 (next period) — left chevron → page 0,
                    //                          right chevron → page 2
                    //   page 2 (7-day overview) — its own back chevron → page 1
                    // The 7-day page has a left chevron that doesn't surface
                    // here (SevenDayPage owns its own header), so we only
                    // wire chevrons for pages 0 and 1 in this block.
                    showChevronRight = (page == 0 || page == 1),
                    showChevronLeft = (page == 1),
                    workStatusToShow = workStatusToShow,
                    locationActionRequired = locationActionRequired,
                    onChevronTap = {
                        // Left chevron on page 1 → back to page 0.
                        // Right chevron on page 0 → forward to page 1.
                        pagerScope.launch {
                            pagerState.animateScrollToPage(if (page == 0) 1 else 0)
                        }
                    },
                    onChevronRightTap = if (page == 1) {
                        { pagerScope.launch { pagerState.animateScrollToPage(2) } }
                    } else {
                        null
                    },
                    onAdjustThreshold = onAdjustThreshold,
                    onNavigateToClothes = onNavigateToClothes,
                    onNavigateToFormat = onNavigateToFormat,
                    onToggleModelSpread = onToggleModelSpread,
                    onRevealModelSpread = onRevealModelSpread,
                    onHideModelSpread = onHideModelSpread,
                    onLongPressDate = onLongPressDate,
                    onSetUpLocation = onSetUpLocation,
                    onOpenPrivacy = onOpenPrivacy,
                    onOpenCalendarSettings = onOpenCalendarSettings,
                    onDismissCelebrationCard = onDismissCelebrationCard,
                    onDismissClothesPromoCard = onDismissClothesPromoCard,
                )
            }
        }
    }
}

/**
 * Stack of top-of-screen banners: update available, local-build / crash /
 * telemetry disclosures, celebration-themes promo, location-required prompt,
 * work-status spinner, and the day's holiday banner. None are pinned — the
 * caller embeds this in its scroll viewport so banners scroll with the
 * content underneath rather than hogging vertical space at the top of the
 * screen in the steady state. Each banner early-returns when its condition
 * isn't met, so the enclosing Column's spacedBy arrangement collapses to
 * zero between hidden entries.
 *
 * HolidayBanner is last on purpose: it ends up adjacent to the outfit row
 * whose palette it explains.
 */
@Composable
private fun BannerStack(
    state: TodayState,
    workStatusToShow: WorkStatus,
    locationActionRequired: Boolean,
    onOpenPrivacy: () -> Unit,
    onOpenClothes: () -> Unit,
    onDismissClothesPromoCard: () -> Unit,
    onOpenCalendarSettings: () -> Unit,
    onDismissCelebrationCard: () -> Unit,
    onSetUpLocation: () -> Unit,
) {
    val bannerModifier = Modifier.fillMaxWidth()
    // Cap the setup/promo stack so a fresh user isn't buried under "set this
    // up" cards. Only the top [maxVisible] eligible promos render (priority
    // location > privacy > clothes > celebration); the rest wait their turn.
    // The operational banners below (update / build / crash / work / holiday)
    // are unaffected and keep their existing positions.
    val shownPromos = promoBannersToShow(
        locationActionRequired = locationActionRequired,
        telemetryNoticeVisible = state.telemetryNoticeVisible,
        clothesPromoEligible = state.clothesPromoCardVisible,
        celebrationEligible = state.celebrationCardVisible,
        hasForecast = state.thisPeriodInsight != null,
    )
    // LocationActionRequiredBanner is first on purpose: without a resolvable
    // location nothing else on the screen is actionable — no insight will
    // generate, no notification will fire — so the prompt to fix it
    // outranks every other banner, including the update / crash disclosures.
    if (PromoBanner.LOCATION in shownPromos) {
        LocationActionRequiredBanner(
            onSetUpLocation = onSetUpLocation,
            modifier = bannerModifier,
        )
    }
    UpdateAvailableBanner(modifier = bannerModifier)
    LocalBuildBanner(modifier = bannerModifier)
    LastCrashBanner(modifier = bannerModifier)
    // One-shot privacy disclosure for the default-on Firebase telemetry,
    // so the default isn't silent. Auto-hides once the user dismisses it
    // (or taps through to Privacy from it). Stays out of the way of the
    // crash banner: that's a current problem to action; this is just
    // disclosure.
    TelemetryNoticeBanner(
        visible = PromoBanner.TELEMETRY in shownPromos,
        onOpenPrivacy = onOpenPrivacy,
        modifier = bannerModifier,
    )
    // "Customize your clothes" nudge so the user knows the per-temperature
    // rules are theirs to tune. Gated upstream on [clothesRules] still
    // matching [ClothesRule.DEFAULTS] (plus the user not having dismissed),
    // and held back by [promoBannersToShow] until the user has seen a forecast
    // and there's room under the cap. Sits before the celebration-themes promo
    // because clothes rules are the more core customization.
    ClothesPromoCard(
        visible = PromoBanner.CLOTHES in shownPromos,
        onOpenClothes = {
            onDismissClothesPromoCard()
            onOpenClothes()
        },
        onDismiss = onDismissClothesPromoCard,
        modifier = bannerModifier,
    )
    // Promo card for the calendar-sourced holiday + birthday theming. Gated
    // upstream on toggles + dismissal ([TodayState.celebrationCardVisible]),
    // held back by [promoBannersToShow] until the user has seen a forecast
    // and there's room under the cap — so it disappears the moment either
    // toggle goes on or the X is tapped, and never crowds a brand-new user.
    CelebrationThemesCard(
        visible = PromoBanner.CELEBRATION in shownPromos,
        onOpenCalendarSettings = onOpenCalendarSettings,
        onDismiss = onDismissCelebrationCard,
        modifier = bannerModifier,
    )
    WorkStatusBanner(status = workStatusToShow, modifier = bannerModifier)
    HolidayBanner(
        theme = state.activeHoliday,
        region = state.region,
        onClick = onOpenCalendarSettings,
        modifier = bannerModifier,
    )
}

/**
 * Shared chrome for every page in the Today pager. Wraps the page's [content]
 * in the common layout: the top/bottom edge fades, the time-format provider,
 * the scrolling Column with its nav-bar inset + padding, the top-of-scroll
 * [BannerStack] (location / update / local-build / crash / telemetry / promo /
 * work-status / holiday), and the pinned [OutfitPreviewRow]. Pages 0 / 1 add
 * the insight card + per-period chart deck; page 2 adds the weekly header +
 * 7-day chart deck. Keeping the chrome here means the banner stack and outfit
 * row are byte-identical across all three pages — swiping between Today /
 * Tonight / 7-day keeps the same cards in the same place.
 */
@Composable
internal fun HomePageScaffold(
    state: TodayState,
    scrollState: ScrollState,
    workStatusToShow: WorkStatus,
    locationActionRequired: Boolean,
    outfitInsight: Insight?,
    onSetUpLocation: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenCalendarSettings: () -> Unit,
    onDismissCelebrationCard: () -> Unit,
    onDismissClothesPromoCard: () -> Unit,
    onNavigateToClothes: () -> Unit,
    onAdjustThreshold: (String, Double) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Edge fades hint at off-screen content above / below — drawn at the
    // page's outer Box bounds (the pager-page edges), so cards pass cleanly
    // under them as the user scrolls.
    EdgeFadeOverlay(scrollState = scrollState) {
        CompositionLocalProvider(LocalTimeFormat provides state.timeFormat) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    // Nav-bar inset goes here — *inside* the scroll viewport, as
                    // content padding — so the last card can scroll fully above
                    // the (translucent) nav bar while the viewport itself still
                    // extends to the screen edge.
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Banner stack sits at the top of the scroll viewport (rather
                // than pinned above the pager) so nothing hogs vertical space
                // on the steady-state screen. HolidayBanner is last in the
                // stack so it ends up adjacent to the outfit row whose palette
                // it explains.
                BannerStack(
                    state = state,
                    workStatusToShow = workStatusToShow,
                    locationActionRequired = locationActionRequired,
                    onOpenPrivacy = onOpenPrivacy,
                    onOpenClothes = onNavigateToClothes,
                    onDismissClothesPromoCard = onDismissClothesPromoCard,
                    onOpenCalendarSettings = onOpenCalendarSettings,
                    onDismissCelebrationCard = onDismissCelebrationCard,
                    onSetUpLocation = onSetUpLocation,
                )
                // Outfit row sits above the page body so it also renders when
                // the page's own insight is missing (page 2's placeholder, or a
                // not-yet-cached next period). [outfitInsight] is always this
                // period's insight, pinning the same today + tonight pair to the
                // same vertical offset on every page so swiping doesn't jump the
                // content. Null only on previews / tests that don't wire a pair.
                if (outfitInsight != null) {
                    OutfitPreviewRow(
                        insight = outfitInsight,
                        temperatureUnit = state.temperatureUnit,
                        clothesRules = state.clothesRules,
                        outfitTopColors = state.outfitTopColors,
                        outfitBottomColors = state.outfitBottomColors,
                        outfitTopStrokes = state.outfitTopStrokes,
                        outfitBottomStrokes = state.outfitBottomStrokes,
                        onAdjustThreshold = onAdjustThreshold,
                        onNavigateToClothes = onNavigateToClothes,
                    )
                }
                content()
            }
        }
    }
}

/**
 * One page inside the Today pager. When [insight] is non-null it renders the
 * existing InsightCard + ConfidenceChip + chart-card stack for that period;
 * when null (the paired slot hasn't been cached yet) it surfaces a
 * [MissingPeriodPlaceholder] for [fallbackPeriod] so the user understands
 * when to expect content there.
 *
 * [scrollState] is hoisted to the pager so both pages share a single
 * vertical offset — swiping mid-page lands the user at the same row on
 * the other day's content.
 */
@Composable
private fun TodayPage(
    insight: Insight?,
    fallbackPeriod: ForecastPeriod,
    state: TodayState,
    scrollState: ScrollState,
    outfitInsight: Insight,
    showChevronRight: Boolean,
    showChevronLeft: Boolean,
    workStatusToShow: WorkStatus,
    locationActionRequired: Boolean,
    onChevronTap: () -> Unit,
    onChevronRightTap: (() -> Unit)? = null,
    onAdjustThreshold: (String, Double) -> Unit,
    onNavigateToClothes: () -> Unit,
    onNavigateToFormat: () -> Unit,
    onToggleModelSpread: () -> Unit,
    onRevealModelSpread: () -> Unit,
    onHideModelSpread: () -> Unit,
    onLongPressDate: () -> Unit,
    onSetUpLocation: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenCalendarSettings: () -> Unit,
    onDismissCelebrationCard: () -> Unit,
    onDismissClothesPromoCard: () -> Unit,
) {
    val scrollScope = rememberCoroutineScope()
    // Captured via onGloballyPositioned on the ConfidenceChip below so the
    // tap handler can scroll the chip to the top of the viewport without
    // hard-coding offsets above it (outfit row + insight card heights vary).
    var chipScrollOffset by remember { mutableIntStateOf(0) }
    HomePageScaffold(
        state = state,
        scrollState = scrollState,
        workStatusToShow = workStatusToShow,
        locationActionRequired = locationActionRequired,
        outfitInsight = outfitInsight,
        onSetUpLocation = onSetUpLocation,
        onOpenPrivacy = onOpenPrivacy,
        onOpenCalendarSettings = onOpenCalendarSettings,
        onDismissCelebrationCard = onDismissCelebrationCard,
        onDismissClothesPromoCard = onDismissClothesPromoCard,
        onNavigateToClothes = onNavigateToClothes,
        onAdjustThreshold = onAdjustThreshold,
    ) {
        if (insight == null) {
            MissingPeriodPlaceholder(
                period = fallbackPeriod,
                morningTime = state.morningTime,
                tonightTime = state.tonightTime,
                showChevronLeft = showChevronLeft,
                onChevronTap = onChevronTap,
                showChevronRight = showChevronRight,
                onChevronRightTap = onChevronRightTap,
            )
            return@HomePageScaffold
        }
        // Two separate per-model-spread affordances:
        //
        //  - The confidence chip [ConfidenceChip] keeps its labelled
        //    "tap to show / hide" toggle (wired through [onChipTap] →
        //    [onToggleModelSpread]). That's the explicit on/off control.
        //
        //  - Tapping the plot grid of any chart enters scrub mode on
        //    the shared [ChartScrubController], which (via the
        //    [SpreadCoordinator] wired below) reveals the per-model
        //    spread if it isn't already on. The matching restore
        //    icon exits scrub mode and undoes only that auto-reveal
        //    — spread state the user enabled via the chip is left
        //    alone.
        //
        // [chipToggle] is null when there's no per-model data in
        // the cache (e.g. older payloads) — in that case the chip
        // stays as a static confidence summary and the controller's
        // [spreadCoordinator] stays null so chart gestures just
        // scrub without touching spread state.
        val perModelAvailable = insight.perModelHourly != null
        val chipToggle = onToggleModelSpread.takeIf { perModelAvailable }
        InsightCard(
            insight = insight,
            region = state.region,
            temperatureUnit = state.temperatureUnit,
            rangeFormat = state.rangeFormat,
            clothesFormat = state.clothesFormat,
            bottomsFormat = state.bottomsFormat,
            rainAccessory = state.rainAccessory,
            showChevronRight = showChevronRight,
            showChevronLeft = showChevronLeft,
            onChevronTap = onChevronTap,
            onChevronRightTap = onChevronRightTap,
            onLongPressDate = onLongPressDate,
            onNavigateToFormat = onNavigateToFormat,
            onNavigateToLocation = onSetUpLocation,
        )
        insight.confidence?.let {
            // Wrap the per-model toggle so a tap also scrolls the chip to the
            // top of the viewport — the per-model overlay renders on the
            // charts below the chip, so scrolling them into view is part of
            // the same gesture's payoff.
            val onChipTap: (() -> Unit)? = chipToggle?.let { toggle ->
                {
                    toggle()
                    scrollScope.launch { scrollState.animateScrollTo(chipScrollOffset) }
                }
            }
            ConfidenceChip(
                modifier = Modifier.onGloballyPositioned { coords ->
                    chipScrollOffset = coords.positionInParent().y.roundToInt()
                },
                info = it,
                perModelHourly = insight.perModelHourly,
                temperatureUnit = state.temperatureUnit,
                windSpeedUnit = state.distanceUnit.windSpeedUnit(),
                showModelSpread = state.showModelSpread,
                onToggleModelSpread = onChipTap,
            )
        }
        if (insight.hourly.isNotEmpty()) {
            // Pass per-model data unconditionally so each chart's y-axis is
            // sized to the same envelope whether the overlay is showing or
            // not — tapping the toggle adds / removes lines but never
            // shifts the scale. The diagnostic cards below follow the same
            // pattern (see [PerModelDiagnosticCard]).
            val perModelData = insight.perModelHourly
            // Shared scrub controller — one per page. Every chart on this
            // page reads it through [LocalChartScrub] and draws its
            // indicator + readout tooltip at the controller's active
            // time. Dragging on any chart updates the time on all of
            // them in lock-step. The controller dies with this
            // composable (no rememberSaveable) so navigating away from
            // the Today screen returns it to "now"-tracking on next
            // entry.
            val scrubController = rememberChartScrubController()
            // Tick the controller's "now" reference once a minute so
            // the live indicator slides smoothly across the chart. Read
            // `now` in the *forecast* zone (Open-Meteo's `timezone=auto`,
            // surfaced on [Insight.forecastZone]) rather than the
            // device's, because [HourlyForecast.time] is wall-clock
            // local to that zone. For the common auto-location case the
            // two are equal; for a manual location in a different zone,
            // using the device zone would shift the indicator by the
            // offset (or hide it out of window). Fall back to the
            // device default on legacy cached insights that predate
            // `forecastZone`. While the user is scrubbed, the controller
            // keeps the live `now` reference internally so the restore
            // icon snaps back to a fresh value rather than the stale one
            // at the time of scrub.
            val zone = insight.forecastZone ?: ZoneId.systemDefault()
            LaunchedEffect(scrubController, zone, insight.hourly, insight.forDate) {
                while (true) {
                    val now = LocalDateTime.now(zone)
                    val inWindow = currentTimeChartX(
                        hourly = insight.hourly,
                        startDate = insight.forDate,
                        now = now,
                    ) != null
                    scrubController.setNow(if (inWindow) now else null)
                    delay(60_000L)
                }
            }
            // Bridge the controller to the per-model-spread state so a
            // tap-to-scrub gesture reveals the spread (and tapping
            // restore undoes that reveal). Reassigned on every
            // recomposition so the closures see the live
            // [state.showModelSpread] value — the controller itself is
            // remembered across compositions, only its callbacks change.
            // Stays null when there's no per-model data (older cached
            // payloads) so the controller skips the reveal entirely.
            val showSpread = state.showModelSpread
            SideEffect {
                scrubController.spreadCoordinator = if (!perModelAvailable) null else {
                    object : SpreadCoordinator {
                        override fun isSpreadVisible(): Boolean = showSpread
                        override fun revealSpread() = onRevealModelSpread()
                        override fun hideSpread() = onHideModelSpread()
                    }
                }
            }
            CompositionLocalProvider(LocalChartScrub provides scrubController) {
                ForecastCard(
                    hourly = insight.hourly,
                    temperatureUnit = state.temperatureUnit,
                    distanceUnit = state.distanceUnit,
                    startDate = insight.forDate,
                    perModelHourly = perModelData,
                    showModelSpread = state.showModelSpread,
                )
                AirTemperatureCard(
                    hourly = insight.hourly,
                    temperatureUnit = state.temperatureUnit,
                    startDate = insight.forDate,
                    perModelHourly = perModelData,
                    showModelSpread = state.showModelSpread,
                )
                PrecipitationCard(
                    hourly = insight.hourly,
                    startDate = insight.forDate,
                    perModelHourly = perModelData,
                    showModelSpread = state.showModelSpread,
                )
                PrecipitationAmountCard(
                    hourly = insight.hourly,
                    forDate = insight.forDate,
                    period = insight.period,
                    perModelHourly = perModelData,
                    showModelSpread = state.showModelSpread,
                )
                // Diagnostic cards below the headline temp + rain pair. Each
                // draws a consensus main line by default and overlays the
                // per-model spread when [showModelSpread] is on — same pattern
                // as the temp / precip cards. Each card auto-hides when every
                // consulted model is missing its metric outright (older cached
                // payloads don't carry wind / humidity / cloud).
                insight.perModelHourly?.let { perModelData ->
                    // Order: "feels-like" metrics first (wind, humidity),
                    // then the sun-related cluster as a cause→effect chain
                    // — clouds gate irradiance, UV is a subset of that
                    // irradiance, sunshine is the time-integrated payoff.
                    WindCard(
                        hourly = insight.hourly,
                        perModelHourly = perModelData,
                        windSpeedUnit = state.distanceUnit.windSpeedUnit(),
                        startDate = insight.forDate,
                        showModelSpread = state.showModelSpread,
                    )
                    HumidityCard(
                        hourly = insight.hourly,
                        perModelHourly = perModelData,
                        startDate = insight.forDate,
                        showModelSpread = state.showModelSpread,
                    )
                    CloudCard(
                        hourly = insight.hourly,
                        perModelHourly = perModelData,
                        startDate = insight.forDate,
                        showModelSpread = state.showModelSpread,
                    )
                    SolarRadiationCard(
                        hourly = insight.hourly,
                        perModelHourly = perModelData,
                        startDate = insight.forDate,
                        showModelSpread = state.showModelSpread,
                    )
                    UvIndexCard(
                        hourly = insight.hourly,
                        perModelHourly = perModelData,
                        startDate = insight.forDate,
                        showModelSpread = state.showModelSpread,
                    )
                    SunshineCard(
                        hourly = insight.hourly,
                        perModelHourly = perModelData,
                        forDate = insight.forDate,
                        period = insight.period,
                        showModelSpread = state.showModelSpread,
                    )
                }
            }
        }
    }
}

/**
 * Page-2 stand-in when the paired period's slot hasn't been generated yet
 * (e.g. mid-morning on the day of first install before the evening worker
 * has run). Names the period and the time of day the user can expect a
 * result, plus a back-chevron to return to the primary page.
 */
@Composable
internal fun MissingPeriodPlaceholder(
    period: ForecastPeriod,
    morningTime: LocalTime,
    tonightTime: LocalTime,
    showChevronLeft: Boolean,
    onChevronTap: () -> Unit,
    showChevronRight: Boolean = false,
    onChevronRightTap: (() -> Unit)? = null,
) {
    val readyAt = if (period == ForecastPeriod.TODAY) morningTime else tonightTime
    val readyAtText = formatHourMinute(readyAt)
    val titleRes = if (period == ForecastPeriod.TODAY) {
        R.string.today_placeholder_today_title
    } else {
        R.string.today_placeholder_tonight_title
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Matches InsightCard's three-zone header: back-chevron in the
            // left slot, centred title, optional forward-chevron in the
            // right slot. Both 28.dp slots stay reserved whether or not a
            // chevron renders, so swiping between an InsightCard and this
            // placeholder doesn't shift the header horizontally.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(28.dp)) {
                    if (showChevronLeft) {
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
                }
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(titleRes),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Box(modifier = Modifier.size(28.dp)) {
                    if (showChevronRight && onChevronRightTap != null) {
                        IconButton(
                            onClick = onChevronRightTap,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.today_view_other_period),
                            )
                        }
                    }
                }
            }
            Text(
                text = stringResource(
                    R.string.today_placeholder_body,
                    readyAtText,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun LocationActionRequiredBanner(
    onSetUpLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.today_location_required_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.today_location_required_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onSetUpLocation,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.today_location_required_action)) }
        }
    }
}

/**
 * Picks the [WorkStatus] the Today screen's banner should render, given the
 * raw status from WorkManager plus the local UI predicates that suppress or
 * soften it.
 *
 *  - **No-location failure with the location-required banner already showing.**
 *    Hide the generic failure card — the action banner above already explains
 *    the cause and offers the fix.
 *  - **Retrying with nothing in the cache yet (cold open).** "Last attempt
 *    failed — retrying" reads as a real error to the user, but WorkManager
 *    bumps `runAttemptCount` and parks the work back in ENQUEUED whenever a
 *    run is interrupted — not just when our code returned `Result.retry()`.
 *    An OS-initiated kill (process death during an app update, low-memory
 *    eviction, the JobScheduler rescheduling around doze) leaves the same
 *    state on disk as a real transient failure. With no prior insight on
 *    screen to anchor "last attempt", the error framing is misleading; treat
 *    it as a generic "Fetching" so the user just sees that work is in flight.
 *    When an existing insight *is* on screen, the "retrying" framing helps
 *    the user understand why what they're looking at is stale, so we keep it.
 */
internal fun bannerStatus(
    workStatus: WorkStatus,
    hasInsight: Boolean,
    locationActionRequired: Boolean,
): WorkStatus = when {
    locationActionRequired &&
        workStatus is WorkStatus.Failed &&
        workStatus.reason == FetchAndNotifyWorker.REASON_NO_LOCATION -> WorkStatus.Idle
    !hasInsight && workStatus is WorkStatus.Retrying -> WorkStatus.Running
    else -> workStatus
}

/**
 * A small festive chip that appears above the outfit preview row whenever the
 * day matches an enabled holiday. The banner colour is the theme's
 * [HolidayTheme.bannerArgb]; the text reads "{emoji} {localised banner copy}"
 * — the emoji is part of the visible glyph run rather than a separate Icon so
 * the line wraps as one unit at large fontScale.
 *
 * Text colour is computed from the banner colour's luminance so the chip is
 * readable on both light (Christmas red) and dark (Anzac khaki) palettes
 * without keeping a parallel light/dark table for every holiday.
 *
 * [region] is consulted only when the active theme defines a per-country
 * banner-text override (currently just Remembrance Day → Veterans Day for the
 * US). Region.SYSTEM falls through to the device's default locale, so the
 * Veterans Day label still surfaces for an SYSTEM-region user on a US phone.
 *
 * The composable early-returns when [theme] is null so the banner stack's
 * "hidden banners take zero vertical space" invariant still holds on
 * non-holiday days.
 */
@Composable
internal fun HolidayBanner(
    theme: HolidayTheme?,
    region: Region = Region.SYSTEM,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (theme == null) return
    val context = LocalContext.current
    val effectiveCountry = remember(region) {
        (region.toJavaLocale() ?: Locale.getDefault()).country
    }
    val bannerText = remember(theme, effectiveCountry) {
        val segments = theme.bannerSegments
        if (!segments.isNullOrEmpty()) {
            // Composed multi-celebration banner: join each piece with the
            // localised "and" ("Happy bank holiday and don't forget your
            // towel").
            val and = context.getString(R.string.holiday_banner_and)
            segments.joinToString(separator = " $and ") { segment ->
                segment.literalText ?: run {
                    val key = segment.textKeyByCountry[effectiveCountry.uppercase()] ?: segment.textKey
                    resolveBannerString(context, key, theme.id.name)
                }
            }
        } else {
            // Synthetic themes (calendar-sourced holidays/birthdays) carry the
            // event title as [displayTitleOverride]; bypass the resource lookup
            // because the key is a runtime string, not a `@string/...` id.
            theme.displayTitleOverride
                ?: resolveBannerString(context, theme.bannerTextKeyFor(effectiveCountry), theme.id.name)
        }
    }
    val bannerColor = remember(theme.bannerArgb) { Color(theme.bannerArgb.toInt()) }
    val textColor = remember(theme.bannerArgb) {
        // sRGB luminance via the 0.299/0.587/0.114 weights — bright banners
        // (Australia Day gold, Brazil yellow) get dark text; deep banners
        // (Christmas red, St Andrew's blue) get white. Threshold 0.55 is a
        // visual compromise that keeps Halloween's medium-orange on white
        // text rather than flipping at the midpoint.
        val argb = theme.bannerArgb.toInt()
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val luminance = 0.299f * r + 0.587f * g + 0.114f * b
        if (luminance > 0.55f) Color.Black else Color.White
    }
    val cardColors = CardDefaults.cardColors(
        containerColor = bannerColor,
        contentColor = textColor,
    )
    val cardModifier = modifier.fillMaxWidth()
    val content: @Composable () -> Unit = {
        Text(
            text = "${theme.emoji}  $bannerText",
            style = MaterialTheme.typography.titleSmall,
            color = textColor,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            textAlign = TextAlign.Center,
        )
    }
    // Material3's `Card(onClick = …)` overload carries the right semantics
    // for accessibility tooling — matches OutfitPreviewCard's pattern.
    if (onClick != null) {
        Card(onClick = onClick, modifier = cardModifier, colors = cardColors) { content() }
    } else {
        Card(modifier = cardModifier, colors = cardColors) { content() }
    }
}

/**
 * Resolves a `@string/<name>` banner key by name (the theme catalogue lives
 * in `:core:domain` and can't hold compile-time `R` ids). Falls back to
 * [fallback] (the holiday's enum name) when the key is null or unknown so a
 * missing translation degrades to something developer-readable rather than
 * blank.
 */
private fun resolveBannerString(
    context: android.content.Context,
    key: String?,
    fallback: String,
): String {
    if (key == null) return fallback
    val resId = context.resources.getIdentifier(key, "string", context.packageName)
    return if (resId == 0) fallback else context.getString(resId)
}

@Composable
internal fun WorkStatusBanner(
    status: WorkStatus,
    modifier: Modifier = Modifier,
) {
    when (status) {
        is WorkStatus.Idle -> Unit
        is WorkStatus.Running -> SpinnerBanner(stringResource(R.string.today_working), modifier)
        is WorkStatus.Retrying -> SpinnerBanner(stringResource(R.string.today_retrying), modifier)
        is WorkStatus.Failed -> {
            Card(
                modifier = modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.today_failed_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = describeFailure(status),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!status.detail.isNullOrBlank()) {
                        var showDetails by rememberSaveable(status.detail) { mutableStateOf(false) }
                        Text(
                            text = stringResource(
                                if (showDetails) R.string.today_failed_hide_details
                                else R.string.today_failed_show_details,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .clickable { showDetails = !showDetails },
                        )
                        if (showDetails) {
                            Text(
                                text = status.detail,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpinnerBanner(message: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun describeFailure(failed: WorkStatus.Failed): String =
    when (failed.reason) {
        FetchAndNotifyWorker.REASON_UNEXPECTED_HTTP ->
            stringResource(R.string.today_failed_unexpected_http)
        FetchAndNotifyWorker.REASON_NO_LOCATION ->
            stringResource(R.string.today_failed_no_location)
        FetchAndNotifyWorker.REASON_UNHANDLED, null ->
            stringResource(R.string.today_failed_unhandled)
        else -> failed.reason
    }

@Composable
internal fun EmptyState(onRefresh: () -> Unit, isWorking: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.today_empty_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.today_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRefresh, enabled = !isWorking) {
                Text(stringResource(R.string.today_fetch_now))
            }
        }
    }
}

/**
 * Side-by-side "What to wear" row. Shows the primary outfit on the left and the
 * upcoming-period outfit on the right — "Today + Tonight" on a morning insight,
 * "Tonight + Tomorrow" on an evening one — so a glance covers both the next few
 * hours and the next handover (heading-out outfit + coming-home outfit).
 *
 * Falls back to a single card when the insight didn't carry a [Insight.nextOutfit]
 * (legacy cache payloads, or a tonight insight on a forecast bundle without
 * tomorrow's daily aggregates).
 *
 * TODO(outfit-weather-overlay): place a small weather glyph (sun / cloud / rain /
 *   snow) over the centre of the top icon so a glance carries both "what to wear"
 *   *and* "what's it doing outside" — e.g. a t-shirt with a sun, a sweater with a
 *   raincloud. Use the same imagery for the product launcher icon (mipmap/ic_launcher,
 *   ic_launcher_round, ic_launcher_background) so the home-screen icon, the
 *   outfit cards, and the notification large icon all read as one family.
 */
@Composable
internal fun OutfitPreviewRow(
    insight: Insight,
    temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
    clothesRules: List<ClothesRule> = emptyList(),
    outfitTopColors: Map<OutfitSuggestion.Top, Long> = emptyMap(),
    outfitBottomColors: Map<OutfitSuggestion.Bottom, Long> = emptyMap(),
    outfitTopStrokes: Map<OutfitSuggestion.Top, Long> = emptyMap(),
    outfitBottomStrokes: Map<OutfitSuggestion.Bottom, Long> = emptyMap(),
    onAdjustThreshold: (String, Double) -> Unit = { _, _ -> },
    onNavigateToClothes: () -> Unit = {},
) {
    val primary = insight.outfit ?: return
    val (primaryLabel, nextLabel) = outfitLabels(insight.period)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutfitPreviewCard(
            outfit = primary,
            label = stringResource(primaryLabel),
            rationale = insight.outfitRationale,
            temperatureUnit = temperatureUnit,
            clothesRules = clothesRules,
            outfitTopColors = outfitTopColors,
            outfitBottomColors = outfitBottomColors,
            outfitTopStrokes = outfitTopStrokes,
            outfitBottomStrokes = outfitBottomStrokes,
            onAdjustThreshold = onAdjustThreshold,
            onNavigateToClothes = onNavigateToClothes,
            modifier = Modifier.weight(1f),
        )
        insight.nextOutfit?.let {
            OutfitPreviewCard(
                outfit = it,
                label = stringResource(nextLabel),
                rationale = insight.nextOutfitRationale,
                temperatureUnit = temperatureUnit,
                clothesRules = clothesRules,
                outfitTopColors = outfitTopColors,
                outfitBottomColors = outfitBottomColors,
                outfitTopStrokes = outfitTopStrokes,
                outfitBottomStrokes = outfitBottomStrokes,
                onAdjustThreshold = onAdjustThreshold,
                onNavigateToClothes = onNavigateToClothes,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun outfitLabels(period: ForecastPeriod): Pair<Int, Int> = when (period) {
    ForecastPeriod.TODAY -> R.string.today_outfit_label_today to R.string.today_outfit_label_tonight
    ForecastPeriod.TONIGHT -> R.string.today_outfit_label_tonight to R.string.today_outfit_label_tomorrow
}

// Title shown in the TopAppBar — tracks the visible pager page so swiping
// right from a morning view flips "Today" to "Tonight" (and the evening
// equivalent flips "Tonight" to "Tomorrow"). Page 2 is the 7-day outlook;
// the title is the same regardless of which period the user opened from.
// Falls back to "Today" when no insight is cached yet (pager isn't
// rendered in that state).
internal fun topBarTitleRes(period: ForecastPeriod?, page: Int): Int {
    if (page == 2) return R.string.today_title_week
    return when (period) {
        null -> R.string.today_title
        ForecastPeriod.TODAY -> if (page == 0) R.string.today_title else R.string.today_outfit_label_tonight
        ForecastPeriod.TONIGHT -> if (page == 0) R.string.today_outfit_label_tonight else R.string.today_outfit_label_tomorrow
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OutfitPreviewCard(
    outfit: OutfitSuggestion,
    label: String,
    modifier: Modifier = Modifier,
    rationale: OutfitRationale? = null,
    temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
    clothesRules: List<ClothesRule> = emptyList(),
    outfitTopColors: Map<OutfitSuggestion.Top, Long> = emptyMap(),
    outfitBottomColors: Map<OutfitSuggestion.Bottom, Long> = emptyMap(),
    outfitTopStrokes: Map<OutfitSuggestion.Top, Long> = emptyMap(),
    outfitBottomStrokes: Map<OutfitSuggestion.Bottom, Long> = emptyMap(),
    onAdjustThreshold: (String, Double) -> Unit = { _, _ -> },
    onNavigateToClothes: () -> Unit = {},
) {
    // Material3's `Card(onClick = …)` overload is preferred over a bare
    // `modifier.clickable` — it carries the right semantics for accessibility
    // tooling and matches how SettingsNavRow / other tap-targets in the app are
    // wired.
    Card(
        onClick = onNavigateToClothes,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            // Reserve a fixed 80dp-tall slot for the top icon so the card's
            // height stays the same regardless of which top is shown — the
            // top SVGs have inconsistent viewport heights (THICK_JACKET is
            // 96×90, THICK_COAT is 96×96, T-SHIRT/SWEATER are 96×86), which
            // would otherwise leave one card a few dp taller than its
            // neighbour. Align the icon to BottomCenter so any unused space
            // sits above it; the icons still meet flush at the waistline
            // (bottom icons are all 96×96, so width(80.dp) already gives an
            // 80dp-square — no slot needed).
            Column(
                verticalArrangement = Arrangement.spacedBy(0.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier.size(80.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    GarmentTopIcon(
                        top = outfit.top,
                        customFill = outfitTopColors[outfit.top]?.let { Color(it.toInt()) },
                        customStroke = outfitTopStrokes[outfit.top]?.let { Color(it.toInt()) },
                        contentDescription = stringResource(topLabelRes(outfit.top)),
                        modifier = Modifier.width(80.dp),
                    )
                }
                GarmentBottomIcon(
                    bottom = outfit.bottom,
                    customFill = outfitBottomColors[outfit.bottom]?.let { Color(it.toInt()) },
                    customStroke = outfitBottomStrokes[outfit.bottom]?.let { Color(it.toInt()) },
                    contentDescription = stringResource(bottomLabelRes(outfit.bottom)),
                    modifier = Modifier.width(80.dp),
                )
            }
            // Reserve two lines for the garment-name text so cards match
            // even when one combination wraps and the other doesn't (e.g.
            // "Thick jacket · Long pants" wraps at the row's per-card width
            // but "Sweater · Long pants" doesn't).
            Text(
                text = stringResource(topLabelRes(outfit.top)) +
                    " · " +
                    stringResource(bottomLabelRes(outfit.bottom)),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                minLines = 2,
            )
            Text(
                text = stringResource(R.string.settings_root_clothes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * "Why this outfit?" detail sheet — explains the deciding facts (feels-like min / max
 * + the hour they occurred + the threshold they crossed) so the user can sanity-check
 * the call against their own day, and nudge the deciding cutoff with `−1°` / `+1°`.
 *
 * The displayed threshold value tracks the *live* [clothesRules] (so a tap updates
 * the number immediately), while the observed value + hour come from the cached
 * [rationale] (frozen at insight-generation time). The comparison ("under" vs "above")
 * is recomputed against the live threshold so the prose stays honest after a tap.
 * Outfit cards on the home screen still show the cached pick — a refresh re-runs the
 * pipeline against the new clothes rules.
 *
 * For full rule management (add / delete / change unit) the user goes to Settings →
 * Clothes; this sheet is just a quick-nudge affordance over the deciding cutoff.
 */
@Composable
internal fun OutfitRationaleDialog(
    outfit: OutfitSuggestion,
    rationale: OutfitRationale?,
    temperatureUnit: TemperatureUnit,
    clothesRules: List<ClothesRule>,
    onAdjustThreshold: (String, Double) -> Unit,
    onNavigateToClothes: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.today_rationale_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (rationale == null) {
                    Text(
                        text = stringResource(R.string.today_rationale_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    GarmentReasonBlock(
                        title = stringResource(topLabelRes(outfit.top)),
                        reason = rationale.top,
                        temperatureUnit = temperatureUnit,
                        clothesRules = clothesRules,
                        onAdjustThreshold = onAdjustThreshold,
                    )
                    GarmentReasonBlock(
                        title = stringResource(bottomLabelRes(outfit.bottom)),
                        reason = rationale.bottom,
                        temperatureUnit = temperatureUnit,
                        clothesRules = clothesRules,
                        onAdjustThreshold = onAdjustThreshold,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.today_rationale_dismiss))
            }
        },
        dismissButton = {
            TextButton(onClick = onNavigateToClothes) {
                Text(stringResource(R.string.today_rationale_open_clothes_settings))
            }
        },
    )
}

@Composable
private fun GarmentReasonBlock(
    title: String,
    reason: GarmentReason,
    temperatureUnit: TemperatureUnit,
    clothesRules: List<ClothesRule>,
    onAdjustThreshold: (String, Double) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        reason.facts.forEach { fact ->
            // Prefer the live rule's threshold (so a tap updates the displayed
            // number immediately) and fall back to the fact's cached threshold
            // when the user has deleted the rule since the insight was cached
            // — that way the dialog still has *something* to render while the
            // user's next nudge re-creates the rule from the catalog default.
            val liveC = clothesRules.firstOrNull { it.item == fact.ruleItem }?.thresholdC()
                ?: fact.thresholdC
            FactRow(
                fact = fact,
                temperatureUnit = temperatureUnit,
                liveThresholdC = liveC,
                onAdjust = { delta -> onAdjustThreshold(fact.ruleItem, delta) },
            )
        }
    }
}

@Composable
private fun FactRow(
    fact: Fact,
    temperatureUnit: TemperatureUnit,
    liveThresholdC: Double,
    onAdjust: (Double) -> Unit,
) {
    // One tap = one degree *in the user's display unit*, persisted as the
    // matching °C delta. Without this, a Fahrenheit user tapping `+` would see
    // the displayed threshold jump by ~2°F per tap (1°C ≈ 1.8°F), which is
    // surprising. Bound checks compare against the canonical Celsius range so
    // the buttons disable at the documented MIN_C / MAX_C edges regardless of
    // unit.
    val stepC = when (temperatureUnit) {
        TemperatureUnit.CELSIUS -> 1.0
        TemperatureUnit.FAHRENHEIT -> 5.0 / 9.0
    }
    val decreaseDesc = stringResource(R.string.today_rationale_threshold_decrease)
    val increaseDesc = stringResource(R.string.today_rationale_threshold_increase)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatFact(fact, temperatureUnit, liveThresholdC),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        FilledTonalIconButton(
            onClick = { onAdjust(-stepC) },
            enabled = liveThresholdC > ClothesRule.THRESHOLD_MIN_C,
            modifier = Modifier
                .size(32.dp)
                .semantics { contentDescription = decreaseDesc },
        ) {
            Text(
                text = "−",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        FilledTonalIconButton(
            onClick = { onAdjust(stepC) },
            enabled = liveThresholdC < ClothesRule.THRESHOLD_MAX_C,
            modifier = Modifier
                .padding(start = 4.dp)
                .size(32.dp)
                .semantics { contentDescription = increaseDesc },
        ) {
            Text(
                text = "+",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun formatFact(fact: Fact, unit: TemperatureUnit, liveThresholdC: Double): String {
    val symbol = unit.symbol()
    val observedConverted = fact.observedC.toUnit(unit)
    val thresholdConverted = liveThresholdC.toUnit(unit)
    // Self-contradiction guard: if integer rounding makes the observed and
    // threshold values look equal but they're actually different (e.g. an
    // actual 17.6 < 18.0 displaying as "18°C, under 18°C"), drop to one-decimal
    // precision so the printed numbers tell the same story as the prose. The
    // common case is still bare integers — fractional formatting only kicks in
    // on the exact-boundary edge.
    val observedI = observedConverted.roundToInt()
    val thresholdI = thresholdConverted.roundToInt()
    val collide = observedI == thresholdI && observedConverted != thresholdConverted
    val observedStr: String
    val thresholdStr: String
    if (collide) {
        observedStr = ONE_DECIMAL_FORMAT.format(observedConverted)
        thresholdStr = ONE_DECIMAL_FORMAT.format(thresholdConverted)
    } else {
        observedStr = observedI.toString()
        thresholdStr = thresholdI.toString()
    }
    val time = fact.observedAt?.let { formatHourMinute(it) }
    // Recompute the comparison against the live threshold so the prose ("under" /
    // "above") stays honest after the user nudges the knob; the cached
    // [Fact.comparison] reflects the value at insight-generation time.
    val comparison = comparisonFor(fact.observedC, liveThresholdC)
    val res = when (fact.metric) {
        Fact.Metric.FEELS_LIKE_MIN -> when (comparison) {
            Fact.Comparison.BELOW -> if (time != null) {
                R.string.today_rationale_min_below_with_time
            } else {
                R.string.today_rationale_min_below
            }
            Fact.Comparison.AT_OR_ABOVE -> if (time != null) {
                R.string.today_rationale_min_above_with_time
            } else {
                R.string.today_rationale_min_above
            }
        }
        Fact.Metric.FEELS_LIKE_MAX -> when (comparison) {
            Fact.Comparison.BELOW -> if (time != null) {
                R.string.today_rationale_max_below_with_time
            } else {
                R.string.today_rationale_max_below
            }
            Fact.Comparison.AT_OR_ABOVE -> if (time != null) {
                R.string.today_rationale_max_above_with_time
            } else {
                R.string.today_rationale_max_above
            }
        }
    }
    return if (time != null) {
        stringResource(res, observedStr, symbol, time, thresholdStr)
    } else {
        stringResource(res, observedStr, symbol, thresholdStr)
    }
}

// Tiny helper: the prose just needs to know "is observed below threshold or at/above",
// regardless of which rule produced the fact. The `at least Y°C` template covers the
// at-or-above branch even at exact equality, and the rule's strict-less-than /
// strict-greater-than operators only differ from `<` / `>=` at exact equality, which
// the [formatFact] self-contradiction guard already handles by switching to one-decimal
// precision.
private fun comparisonFor(observedC: Double, thresholdC: Double): Fact.Comparison =
    if (observedC < thresholdC) Fact.Comparison.BELOW else Fact.Comparison.AT_OR_ABOVE

// Locale-aware one-decimal formatter used as a fallback in [formatFact] when
// integer rounding of observed and threshold values would otherwise collide
// (e.g. "17.6" and "18.0" both rounding to "18"). Default locale picks the
// right decimal separator (`,` in de-DE, `.` in en-US, etc.).
private val ONE_DECIMAL_FORMAT: java.text.NumberFormat =
    java.text.NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }

private fun topLabelRes(top: OutfitSuggestion.Top): Int = when (top) {
    OutfitSuggestion.Top.TSHIRT -> R.string.today_outfit_top_tshirt
    OutfitSuggestion.Top.POLO -> R.string.today_outfit_top_polo
    OutfitSuggestion.Top.SWEATER -> R.string.today_outfit_top_sweater
    OutfitSuggestion.Top.THIN_JACKET -> R.string.today_outfit_top_thin_jacket
    OutfitSuggestion.Top.THICK_JACKET -> R.string.today_outfit_top_thick_jacket
    OutfitSuggestion.Top.THICK_COAT -> R.string.today_outfit_top_thick_coat
    OutfitSuggestion.Top.PUFFER_JACKET -> R.string.today_outfit_top_puffer_jacket
}

private fun bottomLabelRes(bottom: OutfitSuggestion.Bottom): Int = when (bottom) {
    OutfitSuggestion.Bottom.SHORTS -> R.string.today_outfit_bottom_shorts
    OutfitSuggestion.Bottom.SHORT_SKIRT -> R.string.today_outfit_bottom_short_skirt
    OutfitSuggestion.Bottom.LONG_SKIRT -> R.string.today_outfit_bottom_long_skirt
    OutfitSuggestion.Bottom.JEANS -> R.string.today_outfit_bottom_jeans
    OutfitSuggestion.Bottom.LONG_PANTS -> R.string.today_outfit_bottom_long_pants
}

@Composable
internal fun InsightCard(
    insight: Insight,
    region: Region,
    temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
    /**
     * How the rendered prose presents the temperature-range clause (none /
     * numeric degrees / band words). Defaults to [RangeFormat.DEGREES] so
     * existing previews stay byte-identical.
     */
    rangeFormat: RangeFormat = RangeFormat.DEGREES,
    /**
     * How the rendered prose renders the clothes clause (each item named, or
     * collapsed to a layer count). Defaults to [ClothesFormat.ITEMS] so
     * existing previews stay byte-identical.
     */
    clothesFormat: ClothesFormat = ClothesFormat.ITEMS,
    /**
     * Whether bottoms appear in the wear clause. Defaults to
     * [BottomsFormat.IF_GARMENTS] so existing previews stay byte-identical.
     */
    bottomsFormat: BottomsFormat = BottomsFormat.IF_GARMENTS,
    /**
     * Optional wet-weather accessory named alongside the rain mention.
     * Defaults to [RainAccessory.NONE] so existing previews stay
     * byte-identical.
     */
    rainAccessory: RainAccessory = RainAccessory.NONE,
    /**
     * Right-edge chevron affordance. On page 0 it points to the next-period
     * page; on page 1 (paired with [showChevronLeft]) it points to the
     * 7-day overview page. The destination is fully determined by the
     * caller — see [onChevronRightTap].
     */
    showChevronRight: Boolean = false,
    /**
     * Left-edge chevron affordance: jumps back to the previous page. Can
     * appear alongside [showChevronRight] (on page 1 it does, since that
     * page sits between page 0 and the 7-day overview). Both default to
     * false so existing non-pager call sites — and every default-arg
     * preview — keep their snapshots byte-identical.
     */
    showChevronLeft: Boolean = false,
    /**
     * Fallback tap callback for either chevron. The left chevron always
     * uses this; the right chevron uses it only when [onChevronRightTap]
     * is null. Existing call sites that show only one chevron at a time
     * keep wiring this single callback.
     */
    onChevronTap: (() -> Unit)? = null,
    /**
     * Right-chevron override. When non-null, the right chevron taps invoke
     * this instead of [onChevronTap], so a page showing both chevrons can
     * route left and right to different destinations.
     */
    onChevronRightTap: (() -> Unit)? = null,
    /**
     * Hidden developer shortcut: long-pressing the date label invokes this
     * (the Today screen wires it to open Developer settings). Null disables
     * it — a plain tap on the date never does anything, so it can't be
     * triggered accidentally. Default null keeps every preview / non-live
     * call site unchanged.
     */
    onLongPressDate: (() -> Unit)? = null,
    /**
     * Opens the Format settings page. Wired to two affordances: tapping the
     * prose body (only the prose — not the date / location header or the
     * generated-at footer) and a small "Format" link in the card's bottom-right
     * corner. Null disables both; default null keeps every preview / non-live
     * call site unchanged.
     */
    onNavigateToFormat: (() -> Unit)? = null,
    /**
     * Opens the Location settings page. Wired to the location label in the
     * card's header — tapping the resolved city navigates the user there so
     * they can verify the address detail and open the coords in maps. Null
     * keeps the label non-tappable (used by previews / tests).
     */
    onNavigateToLocation: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val formatter = remember(context, region, temperatureUnit, rangeFormat, clothesFormat, bottomsFormat, rainAccessory) {
        InsightFormatter.forRegion(context, region, temperatureUnit, rangeFormat, clothesFormat, bottomsFormat, rainAccessory)
    }
    val generatedAtText = formatHourMinute(
        insight.generatedAt.atZone(ZoneId.systemDefault()).toLocalTime(),
    )
    // Page 2 caches tomorrow's daytime insight after the evening worker run;
    // surface it as "Tomorrow" rather than "Today" so the heading matches the
    // prose lead-in below.
    val isFutureDay = insight.forDate.isAfter(LocalDate.now())
    val periodLabel = stringResource(
        when (insight.period) {
            ForecastPeriod.TODAY ->
                if (isFutureDay) R.string.today_outfit_label_tomorrow
                else R.string.today_outfit_label_today
            ForecastPeriod.TONIGHT -> R.string.today_outfit_label_tonight
        },
    )
    val location = insight.location
    // Fall back to a localised "Your location" when reverse geocoding returned
    // nothing useful — we still have coords, so the maps link is worth keeping.
    val locationLabel = shortLocationLabel(location?.displayName)
        ?: location?.let { stringResource(R.string.today_location_unknown) }
    val renderLeftChevron = showChevronLeft && onChevronTap != null
    val rightChevronAction = onChevronRightTap ?: onChevronTap
    val renderRightChevron = showChevronRight && rightChevronAction != null
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Three-zone header: 28.dp slots are reserved on both edges so
            // the period label sits in the same horizontal position whether
            // or not a chevron is currently rendered, and so the back/forward
            // affordances land on opposite edges as the user swipes between
            // pages. AutoMirrored chevron variants flip in RTL automatically.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(28.dp)) {
                    if (renderLeftChevron) {
                        IconButton(
                            onClick = { onChevronTap?.invoke() },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = stringResource(R.string.today_back_to_primary),
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = periodLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = if (onLongPressDate != null) {
                            Modifier.pointerInput(onLongPressDate) {
                                detectTapGestures(onLongPress = { onLongPressDate() })
                            }
                        } else {
                            Modifier
                        },
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
                            // Tapping the city name opens the Location settings
                            // page — the address detail + Map button live there.
                            // Falls back to inert text when no nav callback is
                            // wired (previews / tests).
                            modifier = if (onNavigateToLocation != null) {
                                Modifier.clickable { onNavigateToLocation() }
                            } else {
                                Modifier
                            },
                        )
                    }
                }
                Box(modifier = Modifier.size(28.dp)) {
                    if (renderRightChevron) {
                        IconButton(
                            onClick = { rightChevronAction?.invoke() },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.today_view_other_period),
                            )
                        }
                    }
                }
            }
            Text(
                text = formatter.format(insight.summary, isFutureDay = isFutureDay),
                style = MaterialTheme.typography.headlineSmall,
                modifier = if (onNavigateToFormat != null) {
                    Modifier.clickable { onNavigateToFormat() }
                } else {
                    Modifier
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(
                        R.string.today_generated_at,
                        generatedAtText,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onNavigateToFormat != null) {
                    Text(
                        text = stringResource(R.string.today_insight_format_link),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onNavigateToFormat() },
                    )
                }
            }
        }
    }
}

/**
 * "How sure are we?" card rendered between [InsightCard] and the temp cards.
 * Background colour tracks the level via the active [app.clothescast.ui.theme.AppPalette]
 * — Material `secondaryContainer` / `surfaceVariant` / `errorContainer` on the
 * Rainbow palette (teal-ish HIGH, neutral MEDIUM, red-ish LOW), or the
 * Okabe-Ito-derived sky blue / neutral / amber when the user has picked the
 * Accessible palette in Display settings. On MEDIUM/LOW tiers the card adds a
 * detail line (or two): a feels-like divergence hint from
 * [ModelDivergenceSummary] explaining *what* the models disagree on (e.g.
 * "Models disagree most at 15:00 (Δ 2.4°C feels-like) — mostly air
 * temperature, 11–14°C") and, when precip spread crosses the HIGH/MEDIUM
 * boundary, a parallel rain-disagreement line. HIGH tiers stay quiet — title
 * + tap hint only.
 */
@Composable
internal fun ConfidenceChip(
    info: ConfidenceInfo,
    perModelHourly: PerModelHourly?,
    temperatureUnit: TemperatureUnit,
    windSpeedUnit: WindSpeedUnit,
    showModelSpread: Boolean = false,
    onToggleModelSpread: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val confidenceColors = AppTheme.palette.confidence.getValue(info.level)
    val bgColor = confidenceColors.background
    val fgColor = confidenceColors.foreground
    val labelRes = when (info.level) {
        ForecastConfidence.HIGH -> R.string.today_confidence_high
        ForecastConfidence.MEDIUM -> R.string.today_confidence_medium
        ForecastConfidence.LOW -> R.string.today_confidence_low
    }
    val cardModifier = modifier
        .fillMaxWidth()
        .let { if (onToggleModelSpread != null) it.clickable(onClick = onToggleModelSpread) else it }
    Card(
        modifier = cardModifier,
        colors = CardDefaults.cardColors(containerColor = bgColor, contentColor = fgColor),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.titleSmall,
            )
            // Detail lines only render when the chip's tier shows disagreement.
            // On HIGH days the title is the message ("Forecasters agree"); per-
            // hour or timing-only spreads aren't worth surfacing here when the
            // daily aggregates driving info.level match — surfacing them would
            // contradict the title (e.g. "Forecasters agree" sitting directly
            // above "Models disagree most at 15:00…" on a day where models
            // share a daily maximum but peak at different hours). Power users
            // can still toggle the per-model overlay on the charts below to
            // see the curves themselves.
            if (info.level != ForecastConfidence.HIGH) {
                // Filter perModelHourly to the consulted-models subset before
                // computing the feels-like divergence hint. ConfidenceInfo's
                // tier is derived from the named consulted models only (ECMWF
                // / GFS / ICON), while perModelHourly also carries the Open-
                // Meteo best_match overlay. Without this filter a best_match
                // outlier could make the detail line disagree with the tier
                // even after the HIGH gate.
                val consultedHourly = remember(perModelHourly, info.modelsConsulted) {
                    perModelHourly?.let { hourly ->
                        val consulted = info.modelsConsulted.toSet()
                        PerModelHourly(byModel = hourly.byModel.filterKeys { it in consulted })
                    }?.takeIf { it.byModel.size >= 2 }
                }
                if (consultedHourly != null) {
                    ModelDivergenceHint(
                        perModelHourly = consultedHourly,
                        temperatureUnit = temperatureUnit,
                        windSpeedUnit = windSpeedUnit,
                    )
                }
                // Precip spread can drive MEDIUM/LOW on its own (tight temps,
                // wide rain disagreement) — ModelDivergenceHint only explains
                // feels-like divergence, so without this line the chip would
                // say "Forecasters disagree…" with no explanation on a precip-
                // only LOW day. Surface the rain disagreement alongside the
                // feels-like hint whenever it crosses the same HIGH/MEDIUM
                // boundary the tier-picker uses.
                if (info.precipSpreadPp >= ConfidenceInfo.PRECIP_HIGH_AGREEMENT_PP) {
                    Text(
                        text = stringResource(
                            R.string.today_confidence_precip_spread,
                            info.precipSpreadPp,
                            info.modelsConsulted.size,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (onToggleModelSpread != null) {
                Text(
                    text = stringResource(
                        if (showModelSpread) R.string.today_confidence_tap_to_hide
                        else R.string.today_confidence_tap_to_show,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
internal fun ForecastCard(
    hourly: List<HourlyForecast>,
    temperatureUnit: TemperatureUnit,
    distanceUnit: DistanceUnit = DistanceUnit.KILOMETERS,
    startDate: java.time.LocalDate = java.time.LocalDate.now(),
    perModelHourly: PerModelHourly? = null,
    showModelSpread: Boolean = false,
) {
    val symbol = temperatureUnit.symbol()
    val feelsLikeMinMax = remember(hourly, temperatureUnit) {
        formatMinMax(hourly.map { it.feelsLikeC }, temperatureUnit)
    }
    val scrubController = LocalChartScrub.current
    val subtitleText = feelsLikeMinMax?.let {
        stringResource(R.string.today_forecast_min_max, it.first, it.second, symbol)
    }
    val readout = rememberChartReadout(hourly, startDate) { idx, moment ->
        val entry = hourly[idx]
        val v = entry.feelsLikeC.toUnit(temperatureUnit).roundToInt()
        stringResource(R.string.today_chart_readout, "$v$symbol", formatScrubMoment(moment))
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Box {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.today_forecast_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                ChartSubtitleRow(subtitle = subtitleText, readout = readout)
                ForecastChart(
                    hourly = hourly,
                    temperatureUnit = temperatureUnit,
                    showFeelsLike = true,
                    startDate = startDate,
                    perModelHourly = perModelHourly,
                    showModelSpread = showModelSpread,
                )
                Text(
                    text = stringResource(R.string.today_forecast_legend_feels_like, symbol),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (perModelHourly != null) {
                    ModelSpreadLegend(
                        visibleModelIds = if (showModelSpread) MODEL_DRAW_ORDER.filter { it in perModelHourly.byModel } else emptyList(),
                        mainLine = MainLineLegend(
                            color = AppTheme.mainLineColor,
                            label = stringResource(R.string.today_chart_main_line_label),
                        ),
                    )
                }
            }
            if (scrubController != null) ChartRestoreOverlay(scrubController)
        }
    }
}

@Composable
internal fun AirTemperatureCard(
    hourly: List<HourlyForecast>,
    temperatureUnit: TemperatureUnit,
    startDate: java.time.LocalDate = java.time.LocalDate.now(),
    perModelHourly: PerModelHourly? = null,
    showModelSpread: Boolean = false,
) {
    val symbol = temperatureUnit.symbol()
    val airMinMax = remember(hourly, temperatureUnit) {
        formatMinMax(hourly.map { it.temperatureC }, temperatureUnit)
    }
    val scrubController = LocalChartScrub.current
    val subtitleText = airMinMax?.let {
        stringResource(R.string.today_forecast_air_min_max, it.first, it.second, symbol)
    }
    val readout = rememberChartReadout(hourly, startDate) { idx, moment ->
        val entry = hourly[idx]
        val v = entry.temperatureC.toUnit(temperatureUnit).roundToInt()
        stringResource(R.string.today_chart_readout, "$v$symbol", formatScrubMoment(moment))
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Box {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.today_forecast_air_section_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                ChartSubtitleRow(subtitle = subtitleText, readout = readout)
                ForecastChart(
                    hourly = hourly,
                    temperatureUnit = temperatureUnit,
                    showFeelsLike = false,
                    startDate = startDate,
                    perModelHourly = perModelHourly,
                    showModelSpread = showModelSpread,
                )
                Text(
                    text = stringResource(R.string.today_forecast_legend_air, symbol),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (perModelHourly != null) {
                    ModelSpreadLegend(
                        visibleModelIds = if (showModelSpread) MODEL_DRAW_ORDER.filter { it in perModelHourly.byModel } else emptyList(),
                        mainLine = MainLineLegend(
                            color = AppTheme.mainLineColor,
                            label = stringResource(R.string.today_chart_main_line_label),
                        ),
                    )
                }
            }
            if (scrubController != null) ChartRestoreOverlay(scrubController)
        }
    }
}

/**
 * One-line "why do the models disagree?" hint rendered inside the forecast
 * confidence card as the chip's detail line. See
 * [ModelDivergenceSummary.computeFrom] for the threshold + factor-ranking
 * heuristic; this composable just formats the result. Color is left
 * inherited so it picks up the chip's `contentColor` (which tracks the
 * confidence tier — secondary / surface / error container).
 */
@Composable
private fun ModelDivergenceHint(
    perModelHourly: PerModelHourly,
    temperatureUnit: TemperatureUnit,
    windSpeedUnit: WindSpeedUnit,
) {
    val summary = remember(perModelHourly) {
        ModelDivergenceSummary.computeFrom(perModelHourly)
    } ?: return
    val factorLabel = stringResource(
        when (summary.topFactor) {
            ModelDivergenceSummary.Factor.AIR_TEMPERATURE -> R.string.today_factor_air_temperature
            ModelDivergenceSummary.Factor.WIND_SPEED -> R.string.today_factor_wind_speed
            ModelDivergenceSummary.Factor.CLOUD_COVER -> R.string.today_factor_cloud_cover
            ModelDivergenceSummary.Factor.RELATIVE_HUMIDITY -> R.string.today_factor_humidity
        },
    )
    val rangeText = formatTopFactorRange(summary, temperatureUnit, windSpeedUnit)
    // A feels-like *delta* converts to °F by simple multiplication (no
    // offset), unlike absolute temperatures. Express in the user's unit so
    // a Fahrenheit user doesn't see °C in the spread number.
    val spreadInUserUnit = when (temperatureUnit) {
        TemperatureUnit.CELSIUS -> summary.feelsLikeSpreadC
        TemperatureUnit.FAHRENHEIT -> summary.feelsLikeSpreadC * 1.8
    }
    Text(
        text = stringResource(
            R.string.today_chart_divergence_summary,
            formatHourMinute(summary.peakHour),
            spreadInUserUnit,
            temperatureUnit.symbol(),
            factorLabel,
            rangeText,
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
}

private fun formatTopFactorRange(
    summary: ModelDivergenceSummary,
    temperatureUnit: TemperatureUnit,
    windSpeedUnit: WindSpeedUnit,
): String = when (summary.topFactor) {
    ModelDivergenceSummary.Factor.AIR_TEMPERATURE -> {
        val min = summary.topFactorMin.toUnit(temperatureUnit).roundToInt()
        val max = summary.topFactorMax.toUnit(temperatureUnit).roundToInt()
        "$min–$max${temperatureUnit.symbol()}"
    }
    ModelDivergenceSummary.Factor.WIND_SPEED -> {
        val min = summary.topFactorMin.toWindSpeedUnit(windSpeedUnit).roundToInt()
        val max = summary.topFactorMax.toWindSpeedUnit(windSpeedUnit).roundToInt()
        "$min–$max ${windSpeedUnit.symbol()}"
    }
    ModelDivergenceSummary.Factor.CLOUD_COVER,
    ModelDivergenceSummary.Factor.RELATIVE_HUMIDITY ->
        "${summary.topFactorMin.roundToInt()}–${summary.topFactorMax.roundToInt()}%"
}

/**
 * Diagnostic wind-speed card. Renders a per-hour consensus main line drawn
 * from the cross-model mean (the temp / precip cards' "main line" pattern,
 * applied uniformly to every diagnostic chart); when [showModelSpread] is
 * on, the per-model lines also draw underneath. The clothes-recommendation
 * pipeline doesn't read wind directly (feels-like already folds in wind
 * chill), so this is purely a "what's the day's wind look like, and where
 * do the models disagree?" surface.
 *
 * Auto-hides when every consulted model is missing wind data outright (older
 * cached payloads). When some hours are present, sparse-handling in
 * [PerModelDiagnosticCard] bridges the gaps for both the main line and the
 * overlay rather than dropping the chart entirely.
 */
@Composable
internal fun WindCard(
    hourly: List<HourlyForecast>,
    perModelHourly: PerModelHourly,
    windSpeedUnit: WindSpeedUnit = WindSpeedUnit.KMH,
    startDate: java.time.LocalDate = java.time.LocalDate.now(),
    showModelSpread: Boolean = false,
) {
    val times = remember(hourly) { hourly.map { it.time } }
    // 10 km/h floor on the y-range so a near-still day doesn't get zoomed
    // into noise — same reasoning as ForecastChart.MIN_Y_SPAN. Express the
    // floor in the user's unit so the heuristic stays equivalent (10 km/h
    // ≈ 6.2 mph) instead of shrinking to a tighter span on imperial.
    val minSpan = 10.0.toWindSpeedUnit(windSpeedUnit)
    val peak = remember(perModelHourly, windSpeedUnit, times) {
        perModelConsensusSeries(perModelHourly) {
            it.windSpeedKmh?.toWindSpeedUnit(windSpeedUnit)
        }
            .maxByOrNull { it.second }
            ?.let { (idx, value) ->
                times.getOrNull(idx)?.let { time -> time to value }
            }
    }
    val subtitle = peak?.let { (time, value) ->
        stringResource(
            R.string.today_wind_peak,
            value.roundToInt(),
            windSpeedUnit.symbol(),
            formatScrubHour(time),
        )
    } ?: stringResource(R.string.today_wind_subtitle, windSpeedUnit.symbol())
    val unitSymbol = windSpeedUnit.symbol()
    PerModelDiagnosticCard(
        title = stringResource(R.string.today_wind_title),
        subtitle = subtitle,
        hourly = hourly,
        startDate = startDate,
        perModelHourly = perModelHourly,
        picker = { it.windSpeedKmh?.toWindSpeedUnit(windSpeedUnit) },
        yAxis = YAxis.AutoZeroBased(minSpan = minSpan),
        tooltipValueFormat = { "${it.roundToInt()} $unitSymbol" },
        // Picker closes over windSpeedUnit; key the series cache on it so the
        // chart values follow when the user flips distance unit while the
        // overlay payload is unchanged.
        pickerKey = windSpeedUnit,
        showOverlay = showModelSpread,
    )
}

/**
 * Diagnostic cloud-cover card. Same scaffolding as [WindCard]: consensus
 * main line by default, per-model overlay underneath when [showModelSpread]
 * is on. Cloud divergence is the upstream cause of most mid-day air-temp
 * disagreements between models (one predicts a clearing, the other doesn't),
 * so this is the most useful follow-up to the wind diagnostic.
 */
@Composable
internal fun CloudCard(
    hourly: List<HourlyForecast>,
    perModelHourly: PerModelHourly,
    startDate: java.time.LocalDate = java.time.LocalDate.now(),
    showModelSpread: Boolean = false,
) {
    val range = remember(perModelHourly) {
        perModelConsensusRange(perModelHourly) { it.cloudCoverPct }
    }
    val subtitle = range?.let {
        stringResource(R.string.today_cloud_range, it.first, it.second)
    } ?: stringResource(R.string.today_cloud_subtitle)
    PerModelDiagnosticCard(
        title = stringResource(R.string.today_cloud_title),
        subtitle = subtitle,
        hourly = hourly,
        startDate = startDate,
        perModelHourly = perModelHourly,
        picker = { it.cloudCoverPct },
        yAxis = YAxis.Percent,
        tooltipValueFormat = { "${it.roundToInt()}%" },
        showOverlay = showModelSpread,
    )
}

/**
 * Diagnostic relative-humidity card. Same gating + scaffolding as the wind /
 * cloud cards. Humidity has low signal at the cool temperatures Europe sees
 * most of the year (apparent-temperature's humidity term only kicks in above
 * ~20 °C) but the data ride the same Open-Meteo call for free, so we surface
 * it for the warmer days where it does drive feels-like.
 */
@Composable
internal fun HumidityCard(
    hourly: List<HourlyForecast>,
    perModelHourly: PerModelHourly,
    startDate: java.time.LocalDate = java.time.LocalDate.now(),
    showModelSpread: Boolean = false,
) {
    val range = remember(perModelHourly) {
        perModelConsensusRange(perModelHourly) { it.relativeHumidityPct }
    }
    val subtitle = range?.let {
        stringResource(R.string.today_humidity_range, it.first, it.second)
    } ?: stringResource(R.string.today_humidity_subtitle)
    PerModelDiagnosticCard(
        title = stringResource(R.string.today_humidity_title),
        subtitle = subtitle,
        hourly = hourly,
        startDate = startDate,
        perModelHourly = perModelHourly,
        picker = { it.relativeHumidityPct },
        yAxis = YAxis.Percent,
        tooltipValueFormat = { "${it.roundToInt()}%" },
        showOverlay = showModelSpread,
    )
}

/**
 * Diagnostic shortwave-radiation card — surface solar irradiance (W/m²) per
 * model and hour. Already bakes in cloud attenuation, so it captures
 * "cloudy but bright" days more honestly than cloud cover alone. Same gating
 * as the other diagnostic cards: only renders when the per-model overlay is
 * active and at least one consulted model returned a shortwave series.
 */
@Composable
internal fun SolarRadiationCard(
    hourly: List<HourlyForecast>,
    perModelHourly: PerModelHourly,
    startDate: java.time.LocalDate = java.time.LocalDate.now(),
    showModelSpread: Boolean = false,
) {
    val times = remember(hourly) { hourly.map { it.time } }
    // Peak irradiance across the cross-model consensus series — same blend
    // the chart draws. Suppressed when the rounded peak is below 10 W/m²
    // (night view, deep-polar winter) so the subtitle doesn't read
    // "Peak 3 W/m² at 12:00".
    val peak = remember(perModelHourly, times) {
        perModelConsensusSeries(perModelHourly) { it.shortwaveRadiationWm2 }
            .maxByOrNull { it.second }
            ?.takeIf { it.second.roundToInt() >= 10 }
            ?.let { (idx, value) ->
                times.getOrNull(idx)?.let { time -> time to value }
            }
    }
    val subtitle = peak?.let { (time, value) ->
        stringResource(
            R.string.today_solar_peak,
            value.roundToInt(),
            formatScrubHour(time),
        )
    } ?: stringResource(R.string.today_solar_subtitle)
    PerModelDiagnosticCard(
        title = stringResource(R.string.today_solar_title),
        subtitle = subtitle,
        hourly = hourly,
        startDate = startDate,
        perModelHourly = perModelHourly,
        picker = { it.shortwaveRadiationWm2 },
        // A typical clear summer noon peaks ~900 W/m²; the floor keeps a deep
        // overcast day's near-zero series from collapsing into a flat line on
        // top of the x-axis.
        yAxis = YAxis.AutoZeroBased(minSpan = 100.0),
        tooltipValueFormat = { "${it.roundToInt()} W/m²" },
        showOverlay = showModelSpread,
    )
}

/**
 * Diagnostic sunshine-duration card with a consensus daily-total blurb on
 * top — the single "Xh Ym of sun today" number averaged across consulted
 * models, paired with the per-model minutes-per-hour breakdown below. Same
 * gating as the other diagnostic cards.
 *
 * Open-Meteo reports sunshine in seconds per hour (0..3600). The chart
 * displays minutes per hour for readability — "0..60" reads more naturally
 * than "0..3600" and lines up with the way users think about sunshine.
 */
@Composable
internal fun SunshineCard(
    hourly: List<HourlyForecast>,
    perModelHourly: PerModelHourly,
    forDate: java.time.LocalDate,
    period: ForecastPeriod = ForecastPeriod.TODAY,
    showModelSpread: Boolean = false,
) {
    // TONIGHT's slice spans [tonightStart, next morning) and so straddles
    // midnight; the per-date filter would drop pre-alarm tomorrow-morning sun
    // and the "Xh of sun tonight" total would be short. Use the window-total
    // variant for TONIGHT and keep the date-filtered call for TODAY (whose
    // slice is single-date by construction).
    val totalHours = remember(perModelHourly, forDate, period) {
        when (period) {
            ForecastPeriod.TODAY -> perModelHourly.consensusSunshineHoursFor(forDate)
            ForecastPeriod.TONIGHT -> perModelHourly.consensusSunshineHours()
        }
    }
    val totalBlurb = if (totalHours != null) {
        formatSunshineTotal(totalHours, period)
    } else {
        stringResource(R.string.today_sunshine_subtitle)
    }
    PerModelDiagnosticCard(
        title = stringResource(R.string.today_sunshine_title),
        subtitle = totalBlurb,
        hourly = hourly,
        startDate = forDate,
        // Seconds → minutes so the y-axis reads 0..60 instead of 0..3600.
        picker = { it.sunshineDurationSec?.div(60.0) },
        perModelHourly = perModelHourly,
        yAxis = YAxis.AutoZeroBased(minSpan = 60.0),
        tooltipValueFormat = { "${it.roundToInt()} min" },
        showOverlay = showModelSpread,
    )
}

@Composable
private fun formatSunshineTotal(hours: Double, period: ForecastPeriod): String {
    val totalMinutes = (hours * 60.0).roundToInt()
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    val res = when (period) {
        ForecastPeriod.TODAY -> Triple(
            R.string.today_sunshine_total_minutes_only,
            R.string.today_sunshine_total_hours_only,
            R.string.today_sunshine_total_hours_minutes,
        )
        ForecastPeriod.TONIGHT -> Triple(
            R.string.today_sunshine_total_minutes_only_tonight,
            R.string.today_sunshine_total_hours_only_tonight,
            R.string.today_sunshine_total_hours_minutes_tonight,
        )
    }
    return when {
        h == 0 -> stringResource(res.first, m)
        m == 0 -> stringResource(res.second, h)
        else -> stringResource(res.third, h, m)
    }
}

/**
 * Diagnostic UV-index card. Cheap actionable signal — hat / sunscreen /
 * sunglasses — even when the temperature itself doesn't trigger anything.
 * Same gating as the other diagnostic cards.
 */
@Composable
internal fun UvIndexCard(
    hourly: List<HourlyForecast>,
    perModelHourly: PerModelHourly,
    startDate: java.time.LocalDate = java.time.LocalDate.now(),
    showModelSpread: Boolean = false,
) {
    val times = remember(hourly) { hourly.map { it.time } }
    // Peak UV across the cross-model consensus series — same blend the chart
    // draws. Suppressed when the rounded peak is below 1 (night view, deep
    // winter) so the subtitle doesn't read "Peak 0 at 21:00".
    val peak = remember(perModelHourly, times) {
        perModelConsensusSeries(perModelHourly) { it.uvIndex }
            .maxByOrNull { it.second }
            ?.takeIf { it.second.roundToInt() >= 1 }
            ?.let { (idx, value) ->
                times.getOrNull(idx)?.let { time -> time to value }
            }
    }
    val subtitle = peak?.let { (time, value) ->
        stringResource(
            R.string.today_uv_peak,
            value.roundToInt(),
            formatScrubHour(time),
        )
    } ?: stringResource(R.string.today_uv_subtitle)
    PerModelDiagnosticCard(
        title = stringResource(R.string.today_uv_title),
        subtitle = subtitle,
        hourly = hourly,
        startDate = startDate,
        perModelHourly = perModelHourly,
        picker = { it.uvIndex },
        // UV peaks around 11–12 in the tropics on summer solstice; the floor
        // keeps a winter-morning all-zero series from collapsing onto the
        // axis. niceStep gives "0, 2, 4, 6" for typical 0..6 days.
        yAxis = YAxis.AutoZeroBased(minSpan = 6.0),
        // No "UV" prefix — the card's own subtitle ("Peak X at HH:00")
        // omits it for the same reason: the card title already names
        // the metric, and the readout sits right next to that subtitle.
        tooltipValueFormat = { "${it.roundToInt()}" },
        showOverlay = showModelSpread,
    )
}

@Composable
internal fun PrecipitationCard(
    hourly: List<HourlyForecast>,
    startDate: java.time.LocalDate = java.time.LocalDate.now(),
    perModelHourly: PerModelHourly? = null,
    showModelSpread: Boolean = false,
) {
    // Always render the chart, even on dry days — keeps the card height stable
    // across days so the cards below don't shift, and the flat baseline is its
    // own kind of information ("nothing coming"). The summary line above the
    // chart switches between a peak callout and a "no rain" message so the
    // chart itself is just the visualisation, not the only signal.
    val peak = remember(hourly) { hourly.maxByOrNull { it.precipitationProbabilityPct } }
    val isDry = peak == null || peak.precipitationProbabilityPct < DRY_THRESHOLD_PCT
    val scrubController = LocalChartScrub.current
    val subtitleText = if (isDry || peak == null) {
        stringResource(R.string.today_precipitation_dry)
    } else {
        stringResource(
            R.string.today_precipitation_peak,
            peak.precipitationProbabilityPct.roundToInt(),
            formatScrubHour(peak.time),
        )
    }
    val readout = rememberChartReadout(hourly, startDate) { idx, moment ->
        val entry = hourly[idx]
        stringResource(
            R.string.today_chart_readout,
            "${entry.precipitationProbabilityPct.roundToInt()}%",
            formatScrubMoment(moment),
        )
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Box {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.today_precipitation_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                ChartSubtitleRow(subtitle = subtitleText, readout = readout)
                PrecipitationChart(
                    hourly = hourly,
                    startDate = startDate,
                    perModelHourly = perModelHourly,
                    showModelSpread = showModelSpread,
                )
                if (perModelHourly != null) {
                    ModelSpreadLegend(
                        visibleModelIds = if (showModelSpread) MODEL_DRAW_ORDER.filter { it in perModelHourly.byModel } else emptyList(),
                        mainLine = MainLineLegend(
                            color = AppTheme.mainLineColor,
                            label = stringResource(R.string.today_chart_main_line_label),
                        ),
                    )
                }
            }
            if (scrubController != null) ChartRestoreOverlay(scrubController)
        }
    }
}

/**
 * Sibling of [PrecipitationCard] that surfaces *how much* rain (mm) per
 * hour rather than chance-of-rain (%). The probability card answers "will
 * it rain?" — this one answers "is it drizzle or a downpour?", which
 * matters most on days where the probability is high but the question of
 * "umbrella or full waterproof" depends on the amount.
 *
 * Same scaffolding as [PrecipitationCard]: always renders (even on dry
 * days — the flat baseline is its own information and keeps the card
 * column from re-shifting) and exposes the same tap-to-reveal per-model
 * overlay via the shared [showModelSpread] flag. The subtitle surfaces
 * the cumulative daily total ("4.2 mm of rain today") rather than the
 * peak hour — same design as [SunshineCard]'s "Xh of sun today" blurb so
 * the two summary lines read consistently. Below the dry threshold the
 * subtitle switches to "No rainfall expected today" (or tonight).
 *
 * Total is the sum of the [consensusRainfallMainLine] series so the
 * subtitle figure matches exactly what the "Combined" chart line implies
 * over the same window — with no risk of the two diverging when a model
 * has partial hourly coverage.
 */
@Composable
internal fun PrecipitationAmountCard(
    hourly: List<HourlyForecast>,
    forDate: java.time.LocalDate = java.time.LocalDate.now(),
    period: ForecastPeriod = ForecastPeriod.TODAY,
    perModelHourly: PerModelHourly? = null,
    showModelSpread: Boolean = false,
) {
    // Same series the chart plots for its "Combined" main line — sourced
    // from the consensus mean per hour when per-model data is available,
    // best-match otherwise. Indexed by hour so the scrub readout above the
    // chart can read off the same value the line shows at that hour
    // instead of always reading best-match (which would surface a
    // contradicting number when the consensus diverges).
    val mainLine = remember(hourly, perModelHourly) {
        consensusRainfallMainLine(hourly, perModelHourly)
    }
    val totalMm = remember(mainLine) { mainLine.sum() }
    val isDry = totalMm < DRY_TOTAL_THRESHOLD_MM
    val scrubController = LocalChartScrub.current
    val subtitleText = if (isDry) {
        stringResource(
            when (period) {
                ForecastPeriod.TODAY -> R.string.today_precipitation_amount_dry_today
                ForecastPeriod.TONIGHT -> R.string.today_precipitation_amount_dry_tonight
            },
        )
    } else {
        stringResource(
            when (period) {
                ForecastPeriod.TODAY -> R.string.today_precipitation_amount_total_today
                ForecastPeriod.TONIGHT -> R.string.today_precipitation_amount_total_tonight
            },
            formatPrecipitationMmAxis(totalMm),
        )
    }
    val readout = rememberChartReadout(hourly, forDate) { idx, moment ->
        val value = mainLine.getOrNull(idx) ?: return@rememberChartReadout null
        stringResource(
            R.string.today_chart_readout,
            formatPrecipitationMmAxis(value),
            formatScrubMoment(moment),
        )
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Box {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.today_precipitation_amount_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                ChartSubtitleRow(subtitle = subtitleText, readout = readout)
                PrecipitationAmountChart(
                    hourly = hourly,
                    startDate = forDate,
                    perModelHourly = perModelHourly,
                    showModelSpread = showModelSpread,
                )
                if (perModelHourly != null) {
                    // Mirror the chart's per-model visibility filter — list
                    // only the models that actually have a precipitation_mm
                    // line plotted, not every model in byModel. Without
                    // this filter, models whose Open-Meteo response omitted
                    // `precipitation_<model>` (UKMO, JMA, …) show up as
                    // legend chips with no corresponding line on the chart.
                    val visibleIds = if (showModelSpread) {
                        MODEL_DRAW_ORDER.filter { modelId ->
                            perModelHourly.byModel[modelId]?.any { it.precipitationMm != null } == true
                        }
                    } else emptyList()
                    ModelSpreadLegend(
                        visibleModelIds = visibleIds,
                        mainLine = MainLineLegend(
                            color = AppTheme.mainLineColor,
                            label = stringResource(R.string.today_chart_main_line_label),
                        ),
                    )
                }
            }
            if (scrubController != null) ChartRestoreOverlay(scrubController)
        }
    }
}

/**
 * Compact "Models: ● Combined ● ECMWF ● GFS ● ICON · ● Best match" footer
 * rendered under the charts. The optional [mainLine] entry (theme primary)
 * comes first so its position in the legend is the same in the single and
 * per-model views — i.e. when the overlay is off and the legend has only
 * "Combined" in it, that chip sits where it sits in the spread view too.
 * Each consulted model then follows in its pinned hue from the active
 * [app.clothescast.ui.theme.AppPalette]. The main line on the temperature
 * and precipitation charts comes from Open-Meteo's automatic-model-selection
 * ("best match") default and routinely tracks a different model than the
 * consulted overlays — surfacing it lets the user map every line on the
 * chart back to its source.
 *
 * Callers pass the exact set of model ids actually plotted in
 * [visibleModelIds] (pre-refactor the legend derived this from `byModel`
 * directly, which silently listed models whose lines had been filtered out of
 * a chart — e.g. wind diagnostic lines for models that didn't report wind).
 * Pass [mainLine] when the chart has a blended main line alongside the
 * overlays (temp + rain cards); the diagnostic cards leave it null.
 */
@Composable
internal fun ModelSpreadLegend(
    visibleModelIds: List<String>,
    mainLine: MainLineLegend? = null,
) {
    if (visibleModelIds.isEmpty() && mainLine == null) return
    val labelStyle = MaterialTheme.typography.bodySmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val modelColors = AppTheme.palette.modelColors
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.today_chart_model_legend_label),
            style = labelStyle,
            color = labelColor,
        )
        mainLine?.let {
            LegendChip(
                color = it.color,
                label = it.label,
                style = labelStyle,
                textColor = labelColor,
            )
        }
        visibleModelIds.forEach { modelId ->
            LegendChip(
                color = modelColors.getValue(modelId),
                label = friendlyModelName(modelId),
                style = labelStyle,
                textColor = labelColor,
            )
        }
    }
}

/** Optional main-line entry rendered before the per-model entries in [ModelSpreadLegend]. */
internal data class MainLineLegend(val color: Color, val label: String)

@Composable
private fun LegendChip(
    color: Color,
    label: String,
    style: androidx.compose.ui.text.TextStyle,
    textColor: Color,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Text(text = label, style = style, color = textColor)
    }
}

private fun friendlyModelName(modelId: String): String = when (modelId) {
    "ecmwf_ifs04" -> "ECMWF"
    "gfs_seamless" -> "GFS"
    "icon_seamless" -> "ICON"
    "gem_seamless" -> "GEM"
    "meteofrance_seamless" -> "ARPEGE"
    "ukmo_seamless" -> "UKMO"
    "jma_seamless" -> "JMA"
    PerModelHourly.BEST_MATCH_MODEL_ID -> "Auto"
    else -> modelId
}

// Open-Meteo rounds probability to whole percents and returns 1–3% peaks on
// objectively dry days; treating anything under 5% as "no rain" suppresses
// the misleading "Peak 2% at 03:00" callout while still surfacing genuine
// drizzle-grade chances at 5%+.
private const val DRY_THRESHOLD_PCT = 5.0

// Dry threshold for the hourly-rainfall card, applied to the day's
// cumulative total (mm). 0.1 mm is the typical "trace" tick across weather
// services — a day that totals less than that is dry by any practical
// measure. Set independently of [DRY_THRESHOLD_PCT] because the probability
// and amount summaries answer different questions: 70% chance of 0.05 mm
// across the day still rounds to "no rainfall expected today" here, even
// though the probability card surfaces the peak.
private const val DRY_TOTAL_THRESHOLD_MM = 0.1

private fun formatMinMax(values: List<Double>, unit: TemperatureUnit): Pair<Int, Int>? {
    if (values.isEmpty()) return null
    val converted = values.map { it.toUnit(unit) }
    return converted.min().roundToInt() to converted.max().roundToInt()
}

// Per-hour mean of [picker] across whichever models reported at that hour —
// the same blend used for the diagnostic charts' main consensus line, lifted
// out so the card subtitles can summarise the same series the chart draws.
// Returns (originalIndex, mean) pairs sorted by index; empty when no model
// has data for the metric.
private fun perModelConsensusSeries(
    perModelHourly: PerModelHourly,
    picker: (PerModelHour) -> Double?,
): List<Pair<Int, Double>> {
    val byIndex = mutableMapOf<Int, MutableList<Double>>()
    perModelHourly.byModel.values.forEach { entries ->
        entries.forEachIndexed { i, e ->
            picker(e)?.let { byIndex.getOrPut(i) { mutableListOf() } += it }
        }
    }
    return byIndex.entries.sortedBy { it.key }.map { (idx, vs) -> idx to vs.average() }
}

private fun perModelConsensusRange(
    perModelHourly: PerModelHourly,
    picker: (PerModelHour) -> Double?,
): Pair<Int, Int>? {
    val values = perModelConsensusSeries(perModelHourly, picker).map { it.second }
    if (values.isEmpty()) return null
    return values.min().roundToInt() to values.max().roundToInt()
}

private fun triggerRefresh(
    context: android.content.Context,
    morningTime: java.time.LocalTime,
    tonightTime: java.time.LocalTime,
) {
    // silent=true so Refresh updates the on-screen Today card but skips the
    // delivery fan-out (notification, TTS, MQTT, cast) — the user is already
    // in the app looking at it. The top progress banner is the only feedback,
    // so there's no toast either. Silent refreshes bypass the same-day cache
    // and the daily/tonight enable gates, so an explicit tap always re-fetches.
    //
    // The period here only selects which work queue the Today screen observes
    // for the spinner (TODAY vs TONIGHT); the worker re-derives the actual
    // window from wall-clock at run time. Boundaries come from the user's
    // schedule (prefs.schedule.time / prefs.tonightSchedule.time) so a
    // customised schedule doesn't desync from the manual refresh.
    val period = if (java.time.LocalTime.now().isInTonightWindow(morningTime, tonightTime)) {
        ForecastPeriod.TONIGHT
    } else {
        ForecastPeriod.TODAY
    }
    FetchAndNotifyWorker.enqueueOneShot(context.applicationContext, silent = true, period = period)
}

private fun triggerPlay(context: android.content.Context, period: ForecastPeriod) {
    // Replay against the cached snapshot's period (not wall-clock) so a
    // morning insight still in the cache at noon replays as TODAY, and a
    // tonight insight viewed before its tonight-window starts replays as
    // TONIGHT. The unique work name is keyed on this so a Refresh and a
    // Play can't run concurrently for the same slot.
    FetchAndNotifyWorker.enqueueReplay(context.applicationContext, period)
    val toastRes = when (period) {
        ForecastPeriod.TODAY -> R.string.today_play_toast_daily
        ForecastPeriod.TONIGHT -> R.string.today_play_toast_nightly
    }
    Toast.makeText(context, context.getString(toastRes), Toast.LENGTH_SHORT).show()
}

// [tonightTime] inclusive through [morningTime] exclusive — wraps midnight when
// tonightTime > morningTime (the normal case). When the user has crossed them
// (a tonight time earlier than morning, e.g. 06:30 / 07:00) the predicate
// degenerates to the in-between sliver, which is fine: the user's two slots
// effectively touch and either side of the line is reasonable.
private fun java.time.LocalTime.isInTonightWindow(
    morningTime: java.time.LocalTime,
    tonightTime: java.time.LocalTime,
): Boolean = if (tonightTime > morningTime) {
    this >= tonightTime || this < morningTime
} else {
    this >= tonightTime && this < morningTime
}

// Trim a forward-geocoded "Boston, Massachusetts, United States" down to the
// city for the home view's date row. Returns null when there's no friendly
// name to show (null/blank input, or the LocationResolver placeholder string
// that means "device location with no real city resolved") — the UI then
// renders date-only with no separator.
internal fun shortLocationLabel(displayName: String?): String? {
    if (displayName.isNullOrBlank()) return null
    if (displayName == "Device location") return null
    return displayName.substringBefore(',').trim().takeIf { it.isNotBlank() }
}

// Hand the user's chosen maps app a `geo:` URI with a search query so the pin
// drops on the actual GPS coords (not the geocoder's centroid for the labelled
// place). Silently no-ops when no maps app is installed — there's no good
// recovery and the rest of the screen still works.
internal fun openInMaps(context: Context, latitude: Double, longitude: Double, label: String?) {
    val labelPart = label?.takeIf { it.isNotBlank() }
        ?.let { "(${Uri.encode(it)})" }
        .orEmpty()
    val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude$labelPart")
    val intent = Intent(Intent.ACTION_VIEW, uri)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No maps app installed; nothing useful to do.
    }
}

