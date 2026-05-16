package app.clothescast

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkManager
import app.clothescast.core.domain.model.ThemeMode
import app.clothescast.locale.AppLocale
import app.clothescast.location.LocationResolver
import app.clothescast.notification.NotificationPermission
import app.clothescast.ui.isTelevision
import app.clothescast.ui.onboarding.OnboardingScreen
import app.clothescast.ui.onboarding.OnboardingViewModel
import app.clothescast.ui.pairing.PairingScreen
import app.clothescast.ui.pairing.PairingViewModel
import app.clothescast.ui.settings.SettingsRoute
import app.clothescast.ui.settings.SettingsScreen
import app.clothescast.ui.settings.SettingsViewModel
import app.clothescast.ui.theme.ClothesCastTheme
import app.clothescast.ui.today.TodayScreen
import app.clothescast.ui.today.TodayViewModel
import app.clothescast.widget.OutfitWidget
import app.clothescast.work.FetchAndNotifyWorker
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private enum class Screen { Today, Settings, Onboarding, Pairing }

class MainActivity : ComponentActivity() {
    // Incremented every time a notification tap delivers EXTRA_NAVIGATE_TO_TODAY —
    // both via onNewIntent (activity already running) and via the launching intent
    // in onCreate (cold start / activity recreated after process death, where
    // rememberSaveable would otherwise restore the previously-saved screen, e.g.
    // Settings). ClothesCastNav observes this counter and snaps back to Today
    // whenever it ticks, so a notification tap reliably lands the user on Today
    // regardless of cold/warm start.
    private var navigateToTodayVersion by mutableIntStateOf(0)

    override fun attachBaseContext(newBase: Context) {
        // Wrap with the persisted per-app locale so Activity Resources render
        // in the user's chosen Region. No-op on API 33+ where the framework
        // routes the LocaleManager-supplied locale through automatically.
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge is forced by the system on targetSdk 35; opting in
        // explicitly lets us drive the status / nav bar icon contrast from
        // our own theme choice (see the DisposableEffect below) instead of
        // leaving the icons mismatched against our surface — light icons on
        // our light background, or vice versa.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        consumeNavigateToTodayExtra(intent)
        val app = application as ClothesCastApplication
        // Read the persisted theme synchronously so the first frame already
        // matches the user's pick — same flicker-avoidance pattern used in
        // ClothesCastNav for the initial-screen decision.
        val initialPrefs = runBlocking {
            app.settingsRepository.preferences.first().let {
                it.themeMode to it.colorPalette
            }
        }
        try {
            setContent {
                val themeMode by app.settingsRepository.preferences
                    .map { it.themeMode }
                    .collectAsStateWithLifecycle(initialValue = initialPrefs.first)
                val colorPalette by app.settingsRepository.preferences
                    .map { it.colorPalette }
                    .collectAsStateWithLifecycle(initialValue = initialPrefs.second)
                val darkTheme = when (themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
                // Re-apply edge-to-edge with the in-app darkTheme as the
                // dark-mode source so the bar icons flip when the user picks
                // Light or Dark explicitly (not just when the system theme
                // changes). Transparent scrims on both bars — the surface
                // background shows through directly.
                DisposableEffect(darkTheme) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                        ) { darkTheme },
                        navigationBarStyle = SystemBarStyle.auto(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                        ) { darkTheme },
                    )
                    onDispose {}
                }
                ClothesCastTheme(darkTheme = darkTheme, colorPalette = colorPalette) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        ClothesCastNav(app, navigateToTodayVersion)
                    }
                }
            }
        } catch (e: NoSuchFieldError) {
            // Some OEM-modified Android 12+ ROMs ship a stripped framework.jar
            // without Configuration.fontWeightAdjustment, even though they
            // report API 31+. AndroidComposeView's constructor reads that
            // field unconditionally on API 31+, so setContent throws here
            // before any composable runs. Fall back to a native View so the
            // user sees an explanation instead of a force-close, and record
            // a non-fatal so we can track incidence. Other NoSuchFieldErrors
            // (an unrelated library or framework field mismatch) re-throw so
            // we still notice them as fatals in Crashlytics.
            if (e.message?.contains("fontWeightAdjustment") != true) throw e
            handleComposeStartupCrash(e)
        }
    }

    private fun handleComposeStartupCrash(error: NoSuchFieldError) {
        runCatching {
            if (FirebaseApp.getApps(this).isNotEmpty()) {
                FirebaseCrashlytics.getInstance().apply {
                    setCustomKey("compose_startup_unsupported", true)
                    recordException(error)
                }
            }
        }
        val padding = (24 * resources.displayMetrics.density).toInt()
        setContentView(
            TextView(this).apply {
                setPadding(padding, padding, padding, padding)
                gravity = Gravity.CENTER
                textSize = 16f
                text = getString(R.string.error_device_incompatible)
            },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Update the stored intent so a later configuration change replays this
        // (already-consumed) intent rather than the original launching one.
        setIntent(intent)
        consumeNavigateToTodayExtra(intent)
    }

    // Removes the extra after handling so a later onCreate (rotation, process
    // recreation) replaying the same intent doesn't snap the user back to Today
    // after they've navigated away.
    private fun consumeNavigateToTodayExtra(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_NAVIGATE_TO_TODAY, false) == true) {
            navigateToTodayVersion++
            intent.removeExtra(EXTRA_NAVIGATE_TO_TODAY)
        }
    }

    companion object {
        /** Extra set by all notification tap intents. MainActivity increments its
         *  navigation counter when this is present so ClothesCastNav snaps to Today. */
        const val EXTRA_NAVIGATE_TO_TODAY = "navigate_to_today"
    }
}

@Composable
private fun ClothesCastNav(app: ClothesCastApplication, navigateToTodayVersion: Int) {
    val context = LocalContext.current

    // Decide initial screen once on first composition. Permission checks are sync;
    // DataStore reads (Gemini key + preferences) go through one Preferences fetch
    // each, microseconds in practice — runBlocking here keeps the UX flicker-free
    // (no flash of Today before snapping to Onboarding) at a negligible startup cost.
    val initialScreen = remember {
        val tv = isTelevision(context)
        // TV OS does not expose POST_NOTIFICATIONS or GPS-based location; skip
        // both checks so a configured-key + city TV install goes straight to Today.
        val notificationOk = tv || NotificationPermission.isGranted(context)
        val keyOk = runBlocking { app.secureKeyStore.geminiKeyConfiguredFlow.first() }
        val prefs = runBlocking { app.settingsRepository.preferences.first() }
        val locationOk = if (tv) {
            // On TV only a manually picked city counts — device location is unavailable.
            prefs.location != null
        } else {
            // Location is "configured" if either branch is filled in — device-location
            // toggle on (with permission) or a manual city stored.
            val coarseGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            (prefs.useDeviceLocation && coarseGranted) || prefs.location != null
        }
        if (notificationOk && keyOk && locationOk) Screen.Today else Screen.Onboarding
    }

    var screen by rememberSaveable { mutableStateOf(initialScreen) }
    // Holds the SettingsRoute we want to land on when entering Settings programmatically
    // (e.g. from onboarding's "Continue"). Saved as a name string so rememberSaveable
    // doesn't need a custom Saver. Consumed once and reset to null on the way out.
    var settingsInitialRoute by rememberSaveable { mutableStateOf<String?>(null) }

    // When the user taps a notification while the app is already running, MainActivity
    // increments navigateToTodayVersion via onNewIntent. Snap back to Today so the
    // user always lands on the screen that actually shows the insight/alert they tapped.
    LaunchedEffect(navigateToTodayVersion) {
        if (navigateToTodayVersion > 0) {
            screen = Screen.Today
            settingsInitialRoute = null
        }
    }

    BackHandler(enabled = screen == Screen.Settings) {
        screen = Screen.Today
        settingsInitialRoute = null
    }

    when (screen) {
        Screen.Today -> {
            val today: TodayViewModel = viewModel(
                factory = TodayViewModel.Factory(
                    insightCache = app.insightCache,
                    workManager = WorkManager.getInstance(app),
                    settingsRepository = app.settingsRepository,
                    refreshOutfitWidget = {
                        runCatching { OutfitWidget().updateAll(context.applicationContext) }
                    },
                ),
            )
            TodayScreen(
                viewModel = today,
                onNavigateToSettings = {
                    settingsInitialRoute = null
                    screen = Screen.Settings
                },
                onNavigateToAbout = {
                    settingsInitialRoute = SettingsRoute.About.name
                    screen = Screen.Settings
                },
                onNavigateToLocation = {
                    settingsInitialRoute = SettingsRoute.Location.name
                    screen = Screen.Settings
                },
                onNavigateToPrivacy = {
                    settingsInitialRoute = SettingsRoute.Privacy.name
                    screen = Screen.Settings
                },
            )
        }
        Screen.Settings -> {
            val settings: SettingsViewModel = viewModel(
                factory = SettingsViewModel.Factory(
                    settingsRepository = app.settingsRepository,
                    keyStore = app.secureKeyStore,
                    rearmAlarm = app.dailyAlarmScheduler::schedule,
                    cancelAlarm = app.dailyAlarmScheduler::cancel,
                    geocodingClient = app.geocodingClient,
                    voiceEnumerator = app.androidTtsVoiceEnumerator,
                    applyAppLocale = { region ->
                        AppLocale.apply(app, region)
                        // API 33+ recreates Activities automatically when
                        // applicationLocales changes; below that we have to
                        // do it ourselves so the currently visible screen
                        // re-renders in the new language instead of waiting
                        // until the user navigates away and back.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            (context as? Activity)?.recreate()
                        }
                    },
                    refreshLocationCache = {
                        // Eager device-location populate when the user flips
                        // device-location ON. Cache-only path — resolves the
                        // fix and writes through to settings without running
                        // the insight / notify / TTS pipeline, so toggling at
                        // 10am after the morning run already fired doesn't
                        // double-notify.
                        FetchAndNotifyWorker.enqueueLocationCacheRefresh(context)
                    },
                    refreshCachedOutfits = {
                        // Re-pick the home-screen outfit against the just-written
                        // clothes-rule preferences so the icon flips immediately
                        // when the user edits a rule or the default-bottom
                        // picker. The cache is the canonical source for the
                        // Today screen and the widget; mutating it here means
                        // the user doesn't have to wait for the next scheduled
                        // or manual refresh to see their choice take effect.
                        // We also push the widget update — the cache flow
                        // wakes the Today screen automatically, but the widget
                        // only refreshes when we explicitly tell it to.
                        val prefs = app.settingsRepository.preferences.first()
                        app.insightCache.recomputeOutfits(prefs.clothesRules, prefs.defaultBottom)
                        runCatching { OutfitWidget().updateAll(context.applicationContext) }
                    },
                    resolveDeviceLocationWithCity = {
                        // "Use my current location" tap on the home-pin card.
                        // We want a precise lat/lon (not a geocoder centroid)
                        // so the at-home gate's 1 km radius actually fires;
                        // resolveFresh enforces the same 5-minute ceiling
                        // the worker uses, so a stale "I was at work
                        // yesterday" cache miss can't silently land as the
                        // user's home — better to no-op the button and let
                        // them retry than save the wrong coordinate.
                        // resolveCityName labels the fix for UI display. Both
                        // swallow failures internally — null falls through to
                        // a no-op in the VM.
                        app.locationResolver.resolveFresh(
                            LocationResolver.FRESH_FIX_MAX_AGE_MS,
                        )?.let { fix ->
                            val city = app.reverseGeocoder.resolveCityName(fix.latitude, fix.longitude)
                            if (city != null) fix.copy(displayName = city) else fix
                        }
                    },
                    workManager = WorkManager.getInstance(app),
                ),
            )
            SettingsScreen(
                viewModel = settings,
                onNavigateBack = {
                    screen = Screen.Today
                    settingsInitialRoute = null
                },
                initialRoute = settingsInitialRoute
                    ?.let { runCatching { SettingsRoute.valueOf(it) }.getOrNull() },
            )
        }
        Screen.Onboarding -> {
            val onboarding: OnboardingViewModel = viewModel(
                factory = OnboardingViewModel.Factory(
                    secureKeyStore = app.secureKeyStore,
                    settingsRepository = app.settingsRepository,
                    geocodingClient = app.geocodingClient,
                    refreshLocationCache = {
                        // Eager device-location populate when the user grants
                        // location permission during onboarding. Cache-only
                        // path — same worker used by Settings — so the
                        // resolved city surfaces in seconds and the user can
                        // tell at a glance whether they need to enter a
                        // manual fallback instead.
                        FetchAndNotifyWorker.enqueueLocationCacheRefresh(context)
                    },
                    workManager = WorkManager.getInstance(app),
                ),
            )
            OnboardingScreen(
                viewModel = onboarding,
                onPairFromPhone = { screen = Screen.Pairing },
                onContinue = {
                    settingsInitialRoute = SettingsRoute.Schedule.name
                    screen = Screen.Settings
                },
                onSkip = {
                    settingsInitialRoute = null
                    screen = Screen.Today
                },
            )
        }
        Screen.Pairing -> {
            val pairing: PairingViewModel = viewModel(
                factory = PairingViewModel.Factory(
                    secureKeyStore = app.secureKeyStore,
                    settingsRepository = app.settingsRepository,
                ),
            )
            PairingScreen(
                viewModel = pairing,
                onSuccess = { screen = Screen.Onboarding },
                onCancel = { screen = Screen.Onboarding },
            )
        }
    }
}
