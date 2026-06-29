package app.clothescast.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.clothescast.core.domain.model.Garment
import app.clothescast.core.domain.model.BandClause
import app.clothescast.core.domain.model.ClothesClause
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.ColorPalette
import app.clothescast.core.domain.model.ConfidenceInfo
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.DeltaClause
import app.clothescast.core.domain.model.Fact
import app.clothescast.core.domain.model.ForecastConfidence
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.GarmentReason
import app.clothescast.core.domain.model.FestiveThemes
import app.clothescast.core.domain.model.HolidayCatalog
import app.clothescast.core.domain.model.HolidayId
import app.clothescast.core.domain.model.HolidayTheme
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
import app.clothescast.core.domain.usecase.ThemeForToday
import app.clothescast.diag.BugReportConsentDialog
import app.clothescast.ui.EdgeFadeOverlay
import app.clothescast.ui.settings.NotificationPermissionBanner
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

// Chart-colour slot assignment for the sample per-model data used by the
// model-spread previews ({ECMWF, GFS, ICON, UKMO}). Mirrors what
// [ForecastModel.assignColorSlots] would persist for that selection — distinct
// slots in enum order — so the preview shows the colours the real app would,
// instead of the bare enum-ordinal fallback (where UKMO's ordinal 5 wraps onto
// ECMWF's slot 0). Non-chart previews ignore it.
internal val SAMPLE_COLOR_SLOTS: Map<String, Int> = mapOf(
    "ecmwf_ifs025" to 0,
    "gfs_seamless" to 1,
    "icon_seamless" to 2,
    "ukmo_seamless" to 3,
)

@Composable
internal fun Frame(
    darkTheme: Boolean = false,
    colorPalette: ColorPalette = ColorPalette.RAINBOW,
    colorSlots: Map<String, Int> = SAMPLE_COLOR_SLOTS,
    content: @Composable () -> Unit,
) {
    // Render every preview in inspection mode, exactly as Android Studio's
    // @Preview pane does. Beyond matching Studio, this is what lets the Vico
    // charts snapshot deterministically: Vico's CartesianChartModelProducer
    // runs its model transform on Dispatchers.Default (a real background
    // thread) in normal composition, but switches to Dispatchers.Unconfined
    // — synchronous, on the calling thread — when LocalInspectionMode is set.
    // Without this, a chart-bearing preview races the background transform and
    // can capture a chrome-only card with no plotted line. The inspection-mode
    // guards in the *Banner wrappers don't bite here: the banner previews
    // render the stateless *Card variants directly, which carry no guard.
    CompositionLocalProvider(LocalInspectionMode provides true) {
        ClothesCastTheme(
            darkTheme = darkTheme,
            dynamicColor = false,
            colorPalette = colorPalette,
            colorSlots = colorSlots,
        ) {
            Surface { Column(modifier = Modifier.padding(16.dp)) { content() } }
        }
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
        // Mirrors RenderInsightSummary: the umbrella rides independently of the
        // wear clause so it folds into the precip clause ("…bring an umbrella").
        carriedAccessories = listOf("umbrella"),
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

@Preview(name = "Outfit · coat + pants + gloves", widthDp = 360)
@Composable
internal fun OutfitCoatPantsGlovesPreview() {
    Frame {
        OutfitPreviewCard(
            outfit = OutfitSuggestion(
                OutfitSuggestion.Top.THICK_COAT,
                OutfitSuggestion.Bottom.LONG_PANTS,
                OutfitSuggestion.Hands.GLOVES,
            ),
            label = "Today",
        )
    }
}

@Preview(name = "Outfit · jacket + jeans + umbrella", widthDp = 360)
@Composable
internal fun OutfitJacketJeansUmbrellaPreview() {
    Frame {
        OutfitPreviewCard(
            outfit = OutfitSuggestion(
                OutfitSuggestion.Top.THICK_JACKET,
                OutfitSuggestion.Bottom.JEANS,
                carried = OutfitSuggestion.Carried.UMBRELLA,
            ),
            label = "Today",
        )
    }
}

@Preview(name = "Outfit · coat + pants + gloves + umbrella", widthDp = 360)
@Composable
internal fun OutfitCoatPantsGlovesUmbrellaPreview() {
    Frame {
        OutfitPreviewCard(
            outfit = OutfitSuggestion(
                OutfitSuggestion.Top.THICK_COAT,
                OutfitSuggestion.Bottom.LONG_PANTS,
                hands = OutfitSuggestion.Hands.GLOVES,
                carried = OutfitSuggestion.Carried.UMBRELLA,
            ),
            label = "Today",
        )
    }
}

@Preview(name = "Outfit · sweater + jeans + rain jacket", widthDp = 360)
@Composable
internal fun OutfitSweaterJeansRainJacketPreview() {
    Frame {
        OutfitPreviewCard(
            outfit = OutfitSuggestion(
                OutfitSuggestion.Top.SWEATER,
                OutfitSuggestion.Bottom.JEANS,
                outer = OutfitSuggestion.Outer.RAIN_JACKET,
            ),
            label = "Today",
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

@Preview(name = "Outfit row · rain jacket + umbrella", widthDp = 360)
@Composable
internal fun OutfitRowRainJacketUmbrellaPreview() {
    // Reproduces the side-by-side half-width clip: the left card's worn line
    // names three pieces (top, rain-jacket shell, bottom) plus a carried
    // umbrella, so the caption wraps past two lines on a narrow card. Mirrors a
    // rainy default setup where both periods light the rain jacket + umbrella.
    Frame {
        OutfitPreviewRow(
            SAMPLE_INSIGHT.copy(
                period = ForecastPeriod.TODAY,
                outfit = OutfitSuggestion(
                    OutfitSuggestion.Top.SWEATER,
                    OutfitSuggestion.Bottom.JEANS,
                    carried = OutfitSuggestion.Carried.UMBRELLA,
                    outer = OutfitSuggestion.Outer.RAIN_JACKET,
                ),
                nextOutfit = OutfitSuggestion(
                    OutfitSuggestion.Top.SWEATER,
                    OutfitSuggestion.Bottom.JEANS,
                    carried = OutfitSuggestion.Carried.UMBRELLA,
                ),
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
                            thresholdC = 16.0,
                            ruleItem = Garment.SWEATER,
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
                            thresholdC = 23.0,
                            ruleItem = Garment.SHORTS,
                            comparison = Fact.Comparison.BELOW,
                        ),
                    ),
                ),
            ),
            temperatureUnit = TemperatureUnit.CELSIUS,
            clothesRules = ClothesRule.DEFAULTS,
            onNavigateToClothes = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "Outfit rationale · customised thresholds", widthDp = 360)
@Composable
internal fun OutfitRationaleDialogTunedPreview() {
    Frame {
        // Mid-tweak state: the user lowered their `sweater` rule from 16°C to 13°C,
        // so observed 13°C is still BELOW the customised threshold and the prose
        // stays "under". Cached fact still carries 16°C; the dialog reads the live
        // 13°C from clothesRules.
        OutfitRationaleDialog(
            outfit = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS),
            rationale = OutfitRationale(
                top = GarmentReason(
                    facts = listOf(
                        Fact(
                            metric = Fact.Metric.FEELS_LIKE_MIN,
                            observedC = 13.0,
                            observedAt = LocalTime.of(7, 0),
                            thresholdC = 16.0,
                            ruleItem = Garment.SWEATER,
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
                            thresholdC = 23.0,
                            ruleItem = Garment.SHORTS,
                            comparison = Fact.Comparison.BELOW,
                        ),
                    ),
                ),
            ),
            temperatureUnit = TemperatureUnit.CELSIUS,
            clothesRules = ClothesRule.DEFAULTS.map {
                if (it.item == Garment.SWEATER) it.copy(condition = ClothesRule.TemperatureBelow(13.0)) else it
            },
            onNavigateToClothes = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "Today · empty state", widthDp = 360)
@Composable
internal fun TodayEmptyStatePreview() {
    Frame { EmptyState(onRefresh = {}) }
}

@Preview(name = "Today · empty state (location required)", widthDp = 360)
@Composable
internal fun TodayEmptyStateLocationRequiredPreview() {
    Frame { EmptyState(onRefresh = {}, locationActionRequired = true, onSetUpLocation = {}) }
}

@Preview(name = "Today · insight loaded", widthDp = 360)
@Composable
internal fun TodayInsightCardPreview() {
    Frame { InsightCard(SAMPLE_INSIGHT, Region.SYSTEM) }
}

// Locks the rendered prose for a layered cold-weather outfit. The
// underlying ClothesClause is what `EvaluateClothesRules` produces after
// `Garment.layerReduce` runs — a user with a t-shirt rule alongside the
// default sweater + jeans rules sees "Wear a sweater and jeans." here,
// not the pre-layering "Wear a sweater, t-shirt, and jeans." If a future
// regression re-introduces the BASE garment to the outfit, this snapshot
// is the visible diff.
@Preview(name = "Today · insight loaded (layered cold day)", widthDp = 360)
@Composable
internal fun TodayInsightCardLayeredPreview() {
    Frame {
        InsightCard(
            SAMPLE_INSIGHT.copy(
                summary = SAMPLE_INSIGHT.summary.copy(
                    band = BandClause(TemperatureBand.COLD, TemperatureBand.COOL, 6.0, 14.0),
                    delta = DeltaClause(4, DeltaClause.Direction.COOLER),
                    clothes = ClothesClause(listOf("sweater", "jeans")),
                    precip = null,
                ),
                recommendedItems = listOf("sweater", "jeans"),
            ),
            Region.SYSTEM,
        )
    }
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

// Page 1 (next-period) of the Today pager sits between page 0 and the
// 7-day overview, so it shows both chevrons at once: left back to page
// 0, right forward to the 7-day page. Snapshot locks in the dual-icon
// layout — the centred date row stays centred between the 28.dp slots.
@Preview(name = "Today · insight with chevron both", widthDp = 360)
@Composable
internal fun TodayInsightCardWithChevronBothPreview() {
    Frame {
        InsightCard(
            SAMPLE_INSIGHT.copy(period = ForecastPeriod.TONIGHT),
            Region.SYSTEM,
            showChevronLeft = true,
            showChevronRight = true,
            onChevronTap = {},
            onChevronRightTap = {},
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

// EdgeFadeOverlay previews — three captures locking in the fade affordance's
// visible states (top-only / bottom-only / both). Stand-in card content fills
// a fixed 400 dp region so the 32 dp gradient bands sit at known offsets and
// regressions in fade height, colour blend, or alpha animation surface as a
// visible pixel diff. The fade colour is `colorScheme.background`, which is
// what the Scaffold's default `containerColor` resolves to on the real Today
// screen — so we wrap the demo in a Surface tinted to that same colour rather
// than the Frame helper's `colorScheme.surface`, otherwise the gradient's
// "fade-to-background" end would visibly mismatch the underlying surface.
@Composable
private fun EdgeFadeDemoFrame(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    ClothesCastTheme(darkTheme = darkTheme, dynamicColor = false, colorPalette = ColorPalette.RAINBOW) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxWidth().height(400.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun EdgeFadeDemoContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().height(130.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) { Box(modifier = Modifier.fillMaxSize().padding(16.dp)) { Text("Card 1") } }
        Card(
            modifier = Modifier.fillMaxWidth().height(130.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) { Box(modifier = Modifier.fillMaxSize().padding(16.dp)) { Text("Card 2") } }
        Card(
            modifier = Modifier.fillMaxWidth().height(130.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        ) { Box(modifier = Modifier.fillMaxSize().padding(16.dp)) { Text("Card 3") } }
    }
}

@Preview(name = "Edge fade · bottom only (scrolled to top)", widthDp = 360)
@Composable
internal fun EdgeFadeBottomOnlyPreview() {
    EdgeFadeDemoFrame {
        EdgeFadeOverlay(topFadeVisible = false, bottomFadeVisible = true) {
            EdgeFadeDemoContent()
        }
    }
}

@Preview(name = "Edge fade · both (mid-scroll)", widthDp = 360)
@Composable
internal fun EdgeFadeBothPreview() {
    EdgeFadeDemoFrame {
        EdgeFadeOverlay(topFadeVisible = true, bottomFadeVisible = true) {
            EdgeFadeDemoContent()
        }
    }
}

@Preview(name = "Edge fade · top only (scrolled to bottom)", widthDp = 360)
@Composable
internal fun EdgeFadeTopOnlyPreview() {
    EdgeFadeDemoFrame {
        EdgeFadeOverlay(topFadeVisible = true, bottomFadeVisible = false) {
            EdgeFadeDemoContent()
        }
    }
}

@Preview(name = "Edge fade · both (dark)", widthDp = 360)
@Composable
internal fun EdgeFadeBothDarkPreview() {
    EdgeFadeDemoFrame(darkTheme = true) {
        EdgeFadeOverlay(topFadeVisible = true, bottomFadeVisible = true) {
            EdgeFadeDemoContent()
        }
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
                modelsConsulted = listOf("ecmwf_ifs025", "gfs_seamless", "icon_seamless"),
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
                modelsConsulted = listOf("ecmwf_ifs025", "gfs_seamless"),
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
                modelsConsulted = listOf("ecmwf_ifs025", "gfs_seamless", "icon_seamless"),
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
                modelsConsulted = listOf("ecmwf_ifs025", "gfs_seamless"),
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
                modelsConsulted = listOf("ecmwf_ifs025", "gfs_seamless"),
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
                modelsConsulted = listOf("ecmwf_ifs025", "gfs_seamless", "icon_seamless"),
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
                modelsConsulted = listOf("ecmwf_ifs025", "gfs_seamless", "icon_seamless"),
            ),
            perModelHourly = null,
            temperatureUnit = TemperatureUnit.CELSIUS,
            windSpeedUnit = WindSpeedUnit.KMH,
            showModelSpread = false,
            onToggleModelSpread = {},
        )
    }
}

// Holiday banner + outfit-row previews. One per [HolidayId] so reviewers
// can eyeball each palette against the holiday it represents. Each preview
// renders the banner (its colour + emoji + text) immediately above an
// outfit row whose top and bottom slots are recoloured by the holiday's
// overrides — i.e. the exact pairing the user sees on the matching day.
//
// The "sample" insight is fixed at a cool spring temperature so the rule-
// driven outfit picker resolves to a SWEATER + LONG_PANTS tier in every
// holiday, keeping the per-holiday diffs to colour alone (icon shapes don't
// shift). The recolour map for the secondary "nextOutfit" tier sits empty
// — we override only the primary outfit's tiers so the diff focuses on
// what the banner actually highlights.
@Composable
private fun HolidayShowcase(
    holidayId: HolidayId,
    darkTheme: Boolean = false,
    region: Region = Region.SYSTEM,
) {
    val theme = HolidayCatalog.themeFor(holidayId)
        ?: error("HolidayCatalog has no entry for $holidayId")
    HolidayShowcase(theme = theme, darkTheme = darkTheme, region = region)
}

@Composable
private fun HolidayShowcase(
    theme: HolidayTheme,
    darkTheme: Boolean = false,
    region: Region = Region.SYSTEM,
) {
    Frame(darkTheme = darkTheme) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HolidayBanner(theme = theme, region = region, modifier = Modifier.fillMaxWidth())
            OutfitPreviewRow(
                SAMPLE_INSIGHT.copy(
                    period = ForecastPeriod.TODAY,
                    outfit = OutfitSuggestion(
                        OutfitSuggestion.Top.SWEATER,
                        OutfitSuggestion.Bottom.LONG_PANTS,
                    ),
                    nextOutfit = OutfitSuggestion(
                        OutfitSuggestion.Top.THICK_JACKET,
                        OutfitSuggestion.Bottom.LONG_PANTS,
                    ),
                ),
                outfitTopColors = theme.topOverrides,
                outfitBottomColors = theme.bottomOverrides,
                outfitTopStrokes = theme.topStrokeOverrides,
                outfitBottomStrokes = theme.bottomStrokeOverrides,
            )
        }
    }
}

@Preview(name = "Holiday · New Year", widthDp = 360)
@Composable
internal fun HolidayNewYearsDayPreview() = HolidayShowcase(HolidayId.NEW_YEARS_DAY)

@Preview(name = "Holiday · Coming of Age (JP)", widthDp = 360)
@Composable
internal fun HolidayJapanComingOfAgeDayPreview() = HolidayShowcase(HolidayId.JAPAN_COMING_OF_AGE_DAY)

@Preview(name = "Holiday · MLK Day", widthDp = 360)
@Composable
internal fun HolidayMlkDayPreview() = HolidayShowcase(HolidayId.MLK_DAY)

@Preview(name = "Holiday · Burns Night", widthDp = 360)
@Composable
internal fun HolidayBurnsNightPreview() = HolidayShowcase(HolidayId.BURNS_NIGHT)

@Preview(name = "Holiday · Australia Day", widthDp = 360)
@Composable
internal fun HolidayAustraliaDayPreview() = HolidayShowcase(HolidayId.AUSTRALIA_DAY)

@Preview(name = "Holiday · Waitangi Day", widthDp = 360)
@Composable
internal fun HolidayWaitangiDayPreview() = HolidayShowcase(HolidayId.WAITANGI_DAY)

@Preview(name = "Holiday · Valentine's Day", widthDp = 360)
@Composable
internal fun HolidayValentinesDayPreview() = HolidayShowcase(HolidayId.VALENTINES_DAY)

@Preview(name = "Holiday · US Presidents' Day", widthDp = 360)
@Composable
internal fun HolidayUsPresidentsDayPreview() = HolidayShowcase(HolidayId.US_PRESIDENTS_DAY)

@Preview(name = "Holiday · St David's Day", widthDp = 360)
@Composable
internal fun HolidayStDavidsDayPreview() = HolidayShowcase(HolidayId.ST_DAVIDS_DAY)

@Preview(name = "Holiday · Korean Independence Movement Day", widthDp = 360)
@Composable
internal fun HolidayKoreanIndependenceMovementDayPreview() =
    HolidayShowcase(HolidayId.KOREAN_INDEPENDENCE_MOVEMENT_DAY)

@Preview(name = "Holiday · St Patrick's Day", widthDp = 360)
@Composable
internal fun HolidayStPatricksDayPreview() = HolidayShowcase(HolidayId.ST_PATRICKS_DAY)

@Preview(name = "Holiday · St George's Day", widthDp = 360)
@Composable
internal fun HolidayStGeorgesDayPreview() = HolidayShowcase(HolidayId.ST_GEORGES_DAY)

@Preview(name = "Holiday · Anzac Day", widthDp = 360)
@Composable
internal fun HolidayAnzacDayPreview() = HolidayShowcase(HolidayId.ANZAC_DAY)

@Preview(name = "Holiday · Mother's Day", widthDp = 360)
@Composable
internal fun HolidayMothersDayPreview() = HolidayShowcase(HolidayId.MOTHERS_DAY)

@Preview(name = "Holiday · Greenery Day (JP)", widthDp = 360)
@Composable
internal fun HolidayJapanGreeneryDayPreview() = HolidayShowcase(HolidayId.JAPAN_GREENERY_DAY)

@Preview(name = "Holiday · Cinco de Mayo", widthDp = 360)
@Composable
internal fun HolidayCincoDeMayoPreview() = HolidayShowcase(HolidayId.CINCO_DE_MAYO)

@Preview(name = "Holiday · Croatia Statehood Day", widthDp = 360)
@Composable
internal fun HolidayCroatiaStatehoodDayPreview() = HolidayShowcase(HolidayId.CROATIA_STATEHOOD_DAY)

@Preview(name = "Holiday · US Memorial Day", widthDp = 360)
@Composable
internal fun HolidayUsMemorialDayPreview() = HolidayShowcase(HolidayId.US_MEMORIAL_DAY)

@Preview(name = "Holiday · Towel Day", widthDp = 360)
@Composable
internal fun HolidayTowelDayPreview() = HolidayShowcase(HolidayId.TOWEL_DAY)

// Composed (multi-celebration) banner: a GB user on a year where the last
// Monday of May lands on the 25th sees Spring Bank Holiday joined with
// Towel Day — bank-holiday title, Towel Day's teal/sand palette, and the
// "don't forget your towel" join clause. Built through the real
// ThemeForToday path so the snapshot exercises the composition end-to-end.
@Preview(name = "Holiday · Bank Holiday + Towel Day", widthDp = 360)
@Composable
internal fun HolidayBankHolidayWithTowelPreview() {
    val theme = ThemeForToday().resolve(
        date = java.time.LocalDate.of(2026, java.time.Month.MAY, 25),
        overrides = emptyMap(),
        enabledCountries = setOf("GB", HolidayCatalog.FUNNY),
        events = emptyList(),
        themeFromCalendarHolidays = false,
        themeFromCalendarBirthdays = false,
    ) ?: error("expected a composed Bank Holiday + Towel Day theme")
    HolidayShowcase(theme = theme)
}

@Preview(name = "Holiday · Italian Republic Day", widthDp = 360)
@Composable
internal fun HolidayItalyRepublicDayPreview() = HolidayShowcase(HolidayId.ITALY_REPUBLIC_DAY)

@Preview(name = "Holiday · Korean Memorial Day", widthDp = 360)
@Composable
internal fun HolidayKoreanMemorialDayPreview() = HolidayShowcase(HolidayId.KOREAN_MEMORIAL_DAY)

@Preview(name = "Holiday · Juneteenth", widthDp = 360)
@Composable
internal fun HolidayJuneteenthPreview() = HolidayShowcase(HolidayId.JUNETEENTH)

@Preview(name = "Holiday · Father's Day (Jun)", widthDp = 360)
@Composable
internal fun HolidayFathersDayJunPreview() = HolidayShowcase(HolidayId.FATHERS_DAY_JUN)

@Preview(name = "Holiday · Canada Day", widthDp = 360)
@Composable
internal fun HolidayCanadaDayPreview() = HolidayShowcase(HolidayId.CANADA_DAY)

@Preview(name = "Holiday · US Independence Day", widthDp = 360)
@Composable
internal fun HolidayUsIndependenceDayPreview() = HolidayShowcase(HolidayId.US_INDEPENDENCE_DAY)

@Preview(name = "Holiday · Bastille Day", widthDp = 360)
@Composable
internal fun HolidayBastilleDayPreview() = HolidayShowcase(HolidayId.BASTILLE_DAY)

@Preview(name = "Holiday · Marine Day (JP)", widthDp = 360)
@Composable
internal fun HolidayJapanMarineDayPreview() = HolidayShowcase(HolidayId.JAPAN_MARINE_DAY)

@Preview(name = "Holiday · Korean Liberation Day", widthDp = 360)
@Composable
internal fun HolidayKoreanLiberationDayPreview() = HolidayShowcase(HolidayId.KOREAN_LIBERATION_DAY)

@Preview(name = "Holiday · Father's Day (Sep)", widthDp = 360)
@Composable
internal fun HolidayFathersDaySepPreview() = HolidayShowcase(HolidayId.FATHERS_DAY_SEP)

@Preview(name = "Holiday · Labor Day (US/CA)", widthDp = 360)
@Composable
internal fun HolidayUsLaborDayPreview() = HolidayShowcase(HolidayId.US_LABOR_DAY)

@Preview(name = "Holiday · Brazil Independence", widthDp = 360)
@Composable
internal fun HolidayBrazilIndependenceDayPreview() = HolidayShowcase(HolidayId.BRAZIL_INDEPENDENCE_DAY)

@Preview(name = "Holiday · Talk Like a Pirate Day", widthDp = 360)
@Composable
internal fun HolidayPirateDayPreview() = HolidayShowcase(HolidayId.TALK_LIKE_A_PIRATE_DAY)

@Preview(name = "Holiday · German Unity Day", widthDp = 360)
@Composable
internal fun HolidayGermanUnityDayPreview() = HolidayShowcase(HolidayId.GERMAN_UNITY_DAY)

@Preview(name = "Holiday · Hangeul Day (KR)", widthDp = 360)
@Composable
internal fun HolidayKoreanHangeulDayPreview() = HolidayShowcase(HolidayId.KOREAN_HANGEUL_DAY)

@Preview(name = "Holiday · Canadian Thanksgiving", widthDp = 360)
@Composable
internal fun HolidayCanadianThanksgivingPreview() = HolidayShowcase(HolidayId.CANADIAN_THANKSGIVING)

@Preview(name = "Holiday · Hispanic Day", widthDp = 360)
@Composable
internal fun HolidaySpainHispanicDayPreview() = HolidayShowcase(HolidayId.SPAIN_HISPANIC_DAY)

@Preview(name = "Holiday · Labour Day (NZ)", widthDp = 360)
@Composable
internal fun HolidayNzLabourDayPreview() = HolidayShowcase(HolidayId.NZ_LABOUR_DAY)

@Preview(name = "Holiday · Halloween", widthDp = 360)
@Composable
internal fun HolidayHalloweenPreview() = HolidayShowcase(HolidayId.HALLOWEEN)

@Preview(name = "Holiday · Culture Day (JP)", widthDp = 360)
@Composable
internal fun HolidayJapanCultureDayPreview() = HolidayShowcase(HolidayId.JAPAN_CULTURE_DAY)

@Preview(name = "Holiday · Melbourne Cup Day", widthDp = 360)
@Composable
internal fun HolidayMelbourneCupDayPreview() = HolidayShowcase(HolidayId.MELBOURNE_CUP_DAY)

@Preview(name = "Holiday · US Election Day", widthDp = 360)
@Composable
internal fun HolidayUsElectionDayPreview() = HolidayShowcase(HolidayId.US_ELECTION_DAY)

@Preview(name = "Holiday · Bonfire Night", widthDp = 360)
@Composable
internal fun HolidayBonfireNightPreview() = HolidayShowcase(HolidayId.BONFIRE_NIGHT)

@Preview(name = "Holiday · Remembrance Sunday (UK)", widthDp = 360)
@Composable
internal fun HolidayUkRemembranceSundayPreview() = HolidayShowcase(HolidayId.UK_REMEMBRANCE_SUNDAY)

@Preview(name = "Holiday · Remembrance Day", widthDp = 360)
@Composable
internal fun HolidayRemembranceDayPreview() = HolidayShowcase(HolidayId.REMEMBRANCE_DAY)

// US variant of Nov 11 — same monochrome khaki palette, different banner
// copy ("Honoring our Veterans" vs "Lest we forget"). Wired by passing
// Region.EN_US into the showcase helper, which forwards it to HolidayBanner.
@Preview(name = "Holiday · Veterans Day (US)", widthDp = 360)
@Composable
internal fun HolidayRemembranceDayUsPreview() =
    HolidayShowcase(HolidayId.REMEMBRANCE_DAY, region = Region.EN_US)

// French variant of Nov 11 — same khaki, "Souvenons-nous" instead of
// "Lest we forget". Exercises the FR country override added in v3.
@Preview(name = "Holiday · Armistice Day (FR)", widthDp = 360)
@Composable
internal fun HolidayRemembranceDayFrPreview() =
    HolidayShowcase(HolidayId.REMEMBRANCE_DAY, region = Region.FR_FR)

@Preview(name = "Holiday · US Thanksgiving", widthDp = 360)
@Composable
internal fun HolidayUsThanksgivingPreview() = HolidayShowcase(HolidayId.US_THANKSGIVING)

@Preview(name = "Holiday · St Andrew's Day", widthDp = 360)
@Composable
internal fun HolidayStAndrewsDayPreview() = HolidayShowcase(HolidayId.ST_ANDREWS_DAY)

@Preview(name = "Holiday · Christmas Day", widthDp = 360)
@Composable
internal fun HolidayChristmasDayPreview() = HolidayShowcase(HolidayId.CHRISTMAS_DAY)

@Preview(name = "Holiday · Boxing Day", widthDp = 360)
@Composable
internal fun HolidayBoxingDayPreview() = HolidayShowcase(HolidayId.BOXING_DAY)

@Preview(name = "Holiday · Christmas (dark)", widthDp = 360)
@Composable
internal fun HolidayChristmasDayDarkPreview() = HolidayShowcase(HolidayId.CHRISTMAS_DAY, darkTheme = true)

// Canonical preview for the synthetic birthday theme. Parallels the
// HolidayShowcase block above, but FestiveThemes.birthday() builds the
// theme at runtime from a calendar event title rather than looking it
// up in HolidayCatalog, so HolidayShowcase (which requires a HolidayId)
// can't render it.
@Preview(name = "Birthday", widthDp = 360)
@Composable
internal fun BirthdayPreview() {
    val theme = FestiveThemes.birthday("Alice's birthday")
    Frame {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HolidayBanner(theme = theme, modifier = Modifier.fillMaxWidth())
            OutfitPreviewRow(
                SAMPLE_INSIGHT.copy(
                    period = ForecastPeriod.TODAY,
                    outfit = OutfitSuggestion(
                        OutfitSuggestion.Top.SWEATER,
                        OutfitSuggestion.Bottom.LONG_PANTS,
                    ),
                    nextOutfit = OutfitSuggestion(
                        OutfitSuggestion.Top.THICK_JACKET,
                        OutfitSuggestion.Bottom.LONG_PANTS,
                    ),
                ),
                outfitTopColors = theme.topOverrides,
                outfitBottomColors = theme.bottomOverrides,
                outfitTopStrokes = theme.topStrokeOverrides,
                outfitBottomStrokes = theme.bottomStrokeOverrides,
            )
        }
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

// The notification-permission warning now lives in the Today banner stack
// (gated on the user having a notify-posting schedule enabled), not in
// Settings. Its snapshot is captured with POST_NOTIFICATIONS denied — see
// PreviewSnapshots.notification_permission_banner — so the card actually
// renders; granted / pre-Android-13 it draws nothing.
@Preview(name = "Banner · notification permission", widthDp = 360)
@Composable
internal fun NotificationPermissionBannerPreview() {
    Frame { NotificationPermissionBanner() }
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

@Preview(name = "Banner · celebration themes", widthDp = 360)
@Composable
internal fun CelebrationThemesCardPreview() {
    Frame {
        CelebrationThemesCardContent(
            onOpenSettings = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "Banner · clothes promo", widthDp = 360)
@Composable
internal fun ClothesPromoCardPreview() {
    Frame {
        ClothesPromoCardContent(
            onOpenSettings = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "Banner · schedule promo", widthDp = 360)
@Composable
internal fun SchedulePromoCardPreview() {
    Frame {
        SchedulePromoCardContent(
            onOpenSettings = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "Banner · play promo", widthDp = 360)
@Composable
internal fun PlayPromoCardPreview() {
    Frame {
        PlayPromoCardContent(
            onDismiss = {},
        )
    }
}

@Preview(name = "Banner · gemini voices promo", widthDp = 360)
@Composable
internal fun GeminiTtsPromoCardPreview() {
    Frame {
        GeminiTtsPromoCardContent(
            onSetUpVoices = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "Banner · gemini voice limit", widthDp = 360)
@Composable
internal fun GeminiTtsLimitCardPreview() {
    Frame {
        GeminiTtsLimitCardContent(
            onOpenVoice = {},
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

// Realistic morning-insight slice (07:00..18:59 — half-open at the
// trailing edge, same shape `slicedForToday` produces in the
// pipeline). Use this preview to see what the Today screen's chart
// actually looks like with a period slice rather than the synthetic
// 24-hour curve above.
@Preview(name = "Forecast chart · 07–18 morning slice", widthDp = 360)
@Composable
internal fun ForecastChartMorningSlicePreview() {
    Frame {
        ForecastChart(
            hourly = SAMPLE_HOURLY.subList(7, 19),
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

// Pins the "now" indicator at a fractional position so the snapshot locks in
// where the line lands relative to the axes and the data. Picks 14.5
// (mid-afternoon, between the curve's two warmest hours) so the indicator
// sits roughly centred and is clearly visible against both the line and the
// gridlines.
@Preview(name = "Forecast chart · with current time", widthDp = 360)
@Composable
internal fun ForecastChartWithCurrentTimePreview() {
    Frame {
        // Drive the shared scrub controller at 14:30 so the indicator
        // lands mid-afternoon, between the curve's two warmest hours.
        // Use setNow rather than scrubTo so the controller stays in its
        // idle "tracking live time" mode — no restore icon — matching
        // the original now-indicator snapshot semantics.
        val controller = androidx.compose.runtime.remember {
            ChartScrubController().apply {
                setNow(
                    java.time.LocalDateTime.of(
                        // Fixed date (a Monday) so the fixture is deterministic
                        // across runs — see issue #920.
                        java.time.LocalDate.of(2024, 1, 1),
                        java.time.LocalTime.of(14, 30),
                    ),
                )
            }
        }
        CompositionLocalProvider(LocalChartScrub provides controller) {
            ForecastChart(
                hourly = SAMPLE_HOURLY,
                temperatureUnit = TemperatureUnit.CELSIUS,
                // Match the pinned setNow date above so the now-indicator lands
                // mid-chart; ForecastChart's startDate otherwise defaults to
                // LocalDate.now() and the two-year gap would push it off-axis.
                startDate = java.time.LocalDate.of(2024, 1, 1),
                showFeelsLike = true,
            )
        }
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
    // Hourly rainfall (mm) loosely tracking the probability curve so the
    // amount card has something to plot — peaking around the same
    // mid-afternoon hour the probability does, well into "moderate rain"
    // territory at the peak.
    val precipMmByHour = listOf(
        0.0, 0.0, 0.0, 0.0, 0.05, 0.1,        // 00–05
        0.2, 0.4, 0.8, 1.5, 2.5, 3.5,         // 06–11
        4.5, 5.0, 4.8, 4.0, 3.0, 2.0,         // 12–17
        1.0, 0.5, 0.3, 0.1, 0.05, 0.0,        // 18–23
    )
    SAMPLE_HOURLY.mapIndexed { i, h ->
        h.copy(
            precipitationProbabilityPct = precipPctByHour[i],
            precipitationMm = precipMmByHour[i],
        )
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

@Preview(name = "Precipitation amount card · rainy", widthDp = 360)
@Composable
internal fun PrecipitationAmountCardPreview() {
    Frame { PrecipitationAmountCard(hourly = SAMPLE_HOURLY_RAINY) }
}

@Preview(name = "Precipitation amount card · rainy (dark)", widthDp = 360)
@Composable
internal fun PrecipitationAmountCardDarkPreview() {
    Frame(darkTheme = true) { PrecipitationAmountCard(hourly = SAMPLE_HOURLY_RAINY) }
}

@Preview(name = "Precipitation amount card · dry", widthDp = 360)
@Composable
internal fun PrecipitationAmountCardDryPreview() {
    Frame { PrecipitationAmountCard(hourly = SAMPLE_HOURLY) }
}

@Preview(name = "Precipitation amount card · with model spread", widthDp = 360)
@Composable
internal fun PrecipitationAmountCardWithModelSpreadPreview() {
    Frame {
        PrecipitationAmountCard(
            hourly = SAMPLE_HOURLY_RAINY,
            forDate = SAMPLE_PER_MODEL_DATE,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            showModelSpread = true,
        )
    }
}

// Sample anchor for per-model entries. The charts resolve per-model points
// to x positions by timestamp lookup against their hourly window (see
// [hourlyTimestampIndices]), so every preview that pairs this fixture with a
// chart must pass `startDate`/`forDate` = this date — a mismatched window
// date drops the per-model series entirely. Pinned (and matching
// SAMPLE_INSIGHT.forDate's vintage) so preview output stays deterministic
// across runs.
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
        precipMmScale: Double = 1.0,
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
            precipitationMm = (h.precipitationMm * precipMmScale).coerceAtLeast(0.0),
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
            "ecmwf_ifs025" to shift(
                deltaC = -1.5, precipDelta = -10.0, windBase = 8.0, cloudBase = 55.0,
                solarPeakWm2 = 600.0, sunshineMinutesAtMidday = 35.0, uvPeak = 5.0,
                precipMmScale = 0.9,
            ),
            "gfs_seamless" to shift(
                deltaC = 0.5, precipDelta = 5.0, windBase = 12.0, cloudBase = 70.0,
                solarPeakWm2 = 500.0, sunshineMinutesAtMidday = 25.0, uvPeak = 4.0,
                precipMmScale = 1.2,
            ),
            "icon_seamless" to shift(
                deltaC = 2.0, precipDelta = -5.0, windBase = 6.0, cloudBase = 40.0,
                solarPeakWm2 = 800.0, sunshineMinutesAtMidday = 50.0, uvPeak = 6.5,
                precipMmScale = 0.6,
            ),
            // UKMO reports temperature and precipitation_mm but Open-Meteo
            // omits `precipitation_probability_ukmo_seamless`, so its
            // probability series is wholesale null. Modeling that here keeps
            // the snapshots honest: UKMO draws a line on the temperature and
            // rainfall-amount charts (and appears in their legends) but is
            // absent from the chance-of-rain chart and its legend — the exact
            // mismatch the chance-of-rain legend filter must respect. It also
            // exercises the tail of MODEL_DRAW_ORDER, which the ECMWF/GFS/ICON
            // trio never reached.
            "ukmo_seamless" to shift(
                deltaC = -3.0, precipDelta = 0.0, windBase = 10.0, cloudBase = 80.0,
                solarPeakWm2 = 450.0, sunshineMinutesAtMidday = 20.0, uvPeak = 3.5,
                precipMmScale = 1.4,
            ).map { it.copy(precipitationProbabilityPct = null) },
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
            startDate = SAMPLE_PER_MODEL_DATE,
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
            startDate = SAMPLE_PER_MODEL_DATE,
            showModelSpread = true,
        )
    }
}

// Locks the scrubbed-card appearance into a snapshot: indicator at 14:30
// drawn by [ChartScrubIndicator], readout row ("14:30 · X km/h") rendered
// by [ChartReadout] using the wrapper's [tooltipValueFormat], and the
// top-right restore icon ([ChartRestoreOverlay]). Pinned on WindCard
// specifically because it exercises the diagnostic path — readout uses
// the consensus mainLine + tooltipValueFormat — that the simpler
// ForecastCard preview below doesn't cover.
@Preview(name = "Wind card · scrubbed", widthDp = 360)
@Composable
internal fun WindCardScrubbedPreview() {
    Frame {
        // Fixed date so the scrub fixture is deterministic across runs — see
        // issue #920. Must match the per-model fixture's anchor date: the
        // chart resolves per-model points by timestamp against the window
        // keyed off [startDate], and the readout only renders hour-of-day,
        // so the date itself never reaches the snapshot.
        val today = SAMPLE_PER_MODEL_DATE
        val controller = androidx.compose.runtime.remember {
            ChartScrubController().apply {
                scrubTo(java.time.LocalDateTime.of(today, java.time.LocalTime.of(14, 30)))
            }
        }
        CompositionLocalProvider(LocalChartScrub provides controller) {
            WindCard(
                hourly = SAMPLE_HOURLY_RAINY,
                perModelHourly = SAMPLE_PER_MODEL_HOURLY,
                startDate = today,
                showModelSpread = true,
            )
        }
    }
}

// Imperial variant — exercises the km/h → mph conversion in both the picker
// (Y-axis values) and the subtitle string. Pairs with the default km/h preview
// above to lock in the unit-switching path now that the wind-speed unit is its
// own setting.
@Preview(name = "Wind card · with model spread (mph)", widthDp = 360)
@Composable
internal fun WindCardWithModelSpreadMphPreview() {
    Frame {
        WindCard(
            hourly = SAMPLE_HOURLY_RAINY,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            windSpeedUnit = WindSpeedUnit.MPH,
            startDate = SAMPLE_PER_MODEL_DATE,
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
            startDate = SAMPLE_PER_MODEL_DATE,
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
            startDate = SAMPLE_PER_MODEL_DATE,
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
            startDate = SAMPLE_PER_MODEL_DATE,
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
            startDate = SAMPLE_PER_MODEL_DATE,
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
            startDate = SAMPLE_PER_MODEL_DATE,
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
            startDate = SAMPLE_PER_MODEL_DATE,
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
            startDate = SAMPLE_PER_MODEL_DATE,
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
            startDate = SAMPLE_PER_MODEL_DATE,
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
            startDate = SAMPLE_PER_MODEL_DATE,
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
            startDate = SAMPLE_PER_MODEL_DATE,
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
            startDate = SAMPLE_PER_MODEL_DATE,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            showModelSpread = true,
        )
    }
}

// Companion to [WindCardScrubbedPreview] — covers the inline-readout
// path in [ForecastCard] (string-templated against the temperature
// unit) rather than the [ChartReadout] helper. Same 14:30 anchor so
// both snapshots line up visually for review.
@Preview(name = "Forecast card · scrubbed", widthDp = 360)
@Composable
internal fun ForecastCardScrubbedPreview() {
    Frame {
        // Fixed date so the scrub fixture is deterministic across runs — see
        // issue #920. Must match the per-model fixture's anchor date (see
        // [WindCardScrubbedPreview]); the readout only renders hour-of-day,
        // so the date itself never reaches the snapshot.
        val today = SAMPLE_PER_MODEL_DATE
        val controller = androidx.compose.runtime.remember {
            ChartScrubController().apply {
                scrubTo(java.time.LocalDateTime.of(today, java.time.LocalTime.of(14, 30)))
            }
        }
        CompositionLocalProvider(LocalChartScrub provides controller) {
            ForecastCard(
                hourly = SAMPLE_HOURLY,
                temperatureUnit = TemperatureUnit.CELSIUS,
                startDate = today,
                perModelHourly = SAMPLE_PER_MODEL_HOURLY,
                showModelSpread = true,
            )
        }
    }
}

@Preview(name = "Air temperature card · with model spread", widthDp = 360)
@Composable
internal fun AirTemperatureCardWithModelSpreadPreview() {
    Frame {
        AirTemperatureCard(
            hourly = SAMPLE_HOURLY,
            temperatureUnit = TemperatureUnit.CELSIUS,
            startDate = SAMPLE_PER_MODEL_DATE,
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
            startDate = SAMPLE_PER_MODEL_DATE,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            showModelSpread = true,
        )
    }
}

// Default-view "consensus only" variants of the main cards (showModelSpread =
// false). These exercise the shaded min–max range band drawn beneath the
// consensus line on the default view — the band hides once the per-model
// overlay toggles on, as in the "· with model spread" previews above.
@Preview(name = "Forecast card · consensus only", widthDp = 360)
@Composable
internal fun ForecastCardConsensusPreview() {
    Frame {
        ForecastCard(
            hourly = SAMPLE_HOURLY,
            temperatureUnit = TemperatureUnit.CELSIUS,
            // Match the per-model fixture's date so the band's window-start
            // anchor lines up with the sample entries' timestamps.
            startDate = SAMPLE_PER_MODEL_DATE,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            showModelSpread = false,
        )
    }
}

@Preview(name = "Air temperature card · consensus only", widthDp = 360)
@Composable
internal fun AirTemperatureCardConsensusPreview() {
    Frame {
        AirTemperatureCard(
            hourly = SAMPLE_HOURLY,
            temperatureUnit = TemperatureUnit.CELSIUS,
            startDate = SAMPLE_PER_MODEL_DATE,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            showModelSpread = false,
        )
    }
}

@Preview(name = "Precipitation card · consensus only", widthDp = 360)
@Composable
internal fun PrecipitationCardConsensusPreview() {
    Frame {
        PrecipitationCard(
            hourly = SAMPLE_HOURLY_RAINY,
            startDate = SAMPLE_PER_MODEL_DATE,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            showModelSpread = false,
        )
    }
}

@Preview(name = "Precipitation amount card · consensus only", widthDp = 360)
@Composable
internal fun PrecipitationAmountCardConsensusPreview() {
    Frame {
        PrecipitationAmountCard(
            hourly = SAMPLE_HOURLY_RAINY,
            forDate = SAMPLE_PER_MODEL_DATE,
            perModelHourly = SAMPLE_PER_MODEL_HOURLY,
            showModelSpread = false,
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
            startDate = SAMPLE_PER_MODEL_DATE,
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
            startDate = SAMPLE_PER_MODEL_DATE,
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
            startDate = SAMPLE_PER_MODEL_DATE,
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
            startDate = SAMPLE_PER_MODEL_DATE,
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

// Realistic 7-day feels-like envelope for the "Next 7 days" pager page. Picks
// a gentle warm-up followed by a midweek cool front so the high and low
// lines diverge / converge across the week — exercises the chart's y-axis
// auto-sizing and proves both lines render distinctly. Dates pin a fixed
// Monday start so the day-of-week x-axis labels in the snapshot stay
// deterministic regardless of when the test runs.
private val SAMPLE_WEEK: List<DailyForecast> = run {
    val highs = listOf(18.0, 20.0, 22.0, 19.0, 15.0, 14.0, 17.0)
    val lows = listOf(9.0, 11.0, 13.0, 12.0, 7.0, 6.0, 9.0)
    val start = LocalDate.of(2026, 4, 27) // Monday
    highs.indices.map { i ->
        // Build a 24-hour diurnal curve per day: cosine-shaped from the
        // low at 04:00 to the high at 14:00. Gives the 7-day chart stack
        // realistic-looking hourly data so the previews exercise the full
        // axis range instead of degenerate flat lines, while keeping the
        // construction obviously deterministic for snapshot stability.
        val hi = highs[i]
        val lo = lows[i]
        val hourly = (0 until 24).map { h ->
            val phase = ((h - 4 + 24) % 24) / 24.0 // 0 at 04:00, 0.5 at 16:00
            val curve = 0.5 * (1 - kotlin.math.cos(phase * 2 * Math.PI))
            val t = lo + (hi - lo) * curve
            HourlyForecast(
                time = LocalTime.of(h, 0),
                temperatureC = t + 1.0,
                feelsLikeC = t,
                // Light afternoon shower mid-week so the precipitation
                // charts have something to draw — peak around Wed 15:00.
                precipitationProbabilityPct = if (i == 2 && h in 14..17) 40.0 - (h - 15).let { if (it < 0) -it else it } * 8 else 5.0,
                condition = if (i == 2 && h in 14..17) WeatherCondition.RAIN else WeatherCondition.CLEAR,
                precipitationMm = if (i == 2 && h in 14..17) 0.4 else 0.0,
            )
        }
        DailyForecast(
            date = start.plusDays(i.toLong()),
            temperatureMinC = lo + 1.0,
            temperatureMaxC = hi + 1.0,
            feelsLikeMinC = lo,
            feelsLikeMaxC = hi,
            precipitationProbabilityMaxPct = if (i == 2) 40.0 else 5.0,
            precipitationMmTotal = if (i == 2) 1.2 else 0.0,
            condition = if (i == 2) WeatherCondition.RAIN else WeatherCondition.CLEAR,
            hourly = hourly,
        )
    }
}

// Outfit pair pinned at the top of every 7-day-page snapshot — same row
// pages 0 / 1 surface, so the snapshots cover the pagination-consistency
// invariant ("outfit row sits at the same vertical offset on all three
// pages") as well as the chart deck below it.
private val SAMPLE_WEEK_OUTFIT_INSIGHT = SAMPLE_INSIGHT.copy(
    period = ForecastPeriod.TODAY,
    outfit = OutfitSuggestion(OutfitSuggestion.Top.TSHIRT, OutfitSuggestion.Bottom.SHORTS),
    nextOutfit = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS),
)

// The 7-day previews render the full [HomePageScaffold], whose banner stack
// reads runtime-only app state (update checker, crash log, telemetry pref).
// [Frame] already provides LocalInspectionMode = true, so those banners no-op
// (see the LocalInspectionMode guards in the *Banner wrappers) and the chart
// deck snapshots cleanly without a live Application — matching how Android
// Studio renders the same screen.
@Composable
private fun SevenDayFrame(content: @Composable () -> Unit) {
    Frame { content() }
}

// [SAMPLE_WEEK]'s feels-like highs swing from 18° today up to 22° Wednesday
// and back down to 15° Friday — enough for [DeriveWeekAheadInsight] to
// fire both the first-warmer and first-cooler slots, so the snapshot
// exercises the week-ahead headline card above the chart deck. (The 40%
// midweek rain peak sits below the 50% threshold so the rain slot stays
// empty.) Monday start keeps the day-of-week in the headline deterministic.
@Preview(name = "Seven-day page · weekly card", widthDp = 360)
@Composable
internal fun SevenDayPagePreview() {
    SevenDayFrame {
        SevenDayPage(
            days = SAMPLE_WEEK,
            state = TodayState(),
            weekPerModelHourly = null,
            scrollState = androidx.compose.foundation.rememberScrollState(),
            onChevronTap = {},
            outfitInsight = SAMPLE_WEEK_OUTFIT_INSIGHT,
        )
    }
}

// Mirrors the per-period [TodayInsightCardWithLocationPreview] — exercises
// the header's location chip next to the "Next 7 days" label so the maps
// deep-link tap-target gets snapshot coverage.
@Preview(name = "Seven-day page · with location", widthDp = 360)
@Composable
internal fun SevenDayPageWithLocationPreview() {
    SevenDayFrame {
        SevenDayPage(
            days = SAMPLE_WEEK,
            state = TodayState(),
            weekPerModelHourly = null,
            scrollState = androidx.compose.foundation.rememberScrollState(),
            onChevronTap = {},
            location = Location(
                latitude = 42.3601,
                longitude = -71.0589,
                displayName = "Boston, Massachusetts, United States",
            ),
            outfitInsight = SAMPLE_WEEK_OUTFIT_INSIGHT,
        )
    }
}

// Synthesises a 7-day per-model bundle by mirroring [SAMPLE_WEEK]'s
// hourly stream into three models with small fixed offsets. Covers all
// 7 dates so [SevenDayPage]'s coverage gate ([weekPerModelDiagnostics])
// passes, the diagnostic cards render, and the tap-hint card appears.
private val SAMPLE_WEEK_PER_MODEL_HOURLY: PerModelHourly = run {
    fun shift(deltaC: Double, precipDelta: Double, windBase: Double) =
        SAMPLE_WEEK.flatMap { day ->
            day.hourly.map { h ->
                PerModelHour(
                    time = java.time.LocalDateTime.of(day.date, h.time),
                    apparentTemperatureC = h.feelsLikeC + deltaC,
                    temperatureC = h.temperatureC + deltaC,
                    precipitationProbabilityPct = (h.precipitationProbabilityPct + precipDelta)
                        .coerceIn(0.0, 100.0),
                    precipitationMm = h.precipitationMm,
                    windSpeedKmh = windBase + (h.time.hour - 12).let { if (it < 0) -it else it } * 0.4,
                    relativeHumidityPct = 70.0,
                    cloudCoverPct = 55.0,
                    shortwaveRadiationWm2 = null,
                    sunshineDurationSec = null,
                    uvIndex = null,
                )
            }
        }
    PerModelHourly(
        byModel = mapOf(
            "ecmwf_ifs025" to shift(deltaC = -1.5, precipDelta = -10.0, windBase = 8.0),
            "gfs_seamless" to shift(deltaC = 0.5, precipDelta = 5.0, windBase = 12.0),
            "icon_seamless" to shift(deltaC = 2.0, precipDelta = -5.0, windBase = 6.0),
        ),
    )
}

// Forecast days 8-14 for the "Following 7 days" page. Same diurnal-curve
// construction as [SAMPLE_WEEK], starting the Monday after it and trending
// warmer (highs 19→24) so the week-ahead headline — compared against today's
// 18° baseline — fires "Warmer …" rather than collapsing to the steady line.
private val SAMPLE_FOLLOWING_WEEK: List<DailyForecast> = run {
    val highs = listOf(19.0, 21.0, 23.0, 24.0, 22.0, 20.0, 21.0)
    val lows = listOf(10.0, 12.0, 13.0, 14.0, 12.0, 11.0, 12.0)
    val start = SAMPLE_WEEK.last().date.plusDays(1)
    highs.indices.map { i ->
        val hi = highs[i]
        val lo = lows[i]
        val hourly = (0 until 24).map { h ->
            val phase = ((h - 4 + 24) % 24) / 24.0
            val curve = 0.5 * (1 - kotlin.math.cos(phase * 2 * Math.PI))
            val t = lo + (hi - lo) * curve
            HourlyForecast(
                time = LocalTime.of(h, 0),
                temperatureC = t + 1.0,
                feelsLikeC = t,
                precipitationProbabilityPct = 5.0,
                condition = WeatherCondition.CLEAR,
                precipitationMm = 0.0,
            )
        }
        DailyForecast(
            date = start.plusDays(i.toLong()),
            temperatureMinC = lo + 1.0,
            temperatureMaxC = hi + 1.0,
            feelsLikeMinC = lo,
            feelsLikeMaxC = hi,
            precipitationProbabilityMaxPct = 5.0,
            precipitationMmTotal = 0.0,
            condition = WeatherCondition.CLEAR,
            hourly = hourly,
        )
    }
}

// Per-model series for the second week, modelling realistic thinning: ICON is
// absent (it runs ~7.5 days, so it never reaches day 8), ECMWF stops a day
// short of the end, and GFS stops earlier still. Their union doesn't cover the
// last day, so the strict coverage gate would hide the deck — this is exactly
// the partial-coverage case the "Following 7 days" page (allowPartialModelCoverage
// = true) renders anyway, with each per-model line stopping on its last day.
private val SAMPLE_FOLLOWING_WEEK_PER_MODEL_HOURLY: PerModelHourly = run {
    fun shift(
        deltaC: Double,
        precipDelta: Double,
        windBase: Double,
        cloudBase: Double,
        solarPeakWm2: Double,
        sunshineMinutesAtMidday: Double,
        uvPeak: Double,
    ) = SAMPLE_FOLLOWING_WEEK.flatMap { day ->
        day.hourly.map { h ->
            // Daytime bell curve (zero overnight, peak ~noon) for the
            // radiation-driven metrics, matching how solar / sunshine / UV behave.
            val hour = h.time.hour
            val daylight = if (hour in 6..18) {
                kotlin.math.sin((hour - 6) / 12.0 * kotlin.math.PI)
            } else {
                0.0
            }
            PerModelHour(
                time = java.time.LocalDateTime.of(day.date, h.time),
                apparentTemperatureC = h.feelsLikeC + deltaC,
                temperatureC = h.temperatureC + deltaC,
                precipitationProbabilityPct = (h.precipitationProbabilityPct + precipDelta)
                    .coerceIn(0.0, 100.0),
                precipitationMm = h.precipitationMm,
                windSpeedKmh = windBase + (h.time.hour - 12).let { if (it < 0) -it else it } * 0.4,
                relativeHumidityPct = 65.0,
                cloudCoverPct = cloudBase,
                shortwaveRadiationWm2 = solarPeakWm2 * daylight,
                sunshineDurationSec = sunshineMinutesAtMidday * 60.0 * daylight,
                uvIndex = uvPeak * daylight,
            )
        }
    }
    // ECMWF reaches day 13 (index 5), GFS only day 11 (index 3); neither covers
    // day 14, and days 12-13 carry ECMWF alone — the thinning the second week
    // really shows.
    val ecmwfThrough = SAMPLE_FOLLOWING_WEEK[5].date
    val gfsThrough = SAMPLE_FOLLOWING_WEEK[3].date
    PerModelHourly(
        byModel = mapOf(
            "ecmwf_ifs025" to shift(
                deltaC = -1.5, precipDelta = 0.0, windBase = 7.0, cloudBase = 50.0,
                solarPeakWm2 = 620.0, sunshineMinutesAtMidday = 38.0, uvPeak = 5.2,
            ).filter { !it.time.toLocalDate().isAfter(ecmwfThrough) },
            "gfs_seamless" to shift(
                deltaC = 0.8, precipDelta = 8.0, windBase = 11.0, cloudBase = 65.0,
                solarPeakWm2 = 520.0, sunshineMinutesAtMidday = 28.0, uvPeak = 4.3,
            ).filter { !it.time.toLocalDate().isAfter(gfsThrough) },
        ),
    )
}

// A full two-week per-model series (days 1-14), built by concatenating the
// first- and second-week samples per model. Mirrors what production hands the
// "Following 7 days" page: a series that *starts at today*, not at day 8. The
// page must slice it to its own window before plotting (the slice keeps the
// off-window days 1-7 from stretching the charts' y-bounds), so feeding this
// to [FollowingWeekPagePreview] turns the snapshot into a regression guard
// for that slicing — drop the slice and the y-axes rescale, changing the PNG.
private val SAMPLE_TWO_WEEK_PER_MODEL_HOURLY: PerModelHourly = PerModelHourly(
    byModel = (SAMPLE_WEEK_PER_MODEL_HOURLY.byModel.keys + SAMPLE_FOLLOWING_WEEK_PER_MODEL_HOURLY.byModel.keys)
        .associateWith { id ->
            SAMPLE_WEEK_PER_MODEL_HOURLY.byModel[id].orEmpty() +
                SAMPLE_FOLLOWING_WEEK_PER_MODEL_HOURLY.byModel[id].orEmpty()
        },
)

// Exercises the tap-hint card and the per-model envelope on the 7-day
// primary charts. With [showModelSpread] off, the hint reads "Tap to
// see each model's forecast" and the primary charts draw consensus
// only; the hint flips and the envelope appears when toggled on.
@Preview(name = "Seven-day page · with per-model spread off", widthDp = 360)
@Composable
internal fun SevenDayPageWithPerModelSpreadOffPreview() {
    SevenDayFrame {
        SevenDayPage(
            days = SAMPLE_WEEK,
            state = TodayState(),
            weekPerModelHourly = SAMPLE_WEEK_PER_MODEL_HOURLY,
            scrollState = androidx.compose.foundation.rememberScrollState(),
            onChevronTap = {},
            outfitInsight = SAMPLE_WEEK_OUTFIT_INSIGHT,
        )
    }
}

@Preview(name = "Seven-day page · with per-model spread on", widthDp = 360)
@Composable
internal fun SevenDayPageWithPerModelSpreadOnPreview() {
    SevenDayFrame {
        SevenDayPage(
            days = SAMPLE_WEEK,
            state = TodayState(showModelSpread = true),
            weekPerModelHourly = SAMPLE_WEEK_PER_MODEL_HOURLY,
            scrollState = androidx.compose.foundation.rememberScrollState(),
            onChevronTap = {},
            outfitInsight = SAMPLE_WEEK_OUTFIT_INSIGHT,
        )
    }
}

// "Next 7 days" page carrying the forward chevron that advances to the
// "Following 7 days" page — exercises the right-hand header slot that the
// other seven-day previews leave empty.
@Preview(name = "Seven-day page · with forward chevron", widthDp = 360)
@Composable
internal fun SevenDayPageWithForwardChevronPreview() {
    SevenDayFrame {
        SevenDayPage(
            days = SAMPLE_WEEK,
            state = TodayState(),
            weekPerModelHourly = null,
            scrollState = androidx.compose.foundation.rememberScrollState(),
            onChevronTap = {},
            onForwardChevronTap = {},
            outfitInsight = SAMPLE_WEEK_OUTFIT_INSIGHT,
        )
    }
}

// The full Following-7-days chart deck (days 8-14), rendered outside the page's
// scroll container so the whole stack captures — the [FollowingWeekPagePreview]
// snapshot stops at the fold, so the charts below it had no coverage. Renders
// the same ten cards [SevenDayPage] does, in the same order: the four primary
// charts plus the wind / humidity / cloud / solar / UV / sunshine diagnostic
// deck (all fetched across the 14-day window). Model spread on, day-of-week
// axis. The per-model sample thins realistically — ECMWF to day 13, GFS to day
// 11, no ICON — so the diagnostic lines stop mid-window and the last day is
// bare, the ragged second-week look the relaxed coverage gate now allows.
@Preview(name = "Following-week chart deck", widthDp = 360)
@Composable
internal fun FollowingWeekChartDeckPreview() {
    Frame {
        val days = SAMPLE_FOLLOWING_WEEK
        val flat = days.flatMap { it.hourly }
        val startDate = days.first().date
        val perModel = SAMPLE_FOLLOWING_WEEK_PER_MODEL_HOURLY
        val labels = days.map {
            it.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
        }
        val dayFormatter =
            com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter { _, value, _ ->
                labels[((value.toInt() - 12) / 24).coerceIn(0, labels.lastIndex)]
            }
        val placer = com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis.ItemPlacer
            .aligned(spacing = { 24 }, offset = { 12 })
        CompositionLocalProvider(
            LocalChartBottomFormatter provides dayFormatter,
            LocalChartBottomItemPlacer provides placer,
            // Match SevenDayPage: peak subtitles name the day ("Peak X Friday")
            // rather than an hour-of-day that can't say which day. Pin "today"
            // to the deck's first day so the snapshot exercises the relative
            // "today" / "tomorrow" labels alongside the bare weekday ones.
            LocalScrubMomentFormat provides ScrubMomentFormat.DayPlusHour,
            LocalForecastToday provides startDate,
        ) {
            ForecastCard(
                hourly = flat,
                temperatureUnit = TemperatureUnit.CELSIUS,
                startDate = startDate,
                perModelHourly = perModel,
                showModelSpread = true,
            )
            AirTemperatureCard(
                hourly = flat,
                temperatureUnit = TemperatureUnit.CELSIUS,
                startDate = startDate,
                perModelHourly = perModel,
                showModelSpread = true,
            )
            PrecipitationCard(
                hourly = flat,
                startDate = startDate,
                perModelHourly = perModel,
                showModelSpread = true,
            )
            PrecipitationAmountCard(
                hourly = flat,
                forDate = startDate,
                period = ForecastPeriod.TODAY,
                perModelHourly = perModel,
                showModelSpread = true,
            )
            WindCard(
                hourly = flat,
                perModelHourly = perModel,
                startDate = startDate,
                showModelSpread = true,
            )
            HumidityCard(
                hourly = flat,
                perModelHourly = perModel,
                startDate = startDate,
                showModelSpread = true,
            )
            CloudCard(
                hourly = flat,
                perModelHourly = perModel,
                startDate = startDate,
                showModelSpread = true,
            )
            SolarRadiationCard(
                hourly = flat,
                perModelHourly = perModel,
                startDate = startDate,
                showModelSpread = true,
            )
            UvIndexCard(
                hourly = flat,
                perModelHourly = perModel,
                startDate = startDate,
                showModelSpread = true,
            )
            SunshineCard(
                hourly = flat,
                perModelHourly = perModel,
                forDate = startDate,
                period = ForecastPeriod.TODAY,
                showModelSpread = true,
            )
        }
    }
}

// The fourth pager page — forecast days 8-14. Exercises the
// "Following 7 days" title, the day-8 day-of-week axis, and the week-ahead
// headline computed against today's baseline (warmer trend → "Warmer …").
// Per-model data is supplied so the diagnostic deck renders.
@Preview(name = "Following-week page · weekly card", widthDp = 360)
@Composable
internal fun FollowingWeekPagePreview() {
    SevenDayFrame {
        SevenDayPage(
            days = SAMPLE_FOLLOWING_WEEK,
            state = TodayState(),
            // Full days-1-14 series (starts at today, like production) so the
            // page's window-slicing is actually exercised by the snapshot.
            weekPerModelHourly = SAMPLE_TWO_WEEK_PER_MODEL_HOURLY,
            scrollState = androidx.compose.foundation.rememberScrollState(),
            onChevronTap = {},
            titleRes = app.clothescast.R.string.today_title_following_week,
            weekAheadBaseline = SAMPLE_WEEK.first(),
            // Mirror page 3 — the second week's models thin out, so the deck
            // renders on partial coverage.
            allowPartialModelCoverage = true,
            outfitInsight = SAMPLE_WEEK_OUTFIT_INSIGHT,
        )
    }
}

// Exercises the [ScrubMomentFormat.DayPlusHour] readout path the 7-day
// page wires up, without standing up the whole SevenDayPage (which owns
// its own scrub controller internally). Pre-scrubs to Wed 14:00 against
// the SAMPLE_WEEK feels-like series so the readout in the subtitle row
// reads `"<value> at Wed 2pm"` — the day-of-week prefix is the bit
// that's new here and the reason the 7-day page's per-period cards now
// need a different formatter than the per-period pages.
@Preview(name = "Forecast card · 7-day day-prefix readout", widthDp = 360)
@Composable
internal fun ForecastCardWeekScrubbedPreview() {
    Frame {
        val flatHourly = SAMPLE_WEEK.flatMap { it.hourly }
        val startDate = SAMPLE_WEEK.first().date
        val wedAtTwo = java.time.LocalDateTime.of(startDate.plusDays(2), java.time.LocalTime.of(14, 0))
        val controller = androidx.compose.runtime.remember {
            ChartScrubController().apply { scrubTo(wedAtTwo) }
        }
        CompositionLocalProvider(
            LocalChartScrub provides controller,
            LocalScrubMomentFormat provides ScrubMomentFormat.DayPlusHour,
        ) {
            ForecastCard(
                hourly = flatHourly,
                temperatureUnit = TemperatureUnit.CELSIUS,
                startDate = startDate,
            )
        }
    }
}
