package app.clothescast.alerts

import app.clothescast.core.domain.model.AlertNotifyLevel
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.data.WeatherAlertsState
import app.clothescast.data.WeatherAlertsStore
import app.clothescast.notification.WeatherAlertNotifier
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import java.util.Locale

/**
 * The one place alert notifications are decided (docs/ALERTS.md → What the
 * user sees). The shade should show exactly this: with alerts on and the
 * permission granted, each stored alert that has started and not ended, at a
 * level "Notify me for" covers and tied closely enough to the user's point,
 * less those the user dismissed at their current level. [reconcile] makes it
 * so from whatever the store and settings hold now, so nothing else posts or
 * cancels an alert notification.
 *
 * It runs after every store write (the refresher's [WeatherAlertsRefresher]
 * `onChanged`), after every change to the alert settings, on every app resume
 * (which covers a permission granted since), and at the start time of the
 * next stored alert that hasn't begun, which [scheduleWake] arranges.
 */
class WeatherAlertShade(
    private val store: WeatherAlertsStore,
    private val notifier: WeatherAlertNotifier,
    private val preferences: suspend () -> UserPreferences,
    /** Wakes [reconcile] at the given time, or cancels the wake for null. */
    private val scheduleWake: (Instant?) -> Unit = {},
    private val clock: Clock = Clock.systemUTC(),
    private val locale: () -> Locale = { Locale.getDefault() },
) {
    private val mutex = Mutex()

    suspend fun reconcile() {
        mutex.withLock {
            val prefs = preferences()
            val state = store.current()
            val now = clock.instant()
            val on = prefs.weatherAlerts
            val notified = notifier.sync(
                alerts = if (on) state.alerts else emptyList(),
                notifyLevel = if (on) prefs.weatherAlertsNotifyLevel else AlertNotifyLevel.NONE,
                previouslyNotified = state.notified,
                now = now,
                timeFormat = prefs.timeFormat,
                locale = locale(),
            )
            if (notified != state.notified) {
                // Only onto the alerts it was worked out for: a check that
                // wrote in between runs its own reconcile next.
                store.update { current ->
                    if (current.alerts == state.alerts) current.copy(notified = notified) else current
                }
            }
            scheduleWake(if (on) nextStart(state, now) else null)
        }
    }

    companion object {
        /** The earliest start time still ahead among the stored alerts, if any. */
        fun nextStart(state: WeatherAlertsState, now: Instant): Instant? =
            state.alerts.mapNotNull { it.alert.startTime }.filter { it.isAfter(now) }.minOrNull()
    }
}
