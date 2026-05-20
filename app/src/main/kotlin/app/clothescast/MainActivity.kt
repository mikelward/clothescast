package app.clothescast

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clothescast.core.domain.model.ThemeMode
import app.clothescast.locale.AppLocale
import app.clothescast.notification.NotificationPermission
import app.clothescast.ui.isTelevision
import app.clothescast.ui.nav.ClothesCastNavHost
import app.clothescast.ui.theme.ClothesCastTheme
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    // Incremented every time a notification tap delivers EXTRA_NAVIGATE_TO_TODAY —
    // both via onNewIntent (activity already running) and via the launching intent
    // in onCreate (cold start / activity recreated after process death, where
    // rememberSaveable would otherwise restore the previously-saved screen, e.g.
    // Settings). ClothesCastNavHost observes this counter and snaps back to Today
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
        // shouldStartOnboarding for the initial-screen decision.
        val initialPrefs = runBlocking {
            app.settingsRepository.preferences.first().let {
                it.themeMode to it.colorPalette
            }
        }
        // Decide the start destination once, synchronously, so the first frame
        // is already correct (no flash of Today before snapping to Onboarding).
        // NavHost ignores this after process death — it restores its own saved
        // back stack — so it only governs a genuine first launch.
        val startOnboarding = shouldStartOnboarding(this, app)
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
                    // enableEdgeToEdge with SystemBarStyle.auto leaves
                    // isNavigationBarContrastEnforced = true (the AUTO night-mode
                    // path), so the system overlays its own translucent scrim
                    // across the nav-bar area "for contrast." With gesture nav
                    // the inset is only ~16-24 dp tall, so the top edge of that
                    // scrim reads as a sharp horizontal line on top of our
                    // content — especially against the scroll-fade gradient that
                    // lands at the same vertical position. The app already paints
                    // its own uniform background through that band, so the system
                    // scrim is both unnecessary and visually jarring.
                    window.isNavigationBarContrastEnforced = false
                    onDispose {}
                }
                ClothesCastTheme(darkTheme = darkTheme, colorPalette = colorPalette) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        ClothesCastNavHost(app, navigateToTodayVersion, startOnboarding)
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
         *  navigation counter when this is present so the nav host snaps to Today. */
        const val EXTRA_NAVIGATE_TO_TODAY = "navigate_to_today"
    }
}

// Decide the start destination once, synchronously. Permission checks are sync;
// DataStore reads (Gemini key + preferences) go through one Preferences fetch
// each, microseconds in practice — runBlocking here keeps the UX flicker-free
// (no flash of Today before snapping to Onboarding) at a negligible startup cost.
private fun shouldStartOnboarding(context: Context, app: ClothesCastApplication): Boolean {
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
    return !(notificationOk && keyOk && locationOk)
}
