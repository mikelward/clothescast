package app.clothescast.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.clothescast.diag.DiagLog
import app.clothescast.tts.ActiveSpeechSession

/**
 * Delete-intent target for the spoken-forecast notification. When the user
 * swipes the briefing notification away (Android 14+ lets a foreground-service
 * notification be dismissed), this cancels the on-device speech so the phone
 * stops talking — without aborting the rest of the delivery. The MQTT and cast
 * publishes run as independent jobs, so they keep going; see [ActiveSpeechSession].
 *
 * The intent carries the run's [EXTRA_TOKEN] so dismissing a *stale* notification
 * (one left in the shade after its own briefing finished) only stops speech when
 * its token is the one currently playing — swiping yesterday's notification can't
 * cut off tonight's briefing.
 */
class SpeechDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISMISS) return
        val token = intent.getLongExtra(EXTRA_TOKEN, 0L)
        DiagLog.i(TAG, "Spoken-forecast notification dismissed (token=$token); stopping its speech if still playing.")
        ActiveSpeechSession.stop(token)
    }

    companion object {
        const val ACTION_DISMISS = "app.clothescast.notification.DISMISS_SPEECH"
        const val EXTRA_TOKEN = "token"
        private const val TAG = "SpeechDismissReceiver"
    }
}
