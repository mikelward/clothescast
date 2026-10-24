package app.clothescast.alerts

import android.content.Context
import app.clothescast.ClothesCastApplication
import app.clothescast.core.domain.model.AlertHazard
import app.clothescast.core.domain.model.AlertHazards
import app.clothescast.core.domain.model.AlertLevel
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.model.WeatherAlert
import app.clothescast.core.domain.usecase.RelevantAlert
import app.clothescast.data.WeatherAlertsState
import app.clothescast.diag.DiagLog
import app.clothescast.ui.garment.OutfitCardInfoLines
import app.clothescast.ui.garment.outfitCardWindowRange
import app.clothescast.ui.garment.withAlerts
import app.clothescast.ui.today.onScreen
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * The alerts a rendered insight carries (docs/ALERTS.md → What the user
 * sees): yellow and up, stored for [insight]'s place and overlapping its
 * forecast window, so the evening's insight for tomorrow carries tomorrow's
 * warnings and not one ending tonight. Most severe first. The outfit card and
 * conditions widget head their strip with the highest level; the prose ends
 * on their titles. Empty when alerts are off, nothing overlaps, or the store
 * can't be read (the insight then goes out without them).
 */
internal suspend fun insightAlerts(
    context: Context,
    prefs: UserPreferences,
    insight: Insight,
    now: Instant = Instant.now(),
): List<RelevantAlert> {
    val app = context.applicationContext as? ClothesCastApplication ?: return emptyList()
    val state = try {
        app.weatherAlertsStore.current()
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        DiagLog.w("CardAlerts", e, "Couldn't read weather alerts for an insight; going out without.")
        return emptyList()
    }
    return insightAlerts(
        state.onScreen(prefs.weatherAlerts, insight.location ?: prefs.location, now),
        insightWindow(insight, prefs.tonightSchedule.time),
    )
}

/**
 * [this] with [alerts] (an insight's, [insightAlerts]): the highest level
 * heads the strip, and a wind or storm warning shows its wind cell.
 */
internal fun OutfitCardInfoLines.withAlerts(alerts: List<RelevantAlert>): OutfitCardInfoLines =
    withAlerts(alerts.maxOfOrNull { it.level }, alerts.anyWind())

/** Whether any of [this] is a wind or storm warning, which shows the strip's wind cell. */
internal fun List<RelevantAlert>.anyWind(): Boolean =
    any { AlertHazards.of(it.alert.eventType) in WIND_HAZARDS }

private val WIND_HAZARDS = setOf(AlertHazard.WIND, AlertHazard.STORM)

/** The strip's level for [state]: [insightAlerts]' highest; a null [window] means "in force at [now]". */
internal fun cardAlertLevel(
    state: WeatherAlertsState,
    enabled: Boolean,
    location: Location?,
    window: ClosedRange<Instant>?,
    now: Instant,
): AlertLevel? = insightAlerts(state.onScreen(enabled, location, now), window).maxOfOrNull { it.level }

/**
 * Of the alerts in force ([onScreen]), those an insight over [window] carries:
 * yellow and up (the strip's rule) and overlapping it; a null [window] keeps
 * every one in force.
 */
internal fun insightAlerts(onScreen: List<RelevantAlert>, window: ClosedRange<Instant>?): List<RelevantAlert> =
    onScreen.filter { it.level >= AlertLevel.YELLOW && (window == null || it.alert.overlaps(window)) }

/** The instants [insight] forecasts, in its own zone; null when it has no hours. */
internal fun insightWindow(insight: Insight, tonightStart: LocalTime): ClosedRange<Instant>? {
    val zone = insight.forecastZone ?: ZoneId.systemDefault()
    return outfitCardWindowRange(insight.forDate, insight.period, tonightStart, insight.hourly)
        ?.let { (start, end) -> start.atZone(zone).toInstant()..end.atZone(zone).toInstant() }
}

// An unknown end runs on; an unknown start has begun.
private fun WeatherAlert.overlaps(window: ClosedRange<Instant>): Boolean =
    endTime?.isAfter(window.start) != false && startTime?.isBefore(window.endInclusive) != false
