package app.clothescast.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.clothescast.R
import app.clothescast.core.domain.model.BandClause
import app.clothescast.core.domain.model.ClothesClause
import app.clothescast.core.domain.model.DeltaClause
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.PrecipClause
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.TemperatureBand
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.WeatherCondition
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The Today screen's pre-first-cast state, rendered as a frosted preview of the
 * *real* layout rather than a bare line of text. The actual outfit row, insight
 * card, and forecast charts are drawn with representative sample data, then
 * blurred and dimmed behind a scrim, with a large "?" and a call to action over
 * the top. The point is to show a brand-new user the shape of what they'll get
 * — "an outfit, a write-up, and charts will live here" — instead of an empty
 * screen, while the "?" + scrim make clear it isn't real data yet.
 *
 * The same frame serves both pre-cast states; only the headline and CTA differ:
 *  - no resolvable location ([locationActionRequired]) → "Location required" +
 *    "Set up your location", routing to [onSetUpLocation]. Nothing can ever
 *    generate until this is fixed, so it names the blocker.
 *  - location set, first cast still pending → "No ClothesCast yet" + a
 *    "Fetch now" button ([onRefresh], disabled while [isWorking]).
 */
@Composable
internal fun TodayEmptySkeleton(
    onRefresh: () -> Unit,
    isWorking: Boolean,
    locationActionRequired: Boolean,
    onSetUpLocation: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        // Background — the real components with sample data, frosted. blur() is a
        // no-op below API 31; the scrim below carries the "fog" on those devices
        // (and unifies the look where blur does apply). clearAndSetSemantics
        // hides this decorative stack from accessibility — it's a teaser, not
        // content to read or act on.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .blur(14.dp)
                .alpha(0.85f)
                .clearAndSetSemantics {},
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutfitPreviewRow(SKELETON_INSIGHT)
            InsightCard(SKELETON_INSIGHT, Region.SYSTEM)
            ForecastCard(hourly = SKELETON_HOURLY, temperatureUnit = TemperatureUnit.CELSIUS)
            PrecipitationCard(hourly = SKELETON_HOURLY)
        }
        // Scrim — a band of heavier fog through the middle (behind the headline +
        // CTA, so they stay legible) that thins out at the top and bottom, so the
        // frosted outfit row peeks through up top and the charts peek through
        // below as a teaser. Also absorbs taps via a no-indication clickable so
        // nothing in the blurred background is reachable; the CTA button below is
        // drawn on top of this scrim, so it still receives taps.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.68f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.15f),
                        ),
                    ),
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .clearAndSetSemantics {},
        )
        // Foreground — the "?" hero and the call to action, anchored near the top
        // so a brand-new user sees them without scrolling past the (taller)
        // frosted stack behind.
        Column(
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 24.dp)
                .padding(top = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "?",
                fontSize = 96.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(
                    if (locationActionRequired) {
                        R.string.today_empty_location_title
                    } else {
                        R.string.today_empty_title
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(
                    if (locationActionRequired) {
                        R.string.today_empty_location_subtitle
                    } else {
                        R.string.today_empty_subtitle
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            if (locationActionRequired) {
                Button(onClick = onSetUpLocation) {
                    Text(stringResource(R.string.today_location_required_action))
                }
            } else {
                Button(onClick = onRefresh, enabled = !isWorking) {
                    Text(stringResource(R.string.today_fetch_now))
                }
            }
        }
    }
}

// Representative weather behind the frost: a gentle overnight-low → afternoon-
// high curve with a wet afternoon, so the temperature chart shows a curve and
// the rain chart shows bars. Placeholder only — no real location or PII — so it
// ships safely in production and renders deterministically in snapshots.
private val SKELETON_HOURLY: List<HourlyForecast> = run {
    val tempsC = listOf(
        9.0, 8.5, 8.0, 7.5, 7.5, 8.0,        // 00–05
        9.0, 10.5, 12.0, 13.5, 15.0, 16.0,   // 06–11
        17.0, 17.5, 18.0, 18.0, 17.5, 16.5,  // 12–17
        15.0, 13.5, 12.5, 11.5, 10.5, 9.5,   // 18–23
    )
    val rainPctByHour = mapOf(13 to 30.0, 14 to 55.0, 15 to 70.0, 16 to 45.0, 17 to 25.0)
    tempsC.mapIndexed { hour, t ->
        HourlyForecast(
            time = LocalTime.of(hour, 0),
            temperatureC = t,
            feelsLikeC = t - if (t < 14.0) 1.5 else 0.0,
            precipitationProbabilityPct = rainPctByHour[hour] ?: 0.0,
            condition = if ((rainPctByHour[hour] ?: 0.0) >= 50.0) {
                WeatherCondition.RAIN
            } else {
                WeatherCondition.PARTLY_CLOUDY
            },
        )
    }
}

private val SKELETON_INSIGHT: Insight = Insight(
    summary = InsightSummary(
        period = ForecastPeriod.TODAY,
        band = BandClause(TemperatureBand.COOL, TemperatureBand.MILD),
        delta = DeltaClause(4, DeltaClause.Direction.WARMER),
        clothes = ClothesClause(listOf("sweater")),
        precip = PrecipClause(WeatherCondition.RAIN, LocalTime.of(15, 0)),
    ),
    recommendedItems = listOf("sweater"),
    generatedAt = Instant.parse("2026-04-26T07:30:00Z"),
    forDate = LocalDate.of(2026, 4, 26),
    period = ForecastPeriod.TODAY,
    outfit = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS),
    nextOutfit = OutfitSuggestion(OutfitSuggestion.Top.THICK_JACKET, OutfitSuggestion.Bottom.LONG_PANTS),
    hourly = SKELETON_HOURLY,
)
