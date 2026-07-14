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
// Bumped from `scheduled_insight_v1` so the drop from DEFAULT to LOW (silent —
// no sound or heads-up) actually reaches existing installs. Android preserves an
// existing channel's user-set importance forever, so editing the constant alone
// would leave anyone who already had v1 still hearing the old ding.
internal const val CHANNEL_SCHEDULED_INSIGHT = "scheduled_insight_v2"
// Bumped from `playback_v1` so the broader semantics (now covers Preparing
// + Delivering across the whole scheduled run, not just speech) take a
// fresh user importance choice. Android preserves an existing channel's
// user-set importance forever, so a user who had muted the old "Speaking
// the forecast" channel would otherwise also lose the new schedule-fired
// confirmation post.
internal const val CHANNEL_PLAYBACK = "playback_v2"

// Retired channel IDs to delete on upgrade. Android persists channels by ID
// until the app explicitly deletes them, so dropping the create call alone
// leaves the dead channel in system notification settings for existing
// installs. deleteNotificationChannel is a no-op when the ID was never
// registered (fresh installs), so this is safe to call unconditionally.
private const val CHANNEL_WEATHER_ALERTS_RETIRED = "weather_alerts_v1"
// Pre-collapse forecast channels. The three of them — morning (HIGH), evening-
// with-events (DEFAULT), evening-empty (LOW silent) — were replaced by the
// single CHANNEL_SCHEDULED_INSIGHT above (now LOW/silent — see the v1
// retirement below). The notifyOnlyOnEvents preference now drives "post or
// don't post" at the worker level, not channel routing, so the silent channel
// no longer makes sense: the user picked "only on events", so an empty
// evening should produce no notification, not a quiet one. And both periods
// at the same importance keeps a night-worker primary tonight slot reading
// exactly like a day-worker primary morning slot.
private const val CHANNEL_DAILY_INSIGHT_RETIRED_V1 = "daily_insight_v1"
// Brief interim ID introduced and immediately collapsed into
// CHANNEL_SCHEDULED_INSIGHT — retire it too in case any dev / TestFlight build
// registered it before the collapse landed.
private const val CHANNEL_DAILY_INSIGHT_RETIRED_V2 = "daily_insight_v2"
private const val CHANNEL_TONIGHT_INSIGHT_DEFAULT_RETIRED = "tonight_insight_default_v1"
private const val CHANNEL_TONIGHT_INSIGHT_SILENT_RETIRED = "tonight_insight_silent_v1"
// Pre-silent forecast channel, IMPORTANCE_DEFAULT (made a sound). Replaced by the
// LOW (silent) CHANNEL_SCHEDULED_INSIGHT above — a fresh ID so the new silence
// reaches installs that already created the DEFAULT channel.
private const val CHANNEL_SCHEDULED_INSIGHT_RETIRED_V1 = "scheduled_insight_v1"
// Pre-v2 playback channel was scoped to "Speaking the forecast"; v2 broadens
// to cover Preparing + Delivering across the whole scheduled run. Same
// rationale as the forecast-channel collapse: let the user pick importance
// again rather than silently carrying their old mute into a different
// scope.
private const val CHANNEL_PLAYBACK_RETIRED_V1 = "playback_v1"

/**
 * Registers the notification channel(s) used by the app. Idempotent — safe to call from
 * Application.onCreate on every cold start.
 *
 * Two channels:
 * - **Scheduled ClothesCast** (LOW, silent): the morning *and* evening forecast
 *   summaries the user opted into. Posted at the same importance for both
 *   periods so a night-worker primary tonight slot reads exactly as well as
 *   a day-worker primary morning slot. Silent by design — these are a calm
 *   reminder, not an alert, so they land in the shade without a sound.
 * - **Scheduled ClothesCast delivery** (LOW, silent): the foreground-service
 *   notification shown while the run prepares + delivers (see
 *   [ScheduledDeliveryService]). A policy requirement for the background
 *   FGS, not a user-actionable post — stays silent and unobtrusive.
 */
object NotificationChannelRegistrar {
    fun register(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return

        // Remove every retired channel ID from installs that previously
        // registered them, so dead channels don't sit in system notification
        // settings forever.
        //
        // The collapse from three forecast channels into one is a fresh
        // start: we create the new channel at its own importance (LOW,
        // below) regardless of what the user did to the old ones. Trying to migrate the prior opt-out
        // state has no clean answer when three channels with different
        // user preferences fold into one — and surprise-muting a user who
        // only silenced (say) the empty-evening silent channel would
        // silence morning forecasts too, which they never asked for.
        // Users who want to mute the new channel can do so in system
        // notification settings.
        manager.deleteNotificationChannel(CHANNEL_WEATHER_ALERTS_RETIRED)
        manager.deleteNotificationChannel(CHANNEL_DAILY_INSIGHT_RETIRED_V1)
        manager.deleteNotificationChannel(CHANNEL_DAILY_INSIGHT_RETIRED_V2)
        manager.deleteNotificationChannel(CHANNEL_TONIGHT_INSIGHT_DEFAULT_RETIRED)
        manager.deleteNotificationChannel(CHANNEL_TONIGHT_INSIGHT_SILENT_RETIRED)
        manager.deleteNotificationChannel(CHANNEL_PLAYBACK_RETIRED_V1)
        manager.deleteNotificationChannel(CHANNEL_SCHEDULED_INSIGHT_RETIRED_V1)

        // LOW + no sound / vibration: the morning and evening summaries are a
        // calm, opted-in reminder, not an alert — they post silently to the shade
        // and status bar without a ding or heads-up. setShowBadge stays on so the
        // launcher dot still flags an unread summary.
        val scheduled = NotificationChannel(
            CHANNEL_SCHEDULED_INSIGHT,
            context.getString(R.string.notification_channel_scheduled_insight_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_scheduled_insight_description)
            setShowBadge(true)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        // The foreground-service notification shown while a scheduled briefing
        // prepares + delivers (see ScheduledDeliveryService). LOW + no sound /
        // vibration: it's a policy requirement for the background run, not a
        // notification the user needs to act on, so it stays quiet and
        // unobtrusive.
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

        manager.createNotificationChannel(scheduled)
        manager.createNotificationChannel(playback)
    }
}
