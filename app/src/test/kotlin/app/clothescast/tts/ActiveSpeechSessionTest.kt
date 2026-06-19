package app.clothescast.tts

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Job
import org.junit.Test

/**
 * Covers the token-scoped cancel contract that lets the spoken briefing
 * notification's dismiss action stop *its own* phone speaker — including a
 * dismissal that lands before playback starts — without touching a later
 * briefing's speech or the rest of delivery. Each test uses distinct tokens so
 * the process-wide singleton's state can't bleed between cases.
 */
class ActiveSpeechSessionTest {

    @Test
    fun `register without a prior dismissal returns true`() {
        ActiveSpeechSession.register(token = 1L, job = Job()) shouldBe true
    }

    @Test
    fun `stop cancels the speech registered under the same token`() {
        val job = Job()
        ActiveSpeechSession.register(token = 2L, job = job)

        ActiveSpeechSession.stop(token = 2L)

        job.isCancelled shouldBe true
    }

    @Test
    fun `stop with a stale token leaves the current speech playing`() {
        // A persisted older notification (token 3) is swiped while a newer
        // briefing (token 4) is speaking: the new briefing must keep playing.
        val current = Job()
        ActiveSpeechSession.register(token = 4L, job = current)

        ActiveSpeechSession.stop(token = 3L)

        current.isCancelled shouldBe false
        ActiveSpeechSession.stop(token = 4L) // cleanup
    }

    @Test
    fun `a dismissal before playback makes the matching register skip speech`() {
        // Real tokens are epoch-millis (well outside the JVM's cached-Long range),
        // so this also guards the remembered-dismissal lookup against comparing by
        // boxed identity instead of value.
        val token = 1_700_000_000_000L
        // The placeholder is swiped during the fetch/alignment wait, before
        // playPhoneSpeaker registers — the matching register then declines.
        ActiveSpeechSession.stop(token)

        ActiveSpeechSession.register(token, job = Job()) shouldBe false
    }

    @Test
    fun `a pre-playback dismissal of one run does not block a different run`() {
        ActiveSpeechSession.stop(token = 1_700_000_001_000L)

        ActiveSpeechSession.register(token = 1_700_000_002_000L, job = Job()) shouldBe true
    }

    @Test
    fun `a stale dismissal does not overwrite a pending one for a different run`() {
        // Epoch-millis tokens (non-cached Longs). The live run's placeholder is
        // swiped pre-register, then an unrelated older notification is swiped; the
        // live run must still see its own dismissal and skip speech — the stale
        // token must not clobber the remembered one.
        val live = 1_700_000_003_000L
        val stale = 1_700_000_004_000L
        ActiveSpeechSession.stop(live)
        ActiveSpeechSession.stop(stale)

        ActiveSpeechSession.register(live, job = Job()) shouldBe false
    }

    @Test
    fun `a late clear from an older run does not unregister a newer job`() {
        val old = Job()
        ActiveSpeechSession.register(token = 8L, job = old)
        val current = Job()
        ActiveSpeechSession.register(token = 9L, job = current)

        ActiveSpeechSession.clear(token = 8L, job = old)
        ActiveSpeechSession.stop(token = 9L)

        current.isCancelled shouldBe true
    }

    @Test
    fun `clearing the current run stops a later dismiss from cancelling it`() {
        val job = Job()
        ActiveSpeechSession.register(token = 10L, job = job)

        ActiveSpeechSession.clear(token = 10L, job = job)
        ActiveSpeechSession.stop(token = 10L)

        job.isCancelled shouldBe false
    }
}
