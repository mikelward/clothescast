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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.clothescast.R
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.ui.theme.ClothesCastTheme

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
                .padding(4.dp),
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
        Spacer(modifier = Modifier.height(4.dp))
        val iconSize = scaledIconSizeMock(size)
        Image(
            painter = painterResource(id = topIconResMock(outfit.top)),
            contentDescription = stringResource(topLabelResMock(outfit.top)),
            modifier = Modifier.size(iconSize),
        )
        Image(
            painter = painterResource(id = bottomIconResMock(outfit.bottom)),
            contentDescription = stringResource(bottomLabelResMock(outfit.bottom)),
            modifier = Modifier.size(iconSize),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(topLabelResMock(outfit.top)) +
                " · " +
                stringResource(bottomLabelResMock(outfit.bottom)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = scaledSubtitleSpMock(size),
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

// Scaling formulas — kept in lockstep with OutfitWidget.kt's scaledIconSize /
// scaledLabelSp / scaledSubtitleSp. Anchored so a 160dp-square cell reproduces
// the previous hard-coded values (icon 48dp, label 14sp, subtitle 11sp).
private fun scaledIconSizeMock(size: DpSize): Dp {
    val short = minOf(size.width.value, size.height.value)
    return (short * 0.30f).coerceIn(36f, 88f).dp
}

private fun scaledLabelSpMock(size: DpSize): TextUnit {
    val short = minOf(size.width.value, size.height.value)
    return (short * 0.0875f).coerceIn(13f, 18f).sp
}

private fun scaledSubtitleSpMock(size: DpSize): TextUnit {
    val short = minOf(size.width.value, size.height.value)
    return (short * 0.0688f).coerceIn(10f, 13f).sp
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
    OutfitSuggestion.Bottom.LONG_SKIRT -> R.drawable.ic_outfit_skirt
    OutfitSuggestion.Bottom.JEANS -> R.drawable.ic_outfit_jeans
    OutfitSuggestion.Bottom.LONG_PANTS -> R.drawable.ic_outfit_long_pants
}

private fun bottomLabelResMock(bottom: OutfitSuggestion.Bottom): Int = when (bottom) {
    OutfitSuggestion.Bottom.SHORTS -> R.string.today_outfit_bottom_shorts
    OutfitSuggestion.Bottom.LONG_SKIRT -> R.string.today_outfit_bottom_long_skirt
    OutfitSuggestion.Bottom.JEANS -> R.string.today_outfit_bottom_jeans
    OutfitSuggestion.Bottom.LONG_PANTS -> R.string.today_outfit_bottom_long_pants
}
