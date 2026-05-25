package app.clothescast.ui.today

import androidx.work.Data
import androidx.work.WorkInfo
import app.clothescast.work.FetchAndNotifyWorker
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Selection-logic tests for the Today screen's work status banner.
 *
 * The interesting cases are around WorkManager keeping multiple WorkInfos for
 * the same unique-work name: a stale FAILED entry from days ago must not mask
 * a fresh SUCCEEDED entry, which was the bug behind PR
 * `claude/fix-forecast-api-error` ("the error seems to persist on the screen
 * even after it worked").
 *
 * Pure JVM — no Robolectric, no Compose. Operates on [WorkInfoLite] (the
 * just-the-fields-we-need view) so the tests are insulated from the exact
 * shape of WorkManager's [WorkInfo] constructor across versions.
 */
class TodayWorkStatusSelectionTest {

    @Test
    fun `empty list is idle`() {
        selectStatus(emptyList()) shouldBe WorkStatus.Idle
    }

    @Test
    fun `running entry surfaces as Running on a fresh enqueue`() {
        selectStatus(listOf(active(WorkInfo.State.RUNNING, runAttemptCount = 0))) shouldBe WorkStatus.Running
    }

    @Test
    fun `first execution (runAttemptCount = 1) surfaces as Running, not Retrying`() {
        // WorkManager sets runAttemptCount = 1 when a worker starts its first
        // execution. The old check (> 0) incorrectly showed "Last attempt failed"
        // on every first launch.
        selectStatus(listOf(active(WorkInfo.State.RUNNING, runAttemptCount = 1))) shouldBe WorkStatus.Running
    }

    @Test
    fun `running entry with attempt count above one surfaces as Retrying`() {
        // After Result.retry(), WorkManager bumps runAttemptCount and parks the
        // WorkInfo back in ENQUEUED until backoff elapses. The banner should
        // tell the user "last attempt failed" rather than pretend it's a
        // brand-new fetch. runAttemptCount reaches 2 on the second attempt.
        selectStatus(listOf(active(WorkInfo.State.ENQUEUED, runAttemptCount = 2))) shouldBe WorkStatus.Retrying
    }

    @Test
    fun `active entry wins over terminal history`() {
        // A SUCCEEDED entry from earlier shouldn't suppress the spinner on a new run.
        val infos = listOf(
            terminalSuccess(completedAt = 1_000L),
            active(WorkInfo.State.RUNNING, runAttemptCount = 0),
        )
        selectStatus(infos) shouldBe WorkStatus.Running
    }

    @Test
    fun `freshly succeeded run hides a previous failure`() {
        // The bug: maxByOrNull(runAttemptCount) was picking the old failure
        // because its retry count was higher than the manual refresh's.
        // Now we sort by completion time, and the fresh success wins.
        val infos = listOf(
            terminalFailure(
                reason = FetchAndNotifyWorker.REASON_UNEXPECTED_HTTP,
                detail = "503",
                runAttemptCount = 4,
                completedAt = 1_000L,
            ),
            terminalSuccess(runAttemptCount = 0, completedAt = 2_000L),
        )
        selectStatus(infos) shouldBe WorkStatus.Idle
    }

    @Test
    fun `most recent failure wins over an older success`() {
        // The opposite case: today's run failed, yesterday's succeeded — surface today's.
        val infos = listOf(
            terminalSuccess(completedAt = 1_000L),
            terminalFailure(
                reason = FetchAndNotifyWorker.REASON_UNHANDLED,
                detail = "kaboom",
                completedAt = 2_000L,
            ),
        )
        selectStatus(infos) shouldBe WorkStatus.Failed(
            reason = FetchAndNotifyWorker.REASON_UNHANDLED,
            detail = "kaboom",
        )
    }

    @Test
    fun `pre-upgrade entries tie at zero and SUCCEEDED breaks the tie`() {
        // Migration scenario: WorkInfos from before this change have no
        // KEY_COMPLETED_AT (defaults to 0). A new SUCCEEDED tied at 0 with an
        // old FAILED should still hide the failure — otherwise users on
        // upgrade would see a stale "Last attempt failed" banner until their
        // history rolled over.
        val infos = listOf(
            terminalFailure(
                reason = FetchAndNotifyWorker.REASON_UNHANDLED,
                detail = null,
                runAttemptCount = 3,
                completedAt = 0L,
            ),
            terminalSuccess(runAttemptCount = 0, completedAt = 0L),
        )
        selectStatus(infos) shouldBe WorkStatus.Idle
    }

    @Test
    fun `active entry past fetch_complete reads as Idle`() {
        // Once the worker has signalled fetch + cache are done (progress
        // KEY_FETCH_COMPLETE = true), the banner should hide even though
        // the WorkInfo is still RUNNING — the remaining time is alignment
        // wait + notification post + TTS playback, and we don't want
        // "Fetching" to linger while TTS speaks.
        val infos = listOf(
            WorkInfoLite(
                state = WorkInfo.State.RUNNING,
                runAttemptCount = 1,
                outputData = Data.EMPTY,
                progress = Data.Builder()
                    .putBoolean(FetchAndNotifyWorker.KEY_FETCH_COMPLETE, true)
                    .build(),
            ),
        )
        selectStatus(infos) shouldBe WorkStatus.Idle
    }

    @Test
    fun `active entry before fetch_complete still reads as Running`() {
        // Sanity-check the inverse: a RUNNING entry with no progress flag
        // (or the flag absent) keeps the spinner up.
        selectStatus(
            listOf(
                WorkInfoLite(
                    state = WorkInfo.State.RUNNING,
                    runAttemptCount = 1,
                    outputData = Data.EMPTY,
                    progress = Data.Builder()
                        .putBoolean(FetchAndNotifyWorker.KEY_FETCH_COMPLETE, false)
                        .build(),
                ),
            ),
        ) shouldBe WorkStatus.Running
    }

    @Test
    fun `deliver-phase retry with fetch_complete still surfaces as Retrying`() {
        // Regression guard: if deliver() throws a retryable exception
        // after the worker called setProgress(KEY_FETCH_COMPLETE=true)
        // (network blip during notification post / MQTT / cast / TTS),
        // WorkManager re-enqueues the same WorkSpec with
        // runAttemptCount > 1. If the progress flag is still in place
        // and we excluded the entry from the active set on that basis
        // alone, the "last attempt failed — retrying" banner would
        // silently disappear while another attempt was actually pending.
        // The runAttemptCount > 1 carve-out keeps the entry active.
        val infos = listOf(
            WorkInfoLite(
                state = WorkInfo.State.ENQUEUED,
                runAttemptCount = 2,
                outputData = Data.EMPTY,
                progress = Data.Builder()
                    .putBoolean(FetchAndNotifyWorker.KEY_FETCH_COMPLETE, true)
                    .build(),
            ),
        )
        selectStatus(infos) shouldBe WorkStatus.Retrying
    }

    @Test
    fun `fetch_complete active entry lets a prior failure surface`() {
        // The worker is mid-delivery (fetch done, TTS playing) but the
        // last terminal run failed: the user should still see the
        // failure rather than have it masked by the in-flight worker.
        // This mirrors the existing "active wins over terminal" rule
        // but with fetch-complete carving the in-flight entry out of
        // the active set so the terminal entry surfaces.
        val infos = listOf(
            terminalFailure(
                reason = FetchAndNotifyWorker.REASON_UNHANDLED,
                detail = "kaboom",
                completedAt = 1_000L,
            ),
            WorkInfoLite(
                state = WorkInfo.State.RUNNING,
                runAttemptCount = 1,
                outputData = Data.EMPTY,
                progress = Data.Builder()
                    .putBoolean(FetchAndNotifyWorker.KEY_FETCH_COMPLETE, true)
                    .build(),
            ),
        )
        selectStatus(infos) shouldBe WorkStatus.Failed(
            reason = FetchAndNotifyWorker.REASON_UNHANDLED,
            detail = "kaboom",
        )
    }

    @Test
    fun `cancelled entries are ignored`() {
        // REPLACE policy cancels the in-flight worker before enqueueing a new
        // one. The transient CANCELLED entry shouldn't clobber the active one
        // or shadow the latest terminal entry.
        val infos = listOf(
            WorkInfoLite(state = WorkInfo.State.CANCELLED, runAttemptCount = 0, outputData = Data.EMPTY),
            terminalSuccess(completedAt = 1_000L),
        )
        selectStatus(infos) shouldBe WorkStatus.Idle
    }

    @Test
    fun `merge prefers Running over Retrying`() {
        // If the morning chain is on a fresh run and the tonight chain is mid-retry,
        // showing Running is the less alarming of the two for the user.
        mergeWorkStatus(WorkStatus.Running, WorkStatus.Retrying) shouldBe WorkStatus.Running
        mergeWorkStatus(WorkStatus.Retrying, WorkStatus.Running) shouldBe WorkStatus.Running
    }

    @Test
    fun `merge surfaces Retrying over a Failed sibling`() {
        // An in-flight retry on one chain takes precedence over a stale failure on the other.
        val failed = WorkStatus.Failed(reason = FetchAndNotifyWorker.REASON_UNHANDLED, detail = null)
        mergeWorkStatus(WorkStatus.Retrying, failed) shouldBe WorkStatus.Retrying
        mergeWorkStatus(failed, WorkStatus.Retrying) shouldBe WorkStatus.Retrying
    }

    @Test
    fun `merge falls through to Idle when both chains are idle`() {
        mergeWorkStatus(WorkStatus.Idle, WorkStatus.Idle) shouldBe WorkStatus.Idle
    }

    @Test
    fun `cold-open retry without an insight reads as Running`() {
        // WorkManager parks work back in ENQUEUED with runAttemptCount > 1
        // whenever a run is interrupted — not just when our code returned
        // Result.retry(). Killing the process during an app update leaves
        // the same on-disk state as a real transient failure, so on first
        // open after an update the banner would shout "Last attempt failed
        // — retrying" even though nothing went wrong. With no insight on
        // screen for the user to anchor "last attempt" against, soften the
        // status to Running so the banner reads as plain "Fetching".
        bannerStatus(
            workStatus = WorkStatus.Retrying,
            hasInsight = false,
            locationActionRequired = false,
        ) shouldBe WorkStatus.Running
    }

    @Test
    fun `retry with an existing insight keeps the retrying framing`() {
        // When there's a previous insight on screen, "retrying" tells the
        // user why what they're looking at might be stale — keep it.
        bannerStatus(
            workStatus = WorkStatus.Retrying,
            hasInsight = true,
            locationActionRequired = false,
        ) shouldBe WorkStatus.Retrying
    }

    @Test
    fun `failed status passes through on a cold open`() {
        // A real terminal failure isn't going to auto-retry — keep the
        // failure card so the user knows to act (tap Refresh, check
        // settings, etc.) rather than burying it as a spinner.
        val failed = WorkStatus.Failed(
            reason = FetchAndNotifyWorker.REASON_UNEXPECTED_HTTP,
            detail = "503",
        )
        bannerStatus(
            workStatus = failed,
            hasInsight = false,
            locationActionRequired = false,
        ) shouldBe failed
    }

    @Test
    fun `no-location failure is suppressed when the action banner is showing`() {
        // The LocationActionRequiredBanner already explains the cause and
        // offers the fix; the generic failure card would just duplicate it.
        bannerStatus(
            workStatus = WorkStatus.Failed(
                reason = FetchAndNotifyWorker.REASON_NO_LOCATION,
                detail = null,
            ),
            hasInsight = false,
            locationActionRequired = true,
        ) shouldBe WorkStatus.Idle
    }

    @Test
    fun `other failures still surface when the action banner is showing`() {
        // The suppression is keyed specifically to REASON_NO_LOCATION —
        // unrelated failures still need to reach the user even when the
        // location-required banner is also up.
        val failed = WorkStatus.Failed(
            reason = FetchAndNotifyWorker.REASON_UNEXPECTED_HTTP,
            detail = "503",
        )
        bannerStatus(
            workStatus = failed,
            hasInsight = false,
            locationActionRequired = true,
        ) shouldBe failed
    }

    private fun active(state: WorkInfo.State, runAttemptCount: Int): WorkInfoLite =
        WorkInfoLite(state = state, runAttemptCount = runAttemptCount, outputData = Data.EMPTY)

    private fun terminalSuccess(runAttemptCount: Int = 0, completedAt: Long): WorkInfoLite =
        WorkInfoLite(
            state = WorkInfo.State.SUCCEEDED,
            runAttemptCount = runAttemptCount,
            outputData = Data.Builder()
                .putLong(FetchAndNotifyWorker.KEY_COMPLETED_AT, completedAt)
                .build(),
        )

    private fun terminalFailure(
        reason: String,
        detail: String?,
        runAttemptCount: Int = 0,
        completedAt: Long,
    ): WorkInfoLite {
        val builder = Data.Builder()
            .putString(FetchAndNotifyWorker.KEY_REASON, reason)
            .putLong(FetchAndNotifyWorker.KEY_COMPLETED_AT, completedAt)
        if (detail != null) builder.putString(FetchAndNotifyWorker.KEY_REASON_DETAIL, detail)
        return WorkInfoLite(
            state = WorkInfo.State.FAILED,
            runAttemptCount = runAttemptCount,
            outputData = builder.build(),
        )
    }
}
