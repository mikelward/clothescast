package app.clothescast.notification

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import app.clothescast.MainActivity
import app.clothescast.R
import app.clothescast.alerts.alertTitle
import app.clothescast.alerts.alertUntilAndIssuer
import app.clothescast.alerts.icon
import app.clothescast.core.domain.model.AlertLevel
import app.clothescast.core.domain.model.AlertNotifyLevel
import app.clothescast.core.domain.model.TimeFormat
import app.clothescast.core.domain.usecase.RelevantAlert
import app.clothescast.diag.DiagLog
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Posts weather alert notifications on their own channel, one per alert
 * (docs/ALERTS.md → What the user sees), as [app.clothescast.alerts.WeatherAlertShade]
 * decides: only for the levels the user picked (orange and red by default),
 * only for alerts tied closely enough to the user's point and in force (started
 * and not ended), each sounding once per level — a dismissed alert stays
 * dismissed unless its level rises — kept current in place while it's still in
 * the shade (a downgrade or a new end time updates it silently), and removed
 * when it ends or drops out.
 */
class WeatherAlertNotifier(private val context: Context) {

    /**
     * Brings the shade in line with [alerts]: posts the ones not yet notified
     * at their level, cancels every alert notification that no longer applies
     * (what [previouslyNotified] lists and anything still showing). Returns
     * the new notified map (alert ID → level) for the caller to store.
     */
    // POST_NOTIFICATIONS is checked through NotificationPermission.isGranted
    // before any post; lint can't follow that helper (as in InsightNotifier).
    @SuppressLint("MissingPermission")
    fun sync(
        alerts: List<RelevantAlert>,
        notifyLevel: AlertNotifyLevel,
        previouslyNotified: Map<String, AlertLevel>,
        now: Instant,
        timeFormat: TimeFormat,
        locale: Locale,
    ): Map<String, AlertLevel> {
        val manager = NotificationManagerCompat.from(context)
        val eligible = alerts.filter { relevant ->
            relevant.notifiable &&
                notifyLevel.notifies(relevant.level) &&
                // In force: a warning for tomorrow waits until it starts.
                relevant.alert.startTime?.isAfter(now) != true &&
                relevant.alert.endTime?.isAfter(now) != false
        }
        val eligibleIds = eligible.map { it.alert.id }.toSet()
        val showing = showingIds()
        for (id in (previouslyNotified.keys + showing) - eligibleIds) {
            manager.cancel(id, NOTIFICATION_ID_WEATHER_ALERT)
        }
        val notified = previouslyNotified.filterKeys { it in eligibleIds }.toMutableMap()
        if (!NotificationPermission.isGranted(context)) {
            if (eligible.isNotEmpty()) DiagLog.i(TAG, "Alert notifications skipped: permission not granted.")
            return notified
        }
        for (relevant in eligible) {
            val id = relevant.alert.id
            val previous = notified[id]
            val rose = previous == null || relevant.level > previous
            // Not new, not higher, and swiped away: it stays dismissed.
            if (!rose && id !in showing) continue
            // A rise sounds again; an update to one still showing doesn't.
            manager.notify(id, NOTIFICATION_ID_WEATHER_ALERT, build(relevant, now, timeFormat, locale, silent = !rose))
            notified[id] = relevant.level
            if (rose) DiagLog.i(TAG, "Posted a %s weather alert notification.", relevant.level)
        }
        return notified
    }

    /** The alert IDs whose notifications are in the shade now. */
    private fun showingIds(): Set<String> = try {
        context.getSystemService(NotificationManager::class.java)
            ?.activeNotifications
            ?.filter { it.id == NOTIFICATION_ID_WEATHER_ALERT }
            ?.mapNotNull { it.tag }
            ?.toSet()
            .orEmpty()
    } catch (e: RuntimeException) {
        // Without the list, nothing is updated in place; new and higher
        // alerts still post.
        DiagLog.w(TAG, e, "Couldn't read the alert notifications showing.")
        emptySet()
    }

    private fun build(
        relevant: RelevantAlert,
        now: Instant,
        timeFormat: TimeFormat,
        locale: Locale,
        silent: Boolean,
    ): android.app.Notification {
        val alert = relevant.alert
        val icon = relevant.icon
        val title = alertTitle(context, alert)
        val detail = alertUntilAndIssuer(context, alert, now, timeFormat, locale)
        val open = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            MainActivity.todayTapIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun base() = NotificationCompat.Builder(context, CHANNEL_WEATHER_ALERTS)
            .setSmallIcon(icon.drawable)
            .setColor(icon.lightArgb)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setGroup(GROUP_WEATHER_ALERTS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(silent)
            // A fixed time, so a silent update doesn't jump the shade's order.
            .apply { alert.startTime?.let { setWhen(it.toEpochMilli()) } }

        // The lock screen gets the title, times and issuer only; the area
        // name (a region, so a coarse location) waits until it's unlocked.
        val expanded = listOfNotNull(alert.areaName, detail).joinToString("\n")
        val builder = base()
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(base().build())
        alert.issuerUrl?.let { url ->
            val view = PendingIntent.getActivity(
                context,
                REQUEST_OPEN_ISSUER,
                Intent(Intent.ACTION_VIEW, url.toUri()),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val label = alert.issuer?.let { context.getString(R.string.weather_alert_open_source, it) }
                ?: context.getString(R.string.weather_alert_open_source_fallback)
            builder.addAction(0, label, view)
        }
        alert.endTime?.let { end ->
            val left = Duration.between(now, end)
            if (!left.isNegative) builder.setTimeoutAfter(left.toMillis())
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "WeatherAlertNotifier"

        /** Shared by every alert; each is told apart by its tag (the alert ID). */
        const val NOTIFICATION_ID_WEATHER_ALERT = 1301
        private const val GROUP_WEATHER_ALERTS = "weather_alerts"
        private const val REQUEST_OPEN_APP = 130
        private const val REQUEST_OPEN_ISSUER = 131
    }
}
