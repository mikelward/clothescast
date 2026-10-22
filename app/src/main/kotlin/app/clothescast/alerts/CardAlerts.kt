package app.clothescast.alerts

import android.content.Context
import app.clothescast.ClothesCastApplication
import app.clothescast.core.domain.model.AlertLevel
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.model.WeatherAlert
import app.clothescast.data.WeatherAlertsState
import app.clothescast.diag.DiagLog
import app.clothescast.ui.garment.outfitCardWindowRange
import app.clothescast.ui.today.TodayAlertsState
import app.clothescast.ui.today.onScreen
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId

/**
 * The alert level the conditions strip on a rendered card heads with — the
 * outfit card sent to Cast and Home Assistant, and the conditions widget
 * (docs/ALERTS.md → What the user sees): the highest yellow-and-up alert
 * stored for [insight]'s place that overlaps its forecast window, so the
 * evening's card for tomorrow shows tomorrow's warnings and not one ending
 * tonight. Null when alerts are off, nothing overlaps, or the store can't be
 * read (the card then draws without it).
 */
internal suspend fun cardAlertLevel(
    context: Context,
    prefs: UserPreferences,
    insight: Insight,
    now: Instant = Instant.now(),
): AlertLevel? {
    val app = context.applicationContext as? ClothesCastApplication ?: return null
    val state = try {
        app.weatherAlertsStore.current()
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        DiagLog.w("CardAlerts", e, "Couldn't read weather alerts for a card; drawing it without.")
        return null
    }
    val zone = insight.forecastZone ?: ZoneId.systemDefault()
    val window = outfitCardWindowRange(insight.forDate, insight.period, prefs.tonightSchedule.time, insight.hourly)
        ?.let { (start, end) -> start.atZone(zone).toInstant()..end.atZone(zone).toInstant() }
    return cardAlertLevel(state, prefs.weatherAlerts, insight.location ?: prefs.location, window, now)
}

/** [cardAlertLevel]'s rule on its inputs; a null [window] means "in force at [now]". */
internal fun cardAlertLevel(
    state: WeatherAlertsState,
    enabled: Boolean,
    location: Location?,
    window: ClosedRange<Instant>?,
    now: Instant,
): AlertLevel? {
    val alerts = state.onScreen(enabled, location, now).filter { window == null || it.alert.overlaps(window) }
    return TodayAlertsState(alerts = alerts).stripLevel
}

// An unknown end runs on; an unknown start has begun.
private fun WeatherAlert.overlaps(window: ClosedRange<Instant>): Boolean =
    endTime?.isAfter(window.start) != false && startTime?.isBefore(window.endInclusive) != false
