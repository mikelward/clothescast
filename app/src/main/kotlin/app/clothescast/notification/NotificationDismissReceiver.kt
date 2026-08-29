package app.clothescast.notification

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.clothescast.diag.DiagLog

/**
 * Logs when one of the app's insight notifications is cleared by the user
 * (swipe-away or "clear all"). Wired via [androidx.core.app.NotificationCompat.Builder.setDeleteIntent]
 * on the daily / tonight insight posts.
 *
 * A delete intent fires *only* on a user-initiated clear — not on a tap (that
 * routes through the content intent + auto-cancel) and not when the app or the
 * system cancels the notification programmatically (e.g. a foreground-service
 * teardown removing its notification). So a forecast post that vanishes from the
 * shade *without* a matching "dismissed" line here was removed by something other
 * than the user. That distinction is exactly what the "notification disappears a
 * second after posting" report needs: pair the absence of a dismissal line with
 * the worker's active-notification snapshot to tell a user swipe from a teardown.
 */
class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "insight"
        DiagLog.i(TAG, "User dismissed the %s notification (id=%s).", label, id)
    }

    companion object {
        private const val TAG = "NotificationDismiss"
        private const val ACTION_DISMISSED = "app.clothescast.notification.DISMISSED"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val EXTRA_LABEL = "label"

        /**
         * Builds the delete-intent [PendingIntent] for a notification, carrying its
         * id + a short [label] so the dismissal log names which post was cleared.
         * [notificationId] doubles as the request code so daily (1001) and tonight
         * (1003) get distinct PendingIntents rather than clobbering each other
         * through FLAG_UPDATE_CURRENT.
         */
        fun deleteIntent(context: Context, notificationId: Int, label: String): PendingIntent {
            val intent = Intent(context, NotificationDismissReceiver::class.java).apply {
                action = ACTION_DISMISSED
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                putExtra(EXTRA_LABEL, label)
            }
            return PendingIntent.getBroadcast(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
