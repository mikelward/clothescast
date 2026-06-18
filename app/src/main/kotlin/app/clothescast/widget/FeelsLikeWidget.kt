package app.clothescast.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
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
import app.clothescast.MainActivity
import app.clothescast.R
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.ThemeMode
import app.clothescast.diag.DiagLog
import app.clothescast.ui.theme.ClothesCastTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

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
        val bitmap = buildChartBitmap(context, id, weekly = false)
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
        val bitmap = buildChartBitmap(context, id, weekly = true)
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

// Fallback render size for the off-screen chart bitmap (3:1, mid-range). Used
// only when the launcher hasn't reported the widget's cell size yet (e.g. the
// picker preview); once it has, [chartRenderSizePx] renders at the cell's own
// aspect (clamped to [MIN_ASPECT_RATIO]..[MAX_ASPECT_RATIO]) so the chart fills
// the space the user gave it instead of letterboxing inside a fixed-aspect box.
private const val RENDER_WIDTH_PX = 720
private const val RENDER_HEIGHT_PX = 240

// Bounds on the derived bitmap dimensions: small enough that a sliver-sized cell
// still renders something legible, capped so a stretched-out widget can't ask
// for a multi-megapixel bitmap on each refresh.
private const val MIN_RENDER_PX = 240
private const val MAX_RENDER_PX = 1600

// A line chart reads best wide, so keep its width:height between these bounds
// regardless of the cell shape. On a tall/near-square cell we pin to the min so
// the chart stays wide; on an ultra-wide cell we pin to the max so it doesn't
// get uncomfortably long-and-thin. The Glance Image then pads the short side
// under ContentScale.Fit. Between the bounds we render at the cell's own aspect.
private const val MIN_ASPECT_RATIO = 2f
private const val MAX_ASPECT_RATIO = 4f

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
    Intent(Intent.ACTION_VIEW, MainActivity.todayPageUri(page).toUri(), context, MainActivity::class.java)
        .apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

// Loads the cached insight, derives the chart inputs, and rasterises the real
// WidgetForecastChart off-screen. Returns null (→ empty state) when there's no
// cached forecast yet or the render fails / times out, so a flaky render
// degrades to "tap to open" rather than crashing the launcher.
private suspend fun buildChartBitmap(context: Context, id: GlanceId, weekly: Boolean): Bitmap? {
    val (insight, prefs) = loadCurrentInsight(context) ?: return null

    val hourly: List<HourlyForecast>
    val days: List<DailyForecast>?
    val startDate: LocalDate
    if (weekly) {
        // The forecast now carries 14 days (days 2-14 in upcomingDays) to feed
        // the Today screen's second week page; the widget's weekly chart stays a
        // 7-day view, so cap to today + the next six days.
        val weekDays = listOfNotNull(insight.currentDay) + insight.upcomingDays.take(6)
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

    val (widthPx, heightPx) = chartRenderSizePx(context, id)
    val bitmap = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
        renderComposableToBitmap(context, widthPx, heightPx) {
            ClothesCastTheme(darkTheme = darkTheme, colorPalette = palette) {
                WidgetForecastChart(
                    hourly = hourly,
                    days = days,
                    temperatureUnit = prefs.temperatureUnit,
                    timeFormat = prefs.timeFormat,
                    startDate = startDate,
                    now = now,
                    // Fill the bitmap we sized to the cell, so the chart scales
                    // with the available space rather than wrapping a fixed height.
                    fillHeight = true,
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

// Derives the off-screen bitmap size from the widget's actual cell, so the chart
// scales with the space the user gave it. The launcher reports the cell extent
// (in dp) via the AppWidget options bundle: for the visible orientation that's
// MAX width × MIN height — the other pair describes the *rotated* extent. We
// render at the cell's own aspect (clamped to
// [MIN_ASPECT_RATIO]..[MAX_ASPECT_RATIO]) so ContentScale.Fit fills the cell on
// a wide placement instead of leaving side gaps, while keeping the chart
// comfortably wider than it is tall and never uncomfortably long-and-thin.
// Resizing the widget triggers an options-changed update, which re-runs
// provideGlance and re-renders at the new size. Falls back to a mid-range 3:1
// aspect when no size is reported yet (e.g. the picker preview).
private fun chartRenderSizePx(context: Context, id: GlanceId): Pair<Int, Int> {
    val fallback = RENDER_WIDTH_PX to RENDER_HEIGHT_PX
    val options = runCatching {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
    }.onFailure { DiagLog.w(TAG, "Widget: reading cell size failed; using default aspect", it) }
        .getOrNull() ?: return fallback

    val portrait = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val widthDp = options.getInt(
        if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
        else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
    )
    val heightDp = options.getInt(
        if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
        else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
    )
    if (widthDp <= 0 || heightDp <= 0) return fallback

    val density = context.resources.displayMetrics.density
    var widthPx = (widthDp * density).roundToInt()
    var heightPx = (heightDp * density).roundToInt()

    // Clamp the aspect into [MIN_ASPECT_RATIO, MAX_ASPECT_RATIO]: render at the
    // cell's own aspect when it's already in range, otherwise pin to the nearer
    // bound by shrinking the longer side (the Glance Image pads the short side
    // under ContentScale.Fit), so the chart stays comfortably wide either way.
    val aspect = widthPx.toFloat() / heightPx
    when {
        aspect > MAX_ASPECT_RATIO -> widthPx = (heightPx * MAX_ASPECT_RATIO).roundToInt()
        aspect < MIN_ASPECT_RATIO -> heightPx = (widthPx / MIN_ASPECT_RATIO).roundToInt()
    }

    // Keep the bitmap within sane pixel bounds, preserving the clamped aspect:
    // scale down if the longer side is over the cap, up if the shorter is under.
    val longer = maxOf(widthPx, heightPx)
    val shorter = minOf(widthPx, heightPx)
    val scale = when {
        longer > MAX_RENDER_PX -> MAX_RENDER_PX.toFloat() / longer
        shorter < MIN_RENDER_PX -> MIN_RENDER_PX.toFloat() / shorter
        else -> 1f
    }
    widthPx = (widthPx * scale).roundToInt()
    heightPx = (heightPx * scale).roundToInt()
    return widthPx to heightPx
}

// Mirrors MainActivity's theme resolution so the widget's dark mode tracks the
// in-app ThemeMode preference rather than only the system setting.
internal fun resolveDarkTheme(context: Context, themeMode: ThemeMode): Boolean = when (themeMode) {
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
 * failing to bind doesn't starve the others — but cancellation rethrows so a
 * cancelled caller unwinds instead of marching through the remaining widgets.
 */
internal suspend fun updateAllClothesCastWidgets(context: Context) {
    suspend fun guarded(label: String, update: suspend () -> Unit) {
        runCatching { update() }.onFailure {
            if (it is CancellationException) throw it
            DiagLog.w(TAG, "$label widget update failed.", it)
        }
    }
    guarded("Outfit") { OutfitWidget().updateAll(context) }
    guarded("Feels-like") { FeelsLikeWidget().updateAll(context) }
    guarded("7-day feels-like") { SevenDayFeelsLikeWidget().updateAll(context) }
    guarded("Conditions") { ConditionsWidget().updateAll(context) }
}

private const val TAG = "Widget"
