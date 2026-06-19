package app.clothescast.tts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/**
 * Process-wide handle to the coroutine currently playing a scheduled briefing on
 * the phone speaker, so the spoken-forecast notification's dismiss action can stop
 * *just the speech* without touching the rest of the delivery.
 *
 * The phone-speaker coroutine [register]s its own [Job] (keyed by a per-run
 * [token]) for the duration of playback and [clear]s it when done.
 * [SpeechDismissReceiver] (wired to the notification's delete intent) calls
 * [stop] with the *dismissed notification's* token, which cancels the job only
 * when that token is the one currently playing. That scoping matters because a
 * spoken notification can persist in the shade after its own briefing ends: a
 * later briefing then registers a different token, so swiping the old
 * notification is a no-op rather than cancelling the new briefing's speech.
 *
 * A dismissal can also arrive *before* playback starts — the "Preparing…"
 * placeholder is swipeable on Android 14+ during the fetch/alignment wait. Such a
 * [stop] is remembered (per token) so the matching [register] returns false and
 * the briefing skips speech rather than talking over a dismissal. The remembered
 * set is bounded so a stream of stale-notification swipes can't grow it without
 * limit, and keyed by token so an unrelated stale swipe can't clobber the live
 * run's pending dismissal.
 *
 * Cancelling the matched job propagates into the suspending `speak()` / `play()`
 * call and tears down the AudioTrack / TextToSpeech engine via their
 * `invokeOnCancellation` hooks, exactly like the Today screen's Stop button does.
 * Because the MQTT and cast publishes run as sibling jobs in the delivery
 * `supervisorScope`, they are unaffected: dismissing the phone notification means
 * "stop talking on *this* phone," not "abort delivery."
 */
object ActiveSpeechSession {
    private data class Session(val token: Long, val job: Job)

    private val lock = Any()
    private var current: Session? = null

    // Tokens dismissed before their speech registered (placeholder swiped during
    // the fetch/alignment wait), newest last. A bounded LRU: a dismissal for a
    // run that never registers (e.g. a persisted older notification swiped) would
    // otherwise linger forever. A live run registers far inside this cap.
    private val dismissedBeforeStart = LinkedHashSet<Long>()

    /**
     * Record [job] (identified by [token]) as the speech about to play. Returns
     * false — and does *not* register — when this run was already dismissed
     * before playback started, signalling the caller to skip speech.
     */
    fun register(token: Long, job: Job): Boolean = synchronized(lock) {
        if (dismissedBeforeStart.remove(token)) return false
        current = Session(token, job)
        true
    }

    /**
     * Cancel the playing speech *only if* it's the run identified by [token] —
     * i.e. the notification the user just dismissed is the one currently
     * speaking. If that run hasn't started speaking yet (placeholder dismissal),
     * remember the token so its upcoming [register] skips playback. A stale token
     * or no active speech is a safe no-op.
     */
    fun stop(token: Long) {
        val toCancel: Job? = synchronized(lock) {
            val session = current
            if (session != null && session.token == token) {
                current = null
                session.job
            } else {
                rememberDismissal(token)
                null
            }
        }
        // Cancel outside the lock so a cancellation callback can't reenter under it.
        toCancel?.cancel(CancellationException("Spoken forecast notification dismissed"))
    }

    /** Clear this run's registration once its playback finishes, if still current. */
    fun clear(token: Long, job: Job) = synchronized(lock) {
        val session = current
        if (session != null && session.token == token && session.job === job) {
            current = null
        }
    }

    private fun rememberDismissal(token: Long) {
        // Re-insert at the tail (LRU) and evict the eldest past the cap.
        dismissedBeforeStart.remove(token)
        dismissedBeforeStart.add(token)
        while (dismissedBeforeStart.size > MAX_REMEMBERED_DISMISSALS) {
            val eldest = dismissedBeforeStart.iterator()
            eldest.next()
            eldest.remove()
        }
    }

    // Generous headroom — only one briefing's placeholder is "live" at a time,
    // and reaching the cap needs that many stale swipes inside one fetch window.
    private const val MAX_REMEMBERED_DISMISSALS = 16
}
