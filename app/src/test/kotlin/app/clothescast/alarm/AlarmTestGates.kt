package app.clothescast.alarm

import androidx.test.core.app.ApplicationProvider
import app.clothescast.ClothesCastApplication
import kotlinx.coroutines.flow.first
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

/**
 * Blocks until [ClothesCastApplication]'s schedule-time observer has handled
 * the schedule currently in prefs, including the widget-chain reconcile an edit
 * triggers. Call it after any test code that changes the morning or tonight
 * time: with no widget placed that reconcile cancels both widget slots, so one
 * still in flight can wipe an alarm the test goes on to arm.
 *
 * [timeoutMillis] is a deadlock guard rather than a wait, as above.
 */
internal fun awaitScheduleObserver(timeoutMillis: Long = 10_000) {
    val app = ApplicationProvider.getApplicationContext<ClothesCastApplication>()
    runBlocking {
        withTimeout(timeoutMillis) {
            val prefs = app.settingsRepository.preferences.first()
            val current = prefs.schedule.time to prefs.tonightSchedule.time
            app.scheduleBoundariesHandled.first { it == current }
        }
    }
}
