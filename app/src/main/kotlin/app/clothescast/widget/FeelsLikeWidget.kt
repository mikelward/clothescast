package app.clothescast.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.clothescast.ClothesCastApplication
import app.clothescast.MainActivity
import app.clothescast.R
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.ThemeMode
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.diag.DiagLog
import app.clothescast.ui.theme.ClothesCastTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Home-screen widgets exposing the Today screen's feels-like chart. Two flavours
 * the user can place independently:
 *
 *  - [FeelsLikeWidget] — the current 12-hour period's hourly feels-like line,
 *    matching pager page 0 (Today / Tonight). Tapping opens the app there.
 *  - [SevenDayFeelsLikeWidget] — the next-7-days feels-like line, matching pager
 *    page 2. Tapping opens the app on the 7-day page.
 *
 * Glance can't host the Compose/Vico chart (it emits RemoteViews), so each
 * widget rasterises the **real** [WidgetForecastChart] — the in-app
 * `ForecastChart` with the legend dropped — to a bitmap via
 * [renderComposableToBitmap] and shows it as an [Image]. Rendering the real
 * composable (rather than a hand-drawn lookalike) keeps the widget's colours,
 * fonts, tick spacing and line shape identical to the screen. The bitmap is
 * themed with the user's palette + dark-mode pref so it matches the app exactly.
 *
 * Both read the same [app.clothescast.data.InsightCache] the Today screen does.
 * Refreshes are pushed by [updateAllClothesCastWidgets] after each cache write /
 * settings change; there's no per-widget polling.
 */
class FeelsLikeWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val bitmap = buildChartBitmap(context, weekly = false)
        provideContent {
            GlanceTheme {
                FeelsLikeChartContent(bitmap = bitmap, page = THIS_PERIOD_PAGE)
            }
        }
    }
}

class SevenDayFeelsLikeWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val bitmap = buildChartBitmap(context, weekly = true)
        provideContent {
            GlanceTheme {
                FeelsLikeChartContent(bitmap = bitmap, page = WEEK_PAGE)
            }
        }
    }
}

/** Pager pages the tap intents deep-link to — page 0 is the current period, page 2 the 7-day deck. */
private const val THIS_PERIOD_PAGE = 0
private const val WEEK_PAGE = 2

// Fixed render size for the off-screen chart bitmap, at the card's natural
// aspect (~width:height of the in-app ForecastCard). The Glance Image scales it
// to the actual cell with ContentScale.Fit, so the chart stays undistorted on
// any cell size; SizeMode.Single means we render once rather than per breakpoint.
private const val RENDER_WIDTH_PX = 720
private const val RENDER_HEIGHT_PX = 560

// Upper bound on how long the off-screen compose+settle may take before we give
// up and show the empty state. Generous — a widget refresh is infrequent — but
// bounded so a composable that never settles can't wedge the worker.
private const val RENDER_TIMEOUT_MS = 4000L

@Composable
private fun FeelsLikeChartContent(bitmap: Bitmap?, page: Int) {
    val context = LocalContext.current
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .clickable(actionStartActivity(chartTapIntent(context, page))),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            EmptyContent()
        } else {
            Image(
                provider = ImageProvider(bitmap),
                contentDescription = context.getString(
                    if (page == WEEK_PAGE) R.string.feels_like_week_widget_label
                    else R.string.feels_like_widget_label,
                ),
                contentScale = ContentScale.Fit,
                modifier = GlanceModifier.fillMaxSize().padding(4.dp),
            )
        }
    }
}

@Composable
private fun EmptyContent() {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = context.getString(R.string.feels_like_widget_empty_title),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Medium),
        )
        Spacer(modifier = GlanceModifier.height(2.dp))
        Text(
            text = context.getString(R.string.widget_empty_subtitle),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
        )
    }
}

// ACTION_VIEW + the Today deep link (optionally carrying ?page=) mirrors
// MainActivity.todayTapIntent, which already lands notification taps on Today
// reliably. The explicit component + action + data tuple keeps the Glance
// trampoline happy (see OutfitWidget.launchAppIntent for the failure mode an
// under-specified intent hit). NEW_TASK is required because the widget launches
// from a non-activity context.
private fun chartTapIntent(context: Context, page: Int): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(MainActivity.todayPageUri(page)), context, MainActivity::class.java)
        .apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

// Loads the cached insight, derives the chart inputs, and rasterises the real
// WidgetForecastChart off-screen. Returns null (→ empty state) when there's no
// cached forecast yet or the render fails / times out, so a flaky render
// degrades to "tap to open" rather than crashing the launcher.
private suspend fun buildChartBitmap(context: Context, weekly: Boolean): Bitmap? {
    val (insight, prefs) = loadInsight(context) ?: return null

    val hourly: List<HourlyForecast>
    val days: List<DailyForecast>?
    val startDate: LocalDate
    if (weekly) {
        val weekDays = listOfNotNull(insight.currentDay) + insight.upcomingDays
        if (weekDays.size < 2) return null
        val flat = weekDays.flatMap { it.hourly }
        if (flat.size < 2) return null
        hourly = flat
        days = weekDays
        startDate = weekDays.first().date
    } else {
        if (insight.hourly.size < 2) return null
        hourly = insight.hourly
        days = null
        startDate = insight.forDate
    }

    val zone = insight.forecastZone ?: ZoneId.systemDefault()
    val now = LocalDateTime.now(zone)
    val darkTheme = resolveDarkTheme(context, prefs.themeMode)
    val palette = prefs.colorPalette

    val bitmap = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
        renderComposableToBitmap(context, RENDER_WIDTH_PX, RENDER_HEIGHT_PX) {
            ClothesCastTheme(darkTheme = darkTheme, colorPalette = palette) {
                WidgetForecastChartCanvas(
                    hourly = hourly,
                    days = days,
                    temperatureUnit = prefs.temperatureUnit,
                    timeFormat = prefs.timeFormat,
                    startDate = startDate,
                    now = now,
                )
            }
        }
    }
    if (bitmap == null) {
        DiagLog.w(
            TAG,
            "Chart bitmap null for ${if (weekly) "7-day" else "period"} widget " +
                "(${hourly.size} hourly pts) — render failed/blank/timeout; showing empty state",
        )
    }
    return bitmap
}

private suspend fun loadInsight(context: Context): Pair<Insight, UserPreferences>? {
    val app = context.applicationContext as ClothesCastApplication
    val prefs = runCatching { app.settingsRepository.preferences.first() }
        .onFailure { DiagLog.w(TAG, "Widget: reading preferences failed", it) }
        .getOrNull() ?: return null
    val snapshot = runCatching { app.insightCache.thisPeriod.first() }
        .onFailure { DiagLog.w(TAG, "Widget: reading cached snapshot failed", it) }
        .getOrNull()
    if (snapshot == null) {
        DiagLog.i(TAG, "Widget: no cached forecast yet — showing empty state")
        return null
    }
    val insight = runCatching { app.deriveInsight(snapshot, prefs).insight }
        .onFailure { DiagLog.w(TAG, "Widget: deriveInsight failed", it) }
        .getOrNull() ?: return null
    return insight to prefs
}

// Mirrors MainActivity's theme resolution so the widget's dark mode tracks the
// in-app ThemeMode preference rather than only the system setting.
private fun resolveDarkTheme(context: Context, themeMode: ThemeMode): Boolean = when (themeMode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
}

/**
 * Pushes a fresh render to every placed ClothesCast widget. Called after each
 * cache write (the worker) and after settings changes that affect what the
 * widgets show (temperature unit / time format / theme on the charts, outfit on
 * [OutfitWidget]). Each update is guarded independently so one widget type
 * failing to bind doesn't starve the others.
 */
internal suspend fun updateAllClothesCastWidgets(context: Context) {
    runCatching { OutfitWidget().updateAll(context) }
        .onFailure { DiagLog.w(TAG, "Outfit widget update failed.", it) }
    runCatching { FeelsLikeWidget().updateAll(context) }
        .onFailure { DiagLog.w(TAG, "Feels-like widget update failed.", it) }
    runCatching { SevenDayFeelsLikeWidget().updateAll(context) }
        .onFailure { DiagLog.w(TAG, "7-day feels-like widget update failed.", it) }
}

private const val TAG = "Widget"
