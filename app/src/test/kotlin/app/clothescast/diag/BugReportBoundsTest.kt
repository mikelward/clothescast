package app.clothescast.diag

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * This app's own section of the report — the structured head and the recent log
 * — has to leave room for the log even when a settings dump is huge, and has to
 * keep the *newest* log lines, since the events that matter sit at the end. The
 * previous run's log is appended after this by the shared library and bounded by
 * its own persist budget, so a fat crash is no longer this app's to bound; the
 * "keep the report under Binder limits" tests for the appended run live in
 * `androidlog`.
 */
class BugReportBoundsTest {

    @Test
    fun `a huge settings section is truncated but still leaves room for the log`() {
        val head = "--- Settings ---\n" + (0 until 4_000).joinToString("\n") { "Clothes rule $it: " + "x".repeat(40) }

        val report = BugReport.assembleSection(head, recent = listOf("the newest event"))

        report shouldContain "details truncated"
        report shouldContain "the newest event"
    }

    @Test
    fun `an oversized log keeps its newest lines and says what it dropped`() {
        val recent = (0 until 300).map { "line-$it " + "x".repeat(600) } + "the newest event"

        val report = BugReport.assembleSection("head\n", recent = recent)

        report shouldContain "the newest event"
        report shouldNotContain "line-0 "
        report shouldContain "older line(s) omitted"
    }

    @Test
    fun `an empty log is called out rather than left blank`() {
        BugReport.assembleSection("head\n", recent = emptyList()) shouldContain
            "(no captured log lines)"
    }

    @Test
    fun `boundedLogTail clamps a newest line that alone overflows the budget`() {
        // Keeping it whole would blow the very ceiling this enforces; dropping
        // it would lose the freshest context. Clamp and mark it.
        val kept = boundedLogTail(listOf("old", "x".repeat(500)), budgetChars = 40).single()

        kept.length shouldBe 40
        kept.endsWith("…(truncated)") shouldBe true
    }
}
