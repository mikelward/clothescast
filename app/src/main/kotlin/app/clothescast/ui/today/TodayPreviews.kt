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
import app.clothescast.core.domain.model.ConfidenceInfo
import app.clothescast.core.domain.model.DeltaClause
import app.clothescast.core.domain.model.Fact
import app.clothescast.core.domain.model.ForecastConfidence
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.GarmentReason
import app.clothescast.core.domain.model.HourlyForecast
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
internal fun Frame(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    ClothesCastTheme(darkTheme = darkTheme, dynamicColor = false) {
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

@Preview(name = "Confidence · high", widthDp = 360)
@Composable
internal fun ConfidenceHighPreview() {
    Frame {
        ConfidenceChip(
            ConfidenceInfo(
                level = ForecastConfidence.HIGH,
                tempSpreadC = 0.8,
                precipSpreadPp = 5.0,
                modelsConsulted = listOf("ECMWF", "GFS", "ICON"),
            ),
        )
    }
}

@Preview(name = "Confidence · medium", widthDp = 360)
@Composable
internal fun ConfidenceMediumPreview() {
    Frame {
        ConfidenceChip(
            ConfidenceInfo(
                level = ForecastConfidence.MEDIUM,
                tempSpreadC = 2.5,
                precipSpreadPp = 20.0,
                modelsConsulted = listOf("ECMWF", "GFS"),
            ),
        )
    }
}

@Preview(name = "Confidence · low", widthDp = 360)
@Composable
internal fun ConfidenceLowPreview() {
    Frame {
        ConfidenceChip(
            ConfidenceInfo(
                level = ForecastConfidence.LOW,
                tempSpreadC = 6.1,
                precipSpreadPp = 55.0,
                modelsConsulted = listOf("ECMWF", "GFS", "ICON"),
            ),
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
    Frame { BugReportConsentDialog(onConfirm = {}, onDismiss = {}) }
}

@Preview(name = "Banner · update available", widthDp = 360)
@Composable
internal fun UpdateAvailableBannerPreview() {
    // Renders the stateless card variant directly so the snapshot doesn't
    // need a working AppUpdateManager / SettingsRepository on the Robolectric
    // app at test time.
    Frame {
        UpdateAvailableBannerCard(
            downloadComplete = false,
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
            downloadComplete = true,
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
