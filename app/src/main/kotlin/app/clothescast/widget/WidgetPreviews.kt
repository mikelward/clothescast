package app.clothescast.widget

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.clothescast.R
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.TimeFormat
import app.clothescast.core.domain.model.WeatherCondition
import app.clothescast.ui.theme.ClothesCastTheme
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

//
// Compose stand-ins for the Glance OutfitWidget, used purely for snapshotting.
// Glance composables can't be rendered by Roborazzi (they emit RemoteViews, not
// Compose UI), so the widget layout is mirrored here in vanilla Compose at the
// same dimensions and styling. Mirrors the NotificationIconPreviews approach.
//
// Visual changes to OutfitWidget.kt should be reflected here so the snapshots
// stay representative — the two files are coupled by intent. The scaling
// formulas below MUST stay in lockstep with the ones in OutfitWidget.kt.
//
// Sizes covered:
//   * compact (110x110)   — default 2x2 launcher cell
//   * standard (160x160)  — what most users probably see; matches the size the
//                           widget snapshots have always been pinned at
//   * large   (220x220)   — stretched 3x3, scaled-up icons + text
//   * wide    (300x150)   — 4x2-ish, side-by-side current + next period
//

private val SIDE_BY_SIDE_MIN_WIDTH = 240.dp

@Composable
private fun WidgetFrame(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    ClothesCastTheme(darkTheme = darkTheme, dynamicColor = false) {
        // Slight surface inset so the rounded widget corners are visible against
        // a launcher-like backdrop. The actual launcher applies its own
        // wallpaper, so the colour here is just for visual contrast.
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.padding(16.dp)) { content() }
        }
    }
}

@Composable
private fun WidgetSurface(width: Dp, height: Dp, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.size(width = width, height = height),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(2.dp),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

@Composable
internal fun OutfitWidgetMockFilled(
    period: ForecastPeriod,
    outfit: OutfitSuggestion,
    width: Dp = 160.dp,
    height: Dp = 160.dp,
) {
    WidgetSurface(width, height) {
        SingleColumnMock(
            label = stringResource(periodLabelResMock(period)),
            outfit = outfit,
            size = DpSize(width, height),
        )
    }
}

@Composable
internal fun OutfitWidgetMockSideBySide(
    primaryPeriod: ForecastPeriod,
    primary: OutfitSuggestion,
    next: OutfitSuggestion,
    width: Dp = 300.dp,
    height: Dp = 150.dp,
) {
    val (primaryLabel, nextLabel) = sideBySideLabelResMock(primaryPeriod)
    val columnSize = DpSize(width / 2, height)
    WidgetSurface(width, height) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                SingleColumnMock(
                    label = stringResource(primaryLabel),
                    outfit = primary,
                    size = columnSize,
                )
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                SingleColumnMock(
                    label = stringResource(nextLabel),
                    outfit = next,
                    size = columnSize,
                )
            }
        }
    }
}

@Composable
private fun SingleColumnMock(label: String, outfit: OutfitSuggestion, size: DpSize) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = scaledLabelSpMock(size),
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(2.dp))
        val iconSize = scaledIconSizeMock(size)
        Box(contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(id = topIconResMock(outfit.top)),
                contentDescription = stringResource(topLabelResMock(outfit.top)),
                modifier = Modifier.size(iconSize),
            )
            outfit.hands?.let { hands ->
                Image(
                    painter = painterResource(id = handsIconResMock(hands)),
                    contentDescription = stringResource(handsLabelResMock(hands)),
                    modifier = Modifier.size(iconSize),
                )
            }
        }
        Image(
            painter = painterResource(id = bottomIconResMock(outfit.bottom)),
            contentDescription = stringResource(bottomLabelResMock(outfit.bottom)),
            modifier = Modifier.size(iconSize),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = buildString {
                append(stringResource(topLabelResMock(outfit.top)))
                append(" · ")
                append(stringResource(bottomLabelResMock(outfit.bottom)))
                outfit.hands?.let {
                    append(" · ")
                    append(stringResource(handsLabelResMock(it)))
                }
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = scaledSubtitleSpMock(size),
            // Mirror the real widget's one-line ellipsis (see OutfitWidget) so
            // the snapshot matches what RemoteViews renders.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun OutfitWidgetMockEmpty(width: Dp = 160.dp, height: Dp = 160.dp) {
    val size = DpSize(width, height)
    WidgetSurface(width, height) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Text(
                text = stringResource(R.string.widget_empty_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = scaledLabelSpMock(size),
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.widget_empty_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = scaledSubtitleSpMock(size),
            )
        }
    }
}

@Preview(name = "Widget · today · t-shirt + shorts", widthDp = 192, heightDp = 192)
@Composable
internal fun WidgetTodayTShirtShortsPreview() {
    WidgetFrame {
        OutfitWidgetMockFilled(
            period = ForecastPeriod.TODAY,
            outfit = OutfitSuggestion(OutfitSuggestion.Top.TSHIRT, OutfitSuggestion.Bottom.SHORTS),
        )
    }
}

@Preview(name = "Widget · tonight · sweater + long pants", widthDp = 192, heightDp = 192)
@Composable
internal fun WidgetTonightSweaterPantsPreview() {
    WidgetFrame {
        OutfitWidgetMockFilled(
            period = ForecastPeriod.TONIGHT,
            outfit = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS),
        )
    }
}

@Preview(name = "Widget · today · thick jacket + long pants", widthDp = 192, heightDp = 192)
@Composable
internal fun WidgetTodayJacketPantsPreview() {
    WidgetFrame {
        OutfitWidgetMockFilled(
            period = ForecastPeriod.TODAY,
            outfit = OutfitSuggestion(OutfitSuggestion.Top.THICK_JACKET, OutfitSuggestion.Bottom.LONG_PANTS),
        )
    }
}

@Preview(name = "Widget · tonight · coat + pants + gloves", widthDp = 192, heightDp = 192)
@Composable
internal fun WidgetTonightCoatPantsGlovesPreview() {
    WidgetFrame {
        OutfitWidgetMockFilled(
            period = ForecastPeriod.TONIGHT,
            outfit = OutfitSuggestion(
                OutfitSuggestion.Top.THICK_COAT,
                OutfitSuggestion.Bottom.LONG_PANTS,
                OutfitSuggestion.Hands.GLOVES,
            ),
        )
    }
}

@Preview(name = "Widget · tonight (dark)", widthDp = 192, heightDp = 192)
@Composable
internal fun WidgetTonightDarkPreview() {
    WidgetFrame(darkTheme = true) {
        OutfitWidgetMockFilled(
            period = ForecastPeriod.TONIGHT,
            outfit = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS),
        )
    }
}

@Preview(name = "Widget · empty", widthDp = 192, heightDp = 192)
@Composable
internal fun WidgetEmptyPreview() {
    WidgetFrame { OutfitWidgetMockEmpty() }
}

@Preview(name = "Widget · today · compact (110dp)", widthDp = 142, heightDp = 142)
@Composable
internal fun WidgetTodayCompactPreview() {
    WidgetFrame {
        OutfitWidgetMockFilled(
            period = ForecastPeriod.TODAY,
            outfit = OutfitSuggestion(OutfitSuggestion.Top.TSHIRT, OutfitSuggestion.Bottom.SHORTS),
            width = 110.dp,
            height = 110.dp,
        )
    }
}

@Preview(name = "Widget · today · large (220dp)", widthDp = 252, heightDp = 252)
@Composable
internal fun WidgetTodayLargePreview() {
    WidgetFrame {
        OutfitWidgetMockFilled(
            period = ForecastPeriod.TODAY,
            outfit = OutfitSuggestion(OutfitSuggestion.Top.TSHIRT, OutfitSuggestion.Bottom.SHORTS),
            width = 220.dp,
            height = 220.dp,
        )
    }
}

@Preview(name = "Widget · today · extra large (320dp)", widthDp = 352, heightDp = 352)
@Composable
internal fun WidgetTodayExtraLargePreview() {
    WidgetFrame {
        OutfitWidgetMockFilled(
            period = ForecastPeriod.TODAY,
            outfit = OutfitSuggestion(OutfitSuggestion.Top.TSHIRT, OutfitSuggestion.Bottom.SHORTS),
            width = 320.dp,
            height = 320.dp,
        )
    }
}

@Preview(name = "Widget · today + tonight · side by side", widthDp = 332, heightDp = 182)
@Composable
internal fun WidgetTodayTonightWidePreview() {
    WidgetFrame {
        OutfitWidgetMockSideBySide(
            primaryPeriod = ForecastPeriod.TODAY,
            primary = OutfitSuggestion(OutfitSuggestion.Top.TSHIRT, OutfitSuggestion.Bottom.SHORTS),
            next = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS),
        )
    }
}

@Preview(name = "Widget · tonight + tomorrow · side by side", widthDp = 332, heightDp = 182)
@Composable
internal fun WidgetTonightTomorrowWidePreview() {
    WidgetFrame {
        OutfitWidgetMockSideBySide(
            primaryPeriod = ForecastPeriod.TONIGHT,
            primary = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS),
            next = OutfitSuggestion(OutfitSuggestion.Top.THIN_JACKET, OutfitSuggestion.Bottom.JEANS),
        )
    }
}

// Tall portrait covers the launchers that scale width and height together.
// Stretching the widget makes the cell larger overall but keeps it portrait,
// at which point the OutfitWidget falls back to single column so one outfit
// can fill the canvas instead of two narrow ones floating in whitespace.
@Preview(name = "Widget · tonight · tall portrait", widthDp = 392, heightDp = 512)
@Composable
internal fun WidgetTonightTallPortraitPreview() {
    WidgetFrame {
        OutfitWidgetMockFilled(
            period = ForecastPeriod.TONIGHT,
            outfit = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.JEANS),
            width = 360.dp,
            height = 480.dp,
        )
    }
}

// Scaling formulas — kept in lockstep with OutfitWidget.kt's scaledIconSize /
// scaledLabelSp / scaledSubtitleSp. See the comment block over scaledIconSize
// for the two-budget reasoning; this mirror must stay byte-for-byte equivalent.
private fun scaledIconSizeMock(size: DpSize): Dp {
    val short = minOf(size.width.value, size.height.value)
    val labelSp = (short * 0.0875f).coerceAtLeast(13f)
    val subtitleSp = (short * 0.0688f).coerceAtLeast(10f)
    val reservedVertical = (labelSp + subtitleSp) * 1.5f + 16f
    val verticalBudget = (size.height.value - reservedVertical).coerceAtLeast(0f) / 2f
    val horizontalBudget = size.width.value * 0.9f
    return minOf(verticalBudget, horizontalBudget).coerceAtLeast(36f).dp
}

private fun scaledLabelSpMock(size: DpSize): TextUnit {
    val short = minOf(size.width.value, size.height.value)
    return (short * 0.0875f).coerceAtLeast(13f).sp
}

private fun scaledSubtitleSpMock(size: DpSize): TextUnit {
    val short = minOf(size.width.value, size.height.value)
    return (short * 0.0688f).coerceAtLeast(10f).sp
}

private fun periodLabelResMock(period: ForecastPeriod): Int = when (period) {
    ForecastPeriod.TODAY -> R.string.today_outfit_label_today
    ForecastPeriod.TONIGHT -> R.string.today_outfit_label_tonight
}

private fun sideBySideLabelResMock(period: ForecastPeriod): Pair<Int, Int> = when (period) {
    ForecastPeriod.TODAY ->
        R.string.today_outfit_label_today to R.string.today_outfit_label_tonight
    ForecastPeriod.TONIGHT ->
        R.string.today_outfit_label_tonight to R.string.today_outfit_label_tomorrow
}

private fun topIconResMock(top: OutfitSuggestion.Top): Int = when (top) {
    OutfitSuggestion.Top.TSHIRT -> R.drawable.ic_outfit_tshirt
    OutfitSuggestion.Top.POLO -> R.drawable.ic_outfit_polo
    OutfitSuggestion.Top.SWEATER -> R.drawable.ic_outfit_sweater
    OutfitSuggestion.Top.THIN_JACKET -> R.drawable.ic_outfit_thin_jacket
    OutfitSuggestion.Top.THICK_JACKET -> R.drawable.ic_outfit_thick_jacket
    OutfitSuggestion.Top.THICK_COAT -> R.drawable.ic_outfit_thick_coat
    OutfitSuggestion.Top.PUFFER_JACKET -> R.drawable.ic_outfit_puffer_jacket
}

private fun topLabelResMock(top: OutfitSuggestion.Top): Int = when (top) {
    OutfitSuggestion.Top.TSHIRT -> R.string.today_outfit_top_tshirt
    OutfitSuggestion.Top.POLO -> R.string.today_outfit_top_polo
    OutfitSuggestion.Top.SWEATER -> R.string.today_outfit_top_sweater
    OutfitSuggestion.Top.THIN_JACKET -> R.string.today_outfit_top_thin_jacket
    OutfitSuggestion.Top.THICK_JACKET -> R.string.today_outfit_top_thick_jacket
    OutfitSuggestion.Top.THICK_COAT -> R.string.today_outfit_top_thick_coat
    OutfitSuggestion.Top.PUFFER_JACKET -> R.string.today_outfit_top_puffer_jacket
}

private fun bottomIconResMock(bottom: OutfitSuggestion.Bottom): Int = when (bottom) {
    OutfitSuggestion.Bottom.SHORTS -> R.drawable.ic_outfit_shorts
    OutfitSuggestion.Bottom.SHORT_SKIRT -> R.drawable.ic_outfit_short_skirt
    OutfitSuggestion.Bottom.LONG_SKIRT -> R.drawable.ic_outfit_skirt
    OutfitSuggestion.Bottom.JEANS -> R.drawable.ic_outfit_jeans
    OutfitSuggestion.Bottom.LONG_PANTS -> R.drawable.ic_outfit_long_pants
}

private fun bottomLabelResMock(bottom: OutfitSuggestion.Bottom): Int = when (bottom) {
    OutfitSuggestion.Bottom.SHORTS -> R.string.today_outfit_bottom_shorts
    OutfitSuggestion.Bottom.SHORT_SKIRT -> R.string.today_outfit_bottom_short_skirt
    OutfitSuggestion.Bottom.LONG_SKIRT -> R.string.today_outfit_bottom_long_skirt
    OutfitSuggestion.Bottom.JEANS -> R.string.today_outfit_bottom_jeans
    OutfitSuggestion.Bottom.LONG_PANTS -> R.string.today_outfit_bottom_long_pants
}

private fun handsIconResMock(hands: OutfitSuggestion.Hands): Int = when (hands) {
    OutfitSuggestion.Hands.GLOVES -> R.drawable.ic_outfit_gloves
}

private fun handsLabelResMock(hands: OutfitSuggestion.Hands): Int = when (hands) {
    OutfitSuggestion.Hands.GLOVES -> R.string.garment_gloves
}

//
// Feels-like chart widget previews. Unlike the OutfitWidget mocks above, these
// render the *real* [WidgetForecastChart] (which reuses the in-app ForecastCard /
// ForecastChart) — there's nothing to mirror, so there's nothing to drift. The
// snapshots are the brand-accurate reference and double as proof the chart
// composes with data under the PreviewSnapshots harness.
//

// One synthetic day of hourly feels-like (°C): cool overnight, warm mid-afternoon.
private val SAMPLE_FEELS_DAY: List<HourlyForecast> = run {
    val feels = listOf(
        8.0, 8.0, 7.0, 7.0, 8.0, 10.0, // 00–05
        11.0, 12.0, 14.0, 16.0, 18.0, 20.0, // 06–11
        21.0, 22.0, 22.0, 21.0, 19.0, 17.0, // 12–17
        15.0, 13.0, 12.0, 10.0, 9.0, 8.0, // 18–23
    )
    feels.mapIndexed { hour, f ->
        HourlyForecast(
            time = LocalTime.of(hour, 0),
            temperatureC = f + 1.0,
            feelsLikeC = f,
            precipitationProbabilityPct = 0.0,
            condition = WeatherCondition.PARTLY_CLOUDY,
        )
    }
}

// Seven days, each a copy of the day curve nudged a little so the week line isn't
// seven identical humps. Daily aggregates are derived from the hourly run.
private val SAMPLE_FEELS_WEEK: List<DailyForecast> = (0..6).map { d ->
    val shift = listOf(0.0, 1.5, 3.0, 1.0, -1.0, -2.0, 0.5)[d]
    val hourly = SAMPLE_FEELS_DAY.map {
        it.copy(feelsLikeC = it.feelsLikeC + shift, temperatureC = it.temperatureC + shift)
    }
    val feels = hourly.map { it.feelsLikeC }
    val air = hourly.map { it.temperatureC }
    DailyForecast(
        date = LocalDate.now().plusDays(d.toLong()),
        temperatureMinC = air.min(),
        temperatureMaxC = air.max(),
        feelsLikeMinC = feels.min(),
        feelsLikeMaxC = feels.max(),
        precipitationProbabilityMaxPct = 0.0,
        precipitationMmTotal = 0.0,
        condition = WeatherCondition.PARTLY_CLOUDY,
        hourly = hourly,
    )
}

private val SAMPLE_NOW: LocalDateTime = LocalDateTime.of(LocalDate.now(), LocalTime.of(10, 30))

@Preview(name = "Feels-like widget · today", widthDp = 360)
@Composable
internal fun FeelsLikeWidgetTodayPreview() {
    WidgetFrame {
        WidgetForecastChart(
            hourly = SAMPLE_FEELS_DAY.subList(7, 19), // 07–18 daytime slice
            days = null,
            temperatureUnit = TemperatureUnit.CELSIUS,
            timeFormat = TimeFormat.TWELVE_HOUR,
            startDate = LocalDate.now(),
            now = SAMPLE_NOW,
        )
    }
}

@Preview(name = "Feels-like widget · today (dark)", widthDp = 360)
@Composable
internal fun FeelsLikeWidgetTodayDarkPreview() {
    WidgetFrame(darkTheme = true) {
        WidgetForecastChart(
            hourly = SAMPLE_FEELS_DAY.subList(7, 19),
            days = null,
            temperatureUnit = TemperatureUnit.CELSIUS,
            timeFormat = TimeFormat.TWELVE_HOUR,
            startDate = LocalDate.now(),
            now = SAMPLE_NOW,
        )
    }
}

@Preview(name = "Feels-like widget · 7 days", widthDp = 360)
@Composable
internal fun FeelsLikeWidgetWeekPreview() {
    WidgetFrame {
        WidgetForecastChart(
            hourly = SAMPLE_FEELS_WEEK.flatMap { it.hourly },
            days = SAMPLE_FEELS_WEEK,
            temperatureUnit = TemperatureUnit.CELSIUS,
            timeFormat = TimeFormat.TWELVE_HOUR,
            startDate = SAMPLE_FEELS_WEEK.first().date,
            now = SAMPLE_NOW,
        )
    }
}

// The runtime widget rasterises into a bitmap sized to the cell and asks the
// chart to fill it (fillHeight = true), so on a wide placement the chart spreads
// out rather than sitting at a fixed height. This preview pins a wide ~3:1 cell
// at a realistic widget height to lock in that look; the others above keep the
// default fixed-height card. Captured at a wider viewport (see PreviewSnapshots).
@Preview(name = "Feels-like widget · wide (3:1)", widthDp = 572, heightDp = 212)
@Composable
internal fun FeelsLikeWidgetWidePreview() {
    WidgetFrame {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.size(width = 540.dp, height = 180.dp),
        ) {
            WidgetForecastChart(
                hourly = SAMPLE_FEELS_DAY.subList(7, 19),
                days = null,
                temperatureUnit = TemperatureUnit.CELSIUS,
                timeFormat = TimeFormat.TWELVE_HOUR,
                startDate = LocalDate.now(),
                now = SAMPLE_NOW,
                fillHeight = true,
            )
        }
    }
}

@Preview(name = "Feels-like widget · no current time", widthDp = 360)
@Composable
internal fun FeelsLikeWidgetNoCurrentTimePreview() {
    WidgetFrame {
        WidgetForecastChart(
            hourly = SAMPLE_FEELS_DAY.subList(7, 19),
            days = null,
            temperatureUnit = TemperatureUnit.CELSIUS,
            timeFormat = TimeFormat.TWELVE_HOUR,
            startDate = LocalDate.now(),
            now = null,
        )
    }
}
