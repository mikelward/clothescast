package app.clothescast.alarm

import androidx.test.core.app.ApplicationProvider
import app.clothescast.ClothesCastApplication
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Gates the alarm tests open on the two pieces of asynchronous work they race:
 * the Application's startup reconcile, and a broadcast's `goAsync` tail.
 *
 * Both used to be waited on by polling for their side effects on a five-second
 * deadline. That is a wait, not a gate: it cannot tell "not finished yet" from
 * "finished, and had nothing to do", so every test whose expected side effect
 * never arrives — the ordinary case, since both delivery slots are off by
 * default — paid the whole deadline and then asserted against state nothing
 * guaranteed was settled.
 */

/**
 * Blocks until [ClothesCastApplication.onCreate]'s scheduling coroutine has
 * finished, so its alarm cancels cannot land in the middle of a test.
 *
 * [timeoutMillis] is a deadlock guard rather than a wait — the reconcile is a
 * preferences read and two AlarmManager calls.
 */
internal fun awaitInitialScheduling(timeoutMillis: Long = 10_000) {
    val app = ApplicationProvider.getApplicationContext<ClothesCastApplication>()
    val job = app.initialSchedulingJob ?: return
    runBlocking { withTimeout(timeoutMillis) { job.join() } }
}

/**
 * Blocks until every broadcast started by a receiver in this package has run to
 * completion. See [ReceiverWork].
 */
internal fun awaitBroadcasts(timeoutMillis: Long = 10_000) {
    ReceiverWork.awaitIdle(timeoutMillis)
}
