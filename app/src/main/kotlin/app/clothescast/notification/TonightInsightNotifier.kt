package app.clothescast.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.clothescast.MainActivity
import app.clothescast.R
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.OutfitSuggestion

/**
 * Posts the tonight insight as a system notification. Picks one of two channels
 * based on whether the insight has calendar events tonight:
 *  - [CHANNEL_TONIGHT_INSIGHT_DEFAULT] when events are present — default importance,
 *    plays the user's notification sound. The user is heading out somewhere; the
 *    summary is worth interrupting them for.
 *  - [CHANNEL_TONIGHT_INSIGHT_SILENT] when the evening is empty — low importance,
 *    silent. Still posted so the user can glance at the lock screen and see the
 *    overnight insight, but nothing audible.
 *
 * Tapping the notification opens MainActivity. POST_NOTIFICATIONS is checked before
 * posting; on Android 13+ a missing permission silently no-ops (the insight is
 * still cached and surfaced in-app the next time the user opens it).
 */
class TonightInsightNotifier(private val context: Context) {

    fun notify(
        insight: Insight,
        prose: String,
        topColors: Map<OutfitSuggestion.Top, Long> = emptyMap(),
        topStrokes: Map<OutfitSuggestion.Top, Long> = emptyMap(),
    ) {
        if (!NotificationPermission.isGranted(context)) return
        NotificationManagerCompat.from(context).notify(
            NOTIFICATION_ID_TONIGHT_INSIGHT,
            buildNotification(insight, prose, topColors, topStrokes),
        )
    }

    /**
     * Builds the tonight insight notification without posting. The worker
     * uses this to feed [androidx.work.ForegroundInfo] when it goes
     * foreground for a cast — the same notification doubles as the
     * foreground-service tile and the persistent insight notification once
     * the worker ends.
     *
     * Skips the POST_NOTIFICATIONS check on purpose: foreground-service
     * notifications are exempt from that gate.
     */
    fun buildNotification(
        insight: Insight,
        prose: String,
        topColors: Map<OutfitSuggestion.Top, Long> = emptyMap(),
        topStrokes: Map<OutfitSuggestion.Top, Long> = emptyMap(),
    ): android.app.Notification {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_NAVIGATE_TO_TODAY, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val channel = if (insight.hasEvents) CHANNEL_TONIGHT_INSIGHT_DEFAULT else CHANNEL_TONIGHT_INSIGHT_SILENT
        val priority = if (insight.hasEvents) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW

        val top = insight.outfit?.top
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(InsightNotifier.smallIconFor(top))
            .setLargeIcon(
                InsightNotifier.largeIconForTop(
                    context = context,
                    top = top,
                    customFillArgb = top?.let { topColors[it] },
                    customStrokeArgb = top?.let { topStrokes[it] },
                ),
            )
            .setContentTitle(context.getString(R.string.notification_tonight_insight_title))
            .setContentText(prose)
            .setStyle(NotificationCompat.BigTextStyle().bigText(prose))
            .setPriority(priority)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .apply {
                // Belt-and-braces: even if a downstream OEM ignores the silent
                // channel's importance, the per-notification flag still suppresses
                // sound + heads-up for the no-events case.
                if (!insight.hasEvents) setSilent(true)
            }
            .build()
    }

    companion object {
        const val NOTIFICATION_ID_TONIGHT_INSIGHT = 1003
        private const val REQUEST_OPEN_APP = 102
    }
}
