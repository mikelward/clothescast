package app.clothescast.alarm

import android.content.BroadcastReceiver
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Runs the asynchronous tail of an alarm broadcast.
 *
 * Every receiver in this package does its real work after `goAsync()`, off the
 * main thread, so `onReceive` returns long before the work is done. Routing all
 * of them through one scope gives a test something to *join*: without that, the
 * only way to know a broadcast had finished was to poll for its side effects on
 * a deadline, which cannot tell "not finished yet" from "finished, and had
 * nothing to do" — the ordinary case, since both delivery slots are off by
 * default. Each such wait cost its full deadline.
 *
 * The scope is process-lifetime and is never cancelled: a broadcast's work must
 * outlive the `onReceive` that started it, and there is no later moment that
 * owns tearing it down. [SupervisorJob] keeps one failing broadcast from
 * cancelling another's work, matching the per-receiver scopes this replaces.
 */
internal object ReceiverWork {
    private val parent = SupervisorJob()
    private val scope = CoroutineScope(parent + Dispatchers.Default)

    /**
     * Runs [body] off the main thread and finishes [pending] when it returns,
     * however it returns. Callers keep their own `try`/`catch`: what a failure
     * means — re-arm, fall back, give up — is the receiver's business, not this
     * helper's.
     *
     * [pending] is nullable because `goAsync()` returns null for a receiver
     * invoked directly rather than dispatched by the system — every test in
     * this package does exactly that. The per-receiver version of this code
     * called `finish()` on it unconditionally and threw a NullPointerException
     * on every one of those broadcasts, inside a coroutine whose SupervisorJob
     * swallowed it.
     */
    fun launch(pending: BroadcastReceiver.PendingResult?, body: suspend CoroutineScope.() -> Unit): Job =
        scope.launch {
            try {
                body()
            } finally {
                pending?.finish()
            }
        }

    /**
     * Blocks until no broadcast work is outstanding.
     *
     * Joins in a loop because a broadcast's coroutine may start another before
     * it ends. [timeoutMillis] is a deadlock guard, not a wait: a broadcast that
     * has not settled by then is a bug in the test or the code under test, so
     * this throws rather than letting the caller assert against half-done work.
     */
    @VisibleForTesting
    fun awaitIdle(timeoutMillis: Long = 10_000) {
        runBlocking {
            withTimeout(timeoutMillis) {
                while (true) {
                    val outstanding = parent.children.toList()
                    if (outstanding.isEmpty()) return@withTimeout
                    outstanding.forEach { it.join() }
                }
            }
        }
    }
}
