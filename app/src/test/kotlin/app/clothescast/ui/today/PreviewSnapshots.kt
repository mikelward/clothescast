package app.clothescast.ui.today

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clothescast.notification.NotificationIconSweaterPreview
import app.clothescast.notification.NotificationIconTShirtPreview
import app.clothescast.notification.NotificationIconThickJacketPreview
import app.clothescast.ui.LauncherIconDevPreview
import app.clothescast.ui.LauncherIconPreview
import app.clothescast.ui.onboarding.OnboardingCompletePreview
import app.clothescast.ui.onboarding.OnboardingFreshPreview
import app.clothescast.ui.onboarding.OnboardingPartialPreview
import app.clothescast.ui.settings.SettingsCalendarPreview
import app.clothescast.ui.settings.SettingsClothesFahrenheitPreview
import app.clothescast.ui.settings.SettingsClothesPreview
import app.clothescast.ui.settings.SettingsDisplayPreview
import app.clothescast.ui.settings.SettingsLocationPreview
import app.clothescast.ui.settings.SettingsPrivacyPreview
import app.clothescast.ui.settings.SettingsRegionPreview
import app.clothescast.ui.settings.SettingsRootPreview
import app.clothescast.ui.settings.SettingsSchedulePreview
import app.clothescast.ui.settings.SettingsVoiceDevicePreview
import app.clothescast.ui.settings.SettingsVoiceGeminiPreview
import app.clothescast.widget.WidgetEmptyPreview
import app.clothescast.widget.WidgetTodayJacketPantsPreview
import app.clothescast.widget.WidgetTodayTShirtShortsPreview
import app.clothescast.widget.WidgetTonightDarkPreview
import app.clothescast.widget.WidgetTonightSweaterPantsPreview
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

//
// Calls every preview wrapper across the app — `ui/today/TodayPreviews.kt`,
// `ui/onboarding/OnboardingPreviews.kt`, `ui/settings/SettingsPreviews.kt`,
// `notification/NotificationIconPreviews.kt`, `widget/WidgetPreviews.kt` —
// and captures each to PNG under
// `app/snapshots/` — a *tracked* path, so GitHub renders image diffs natively
// in the PR's "Files changed" view. CI commits any new/updated PNGs back to
// the PR branch (see ci.yml), so reviewers see pixel changes inline without
// downloading anything. `roborazzi.test.record=true` in the build script means
// each run re-records; the diff "gate" is just "the PNGs in the repo changed".
// When/if a hard-fail regression gate is wanted on stable surfaces, switch
// those tests to verifyRoborazzi*.
//
// **Sizing:** Studio honours each `@Preview`'s `widthDp` / `heightDp` in its
// design pane, but those parameters are *metadata* — calling the preview
// composable directly (as we do here) doesn't apply them. Snapshots render
// at the Robolectric device size from `@Config(qualifiers = ...)` below.
// We pin `widthDp = 360` on each `@Preview` to match the qualifier so Studio
// and snapshots agree on width; vertical extent is whatever the content
// needs at that width.
//
// To add a new captured state, add a `@Preview internal fun XxxPreview()` to
// the relevant `*Previews.kt` file and a one-line `capture { XxxPreview() }`
// test below.
//
// Robolectric `@GraphicsMode(NATIVE)` switches to the real Skia pipeline so
// Compose can actually rasterise. SDK is pinned so renders are reproducible
// across host machines.
//
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class PreviewSnapshots {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // Override Roborazzi's default `<package>.<class>.<method>.png` naming with
    // just `<method>.png`. The PNGs all live under `app/snapshots/` already, so
    // the package + class prefix was pure noise on every filename and every
    // `git mv` if the test class ever got renamed or moved.
    @get:Rule
    val testName = TestName()

    // `captureRoboImage(filePath = ...)` resolves a bare filename against the test
    // working dir (the `:app` module root), bypassing `roborazzi.output.dir` from
    // the build script. Prefix it explicitly so PNGs land under the tracked
    // snapshots directory and CI's `git add app/snapshots` finds them.
    private val outputDir: String = System.getProperty("roborazzi.output.dir")
        ?: error("roborazzi.output.dir not set; configure in app/build.gradle.kts testOptions")

    // Robolectric creates a fresh Application per test method by default, but
    // AndroidJUnit4 doesn't guarantee source-order execution: if `onboarding_partial`
    // (which grants POST_NOTIFICATIONS) ran before `onboarding_fresh` *and* a
    // future Robolectric / lifecycle change started sharing application state
    // across methods, the fresh snapshot would silently capture the wrong
    // baseline. Explicitly denying both perms before every test pins the
    // starting state regardless of the surrounding harness's reset semantics.
    @Before
    fun resetPermissions() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    private fun capture(content: @Composable () -> Unit) {
        composeRule.setContent { content() }
        // Drain any async side-effects (e.g. Vico's CartesianChartModelProducer
        // transaction, which runs in a LaunchedEffect and delivers data to the chart
        // host via a separate coroutine hop). Without this, the chart snapshot can
        // race and produce a near-blank PNG. waitForIdle() works if Vico dispatches
        // on the composition's coroutine scope; if it still flakes, the robust fix
        // is to pre-populate the producer synchronously in the test with runBlocking
        // before setContent, which requires hoisting it out of ForecastChart into a
        // parameter so the test can inject it.
        // TODO: if forecast_chart snapshots flake again, implement the runBlocking
        //  pre-population approach instead.
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(filePath = "$outputDir/${testName.methodName}.png")
    }

    // Dialog previews live in their own popup window — `composeRule.onRoot()`
    // sees both the host composition and the popup and errors with "Expected
    // exactly 1 node but found 2 that satisfy isRoot". Capture the dialog
    // node directly instead so the snapshot covers the popup contents (which
    // is the visible thing here).
    private fun captureDialog(content: @Composable () -> Unit) {
        composeRule.setContent { content() }
        composeRule.onNode(isDialog()).captureRoboImage(filePath = "$outputDir/${testName.methodName}.png")
    }

    @Test fun outfit_tshirt_shorts() = capture { OutfitTShirtShortsPreview() }
    @Test fun outfit_tshirt_pants() = capture { OutfitTShirtPantsPreview() }
    @Test fun outfit_sweater_shorts() = capture { OutfitSweaterShortsPreview() }
    @Test fun outfit_sweater_pants() = capture { OutfitSweaterPantsPreview() }
    @Test fun outfit_jacket_shorts() = capture { OutfitJacketShortsPreview() }
    @Test fun outfit_jacket_pants() = capture { OutfitJacketPantsPreview() }
    @Test fun outfit_sweater_pants_dark() = capture { OutfitSweaterPantsDarkPreview() }
    @Test fun outfit_row_today_tonight() = capture { OutfitRowTodayTonightPreview() }
    @Test fun outfit_row_tonight_tomorrow() = capture { OutfitRowTonightTomorrowPreview() }
    @Test fun outfit_rationale_dialog() = captureDialog { OutfitRationaleDialogPreview() }
    @Test fun outfit_rationale_dialog_tuned() = captureDialog { OutfitRationaleDialogTunedPreview() }

    @Test fun today_empty_state() = capture { TodayEmptyStatePreview() }
    @Test fun today_insight_card() = capture { TodayInsightCardPreview() }
    @Test fun today_insight_card_dark() = capture { TodayInsightCardDarkPreview() }
    @Test fun today_insight_card_with_location() = capture { TodayInsightCardWithLocationPreview() }
    @Test fun today_insight_card_location_unknown() = capture { TodayInsightCardLocationUnknownPreview() }
    @Test fun today_insight_card_long() = capture { TodayInsightCardLongPreview() }

    @Test fun forecast_chart() = capture { ForecastChartPreview() }
    @Test fun forecast_chart_dark() = capture { ForecastChartDarkPreview() }

    @Test fun today_insight_card_large_font() = capture { TodayInsightCardLargeFontPreview() }
    @Test fun outfit_tshirt_shorts_rtl() = capture { OutfitTShirtShortsRtlPreview() }

    @Test fun confidence_high() = capture { ConfidenceHighPreview() }
    @Test fun confidence_medium() = capture { ConfidenceMediumPreview() }
    @Test fun confidence_low() = capture { ConfidenceLowPreview() }

    @Test fun work_status_running() = capture { WorkStatusRunningPreview() }
    @Test fun work_status_retrying() = capture { WorkStatusRetryingPreview() }
    @Test fun work_status_failed() = capture { WorkStatusFailedPreview() }
    @Test fun work_status_failed_unhandled() = capture { WorkStatusFailedUnhandledPreview() }
    @Test fun work_status_failed_no_location() = capture { WorkStatusFailedNoLocationPreview() }
    @Test fun location_action_required_banner() = capture { LocationActionRequiredBannerPreview() }
    @Test fun last_crash_banner() = capture { LastCrashBannerPreview() }
    @Test fun bug_report_consent_dialog() = captureDialog { BugReportConsentDialogPreview() }
    @Test fun update_available_banner() = capture { UpdateAvailableBannerPreview() }
    @Test fun update_downloaded_banner() = capture { UpdateDownloadedBannerPreview() }
    @Test fun local_build_banner() = capture { LocalBuildBannerPreview() }
    @Test fun local_build_banner_dirty() = capture { LocalBuildBannerDirtyPreview() }
    @Test fun telemetry_notice_banner() = capture { TelemetryNoticeBannerPreview() }

    @Test fun launcher_icon() = capture { LauncherIconPreview() }
    @Test fun launcher_icon_dev() = capture { LauncherIconDevPreview() }

    @Test fun notification_icon_tshirt() = capture { NotificationIconTShirtPreview() }
    @Test fun notification_icon_sweater() = capture { NotificationIconSweaterPreview() }
    @Test fun notification_icon_thick_jacket() = capture { NotificationIconThickJacketPreview() }

    @Test fun widget_today_tshirt_shorts() = capture { WidgetTodayTShirtShortsPreview() }
    @Test fun widget_tonight_sweater_pants() = capture { WidgetTonightSweaterPantsPreview() }
    @Test fun widget_today_jacket_pants() = capture { WidgetTodayJacketPantsPreview() }
    @Test fun widget_tonight_dark() = capture { WidgetTonightDarkPreview() }
    @Test fun widget_empty() = capture { WidgetEmptyPreview() }

    @Test fun settings_root() = capture { SettingsRootPreview() }
    @Test fun settings_schedule() = capture { SettingsSchedulePreview() }
    @Test fun settings_clothes() = capture { SettingsClothesPreview() }
    @Test fun settings_clothes_fahrenheit() = capture { SettingsClothesFahrenheitPreview() }
    @Test fun settings_region() = capture { SettingsRegionPreview() }
    @Test fun settings_display() = capture { SettingsDisplayPreview() }
    @Test fun settings_voice_device() = capture { SettingsVoiceDevicePreview() }
    @Test fun settings_voice_gemini() = capture { SettingsVoiceGeminiPreview() }
    @Test fun settings_location() = capture { SettingsLocationPreview() }
    @Test fun settings_calendar() = capture { SettingsCalendarPreview() }
    @Test fun settings_privacy() = capture { SettingsPrivacyPreview() }

    // Onboarding's notification + location step cards derive their "complete"
    // checkmark from runtime permission state (POST_NOTIFICATIONS,
    // ACCESS_COARSE_LOCATION) read via LocalContext, not from any value the
    // composable accepts as a parameter. To capture states beyond "fresh
    // install — nothing granted", grant the relevant permissions on the
    // Robolectric application *before* setContent runs.
    @Test fun onboarding_fresh() = capture { OnboardingFreshPreview() }

    @Test fun onboarding_partial() {
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        capture { OnboardingPartialPreview() }
    }

    @Test fun onboarding_complete() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        capture { OnboardingCompletePreview() }
    }

    // Meta-test: verifies that every `@Preview internal fun XxxPreview()` declared
    // in the app's `*Previews.kt` files has a corresponding `@Test` in this class.
    // Also checks the reverse — no stale `@Test` calling a preview that no longer
    // exists. Fails with a descriptive message listing the mismatch(es).
    //
    // Name mapping: "TodayInsightCardPreview" → remove "Preview" → "TodayInsightCard"
    //   → insert "_" at every lowercase→UPPERCASE boundary → "Today_Insight_Card"
    //   → lowercase → "today_insight_card". Consecutive-uppercase runs (e.g. "TShirt")
    //   are treated as one word because there is no lowercase→UPPERCASE boundary
    //   within the run, yielding "tshirt" rather than "t_shirt".
    @Test fun no_preview_without_snapshot() {
        val previewClasses = listOf(
            "app.clothescast.ui.LauncherIconPreviewsKt",
            "app.clothescast.ui.today.TodayPreviewsKt",
            "app.clothescast.ui.onboarding.OnboardingPreviewsKt",
            "app.clothescast.ui.settings.SettingsPreviewsKt",
            "app.clothescast.notification.NotificationIconPreviewsKt",
            "app.clothescast.widget.WidgetPreviewsKt",
        ).map { Class.forName(it) }

        val allPreviewFunctions = previewClasses
            .flatMap { it.declaredMethods.toList() }
            .filter { !it.isSynthetic && !it.isBridge && it.name.endsWith("Preview") }
            .map { it.name }
            .toSortedSet()

        fun previewNameToTestName(name: String): String =
            name.removeSuffix("Preview")
                .replace(Regex("([a-z])([A-Z])"), "$1_$2")
                .lowercase()

        val expectedTestNames = allPreviewFunctions.map(::previewNameToTestName).toSortedSet()

        val captureTestNames = PreviewSnapshots::class.java.methods
            .filter { it.isAnnotationPresent(Test::class.java) && it.name != "no_preview_without_snapshot" }
            .map { it.name }
            .toSortedSet()

        val untested = expectedTestNames - captureTestNames
        val orphaned = captureTestNames - expectedTestNames

        val errors = buildList {
            if (untested.isNotEmpty()) {
                add("@Preview functions missing a snapshot @Test — add capture { XxxPreview() }: $untested")
            }
            if (orphaned.isNotEmpty()) {
                add("@Test methods without a matching @Preview function — rename to match or remove: $orphaned")
            }
        }
        check(errors.isEmpty()) { errors.joinToString("\n") }
    }
}
