package app.clothescast.ui.today

import android.Manifest
import android.graphics.BitmapFactory
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
import app.clothescast.widget.WidgetTodayCompactPreview
import app.clothescast.widget.WidgetTodayJacketPantsPreview
import app.clothescast.widget.WidgetTodayLargePreview
import app.clothescast.widget.WidgetTodayTonightWidePreview
import app.clothescast.widget.WidgetTodayTShirtShortsPreview
import app.clothescast.widget.WidgetTonightDarkPreview
import app.clothescast.widget.WidgetTonightSweaterPantsPreview
import app.clothescast.widget.WidgetTonightTomorrowWidePreview
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlin.math.abs
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
        captureUntilStable {
            composeRule.onRoot().captureRoboImage(filePath = "$outputDir/${testName.methodName}.png")
        }
    }

    // Dialog previews live in their own popup window — `composeRule.onRoot()`
    // sees both the host composition and the popup and errors with "Expected
    // exactly 1 node but found 2 that satisfy isRoot". Capture the dialog
    // node directly instead so the snapshot covers the popup contents (which
    // is the visible thing here).
    private fun captureDialog(content: @Composable () -> Unit) {
        composeRule.setContent { content() }
        captureUntilStable {
            composeRule.onNode(isDialog()).captureRoboImage(filePath = "$outputDir/${testName.methodName}.png")
        }
    }

    /**
     * Captures a snapshot to disk and re-captures until two consecutive
     * attempts produce the same bytes — i.e. the rendering has settled.
     *
     * Two layers of "give async work time to drain" before each attempt:
     *  - `waitForIdle()` drains the Compose dispatcher.
     *  - We then pump a handful of additional frames. Vico's
     *    `CartesianChartModelProducer` feeds the chart host via a separate
     *    coroutine flow that `waitForIdle()` doesn't always cover, so the
     *    first capture can land before the chart line has actually painted.
     *    The frame pump gives the host's collection + recompose + paint
     *    sequence room to complete. Cheap on chart-less previews (just a
     *    few no-op frames), so we apply it uniformly rather than annotating
     *    each chart-bearing preview by hand.
     *
     * If a chart is mid-paint we see different bytes on consecutive captures
     * and keep looping; once the picture stops changing we accept it. Catches
     * the "card chrome rendered but chart line missing" case the older
     * [looksEmpty] backstop misses — a card with axis grid + text has many
     * distinct colours and never trips [looksEmpty]'s all-pixels-equal
     * check, but it's still the wrong answer compared to a stabilised
     * second capture.
     *
     * After the in-session stable check passes, the result is compared
     * against the pre-capture baseline (the PNG already on disk, i.e.
     * whatever `main` last committed). If the only differences are
     * sub-perceptual rendering noise — anti-aliasing wobble on a chart
     * line, font hinting jitter between Robolectric sessions — the
     * baseline bytes are restored. This stops the regen bot from
     * committing 41-byte "look at me, I'm a UI change" diffs that are
     * invisible to the eye but break apart inline image-diff review.
     *
     * [looksEmpty] stays as a separate guard so a *completely* blank
     * capture (every sampled pixel matching the first) never gets to
     * "stable" status just by failing the same way twice in a row.
     *
     * History: PR #436's CI runs flipped `precipitation_card_dry.png`
     * between a "with chart" (29.6KB) and a "no chart" render (11.6KB)
     * on consecutive runs of the *same* commit — the chart-missing
     * race the stable check now closes. PR #438 cut that race to
     * pieces but still left a 41-byte anti-aliasing wobble on
     * `uv_index_card_with_model_spread.png` flipping each run; this
     * helper's noise-tolerant baseline restore handles that.
     */
    private fun captureUntilStable(maxAttempts: Int = 4, snapshot: () -> Unit) {
        val outputFile = File("$outputDir/${testName.methodName}.png")
        // Snapshot the pre-capture bytes for the noise-tolerant baseline
        // restore at the end. Null when this is a new preview with no
        // existing PNG; in that case any capture is the new baseline.
        val baseline = if (outputFile.exists()) outputFile.readBytes() else null
        var prior: ByteArray? = null
        repeat(maxAttempts) { attempt ->
            composeRule.waitForIdle()
            repeat(FRAME_PUMP_COUNT) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.waitForIdle()
            }
            snapshot()
            if (looksEmpty(outputFile)) {
                // Don't let a blank capture become "stable" by repeating —
                // forget any prior bytes so the next non-blank attempt has
                // to confirm against itself, not against the failure.
                prior = null
            } else {
                val current = outputFile.readBytes()
                if (prior != null && prior.contentEquals(current)) {
                    restoreBaselineIfOnlyNoise(outputFile, current, baseline)
                    return
                }
                prior = current
            }
            if (attempt < maxAttempts - 1) Thread.sleep(50L * (attempt + 1))
        }
        System.err.println(
            "WARN: snapshot ${testName.methodName} did not stabilise after $maxAttempts attempts",
        )
    }

    /**
     * After a stable capture lands, compare it pixel-wise against the
     * pre-capture [baseline]. Restore [baseline] only when the
     * differences look like rendering noise rather than a real UI
     * change. Two thresholds, both of which must hold:
     *
     *  - At most [NOISY_PIXEL_BUDGET] pixels exceed [INTENSITY_TOLERANCE]
     *    in any RGB channel. Bounds the "moved-label" / "swapped-glyph"
     *    style of regression, where a handful of pixels go from full
     *    foreground intensity to full background intensity.
     *
     *  - At most [IN_TOLERANCE_PIXEL_BUDGET] pixels differ at all but
     *    within tolerance. Without this an across-the-board ≤8/255
     *    intensity shift — e.g. a Material colour token tweak applied
     *    to every pixel of a card surface — would never bump the
     *    high-diff counter and the real change would be discarded as
     *    noise. Anti-aliasing wobble on chart lines typically affects
     *    a few hundred edge pixels at low intensity; a broad surface
     *    shift hits tens of thousands.
     *
     * If both budgets hold, the captured PNG is functionally identical
     * to what `main` already has and rewriting it just flips a few
     * sub-perceptual bits — exactly the spurious-regen pattern we're
     * trying to avoid.
     */
    private fun restoreBaselineIfOnlyNoise(
        file: File,
        captured: ByteArray,
        baseline: ByteArray?,
    ) {
        if (baseline == null) return
        if (baseline.contentEquals(captured)) return
        val a = runCatching { BitmapFactory.decodeByteArray(baseline, 0, baseline.size) }.getOrNull()
            ?: return
        val b = runCatching { BitmapFactory.decodeByteArray(captured, 0, captured.size) }.getOrNull()
            ?: return
        if (a.width != b.width || a.height != b.height) return
        val total = a.width * a.height
        val pa = IntArray(total).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
        val pb = IntArray(total).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        var noisyPixels = 0
        var inToleranceDiffs = 0
        for (i in 0 until total) {
            val d = maxChannelDiff(pa[i], pb[i])
            if (d == 0) continue
            if (d > INTENSITY_TOLERANCE) {
                if (++noisyPixels > NOISY_PIXEL_BUDGET) return
            } else {
                if (++inToleranceDiffs > IN_TOLERANCE_PIXEL_BUDGET) return
            }
        }
        file.writeBytes(baseline)
    }

    /**
     * Largest per-channel absolute difference between two ARGB-packed
     * pixels. Alpha is ignored: the captured PNGs are all fully opaque,
     * so any alpha variance is rendering-pipeline noise rather than a
     * meaningful colour change.
     */
    private fun maxChannelDiff(a: Int, b: Int): Int {
        val rDiff = abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff))
        val gDiff = abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff))
        val bDiff = abs((a and 0xff) - (b and 0xff))
        return maxOf(rDiff, gDiff, bDiff)
    }

    // Number of additional frames to advance past Compose's idle state
    // before each capture attempt. Three covers Vico's typical
    // producer-flow-emit + host-recompose + paint sequence with margin;
    // each frame is ~16ms in virtual time but ~near-zero wall-clock under
    // Robolectric, so the cost is negligible.
    //
    // Noise tolerances for the baseline-restore comparison.
    //
    // INTENSITY_TOLERANCE = 8/255 per channel: below this, a pixel diff is
    // considered "rendering wobble" rather than a meaningful colour change.
    // Above, it's a hard regression signal.
    //
    // NOISY_PIXEL_BUDGET = 64: how many pixels may exceed the intensity
    // threshold before we stop calling it noise. A 1-pixel label shift
    // swaps text-foreground for text-background across hundreds of pixels
    // and blows through this immediately.
    //
    // IN_TOLERANCE_PIXEL_BUDGET = 4096 (~1% of a 720x540 card): how many
    // pixels may *differ at all* — even by a single intensity level —
    // before we stop calling it noise. Without this, a uniform ≤8/255
    // colour-token tweak applied to every pixel of a surface would slip
    // past unnoticed; with it, broad palette / theme changes are caught.
    // Anti-aliasing on chart-line edges and font hinting across the
    // ~20 axis labels on a chart card typically affects a few hundred
    // sub-tolerance pixels, well under the cap.
    private companion object {
        const val FRAME_PUMP_COUNT = 3
        const val INTENSITY_TOLERANCE = 8
        const val NOISY_PIXEL_BUDGET = 64
        const val IN_TOLERANCE_PIXEL_BUDGET = 4096
    }

    // "Empty" = unreadable as a PNG, or every sampled pixel matches (0,0). The
    // 16x16 grid is sparse enough to be cheap and dense enough that a centred
    // launcher icon or any non-trivial UI registers at least one differing
    // pixel. BitmapFactory rather than javax.imageio because Android unit
    // tests compile against android.jar (which omits javax.imageio); under
    // GraphicsMode.NATIVE Robolectric decodes via real Skia, so getPixel
    // returns the actual rasterised colour.
    private fun looksEmpty(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return true
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            ?: return true
        if (bitmap.width == 0 || bitmap.height == 0) return true
        val firstPixel = bitmap.getPixel(0, 0)
        val stepX = (bitmap.width / 16).coerceAtLeast(1)
        val stepY = (bitmap.height / 16).coerceAtLeast(1)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                if (bitmap.getPixel(x, y) != firstPixel) return false
                x += stepX
            }
            y += stepY
        }
        return true
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
    @Test fun today_insight_card_with_chevron_right() = capture { TodayInsightCardWithChevronRightPreview() }
    @Test fun today_insight_card_with_chevron_left() = capture { TodayInsightCardWithChevronLeftPreview() }
    @Test fun today_insight_card_with_chevron_dark() = capture { TodayInsightCardWithChevronDarkPreview() }
    @Test fun missing_tonight_placeholder() = capture { MissingTonightPlaceholderPreview() }
    @Test fun missing_today_placeholder() = capture { MissingTodayPlaceholderPreview() }
    @Test fun missing_tonight_placeholder_dark() = capture { MissingTonightPlaceholderDarkPreview() }

    @Test fun forecast_chart() = capture { ForecastChartPreview() }
    @Test fun forecast_chart_dark() = capture { ForecastChartDarkPreview() }
    @Test fun forecast_chart_with_model_spread() = capture { ForecastChartWithModelSpreadPreview() }
    @Test fun forecast_card_with_model_spread() = capture { ForecastCardWithModelSpreadPreview() }
    @Test fun air_temperature_card_with_model_spread() = capture { AirTemperatureCardWithModelSpreadPreview() }
    @Test fun precipitation_card_with_model_spread() = capture { PrecipitationCardWithModelSpreadPreview() }
    @Test fun forecast_card_with_model_spread_accessible() = capture { ForecastCardWithModelSpreadAccessiblePreview() }
    @Test fun precipitation_card_with_model_spread_accessible() = capture { PrecipitationCardWithModelSpreadAccessiblePreview() }
    @Test fun forecast_card_with_model_spread_highlighter() = capture { ForecastCardWithModelSpreadHighlighterPreview() }
    @Test fun precipitation_card_with_model_spread_highlighter() = capture { PrecipitationCardWithModelSpreadHighlighterPreview() }
    @Test fun wind_card_with_model_spread() = capture { WindCardWithModelSpreadPreview() }
    @Test fun wind_card_with_model_spread_mph() = capture { WindCardWithModelSpreadMphPreview() }
    @Test fun cloud_card_with_model_spread() = capture { CloudCardWithModelSpreadPreview() }
    @Test fun humidity_card_with_model_spread() = capture { HumidityCardWithModelSpreadPreview() }
    @Test fun solar_radiation_card_with_model_spread() = capture { SolarRadiationCardWithModelSpreadPreview() }
    @Test fun sunshine_card_with_model_spread() = capture { SunshineCardWithModelSpreadPreview() }
    @Test fun uv_index_card_with_model_spread() = capture { UvIndexCardWithModelSpreadPreview() }
    // Consensus-only variants — the default state of each diagnostic card
    // when the model-spread tap toggle is off. Locks in the "main line only"
    // appearance so a regression that accidentally renders overlay lines
    // when the toggle is off would surface as a snapshot diff.
    @Test fun wind_card_consensus() = capture { WindCardConsensusPreview() }
    @Test fun cloud_card_consensus() = capture { CloudCardConsensusPreview() }
    @Test fun humidity_card_consensus() = capture { HumidityCardConsensusPreview() }
    @Test fun solar_radiation_card_consensus() = capture { SolarRadiationCardConsensusPreview() }
    @Test fun sunshine_card_consensus() = capture { SunshineCardConsensusPreview() }
    @Test fun sunshine_card_tonight() = capture { SunshineCardTonightPreview() }
    @Test fun uv_index_card_consensus() = capture { UvIndexCardConsensusPreview() }
    @Test fun wind_card_sparse_trailing() = capture { WindCardSparseTrailingPreview() }

    @Test fun precipitation_card() = capture { PrecipitationCardPreview() }
    @Test fun precipitation_card_dark() = capture { PrecipitationCardDarkPreview() }
    @Test fun precipitation_card_dry() = capture { PrecipitationCardDryPreview() }

    @Test fun today_insight_card_large_font() = capture { TodayInsightCardLargeFontPreview() }
    @Test fun outfit_tshirt_shorts_rtl() = capture { OutfitTShirtShortsRtlPreview() }

    @Test fun confidence_high() = capture { ConfidenceHighPreview() }
    @Test fun confidence_medium() = capture { ConfidenceMediumPreview() }
    @Test fun confidence_low() = capture { ConfidenceLowPreview() }
    @Test fun confidence_medium_tap_to_show() = capture { ConfidenceMediumTapToShowPreview() }
    @Test fun confidence_medium_tap_to_hide() = capture { ConfidenceMediumTapToHidePreview() }
    @Test fun confidence_medium_precip_only() = capture { ConfidenceMediumPrecipOnlyPreview() }
    @Test fun confidence_medium_no_detail() = capture { ConfidenceMediumNoDetailPreview() }

    @Test fun work_status_running() = capture { WorkStatusRunningPreview() }
    @Test fun work_status_retrying() = capture { WorkStatusRetryingPreview() }
    @Test fun work_status_failed() = capture { WorkStatusFailedPreview() }
    @Test fun work_status_failed_unhandled() = capture { WorkStatusFailedUnhandledPreview() }
    @Test fun work_status_failed_no_location() = capture { WorkStatusFailedNoLocationPreview() }
    @Test fun location_action_required_banner() = capture { LocationActionRequiredBannerPreview() }
    @Test fun last_crash_banner() = capture { LastCrashBannerPreview() }
    @Test fun bug_report_consent_dialog() = captureDialog { BugReportConsentDialogPreview() }
    @Test fun update_available_banner() = capture { UpdateAvailableBannerPreview() }
    @Test fun update_downloading_banner() = capture { UpdateDownloadingBannerPreview() }
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
    @Test fun widget_today_compact() = capture { WidgetTodayCompactPreview() }
    @Test fun widget_today_large() = capture { WidgetTodayLargePreview() }
    @Test fun widget_today_tonight_wide() = capture { WidgetTodayTonightWidePreview() }
    @Test fun widget_tonight_tomorrow_wide() = capture { WidgetTonightTomorrowWidePreview() }

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
