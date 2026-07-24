package app.clothescast.diag

import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * [BugReport.share] must never turn a failure while collecting the report into
 * another crash — the report is most useful right after something has already
 * gone wrong — must say so when neither delivery route landed, and must report
 * back whether anything was actually retained, because the post-crash banner
 * dismisses itself on that answer. The payload build, the screenshot capture,
 * the clipboard write, the chooser launch, and the main-thread hop are all
 * injected, so every outcome is drivable without a real window,
 * `ClipboardManager`, or share target.
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
            clipboardWrite = { _, text -> shared = text; true },
            chooserLaunch = { _, _, _ -> true },
        )

        shared.shouldNotBeNull()
        shared!! shouldContain "Report collection failed"
        shared!! shouldContain "--- Recent log"
    }

    @Test
    fun `a collection failure does not escape into the caller's scope`(): Unit = runBlocking {
        // The share runs from a tap; an escaping throwable would take the app
        // down — the one thing a bug-report path must never do.
        val retained = BugReport.share(
            activity,
            includeScreenshot = false,
            mainDispatcher = Dispatchers.Unconfined,
            payloadCollect = { _, _ -> throw OutOfMemoryError("simulated") },
            clipboardWrite = { _, _ -> true },
            chooserLaunch = { _, _, _ -> true },
        )

        retained shouldBe true
    }

    @Test
    fun `neither route landing tells the user and reports nothing retained`(): Unit = runBlocking {
        val retained = BugReport.share(
            activity,
            includeScreenshot = false,
            mainDispatcher = Dispatchers.Unconfined,
            payloadCollect = { _, _ -> "report" },
            clipboardWrite = { _, _ -> false },
            chooserLaunch = { _, _, _ -> false },
        )

        retained shouldBe false
        ShadowToast.getLatestToast().shouldNotBeNull()
    }

    @Test
    fun `a throwing clipboard or chooser is survivable and still reported`(): Unit = runBlocking {
        val retained = BugReport.share(
            activity,
            includeScreenshot = false,
            mainDispatcher = Dispatchers.Unconfined,
            payloadCollect = { _, _ -> "report" },
            clipboardWrite = { _, _ -> throw SecurityException("no clipboard access") },
            chooserLaunch = { _, _, _ -> throw IllegalStateException("no share target") },
        )

        retained shouldBe false
        ShadowToast.getLatestToast().shouldNotBeNull()
    }

    @Test
    fun `an opened chooser without a clipboard copy is not a retained report`(): Unit = runBlocking {
        // The sheet can still be backed out of, and ACTION_SEND gives no
        // delivery callback — so the crash banner must stay up for a retry.
        val retained = BugReport.share(
            activity,
            includeScreenshot = false,
            mainDispatcher = Dispatchers.Unconfined,
            payloadCollect = { _, _ -> "report" },
            clipboardWrite = { _, _ -> false },
            chooserLaunch = { _, _, _ -> true },
        )

        retained shouldBe false
        ShadowToast.getLatestToast() shouldBe null
    }

    @Test
    fun `a text-only share never captures a screenshot`(): Unit = runBlocking {
        var captured = false

        BugReport.share(
            activity,
            includeScreenshot = false,
            mainDispatcher = Dispatchers.Unconfined,
            payloadCollect = { _, _ -> "report" },
            screenshotCapture = { captured = true; null },
            clipboardWrite = { _, _ -> true },
            chooserLaunch = { _, _, _ -> true },
        )

        // The post-crash banner shares text-only: the screen visible now is from
        // a different run than the crash.
        captured shouldBe false
    }
}
