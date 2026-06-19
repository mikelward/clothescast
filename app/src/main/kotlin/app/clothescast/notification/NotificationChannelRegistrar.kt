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
internal const val CHANNEL_PLAYBACK = "playback_v1"

// Retired channel IDs to delete on upgrade. Android persists channels by ID
// until the app explicitly deletes them, so dropping the create call alone
// leaves the dead channel in system notification settings for existing
// installs. deleteNotificationChannel is a no-op when the ID was never
// registered (fresh installs), so this is safe to call unconditionally.
private const val CHANNEL_WEATHER_ALERTS_RETIRED = "weather_alerts_v1"

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

        // Remove the retired severe-weather-alerts channel from installs that
        // previously registered it (the feature is gone); see the const above.
        manager.deleteNotificationChannel(CHANNEL_WEATHER_ALERTS_RETIRED)

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

        // The foreground-service notification shown while a scheduled briefing
        // speaks (see FetchAndNotifyWorker). LOW + no sound/vibration: it's a
        // policy requirement for background audio, not a notification the user
        // needs to act on, so it stays quiet and unobtrusive.
        val playback = NotificationChannel(
            CHANNEL_PLAYBACK,
            context.getString(R.string.notification_channel_playback_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_playback_description)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        manager.createNotificationChannel(daily)
        manager.createNotificationChannel(tonightWithEvents)
        manager.createNotificationChannel(tonightSilent)
        manager.createNotificationChannel(playback)
    }
}
