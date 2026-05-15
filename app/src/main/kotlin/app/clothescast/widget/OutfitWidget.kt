package app.clothescast.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.clothescast.ClothesCastApplication
import app.clothescast.MainActivity
import app.clothescast.R
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.ui.garment.bottomDrawable
import app.clothescast.ui.garment.outfitBottomDefaults
import app.clothescast.ui.garment.outfitTopDefaults
import app.clothescast.ui.garment.renderOutfitBitmap
import app.clothescast.ui.garment.topDrawable
import kotlinx.coroutines.flow.first

/**
 * A glanceable home-screen widget showing the current period's outfit — top icon,
 * bottom icon, and the period label ("Today" / "Tonight"). Tapping the widget
 * opens the app.
 *
 * The widget reads the same [app.clothescast.data.InsightCache] the Today screen
 * does, so it stays in lockstep with whatever the app last computed. Refreshes
 * are pushed by [app.clothescast.work.FetchAndNotifyWorker] after each cache
 * write via OutfitWidget().updateAll(context); there's no per-widget polling.
 *
 * Sizing is adaptive ([SizeMode.Exact] — minSdk 31 makes that safe): icon and
 * text sizes scale with the widget's shortest side so a stretched widget looks
 * deliberately bigger rather than centred-with-whitespace, and once the widget
 * is wide enough to fit two columns it splits into the current period plus the
 * next one ("Today + Tonight" on a morning insight, "Tonight + Tomorrow" on an
 * evening one) — same convention as the Today screen's OutfitPreviewRow.
 */
class OutfitWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as ClothesCastApplication
        // Read once on each provideGlance() pass — the worker calls updateAll()
        // after writing to the cache, which re-invokes this function. We read
        // THIS_PERIOD specifically (not "freshest of either slot") because
        // NEXT_PERIOD is a pre-render of the upcoming window — taking the
        // newer-generated-at would surface "Tonight" while the user is still
        // in the daytime window the morning worker just delivered.
        val insight = runCatching { app.insightCache.thisPeriod.first() }.getOrNull()
        // Per-garment colour overrides — empty when the user hasn't customised.
        // Pulled here (not inside the Composable) so the bitmap rasterizer can
        // run on this dispatcher rather than during composition.
        val prefs = runCatching { app.settingsRepository.preferences.first() }.getOrNull()
        val topColors = prefs?.outfitTopColors ?: emptyMap()
        val bottomColors = prefs?.outfitBottomColors ?: emptyMap()
        provideContent {
            GlanceTheme {
                OutfitWidgetContent(insight, topColors, bottomColors)
            }
        }
    }
}

// Threshold at which we split into two columns: each column needs roughly the
// width of a default-sized single-column widget (~120dp), so a 240dp+ widget is
// the minimum that doesn't cramp either side.
private val SIDE_BY_SIDE_MIN_WIDTH = 240.dp

// Glance 1.1.x's actionStartActivity<MainActivity>() crashed in the wild with
// "List adapter activity trampoline invoked without specifying target intent"
// — the launcher dispatches the trampoline activity with empty extras, so the
// wrapped target Intent is missing. Constructing the Intent ourselves with
// ACTION_MAIN + a unique data URI gives the framework a fully-specified
// component / action / data tuple to key the PendingIntent on, which makes
// the trampoline more resilient to launcher-side state stripping.
private fun launchAppIntent(context: Context): Intent =
    Intent(Intent.ACTION_MAIN)
        .setClass(context, MainActivity::class.java)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setData(Uri.parse("clothescast://widget/open"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

@Composable
private fun OutfitWidgetContent(
    insight: Insight?,
    topColors: Map<OutfitSuggestion.Top, Long>,
    bottomColors: Map<OutfitSuggestion.Bottom, Long>,
) {
    val size = LocalSize.current
    val context = LocalContext.current
    val outfit = insight?.outfit
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .clickable(actionStartActivity(launchAppIntent(context)))
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (insight == null || outfit == null) {
            EmptyContent(size)
        } else if (size.width >= SIDE_BY_SIDE_MIN_WIDTH && insight.nextOutfit != null) {
            SideBySideContent(insight, size, topColors, bottomColors)
        } else {
            SingleColumnContent(
                label = context.getString(periodLabelRes(insight.period)),
                outfit = outfit,
                size = size,
                topColors = topColors,
                bottomColors = bottomColors,
            )
        }
    }
}

@Composable
private fun SingleColumnContent(
    label: String,
    outfit: OutfitSuggestion,
    size: DpSize,
    topColors: Map<OutfitSuggestion.Top, Long>,
    bottomColors: Map<OutfitSuggestion.Bottom, Long>,
) {
    val context = LocalContext.current
    val iconSize = scaledIconSize(size)
    val iconPx = with(Density(context)) { iconSize.toPx() }.toInt().coerceAtLeast(1)
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = scaledLabelStyle(size))
        Spacer(modifier = GlanceModifier.height(4.dp))
        // Top-over-bottom vertical stack matches the Today screen's
        // OutfitPreviewCard so the home-screen glance reads the same way as
        // the in-app card the user already knows.
        Image(
            provider = ImageProvider(
                renderOutfitBitmap(
                    context = context,
                    drawableRes = topDrawable(outfit.top),
                    defaults = outfitTopDefaults.getValue(outfit.top),
                    customFillArgb = topColors[outfit.top],
                    sizePx = iconPx,
                ),
            ),
            contentDescription = context.getString(topLabelRes(outfit.top)),
            modifier = GlanceModifier.size(iconSize),
        )
        Image(
            provider = ImageProvider(
                renderOutfitBitmap(
                    context = context,
                    drawableRes = bottomDrawable(outfit.bottom),
                    defaults = outfitBottomDefaults.getValue(outfit.bottom),
                    customFillArgb = bottomColors[outfit.bottom],
                    sizePx = iconPx,
                ),
            ),
            contentDescription = context.getString(bottomLabelRes(outfit.bottom)),
            modifier = GlanceModifier.size(iconSize),
        )
        Spacer(modifier = GlanceModifier.height(2.dp))
        Text(
            text = context.getString(topLabelRes(outfit.top)) +
                " · " +
                context.getString(bottomLabelRes(outfit.bottom)),
            style = scaledSubtitleStyle(size),
        )
    }
}

@Composable
private fun SideBySideContent(
    insight: Insight,
    size: DpSize,
    topColors: Map<OutfitSuggestion.Top, Long>,
    bottomColors: Map<OutfitSuggestion.Bottom, Long>,
) {
    val context = LocalContext.current
    val primaryOutfit = insight.outfit ?: return
    val nextOutfit = insight.nextOutfit ?: return
    val (primaryLabelRes, nextLabelRes) = sideBySideLabelRes(insight.period)
    // Each column gets roughly half the available width; drive the per-column
    // scale off that half-width so icons / text don't try to grow into space
    // they don't actually have.
    val columnSize = DpSize(size.width / 2, size.height)
    Row(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            SingleColumnContent(
                label = context.getString(primaryLabelRes),
                outfit = primaryOutfit,
                size = columnSize,
                topColors = topColors,
                bottomColors = bottomColors,
            )
        }
        Box(
            modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            SingleColumnContent(
                label = context.getString(nextLabelRes),
                outfit = nextOutfit,
                size = columnSize,
                topColors = topColors,
                bottomColors = bottomColors,
            )
        }
    }
}

@Composable
private fun EmptyContent(size: DpSize) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = context.getString(R.string.widget_empty_title),
            style = scaledLabelStyle(size),
        )
        Spacer(modifier = GlanceModifier.height(2.dp))
        Text(
            text = context.getString(R.string.widget_empty_subtitle),
            style = scaledSubtitleStyle(size),
        )
    }
}

// Scaling factors are anchored so a 160dp-square cell reproduces the previous
// hard-coded values (icon 48dp, label 14sp, subtitle 11sp); larger cells grow
// up to the caps below. Sizing is driven by the shortest dimension because
// the column is bounded vertically by two stacked icons + label + subtitle.
private fun scaledIconSize(size: DpSize): Dp {
    val short = minOf(size.width.value, size.height.value)
    return (short * 0.30f).coerceIn(36f, 88f).dp
}

@Composable
private fun scaledLabelStyle(size: DpSize): TextStyle = TextStyle(
    color = GlanceTheme.colors.onSurface,
    fontWeight = FontWeight.Medium,
    fontSize = scaledLabelSp(size),
)

@Composable
private fun scaledSubtitleStyle(size: DpSize): TextStyle = TextStyle(
    color = GlanceTheme.colors.onSurfaceVariant,
    fontSize = scaledSubtitleSp(size),
)

private fun scaledLabelSp(size: DpSize): TextUnit {
    val short = minOf(size.width.value, size.height.value)
    return (short * 0.0875f).coerceIn(13f, 18f).sp
}

private fun scaledSubtitleSp(size: DpSize): TextUnit {
    val short = minOf(size.width.value, size.height.value)
    return (short * 0.0688f).coerceIn(10f, 13f).sp
}

private fun periodLabelRes(period: ForecastPeriod): Int = when (period) {
    ForecastPeriod.TODAY -> R.string.today_outfit_label_today
    ForecastPeriod.TONIGHT -> R.string.today_outfit_label_tonight
}

// Mirrors `outfitLabels` in TodayScreen.OutfitPreviewRow: the "next" period
// after TODAY is TONIGHT, after TONIGHT it's TOMORROW.
private fun sideBySideLabelRes(period: ForecastPeriod): Pair<Int, Int> = when (period) {
    ForecastPeriod.TODAY ->
        R.string.today_outfit_label_today to R.string.today_outfit_label_tonight
    ForecastPeriod.TONIGHT ->
        R.string.today_outfit_label_tonight to R.string.today_outfit_label_tomorrow
}

private fun topLabelRes(top: OutfitSuggestion.Top): Int = when (top) {
    OutfitSuggestion.Top.TSHIRT -> R.string.today_outfit_top_tshirt
    OutfitSuggestion.Top.POLO -> R.string.today_outfit_top_polo
    OutfitSuggestion.Top.SWEATER -> R.string.today_outfit_top_sweater
    OutfitSuggestion.Top.THIN_JACKET -> R.string.today_outfit_top_thin_jacket
    OutfitSuggestion.Top.THICK_JACKET -> R.string.today_outfit_top_thick_jacket
    OutfitSuggestion.Top.THICK_COAT -> R.string.today_outfit_top_thick_coat
    OutfitSuggestion.Top.PUFFER_JACKET -> R.string.today_outfit_top_puffer_jacket
}

private fun bottomLabelRes(bottom: OutfitSuggestion.Bottom): Int = when (bottom) {
    OutfitSuggestion.Bottom.SHORTS -> R.string.today_outfit_bottom_shorts
    OutfitSuggestion.Bottom.LONG_SKIRT -> R.string.today_outfit_bottom_long_skirt
    OutfitSuggestion.Bottom.JEANS -> R.string.today_outfit_bottom_jeans
    OutfitSuggestion.Bottom.LONG_PANTS -> R.string.today_outfit_bottom_long_pants
}
