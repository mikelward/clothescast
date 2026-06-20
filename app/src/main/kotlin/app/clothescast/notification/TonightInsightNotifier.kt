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
import app.clothescast.diag.DiagLog

/**
 * Posts the tonight insight as a system notification on the shared
 * [CHANNEL_SCHEDULED_INSIGHT] — the same channel the morning insight uses, so
 * a night-worker primary tonight slot reads exactly as well as a day-worker
 * primary morning slot.
 *
 * "Only notify on events" lives at the worker / [DeliveryGates] level: when
 * the user has it on and the evening has no events, the worker doesn't call
 * `notify()` at all (no quiet-channel fallback). That keeps the channel
 * design symmetric across periods and lets the user dial channel importance
 * once in system settings.
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
        outerColors: Map<OutfitSuggestion.Outer, Long> = emptyMap(),
    ) {
        if (!NotificationPermission.isGranted(context)) return

        val pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            MainActivity.todayTapIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val top = insight.outfit?.top
        val notification = NotificationCompat.Builder(context, CHANNEL_SCHEDULED_INSIGHT)
            .setSmallIcon(InsightNotifier.smallIconFor(top))
            .setLargeIcon(
                InsightNotifier.largeIconForTop(
                    context = context,
                    top = top,
                    hands = insight.outfit?.hands,
                    customFillArgb = top?.let { topColors[it] },
                    customStrokeArgb = top?.let { topStrokes[it] },
                    handsFillArgb = insight.outfit?.hands?.let { handsColors[it] },
                    outer = insight.outfit?.outer,
                    outerFillArgb = insight.outfit?.outer?.let { outerColors[it] },
                ),
            )
            .setContentTitle(context.getString(R.string.notification_tonight_insight_title))
            .setContentText(prose)
            .setStyle(NotificationCompat.BigTextStyle().bigText(prose))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setDeleteIntent(
                NotificationDismissReceiver.deleteIntent(context, NOTIFICATION_ID_TONIGHT_INSIGHT, "tonight insight"),
            )
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_TONIGHT_INSIGHT, notification)
        DiagLog.i(
            TAG,
            "Posted tonight insight notification (id=$NOTIFICATION_ID_TONIGHT_INSIGHT, channel=$CHANNEL_SCHEDULED_INSIGHT, hasEvents=${insight.hasEvents}).",
        )
    }

    companion object {
        private const val TAG = "TonightInsightNotifier"
        const val NOTIFICATION_ID_TONIGHT_INSIGHT = 1003
        private const val REQUEST_OPEN_APP = 102
    }
}
