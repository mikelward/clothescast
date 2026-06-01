package app.clothescast.notification

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
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

    // POST_NOTIFICATIONS is checked via NotificationPermission.isGranted() at the
    // top of this method (an early return), but lint can't trace the permission
    // check through that helper to the notify() call below — so it flags a false
    // positive. The guard makes the post safe on Android 13+.
    @SuppressLint("MissingPermission")
    fun notify(
        insight: Insight,
        prose: String,
        topColors: Map<OutfitSuggestion.Top, Long> = emptyMap(),
        topStrokes: Map<OutfitSuggestion.Top, Long> = emptyMap(),
        handsColors: Map<OutfitSuggestion.Hands, Long> = emptyMap(),
    ) {
        if (!NotificationPermission.isGranted(context)) return

        val pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            MainActivity.todayTapIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val channel = if (insight.hasEvents) CHANNEL_TONIGHT_INSIGHT_DEFAULT else CHANNEL_TONIGHT_INSIGHT_SILENT
        val priority = if (insight.hasEvents) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW

        val top = insight.outfit?.top
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(InsightNotifier.smallIconFor(top))
            .setLargeIcon(
                InsightNotifier.largeIconForTop(
                    context = context,
                    top = top,
                    hands = insight.outfit?.hands,
                    customFillArgb = top?.let { topColors[it] },
                    customStrokeArgb = top?.let { topStrokes[it] },
                    handsFillArgb = insight.outfit?.hands?.let { handsColors[it] },
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

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_TONIGHT_INSIGHT, notification)
    }

    companion object {
        const val NOTIFICATION_ID_TONIGHT_INSIGHT = 1003
        private const val REQUEST_OPEN_APP = 102
    }
}
