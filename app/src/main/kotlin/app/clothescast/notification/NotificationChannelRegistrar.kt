package app.clothescast.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import app.clothescast.R

/**
 * Channel IDs are stable identifiers persisted by the system; renaming or deleting one
 * is a user-visible change. Bump the suffix when channel semantics change.
 */
internal const val CHANNEL_DAILY_INSIGHT = "daily_insight_v1"
internal const val CHANNEL_TONIGHT_INSIGHT_DEFAULT = "tonight_insight_default_v1"
internal const val CHANNEL_TONIGHT_INSIGHT_SILENT = "tonight_insight_silent_v1"

/**
 * Registers the notification channel(s) used by the app. Idempotent — safe to call from
 * Application.onCreate on every cold start.
 *
 * Three channels:
 * - **Daily insight** (HIGH): the morning weather summary the user opted in to.
 * - **Tonight insight (with events)** (DEFAULT): the evening summary when the user
 *   has calendar events tonight. Plays the default notification sound — the user is
 *   actually heading out and should hear the heads-up.
 * - **Tonight insight (silent)** (LOW): the evening summary when the evening is
 *   empty. Posts so the user can glance at it on the lock screen, but no sound or
 *   vibration so it doesn't interrupt the evening.
 *
 * Two tonight channels (rather than per-notification silencing) is the canonical
 * Android approach: channel importance is sticky, the user can mute / un-mute either
 * independently in system settings, and silent-vs-default behaviour is reliable
 * across OEMs.
 */
object NotificationChannelRegistrar {
    fun register(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return

        val daily = NotificationChannel(
            CHANNEL_DAILY_INSIGHT,
            context.getString(R.string.notification_channel_daily_insight_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_daily_insight_description)
            setShowBadge(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        val tonightWithEvents = NotificationChannel(
            CHANNEL_TONIGHT_INSIGHT_DEFAULT,
            context.getString(R.string.notification_channel_tonight_insight_default_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_tonight_insight_default_description)
            setShowBadge(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        val tonightSilent = NotificationChannel(
            CHANNEL_TONIGHT_INSIGHT_SILENT,
            context.getString(R.string.notification_channel_tonight_insight_silent_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_tonight_insight_silent_description)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        manager.createNotificationChannel(daily)
        manager.createNotificationChannel(tonightWithEvents)
        manager.createNotificationChannel(tonightSilent)
    }
}
