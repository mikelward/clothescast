package app.clothescast.data

import app.clothescast.diag.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Runs settings writes one at a time, in the order they were submitted.
 *
 * A pinch and then a slider drag, or two quick pinches, each settle a text
 * size. Launched independently on a multi-threaded scope, those writes can
 * reach DataStore out of order, leaving the earlier size stored last -- and the
 * screen then snaps back to it from the preferences flow (Codex, PR #1273).
 * One consumer draining an unbounded FIFO keeps every write and lands the
 * newest last. [submit] never suspends, so it is safe from a click handler.
 */
internal class OrderedWrites(scope: CoroutineScope, private val tag: String) {
    private class Write(val what: String, val block: suspend () -> Unit)

    private val queue = Channel<Write>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (write in queue) {
                try {
                    write.block()
                } catch (e: IOException) {
                    // A refused write leaves the previous value stored; the
                    // flow keeps reporting it, so the screen shows what was
                    // actually saved rather than what failed to be.
                    DiagLog.w(tag, e, "Saving the %s failed", write.what)
                }
            }
        }
    }

    fun submit(what: String, block: suspend () -> Unit) {
        // An unlimited channel refuses a send only once closed, and this one
        // is never closed -- but say so rather than drop a write silently.
        val sent = queue.trySend(Write(what, block))
        if (sent.isFailure) DiagLog.w(tag, sent.exceptionOrNull(), "Dropped a %s write: queue closed", what)
    }
}
