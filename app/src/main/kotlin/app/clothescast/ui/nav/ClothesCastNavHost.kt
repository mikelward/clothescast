package app.clothescast.ui.nav

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import androidx.glance.appwidget.updateAll
import androidx.work.WorkManager
import app.clothescast.ClothesCastApplication
import app.clothescast.MainActivity
import app.clothescast.R
import app.clothescast.calendar.resolveHolidayTheme
import app.clothescast.cast.castCurrentInsight
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.insight.InsightFormatter
import app.clothescast.locale.AppLocale
import app.clothescast.location.LocationResolver
import app.clothescast.ui.garment.outfitCardInfoLines
import app.clothescast.ui.garment.renderOutfitCard
import app.clothescast.ui.onboarding.OnboardingScreen
import app.clothescast.ui.onboarding.OnboardingViewModel
import app.clothescast.ui.pairing.PairingScreen
import app.clothescast.ui.pairing.PairingViewModel
import app.clothescast.ui.settings.AboutPage
import app.clothescast.ui.settings.CalendarPage
import app.clothescast.ui.settings.ClothesPage
import app.clothescast.ui.settings.DeveloperPage
import app.clothescast.ui.settings.DisplayPage
import app.clothescast.ui.settings.ForecastersPage
import app.clothescast.ui.settings.FormatPage
import app.clothescast.ui.settings.LocationPage
import app.clothescast.ui.settings.PrivacyPage
import app.clothescast.ui.settings.RegionPage
import app.clothescast.ui.settings.SchedulePage
import app.clothescast.ui.settings.SettingsMenuItem
import app.clothescast.ui.settings.SettingsRootPage
import app.clothescast.ui.settings.SettingsViewModel
import app.clothescast.ui.settings.SmartHomePage
import app.clothescast.ui.settings.VoicePage
import app.clothescast.ui.today.TodayScreen
import app.clothescast.ui.today.TodayViewModel
import app.clothescast.widget.OutfitWidget
import app.clothescast.work.FetchAndNotifyWorker
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

// Duration of the push/pop slide between destinations. Short enough to feel
// snappy (and to keep the can't-tap-yet window small), long enough to read as a
// directional transition rather than a jump.
private const val NAV_ANIM_MS = 200

// Type-safe destinations. The four top-level screens plus a nested graph for
// Settings — each settings sub-page is its own destination so the framework's
// back stack handles up-navigation. No hand-maintained "current route" state,
// no goBackOrUp(), no per-screen BackHandler.
@Serializable internal object TodayRoute
@Serializable internal object OnboardingRoute
@Serializable internal object PairingRoute

@Serializable internal object SettingsGraph
@Serializable internal object SettingsRootDest
// fromOnboarding rides as a typed nav argument (was a stringly-typed
// `settingsInitialRoute` round-trip through MainActivity before).
@Serializable internal data class ScheduleDest(val fromOnboarding: Boolean = false)
@Serializable internal object FormatDest
@Serializable internal object ClothesDest
@Serializable internal object RegionDest
@Serializable internal object VoiceDest
@Serializable internal object DisplayDest
@Serializable internal object LocationDest
@Serializable internal object CalendarDest
@Serializable internal object ForecastersDest
@Serializable internal object SmartHomeDest
@Serializable internal object PrivacyDest
@Serializable internal object AboutDest
@Serializable internal object DeveloperDest

@Composable
fun ClothesCastNavHost(
    app: ClothesCastApplication,
    startOnboarding: Boolean,
    newIntent: Intent?,
) {
    val nav = rememberNavController()

    // Intents that arrive while the activity is already running (a notification
    // tap → onNewIntent) are forwarded here and matched against the Today deep
    // link below, so the user lands on Today regardless of where they were.
    // Cold-start / post-process-death intents are handled automatically by the
    // NavController from the launch intent, so they don't need this path.
    LaunchedEffect(newIntent) {
        if (newIntent != null) nav.handleDeepLink(newIntent)
    }

    NavHost(
        navController = nav,
        startDestination = if (startOnboarding) OnboardingRoute else TodayRoute,
        // Push/pop slide so sub-pages animate in from the leading edge (and back
        // out the same way on up-navigation). The library default is a 700ms
        // crossfade, which both reads as "appearing from nowhere" and overlaps the
        // outgoing and incoming screens in the same spot — a low-alpha layer still
        // eats touches, so a quick tap on a freshly-shown list can hit the wrong
        // screen. A short directional slide fixes the look and stops the screens
        // sitting on top of each other. Start/End (not Left/Right) so the motion
        // mirrors correctly in RTL locales (ar/fa/iw).
        enterTransition = { slideIntoContainer(SlideDirection.Start, tween(NAV_ANIM_MS)) },
        exitTransition = { slideOutOfContainer(SlideDirection.Start, tween(NAV_ANIM_MS)) },
        popEnterTransition = { slideIntoContainer(SlideDirection.End, tween(NAV_ANIM_MS)) },
        popExitTransition = { slideOutOfContainer(SlideDirection.End, tween(NAV_ANIM_MS)) },
    ) {
        composable<TodayRoute>(
            deepLinks = listOf(navDeepLink<TodayRoute>(basePath = MainActivity.DEEP_LINK_TODAY)),
        ) {
            val context = LocalContext.current
            val today: TodayViewModel = viewModel(factory = todayViewModelFactory(app, context))
            TodayScreen(
                viewModel = today,
                onNavigateToSettings = { nav.navigate(SettingsGraph) },
                // Deep links push the target page straight onto Today, so a
                // single back returns to Today (no detour through the root list).
                onNavigateToAbout = { nav.navigate(AboutDest) },
                onNavigateToLocation = { nav.navigate(LocationDest) },
                onNavigateToPrivacy = { nav.navigate(PrivacyDest) },
                onNavigateToClothes = { nav.navigate(ClothesDest) },
                onNavigateToCalendar = { nav.navigate(CalendarDest) },
                onNavigateToDeveloper = { nav.navigate(DeveloperDest) },
                onNavigateToFormat = { nav.navigate(FormatDest) },
            )
        }

        composable<OnboardingRoute> {
            val context = LocalContext.current
            val onboarding: OnboardingViewModel = viewModel(
                factory = OnboardingViewModel.Factory(
                    secureKeyStore = app.secureKeyStore,
                    settingsRepository = app.settingsRepository,
                    geocodingClient = app.geocodingClient,
                    refreshLocationCache = {
                        FetchAndNotifyWorker.enqueueLocationCacheRefresh(context)
                    },
                    workManager = WorkManager.getInstance(app),
                ),
            )
            OnboardingScreen(
                viewModel = onboarding,
                onPairFromPhone = { nav.navigate(PairingRoute) },
                onContinue = { nav.navigate(ScheduleDest(fromOnboarding = true)) },
                onSkip = {
                    nav.navigate(TodayRoute) {
                        popUpTo(nav.graph.id) { inclusive = true }
                    }
                },
            )
        }

        composable<PairingRoute> {
            val pairing: PairingViewModel = viewModel(
                factory = PairingViewModel.Factory(
                    secureKeyStore = app.secureKeyStore,
                    settingsRepository = app.settingsRepository,
                ),
            )
            PairingScreen(
                viewModel = pairing,
                onSuccess = { nav.popBackStack() },
                onCancel = { nav.popBackStack() },
            )
        }

        settingsGraph(nav, app)
    }
}

private fun NavGraphBuilder.settingsGraph(nav: NavController, app: ClothesCastApplication) {
    // Finishing onboarding lands the user on Today as a fresh root.
    val finishOnboarding: () -> Unit = {
        nav.navigate(TodayRoute) {
            popUpTo(nav.graph.id) { inclusive = true }
        }
    }
    val onBack: () -> Unit = { nav.popBackStack() }

    navigation<SettingsGraph>(startDestination = SettingsRootDest) {
        composable<SettingsRootDest> { e ->
            SettingsRootPage(
                viewModel = e.settingsViewModel(nav, app),
                items = settingsMenu(nav),
                onBack = onBack,
                onOpenLocation = { nav.navigate(LocationDest) },
            )
        }
        composable<ScheduleDest> { e ->
            SchedulePage(
                viewModel = e.settingsViewModel(nav, app),
                onBack = onBack,
                onboardingLanding = e.toRoute<ScheduleDest>().fromOnboarding,
                onFinishOnboarding = finishOnboarding,
            )
        }
        composable<FormatDest> { e -> FormatPage(e.settingsViewModel(nav, app), onBack) }
        composable<ClothesDest> { e -> ClothesPage(e.settingsViewModel(nav, app), onBack) }
        composable<RegionDest> { e -> RegionPage(e.settingsViewModel(nav, app), onBack) }
        composable<VoiceDest> { e -> VoicePage(e.settingsViewModel(nav, app), onBack) }
        composable<DisplayDest> { e -> DisplayPage(e.settingsViewModel(nav, app), onBack) }
        composable<LocationDest> { e -> LocationPage(e.settingsViewModel(nav, app), onBack) }
        composable<CalendarDest> { e ->
            CalendarPage(
                viewModel = e.settingsViewModel(nav, app),
                onBack = onBack,
                onNavigateToRegion = { nav.navigate(RegionDest) },
                onNavigateToLocation = { nav.navigate(LocationDest) },
            )
        }
        composable<ForecastersDest> { e -> ForecastersPage(e.settingsViewModel(nav, app), onBack) }
        composable<SmartHomeDest> { e -> SmartHomePage(e.settingsViewModel(nav, app), onBack) }
        composable<PrivacyDest> { e -> PrivacyPage(e.settingsViewModel(nav, app), onBack) }
        composable<AboutDest> { AboutPage(onBack) }
        composable<DeveloperDest> { e -> DeveloperPage(e.settingsViewModel(nav, app), onBack) }
    }
}

// The ordered Settings root menu. Title + subtitle live here next to the
// destination each row opens; Root and About aren't listed (Root is the menu
// itself, About is reached only from Today's overflow). Order matches the list
// the user sees: most-tweaked rules first, set-once config next, data sources last.
private fun settingsMenu(nav: NavController): List<SettingsMenuItem> = listOf(
    SettingsMenuItem(R.string.settings_root_schedule, R.string.settings_root_schedule_subtitle) { nav.navigate(ScheduleDest()) },
    SettingsMenuItem(R.string.settings_root_clothes, R.string.settings_root_clothes_subtitle) { nav.navigate(ClothesDest) },
    SettingsMenuItem(R.string.settings_root_format, R.string.settings_root_format_subtitle) { nav.navigate(FormatDest) },
    SettingsMenuItem(R.string.settings_root_location, R.string.settings_root_location_subtitle) { nav.navigate(LocationDest) },
    SettingsMenuItem(R.string.settings_root_region, R.string.settings_root_region_subtitle) { nav.navigate(RegionDest) },
    SettingsMenuItem(R.string.settings_root_voice, R.string.settings_root_voice_subtitle) { nav.navigate(VoiceDest) },
    SettingsMenuItem(R.string.settings_root_display, R.string.settings_root_display_subtitle) { nav.navigate(DisplayDest) },
    SettingsMenuItem(R.string.settings_root_calendar, R.string.settings_root_calendar_subtitle) { nav.navigate(CalendarDest) },
    SettingsMenuItem(R.string.settings_root_forecasters, R.string.settings_root_forecasters_subtitle) { nav.navigate(ForecastersDest) },
    SettingsMenuItem(R.string.settings_root_smart_home, R.string.settings_root_smart_home_subtitle) { nav.navigate(SmartHomeDest) },
    SettingsMenuItem(R.string.settings_root_privacy, R.string.settings_root_privacy_subtitle) { nav.navigate(PrivacyDest) },
    SettingsMenuItem(R.string.settings_root_developer, R.string.settings_root_developer_subtitle) { nav.navigate(DeveloperDest) },
)

// One SettingsViewModel shared across every settings destination, scoped to the
// SettingsGraph back-stack entry. Replaces the single VM the old monolithic
// SettingsScreen held; surviving config changes and process death the same way.
@Composable
private fun NavBackStackEntry.settingsViewModel(
    nav: NavController,
    app: ClothesCastApplication,
): SettingsViewModel {
    val context = LocalContext.current
    val parentEntry = remember(this) { nav.getBackStackEntry<SettingsGraph>() }
    return viewModel(viewModelStoreOwner = parentEntry, factory = settingsViewModelFactory(app, context))
}

private fun todayViewModelFactory(app: ClothesCastApplication, context: Context) =
    TodayViewModel.Factory(
        insightCache = app.insightCache,
        workManager = WorkManager.getInstance(app),
        settingsRepository = app.settingsRepository,
        refreshOutfitWidget = {
            runCatching { OutfitWidget().updateAll(context.applicationContext) }
        },
        deriveInsight = app.deriveInsight,
        calendarEventReader = app.calendarEventReader,
    )

private fun settingsViewModelFactory(app: ClothesCastApplication, context: Context) =
    SettingsViewModel.Factory(
        settingsRepository = app.settingsRepository,
        keyStore = app.secureKeyStore,
        rearmAlarm = app.dailyAlarmScheduler::schedule,
        cancelAlarm = app.dailyAlarmScheduler::cancel,
        geocodingClient = app.geocodingClient,
        voiceEnumerator = app.androidTtsVoiceEnumerator,
        applyAppLocale = { region ->
            AppLocale.apply(app, region)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                (context as? Activity)?.recreate()
            }
        },
        refreshLocationCache = {
            FetchAndNotifyWorker.enqueueLocationCacheRefresh(context)
        },
        refreshOutfitWidget = {
            // No cache work needed — the cache holds the raw ForecastSnapshot,
            // and every consumer (Today screen, Format settings preview, cast,
            // MQTT) re-derives off the current prefs reactively. The widget
            // doesn't subscribe to the prefs flow itself, so a settings write
            // that changes what icon should render needs an explicit nudge to
            // repaint the launcher.
            runCatching { OutfitWidget().updateAll(context.applicationContext) }
        },
        resolveDeviceLocationWithCity = {
            app.locationResolver.resolveFresh(
                LocationResolver.FRESH_FIX_MAX_AGE_MS,
            )?.let { fix ->
                val geo = app.reverseGeocoder.resolve(fix.latitude, fix.longitude)
                fix.copy(
                    displayName = geo.city ?: fix.displayName,
                    countryCode = geo.countryCode ?: fix.countryCode,
                )
            }
        },
        workManager = WorkManager.getInstance(app),
        insightCache = app.insightCache,
        mqttPublisher = app.mqttPublisher,
        fullPublish = {
            val prefs = app.settingsRepository.preferences.first()
            val snapshot = app.insightCache.thisPeriod.first()
                ?: return@Factory app.mqttPublisher.publishTest()
            val insight = app.deriveInsight(snapshot, prefs).insight
            val formatter = InsightFormatter.forRegion(
                context,
                prefs.region,
                prefs.temperatureUnit,
                prefs.rangeFormat,
                prefs.clothesFormat,
                prefs.bottomsFormat,
                prefs.rainAccessory,
            )
            val prose = formatter.format(insight.summary)
            val png: ByteArray? = insight.outfit?.let { outfit ->
                runCatching {
                    val info = outfitCardInfoLines(
                        context = context,
                        formatter = formatter,
                        hourly = insight.hourly,
                        temperatureUnit = prefs.temperatureUnit,
                    )
                    val header = context.getString(
                        if (insight.period == ForecastPeriod.TODAY) R.string.outfit_card_header_today
                        else R.string.outfit_card_header_tonight
                    )
                    val theme = resolveHolidayTheme(prefs, app.calendarEventReader)
                    val topColors: Map<OutfitSuggestion.Top, Long> =
                        prefs.outfitTopColors + (theme?.topOverrides ?: emptyMap())
                    val bottomColors: Map<OutfitSuggestion.Bottom, Long> =
                        prefs.outfitBottomColors + (theme?.bottomOverrides ?: emptyMap())
                    val topStrokes: Map<OutfitSuggestion.Top, Long> =
                        theme?.topStrokeOverrides ?: emptyMap()
                    val bottomStrokes: Map<OutfitSuggestion.Bottom, Long> =
                        theme?.bottomStrokeOverrides ?: emptyMap()
                    renderOutfitCard(
                        context = context,
                        outfit = outfit,
                        header = header,
                        prose = prose,
                        tempLine = info.tempLine,
                        rainLine = info.rainLine,
                        tempFillFraction = info.tempFillFraction,
                        rainFillFraction = info.rainFillFraction,
                        topColors = topColors,
                        bottomColors = bottomColors,
                        topStrokes = topStrokes,
                        bottomStrokes = bottomStrokes,
                    )
                }.getOrNull()
            }
            app.mqttPublisher.publishIfEnabled(insight.period, prose, image = png)
        },
        discovery = app.homeAssistantDiscovery,
        castRouteDiscovery = app.castRouteDiscovery,
        calendarEventReader = app.calendarEventReader,
        castAvailable = app.castContext != null,
        castNowAction = app.castInsightController?.let { controller ->
            {
                castCurrentInsight(
                    context = context,
                    settingsRepository = app.settingsRepository,
                    insightCache = app.insightCache,
                    deriveInsight = app.deriveInsight,
                    calendarEventReader = app.calendarEventReader,
                    controller = controller,
                    locale = LocaleListCompat.getAdjustedDefault().get(0)
                        ?: java.util.Locale.getDefault(),
                )
            }
        },
    )
