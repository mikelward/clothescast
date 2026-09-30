package app.clothescast.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.android.ProcessExits
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowActivityManager

/**
 * The start-up record of why earlier processes ended, driven through the real
 * platform query (Robolectric's `ActivityManager`) into a fresh [DebugLog], and
 * the bug report's read of it once a busy run has pushed it out of the ring.
 *
 * The reason and importance names are the shared library's, and its own suite
 * pins them; what this app owns is that the query runs, lands in the log the
 * report reads, and survives until the report is shared.
 */
@RunWith(RobolectricTestRunner::class)
class ProcessExitReasonsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun seedExit(reason: Int, description: String = "stopped by the installer") {
        val exitInfo = ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
            .setReason(reason)
            .setImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
            .setTimestamp(1_700_000_000_000L)
            .setDescription(description)
            .build()
        shadowOf(context.getSystemService(ActivityManager::class.java))
            .addApplicationExitInfo(exitInfo)
    }

    @Test
    fun recordsEachRecentExitPinnedWithItsReasonNamed() {
        // Without this the suite stays green if the collection is deleted, asks
        // for the wrong package, or drops its results on the floor — which is
        // the feature.
        seedExit(ApplicationExitInfo.REASON_CRASH)
        seedExit(ApplicationExitInfo.REASON_PACKAGE_UPDATED)
        val log = DebugLog()

        logRecentProcessExits(context, log)

        val pinned = log.pinnedSnapshot()
        val exits = pinned.filter { it.contains("processExit ") }
        exits.size shouldBe 2
        exits.any { it.contains("reason=crash ") } shouldBe true
        exits.any { it.contains("reason=packageUpdated") } shouldBe true
        exits.all { it.contains("importance=foreground") } shouldBe true
        // Kept on purpose: it can name the installer that stopped us.
        exits.all { it.contains("stopped by the installer") } shouldBe true
        pinned.any { it.contains("ownPackage lastUpdateTime=") } shouldBe true
    }

    @Test
    fun saysSoWhenThePlatformHasNoExitRecords() {
        // A fresh install, or a device that has pruned its records. The line
        // matters because its absence would otherwise be ambiguous with the
        // query having failed or never run.
        val log = DebugLog()

        logRecentProcessExits(context, log)

        log.pinnedSnapshot().any { it.contains("processExits none") } shouldBe true
    }

    @Test
    fun aFullBatchReachesTheReportAfterABusyRunHasPushedItOutOfTheRing() {
        repeat(ProcessExits.DEFAULT_MAX_RECORDS) {
            seedExit(ApplicationExitInfo.REASON_ANR, description = "x".repeat(1_000))
        }
        val log = DebugLog()
        logRecentProcessExits(context, log)
        repeat(DebugLog.DEFAULT_MAX_ENTRIES + 50) { log.event("busy %s", it) }
        // Only the pinned copy carries them now, so reading the ring alone loses them.
        log.snapshot().none { it.contains("processExit ") } shouldBe true

        val report = BugReport.reportLogLines(log)

        report.count { it.contains("processExit reason=anr") } shouldBe ProcessExits.DEFAULT_MAX_RECORDS
        report.any { it.contains("ownPackage lastUpdateTime=") } shouldBe true
        // Ahead of the recent lines, newest last.
        (report.indexOfLast { it.contains("processExit ") } <
            report.indexOfFirst { it.contains("busy ") }) shouldBe true
        report.last().endsWith("busy ${DebugLog.DEFAULT_MAX_ENTRIES + 49}") shouldBe true
    }

    @Test
    fun theReportReservesRoomForAWholeBatch() {
        // A constant so the fallback report doesn't load ProcessExits; it has
        // to keep up with the batch the library can write.
        (BugReport.MAX_PINNED_PAYLOAD_CHARS >= ProcessExits.maxBatchChars()) shouldBe true
    }
}
