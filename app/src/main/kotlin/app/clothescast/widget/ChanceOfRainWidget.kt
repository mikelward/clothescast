package app.clothescast.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import app.clothescast.R
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.TimeFormat
import app.clothescast.diag.DiagLog
import app.clothescast.ui.LocalTimeFormat
import app.clothescast.ui.theme.ClothesCastTheme
import app.clothescast.ui.today.ChartScrubController
import app.clothescast.ui.today.LocalChartScrub
import app.clothescast.ui.today.PrecipitationCard
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Home-screen widget exposing the Today screen's chance-of-rain chart for the
 * current period. Same approach as [FeelsLikeWidget]: Glance can't host Vico,
 * so the real in-app card ([PrecipitationCard], via [WidgetRainChart]) is
 * rasterised off-screen at the cell's size and shown as an image. Reads the
 * same cached insight through [loadCurrentInsight], so it shares the
 * freshness gate and the empty "tap to open" state, and is repainted by
 * [updateAllClothesCastWidgets] after each cache write.
 */
class ChanceOfRainWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val charts = buildRainCharts(context, id)
        provideContent {
            GlanceTheme {
                ChartWidgetContent(
                    charts = charts,
                    page = THIS_PERIOD_PAGE,
                    labelRes = R.string.today_precipitation_title,
                )
            }
        }
    }
}

/**
 * The in-app chance-of-rain card without its title or y-axis, for the widget — the
 * "Peak 60% at 15:00" / "No rain expected today" line and the % axis already
 * say what it is. Rendered for real rather than redrawn, as with
 * [WidgetForecastChart], so the widget can't drift from the screen.
 *
 * @param now current time in the forecast zone (the current-time line and
 *   readout), or null when "now" is outside the window.
 */
@Composable
internal fun WidgetRainChart(
    hourly: List<HourlyForecast>,
    timeFormat: TimeFormat,
    startDate: LocalDate,
    now: LocalDateTime?,
    fillHeight: Boolean = false,
    period: ForecastPeriod = ForecastPeriod.TODAY,
) {
    val controller = remember(now) {
        ChartScrubController().apply { now?.let { setNow(it) } }
    }
    CompositionLocalProvider(
        LocalTimeFormat provides timeFormat,
        LocalChartScrub provides controller,
    ) {
        // Explicit M3 Vico theme — the off-screen render context doesn't
        // reproduce the in-app default (see WidgetForecastChart).
        ProvideVicoTheme(rememberM3VicoTheme()) {
            PrecipitationCard(
                hourly = hourly,
                startDate = startDate,
                showHeader = false,
                fillHeight = fillHeight,
                period = period,
                showYAxis = false,
            )
        }
    }
}

// Empty (→ empty state) when there's no current cached forecast or every
// render fails / times out, so a flaky render degrades to "tap to open".
private suspend fun buildRainCharts(context: Context, id: GlanceId): WidgetCharts {
    val (insight, prefs) = loadCurrentInsight(context) ?: return WidgetCharts.EMPTY
    if (insight.hourly.size < 2) return WidgetCharts.EMPTY

    val zone = insight.forecastZone ?: ZoneId.systemDefault()
    val now = LocalDateTime.now(zone)
    val darkTheme = resolveDarkTheme(context, prefs.themeMode)
    return renderForCells(context, id) { widthPx, heightPx, densityDpi ->
        val bitmap = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
            renderComposableToBitmap(context, widthPx, heightPx, densityDpi) {
                ClothesCastTheme(darkTheme = darkTheme, colorPalette = prefs.colorPalette) {
                    WidgetRainChart(
                        hourly = insight.hourly,
                        timeFormat = prefs.timeFormat,
                        startDate = insight.forDate,
                        now = now,
                        fillHeight = true,
                        period = insight.period,
                    )
                }
            }
        }
        if (bitmap == null) {
            DiagLog.w(
                TAG,
                "Rain chart bitmap null (%s hourly pts) — render failed/blank/timeout; showing empty state",
                insight.hourly.size,
            )
        }
        bitmap
    }
}

private const val TAG = "Widget"
