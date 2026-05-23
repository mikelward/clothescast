package app.clothescast.ui.today

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.clothescast.core.domain.model.HourlyForecast
import com.patrykandpatrick.vico.core.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.core.cartesian.decoration.Decoration
import com.patrykandpatrick.vico.core.common.Fill
import com.patrykandpatrick.vico.core.common.component.LineComponent
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Composition-local x-coordinate (in chart units) at which to draw the
 * "now" indicator. Null means "don't draw" — that's the default everywhere
 * except inside [TodayScreen]'s card section, which provides a real value
 * computed from [currentTimeChartX]. Routing this through a local instead
 * of plumbing a parameter through every card wrapper keeps the existing
 * chart / card signatures (and their previews) unchanged.
 */
internal val LocalChartCurrentTimeX = compositionLocalOf<Double?> { null }

/**
 * Maps the current wall-clock time to an x-position on the 0..n-1 hour-index
 * axis the chart uses. Returns null when [now] sits outside the hourly window,
 * so the caller can omit the decoration entirely on future-day pages or before
 * the morning slice begins.
 *
 * Handles the tonight slice (e.g. 21,22,23,0..5) by advancing the date on each
 * hour-of-day backwards step rather than requiring callers to pass the wrap
 * point in. [startDate] is the date of `hourly[0]` — for the TODAY insight
 * that's `insight.forDate`; for TONIGHT it's also `insight.forDate` because
 * the window starts on that date and rolls into the next.
 *
 * Fractional: a 14:30 lookup against an hourly list with hours 14 and 15
 * returns 0.5 past index 14's position, so the resulting line slides smoothly
 * across the chart as the minute hand moves rather than jumping in hour
 * steps.
 */
internal fun currentTimeChartX(
    hourly: List<HourlyForecast>,
    startDate: LocalDate,
    now: LocalDateTime,
): Double? {
    if (hourly.isEmpty()) return null
    val timestamps = ArrayList<LocalDateTime>(hourly.size)
    var date = startDate
    var prevHour: Int? = null
    for (h in hourly) {
        val hour = h.time.hour
        if (prevHour != null && hour < prevHour) date = date.plusDays(1)
        timestamps += LocalDateTime.of(date, h.time)
        prevHour = hour
    }
    val end = timestamps.last().plusHours(1)
    if (now.isBefore(timestamps.first()) || !now.isBefore(end)) return null
    for (i in timestamps.indices) {
        val start = timestamps[i]
        val next = if (i + 1 < timestamps.size) timestamps[i + 1] else end
        if (!now.isBefore(start) && now.isBefore(next)) {
            val span = Duration.between(start, next).toMillis().toDouble()
            val off = Duration.between(start, now).toMillis().toDouble()
            return i + off / span
        }
    }
    return null
}

/**
 * Returns the Vico [Decoration] list to hand to `rememberCartesianChart`'s
 * `decorations` parameter. Empty when [LocalChartCurrentTimeX] is null, so
 * every chart that calls this is free to wire it in unconditionally.
 */
@Composable
internal fun rememberCurrentTimeDecorations(): List<Decoration> {
    val x = LocalChartCurrentTimeX.current ?: return EMPTY
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    return remember(x, color) {
        listOf(VerticalLine(x = x, color = color))
    }
}

private val EMPTY: List<Decoration> = emptyList()

/**
 * Draws a 1.5-dp vertical line at chart-x position [x], spanning the chart's
 * full vertical bounds. Vico ships a [com.patrykandpatrick.vico.core.cartesian.decoration.HorizontalLine]
 * out of the box but no vertical counterpart, so we map x → canvas pixel using
 * the same formula `LineCartesianLayer` uses for its own data points:
 * `drawingStart + multiplier * xSpacing * (x - minX) / xStep`.
 *
 * Drawn under the data layers (`drawUnderLayers`) so the forecast lines stay
 * visually dominant and the indicator reads as a background reference rather
 * than something that competes with the data.
 */
private class VerticalLine(
    private val x: Double,
    private val color: Color,
) : Decoration {
    private val line = LineComponent(fill = Fill(color.toArgb()), thicknessDp = 1.5f)

    override fun drawUnderLayers(context: CartesianDrawingContext) {
        with(context) {
            if (x < ranges.minX || x > ranges.maxX) return
            val multiplier = layoutDirectionMultiplier.toFloat()
            val drawingStart =
                (if (isLtr) layerBounds.left else layerBounds.right) +
                    multiplier * layerDimensions.startPadding - scroll
            val canvasX = drawingStart +
                multiplier * layerDimensions.xSpacing *
                ((x - ranges.minX) / ranges.xStep).toFloat()
            line.drawVertical(context, canvasX, layerBounds.top, layerBounds.bottom)
        }
    }
}
