package app.clothescast.diag

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Turning telemetry off has to stop collection *and* discard what was already
 * collected. Crashlytics honors the flag only from the next launch and keeps
 * captured reports on disk, so stopping collection alone leaves a crash caught
 * before the opt-out free to upload after it.
 *
 * The deletion is recorded as a durable debt because it is the half a process
 * death can lose — see `Telemetry.applyTelemetryChoice`.
 */
class TelemetryDisableTest {

    private val calls = mutableListOf<String>()
    private var safeToEnable = true

    private suspend fun apply(enabled: Boolean, discardOwed: Boolean = false) =
        Telemetry.applyTelemetryChoice(
            enabled = enabled,
            discardOwed = discardOwed,
            setAnalyticsCollectionEnabled = { calls += "analytics=$it" },
            setCrashlyticsCollectionEnabled = { calls += "crashlytics=$it" },
            deleteUnsentReports = { calls += "deleteUnsentReports" },
            resetAnalyticsData = { calls += "resetAnalyticsData" },
            clearDiscardOwed = { calls += "clearDiscardOwed" },
            safeToEnable = { safeToEnable },
        )

    @Test
    fun `turning it off stops collection before it discards`() = runTest {
        apply(enabled = false, discardOwed = true)

        // Stopping first is what a kill between the steps needs: both SDKs
        // persist their collection flag, so the next launch's
        // FirebaseInitProvider starts disabled and cannot upload the report
        // this had not yet deleted. The debt is written with the opt-out
        // itself, in SettingsRepository, not here.
        assertEquals(
            listOf(
                "analytics=false",
                "crashlytics=false",
                "deleteUnsentReports",
                "resetAnalyticsData",
                "clearDiscardOwed",
            ),
            calls,
        )
    }

    @Test
    fun `a failed durable write does not take the collector with it`() = runTest {
        // Every durable step here is DataStore I/O. Letting an IOException
        // escape ends the only subscription to the user's choice, leaving the
        // switch reading on with both SDKs stopped and every later toggle
        // ignored for the process (Codex, PR #1161). The caller catches; what
        // this pins is that the failure is not swallowed inside, and that the
        // debt survives so the next launch retries.
        val boom = IOException("no space left on device")
        var thrown: IOException? = null
        try {
            Telemetry.applyTelemetryChoice(
                enabled = false,
                discardOwed = true,
                setAnalyticsCollectionEnabled = { calls += "analytics=$it" },
                setCrashlyticsCollectionEnabled = { calls += "crashlytics=$it" },
                deleteUnsentReports = { calls += "deleteUnsentReports" },
                resetAnalyticsData = { calls += "resetAnalyticsData" },
                clearDiscardOwed = { throw boom },
            )
        } catch (e: IOException) {
            thrown = e
        }

        assertEquals(boom, thrown)
        // Collection was stopped before the write that failed, which is the
        // half that matters: the user is not left collecting.
        assertEquals(
            listOf("analytics=false", "crashlytics=false", "deleteUnsentReports", "resetAnalyticsData"),
            calls,
        )
    }

    @Test
    fun `a failed clear puts the flags back rather than leaving them on`() = runTest {
        // The clear happens after the enable, so a throw there would otherwise
        // leave collection persisted on with the deletion still owed — and the
        // collector will not retry, because `distinctUntilChanged` drops the
        // unchanged choice. The next launch's provider would then start
        // collecting before the discharge (Codex, PR #1161).
        val boom = IOException("no space left on device")
        var thrown: IOException? = null
        try {
            Telemetry.applyTelemetryChoice(
                enabled = true,
                discardOwed = true,
                setAnalyticsCollectionEnabled = { calls += "analytics=$it" },
                setCrashlyticsCollectionEnabled = { calls += "crashlytics=$it" },
                deleteUnsentReports = { calls += "deleteUnsentReports" },
                resetAnalyticsData = { calls += "resetAnalyticsData" },
                clearDiscardOwed = { throw boom },
            )
        } catch (e: IOException) {
            thrown = e
        }

        assertEquals(boom, thrown)
        // Enabled, then put straight back: what persists is "off, still owed",
        // so the next launch discharges before it collects.
        assertEquals(
            listOf(
                "analytics=false",
                "crashlytics=false",
                "deleteUnsentReports",
                "resetAnalyticsData",
                "analytics=true",
                "crashlytics=true",
                "analytics=false",
                "crashlytics=false",
            ),
            calls,
        )
    }

    @Test
    fun `an opted-out launch with nothing owed only re-asserts the flags`() = runTest {
        // Reporting is opt-in, so this is now the common path: every launch of
        // an install that has never turned it on. Running the purge here would
        // put an analytics reset and a DataStore write on the startup path for
        // the majority of users, to delete reports that were never collected.
        // The two flag calls stay — they are idempotent, and they are what the
        // next launch's FirebaseInitProvider reads.
        apply(enabled = false, discardOwed = false)

        assertEquals(listOf("analytics=false", "crashlytics=false"), calls)
    }

    @Test
    fun `an already-opted-in launch enables and does nothing else`() = runTest {
        apply(enabled = true)

        // Discarding on the way in would throw away reports from a period the
        // user had already consented to. Nothing is replayed either: the
        // enable path deliberately restores no analytics state, because making
        // that exactly-once across process deaths and three independent
        // startup collectors produced five findings and is not worth its cost
        // (Codex, PR #1161; `TODO.md`).
        assertEquals(listOf("analytics=true", "crashlytics=true"), calls)
    }

    @Test
    fun `a rapid off-on discharges the debt before collection resumes`() = runTest {
        // The off can be conflated away entirely, so the disabled transition
        // never runs and the SDK flags are still enabled from before it. The
        // debt is the only record that the off period happened, which is why
        // it is written with the choice rather than by this function.
        apply(enabled = true, discardOwed = true)

        assertEquals(
            listOf(
                "analytics=false",
                "crashlytics=false",
                "deleteUnsentReports",
                "resetAnalyticsData",
                "analytics=true",
                "crashlytics=true",
                // Last, after the enable it guards — a death between the two
                // must leave the debt standing rather than a collecting
                // install with nothing owed (Codex, PR #1161).
                "clearDiscardOwed",
            ),
            calls,
        )
    }

    @Test
    fun `a launch inheriting a debt discharges it even though the choice reads on`() = runTest {
        // The case the durable record exists for: an earlier run stored the
        // opt-in but died before the delete. The preference alone reads
        // "enabled, nothing owed" and the off period's reports are still there.
        apply(enabled = true, discardOwed = true)

        assertEquals("the delete runs", 1, calls.count { it == "deleteUnsentReports" })
        assertEquals(
            "and collection only resumes after it",
            calls.indexOf("deleteUnsentReports") < calls.indexOf("analytics=true"),
            true,
        )
        // The debt is retired last, after the enable it guards. Clearing it
        // first left a death between the enable and the clear looking like
        // "collecting, nothing owed" on the next launch — free to release the
        // pre-consent report (Codex, PR #1161).
        assertEquals(
            "and the debt is retired only after that",
            calls.indexOf("analytics=true") < calls.indexOf("clearDiscardOwed"),
            true,
        )
    }

    @Test
    fun `a choice landing during the discharge stops the enable`() = runTest {
        // discharge() suspends — a DataStore edit and two SDK calls — so the
        // user can cross the consent line while it is in flight, and this
        // transition is stale by the time it would enable. Two shapes end
        // here, which is why the re-read is of the whole choice rather than
        // the `enabled` half:
        //
        //  - An opt-out. The flags persist across the process, so turning them
        //    on would leave an opted-out user collecting.
        //  - An off then a quick on. The final choice is still enabled, so an
        //    `enabled`-only check passes, but the newer debt the generation
        //    check preserved is still owed — enabling would resume collection
        //    with a report from the opted-out gap still held (Codex, PR #1161).
        //
        // Either way collection stays stopped and the debt stands, and the
        // pending emission discharges it and enables then.
        safeToEnable = false

        apply(enabled = true, discardOwed = true)

        assertEquals(
            listOf(
                "analytics=false",
                "crashlytics=false",
                "deleteUnsentReports",
                "resetAnalyticsData",
                // No clear: the return happens before it, so the debt stands
                // for the pending emission to discharge. That is the point —
                // a superseded pass must not retire a debt it is abandoning.
            ),
            calls,
        )
    }
}
