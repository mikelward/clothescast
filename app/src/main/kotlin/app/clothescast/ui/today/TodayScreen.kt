package app.clothescast.ui.today

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import app.clothescast.ui.isTelevision
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
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
import app.clothescast.core.domain.model.HomeSection
import app.clothescast.core.domain.model.bannerTextKeyFor
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.OutfitRationale
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.ModelDivergenceSummary
import app.clothescast.core.domain.model.PerModelHour
import app.clothescast.core.domain.model.PerModelHourly
import app.clothescast.core.domain.model.WeatherCondition
import app.clothescast.core.domain.model.consensusSunshineHours
import app.clothescast.core.domain.model.consensusSunshineHoursFor
import app.clothescast.core.domain.model.BottomsFormat
import app.clothescast.core.domain.model.ClothesFormat
import app.clothescast.core.domain.model.PreambleVisibility
import app.clothescast.core.domain.model.RangeFormat
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.WindSpeedUnit
import app.clothescast.core.domain.model.symbol
import app.clothescast.core.domain.model.thresholdC
import app.clothescast.core.domain.model.toUnit
import app.clothescast.core.domain.model.toWindSpeedUnit
import app.clothescast.core.domain.model.windSpeedUnit
import app.clothescast.tts.toJavaLocale
import app.clothescast.ui.BugReportOverflowMenu
import app.clothescast.ui.EdgeFadeOverlay
import app.clothescast.ui.LocalTimeFormat
import app.clothescast.ui.StopSquareIcon
import app.clothescast.ui.formatHourMinute
import app.clothescast.ui.formatScrubHour
import app.clothescast.ui.garment.GarmentBottomIcon
import app.clothescast.ui.garment.GarmentCarriedIcon
import app.clothescast.ui.garment.GarmentOuterIcon
import app.clothescast.ui.garment.GarmentHandsIcon
import app.clothescast.ui.garment.GarmentTopIcon
import app.clothescast.ui.garment.OutfitCardInfoLines
import app.clothescast.ui.garment.conditionsCells
import app.clothescast.ui.garment.outfitCardInfoLines
import app.clothescast.ui.garment.outfitGarmentCaption
import app.clothescast.ui.garment.renderConditionsStripBitmap
import app.clothescast.diag.DiagLog
import app.clothescast.insight.InsightFormatter
import app.clothescast.location.hasBackgroundLocationPermission
import app.clothescast.location.hasCoarseLocationPermission
import app.clothescast.ui.settings.NotificationPermissionBanner
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
    onNavigateToSchedule: () -> Unit = onNavigateToSettings,
    onNavigateToDeveloper: () -> Unit = onNavigateToSettings,
    onNavigateToFormat: () -> Unit = onNavigateToSettings,
    onNavigateToVoice: () -> Unit = onNavigateToSettings,
    // Pager page to open on, from the Today deep link's `?page=` query. The
    // feels-like home-screen widgets deep-link to page 0 (current period) and
    // page 2 (7-day). rememberPagerState only reads this on first composition,
    // so a config change keeps the user's swiped-to page rather than yanking
    // back here.
    startPage: Int = 0,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // Both Running (fresh enqueue) and Retrying (post-failure backoff) suppress
    // Refresh — the worker still bills a Gemini call on resumption, and a tap
    // would REPLACE the in-flight retry chain. The banner copy distinguishes them.
    val isWorking = state.workStatus is WorkStatus.Running ||
        state.workStatus is WorkStatus.Retrying

    // Hoisted out of TodayContent so the TopAppBar title can swap with the
    // visible page — page 0 is the user's current 12-hour window ("Today" or
    // "Tonight"), page 1 is the next window ("Tonight" or "Tomorrow"),
    // page 2 is the 7-day outlook (days 1-7), page 3 is the following week
    // (days 8-14). Page count is constant; when thisPeriodInsight is null the
    // pager doesn't render but the state is harmlessly retained at page 0.
    val pagerState = rememberPagerState(initialPage = startPage.coerceIn(0, 3)) { 4 }
    val titleRes = topBarTitleRes(
        period = state.thisPeriodInsight?.period,
        page = pagerState.currentPage,
        overnight = state.thisPeriodInsight?.summary?.overnight == true,
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
                    // Play delivers the current window's insight through the
                    // full deliver() pipeline — notification, phone-speaker
                    // TTS, MQTT publish, cast. It replays a fresh cached
                    // snapshot when one exists, else fetches fresh, so it's
                    // enabled whenever idle even with an empty cache (matching
                    // Refresh). While a delivery is in flight ([anyWorkActive],
                    // broader than [isWorking] so it stays true through the
                    // post-fetch TTS / MQTT / Cast window the spinner-banner
                    // logic treats as Idle) the button flips to a Stop control
                    // that cancels the active delivery — whether it's this
                    // Play, a Refresh, or a scheduled run mid-announcement — so
                    // the user can cut a long cast short instead of waiting it
                    // out. Cancelling stops the TTS playback (see
                    // [FetchAndNotifyWorker.cancelDelivery]).
                    if (state.anyWorkActive) {
                        IconButton(onClick = { FetchAndNotifyWorker.cancelDelivery(context) }) {
                            Icon(
                                imageVector = StopSquareIcon,
                                contentDescription = stringResource(R.string.today_stop),
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                // "Play" means the *current* cast, so derive the
                                // window from the wall clock at tap time — not from
                                // the cached page-0 insight's period. If the screen
                                // sits open across the daily/nightly boundary that
                                // cached period goes stale (still TODAY) while the
                                // user now wants the nightly cast; the worker also
                                // keys its next-occurrence logic off the current
                                // window, so handing it the stale TODAY would make it
                                // play tomorrow's daytime instead of tonight. Uses
                                // the same window check Refresh does.
                                val playPeriod =
                                    if (LocalTime.now().isInTonightWindow(state.morningTime, state.tonightTime)) {
                                        ForecastPeriod.TONIGHT
                                    } else {
                                        ForecastPeriod.TODAY
                                    }
                                triggerPlay(context, playPeriod)
                                // Using Play is the user acting on the "Preview your
                                // ClothesCast" promo, so retire it — they've found
                                // the button it points at. Require a delivered
                                // forecast (thisPeriodInsight != null), matching the
                                // hasForecast gate promoBannersToShow uses to render
                                // the card: otherwise a fresh install (daily on by
                                // default) tapping Play before any forecast exists
                                // would persist the dismissal and the promo would
                                // never get its chance to show.
                                if (state.playPromoCardVisible && state.thisPeriodInsight != null) {
                                    viewModel.dismissPlayPromoCard()
                                }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.today_play),
                            )
                        }
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.today_open_settings),
                        )
                    }
                    BugReportOverflowMenu(onNavigateToAbout = onNavigateToAbout)
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
            onDismissMqttError = viewModel::dismissMqttPublishError,
            onDismissCastError = viewModel::dismissCastPublishError,
            onOpenPrivacy = onNavigateToPrivacy,
            onOpenCalendarSettings = onNavigateToCalendar,
            onOpenSchedule = {
                // Following the CTA retires the promo too — see
                // [TodayViewModel.dismissSchedulePromoCard].
                viewModel.dismissSchedulePromoCard()
                onNavigateToSchedule()
            },
            onDismissCelebrationCard = viewModel::dismissCelebrationCard,
            onDismissClothesPromoCard = viewModel::dismissClothesPromoCard,
            onDismissSchedulePromoCard = viewModel::dismissSchedulePromoCard,
            onDismissPlayPromoCard = viewModel::dismissPlayPromoCard,
            onOpenVoice = onNavigateToVoice,
            onDismissGeminiTtsPromoCard = viewModel::dismissGeminiTtsPromoCard,
            onDismissGeminiTtsLimitCard = viewModel::dismissGeminiTtsLimitCard,
            onCalendarPermissionChanged = viewModel::notifyCalendarPermissionChanged,
            onNavigateToClothes = onNavigateToClothes,
            onNavigateToFormat = onNavigateToFormat,
            onToggleModelSpread = viewModel::toggleModelSpread,
            onRevealModelSpread = viewModel::revealModelSpread,
            onHideModelSpread = viewModel::hideModelSpread,
            onLongPressDate = onNavigateToDeveloper,
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
    onDismissMqttError: () -> Unit,
    onDismissCastError: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenCalendarSettings: () -> Unit,
    onOpenSchedule: () -> Unit,
    onDismissCelebrationCard: () -> Unit,
    onDismissClothesPromoCard: () -> Unit,
    onDismissSchedulePromoCard: () -> Unit,
    onDismissPlayPromoCard: () -> Unit,
    onOpenVoice: () -> Unit,
    onDismissGeminiTtsPromoCard: () -> Unit,
    onDismissGeminiTtsLimitCard: () -> Unit,
    onCalendarPermissionChanged: () -> Unit,
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
                    onOpenSchedule = onOpenSchedule,
                    onDismissSchedulePromoCard = onDismissSchedulePromoCard,
                    onDismissPlayPromoCard = onDismissPlayPromoCard,
                    onOpenVoice = onOpenVoice,
                    onDismissGeminiTtsPromoCard = onDismissGeminiTtsPromoCard,
                    onDismissGeminiTtsLimitCard = onDismissGeminiTtsLimitCard,
                    onDismissMqttError = onDismissMqttError,
                    onDismissCastError = onDismissCastError,
                    onOpenCalendarSettings = onOpenCalendarSettings,
                    onDismissCelebrationCard = onDismissCelebrationCard,
                    onSetUpLocation = onSetUpLocation,
                )
                EmptyState(
                    onRefresh = onRefresh,
                    isWorking = isWorking,
                    locationActionRequired = locationActionRequired,
                    onSetUpLocation = onSetUpLocation,
                )
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
                // Pre-compose the neighbouring pages so the swipe is pure
                // translation of already-laid-out content. The 7-day page
                // (page 2) carries six Vico chart cards plus the per-model
                // diagnostic deck; composing all that on the frame the user's
                // finger starts moving is the bulk of the perceived jank.
                // 1 covers 0↔1 and 1↔2 — the common swipe paths — without
                // paying for page 2's composition on a cold open to page 0.
                beyondViewportPageCount = 1,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { page ->
                if (page == 2 || page == 3) {
                    val currentDay = state.thisPeriodInsight.currentDay
                    val upcoming = state.thisPeriodInsight.upcomingDays
                    val isFollowingWeek = page == 3
                    // Page 2 ("Next 7 days") plots today + days 2-7 so the chart
                    // starts on the user's current day. Page 3 ("Following 7
                    // days") plots days 8-14 — upcomingDays[0] is tomorrow
                    // (day 2), so day 8 is upcomingDays[6] onward. Either page
                    // collapses to a stand-in message via [SevenDayPage] when
                    // the slice has fewer than two days: legacy cached insights
                    // that predate the field, or a 7-day cache not yet widened
                    // to 14 days (which leaves page 3 empty until the next
                    // fetch). The diagnostic cards (wind / humidity / cloud /
                    // solar / UV / sunshine) and model-spread overlays ride on
                    // [weekPerModelHourly]; they auto-hide for any day a model
                    // doesn't reach — which on page 3 is routine, since ICON
                    // stops at day 7 and ECMWF coarsens past ~day 6.
                    val weekDays = if (isFollowingWeek) {
                        upcoming.drop(6)
                    } else {
                        listOfNotNull(currentDay) + upcoming.take(6)
                    }
                    SevenDayPage(
                        days = weekDays,
                        // The second week's headline compares against today (the
                        // user's anchor) rather than against day 8.
                        weekAheadBaseline = if (isFollowingWeek) currentDay else null,
                        titleRes = if (isFollowingWeek) {
                            R.string.today_title_following_week
                        } else {
                            R.string.today_title_week
                        },
                        state = state,
                        weekPerModelHourly = state.thisPeriodInsight.weekPerModelHourly,
                        // Second week only: let the diagnostic deck render even
                        // when models cover just part of days 8-14 (they thin
                        // out past day 7). The near-week page keeps the strict
                        // all-days gate so a stale cache there stays hidden.
                        allowPartialModelCoverage = isFollowingWeek,
                        scrollState = scrollState,
                        workStatusToShow = workStatusToShow,
                        locationActionRequired = locationActionRequired,
                        onChevronTap = {
                            // Left chevron steps back one page (3 → 2, 2 → 1).
                            pagerScope.launch {
                                pagerState.animateScrollToPage(if (isFollowingWeek) 2 else 1)
                            }
                        },
                        // Forward chevron only on page 2, advancing to page 3.
                        onForwardChevronTap = if (isFollowingWeek) {
                            null
                        } else {
                            { pagerScope.launch { pagerState.animateScrollToPage(3) } }
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
                        onNavigateToClothes = onNavigateToClothes,
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
                    onDismissMqttError = onDismissMqttError,
                    onDismissCastError = onDismissCastError,
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
                    onNavigateToClothes = onNavigateToClothes,
                    onNavigateToFormat = onNavigateToFormat,
                    onToggleModelSpread = onToggleModelSpread,
                    onRevealModelSpread = onRevealModelSpread,
                    onHideModelSpread = onHideModelSpread,
                    onLongPressDate = onLongPressDate,
                    onSetUpLocation = onSetUpLocation,
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
                    onDismissMqttError = onDismissMqttError,
                    onDismissCastError = onDismissCastError,
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
    onOpenSchedule: () -> Unit,
    onDismissSchedulePromoCard: () -> Unit,
    onDismissPlayPromoCard: () -> Unit,
    onOpenVoice: () -> Unit,
    onDismissGeminiTtsPromoCard: () -> Unit,
    onDismissGeminiTtsLimitCard: () -> Unit,
    onOpenCalendarSettings: () -> Unit,
    onDismissCelebrationCard: () -> Unit,
    onSetUpLocation: () -> Unit,
    onDismissMqttError: () -> Unit,
    onDismissCastError: () -> Unit,
) {
    val bannerModifier = Modifier.fillMaxWidth()
    // Cap the setup/promo stack so a fresh user isn't buried under "set this
    // up" cards. Only the top [maxVisible] eligible promos render (priority
    // location > privacy > clothes > schedule > play > celebration); the rest
    // wait their turn.
    // The operational banners below (update / build / crash / work / holiday)
    // are unaffected and keep their existing positions.
    val shownPromos = promoBannersToShow(
        locationActionRequired = locationActionRequired,
        telemetryNoticeVisible = state.telemetryNoticeVisible,
        clothesPromoEligible = state.clothesPromoCardVisible,
        schedulePromoEligible = state.schedulePromoCardVisible,
        playPromoEligible = state.playPromoCardVisible,
        geminiPromoEligible = state.geminiTtsPromoCardVisible,
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
    // Scheduled notifications silently won't post until POST_NOTIFICATIONS is
    // granted (Android 13+). Only surfaced when the user has actually enabled a
    // notify-posting schedule — there's nothing to warn about when every
    // schedule is off, SILENT, or TTS-only. The banner renders nothing once the
    // permission is granted or on pre-Android-13, so it's safe to keep mounted.
    if (state.notifyScheduleEnabled) {
        NotificationPermissionBanner(modifier = bannerModifier)
    }
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
    // "Preview your ClothesCast" nudge — let the user hear the cast on demand
    // instead of waiting for the alarm. Gated upstream on a cast slot being
    // enabled (true by default for the morning cast) plus not dismissed. Sits
    // between the clothes promo and the schedule promo. Its Play button mirrors
    // the top-bar one: same window derivation, same play-retires-the-promo
    // behavior.
    val playPromoContext = LocalContext.current
    PlayPromoCard(
        visible = PromoBanner.PLAY in shownPromos,
        enabled = !state.anyWorkActive,
        onPlay = {
            // "Play" means the *current* cast, so derive the window from the wall
            // clock at tap time — same check Refresh and the top-bar Play use.
            val playPeriod =
                if (LocalTime.now().isInTonightWindow(state.morningTime, state.tonightTime)) {
                    ForecastPeriod.TONIGHT
                } else {
                    ForecastPeriod.TODAY
                }
            triggerPlay(playPromoContext, playPeriod)
            // Playing retires the promo — they've used the feature it pitches.
            // Require a delivered forecast (matching the hasForecast gate that
            // renders the card) so a fresh install can't persist the dismissal
            // before any forecast exists.
            if (state.thisPeriodInsight != null) onDismissPlayPromoCard()
        },
        onDismiss = onDismissPlayPromoCard,
        modifier = bannerModifier,
    )
    // "Try high quality voices" nudge — point users who haven't set up Gemini
    // at Voice settings so they can swap the device's built-in TTS for the
    // natural Gemini voices. Gated upstream on no Gemini key being configured
    // (plus not dismissed), and held back by [promoBannersToShow] until a
    // forecast exists and there's room under the cap. Sits between the play
    // promo and the schedule promo. Like the clothes card, the CTA dismisses
    // too — once the user's been pointed at Voice settings the card retires
    // whether or not they add a key (and it auto-hides the moment a key lands
    // regardless).
    GeminiTtsPromoCard(
        visible = PromoBanner.GEMINI in shownPromos,
        onSetUpVoices = {
            onDismissGeminiTtsPromoCard()
            onOpenVoice()
        },
        onDismiss = onDismissGeminiTtsPromoCard,
        modifier = bannerModifier,
    )
    // "Free voice limit reached" — the shared Gemini TTS allowance is spent for
    // the day, so a cast just fell back to the device voice. Not a promo (it's
    // an actionable notice, not gated by [promoBannersToShow]'s cap) and stands
    // in for the promo above, which the ViewModel holds back while this shows.
    // The CTA routes to Speech settings to add a BYOK key but doesn't dismiss —
    // the card retires on its own once the next synth succeeds.
    GeminiTtsLimitCard(
        visible = state.geminiTtsLimitCardVisible,
        onOpenVoice = onOpenVoice,
        onDismiss = onDismissGeminiTtsLimitCard,
        modifier = bannerModifier,
    )
    GeminiKeyNeedsReentryCard(
        visible = state.geminiKeyNeedsReentry,
        onOpenVoice = onOpenVoice,
        modifier = bannerModifier,
    )
    // "Set up a schedule" nudge — scheduled casts don't fire until the user
    // enables a slot, so a fresh install gets nothing on a timer. Gated
    // upstream on neither master switch being on (plus the user not having
    // dismissed), and held back by [promoBannersToShow] until a forecast
    // exists and there's room under the cap. Sits between the Gemini-voices
    // promo and the celebration promo. The CTA only routes to Schedule
    // settings (where the notification-permission prompt lives); enabling a
    // slot there auto-hides the card, so unlike the clothes card the CTA
    // doesn't dismiss.
    SchedulePromoCard(
        visible = PromoBanner.SCHEDULE in shownPromos,
        onOpenSchedule = onOpenSchedule,
        onDismiss = onDismissSchedulePromoCard,
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
    // Delivery-destination failures sit just under the fetch / work-status
    // banner: the forecast may have fetched fine, but if the cast to the
    // smart display or the publish to the Home Assistant bridge failed, the
    // user should see it here rather than only in Smart Home settings. Each
    // clears itself once a later run succeeds (the worker records a null
    // error), so no dismiss affordance is needed — same as WorkStatusBanner.
    if (!state.mqttPublishError.isNullOrBlank()) {
        PublishErrorBanner(
            title = stringResource(R.string.today_mqtt_failed_title),
            detail = state.mqttPublishError,
            onDismiss = onDismissMqttError,
            modifier = bannerModifier,
        )
    }
    if (!state.castPublishError.isNullOrBlank()) {
        PublishErrorBanner(
            title = stringResource(R.string.today_cast_failed_title),
            detail = state.castPublishError,
            onDismiss = onDismissCastError,
            modifier = bannerModifier,
        )
    }
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
 * work-status / holiday), and the user-reorderable sections in
 * [homeSectionOrder]: the conditions strip and [OutfitPreviewRow] (drawn here),
 * plus the [insightSlot], [confidenceSlot], and [chartsSlot] the page supplies.
 * Pages 0 / 1 fill the insight / confidence / per-period chart slots; page 2
 * supplies the weekly header (as [insightSlot]) + 7-day chart deck. Keeping the
 * chrome here means the banner stack and reorderable sections are byte-identical
 * across all three pages — swiping keeps the same cards in the same place.
 */
@Composable
internal fun HomePageScaffold(
    state: TodayState,
    scrollState: ScrollState,
    workStatusToShow: WorkStatus,
    locationActionRequired: Boolean,
    outfitInsight: Insight?,
    onSetUpLocation: () -> Unit,
    onDismissMqttError: () -> Unit,
    onDismissCastError: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenCalendarSettings: () -> Unit,
    onOpenSchedule: () -> Unit,
    onDismissCelebrationCard: () -> Unit,
    onDismissClothesPromoCard: () -> Unit,
    onDismissSchedulePromoCard: () -> Unit,
    onDismissPlayPromoCard: () -> Unit,
    onOpenVoice: () -> Unit,
    onDismissGeminiTtsPromoCard: () -> Unit,
    onDismissGeminiTtsLimitCard: () -> Unit,
    onNavigateToClothes: () -> Unit,
    homeSectionOrder: List<HomeSection> = HomeSection.DEFAULTS,
    insightSlot: (@Composable ColumnScope.() -> Unit)? = null,
    conditionsHourly: List<HourlyForecast>? = null,
    // This page's rain-clause condition (insight.summary.precip), so the strip's
    // droplet matches the prose even when a minority of models codes the rain and
    // the blended [conditionsHourly] consensus reads dry. Null when no rain clause.
    conditionsPrecipCondition: WeatherCondition? = null,
    confidenceSlot: (@Composable ColumnScope.() -> Unit)? = null,
    chartsSlot: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Conditions for the strip. Driven by [conditionsHourly] — the hourly
    // series of *this page's* period (today / tonight on pages 0 / 1, the whole
    // 7-day window on page 2) — so each page's strip reads its own feels-like
    // range, rain, wind and UV rather than repeating this period's everywhere.
    // Reuses the exact helper the conditions widget and outfit card feed off so
    // every surface reads the same indicators in the same order.
    val context = LocalContext.current
    // D-pad scrolling support for Android TV (see the Column modifier below).
    val isTv = remember(context) { isTelevision(context) }
    val tvScrollScope = rememberCoroutineScope()
    val tvScrollStepPx = with(LocalDensity.current) { 240.dp.toPx() }
    val conditionsInfo: OutfitCardInfoLines? = remember(
        conditionsHourly,
        conditionsPrecipCondition,
        state.region,
        state.temperatureUnit,
        state.distanceUnit,
    ) {
        conditionsHourly?.takeIf { it.isNotEmpty() }?.let { hourly ->
            runCatching {
                val formatter = InsightFormatter.forRegion(context, state.region)
                outfitCardInfoLines(
                    context = context,
                    formatter = formatter,
                    hourly = hourly,
                    temperatureUnit = state.temperatureUnit,
                    windSpeedUnit = state.distanceUnit.windSpeedUnit(),
                    precipCondition = conditionsPrecipCondition,
                )
            }.onFailure { t ->
                // Explicit fallback: a formatter/resource failure hides the
                // strip (returns null below) rather than crashing the page —
                // but log it so a region/locale regression is traceable
                // instead of vanishing silently. Synchronous, non-suspend
                // call, so there's no CancellationException to preserve.
                DiagLog.w(
                    "TodayScreen",
                    "Conditions strip failed to build for region=${state.region}, " +
                        "${hourly.size} hourly entries",
                    t,
                )
            }.getOrNull()
        }
    }
    // Edge fades hint at off-screen content above / below — drawn at the
    // page's outer Box bounds (the pager-page edges), so cards pass cleanly
    // under them as the user scrolls.
    EdgeFadeOverlay(scrollState = scrollState) {
        CompositionLocalProvider(LocalTimeFormat provides state.timeFormat) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // On TV the home page is a mostly read-only card stack with
                    // little focusable content, so D-pad presses can't move focus
                    // past the confidence chip and the chart deck below it stays
                    // unreachable. Make the scroll container focusable and turn
                    // D-pad up/down into a scroll so the whole page is navigable
                    // with a remote. Gated on TV so phone / touch is unchanged.
                    .then(
                        if (isTv) {
                            Modifier
                                .focusable()
                                .onKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) {
                                        false
                                    } else when (event.key) {
                                        Key.DirectionDown ->
                                            if (scrollState.canScrollForward) {
                                                tvScrollScope.launch {
                                                    scrollState.animateScrollBy(tvScrollStepPx)
                                                }
                                                true
                                            } else {
                                                false
                                            }
                                        Key.DirectionUp ->
                                            if (scrollState.canScrollBackward) {
                                                tvScrollScope.launch {
                                                    scrollState.animateScrollBy(-tvScrollStepPx)
                                                }
                                                true
                                            } else {
                                                false
                                            }
                                        else -> false
                                    }
                                }
                        } else {
                            Modifier
                        },
                    )
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
                    onOpenSchedule = onOpenSchedule,
                    onDismissSchedulePromoCard = onDismissSchedulePromoCard,
                    onDismissPlayPromoCard = onDismissPlayPromoCard,
                    onOpenVoice = onOpenVoice,
                    onDismissGeminiTtsPromoCard = onDismissGeminiTtsPromoCard,
                    onDismissGeminiTtsLimitCard = onDismissGeminiTtsLimitCard,
                    onDismissMqttError = onDismissMqttError,
                    onDismissCastError = onDismissCastError,
                    onOpenCalendarSettings = onOpenCalendarSettings,
                    onDismissCelebrationCard = onDismissCelebrationCard,
                    onSetUpLocation = onSetUpLocation,
                )
                // The conditions strip, outfit row, insight ([insightSlot]),
                // confidence chip ([confidenceSlot]), and chart deck
                // ([chartsSlot]) are the user-reorderable sections;
                // [homeSectionOrder] decides their order. Each renders only when
                // it has content this period (e.g. confidence / charts need a
                // cached insight), so a section the user ordered but has no data
                // for simply contributes nothing. The non-reorderable [content]
                // (the missing-period placeholder) always trails them. The same
                // order is used on all three pager pages — the 7-day page
                // supplies its week-headline card as [insightSlot] — so each
                // section keeps the same vertical offset across a swipe.
                //
                // The outfit row still renders even when the page's own insight
                // is missing (page 2's placeholder, or a not-yet-cached next
                // period): [outfitInsight] is always this period's insight,
                // null only on previews / tests that don't wire a pair.
                homeSectionOrder.forEach { section ->
                    when (section) {
                        // Conditions strip (feels-like, rain, wind, UV) inset to
                        // the same width as the cards and clipped to the card
                        // shape, so it reads as one of the cards in the stack.
                        // Only shown once this period's hourly forecast is
                        // cached; before then the strip would be blank.
                        HomeSection.CONDITIONS ->
                            conditionsInfo?.takeIf { it.tempLine.isNotBlank() }?.let { info ->
                                ConditionsStrip(info = info)
                            }
                        HomeSection.OUTFIT -> if (outfitInsight != null) {
                            OutfitPreviewRow(
                                insight = outfitInsight,
                                temperatureUnit = state.temperatureUnit,
                                clothesRules = state.clothesRules,
                                outfitTopColors = state.outfitTopColors,
                                outfitBottomColors = state.outfitBottomColors,
                                outfitHandsColors = state.outfitHandsColors,
                                outfitCarriedColors = state.outfitCarriedColors,
                                outfitOuterColors = state.outfitOuterColors,
                                outfitTopStrokes = state.outfitTopStrokes,
                                outfitBottomStrokes = state.outfitBottomStrokes,
                                onNavigateToClothes = onNavigateToClothes,
                            )
                        }
                        HomeSection.INSIGHT -> insightSlot?.invoke(this)
                        HomeSection.CONFIDENCE -> confidenceSlot?.invoke(this)
                        HomeSection.CHARTS -> chartsSlot?.invoke(this)
                    }
                }
                content()
            }
        }
    }
}

/** Height of the home-page conditions band. */
private val CONDITIONS_STRIP_HEIGHT = 36.dp

/**
 * Conditions band: the same feels-like / rain / wind / UV indicators the
 * conditions widget and outfit card show, rasterized once via
 * [renderConditionsStripBitmap] so all three surfaces read identically rather
 * than re-implementing the glyphs in Compose. Sized to match the other cards
 * (the caller leaves it inside the page Column's horizontal padding) and clipped
 * to the same Material card shape, so it reads as one of the cards in the stack.
 * The band paints [surfaceVariant] behind a transparent bitmap so it matches the
 * cards' container; the renderer's light/dark glyph + text colors are chosen off
 * that surface's own luminance, so the interiors and labels stay legible whether
 * MainActivity resolved a light or dark scheme (system mode or in-app override).
 */
@Composable
private fun ConditionsStrip(
    info: OutfitCardInfoLines,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val darkTheme = surface.luminance() < 0.5f
    val cells = remember(info) { conditionsCells(info) }
    val description = remember(info) { conditionsStripDescription(info) }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(CONDITIONS_STRIP_HEIGHT)
            .clip(CardDefaults.shape)
            .background(surface),
        contentAlignment = Alignment.Center,
    ) {
        val widthPx = with(density) { maxWidth.toPx() }.toInt().coerceAtLeast(1)
        val heightPx = with(density) { maxHeight.toPx() }.toInt().coerceAtLeast(1)
        val bitmap = remember(cells, widthPx, heightPx, darkTheme) {
            renderConditionsStripBitmap(context, cells, widthPx, heightPx, darkTheme)
        }
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = description,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Comma-joined accessibility label for the conditions strip's present cells. */
private fun conditionsStripDescription(info: OutfitCardInfoLines): String =
    listOfNotNull(
        info.tempLine.takeIf { it.isNotBlank() },
        info.rainLineShort,
        info.windLabel,
        info.uvLabel,
    ).joinToString(", ")

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
    onNavigateToClothes: () -> Unit,
    onNavigateToFormat: () -> Unit,
    onToggleModelSpread: () -> Unit,
    onRevealModelSpread: () -> Unit,
    onHideModelSpread: () -> Unit,
    onLongPressDate: () -> Unit,
    onSetUpLocation: () -> Unit,
    onDismissMqttError: () -> Unit,
    onDismissCastError: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenCalendarSettings: () -> Unit,
    onOpenSchedule: () -> Unit,
    onDismissCelebrationCard: () -> Unit,
    onDismissClothesPromoCard: () -> Unit,
    onDismissSchedulePromoCard: () -> Unit,
    onDismissPlayPromoCard: () -> Unit,
    onOpenVoice: () -> Unit,
    onDismissGeminiTtsPromoCard: () -> Unit,
    onDismissGeminiTtsLimitCard: () -> Unit,
) {
    val scrollScope = rememberCoroutineScope()
    // Captured via onGloballyPositioned so the chip-tap handler can scroll the
    // relevant section to the top of the viewport without hard-coding offsets
    // (outfit / insight / conditions heights vary). Both are measured in the
    // same coordinate space (the scrolling Column), so a chip tap scrolls to
    // whichever of the two sits higher — see [onChipTap] below. The chart
    // offset starts at "unknown" (MAX) so before the deck is laid out (or when
    // it's absent) the tap just targets the chip.
    var chipScrollOffset by remember { mutableIntStateOf(0) }
    var chartsScrollOffset by remember { mutableIntStateOf(Int.MAX_VALUE) }
    HomePageScaffold(
        state = state,
        scrollState = scrollState,
        workStatusToShow = workStatusToShow,
        locationActionRequired = locationActionRequired,
        outfitInsight = outfitInsight,
        onSetUpLocation = onSetUpLocation,
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
        // The conditions strip reads this page's own period — [insight] is the
        // page's insight (this period on page 0, the next period on page 1), so
        // each page's strip shows its own feels-like range / rain / wind / UV.
        conditionsHourly = insight?.hourly,
        // Pair the strip's hourly with this page's rain-clause condition so the
        // droplet matches the prose on a minority-model drizzle the blend hides.
        conditionsPrecipCondition = insight?.summary?.precip?.condition,
        // The insight card — its own reorderable section. The confidence chip
        // that used to ride along with it is now [confidenceSlot]; the chart
        // deck is [chartsSlot]. Renders only when this period has an insight;
        // the null case falls through to the placeholder in [content].
        insightSlot = {
            if (insight != null) {
                InsightCard(
                    insight = insight,
                    region = state.region,
                    temperatureUnit = state.temperatureUnit,
                    rangeFormat = state.rangeFormat,
                    clothesFormat = state.clothesFormat,
                    bottomsFormat = state.bottomsFormat,
                    periodPreamble = state.periodPreamble,
                    wearPreamble = state.wearPreamble,
                    showChevronRight = showChevronRight,
                    showChevronLeft = showChevronLeft,
                    onChevronTap = onChevronTap,
                    onChevronRightTap = onChevronRightTap,
                    onLongPressDate = onLongPressDate,
                    onNavigateToFormat = onNavigateToFormat,
                    onNavigateToLocation = onSetUpLocation,
                )
            }
        },
        // The forecast-confidence chip — now its own reorderable section,
        // decoupled from the insight card so the user can position it anywhere.
        // Only shown when this period carries a confidence summary.
        confidenceSlot = {
            if (insight != null) {
                insight.confidence?.let { confidence ->
                    // Two separate per-model-spread affordances:
                    //
                    //  - The confidence chip [ConfidenceChip] keeps its labelled
                    //    "tap to show / hide" toggle (wired through [onChipTap] →
                    //    [onToggleModelSpread]). That's the explicit on/off control.
                    //
                    //  - Tapping the plot grid of any chart enters scrub mode on
                    //    the shared [ChartScrubController], which (via the
                    //    [SpreadCoordinator] wired in [chartsSlot]) reveals the
                    //    per-model spread if it isn't already on.
                    //
                    // [chipToggle] is null when there's no per-model data in the
                    // cache (e.g. older payloads) — the chip then stays a static
                    // confidence summary.
                    val perModelAvailable = insight.perModelHourly != null
                    val chipToggle = onToggleModelSpread.takeIf { perModelAvailable }
                    // Wrap the per-model toggle so a tap also scrolls the
                    // affected charts into view — the per-model overlay renders
                    // on the chart deck, which (now that CONFIDENCE and CHARTS
                    // reorder independently) may sit above *or* below the chip.
                    // Scroll to whichever of the chip / chart deck is higher so
                    // the revealed overlays land on screen in either order.
                    val onChipTap: (() -> Unit)? = chipToggle?.let { toggle ->
                        {
                            toggle()
                            val target = minOf(chipScrollOffset, chartsScrollOffset)
                            scrollScope.launch { scrollState.animateScrollTo(target) }
                        }
                    }
                    ConfidenceChip(
                        modifier = Modifier.onGloballyPositioned { coords ->
                            chipScrollOffset = coords.positionInParent().y.roundToInt()
                        },
                        info = confidence,
                        perModelHourly = insight.perModelHourly,
                        temperatureUnit = state.temperatureUnit,
                        windSpeedUnit = state.distanceUnit.windSpeedUnit(),
                        showModelSpread = state.showModelSpread,
                        onToggleModelSpread = onChipTap,
                    )
                }
            }
        },
        // The chart deck — its own reorderable section. Moves as one contiguous
        // block so every card keeps sharing the single [ChartScrubController] /
        // y-axis envelope set up here. Renders only once this period's hourly
        // forecast is cached.
        chartsSlot = chartsSlot@{
            if (insight == null || insight.hourly.isEmpty()) return@chartsSlot
            // Whether the cache carried per-model curves — gates the scrub→spread
            // bridge below (see [confidenceSlot] for the chip side of the flag).
            val perModelAvailable = insight.perModelHourly != null
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
                // Wrap the deck so its top offset (in the scrolling Column) is
                // captured for the confidence chip's scroll-to-charts payoff.
                // spacedBy matches the outer Column so the wrapper is visually
                // transparent — the cards keep their 16.dp gaps.
                Column(
                    modifier = Modifier.onGloballyPositioned { coords ->
                        chartsScrollOffset = coords.positionInParent().y.roundToInt()
                    },
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
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
        },
    ) {
        // Non-reorderable: the "paired period not cached yet" placeholder. Shown
        // in place of the insight/charts when this period has no insight.
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
    // Composed multi-celebration banner joins each piece with the localised
    // "and" ("Happy bank holiday and don't forget your towel"). Read observably
    // up front so it isn't queried off LocalContext inside the remember body.
    val and = stringResource(R.string.holiday_banner_and)
    val bannerText = remember(theme, effectiveCountry, and) {
        val segments = theme.bannerSegments
        if (!segments.isNullOrEmpty()) {
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
@android.annotation.SuppressLint("DiscouragedApi")
private fun resolveBannerString(
    context: android.content.Context,
    key: String?,
    fallback: String,
): String {
    if (key == null) return fallback
    // getIdentifier is required: the string name is a runtime banner key.
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

/**
 * Top-of-page error card for a failed delivery to a smart-home destination
 * (MQTT bridge or Cast display). Styled like [WorkStatusBanner]'s failed state
 * — error-container card, a friendly title + "we'll retry" body, and the raw
 * failure reason tucked behind a Show details toggle so the technical string
 * (broker error, cast load rejection) is available without dominating the card.
 *
 * Unlike the fetch-failure banner this one carries a dismiss (X). A delivery
 * failure can outlive the config that would retry it — e.g. a failed on-demand
 * Play, or the user turning the destination off afterwards — so there has to be
 * a way to clear a failure the user has seen. Dismissal is recorded against the
 * failure's timestamp upstream ([TodayViewModel.dismissMqttPublishError] /
 * [TodayViewModel.dismissCastPublishError]), so a strictly-newer failure still
 * surfaces.
 */
@Composable
private fun PublishErrorBanner(
    title: String,
    detail: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 4.dp, bottom = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.today_publish_failed_dismiss),
                    )
                }
            }
            Text(
                text = stringResource(R.string.today_publish_failed_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = 12.dp),
            )
            if (!detail.isNullOrBlank()) {
                var showDetails by rememberSaveable(detail) { mutableStateOf(false) }
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
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
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
internal fun EmptyState(
    onRefresh: () -> Unit,
    isWorking: Boolean = false,
    locationActionRequired: Boolean = false,
    onSetUpLocation: () -> Unit = {},
) {
    // The pre-first-cast state is a frosted preview of the real layout (outfit +
    // insight + charts) with a "?" and a call to action over the top, so a
    // brand-new user sees the shape of what they'll get rather than an empty
    // screen. When no location is set up the CTA names that blocker (nothing can
    // generate without it); otherwise it's a plain "Fetch now". (The
    // top-of-screen location banner is suppressed in the no-forecast case so the
    // fix isn't prompted twice — see promoBannersToShow.)
    TodayEmptySkeleton(
        onRefresh = onRefresh,
        isWorking = isWorking,
        locationActionRequired = locationActionRequired,
        onSetUpLocation = onSetUpLocation,
    )
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
    outfitHandsColors: Map<OutfitSuggestion.Hands, Long> = emptyMap(),
    outfitCarriedColors: Map<OutfitSuggestion.Carried, Long> = emptyMap(),
    outfitOuterColors: Map<OutfitSuggestion.Outer, Long> = emptyMap(),
    outfitTopStrokes: Map<OutfitSuggestion.Top, Long> = emptyMap(),
    outfitBottomStrokes: Map<OutfitSuggestion.Bottom, Long> = emptyMap(),
    onNavigateToClothes: () -> Unit = {},
) {
    val primary = insight.outfit ?: return
    val (primaryLabel, nextLabel) = outfitLabels(insight)
    // IntrinsicSize.Max + fillMaxHeight stretches both cards to the taller one's
    // height. The garment caption grows to as many lines as its content needs
    // (a long worn line wrapping on a narrow card, plus an accessory line) — so
    // a card pairing an umbrella with a wrapping worn line keeps the same height
    // as its neighbour instead of one card running taller than the other.
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
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
            outfitHandsColors = outfitHandsColors,
            outfitCarriedColors = outfitCarriedColors,
            outfitOuterColors = outfitOuterColors,
            outfitTopStrokes = outfitTopStrokes,
            outfitBottomStrokes = outfitBottomStrokes,
            onNavigateToClothes = onNavigateToClothes,
            modifier = Modifier.weight(1f).fillMaxHeight(),
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
                outfitHandsColors = outfitHandsColors,
                outfitCarriedColors = outfitCarriedColors,
                outfitOuterColors = outfitOuterColors,
                outfitTopStrokes = outfitTopStrokes,
                outfitBottomStrokes = outfitBottomStrokes,
                onNavigateToClothes = onNavigateToClothes,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

private fun outfitLabels(insight: Insight): Pair<Int, Int> =
    when (insight.period) {
        ForecastPeriod.TODAY ->
            R.string.today_outfit_label_today to R.string.today_outfit_label_tonight
        ForecastPeriod.TONIGHT ->
            // Ongoing overnight → "Overnight" + "Today"; the coming night →
            // "Tonight" + "Tomorrow".
            if (insight.summary.overnight) {
                R.string.today_outfit_label_overnight to R.string.today_outfit_label_today
            } else {
                R.string.today_outfit_label_tonight to R.string.today_outfit_label_tomorrow
            }
    }

// Title shown in the TopAppBar — tracks the visible pager page so swiping
// right from a morning view flips "Today" to "Tonight" (and the evening
// equivalent flips "Tonight" to "Tomorrow"). Page 2 is the 7-day outlook and
// page 3 the following week (days 8-14); their titles are the same regardless
// of which period the user opened from. Falls back to "Today" when no insight
// is cached yet (pager isn't rendered in that state).
internal fun topBarTitleRes(
    period: ForecastPeriod?,
    page: Int,
    overnight: Boolean = false,
): Int {
    if (page == 2) return R.string.today_title_week
    if (page == 3) return R.string.today_title_following_week
    // The ongoing overnight (post-midnight tail): page 0 is "Overnight", page 1
    // the daytime it leads into ("Today").
    return when (period) {
        null -> R.string.today_title
        ForecastPeriod.TODAY -> if (page == 0) R.string.today_title else R.string.today_outfit_label_tonight
        ForecastPeriod.TONIGHT ->
            if (overnight) {
                if (page == 0) R.string.today_outfit_label_overnight else R.string.today_outfit_label_today
            } else {
                if (page == 0) R.string.today_outfit_label_tonight else R.string.today_outfit_label_tomorrow
            }
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
    outfitHandsColors: Map<OutfitSuggestion.Hands, Long> = emptyMap(),
    outfitCarriedColors: Map<OutfitSuggestion.Carried, Long> = emptyMap(),
    outfitOuterColors: Map<OutfitSuggestion.Outer, Long> = emptyMap(),
    outfitTopStrokes: Map<OutfitSuggestion.Top, Long> = emptyMap(),
    outfitBottomStrokes: Map<OutfitSuggestion.Bottom, Long> = emptyMap(),
    onNavigateToClothes: () -> Unit = {},
) {
    // Tapping the card opens the "Why this outfit?" sheet so a glance can become
    // an explanation; the sheet itself offers the jump to Settings → Clothes.
    // Material3's `Card(onClick = …)` overload is preferred over a bare
    // `modifier.clickable` — it carries the right semantics for accessibility
    // tooling and matches how SettingsNavRow / other tap-targets in the app are
    // wired.
    var showRationale by remember { mutableStateOf(false) }
    Card(
        onClick = { showRationale = true },
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
            // The umbrella is a full-figure overlay (held at the hip, hanging
            // down past the legs), so it spans the top+bottom stack rather than
            // sitting on one icon — pin the Box to the figure's 80×160 footprint
            // (top 80 + bottom 80) and lay the umbrella over the whole thing so
            // its 96×192 art maps 1:1. Only when a carried (umbrella) rule fired.
            Box(modifier = Modifier.size(width = 80.dp, height = 160.dp)) {
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
                        // Rain jacket overlays the top at the same width — the outer
                        // shell painted over whatever warmth tier the rules picked.
                        // Drawn before the gloves so they stay visible at the sides.
                        // Only when an outer rule fired (off by default).
                        outfit.outer?.let { outer ->
                            GarmentOuterIcon(
                                outer = outer,
                                customFill = outfitOuterColors[outer]?.let { Color(it.toInt()) },
                                contentDescription = stringResource(R.string.garment_rain_jacket),
                                modifier = Modifier.width(80.dp),
                            )
                        }
                        // Gloves overlay the top at the same width, bottom-aligned
                        // so they land at the body's sides. Only when a hands rule
                        // fired — extremity gear is opt-in, so most outfits skip it.
                        outfit.hands?.let { hands ->
                            GarmentHandsIcon(
                                hands = hands,
                                customFill = outfitHandsColors[hands]?.let { Color(it.toInt()) },
                                contentDescription = stringResource(R.string.garment_gloves),
                                modifier = Modifier.width(80.dp),
                            )
                        }
                    }
                    GarmentBottomIcon(
                        bottom = outfit.bottom,
                        customFill = outfitBottomColors[outfit.bottom]?.let { Color(it.toInt()) },
                        customStroke = outfitBottomStrokes[outfit.bottom]?.let { Color(it.toInt()) },
                        contentDescription = stringResource(bottomLabelRes(outfit.bottom)),
                        modifier = Modifier.width(80.dp),
                    )
                }
                outfit.carried?.let { carried ->
                    GarmentCarriedIcon(
                        carried = carried,
                        customFill = outfitCarriedColors[carried]?.let { Color(it.toInt()) },
                        contentDescription = stringResource(R.string.garment_umbrella),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            // Names every piece the icon shows in one flowing "· "-joined run —
            // worn outfit (top, rain-jacket shell, bottom) then the gloves /
            // umbrella accessories — so the caption matches the figure. No forced
            // break and no maxLines cap: a long caption on a narrow side-by-side
            // card ("Sweater · Rain jacket · Jeans · Umbrella") just soft-wraps,
            // where a hard two-line cap used to clip the trailing accessory and
            // drop "Umbrella" even though the figure holds one. minLines = 2
            // reserves height so a short caption isn't cramped, and the Row
            // stretches both cards to equal height so a wrapping card doesn't run
            // taller than its neighbour. fillMaxWidth + centre keeps it centred.
            Text(
                text = outfitGarmentCaption(
                    context = LocalContext.current,
                    outfit = outfit,
                    topLabel = stringResource(topLabelRes(outfit.top)),
                    bottomLabel = stringResource(bottomLabelRes(outfit.bottom)),
                ),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (showRationale) {
        OutfitRationaleDialog(
            outfit = outfit,
            rationale = rationale,
            temperatureUnit = temperatureUnit,
            clothesRules = clothesRules,
            onNavigateToClothes = {
                showRationale = false
                onNavigateToClothes()
            },
            onDismiss = { showRationale = false },
        )
    }
}

/**
 * "Why this outfit?" detail sheet — explains the deciding facts (feels-like min / max
 * + the hour they occurred + the threshold they crossed) so the user can sanity-check
 * the call against their own day.
 *
 * The displayed threshold value tracks the *live* [clothesRules], while the observed
 * value + hour come from the cached [rationale] (frozen at insight-generation time).
 * The comparison ("under" vs "above") is recomputed against the live threshold so the
 * prose stays honest. Outfit cards on the home screen show the cached pick — a refresh
 * re-runs the pipeline against the current clothes rules.
 *
 * For rule management (add / delete / change threshold or unit) the user goes to
 * Settings → Clothes via the dialog's button.
 */
@Composable
internal fun OutfitRationaleDialog(
    outfit: OutfitSuggestion,
    rationale: OutfitRationale?,
    temperatureUnit: TemperatureUnit,
    clothesRules: List<ClothesRule>,
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
                    )
                    GarmentReasonBlock(
                        title = stringResource(bottomLabelRes(outfit.bottom)),
                        reason = rationale.bottom,
                        temperatureUnit = temperatureUnit,
                        clothesRules = clothesRules,
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
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        reason.facts.forEach { fact ->
            // Prefer the live rule's threshold and fall back to the fact's
            // cached threshold when the user has deleted the rule since the
            // insight was cached — that way the dialog still has *something* to
            // render while the next refresh re-creates the rule from the
            // catalog default.
            val liveC = clothesRules.firstOrNull { it.item == fact.ruleItem }?.thresholdC()
                ?: fact.thresholdC
            FactRow(
                fact = fact,
                temperatureUnit = temperatureUnit,
                liveThresholdC = liveC,
            )
        }
    }
}

@Composable
private fun FactRow(
    fact: Fact,
    temperatureUnit: TemperatureUnit,
    liveThresholdC: Double,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatFact(fact, temperatureUnit, liveThresholdC),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
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
        val format = oneDecimalFormat()
        observedStr = format.format(observedConverted)
        thresholdStr = format.format(thresholdConverted)
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
// right decimal separator (`,` in de-DE, `.` in en-US, etc.). Built per call
// rather than cached in a static field so it tracks the user's in-app locale
// changes (NumberFormat is also not thread-safe to share).
private fun oneDecimalFormat(): java.text.NumberFormat =
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
     * Where the period / wear preambles survive. Both default to
     * [PreambleVisibility.ALWAYS] (full prose) until the drop is translated
     * beyond English; the real card passes the user's choice in explicitly.
     */
    periodPreamble: PreambleVisibility = PreambleVisibility.ALWAYS,
    wearPreamble: PreambleVisibility = PreambleVisibility.ALWAYS,
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
     * Opens the Format settings page. Wired to tapping the prose body (only
     * the prose — not the date / location header). Null disables it; default
     * null keeps every preview / non-live call site unchanged.
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
    val formatter = remember(
        context, region, temperatureUnit, rangeFormat, clothesFormat, bottomsFormat,
        periodPreamble, wearPreamble,
    ) {
        InsightFormatter.forRegion(
            context, region, temperatureUnit, rangeFormat, clothesFormat, bottomsFormat,
            periodPreamble, wearPreamble,
        )
    }
    // Page 2 caches tomorrow's daytime insight after the evening worker run;
    // surface it as "Tomorrow" rather than "Today" so the heading matches the
    // prose lead-in below. The ongoing overnight (post-midnight tail) is flagged
    // on the insight itself — "Overnight", not "Tonight".
    val isFutureDay = insight.forDate.isAfter(LocalDate.now())
    val periodLabel = stringResource(
        when (insight.period) {
            ForecastPeriod.TODAY ->
                if (isFutureDay) R.string.today_outfit_label_tomorrow
                else R.string.today_outfit_label_today
            ForecastPeriod.TONIGHT ->
                if (insight.summary.overnight) R.string.today_outfit_label_overnight
                else R.string.today_outfit_label_tonight
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
                // VISUAL surface: under the default Speech-only period preamble
                // the card opens straight on the measurement ("14° to 20°. …"),
                // dropping the redundant "Today, it will be …" lead the card's
                // own period header already supplies. The user's Lead-in setting
                // can override (Always shows the lead here too; Never drops it
                // from the spoken briefing as well).
                text = formatter.format(insight.summary, isFutureDay = isFutureDay),
                style = MaterialTheme.typography.headlineSmall,
                modifier = if (onNavigateToFormat != null) {
                    Modifier.clickable { onNavigateToFormat() }
                } else {
                    Modifier
                },
            )
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
    // The home-screen feels-like widget reuses this card but drops the chrome —
    // the section header and the legend (per-hour caption + model-spread chips)
    // — for a glanceable summary. Both default to true so the in-app cards are
    // unchanged.
    showHeader: Boolean = true,
    showLegend: Boolean = true,
    // When true the card and its chart fill the height they're given instead of
    // wrapping a fixed 180.dp chart. Used by the home-screen widget, whose
    // bitmap is sized to the cell; the in-app cards leave it false (fixed
    // height inside a scrolling column).
    fillHeight: Boolean = false,
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

    Card(modifier = if (fillHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
        Box(modifier = if (fillHeight) Modifier.fillMaxSize() else Modifier) {
            Column(
                modifier = if (fillHeight) {
                    Modifier.fillMaxSize().padding(20.dp)
                } else {
                    Modifier.padding(20.dp)
                },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showHeader) {
                    Text(
                        text = stringResource(R.string.today_forecast_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                ChartSubtitleRow(subtitle = subtitleText, readout = readout)
                ForecastChart(
                    hourly = hourly,
                    temperatureUnit = temperatureUnit,
                    showFeelsLike = true,
                    startDate = startDate,
                    modifier = if (fillHeight) Modifier.weight(1f) else Modifier,
                    perModelHourly = perModelHourly,
                    showModelSpread = showModelSpread,
                    fillHeight = fillHeight,
                )
                if (showLegend) {
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
 * True on the multi-day chart deck (the 7-day / following-week pages), where
 * a per-period "today" / "tonight" subtitle qualifier doesn't apply. Keyed off
 * [LocalScrubMomentFormat] — the same `DayPlusHour` signal the peak subtitles
 * and scrub readout use.
 */
@Composable
private fun isWeekView(): Boolean =
    LocalScrubMomentFormat.current == ScrubMomentFormat.DayPlusHour

/**
 * The bare day word for the peak at [hourIndex] in [hourly] — the 7-day deck's
 * peak subtitles read "Peak 18% Friday", or "today" / "tomorrow" when the peak
 * lands on the current or next day (per [LocalForecastToday]). The peak's date
 * is reconstructed from its index via [chartXToTime] so it survives the
 * flattened 168-hour week (and the ~10 days/year a window straddles a DST
 * shift) rather than assuming a fixed 24-hour stride.
 *
 * The token is bare — no "on", no parentheses — because that's the only form
 * that ports cleanly across locales: an idiomatic "on <day>" needs a
 * preposition (de "am", es "el") or case inflection (Slavic accusative,
 * Finnish essive) that [java.time.format.TextStyle.FULL] / `getDisplayName`
 * can't produce. today / tomorrow reuse the localized outfit-card labels,
 * lower-cased to their adverb form ("Heute" → "heute") so they sit
 * mid-subtitle; the weekday keeps the locale's own casing (capital
 * "Friday"/"Freitag", lower-case Romance "vendredi").
 */
@Composable
private fun peakDayLabel(
    hourly: List<HourlyForecast>,
    startDate: LocalDate,
    hourIndex: Int,
): String {
    val moment = chartXToTime(hourly, startDate, hourIndex.toDouble())
        ?: LocalDateTime.of(startDate, hourly[hourIndex].time)
    val peakDate = moment.toLocalDate()
    val today = LocalForecastToday.current
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    return when {
        today != null && peakDate == today ->
            stringResource(R.string.today_outfit_label_today).replaceFirstChar { it.lowercaseChar() }
        today != null && peakDate == today.plusDays(1) ->
            stringResource(R.string.today_outfit_label_tomorrow).replaceFirstChar { it.lowercaseChar() }
        else -> moment.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, locale)
    }
}

/**
 * Builds a chart card's "peak" subtitle, naming the hour ("Peak 18% at
 * 23:00") on the per-period pages and the day of week ("Peak 18% on Friday")
 * on the 7-day deck — keyed off [LocalScrubMomentFormat], the same signal the
 * scrub readout uses to disambiguate a repeated hour-of-day across the week.
 *
 * [leadingArgs] are the value placeholders that precede the time/day token in
 * both string templates (e.g. the rounded value, or speed + unit symbol); the
 * token itself is appended as the final format argument.
 */
@Composable
private fun peakSubtitle(
    hourly: List<HourlyForecast>,
    startDate: LocalDate,
    hourIndex: Int,
    @androidx.annotation.StringRes atTimeRes: Int,
    @androidx.annotation.StringRes onDayRes: Int,
    vararg leadingArgs: Any,
): String =
    if (LocalScrubMomentFormat.current == ScrubMomentFormat.DayPlusHour) {
        stringResource(onDayRes, *leadingArgs, peakDayLabel(hourly, startDate, hourIndex))
    } else {
        stringResource(atTimeRes, *leadingArgs, formatScrubHour(hourly[hourIndex].time))
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
    // Timestamp → window-position map matching the chart's keying (see
    // [hourlyTimestampIndices]) so the peak subtitle reads off the same
    // consensus series the chart draws — and names the right hour even
    // when a model dropped an hour.
    val indexByTime = remember(hourly, startDate) { hourlyTimestampIndices(hourly, startDate) }
    // 10 km/h floor on the y-range so a near-still day doesn't get zoomed
    // into noise — same reasoning as ForecastChart.MIN_Y_SPAN. Express the
    // floor in the user's unit so the heuristic stays equivalent (10 km/h
    // ≈ 6.2 mph) instead of shrinking to a tighter span on imperial.
    val minSpan = 10.0.toWindSpeedUnit(windSpeedUnit)
    val peak = remember(perModelHourly, windSpeedUnit, times, indexByTime) {
        perModelConsensusSeries(perModelHourly, indexByTime) {
            it.windSpeedKmh?.toWindSpeedUnit(windSpeedUnit)
        }
            .maxByOrNull { it.second }
            ?.takeIf { it.first in times.indices }
    }
    val subtitle = peak?.let { (idx, value) ->
        peakSubtitle(
            hourly = hourly,
            startDate = startDate,
            hourIndex = idx,
            atTimeRes = R.string.today_wind_peak,
            onDayRes = R.string.today_wind_peak_day,
            value.roundToInt(),
            windSpeedUnit.symbol(),
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
    // Timestamp → window-position map matching the chart's keying — see
    // [WindCard] / [hourlyTimestampIndices].
    val indexByTime = remember(hourly, startDate) { hourlyTimestampIndices(hourly, startDate) }
    val range = remember(perModelHourly, indexByTime) {
        perModelConsensusRange(perModelHourly, indexByTime) { it.cloudCoverPct }
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
    // Timestamp → window-position map matching the chart's keying — see
    // [WindCard] / [hourlyTimestampIndices].
    val indexByTime = remember(hourly, startDate) { hourlyTimestampIndices(hourly, startDate) }
    val range = remember(perModelHourly, indexByTime) {
        perModelConsensusRange(perModelHourly, indexByTime) { it.relativeHumidityPct }
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
    // Timestamp → window-position map matching the chart's keying — see
    // [WindCard] / [hourlyTimestampIndices].
    val indexByTime = remember(hourly, startDate) { hourlyTimestampIndices(hourly, startDate) }
    // Peak irradiance across the cross-model consensus series — same blend
    // the chart draws. Suppressed when the rounded peak is below 10 W/m²
    // (night view, deep-polar winter) so the subtitle doesn't read
    // "Peak 3 W/m² at 12:00".
    val peak = remember(perModelHourly, times, indexByTime) {
        perModelConsensusSeries(perModelHourly, indexByTime) { it.shortwaveRadiationWm2 }
            .maxByOrNull { it.second }
            ?.takeIf { it.second.roundToInt() >= 10 && it.first in times.indices }
    }
    val subtitle = peak?.let { (idx, value) ->
        peakSubtitle(
            hourly = hourly,
            startDate = startDate,
            hourIndex = idx,
            atTimeRes = R.string.today_solar_peak,
            onDayRes = R.string.today_solar_peak_day,
            value.roundToInt(),
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
    // slice is single-date by construction). On the 7-day deck the per-model
    // data is already sliced to the visible week, so the window total is the
    // week's sunshine — date-filtering to forDate would show only day one.
    val weekView = isWeekView()
    val totalHours = remember(perModelHourly, forDate, period, weekView) {
        when {
            weekView || period == ForecastPeriod.TONIGHT -> perModelHourly.consensusSunshineHours()
            else -> perModelHourly.consensusSunshineHoursFor(forDate)
        }
    }
    val totalBlurb = if (totalHours != null) {
        formatSunshineTotal(totalHours, period, weekView)
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
private fun formatSunshineTotal(
    hours: Double,
    period: ForecastPeriod,
    weekView: Boolean,
): String {
    val totalMinutes = (hours * 60.0).roundToInt()
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    val res = when {
        weekView -> Triple(
            R.string.today_sunshine_total_minutes_only_week,
            R.string.today_sunshine_total_hours_only_week,
            R.string.today_sunshine_total_hours_minutes_week,
        )
        period == ForecastPeriod.TODAY -> Triple(
            R.string.today_sunshine_total_minutes_only,
            R.string.today_sunshine_total_hours_only,
            R.string.today_sunshine_total_hours_minutes,
        )
        else -> Triple(
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
    // Timestamp → window-position map matching the chart's keying — see
    // [WindCard] / [hourlyTimestampIndices].
    val indexByTime = remember(hourly, startDate) { hourlyTimestampIndices(hourly, startDate) }
    // Peak UV across the cross-model consensus series — same blend the chart
    // draws. Suppressed when the rounded peak is below 1 (night view, deep
    // winter) so the subtitle doesn't read "Peak 0 at 21:00".
    val peak = remember(perModelHourly, times, indexByTime) {
        perModelConsensusSeries(perModelHourly, indexByTime) { it.uvIndex }
            .maxByOrNull { it.second }
            ?.takeIf { it.second.roundToInt() >= 1 && it.first in times.indices }
    }
    val subtitle = peak?.let { (idx, value) ->
        peakSubtitle(
            hourly = hourly,
            startDate = startDate,
            hourIndex = idx,
            atTimeRes = R.string.today_uv_peak,
            onDayRes = R.string.today_uv_peak_day,
            value.roundToInt(),
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
    val peakIdx = remember(hourly) {
        hourly.indices.maxByOrNull { hourly[it].precipitationProbabilityPct }
    }
    val isDry = peakIdx == null ||
        hourly[peakIdx].precipitationProbabilityPct < DRY_THRESHOLD_PCT
    val scrubController = LocalChartScrub.current
    val subtitleText = if (isDry || peakIdx == null) {
        stringResource(
            if (isWeekView()) R.string.today_precipitation_dry_week
            else R.string.today_precipitation_dry,
        )
    } else {
        peakSubtitle(
            hourly = hourly,
            startDate = startDate,
            hourIndex = peakIdx,
            atTimeRes = R.string.today_precipitation_peak,
            onDayRes = R.string.today_precipitation_peak_day,
            hourly[peakIdx].precipitationProbabilityPct.roundToInt(),
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
                    // Mirror the chart's per-model visibility filter (see
                    // [PrecipitationChart]) — list only the models that
                    // actually have a probability line plotted, not every
                    // model in byModel. Open-Meteo omits
                    // `precipitation_probability_<model>` for some models
                    // (UKMO, JMA, …) even though they report temperature and
                    // precipitation_mm, so a bare `it in byModel` filter shows
                    // a legend chip with no corresponding line on the chart.
                    val visibleIds = if (showModelSpread) {
                        MODEL_DRAW_ORDER.filter { modelId ->
                            perModelHourly.byModel[modelId]
                                ?.any { it.precipitationProbabilityPct != null } == true
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
    // contradicting number when the consensus diverges). The timestamp →
    // window-position map matches the one the chart builds from the same
    // inputs, so the two series stay byte-identical.
    val indexByTime = remember(hourly, forDate) { hourlyTimestampIndices(hourly, forDate) }
    val mainLine = remember(hourly, perModelHourly, indexByTime) {
        consensusRainfallMainLine(hourly, perModelHourly, indexByTime)
    }
    val totalMm = remember(mainLine) { mainLine.sum() }
    val rainPeakIdx = remember(mainLine) { mainLine.indices.maxByOrNull { mainLine[it] } }
    val isDry = totalMm < DRY_TOTAL_THRESHOLD_MM
    val scrubController = LocalChartScrub.current
    val subtitleText = if (isDry) {
        stringResource(
            when {
                isWeekView() -> R.string.today_precipitation_amount_dry_week
                period == ForecastPeriod.TODAY -> R.string.today_precipitation_amount_dry_today
                else -> R.string.today_precipitation_amount_dry_tonight
            },
        )
    } else if (isWeekView() &&
        rainPeakIdx != null &&
        mainLine[rainPeakIdx] >= TRACE_MM_FLOOR
    ) {
        // 7-day page: a per-period "X of rain today" total doesn't apply to a
        // week-wide window, so surface the wettest hour and name its day —
        // matching the Chance of rain / Wind / Solar / UV peak subtitles. The
        // peak-amount gate avoids "Peak 0.0 mm on …" when a week clears the
        // dry total only through trace hours that each round to zero
        // (formatPrecipitationMmAxis shows one decimal, so < 0.05 mm renders
        // as "0.0 mm"); those fall through to the weekly total below.
        stringResource(
            R.string.today_precipitation_amount_peak_day,
            formatPrecipitationMmAxis(mainLine[rainPeakIdx]),
            peakDayLabel(hourly, forDate, rainPeakIdx),
        )
    } else {
        stringResource(
            when {
                isWeekView() -> R.string.today_precipitation_amount_total_week
                period == ForecastPeriod.TODAY -> R.string.today_precipitation_amount_total_today
                else -> R.string.today_precipitation_amount_total_tonight
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
 * Compact one-line "● Average ● Auto ● ECM ● GFS ● ICO" footer rendered
 * under the charts (no "Models:" prefix — the chips read as the line key on
 * their own). Per-model entries use three-letter codes
 * ([shortModelName]) so the full five-model default set still fits a single
 * line without wrapping. The optional [mainLine] entry (theme primary)
 * comes first so its position in the legend is the same in the single and
 * per-model views — i.e. when the overlay is off and the legend has only
 * "Average" in it, that chip sits where it sits in the spread view too.
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
    // No "Models:" prefix — the coloured chips under a titled chart read as
    // the line key on their own, and dropping the word buys horizontal room
    // to keep the full default set on one line.
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        mainLine?.let {
            LegendChip(
                color = it.color,
                label = compactMainLabel(it.label),
                style = labelStyle,
                textColor = labelColor,
            )
        }
        visibleModelIds.forEach { modelId ->
            LegendChip(
                color = modelColors.getValue(modelId),
                label = shortModelName(modelId),
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
    "ecmwf_ifs025" -> "ECMWF"
    "gfs_seamless" -> "GFS"
    "icon_seamless" -> "ICON"
    "gem_seamless" -> "GEM"
    "meteofrance_seamless" -> "ARPEGE"
    "ukmo_seamless" -> "UKMO"
    "jma_seamless" -> "JMA"
    "ecmwf_aifs025_single" -> "AIFS"
    PerModelHourly.BEST_MATCH_MODEL_ID -> "Auto"
    else -> modelId
}

/**
 * Short legend label so the model spread legend stays on one line even with
 * the full five-model default set. The model names truncate to three letters
 * (ECMWF → ECM, ICON → ICO, …); the synthetic Auto (best-match) entry keeps
 * its word since it isn't a forecaster name. The blended main line is labelled
 * separately via [MainLineLegend] (see `today_chart_main_line_label`).
 */
private fun shortModelName(modelId: String): String =
    if (modelId == PerModelHourly.BEST_MATCH_MODEL_ID) {
        friendlyModelName(modelId)
    } else {
        friendlyModelName(modelId).take(3)
    }

/**
 * Width budget for the blended main-line label ("Average" and its
 * translations). Most languages have a short word for it (Media, Medel,
 * Snitt, Mittel, Moyenne, …), but a few are long, so this is the cross-locale
 * safety net: anything longer is truncated with an ellipsis so it can't push
 * the one-line legend into a wrap. Translators should still aim for a short
 * native word or abbreviation (see `today_chart_main_line_label`); this only
 * catches overflow.
 */
private const val MAX_MAIN_LINE_LABEL_CHARS = 8

private fun compactMainLabel(label: String): String =
    if (label.length <= MAX_MAIN_LINE_LABEL_CHARS) {
        label
    } else {
        label.take(MAX_MAIN_LINE_LABEL_CHARS - 1).trimEnd() + "…"
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

// Smallest single-hour amount that still renders as a non-zero "X mm" through
// [formatPrecipitationMmAxis] (one decimal, so anything below 0.05 mm shows as
// "0.0 mm"). The 7-day Rainfall card gates its peak-day subtitle on this so a
// week of trace hours doesn't read "Peak 0.0 mm on Friday".
private const val TRACE_MM_FLOOR = 0.05

internal fun formatMinMax(values: List<Double>, unit: TemperatureUnit): Pair<Int, Int>? {
    if (values.isEmpty()) return null
    val converted = values.map { it.toUnit(unit) }
    return converted.min().roundToInt() to converted.max().roundToInt()
}

// Per-hour mean of [picker] across whichever models reported at that hour —
// the same blend used for the diagnostic charts' main consensus line, lifted
// out so the card subtitles can summarise the same series the chart draws.
// Entries are resolved to window positions by timestamp lookup against
// [indexByTime] (the [hourlyTimestampIndices] map for the card's hourly
// window), matching [PerModelDiagnosticCard]'s keying — so a model that
// dropped an hour doesn't merge mismatched wall-clock hours into one mean,
// and the peak subtitles ("Peak X at H") name the right hour. Entries
// outside the window are skipped. Returns (windowIndex, mean) pairs sorted
// by index; empty when no model has data for the metric.
private fun perModelConsensusSeries(
    perModelHourly: PerModelHourly,
    indexByTime: Map<LocalDateTime, Int>,
    picker: (PerModelHour) -> Double?,
): List<Pair<Int, Double>> {
    val byIndex = mutableMapOf<Int, MutableList<Double>>()
    perModelHourly.byModel.values.forEach { entries ->
        entries.forEach { e ->
            val idx = indexByTime[e.time] ?: return@forEach
            picker(e)?.let { byIndex.getOrPut(idx) { mutableListOf() } += it }
        }
    }
    return byIndex.entries.sortedBy { it.key }.map { (idx, vs) -> idx to vs.average() }
}

private fun perModelConsensusRange(
    perModelHourly: PerModelHourly,
    indexByTime: Map<LocalDateTime, Int>,
    picker: (PerModelHour) -> Double?,
): Pair<Int, Int>? {
    val values = perModelConsensusSeries(perModelHourly, indexByTime, picker).map { it.second }
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
    // Play the requested period: replays a fresh cached snapshot when one
    // exists, else fetches fresh so an empty / stale cache still delivers
    // rather than silently no-opping (see FetchAndNotifyWorker.playInsight).
    // The unique work name is keyed on the play queue so a Refresh and a
    // Play can't run concurrently for the same slot.
    //
    // forceNotifyAndSpeak: tapping Play is an explicit "do it now," so it
    // posts the notification and speaks regardless of the delivery mode — a
    // SILENT / notification-only user still sees and hears their forecast.
    // (The Schedule "Play now" preview leaves this off to simulate the
    // configured mode.)
    FetchAndNotifyWorker.enqueuePlay(context.applicationContext, period, forceNotifyAndSpeak = true)
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
    val uri = "geo:$latitude,$longitude?q=$latitude,$longitude$labelPart".toUri()
    val intent = Intent(Intent.ACTION_VIEW, uri)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No maps app installed; nothing useful to do.
    }
}
