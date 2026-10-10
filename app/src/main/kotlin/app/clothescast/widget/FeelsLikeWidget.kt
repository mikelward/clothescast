package app.clothescast.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import android.util.SizeF
import androidx.core.net.toUri
import androidx.core.os.BundleCompat
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
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
import kotlin.math.abs
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

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val charts = buildCharts(context, id, weekly = false)
        provideContent {
            GlanceTheme {
                ChartWidgetContent(charts = charts, page = THIS_PERIOD_PAGE, labelRes = R.string.feels_like_widget_label)
            }
        }
    }
}

class SevenDayFeelsLikeWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val charts = buildCharts(context, id, weekly = true)
        provideContent {
            GlanceTheme {
                ChartWidgetContent(charts = charts, page = WEEK_PAGE, labelRes = R.string.feels_like_week_widget_label)
            }
        }
    }
}

/** Pager pages the tap intents deep-link to — page 0 is the current period, page 2 the 7-day deck. */
internal const val THIS_PERIOD_PAGE = 0
private const val WEEK_PAGE = 2

// Fallback render size for the off-screen chart bitmap (3:1, mid-range). Used
// only when the launcher hasn't reported the widget's cell size yet (e.g. the
// picker preview); once it has, [renderForCells] renders at each cell's own
// shape so the chart fills the space the user gave it.
private const val RENDER_WIDTH_PX = 720
private const val RENDER_HEIGHT_PX = 240

// Bounds on the derived bitmap dimensions: small enough that a sliver-sized cell
// still renders something legible, capped so a stretched-out widget can't ask
// for a multi-megapixel bitmap on each refresh.
private const val MIN_RENDER_PX = 240
private const val MAX_RENDER_PX = 1600

// Upper bound on how long the off-screen compose+settle may take before we give
// up and show the empty state. Generous — a widget refresh is infrequent — but
// bounded so a composable that never settles can't wedge the worker.
internal const val RENDER_TIMEOUT_MS = 4000L

/**
 * Shared Glance shell for the chart widgets (feels-like and chance of rain):
 * the rasterised chart in [charts] rendered for the cell being composed (the
 * widgets use SizeMode.Exact, so this runs once per cell size), or the "no
 * forecast yet" empty state when there's none. Tapping opens Today on [page].
 *
 * The shell paints a background only for the empty state. With a chart, the
 * bitmap's own card is the widget's surface: it's themed from the in-app
 * theme setting, while GlanceTheme follows the system, so a shell background
 * behind it showed as a mismatched border (a light frame round a dark chart)
 * whenever the two disagreed.
 */
@Composable
internal fun ChartWidgetContent(charts: List<SizedChart>, page: Int, @StringRes labelRes: Int) {
    val context = LocalContext.current
    val size = LocalSize.current
    val bitmap = closestSizeIndex(charts.map { it.widthDp to it.heightDp }, size.width.value, size.height.value)
        .let { charts.getOrNull(it)?.bitmap }
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .let { if (bitmap == null) it.background(GlanceTheme.colors.widgetBackground) else it }
            .cornerRadius(16.dp)
            .clickable(actionStartActivity(chartTapIntent(context, page))),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            EmptyContent()
        } else {
            Image(
                provider = ImageProvider(bitmap),
                contentDescription = context.getString(labelRes),
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
// WidgetForecastChart off-screen, once per cell size. Returns empty (→ empty
// state) when there's no cached forecast yet or every render fails / times
// out, so a flaky render degrades to "tap to open" rather than crashing the
// launcher.
private suspend fun buildCharts(context: Context, id: GlanceId, weekly: Boolean): List<SizedChart> {
    val (insight, prefs) = loadCurrentInsight(context) ?: return emptyList()

    val hourly: List<HourlyForecast>
    val days: List<DailyForecast>?
    val startDate: LocalDate
    if (weekly) {
        // The forecast now carries 14 days (days 2-14 in upcomingDays) to feed
        // the Today screen's second week page; the widget's weekly chart stays a
        // 7-day view, so cap to today + the next six days.
        val weekDays = listOfNotNull(insight.currentDay) + insight.upcomingDays.take(6)
        if (weekDays.size < 2) return emptyList()
        val flat = weekDays.flatMap { it.hourly }
        if (flat.size < 2) return emptyList()
        hourly = flat
        days = weekDays
        startDate = weekDays.first().date
    } else {
        if (insight.hourly.size < 2) return emptyList()
        hourly = insight.hourly
        days = null
        startDate = insight.forDate
    }

    val zone = insight.forecastZone ?: ZoneId.systemDefault()
    val now = LocalDateTime.now(zone)
    val darkTheme = resolveDarkTheme(context, prefs.themeMode)
    val palette = prefs.colorPalette

    return renderForCells(context, id) { widthPx, heightPx ->
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
                "Chart bitmap null for %s widget (%s hourly pts) — render failed/blank/timeout; showing empty state",
                if (weekly) "7-day" else "period",
                hourly.size,
            )
        }
        bitmap
    }
}

/** A chart bitmap rendered for one cell size the launcher reported, in dp. */
internal class SizedChart(val widthDp: Float, val heightDp: Float, val bitmap: Bitmap)

// The cell sizes the widget can be shown at, in dp. On API 31+ (our minSdk) the
// launcher lists the real sizes in OPTION_APPWIDGET_SIZES — usually one per
// orientation — and Glance's SizeMode.Exact composes once for each of them, so
// rendering a bitmap per listed size lets the shown image match its cell
// exactly. The older MIN/MAX width/height pair is only a range, and reading the
// "portrait" corner of it guessed wrong on some launchers: a tablet reported a
// 356x55 dp cell for a much taller widget, so the chart was drawn short and
// letterboxed. That pair is now just the fallback for a launcher that lists no
// sizes, rendered for both orientations. Empty when nothing is reported yet
// (e.g. the picker preview).
internal fun chartCellSizesDp(context: Context, id: GlanceId): List<Pair<Float, Float>> {
    val options = runCatching {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
    }.onFailure { DiagLog.w(TAG, it, "Widget: reading cell size failed; using default aspect") }
        .getOrNull() ?: return emptyList()

    val listed = BundleCompat.getParcelableArrayList(options, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
        .orEmpty()
        .map { it.width to it.height }
    val sizes = listed.ifEmpty {
        // Without a listed size, Glance's Exact mode composes for both
        // orientations' estimates from the MIN/MAX pair — portrait is
        // MIN width x MAX height, landscape MAX width x MIN height — so render
        // both, letting each composition find its own shape.
        val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).toFloat()
        val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).toFloat()
        val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).toFloat()
        val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).toFloat()
        listOf(minW to maxH, maxW to minH)
    }
    val usable = distinctCellSizes(sizes)
    DiagLog.i(
        TAG,
        "Widget: chart cells %s dp (%s)",
        usable.joinToString { "%.0fx%.0f".format(java.util.Locale.ROOT, it.first, it.second) },
        if (listed.isEmpty()) "min/max fallback" else "listed",
    )
    return usable
}

// Drops non-positive and duplicate sizes (to the whole dp), and caps the count
// so a launcher listing many sizes can't multiply the render work and the
// bitmap memory every RemoteViews update carries.
internal fun distinctCellSizes(sizes: List<Pair<Float, Float>>): List<Pair<Float, Float>> =
    sizes.filter { it.first > 0f && it.second > 0f }
        .distinctBy { it.first.roundToInt() to it.second.roundToInt() }
        .take(MAX_CELL_SIZES)

private const val MAX_CELL_SIZES = 4

/**
 * Renders [render] once per cell size the widget can show at, each bitmap at
 * that cell's own shape, so the chart fills whichever cell is on screen. Falls
 * back to one mid-range 3:1 bitmap when no size is reported yet. Sizes whose
 * render fails are dropped; an empty result means the empty state.
 */
internal suspend fun renderForCells(
    context: Context,
    id: GlanceId,
    render: suspend (widthPx: Int, heightPx: Int) -> Bitmap?,
): List<SizedChart> {
    val cells = chartCellSizesDp(context, id)
    if (cells.isEmpty()) {
        val density = context.resources.displayMetrics.density
        return listOfNotNull(
            render(RENDER_WIDTH_PX, RENDER_HEIGHT_PX)?.let {
                SizedChart(RENDER_WIDTH_PX / density, RENDER_HEIGHT_PX / density, it)
            },
        )
    }
    val density = context.resources.displayMetrics.density
    return cells.mapNotNull { (widthDp, heightDp) ->
        val (widthPx, heightPx) = scaleRenderSize((widthDp * density).roundToInt(), (heightDp * density).roundToInt())
        render(widthPx, heightPx)?.let { SizedChart(widthDp, heightDp, it) }
    }
}

/**
 * The index of the size in [sizes] closest to the cell Glance is composing for
 * ([widthDp] x [heightDp]): an exact match when the launcher's listed size is
 * the one shown, otherwise the nearest, so a launcher that reports slightly
 * different numbers still gets the right orientation's bitmap.
 */
internal fun closestSizeIndex(sizes: List<Pair<Float, Float>>, widthDp: Float, heightDp: Float): Int =
    sizes.indices.minByOrNull { i ->
        val (w, h) = sizes[i]
        abs(w - widthDp) + abs(h - heightDp)
    } ?: -1

/**
 * Keeps a [widthPx]×[heightPx] bitmap within [MIN_RENDER_PX]..[MAX_RENDER_PX]
 * on each side where possible, preserving its aspect: scaled down when the
 * longer side is over the cap, up when the shorter is under the floor (the cap
 * wins for an extreme aspect, so the bitmap never exceeds it).
 */
internal fun scaleRenderSize(widthPx: Int, heightPx: Int): Pair<Int, Int> {
    val longer = maxOf(widthPx, heightPx)
    val shorter = minOf(widthPx, heightPx)
    val scale = when {
        longer > MAX_RENDER_PX -> MAX_RENDER_PX.toFloat() / longer
        shorter < MIN_RENDER_PX -> minOf(MIN_RENDER_PX.toFloat() / shorter, MAX_RENDER_PX.toFloat() / longer)
        else -> 1f
    }
    return (widthPx * scale).roundToInt().coerceAtLeast(1) to (heightPx * scale).roundToInt().coerceAtLeast(1)
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
            DiagLog.w(TAG, it, "%s widget update failed.", label)
        }
    }
    guarded("Outfit") { OutfitWidget().updateAll(context) }
    guarded("Feels-like") { FeelsLikeWidget().updateAll(context) }
    guarded("7-day feels-like") { SevenDayFeelsLikeWidget().updateAll(context) }
    guarded("Chance of rain") { ChanceOfRainWidget().updateAll(context) }
    guarded("Conditions") { ConditionsWidget().updateAll(context) }
}

private const val TAG = "Widget"
