package app.clothescast.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowActivityManager

/**
 * The exit reason is the whole diagnostic value here: it separates a failure of
 * ours from the system reclaiming the process, and a mapping that mislabels one
 * as the other makes the log confidently wrong rather than merely unhelpful.
 */
@RunWith(RobolectricTestRunner::class)
class ProcessExitReasonsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun seedExit(
        reason: Int,
        importance: Int = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
    ) {
        val exitInfo = ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
            .setReason(reason)
            .setImportance(importance)
            .setTimestamp(1_700_000_000_000L)
            .setDescription("stopped by the installer")
            .build()
        shadowOf(context.getSystemService(ActivityManager::class.java))
            .addApplicationExitInfo(exitInfo)
    }

    @Test
    fun namesTheReasonsThatSeparateOurFailuresFromThePlatformKillingUs() {
        // Ours to fix.
        exitReasonName(ApplicationExitInfo.REASON_CRASH) shouldBe "crash"
        exitReasonName(ApplicationExitInfo.REASON_CRASH_NATIVE) shouldBe "crashNative"
        exitReasonName(ApplicationExitInfo.REASON_ANR) shouldBe "anr"
        // Not ours — the system reclaiming or replacing the process. These are
        // the ones no in-process signal can see, which is why this exists.
        exitReasonName(ApplicationExitInfo.REASON_LOW_MEMORY) shouldBe "lowMemory"
        exitReasonName(ApplicationExitInfo.REASON_PACKAGE_UPDATED) shouldBe "packageUpdated"
        exitReasonName(ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE) shouldBe "packageStateChange"
        exitReasonName(ApplicationExitInfo.REASON_USER_REQUESTED) shouldBe "userRequested"
    }

    @Test
    fun keepsTheNumberOfAReasonItDoesNotRecognize() {
        // A platform addition should degrade to something still diagnosable
        // rather than collapsing into an indistinguishable "unknown", which the
        // platform already uses for a reason of its own.
        exitReasonName(9999) shouldBe "unrecognized(9999)"
        exitReasonName(ApplicationExitInfo.REASON_UNKNOWN) shouldBe "unknown"
    }

    @Test
    fun namesThePriorityAndroidAssignedTheProcess() {
        // A background reclaim is routine; foreground importance means the
        // system counted the process as user-aware work. Not proof of a
        // visible screen — the alarm receiver and the widget update both
        // reach it with nothing on screen, and on this app those are most of
        // what runs.
        val foreground = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        processImportanceName(foreground) shouldBe "foreground"
        processImportanceName(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED) shouldBe "cached"
        processImportanceName(ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE) shouldBe "gone"
        processImportanceName(7) shouldBe "unrecognized(7)"
    }

    @Test
    fun recordsEachRecentExitWithItsReasonNamed() {
        // The mapping tests above prove the names are right; this proves the
        // query actually runs and its answers reach the log. Without it the
        // suite stays green if the collection is deleted, asks for the wrong
        // package, or drops its results on the floor — which is the feature.
        DiagLog.install(context)
        seedExit(ApplicationExitInfo.REASON_CRASH)
        seedExit(ApplicationExitInfo.REASON_PACKAGE_UPDATED)

        logRecentProcessExits(context)

        val lines = DiagLog.snapshot().filter { it.contains("processExit ") }
        lines.count() shouldBe 2
        lines.any { it.contains("reason=crash") } shouldBe true
        lines.any { it.contains("reason=packageUpdated") } shouldBe true
        lines.all { it.contains("importance=foreground") } shouldBe true
    }

    @Test
    fun saysSoWhenThePlatformHasNoExitRecords() {
        // A fresh install, or a device that has pruned its records. The line
        // matters because its absence would otherwise be ambiguous with the
        // query having failed or never run.
        DiagLog.install(context)

        logRecentProcessExits(context)

        DiagLog.snapshot().any { it.contains("processExits none") } shouldBe true
    }
}
