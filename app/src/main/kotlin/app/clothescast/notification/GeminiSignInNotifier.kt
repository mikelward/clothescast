package app.clothescast.notification

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.clothescast.MainActivity
import app.clothescast.R
import app.clothescast.diag.DiagLog

/**
 * Tells a signed-out user, once, that the free Gemini voice now needs Google
 * sign-in and that the device voice is speaking instead. Without it a user
 * who picked Gemini on an older build would hear the voice change with no
 * reason given (SPEC.md → "Accounts and the developer-funded paths").
 */
class GeminiSignInNotifier(private val context: Context) {

    /**
     * Posts the notice, opening Voice settings on tap. False when the user
     * wouldn't see it — no permission, the app's notifications off, or the
     * channel blocked — so the worker tries again on a later run rather than
     * recording a notice nobody got.
     */
    // Lint can't trace the permission check through NotificationPermission.
    @SuppressLint("MissingPermission")
    fun notify(): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!NotificationPermission.isGranted(context) || !manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannelCompat(CHANNEL_SCHEDULED_INSIGHT)
        if (channel != null && channel.importance == NotificationManagerCompat.IMPORTANCE_NONE) return false
        val pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            MainActivity.voiceTapIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = context.getString(R.string.notify_gemini_sign_in_text)
        val notification = NotificationCompat.Builder(context, CHANNEL_SCHEDULED_INSIGHT)
            .setSmallIcon(R.drawable.ic_notification_insight)
            .setContentTitle(context.getString(R.string.notify_gemini_sign_in_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        manager.notify(NOTIFICATION_ID_GEMINI_SIGN_IN, notification)
        DiagLog.i(TAG, "Posted the Gemini sign-in notice (id=%s).", NOTIFICATION_ID_GEMINI_SIGN_IN)
        return true
    }

    companion object {
        private const val TAG = "GeminiSignInNotifier"
        const val NOTIFICATION_ID_GEMINI_SIGN_IN = 1007
        private const val REQUEST_OPEN_APP = 104

        /**
         * Should this scheduled run post the notice? Only when the phone is
         * about to speak (so the device voice really stands in), the user
         * picked Gemini, has no key of their own, the build has the shared
         * voice, they aren't signed in, and they haven't been told yet. A
         * silent run keeps the notice for one that speaks.
         */
        fun needsNotice(
            phoneWillSpeak: Boolean,
            geminiSelected: Boolean,
            ownKeyConfigured: Boolean,
            sharedTtsConfigured: Boolean,
            signedIn: Boolean,
            alreadyShown: Boolean,
        ): Boolean = phoneWillSpeak && geminiSelected && !ownKeyConfigured && sharedTtsConfigured &&
            !signedIn && !alreadyShown
    }
}
