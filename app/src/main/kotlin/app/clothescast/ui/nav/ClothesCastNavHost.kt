package app.clothescast.ui.nav

import android.app.Activity
import android.content.Context
import android.os.Build
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
import androidx.navigation.toRoute
import androidx.glance.appwidget.updateAll
import androidx.work.WorkManager
import app.clothescast.ClothesCastApplication
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
import app.clothescast.ui.settings.SettingsRoute
import app.clothescast.ui.settings.SettingsSubPage
import app.clothescast.ui.settings.SettingsViewModel
import app.clothescast.ui.today.TodayScreen
import app.clothescast.ui.today.TodayViewModel
import app.clothescast.widget.OutfitWidget
import app.clothescast.work.FetchAndNotifyWorker
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

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
@Serializable internal object ClothesDest
@Serializable internal object RegionDest
@Serializable internal object VoiceDest
@Serializable internal object DisplayDest
@Serializable internal object HolidaysDest
@Serializable internal object LocationDest
@Serializable internal object CalendarDest
@Serializable internal object ForecastersDest
@Serializable internal object SmartHomeDest
@Serializable internal object PrivacyDest
@Serializable internal object AboutDest

private fun SettingsRoute.toDestination(): Any = when (this) {
    SettingsRoute.Root -> SettingsRootDest
    SettingsRoute.Schedule -> ScheduleDest()
    SettingsRoute.Clothes -> ClothesDest
    SettingsRoute.Region -> RegionDest
    SettingsRoute.Voice -> VoiceDest
    SettingsRoute.Display -> DisplayDest
    SettingsRoute.Holidays -> HolidaysDest
    SettingsRoute.Location -> LocationDest
    SettingsRoute.Calendar -> CalendarDest
    SettingsRoute.Forecasters -> ForecastersDest
    SettingsRoute.SmartHome -> SmartHomeDest
    SettingsRoute.Privacy -> PrivacyDest
    SettingsRoute.About -> AboutDest
}

@Composable
fun ClothesCastNavHost(
    app: ClothesCastApplication,
    navigateToTodayVersion: Int,
    startOnboarding: Boolean,
) {
    val nav = rememberNavController()

    // Notification taps snap to Today regardless of where the user was. The
    // back stack is cleared so a subsequent back exits the app rather than
    // walking back into a half-finished settings drill-down.
    LaunchedEffect(navigateToTodayVersion) {
        if (navigateToTodayVersion > 0) {
            nav.navigate(TodayRoute) {
                popUpTo(nav.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = nav,
        startDestination = if (startOnboarding) OnboardingRoute else TodayRoute,
    ) {
        composable<TodayRoute> {
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
                onNavigateToHolidays = { nav.navigate(HolidaysDest) },
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
    val onNavigate: (SettingsRoute) -> Unit = { nav.navigate(it.toDestination()) }
    val onBack: () -> Unit = { nav.popBackStack() }

    navigation<SettingsGraph>(startDestination = SettingsRootDest) {
        composable<SettingsRootDest> { entry ->
            SettingsSubPage(SettingsRoute.Root, entry.settingsViewModel(nav, app), onBack, onNavigate)
        }
        composable<ScheduleDest> { entry ->
            SettingsSubPage(
                route = SettingsRoute.Schedule,
                viewModel = entry.settingsViewModel(nav, app),
                onBack = onBack,
                onNavigate = onNavigate,
                onboardingLanding = entry.toRoute<ScheduleDest>().fromOnboarding,
                onFinishOnboarding = finishOnboarding,
            )
        }
        composable<ClothesDest> { e -> SettingsSubPage(SettingsRoute.Clothes, e.settingsViewModel(nav, app), onBack, onNavigate) }
        composable<RegionDest> { e -> SettingsSubPage(SettingsRoute.Region, e.settingsViewModel(nav, app), onBack, onNavigate) }
        composable<VoiceDest> { e -> SettingsSubPage(SettingsRoute.Voice, e.settingsViewModel(nav, app), onBack, onNavigate) }
        composable<DisplayDest> { e -> SettingsSubPage(SettingsRoute.Display, e.settingsViewModel(nav, app), onBack, onNavigate) }
        composable<HolidaysDest> { e -> SettingsSubPage(SettingsRoute.Holidays, e.settingsViewModel(nav, app), onBack, onNavigate) }
        composable<LocationDest> { e -> SettingsSubPage(SettingsRoute.Location, e.settingsViewModel(nav, app), onBack, onNavigate) }
        composable<CalendarDest> { e -> SettingsSubPage(SettingsRoute.Calendar, e.settingsViewModel(nav, app), onBack, onNavigate) }
        composable<ForecastersDest> { e -> SettingsSubPage(SettingsRoute.Forecasters, e.settingsViewModel(nav, app), onBack, onNavigate) }
        composable<SmartHomeDest> { e -> SettingsSubPage(SettingsRoute.SmartHome, e.settingsViewModel(nav, app), onBack, onNavigate) }
        composable<PrivacyDest> { e -> SettingsSubPage(SettingsRoute.Privacy, e.settingsViewModel(nav, app), onBack, onNavigate) }
        composable<AboutDest> { e -> SettingsSubPage(SettingsRoute.About, e.settingsViewModel(nav, app), onBack, onNavigate) }
    }
}

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
        refreshCachedOutfits = {
            val prefs = app.settingsRepository.preferences.first()
            app.insightCache.recomputeOutfits(prefs.clothesRules, prefs.defaultBottom)
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
        mqttPublisher = app.mqttPublisher,
        fullPublish = {
            val prefs = app.settingsRepository.preferences.first()
            val insight = app.insightCache.thisPeriod.first()
                ?: return@Factory app.mqttPublisher.publishTest()
            val formatter = InsightFormatter.forRegion(context, prefs.region, prefs.temperatureUnit)
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
        castAvailable = app.castContext != null,
        castNowAction = app.castInsightController?.let { controller ->
            {
                castCurrentInsight(
                    context = context,
                    settingsRepository = app.settingsRepository,
                    insightCache = app.insightCache,
                    calendarEventReader = app.calendarEventReader,
                    controller = controller,
                    locale = LocaleListCompat.getAdjustedDefault().get(0)
                        ?: java.util.Locale.getDefault(),
                )
            }
        },
    )
