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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.clothescast.core.domain.model.ForecastModel
import app.clothescast.core.domain.model.ThemeMode
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.model.defaultsFor
import app.clothescast.diag.DiagLog
import app.clothescast.diag.Telemetry
import app.clothescast.locale.AppLocale
import app.clothescast.ui.nav.ClothesCastNavHost
import app.clothescast.ui.theme.ClothesCastTheme
import app.clothescast.work.FetchAndNotifyWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Instant

/**
 * Resolves the per-forecaster chart-colour slot assignment from prefs: the
 * explicit selection (or the location-aware Auto default) reconciled against the
 * persisted slot map, so each forecaster's overlay colour is stable across edits.
 */
private fun colorSlotsOf(prefs: UserPreferences): Map<String, Int> =
    ForecastModel.assignColorSlots(
        prefs.forecastModels ?: ForecastModel.defaultsFor(prefs.location),
        prefs.forecasterColorSlots,
    )

class MainActivity : ComponentActivity() {
    // The latest intent delivered while the activity is already running (a
    // notification tap → onNewIntent). ClothesCastNavHost forwards it to the
    // NavController, which matches the Today deep link and navigates there.
    // Cold-start / post-process-death intents are handled automatically by the
    // NavController from the launch intent, so they don't go through here.
    private var deepLinkIntent by mutableStateOf<Intent?>(null)

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
        val app = application as ClothesCastApplication
        // Read the persisted theme synchronously so the first frame already
        // matches the user's pick (no flash of the wrong theme before the
        // collected flow catches up).
        val initialSnapshot = runBlocking { app.settingsRepository.preferences.first() }
        val initialPrefs = initialSnapshot.themeMode to initialSnapshot.colorPalette
        val initialColorSlots = colorSlotsOf(initialSnapshot)
        // Derive the theme/palette flows outside composition — invoking flow
        // operators (map) inside setContent recreates them on every recompose.
        val themeModeFlow = app.settingsRepository.preferences.map { it.themeMode }
        val colorPaletteFlow = app.settingsRepository.preferences.map { it.colorPalette }
        // Per-forecaster chart-colour slots: resolve the selection (or the
        // location-aware Auto default) against the persisted slot map so each
        // forecaster's overlay colour stays put across selection edits.
        val colorSlotsFlow = app.settingsRepository.preferences.map { colorSlotsOf(it) }
        try {
            setContent {
                val themeMode by themeModeFlow
                    .collectAsStateWithLifecycle(initialValue = initialPrefs.first)
                val colorPalette by colorPaletteFlow
                    .collectAsStateWithLifecycle(initialValue = initialPrefs.second)
                val colorSlots by colorSlotsFlow
                    .collectAsStateWithLifecycle(initialValue = initialColorSlots)
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
                ClothesCastTheme(
                    darkTheme = darkTheme,
                    colorPalette = colorPalette,
                    colorSlots = colorSlots,
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        ClothesCastNavHost(app, deepLinkIntent)
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
        // Through Telemetry, not straight to Crashlytics: this runs before the
        // stored telemetry choice has necessarily been applied, and a direct
        // `recordException` here could queue an app-generated report during
        // the migrating launch's window — the one thing PRIVACY.md says does
        // not happen (Codex, PR #1161). Telemetry holds it until the choice is
        // on both SDKs rather than dropping it, so a consenting user still
        // gets the incidence data this exists for.
        Telemetry.recordComposeStartupCrash(
            (application as ClothesCastApplication).applicationScope,
            error,
        )
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
        // Replace the stored intent (so a later config-change replays this one,
        // already marked handled by the NavController) and hand it to the nav
        // host to match against the Today deep link.
        setIntent(intent)
        deepLinkIntent = intent
    }

    override fun onStart() {
        super.onStart()
        // Opportunistic refresh: when the user opens the app to an empty cache
        // or a cached insight that's gone stale (>= SILENT_REFRESH_MIN_AGE since
        // the last fetch), kick a silent background fetch so the screen (and any
        // home-screen widget) re-renders off fresh data without waiting for the
        // next scheduled alarm. The empty-cache case matters now that onboarding
        // is gone and both delivery slots are opt-in — otherwise a fresh install
        // that never enabled notifications would sit on "No forecast yet" until
        // the user found the manual Refresh. The worker picks the period itself
        // based on the user's schedule + wall-clock time (see
        // [FetchAndNotifyWorker.currentPeriodForSchedule]), so a cache stuck in
        // the wrong window after a missed alarm gets corrected on the next open.
        // REPLACE-deduped on the worker side, so the per-recreate onStart fires
        // (config changes, returning from a permission dialog) coalesce into a
        // single trailing run and can't be swallowed by an earlier one stuck in
        // retry-backoff.
        val app = application as ClothesCastApplication
        lifecycleScope.launch {
            val snapshot = runCatching { app.insightCache.thisPeriod.first() }
                .getOrElse {
                    DiagLog.w(TAG, "App-open freshness check failed; skipping silent refresh.", it)
                    return@launch
                }
            if (!FetchAndNotifyWorker.shouldSilentlyRefresh(snapshot, Instant.now())) return@launch
            FetchAndNotifyWorker.enqueueSilentRefresh(applicationContext)
        }
    }

    companion object {
        private const val TAG = "MainActivity"

        /** Deep-link URI that lands on the Today screen. Notification taps target
         *  this; ClothesCastNavHost declares a matching navDeepLink on TodayRoute.
         *  An optional `?page=` query selects the pager page (0 = current period,
         *  2 = 7-day) — the feels-like widgets use it; see [todayPageUri]. */
        const val DEEP_LINK_TODAY = "clothescast://today"

        /** [DEEP_LINK_TODAY] targeting a specific pager [page]. Page 0 omits the
         *  query so the bare notification deep link keeps matching unchanged. */
        fun todayPageUri(page: Int): String =
            if (page <= 0) DEEP_LINK_TODAY else "$DEEP_LINK_TODAY?page=$page"

        /** Tap intent for notifications: opens (or brings forward) MainActivity and
         *  deep-links to Today. SINGLE_TOP/CLEAR_TOP reuses a running task. */
        fun todayTapIntent(context: Context): Intent =
            Intent(Intent.ACTION_VIEW, DEEP_LINK_TODAY.toUri(), context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
    }
}
