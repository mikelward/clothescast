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

// Retired channel IDs to delete on upgrade. Android persists channels by ID
// until the app explicitly deletes them, so dropping the create call alone
// leaves the dead channel in system notification settings for existing
// installs. deleteNotificationChannel is a no-op when the ID was never
// registered (fresh installs), so this is safe to call unconditionally.
private const val CHANNEL_WEATHER_ALERTS_RETIRED = "weather_alerts_v1"

// Retired: the spoken briefing's playback foreground service used to show its
// own "Speaking the forecast" notification on this channel. It now reuses the
// forecast's own daily/tonight channel (the foreground-service notification *is*
// the forecast), so this dedicated channel is gone.
private const val CHANNEL_PLAYBACK_RETIRED = "playback_v1"

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

        // Remove retired channels from installs that previously registered them
        // (the severe-weather-alerts feature is gone; the spoken-briefing
        // playback notification now lives on the forecast's own channel); see
        // the consts above.
        manager.deleteNotificationChannel(CHANNEL_WEATHER_ALERTS_RETIRED)
        manager.deleteNotificationChannel(CHANNEL_PLAYBACK_RETIRED)

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
