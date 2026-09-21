package app.clothescast.diag

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mikelward.androidlog.android.ShareOutcome
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * [BugReport.share] must never turn a failure while collecting the report into
 * another crash — the report is most useful right after something has already
 * gone wrong — must say so when the shared library reports nothing landed, and
 * must attach the screenshot only when one was asked for. The payload build, the
 * screenshot capture, the library hand-off (`DebugReport.deliver`) and the
 * main-thread hop are all injected, so every outcome is drivable without a real
 * window, `ClipboardManager`, or share target.
 *
 * The rule that the previous run is consumed only once the clipboard copy lands
 * — so a share the user backed out of leaves the crash banner up — now lives in
 * the library (`DebugReport.settle`) and is tested there.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BugReportShareTest {

    private val activity: ComponentActivity =
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    @After
    fun tearDown() {
        ShadowToast.reset()
    }

    @Test
    fun `a failed payload collection still shares a report carrying the recent log`(): Unit = runBlocking {
        DiagLog.i("BugReportShareTest", "a marker line")
        var shared: String? = null

        BugReport.share(
            activity,
            includeScreenshot = false,
            mainDispatcher = Dispatchers.Unconfined,
            payloadCollect = { _, _ -> error("preferences unreadable") },
            deliverReport = { _, report, _ -> shared = report.text; ShareOutcome.SHARED },
        )

        shared.shouldNotBeNull()
        shared!! shouldContain "Report collection failed"
        shared!! shouldContain "--- Recent log"
    }

    @Test
    fun `a collection failure does not escape into the caller's scope`(): Unit = runBlocking {
        // The share runs from a tap; an escaping throwable would take the app
        // down — the one thing a bug-report path must never do.
        val delivered = BugReport.share(
            activity,
            includeScreenshot = false,
            mainDispatcher = Dispatchers.Unconfined,
            payloadCollect = { _, _ -> throw OutOfMemoryError("simulated") },
            deliverReport = { _, _, _ -> ShareOutcome.SHARED },
        )

        delivered shouldBe true
    }

    @Test
    fun `nothing landing tells the user and reports nothing delivered`(): Unit = runBlocking {
        val delivered = BugReport.share(
            activity,
            includeScreenshot = false,
            mainDispatcher = Dispatchers.Unconfined,
            payloadCollect = { _, _ -> "report" },
            deliverReport = { _, _, _ -> ShareOutcome.FAILED },
        )

        delivered shouldBe false
        ShadowToast.getLatestToast().shouldNotBeNull()
    }

    @Test
    fun `a throwing delivery is survivable and still reported`(): Unit = runBlocking {
        val delivered = BugReport.share(
            activity,
            includeScreenshot = false,
            mainDispatcher = Dispatchers.Unconfined,
            payloadCollect = { _, _ -> "report" },
            deliverReport = { _, _, _ -> throw IllegalStateException("no share target") },
        )

        delivered shouldBe false
        ShadowToast.getLatestToast().shouldNotBeNull()
    }

    @Test
    fun `a delivered report is not flagged as failed`(): Unit = runBlocking {
        // COPIED_ONLY: the clipboard has it but no chooser opened — delivered, and
        // said so without the failure toast, because the report did reach the user.
        val delivered = BugReport.share(
            activity,
            includeScreenshot = false,
            mainDispatcher = Dispatchers.Unconfined,
            payloadCollect = { _, _ -> "report" },
            deliverReport = { _, _, _ -> ShareOutcome.COPIED_ONLY },
        )

        delivered shouldBe true
        ShadowToast.getLatestToast() shouldBe null
    }

    @Test
    fun `a share that outlives its screen still opens the chooser`(): Unit = runBlocking {
        // The share runs on the application scope, so the Activity that started
        // it can be gone by the time the chooser launches. deliverReport starts
        // from a torn-down Activity's application context so the token is live,
        // and the library launches with NEW_TASK — exercised through the real
        // DebugReport.deliver here rather than a seam.
        activity.finish()

        BugReport.share(
            activity,
            includeScreenshot = false,
            mainDispatcher = Dispatchers.Unconfined,
            payloadCollect = { _, _ -> "report" },
        )

        shadowOf(activity.application).nextStartedActivity.shouldNotBeNull()
    }

    @Test
    fun `a captured screenshot is handed to the library`(): Unit = runBlocking {
        val shot = Uri.parse("content://app.clothescast.fileprovider/bug-reports/screenshot-1.png")
        var delivered: Uri? = null

        BugReport.share(
            activity,
            includeScreenshot = true,
            mainDispatcher = Dispatchers.Unconfined,
            payloadCollect = { _, _ -> "report" },
            screenshotCapture = { shot },
            deliverReport = { _, _, uri -> delivered = uri; ShareOutcome.SHARED },
        )

        delivered shouldBe shot
    }

    @Test
    fun `a text-only share never captures a screenshot and attaches none`(): Unit = runBlocking {
        var captured = false
        var delivered: Uri? = Uri.parse("content://sentinel")

        BugReport.share(
            activity,
            includeScreenshot = false,
            mainDispatcher = Dispatchers.Unconfined,
            payloadCollect = { _, _ -> "report" },
            screenshotCapture = { captured = true; null },
            deliverReport = { _, _, uri -> delivered = uri; ShareOutcome.SHARED },
        )

        // The post-crash banner shares text-only: the screen visible now is from
        // a different run than the crash.
        captured shouldBe false
        delivered shouldBe null
    }
}
