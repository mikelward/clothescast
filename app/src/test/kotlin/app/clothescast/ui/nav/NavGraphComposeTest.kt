package app.clothescast.ui.nav

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Guards the launch path that the release build crashed on (the "Serializer for
 * class <obfuscated> is not found" startup crash). The type-safe Navigation
 * destinations in this package are `@Serializable`, and Compose Navigation
 * resolves each route's `KSerializer` *reflectively, by `KClass`*, while it
 * builds the graph at `NavHost` composition and again every time you
 * `navigate(route)`. A destination that isn't reflectively serializable —
 * missing `@Serializable`, or a shape kotlinx can't resolve — blows up on the
 * first frame, before the user touches anything.
 *
 * This builds a graph with the same route *shape* as [ClothesCastNavHost] (a
 * top-level set plus the nested Settings graph) with stub screen bodies, then
 * walks every destination. Stub bodies keep it fast and flake-free — we're
 * exercising the route wiring, not the screens or their ViewModels.
 *
 * Scope, stated honestly: this runs on **un-minified** bytecode, so it does
 * *not* catch R8 stripping a serializer in the release build — which is exactly
 * what crashed in #880. Catching that needs a launch smoke test on the
 * *minified* variant, and CI has no such test today: it runs the debug unit
 * tests plus assembleRelease on PRs and a release bundle on main, so the
 * minified variant is built but never launched. That failure mode is
 * therefore still **uncovered** until such a test is added. What this test catches cheaply, at push time, is
 * a destination wired into the graph without `@Serializable`, or one the
 * framework can't navigate to. New destinations must be added below.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class NavGraphComposeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // Every Settings destination, in the order ClothesCastNavHost registers
    // them. Listed by instance so navigate() turns each into its route string
    // via the route's KSerializer — the exact resolution that crashed.
    private val settingsDestinations: List<Any> = listOf(
        ScheduleDest, FormatDest, ClothesDest, RegionDest, VoiceDest,
        DisplayDest, LocationDest, CalendarDest, ForecastersDest,
        SmartHomeDest, PrivacyDest, AboutDest, DeveloperDest,
    )

    @Test
    fun `nav graph builds and every destination is reachable`() {
        lateinit var nav: NavHostController
        composeRule.setContent {
            nav = rememberNavController()
            // Same graph shape as ClothesCastNavHost; stub bodies on purpose.
            NavHost(navController = nav, startDestination = TodayRoute()) {
                composable<TodayRoute> {}
                composable<PairingRoute> {}
                navigation<SettingsGraph>(startDestination = SettingsRootDest) {
                    composable<SettingsRootDest> {}
                    composable<ScheduleDest> {}
                    composable<FormatDest> {}
                    composable<ClothesDest> {}
                    composable<RegionDest> {}
                    composable<VoiceDest> {}
                    composable<DisplayDest> {}
                    composable<LocationDest> {}
                    composable<CalendarDest> {}
                    composable<ForecastersDest> {}
                    composable<SmartHomeDest> {}
                    composable<PrivacyDest> {}
                    composable<AboutDest> {}
                    composable<DeveloperDest> {}
                }
            }
        }
        // Building the graph already serializes the start destination instance
        // (TodayRoute) and the nested graph's start (SettingsRootDest, an
        // object) — that alone reproduces the launch composition.
        composeRule.waitForIdle()

        // Then walk every other destination. Each navigate() generates the
        // route from the @Serializable instance via its KSerializer; a broken
        // or missing serializer throws right here.
        composeRule.runOnUiThread {
            nav.navigate(PairingRoute)
            nav.navigate(SettingsGraph) // resolves to SettingsRootDest
            settingsDestinations.forEach { nav.navigate(it) }
        }
        composeRule.waitForIdle()

        // We don't care where we land — only that none of the above threw and
        // the graph is live.
        nav.currentBackStackEntry.shouldNotBeNull()
    }
}
