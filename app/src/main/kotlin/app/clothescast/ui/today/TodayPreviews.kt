package app.clothescast.ui.today

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.clothescast.core.domain.model.BandClause
import app.clothescast.core.domain.model.ClothesClause
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.ColorPalette
import app.clothescast.core.domain.model.ConfidenceInfo
import app.clothescast.core.domain.model.DeltaClause
import app.clothescast.core.domain.model.Fact
import app.clothescast.core.domain.model.ForecastConfidence
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.GarmentReason
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.PerModelHour
import app.clothescast.core.domain.model.PerModelHourly
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.OutfitRationale
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.PrecipClause
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.TemperatureBand
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.WeatherCondition
import app.clothescast.core.domain.model.WindSpeedUnit
import app.clothescast.diag.BugReportConsentDialog
import app.clothescast.ui.theme.ClothesCastTheme
import app.clothescast.work.FetchAndNotifyWorker
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

//
// Preview wrappers for the Today-screen composables. Two purposes:
//
//   - Studio's design pane renders these via `@Preview` so designers/devs can
//     eyeball each state without running the app.
//   - The Roborazzi snapshot test in `app/src/test` invokes each function and
//     captures it to PNGs under `app/snapshots/`, which CI commits back to the
//     PR branch so GitHub renders image diffs inline in "Files changed".
//
// Adding a new screen state: add a `@Preview internal fun XxxPreview()` here,
// and add a corresponding test method in `PreviewSnapshots` that calls it.
// The test list is explicit by design — no annotation scanner — so the set of
// captured artifacts is obvious from a single file.
//

@Composable
internal fun Frame(
    darkTheme: Boolean = false,
    colorPalette: ColorPalette = ColorPalette.RAINBOW,
    content: @Composable () -> Unit,
) {
    ClothesCastTheme(darkTheme = darkTheme, dynamicColor = false, colorPalette = colorPalette) {
        Surface { Column(modifier = Modifier.padding(16.dp)) { content() } }
    }
}

// `@Preview(fontScale = …)` is metadata Studio honours in its design pane but
// that doesn't reach a snapshot test invoking the composable directly — the
// override has to come through LocalDensity at composition time. Same shape
// for layoutDirection: the @Preview attribute is design-pane-only.
@Composable
private fun ScaledFrame(
    fontScale: Float = 1.0f,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val baseDensity = LocalDensity.current
    val scaledDensity = Density(density = baseDensity.density, fontScale = fontScale)
    CompositionLocalProvider(
        LocalDensity provides scaledDensity,
        LocalLayoutDirection provides layoutDirection,
    ) {
        Frame(darkTheme = darkTheme, content = content)
    }
}

private val SAMPLE_INSIGHT = Insight(
    summary = InsightSummary(
        period = ForecastPeriod.TODAY,
        band = BandClause(TemperatureBand.COOL, TemperatureBand.MILD),
        delta = DeltaClause(4, DeltaClause.Direction.WARMER),
        clothes = ClothesClause(listOf("sweater", "umbrella")),
        precip = PrecipClause(WeatherCondition.RAIN, LocalTime.of(15, 0)),
    ),
    recommendedItems = listOf("sweater", "umbrella"),
    generatedAt = Instant.parse("2026-04-26T07:30:00Z"),
    forDate = LocalDate.of(2026, 4, 26),
)

@Preview(name = "Outfit · t-shirt + shorts", widthDp = 360)
@Composable
internal fun OutfitTShirtShortsPreview() {
    Frame {
        OutfitPreviewCard(
            outfit = OutfitSuggestion(OutfitSuggestion.Top.TSHIRT, OutfitSuggestion.Bottom.SHORTS),
            label = "Today",
        )
    }
}

@Preview(name = "Outfit · t-shirt + long pants", widthDp = 360)
@Composable
internal fun OutfitTShirtPantsPreview() {
    Frame {
        OutfitPreviewCard(
            outfit = OutfitSuggestion(OutfitSuggestion.Top.TSHIRT, OutfitSuggestion.Bottom.LONG_PANTS),
            label = "Today",
        )
    }
}

@Preview(name = "Outfit · sweater + shorts", widthDp = 360)
@Composable
internal fun OutfitSweaterShortsPreview() {
    Frame {
        OutfitPreviewCard(
            outfit = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.SHORTS),
            label = "Tonight",
        )
    }
}

@Preview(name = "Outfit · sweater + long pants", widthDp = 360)
@Composable
internal fun OutfitSweaterPantsPreview() {
    Frame {
        OutfitPreviewCard(
            outfit = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS),
            label = "Tonight",
        )
    }
}

@Preview(name = "Outfit · thick jacket + shorts", widthDp = 360)
@Composable
internal fun OutfitJacketShortsPreview() {
    Frame {
        OutfitPreviewCard(
            outfit = OutfitSuggestion(OutfitSuggestion.Top.THICK_JACKET, OutfitSuggestion.Bottom.SHORTS),
            label = "Tomorrow",
        )
    }
}

@Preview(name = "Outfit · thick jacket + long pants", widthDp = 360)
@Composable
internal fun OutfitJacketPantsPreview() {
    Frame {
        OutfitPreviewCard(
            outfit = OutfitSuggestion(OutfitSuggestion.Top.THICK_JACKET, OutfitSuggestion.Bottom.LONG_PANTS),
            label = "Tomorrow",
        )
    }
}

@Preview(name = "Outfit · sweater + pants (dark)", widthDp = 360)
@Composable
internal fun OutfitSweaterPantsDarkPreview() {
    Frame(darkTheme = true) {
        OutfitPreviewCard(
            outfit = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS),
            label = "Tonight",
        )
    }
}

@Preview(name = "Outfit row · today + tonight", widthDp = 360)
@Composable
internal fun OutfitRowTodayTonightPreview() {
    Frame {
        OutfitPreviewRow(
            SAMPLE_INSIGHT.copy(
                period = ForecastPeriod.TODAY,
                outfit = OutfitSuggestion(OutfitSuggestion.Top.TSHIRT, OutfitSuggestion.Bottom.SHORTS),
                nextOutfit = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS),
            ),
        )
    }
}

@Preview(name = "Outfit row · tonight + tomorrow", widthDp = 360)
@Composable
internal fun OutfitRowTonightTomorrowPreview() {
    Frame {
        OutfitPreviewRow(
            SAMPLE_INSIGHT.copy(
                period = ForecastPeriod.TONIGHT,
                outfit = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS),
                nextOutfit = OutfitSuggestion(OutfitSuggestion.Top.THICK_JACKET, OutfitSuggestion.Bottom.LONG_PANTS),
            ),
        )
    }
}

@Preview(name = "Outfit rationale · sweater + pants", widthDp = 360)
@Composable
internal fun OutfitRationaleDialogPreview() {
    Frame {
        OutfitRationaleDialog(
            outfit = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS),
            rationale = OutfitRationale(
                top = GarmentReason(
                    facts = listOf(
                        Fact(
                            metric = Fact.Metric.FEELS_LIKE_MIN,
                            observedC = 13.0,
                            observedAt = LocalTime.of(7, 0),
                            thresholdC = 18.0,
                            ruleItem = "sweater",
                            comparison = Fact.Comparison.BELOW,
                        ),
                    ),
                ),
                bottom = GarmentReason(
                    facts = listOf(
                        Fact(
                            metric = Fact.Metric.FEELS_LIKE_MAX,
                            observedC = 17.0,
                            observedAt = LocalTime.of(14, 0),
                            thresholdC = 24.0,
                            ruleItem = "shorts",
                            comparison = Fact.Comparison.BELOW,
                        ),
                    ),
                ),
            ),
            temperatureUnit = TemperatureUnit.CELSIUS,
            clothesRules = ClothesRule.DEFAULTS,
            onAdjustThreshold = { _, _ -> },
            onDismiss = {},
        )
    }
}

@Preview(name = "Outfit rationale · customised thresholds", widthDp = 360)
@Composable
internal fun OutfitRationaleDialogTunedPreview() {
    Frame {
        // Mid-tweak state: the user lowered their `sweater` rule from 18°C to 15°C,
        // so observed 13°C is still BELOW the customised threshold and the prose
        // stays "under". Cached fact still carries 18°C; the dialog reads the live
        // 15°C from clothesRules.
        OutfitRationaleDialog(
            outfit = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS),
            rationale = OutfitRationale(
                top = GarmentReason(
                    facts = listOf(
                        Fact(
                            metric = Fact.Metric.FEELS_LIKE_MIN,
                            observedC = 13.0,
                            observedAt = LocalTime.of(7, 0),
                            thresholdC = 18.0,
                            ruleItem = "sweater",
                            comparison = Fact.Comparison.BELOW,
                        ),
                    ),
                ),
                bottom = GarmentReason(
                    facts = listOf(
                        Fact(
                            metric = Fact.Metric.FEELS_LIKE_MAX,
                            observedC = 17.0,
                            observedAt = LocalTime.of(14, 0),
                            thresholdC = 24.0,
                            ruleItem = "shorts",
                            comparison = Fact.Comparison.BELOW,
                        ),
                    ),
                ),
            ),
            temperatureUnit = TemperatureUnit.CELSIUS,
            clothesRules = ClothesRule.DEFAULTS.map {
                if (it.item == "sweater") it.copy(condition = ClothesRule.TemperatureBelow(15.0)) else it
            },
            onAdjustThreshold = { _, _ -> },
            onDismiss = {},
        )
    }
}

@Preview(name = "Today · empty state", widthDp = 360)
@Composable
internal fun TodayEmptyStatePreview() {
    Frame { EmptyState(onRefresh = {}) }
}

@Preview(name = "Today · insight loaded", widthDp = 360)
@Composable
internal fun TodayInsightCardPreview() {
    Frame { InsightCard(SAMPLE_INSIGHT, Region.SYSTEM) }
}

@Preview(name = "Today · insight (dark)", widthDp = 360)
@Composable
internal fun TodayInsightCardDarkPreview() {
    Frame(darkTheme = true) { InsightCard(SAMPLE_INSIGHT, Region.SYSTEM) }
}

@Preview(name = "Today · insight with location", widthDp = 360)
@Composable
internal fun TodayInsightCardWithLocationPreview() {
    Frame {
        InsightCard(
            SAMPLE_INSIGHT.copy(
                location = Location(
                    latitude = 42.3601,
                    longitude = -71.0589,
                    displayName = "Boston, Massachusetts, United States",
                ),
            ),
            Region.SYSTEM,
        )
    }
}

// Reverse geocoding failed (or wasn't available — AOSP, IO error, blank
// locality). We still have coords, so the row shows the localised fallback
// label as a maps link instead of dropping silently to date-only.
@Preview(name = "Today · insight with location (unknown name)", widthDp = 360)
@Composable
internal fun TodayInsightCardLocationUnknownPreview() {
    Frame {
        InsightCard(
            SAMPLE_INSIGHT.copy(
                location = Location(
                    latitude = 42.3601,
                    longitude = -71.0589,
                    displayName = "Device location",
                ),
            ),
            Region.SYSTEM,
        )
    }
}

// Pager affordances. Two variants of the insight card so the chevron's
// rendering — placement at the trailing edge of the date row, plus the
// IconButton's tonal background — is locked into a snapshot for both
// directions. The default-arg `TodayInsightCardPreview` above still
// captures the no-chevron baseline, so the existence of the chevron
// state is reviewable side-by-side without the cluttering snapshots
// from existing previews shifting.
@Preview(name = "Today · insight with chevron right", widthDp = 360)
@Composable
internal fun TodayInsightCardWithChevronRightPreview() {
    Frame {
        InsightCard(
            SAMPLE_INSIGHT,
            Region.SYSTEM,
            showChevronRight = true,
            onChevronTap = {},
        )
    }
}

@Preview(name = "Today · insight with chevron left", widthDp = 360)
@Composable
internal fun TodayInsightCardWithChevronLeftPreview() {
    Frame {
        InsightCard(
            SAMPLE_INSIGHT.copy(period = ForecastPeriod.TONIGHT),
            Region.SYSTEM,
            showChevronLeft = true,
            onChevronTap = {},
        )
    }
}

@Preview(name = "Today · insight with chevron (dark)", widthDp = 360)
@Composable
internal fun TodayInsightCardWithChevronDarkPreview() {
    Frame(darkTheme = true) {
        InsightCard(
            SAMPLE_INSIGHT,
            Region.SYSTEM,
            showChevronRight = true,
            onChevronTap = {},
        )
    }
}

// Page-2 placeholder shown when the paired period hasn't been generated
// yet. Two variants cover the wording for either direction (TONIGHT is
// the typical case — open the app in the morning, swipe right before
// the evening worker has run; TODAY is the after-evening-only-refresh
// case) plus a dark variant.
@Preview(name = "Today · missing tonight placeholder", widthDp = 360)
@Composable
internal fun MissingTonightPlaceholderPreview() {
    Frame {
        MissingPeriodPlaceholder(
            period = ForecastPeriod.TONIGHT,
            morningTime = LocalTime.of(7, 0),
            tonightTime = LocalTime.of(19, 0),
            showChevronLeft = true,
            onChevronTap = {},
        )
    }
}

@Preview(name = "Today · missing today placeholder", widthDp = 360)
@Composable
internal fun MissingTodayPlaceholderPreview() {
    Frame {
        MissingPeriodPlaceholder(
            period = ForecastPeriod.TODAY,
            morningTime = LocalTime.of(7, 0),
            tonightTime = LocalTime.of(19, 0),
            showChevronLeft = true,
            onChevronTap = {},
        )
    }
}

@Preview(name = "Today · missing tonight placeholder (dark)", widthDp = 360)
@Composable
internal fun MissingTonightPlaceholderDarkPreview() {
    Frame(darkTheme = true) {
        MissingPeriodPlaceholder(
            period = ForecastPeriod.TONIGHT,
            morningTime = LocalTime.of(7, 0),
            tonightTime = LocalTime.of(19, 0),
            showChevronLeft = true,
            onChevronTap = {},
        )
    }
}

@Preview(name = "Confidence · high", widthDp = 360)
@Composable
internal fun ConfidenceHighPreview() {
    Frame {
        ConfidenceChip(
            info = ConfidenceInfo(
                level = ForecastConfidence.HIGH,
                tempSpreadC = 0.8,
                precipSpreadPp = 5.0,
                modelsConsulted = listOf("ecmwf_ifs04", "gfs_seamless", "icon_seamless"),
            ),
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            temperatureUnit = TemperatureUnit.CELSIUS,
            windSpeedUnit = WindSpeedUnit.KMH,
            onToggleModelSpread = {},
        )
    }
}

@Preview(name = "Confidence · medium", widthDp = 360)
@Composable
internal fun ConfidenceMediumPreview() {
    Frame {
        ConfidenceChip(
            info = ConfidenceInfo(
                level = ForecastConfidence.MEDIUM,
                tempSpreadC = 2.5,
                precipSpreadPp = 20.0,
                modelsConsulted = listOf("ecmwf_ifs04", "gfs_seamless"),
            ),
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            temperatureUnit = TemperatureUnit.CELSIUS,
            windSpeedUnit = WindSpeedUnit.KMH,
            onToggleModelSpread = {},
        )
    }
}

@Preview(name = "Confidence · low", widthDp = 360)
@Composable
internal fun ConfidenceLowPreview() {
    Frame {
        ConfidenceChip(
            info = ConfidenceInfo(
                level = ForecastConfidence.LOW,
                tempSpreadC = 6.1,
                precipSpreadPp = 55.0,
                modelsConsulted = listOf("ecmwf_ifs04", "gfs_seamless", "icon_seamless"),
            ),
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            temperatureUnit = TemperatureUnit.CELSIUS,
            windSpeedUnit = WindSpeedUnit.KMH,
            onToggleModelSpread = {},
        )
    }
}

// Tap-to-toggle hint variants — when the model-spread toggle is wired,
// the confidence chip grows an extra "Tap to ..." line so the gesture
// is discoverable. These previews lock both sides of the hint copy
// (show vs. hide) into snapshots.
@Preview(name = "Confidence · medium · tap to show spread", widthDp = 360)
@Composable
internal fun ConfidenceMediumTapToShowPreview() {
    Frame {
        ConfidenceChip(
            info = ConfidenceInfo(
                level = ForecastConfidence.MEDIUM,
                tempSpreadC = 2.5,
                precipSpreadPp = 20.0,
                modelsConsulted = listOf("ecmwf_ifs04", "gfs_seamless"),
            ),
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            temperatureUnit = TemperatureUnit.CELSIUS,
            windSpeedUnit = WindSpeedUnit.KMH,
            showModelSpread = false,
            onToggleModelSpread = {},
        )
    }
}

@Preview(name = "Confidence · medium · tap to hide spread", widthDp = 360)
@Composable
internal fun ConfidenceMediumTapToHidePreview() {
    Frame {
        ConfidenceChip(
            info = ConfidenceInfo(
                level = ForecastConfidence.MEDIUM,
                tempSpreadC = 2.5,
                precipSpreadPp = 20.0,
                modelsConsulted = listOf("ecmwf_ifs04", "gfs_seamless"),
            ),
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            temperatureUnit = TemperatureUnit.CELSIUS,
            windSpeedUnit = WindSpeedUnit.KMH,
            showModelSpread = true,
            onToggleModelSpread = {},
        )
    }
}

// Two variants covering the chip's "what fires when" judgment calls so the
// effect of each gate is reviewable in a snapshot:
//
//  - `ConfidenceMediumPrecipOnlyPreview` exercises the precip-only-driver
//    path added to address the precip-driven LOW/MEDIUM case (tight feels-
//    like, wide rain disagreement). perModelHourly is null so no feels-like
//    divergence hint can fire; precipSpreadPp ≥ 15 makes only the rain line
//    render. Validates that the chip still says *why* models disagree even
//    when ModelDivergenceSummary has nothing to offer.
//  - `ConfidenceMediumNoDetailPreview` exercises the deliberately-empty
//    case: a MEDIUM/LOW tier from cached data with no perModelHourly and
//    sub-threshold precip spread. The chip shows only the title and the
//    tap-to-show hint. Locked into a snapshot so the call to *not* add a
//    raw-numbers fallback (which would reintroduce the abstract "Spread: …"
//    line this PR set out to remove) is easy to eyeball.
@Preview(name = "Confidence · medium · precip-only", widthDp = 360)
@Composable
internal fun ConfidenceMediumPrecipOnlyPreview() {
    Frame {
        ConfidenceChip(
            info = ConfidenceInfo(
                level = ForecastConfidence.MEDIUM,
                tempSpreadC = 1.0,
                precipSpreadPp = 35.0,
                modelsConsulted = listOf("ecmwf_ifs04", "gfs_seamless", "icon_seamless"),
            ),
            perModelHourly = null,
            temperatureUnit = TemperatureUnit.CELSIUS,
            windSpeedUnit = WindSpeedUnit.KMH,
            showModelSpread = false,
            onToggleModelSpread = {},
        )
    }
}

@Preview(name = "Confidence · medium · no detail line", widthDp = 360)
@Composable
internal fun ConfidenceMediumNoDetailPreview() {
    Frame {
        ConfidenceChip(
            info = ConfidenceInfo(
                level = ForecastConfidence.MEDIUM,
                tempSpreadC = 2.5,
                precipSpreadPp = 8.0,
                modelsConsulted = listOf("ecmwf_ifs04", "gfs_seamless", "icon_seamless"),
            ),
            perModelHourly = null,
            temperatureUnit = TemperatureUnit.CELSIUS,
            windSpeedUnit = WindSpeedUnit.KMH,
            showModelSpread = false,
            onToggleModelSpread = {},
        )
    }
}

@Preview(name = "Banner · running", widthDp = 360)
@Composable
internal fun WorkStatusRunningPreview() {
    Frame { WorkStatusBanner(WorkStatus.Running) }
}

@Preview(name = "Banner · retrying", widthDp = 360)
@Composable
internal fun WorkStatusRetryingPreview() {
    Frame { WorkStatusBanner(WorkStatus.Retrying) }
}

@Preview(name = "Banner · failed (HTTP error)", widthDp = 360)
@Composable
internal fun WorkStatusFailedPreview() {
    Frame {
        WorkStatusBanner(
            WorkStatus.Failed(
                reason = FetchAndNotifyWorker.REASON_UNEXPECTED_HTTP,
                detail = "503",
            ),
        )
    }
}

@Preview(name = "Banner · failed (unhandled, long detail)", widthDp = 360)
@Composable
internal fun WorkStatusFailedUnhandledPreview() {
    Frame {
        WorkStatusBanner(
            WorkStatus.Failed(
                reason = FetchAndNotifyWorker.REASON_UNHANDLED,
                detail = "NoTransformationFoundException: Expected response body of " +
                    "the type 'class app.clothescast.core.data.weather.OpenMeteoResponse'",
            ),
        )
    }
}

@Preview(name = "Banner · failed (no location)", widthDp = 360)
@Composable
internal fun WorkStatusFailedNoLocationPreview() {
    Frame {
        WorkStatusBanner(
            WorkStatus.Failed(
                reason = FetchAndNotifyWorker.REASON_NO_LOCATION,
                detail = null,
            ),
        )
    }
}

@Preview(name = "Banner · location action required", widthDp = 360)
@Composable
internal fun LocationActionRequiredBannerPreview() {
    Frame { LocationActionRequiredBanner(onSetUpLocation = {}) }
}

@Preview(name = "Banner · last-run crash", widthDp = 360)
@Composable
internal fun LastCrashBannerPreview() {
    // Renders the stateless card variant directly so the snapshot doesn't
    // depend on a real `cacheDir/last-crash.txt` existing on the Robolectric
    // filesystem at test time.
    Frame { LastCrashBannerCard(onShare = {}, onDismiss = {}) }
}

@Preview(name = "Dialog · bug-report consent", widthDp = 360)
@Composable
internal fun BugReportConsentDialogPreview() {
    Frame { BugReportConsentDialog(onConfirm = { _ -> }, onDismiss = {}) }
}

@Preview(name = "Banner · update available", widthDp = 360)
@Composable
internal fun UpdateAvailableBannerPreview() {
    // Renders the stateless card variant directly so the snapshot doesn't
    // need a working AppUpdateManager / SettingsRepository on the Robolectric
    // app at test time.
    Frame {
        UpdateAvailableBannerCard(
            phase = UpdatePhase.Available,
            onAction = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "Banner · update downloading", widthDp = 360)
@Composable
internal fun UpdateDownloadingBannerPreview() {
    Frame {
        UpdateAvailableBannerCard(
            phase = UpdatePhase.Downloading(progress = 0.42f),
            onAction = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "Banner · update downloaded", widthDp = 360)
@Composable
internal fun UpdateDownloadedBannerPreview() {
    Frame {
        UpdateAvailableBannerCard(
            phase = UpdatePhase.ReadyToInstall,
            onAction = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "Banner · telemetry notice", widthDp = 360)
@Composable
internal fun TelemetryNoticeBannerPreview() {
    // Renders the stateless card variant directly so the snapshot doesn't
    // depend on a live SettingsRepository read on the Robolectric app at
    // test time.
    Frame {
        TelemetryNoticeBannerCard(
            onOpenSettings = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "Banner · local build (clean)", widthDp = 360)
@Composable
internal fun LocalBuildBannerPreview() {
    // Pin `now` and `buildTimestampMs` so the snapshot's relative time string
    // is deterministic instead of "5 hours ago" sliding to "6 hours ago" at
    // the next snapshot run.
    Frame {
        LocalBuildBannerCard(
            branch = "claude/add-update-check-banner-lcQwR",
            sha = "3cb1b3c",
            dirty = false,
            buildTimestampMs = 1_746_360_000_000L, // 2026-05-04 11:00 UTC
            onDismiss = {},
            nowProvider = { 1_746_367_200_000L },  // +2h00
        )
    }
}

@Preview(name = "Banner · local build (dirty)", widthDp = 360)
@Composable
internal fun LocalBuildBannerDirtyPreview() {
    Frame {
        LocalBuildBannerCard(
            branch = "claude/add-update-check-banner-lcQwR",
            sha = "3cb1b3c",
            dirty = true,
            buildTimestampMs = 1_746_366_000_000L, // 2026-05-04 12:40 UTC
            onDismiss = {},
            nowProvider = { 1_746_367_200_000L },  // +20 min
        )
    }
}

// 24-hour curve loosely tracking a temperate spring day: cool overnight low,
// warming through morning, peak around 15:00, then dropping back. Values are
// in Celsius — the ForecastChart converts at the edge per temperatureUnit.
private val SAMPLE_HOURLY: List<HourlyForecast> = run {
    val tempsC = listOf(
        9.0, 8.5, 8.0, 7.5, 7.5, 8.0,        // 00–05
        9.0, 10.5, 12.0, 13.5, 15.0, 16.0,   // 06–11
        17.0, 17.5, 18.0, 18.0, 17.5, 16.5,  // 12–17
        15.0, 13.5, 12.5, 11.5, 10.5, 9.5,   // 18–23
    )
    tempsC.mapIndexed { hour, t ->
        HourlyForecast(
            time = LocalTime.of(hour, 0),
            temperatureC = t,
            // Feels-like 1–2°C below air through the cool hours, equal at the peak.
            feelsLikeC = t - if (t < 14.0) 1.5 else 0.0,
            precipitationProbabilityPct = 0.0,
            condition = WeatherCondition.PARTLY_CLOUDY,
        )
    }
}

@Preview(name = "Forecast chart · 24h curve", widthDp = 360)
@Composable
internal fun ForecastChartPreview() {
    Frame {
        ForecastChart(
            hourly = SAMPLE_HOURLY,
            temperatureUnit = TemperatureUnit.CELSIUS,
            showFeelsLike = true,
        )
    }
}

@Preview(name = "Forecast chart · 24h curve (dark)", widthDp = 360)
@Composable
internal fun ForecastChartDarkPreview() {
    Frame(darkTheme = true) {
        ForecastChart(
            hourly = SAMPLE_HOURLY,
            temperatureUnit = TemperatureUnit.CELSIUS,
            showFeelsLike = true,
        )
    }
}

// 24-hour rain probability tracking a wet day with a mid-afternoon peak. Values
// in percent. Reuses SAMPLE_HOURLY's temperature curve as a base and overrides
// just the precipitation probabilities, so the rainy preview shares the same
// time/temperature shape as the dry one — only the precip series differs.
private val SAMPLE_HOURLY_RAINY: List<HourlyForecast> = run {
    val precipPctByHour = listOf(
        0.0, 0.0, 0.0, 0.0, 5.0, 10.0,        // 00–05
        15.0, 25.0, 40.0, 55.0, 65.0, 70.0,   // 06–11
        75.0, 80.0, 80.0, 75.0, 60.0, 45.0,   // 12–17
        30.0, 20.0, 15.0, 10.0, 5.0, 0.0,     // 18–23
    )
    SAMPLE_HOURLY.mapIndexed { i, h ->
        h.copy(precipitationProbabilityPct = precipPctByHour[i])
    }
}

@Preview(name = "Precipitation card · rainy", widthDp = 360)
@Composable
internal fun PrecipitationCardPreview() {
    Frame { PrecipitationCard(hourly = SAMPLE_HOURLY_RAINY) }
}

@Preview(name = "Precipitation card · rainy (dark)", widthDp = 360)
@Composable
internal fun PrecipitationCardDarkPreview() {
    Frame(darkTheme = true) { PrecipitationCard(hourly = SAMPLE_HOURLY_RAINY) }
}

// Sample anchor for per-model entries. Charts read `time.toLocalTime()` for
// labels, so any LocalDate works — pinning today keeps preview output
// deterministic-ish across runs (and matches SAMPLE_INSIGHT.forDate's
// vintage).
private val SAMPLE_PER_MODEL_DATE: LocalDate = LocalDate.of(2026, 4, 26)

// Three model curves spread around the blended sample. Offsets are deliberate
// so the overlay is visually distinguishable from the main line.
private val SAMPLE_PER_MODEL_HOURLY: PerModelHourly = run {
    fun shift(
        deltaC: Double,
        precipDelta: Double,
        windBase: Double,
        cloudBase: Double,
        solarPeakWm2: Double,
        sunshineMinutesAtMidday: Double,
        uvPeak: Double,
    ) = SAMPLE_HOURLY_RAINY.mapIndexed { i, h ->
        // Simple sinusoidal-ish wind curve so the per-model lines visibly
        // diverge across the day — the diagnostic chart's whole point is
        // showing where the spread is largest.
        val hourPhase = (i - 6).coerceAtLeast(0).coerceAtMost(12)
        // Bell-shaped daytime curve peaking around noon — zero overnight,
        // climbing to the peak between hours 6..18, falling back to zero.
        // Matches how shortwave / sunshine / UV behave in reality.
        val daylightFactor = if (i in 6..18) {
            val t = (i - 6).toDouble() / 12.0
            kotlin.math.sin(t * kotlin.math.PI)
        } else 0.0
        PerModelHour(
            time = java.time.LocalDateTime.of(SAMPLE_PER_MODEL_DATE, h.time),
            apparentTemperatureC = h.feelsLikeC + deltaC,
            temperatureC = h.temperatureC + deltaC,
            precipitationProbabilityPct = (h.precipitationProbabilityPct + precipDelta)
                .coerceIn(0.0, 100.0),
            windSpeedKmh = windBase + hourPhase * 0.6,
            relativeHumidityPct = 70.0 - hourPhase * 0.5,
            cloudCoverPct = (cloudBase + hourPhase * 2.0).coerceIn(0.0, 100.0),
            shortwaveRadiationWm2 = solarPeakWm2 * daylightFactor,
            // sunshine_duration is per-hour seconds; convert from the
            // midday-minutes parameter so the call site reads naturally.
            sunshineDurationSec = sunshineMinutesAtMidday * 60.0 * daylightFactor,
            uvIndex = uvPeak * daylightFactor,
        )
    }
    PerModelHourly(
        byModel = mapOf(
            "ecmwf_ifs04" to shift(
                deltaC = -1.5, precipDelta = -10.0, windBase = 8.0, cloudBase = 55.0,
                solarPeakWm2 = 600.0, sunshineMinutesAtMidday = 35.0, uvPeak = 5.0,
            ),
            "gfs_seamless" to shift(
                deltaC = 0.5, precipDelta = 5.0, windBase = 12.0, cloudBase = 70.0,
                solarPeakWm2 = 500.0, sunshineMinutesAtMidday = 25.0, uvPeak = 4.0,
            ),
            "icon_seamless" to shift(
                deltaC = 2.0, precipDelta = -5.0, windBase = 6.0, cloudBase = 40.0,
                solarPeakWm2 = 800.0, sunshineMinutesAtMidday = 50.0, uvPeak = 6.5,
            ),
            // best_match shares ECMWF's offsets so the preview reflects the
            // realistic case — Open-Meteo's "Auto" pick typically *is* one of
            // the consulted models for the region, not a wild outlier. With
            // matching timeseries, the layered render becomes the visual
            // test: the thicker (4 dp) grey best_match line should peek out
            // either side of the thinner (2 dp) ECMWF line drawn on top of
            // it. Wind/cloud/solar/sunshine/UV are null because the primary
            // `/v1/forecast` call doesn't fetch the diagnostic fields per
            // best_match; the diagnostic charts will hide the Auto line on
            // those metrics.
            PerModelHourly.BEST_MATCH_MODEL_ID to shift(
                deltaC = -1.5, precipDelta = -10.0, windBase = 0.0, cloudBase = 0.0,
                solarPeakWm2 = 0.0, sunshineMinutesAtMidday = 0.0, uvPeak = 0.0,
            ).map {
                it.copy(
                    windSpeedKmh = null,
                    relativeHumidityPct = null,
                    cloudCoverPct = null,
                    shortwaveRadiationWm2 = null,
                    sunshineDurationSec = null,
                    uvIndex = null,
                )
            },
        ),
    )
}

// "Consensus-only" variants (showModelSpread = false) — the default state of
// each diagnostic card. The chart draws a single main line computed as the
// per-hour cross-model mean; no per-model overlay, no legend.
@Preview(name = "Wind card · consensus only", widthDp = 360)
@Composable
internal fun WindCardConsensusPreview() {
    Frame {
        WindCard(
            hourly = SAMPLE_HOURLY_RAINY,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
        )
    }
}

@Preview(name = "Wind card · with model spread", widthDp = 360)
@Composable
internal fun WindCardWithModelSpreadPreview() {
    Frame {
        WindCard(
            hourly = SAMPLE_HOURLY_RAINY,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            showModelSpread = true,
        )
    }
}

// Imperial variant — exercises the km/h → mph conversion in both the picker
// (Y-axis values) and the subtitle string. Pairs with the default km/h preview
// above to lock in the unit-switching path now that DistanceUnit drives wind
// display.
@Preview(name = "Wind card · with model spread (mph)", widthDp = 360)
@Composable
internal fun WindCardWithModelSpreadMphPreview() {
    Frame {
        WindCard(
            hourly = SAMPLE_HOURLY_RAINY,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            windSpeedUnit = WindSpeedUnit.MPH,
            showModelSpread = true,
        )
    }
}

@Preview(name = "Cloud card · consensus only", widthDp = 360)
@Composable
internal fun CloudCardConsensusPreview() {
    Frame {
        CloudCard(
            hourly = SAMPLE_HOURLY_RAINY,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
        )
    }
}

@Preview(name = "Cloud card · with model spread", widthDp = 360)
@Composable
internal fun CloudCardWithModelSpreadPreview() {
    Frame {
        CloudCard(
            hourly = SAMPLE_HOURLY_RAINY,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            showModelSpread = true,
        )
    }
}

@Preview(name = "Humidity card · consensus only", widthDp = 360)
@Composable
internal fun HumidityCardConsensusPreview() {
    Frame {
        HumidityCard(
            hourly = SAMPLE_HOURLY_RAINY,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
        )
    }
}

@Preview(name = "Humidity card · with model spread", widthDp = 360)
@Composable
internal fun HumidityCardWithModelSpreadPreview() {
    Frame {
        HumidityCard(
            hourly = SAMPLE_HOURLY_RAINY,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            showModelSpread = true,
        )
    }
}

@Preview(name = "Solar radiation card · consensus only", widthDp = 360)
@Composable
internal fun SolarRadiationCardConsensusPreview() {
    Frame {
        SolarRadiationCard(
            hourly = SAMPLE_HOURLY_RAINY,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
        )
    }
}

@Preview(name = "Solar radiation card · with model spread", widthDp = 360)
@Composable
internal fun SolarRadiationCardWithModelSpreadPreview() {
    Frame {
        SolarRadiationCard(
            hourly = SAMPLE_HOURLY_RAINY,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            showModelSpread = true,
        )
    }
}

@Preview(name = "Sunshine card · consensus only", widthDp = 360)
@Composable
internal fun SunshineCardConsensusPreview() {
    Frame {
        SunshineCard(
            hourly = SAMPLE_HOURLY_RAINY,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            forDate = SAMPLE_PER_MODEL_DATE,
        )
    }
}

@Preview(name = "Sunshine card · with model spread", widthDp = 360)
@Composable
internal fun SunshineCardWithModelSpreadPreview() {
    Frame {
        SunshineCard(
            hourly = SAMPLE_HOURLY_RAINY,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            forDate = SAMPLE_PER_MODEL_DATE,
            showModelSpread = true,
        )
    }
}

@Preview(name = "Sunshine card · tonight wording", widthDp = 360)
@Composable
internal fun SunshineCardTonightPreview() {
    Frame {
        SunshineCard(
            hourly = SAMPLE_HOURLY_RAINY,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            forDate = SAMPLE_PER_MODEL_DATE,
            period = ForecastPeriod.TONIGHT,
        )
    }
}

@Preview(name = "UV index card · consensus only", widthDp = 360)
@Composable
internal fun UvIndexCardConsensusPreview() {
    Frame {
        UvIndexCard(
            hourly = SAMPLE_HOURLY_RAINY,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
        )
    }
}

@Preview(name = "UV index card · with model spread", widthDp = 360)
@Composable
internal fun UvIndexCardWithModelSpreadPreview() {
    Frame {
        UvIndexCard(
            hourly = SAMPLE_HOURLY_RAINY,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            showModelSpread = true,
        )
    }
}

// Sparse-edge variant: every model is missing wind data for hours 00–19 and
// only carries values for the trailing 20–23 window. Without an x-range pin
// on the chart, those four surviving points get fitted to the full card width
// by Zoom.Content and the lines appear as if they cover the whole day. With
// the pin, they sit on the right edge with the leading hours visibly empty.
private val SAMPLE_PER_MODEL_HOURLY_SPARSE_WIND_TRAILING: PerModelHourly =
    PerModelHourly(
        byModel = SAMPLE_PER_MODEL_HOURLY.byModel.mapValues { (_, entries) ->
            entries.mapIndexed { i, e ->
                if (i < 20) e.copy(windSpeedKmh = null) else e
            }
        },
    )

@Preview(name = "Wind card · sparse trailing only", widthDp = 360)
@Composable
internal fun WindCardSparseTrailingPreview() {
    Frame {
        WindCard(
            hourly = SAMPLE_HOURLY_RAINY,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY_SPARSE_WIND_TRAILING,
            showModelSpread = true,
        )
    }
}

@Preview(name = "Forecast chart · with model spread", widthDp = 360)
@Composable
internal fun ForecastChartWithModelSpreadPreview() {
    Frame {
        ForecastChart(
            hourly = SAMPLE_HOURLY,
            temperatureUnit = TemperatureUnit.CELSIUS,
            showFeelsLike = true,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            showModelSpread = true,
        )
    }
}

// Captures the full ForecastCard including the legend's "Best match" chip —
// layered on top of the chart by the card itself, so the existing
// [ForecastChartWithModelSpreadPreview] (which renders the chart in isolation)
// doesn't exercise it.
@Preview(name = "Forecast card · with model spread", widthDp = 360)
@Composable
internal fun ForecastCardWithModelSpreadPreview() {
    Frame {
        ForecastCard(
            hourly = SAMPLE_HOURLY,
            temperatureUnit = TemperatureUnit.CELSIUS,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            showModelSpread = true,
        )
    }
}

@Preview(name = "Air temperature card · with model spread", widthDp = 360)
@Composable
internal fun AirTemperatureCardWithModelSpreadPreview() {
    Frame {
        AirTemperatureCard(
            hourly = SAMPLE_HOURLY,
            temperatureUnit = TemperatureUnit.CELSIUS,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            showModelSpread = true,
        )
    }
}

@Preview(name = "Precipitation card · with model spread", widthDp = 360)
@Composable
internal fun PrecipitationCardWithModelSpreadPreview() {
    Frame {
        PrecipitationCard(
            hourly = SAMPLE_HOURLY_RAINY,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            showModelSpread = true,
        )
    }
}

// Two accessible-palette previews — the temp + rain cards with the per-model
// overlay turned on — so reviewers can see what the Okabe-Ito-derived trio
// actually looks like on a chart before the toggle ships. We pick the two
// cards that have a blended main line on top so the snapshot exercises both
// the [AppPalette.modelColors] swap and the blended-vs-overlay contrast at
// the same time; the diagnostic cards reuse the same overlay colours so they
// don't need their own snapshot.
@Preview(name = "Forecast card · accessible palette + model spread", widthDp = 360)
@Composable
internal fun ForecastCardWithModelSpreadAccessiblePreview() {
    Frame(colorPalette = ColorPalette.ACCESSIBLE) {
        ForecastCard(
            hourly = SAMPLE_HOURLY,
            temperatureUnit = TemperatureUnit.CELSIUS,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            showModelSpread = true,
        )
    }
}

@Preview(name = "Precipitation card · accessible palette + model spread", widthDp = 360)
@Composable
internal fun PrecipitationCardWithModelSpreadAccessiblePreview() {
    Frame(colorPalette = ColorPalette.ACCESSIBLE) {
        PrecipitationCard(
            hourly = SAMPLE_HOURLY_RAINY,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            showModelSpread = true,
        )
    }
}

// Same pair, but with the Highlighter palette — magenta / lime / cyan neon
// trio. Picked for visual punch (arcade / Tron-readout vibe) while staying
// safe under all three common CVD profiles by routing the third hue to
// lime rather than yellow.
@Preview(name = "Forecast card · highlighter palette + model spread", widthDp = 360)
@Composable
internal fun ForecastCardWithModelSpreadHighlighterPreview() {
    Frame(colorPalette = ColorPalette.HIGHLIGHTER) {
        ForecastCard(
            hourly = SAMPLE_HOURLY,
            temperatureUnit = TemperatureUnit.CELSIUS,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            showModelSpread = true,
        )
    }
}

@Preview(name = "Precipitation card · highlighter palette + model spread", widthDp = 360)
@Composable
internal fun PrecipitationCardWithModelSpreadHighlighterPreview() {
    Frame(colorPalette = ColorPalette.HIGHLIGHTER) {
        PrecipitationCard(
            hourly = SAMPLE_HOURLY_RAINY,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            showModelSpread = true,
        )
    }
}

// SAMPLE_HOURLY is already all-zero precipitation, so reuse it for the dry
// variant — exercises the "No rain expected today" copy path while still
// rendering the chart (per the always-show-chart design choice).
@Preview(name = "Precipitation card · dry", widthDp = 360)
@Composable
internal fun PrecipitationCardDryPreview() {
    Frame { PrecipitationCard(hourly = SAMPLE_HOURLY) }
}

// Accessibility / i18n stress variants. Each picks the surface most likely to
// regress under the relevant axis: `headlineSmall` prose + adjacent confidence
// chip for fontScale (the chip's row crowds the text at the top of the card),
// and the period-label / outfit-icon row for RTL (label-vs-icon ordering and
// padding mirror together).
@Preview(name = "Insight card · fontScale 1.5", widthDp = 360, fontScale = 1.5f)
@Composable
internal fun TodayInsightCardLargeFontPreview() {
    ScaledFrame(fontScale = 1.5f) { InsightCard(SAMPLE_INSIGHT, Region.SYSTEM) }
}

@Preview(name = "Outfit · t-shirt + shorts (RTL)", widthDp = 360, locale = "ar")
@Composable
internal fun OutfitTShirtShortsRtlPreview() {
    ScaledFrame(layoutDirection = LayoutDirection.Rtl) {
        OutfitPreviewCard(
            outfit = OutfitSuggestion(OutfitSuggestion.Top.TSHIRT, OutfitSuggestion.Bottom.SHORTS),
            label = "Today",
        )
    }
}

// Stress variant of the InsightCard: the structured InsightSummary's prose
// length is dominated by the clothes clause's item count (the formatter joins
// them into a comma list), so feeding it five items is what produces the
// longest natural rendering. Catches wrapping / line-height regressions in
// `headlineSmall` that the single-clause `TodayInsightCardPreview` would miss.
@Preview(name = "Today · insight (long clothes list)", widthDp = 360)
@Composable
internal fun TodayInsightCardLongPreview() {
    Frame {
        InsightCard(
            SAMPLE_INSIGHT.copy(
                summary = SAMPLE_INSIGHT.summary.copy(
                    clothes = ClothesClause(
                        listOf("sweater", "jacket", "scarf", "gloves", "umbrella"),
                    ),
                ),
            ),
            Region.SYSTEM,
        )
    }
}
