package app.clothescast.diag

import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.android.DebugReport
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * This app's own section of the report — the structured head and the recent log
 * — has to leave room for the log even when a settings dump is huge, and has to
 * keep the *newest* log lines, since the events that matter sit at the end. The
 * pinned lines ahead of them are covered by `ProcessExitReasonsTest`. The
 * previous run's log is appended after this by the shared library, inside its
 * own share of the report, so a fat crash is no longer this app's to bound; the
 * "keep the report under Binder limits" tests for the appended run live in
 * `androidlog`. What is this app's is staying inside the rest of that report.
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
    fun `an oversized log keeps its newest lines within the character ceiling`() {
        val log = DebugLog()
        repeat(300) { log.event("line-%s %s", it, "x".repeat(600)) }
        log.event("the newest event")

        val recent = BugReport.reportLogLines(log)

        recent.last() shouldContain "the newest event"
        recent.none { it.contains("line-0 ") } shouldBe true
        (recent.sumOf { it.length + 1 } <= 16_000) shouldBe true
        BugReport.assembleSection("head\n", recent) shouldContain
            "older lines are dropped to keep the report shareable"
    }

    @Test
    fun `the largest section this app can build fits its share of the report`() {
        // Past its share, the library cuts the section from the middle -- here
        // the end of the settings and the start of the log -- so a worst case
        // that fits is what keeps that cut from ever reaching this report.
        val head = "--- Settings ---\n" + (0 until 4_000).joinToString("\n") { "Clothes rule $it: " + "x".repeat(40) }
        val log = DebugLog()
        repeat(300) { log.event("line-%s %s", it, "x".repeat(600)) }

        val section = BugReport.assembleSection(head, BugReport.reportLogLines(log))

        section shouldContain "details truncated"
        (section.length <= DebugReport.MAX_REPORT_CHARS - DebugReport.MAX_EARLIER_RUNS_CHARS) shouldBe true
    }

    @Test
    fun `a short log is reported whole`() {
        val log = DebugLog()
        repeat(40) { log.event("line %s", it) }

        val recent = BugReport.reportLogLines(log)

        recent.count { it.contains(" line ") } shouldBe 40
    }

    @Test
    fun `an empty log is called out rather than left blank`() {
        BugReport.assembleSection("head\n", recent = emptyList()) shouldContain
            "(no captured log lines)"
    }
}
