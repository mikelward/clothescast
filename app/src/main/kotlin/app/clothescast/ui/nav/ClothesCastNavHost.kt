package app.clothescast.ui.nav

import android.content.Intent
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import androidx.work.WorkManager
import app.clothescast.ClothesCastApplication
import app.clothescast.MainActivity
import app.clothescast.R
import app.clothescast.calendar.resolveHolidayTheme
import app.clothescast.cast.castCurrentInsight
import app.clothescast.core.data.weather.GoogleWeatherProbe
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.diag.DiagLog
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.insight.InsightFormatter
import app.clothescast.locale.AppLocale
import app.clothescast.location.LocationResolver
import app.clothescast.ui.LocalNavigateToAbout
import app.clothescast.ui.garment.outfitCardInfoLines
import app.clothescast.ui.garment.renderOutfitCard
import app.clothescast.ui.pairing.PairingScreen
import app.clothescast.ui.pairing.PairingViewModel
import app.clothescast.ui.settings.AboutPage
import app.clothescast.ui.settings.CalendarPage
import app.clothescast.ui.settings.ClothesPage
import app.clothescast.ui.settings.DeveloperPage
import app.clothescast.ui.settings.DisplayPage
import app.clothescast.ui.settings.ForecastersPage
import app.clothescast.ui.settings.FormatPage
import app.clothescast.ui.settings.LocalSettingsDoneAction
import app.clothescast.ui.settings.LocationPage
import app.clothescast.ui.settings.PrivacyPage
import app.clothescast.ui.settings.RegionPage
import app.clothescast.ui.settings.SchedulePage
import app.clothescast.ui.settings.SettingsDest
import app.clothescast.ui.settings.SettingsMenuItem
import app.clothescast.ui.settings.SettingsRootPage
import app.clothescast.ui.settings.SettingsViewModel
import app.clothescast.ui.settings.SmartHomePage
import app.clothescast.ui.settings.VoicePage
import app.clothescast.ui.today.TodayScreen
import app.clothescast.ui.today.TodayViewModel
import app.clothescast.work.FetchAndNotifyWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

// Duration of the push/pop slide between destinations. Short enough to feel
// snappy (and to keep the can't-tap-yet window small), long enough to read as a
// directional transition rather than a jump.
private const val NAV_ANIM_MS = 200

// Type-safe destinations. The four top-level screens plus a nested graph for
// Settings — each settings sub-page is its own destination so the framework's
// back stack handles up-navigation. No hand-maintained "current route" state,
// no goBackOrUp(), no per-screen BackHandler.
// [page] selects which pager page the Today screen opens on: 0 = current period,
// 1 = next period, 2 = 7-day deck. Defaulted (so it rides as an optional `?page=`
// query on the deep link), which keeps the bare `clothescast://today` notification
// link matching with page 0. The feels-like home-screen widgets deep-link to
// page 0 / 2.
@Serializable internal data class TodayRoute(val page: Int = 0)
@Serializable internal object PairingRoute

@Serializable internal object SettingsGraph
@Serializable internal object SettingsRootDest
@Serializable internal object ScheduleDest
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

    // Provided once for the whole tree so the shared bug-report overflow menu —
    // on Today's top bar and every settings page's — can open About from
    // anywhere. launchSingleTop avoids stacking a second About when the menu is
    // used while already on it.
    CompositionLocalProvider(
        LocalNavigateToAbout provides { nav.navigate(AboutDest) { launchSingleTop = true } },
    ) {
    NavHost(
        navController = nav,
        startDestination = TodayRoute(),
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
        ) { entry ->
            val today: TodayViewModel = viewModel(factory = todayViewModelFactory(app))
            TodayScreen(
                viewModel = today,
                startPage = entry.toRoute<TodayRoute>().page,
                onNavigateToSettings = { nav.navigate(SettingsGraph) },
                // Deep links push the target page straight onto Today, so a
                // single back returns to Today (no detour through the root list).
                onNavigateToAbout = { nav.navigate(AboutDest) },
                onNavigateToLocation = { nav.navigate(LocationDest) },
                onNavigateToPrivacy = { nav.navigate(PrivacyDest) },
                onNavigateToClothes = { nav.navigate(ClothesDest) },
                onNavigateToCalendar = { nav.navigate(CalendarDest) },
                onNavigateToSchedule = { nav.navigate(ScheduleDest) },
                onNavigateToDeveloper = { nav.navigate(DeveloperDest) },
                onNavigateToFormat = { nav.navigate(FormatDest) },
                onNavigateToVoice = { nav.navigate(VoiceDest) },
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
}

private fun NavGraphBuilder.settingsGraph(nav: NavController, app: ClothesCastApplication) {
    val onBack: () -> Unit = { nav.popBackStack() }

    navigation<SettingsGraph>(startDestination = SettingsRootDest) {
        // The root menu is the hub; its back arrow returns to Today. It's not
        // wrapped in SettingsSubPage, so it never grows a Done bar even when
        // opened straight from Today — Done is for focused sub-tasks, not the menu.
        composable<SettingsRootDest> { e ->
            SettingsRootPage(
                viewModel = e.settingsViewModel(nav, app),
                items = settingsMenu(nav),
                onBack = onBack,
                onOpenLocation = { nav.navigate(LocationDest) },
            )
        }
        composable<ScheduleDest> { e ->
            SettingsSubPage(nav, e) {
                SchedulePage(
                    viewModel = e.settingsViewModel(nav, app),
                    onBack = onBack,
                    onSetUpSpeech = { nav.navigate(VoiceDest) },
                    onOpenSmartHome = { nav.navigate(SmartHomeDest) },
                )
            }
        }
        composable<FormatDest> { e -> SettingsSubPage(nav, e) { FormatPage(e.settingsViewModel(nav, app), onBack) } }
        composable<ClothesDest> { e -> SettingsSubPage(nav, e) { ClothesPage(e.settingsViewModel(nav, app), onBack) } }
        composable<RegionDest> { e -> SettingsSubPage(nav, e) { RegionPage(e.settingsViewModel(nav, app), onBack) } }
        composable<VoiceDest> { e ->
            SettingsSubPage(nav, e) {
                VoicePage(
                    viewModel = e.settingsViewModel(nav, app),
                    onBack = onBack,
                    onPairFromPhone = { nav.navigate(PairingRoute) },
                )
            }
        }
        composable<DisplayDest> { e -> SettingsSubPage(nav, e) { DisplayPage(e.settingsViewModel(nav, app), onBack) } }
        composable<LocationDest> { e -> SettingsSubPage(nav, e) { LocationPage(e.settingsViewModel(nav, app), onBack) } }
        composable<CalendarDest> { e ->
            SettingsSubPage(nav, e) {
                CalendarPage(
                    viewModel = e.settingsViewModel(nav, app),
                    onBack = onBack,
                    onNavigateToRegion = { nav.navigate(RegionDest) },
                    onNavigateToLocation = { nav.navigate(LocationDest) },
                )
            }
        }
        composable<ForecastersDest> { e -> SettingsSubPage(nav, e) { ForecastersPage(e.settingsViewModel(nav, app), onBack) } }
        composable<SmartHomeDest> { e ->
            SettingsSubPage(nav, e) {
                SmartHomePage(
                    viewModel = e.settingsViewModel(nav, app),
                    onBack = onBack,
                    onSetUpSpeech = { nav.navigate(VoiceDest) },
                )
            }
        }
        composable<PrivacyDest> { e -> SettingsSubPage(nav, e) { PrivacyPage(e.settingsViewModel(nav, app), onBack) } }
        composable<AboutDest> { e -> SettingsSubPage(nav, e) { AboutPage(onBack) } }
        composable<DeveloperDest> { e -> SettingsSubPage(nav, e) { DeveloperPage(e.settingsViewModel(nav, app), onBack) } }
    }
}

/**
 * Wraps a Settings sub-page, providing [LocalSettingsDoneAction] so the page's
 * [app.clothescast.ui.settings.SettingsScaffold] shows a bottom "Done" bar when
 * the page was opened from outside the Settings root menu — deep-linked from a
 * Today promo/banner, or pushed from another settings page's setup jump
 * (Schedule / Smart Home → Voice). Done returns to wherever the page opened.
 *
 * [NavController.previousBackStackEntry] skips NavGraph nodes, so it's the
 * visible screen beneath this one: [SettingsRootDest] when reached through the
 * menu — no Done, the back arrow goes back to the menu — or Today / another
 * sub-page otherwise, where Done pops back to it. Computed once per entry so the
 * opener is captured when the page is first shown.
 */
@OptIn(ExperimentalSerializationApi::class)
@Composable
private fun SettingsSubPage(
    nav: NavController,
    entry: NavBackStackEntry,
    content: @Composable () -> Unit,
) {
    val doneAction: (() -> Unit)? = remember(entry) {
        // A type-safe destination's route is registered as its serializer's
        // serial name, so comparing against SettingsRootDest's serial name
        // identifies the menu hub without depending on NavDestination.hasRoute
        // (which isn't reachable as a KClass overload from app code in nav 2.8).
        val rootRoute = serializer<SettingsRootDest>().descriptor.serialName
        val below = nav.previousBackStackEntry?.destination
        if (below != null && below.route != rootRoute) {
            { nav.popBackStack() }
        } else {
            null
        }
    }
    CompositionLocalProvider(LocalSettingsDoneAction provides doneAction, content = content)
}

// The Settings root menu's order, labels, and subtitles live in SettingsDest;
// this function only attaches each row to its navigation target. Order matches
// the list the user sees: most-tweaked rules first, set-once config next, data
// sources last. Root and About aren't in SettingsDest (Root is the menu itself,
// About is reached only from Today's overflow).
private fun settingsMenu(nav: NavController): List<SettingsMenuItem> =
    SettingsDest.entries.map { dest ->
        SettingsMenuItem(dest.titleRes, dest.subtitleRes) { nav.openSettingsDest(dest) }
    }

// Exhaustive `when` over SettingsDest — adding a new enum value here is a
// compile error until you wire its navigation target, which is the whole point
// of routing every row through the enum.
private fun NavController.openSettingsDest(dest: SettingsDest) = when (dest) {
    SettingsDest.SCHEDULE -> navigate(ScheduleDest)
    SettingsDest.CLOTHES -> navigate(ClothesDest)
    SettingsDest.FORMAT -> navigate(FormatDest)
    SettingsDest.LOCATION -> navigate(LocationDest)
    SettingsDest.REGION -> navigate(RegionDest)
    SettingsDest.VOICE -> navigate(VoiceDest)
    SettingsDest.DISPLAY -> navigate(DisplayDest)
    SettingsDest.CALENDAR -> navigate(CalendarDest)
    SettingsDest.FORECASTERS -> navigate(ForecastersDest)
    SettingsDest.SMART_HOME -> navigate(SmartHomeDest)
    SettingsDest.PRIVACY -> navigate(PrivacyDest)
    SettingsDest.DEVELOPER -> navigate(DeveloperDest)
}

// One SettingsViewModel shared across every settings destination, scoped to the
// SettingsGraph back-stack entry. Replaces the single VM the old monolithic
// SettingsScreen held; surviving config changes and process death the same way.
@Composable
private fun NavBackStackEntry.settingsViewModel(
    nav: NavController,
    app: ClothesCastApplication,
): SettingsViewModel {
    val parentEntry = remember(this) { nav.getBackStackEntry<SettingsGraph>() }
    return viewModel(viewModelStoreOwner = parentEntry, factory = settingsViewModelFactory(app))
}

// Factory lambdas capture only [app], never an Activity context: the ViewModels
// outlive any one Activity (they're scoped to a back-stack entry and survive
// config changes), and `viewModel(factory = …)` consults the factory only on
// first creation — a captured LocalContext would pin the original Activity for
// the VM's lifetime (leak) and act on the destroyed instance after rotation.
private fun todayViewModelFactory(app: ClothesCastApplication) =
    TodayViewModel.Factory(
        insightCache = app.insightCache,
        workManager = WorkManager.getInstance(app),
        settingsRepository = app.settingsRepository,
        deriveInsight = app.deriveInsight,
        calendarEventReader = app.calendarEventReader,
        geminiKeyConfigured = app.secureKeyStore.geminiKeyConfiguredFlow,
        geminiKeyNeedsReentry = app.secureKeyStore.geminiKeyNeedsReentryFlow,
    )

private fun settingsViewModelFactory(app: ClothesCastApplication) =
    SettingsViewModel.Factory(
        settingsRepository = app.settingsRepository,
        keyStore = app.secureKeyStore,
        rearmAlarm = app.dailyAlarmScheduler::schedule,
        cancelAlarm = app.dailyAlarmScheduler::cancel,
        geocodingClient = app.geocodingClient,
        voiceEnumerator = app.androidTtsVoiceEnumerator,
        applyAppLocale = { region ->
            AppLocale.apply(app, region)
            // API 31/32 has no LocaleManager to recreate automatically, so the
            // visible Activity is recreated by hand — resolved *at call time*
            // (see the factory note above): the VM survives config changes, so
            // a captured Activity would be the destroyed pre-rotation instance,
            // whose recreate() is a no-op and the new locale wouldn't show.
            app.currentResumedActivity()?.recreate()
        },
        refreshLocationCache = {
            FetchAndNotifyWorker.enqueueLocationCacheRefresh(app)
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
                app,
                prefs.region,
                prefs.temperatureUnit,
                prefs.rangeFormat,
                prefs.clothesFormat,
                prefs.bottomsFormat,
                prefs.accessoriesFormat,
                prefs.periodPreamble,
                prefs.wearPreamble,
            )
            val prose = formatter.format(insight.summary)
            val png: ByteArray? = insight.outfit?.let { outfit ->
                runCatching {
                    val info = outfitCardInfoLines(
                        context = app,
                        formatter = formatter,
                        hourly = insight.hourly,
                        temperatureUnit = prefs.temperatureUnit,
                        windSpeedUnit = prefs.windSpeedUnit,
                    )
                    val header = app.getString(
                        if (insight.period == ForecastPeriod.TODAY) R.string.outfit_card_header_today
                        else R.string.outfit_card_header_tonight
                    )
                    val theme = resolveHolidayTheme(prefs, app.calendarEventReader)
                    val topColors: Map<OutfitSuggestion.Top, Long> =
                        prefs.outfitTopColors + (theme?.topOverrides ?: emptyMap())
                    val bottomColors: Map<OutfitSuggestion.Bottom, Long> =
                        prefs.outfitBottomColors + (theme?.bottomOverrides ?: emptyMap())
                    val handsColors: Map<OutfitSuggestion.Hands, Long> = prefs.outfitHandsColors
                    val carriedColors: Map<OutfitSuggestion.Carried, Long> = prefs.outfitCarriedColors
                    val outerColors: Map<OutfitSuggestion.Outer, Long> = prefs.outfitOuterColors
                    val topStrokes: Map<OutfitSuggestion.Top, Long> =
                        theme?.topStrokeOverrides ?: emptyMap()
                    val bottomStrokes: Map<OutfitSuggestion.Bottom, Long> =
                        theme?.bottomStrokeOverrides ?: emptyMap()
                    renderOutfitCard(
                        context = app,
                        outfit = outfit,
                        header = header,
                        prose = prose,
                        info = info,
                        topColors = topColors,
                        bottomColors = bottomColors,
                        handsColors = handsColors,
                        carriedColors = carriedColors,
                        outerColors = outerColors,
                        topStrokes = topStrokes,
                        bottomStrokes = bottomStrokes,
                    )
                }.getOrNull()
            }
            app.mqttPublisher.publishIfEnabled(insight.period, prose, image = png, hasEvents = insight.hasEvents)
        },
        discovery = app.homeAssistantDiscovery,
        castRouteDiscovery = app.castRouteDiscovery,
        calendarEventReader = app.calendarEventReader,
        castAvailable = app.castContext != null,
        sharedTtsAvailable = app.sharedTtsAvailable,
        castNowAction = app.castInsightController?.let { controller ->
            {
                castCurrentInsight(
                    context = app,
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
        googleWeatherProbe = {
            // Probe with the same location the forecast would use, gated the
            // same way: the cached settings location, falling back to a device
            // fix ONLY when the user has device location enabled (mirrors
            // FetchAndNotifyWorker.resolveLocation). Without this gate, opening
            // the page with device location off and no saved city would resolve
            // and send device coordinates to Google in a state where the
            // forecast itself would report "no location". The key rides the
            // same BYOK Gemini slot the forecast path reads.
            val prefs = app.settingsRepository.preferences.first()
            val location = prefs.location
                ?: if (prefs.useDeviceLocation) app.locationResolver.resolve() else null
            // Rethrow cancellation rather than swallowing it: if the probe is
            // superseded (key replaced mid-probe) or the VM is cleared while
            // the key read is suspended, a runCatching would turn the cancel
            // into an empty key -> NoKey and let the dead probe publish a stale
            // result, breaking the supersede contract. Only a genuine read
            // failure (e.g. corrupt ciphertext after a Keystore reset) degrades
            // to the blank-key/NoKey path. Mirrors the extraModelHourly wiring
            // in ClothesCastApplication.
            val key = try {
                app.secureKeyStore.getGoogleApiKey().orEmpty()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                DiagLog.w("GoogleWeather", "Google API key unavailable; probe reports no key", e)
                ""
            }
            when {
                key.isBlank() -> GoogleWeatherProbe.NoKey
                // No location to probe with — report a generic failure rather
                // than guess coordinates; the status line invites a retry.
                location == null -> GoogleWeatherProbe.Failed(httpStatus = null)
                else -> app.googleWeatherClient.probe(location, key)
            }
        },
    )
