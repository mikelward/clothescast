package app.clothescast.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.clothescast.ClothesCastApplication
import app.clothescast.R
import app.clothescast.core.domain.model.windSpeedUnit
import app.clothescast.insight.InsightFormatter
import app.clothescast.ui.garment.ConditionsCell
import app.clothescast.ui.garment.ConditionsGlyph
import app.clothescast.ui.garment.OutfitCardInfoLines
import app.clothescast.ui.garment.STRIP_SURFACE_DARK_ARGB
import app.clothescast.ui.garment.STRIP_SURFACE_LIGHT_ARGB
import app.clothescast.ui.garment.outfitCardInfoLines
import app.clothescast.ui.garment.renderConditionsStripBitmap
import app.clothescast.ui.garment.uvScaleColorArgb
import app.clothescast.ui.garment.windScaleColorArgb
import kotlinx.coroutines.flow.first

/**
 * A glanceable home-screen widget showing the day's conditions as a horizontal
 * strip: a feels-like thermometer and chance-of-rain droplet, plus wind and UV
 * cells when those are high enough to be worth acting on. It's the same
 * temperature / rain visual the outfit card (Cast / Home Assistant) draws at the
 * bottom, lifted onto the home screen and extended.
 *
 * Like [OutfitWidget] it reads the shared [app.clothescast.data.InsightCache]
 * and rasterizes pure-Canvas glyphs to a bitmap (via [renderConditionsStripBitmap])
 * shown through [ImageProvider]; refreshes are pushed by
 * [updateAllClothesCastWidgets] after each cache write, no per-widget polling.
 *
 * Wind tints by the Beaufort scale, UV by the WHO scale; both appear only above
 * their "notable" threshold. The strip renderer takes a list of discrete cells,
 * so further indicators (e.g. a current-temp marker) are additive.
 */
class ConditionsWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as ClothesCastApplication
        // Mirror OutfitWidget: read snapshot + prefs once, derive against the
        // current prefs so a unit / theme change reflects without the cache
        // moving. THIS_PERIOD specifically — NEXT_PERIOD is a pre-render of the
        // upcoming window and would surface tomorrow's range mid-afternoon.
        val prefs = runCatching { app.settingsRepository.preferences.first() }.getOrNull()
        val snapshot = runCatching { app.insightCache.thisPeriod.first() }.getOrNull()
        val insight = if (snapshot != null && prefs != null) {
            runCatching { app.deriveInsight(snapshot, prefs).insight }.getOrNull()
        } else {
            null
        }
        // Compute the strip's labels / fill fractions here (off the composition)
        // so the temperature + peak-rain math runs on this dispatcher, reusing
        // the exact helper the outfit card uses.
        val info = if (insight != null && prefs != null) {
            runCatching {
                val formatter = InsightFormatter.forRegion(context, prefs.region)
                outfitCardInfoLines(
                    context = context,
                    formatter = formatter,
                    hourly = insight.hourly,
                    temperatureUnit = prefs.temperatureUnit,
                    windSpeedUnit = prefs.distanceUnit.windSpeedUnit(),
                )
            }.getOrNull()
        } else {
            null
        }
        val darkTheme = prefs?.let { resolveDarkTheme(context, it.themeMode) } ?: false
        provideContent {
            GlanceTheme {
                ConditionsWidgetContent(info, darkTheme)
            }
        }
    }
}

@Composable
private fun ConditionsWidgetContent(info: OutfitCardInfoLines?, darkTheme: Boolean) {
    val context = LocalContext.current
    val size = LocalSize.current
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            // Background tracks the same darkTheme as the strip's glyph/text
            // colours, not GlanceTheme.colors.widgetBackground — the latter
            // follows the device night mode, so a user who forces the app to
            // the opposite theme would otherwise get e.g. light-on-light text.
            .background(if (darkTheme) STRIP_DARK_SURFACE else STRIP_LIGHT_SURFACE)
            .cornerRadius(16.dp)
            .clickable(actionStartActivity(launchAppIntent(context)))
            .padding(STRIP_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        // A blank tempLine means no hourly forecast cached yet — show the
        // shared empty-state copy rather than an empty strip.
        if (info == null || info.tempLine.isBlank()) {
            Text(
                text = context.getString(R.string.feels_like_widget_empty_title),
                style = TextStyle(
                    color = ColorProvider(if (darkTheme) STRIP_DARK_TEXT else STRIP_LIGHT_TEXT),
                    fontWeight = FontWeight.Medium,
                ),
            )
        } else {
            // Size the bitmap to the padded drawable area (not the full cell)
            // so ContentScale.Fit doesn't shrink it to fit the larger,
            // wrong-aspect cell and leave the labels needlessly small.
            val density = Density(context)
            val contentWidth = (size.width - STRIP_PADDING * 2).coerceAtLeast(1.dp)
            val contentHeight = (size.height - STRIP_PADDING * 2).coerceAtLeast(1.dp)
            val widthPx = with(density) { contentWidth.toPx() }.toInt().coerceAtLeast(1)
            val heightPx = with(density) { contentHeight.toPx() }.toInt().coerceAtLeast(1)
            Image(
                provider = ImageProvider(
                    renderConditionsStripBitmap(
                        context = context,
                        cells = conditionsCells(info),
                        widthPx = widthPx,
                        heightPx = heightPx,
                        darkTheme = darkTheme,
                    ),
                ),
                contentDescription = conditionsContentDescription(info),
                contentScale = ContentScale.Fit,
                modifier = GlanceModifier.fillMaxSize(),
            )
        }
    }
}

private val STRIP_PADDING = 8.dp

// Surface + text colours for the strip, keyed off the resolved app theme so the
// widget honours the in-app ThemeMode rather than the device night mode. The
// surfaces are shared with the render layer (and snapshot tests) so they can't
// drift; the red/blue glyph fills read on both.
private val STRIP_LIGHT_SURFACE = Color(STRIP_SURFACE_LIGHT_ARGB)
private val STRIP_DARK_SURFACE = Color(STRIP_SURFACE_DARK_ARGB)
private val STRIP_LIGHT_TEXT = Color(0xFF1A1A1A)
private val STRIP_DARK_TEXT = Color(0xFFECECEC)

// Builds the strip's cells from the computed info: thermometer always, then the
// rain / wind / UV cells only when their label is present (gated upstream in
// outfitCardInfoLines). Wind / UV pick their Beaufort / WHO scale tint.
private fun conditionsCells(info: OutfitCardInfoLines): List<ConditionsCell> = buildList {
    add(ConditionsCell(ConditionsGlyph.THERMOMETER, info.tempLine, fillFraction = info.tempFillFraction))
    info.rainLineShort?.let {
        add(ConditionsCell(ConditionsGlyph.DROPLET, it, fillFraction = info.rainFillFraction ?: 0f))
    }
    info.windLabel?.let {
        add(ConditionsCell(ConditionsGlyph.WIND, it, tintArgb = windScaleColorArgb(info.windMaxKmh ?: 0.0)))
    }
    info.uvLabel?.let {
        add(ConditionsCell(ConditionsGlyph.UV, it, tintArgb = uvScaleColorArgb(info.uvMax ?: 0.0)))
    }
}

private fun conditionsContentDescription(info: OutfitCardInfoLines): String =
    listOfNotNull(
        info.tempLine.takeIf { it.isNotBlank() },
        info.rainLineShort,
        info.windLabel,
        info.uvLabel,
    ).joinToString(", ")
