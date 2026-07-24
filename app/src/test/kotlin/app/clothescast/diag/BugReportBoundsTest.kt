package app.clothescast.diag

import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * The assembled report has to survive the trip to a share target. It crosses
 * Binder twice (clipboard, then the chooser's `ACTION_SEND` extra) against a
 * ~1 MB per-process buffer shared with every other in-flight transaction, and
 * both hand-offs are best-effort — so an oversized report used to make the tap
 * do nothing at all. Each section is bounded on its own, and the newest lines
 * are the ones kept: a crash entry and the events around it sit at the *end* of
 * a log.
 */
class BugReportBoundsTest {

    @Test
    fun `a huge settings section still leaves room for the log`() {
        val head = "--- Settings ---\n" + (0 until 4_000).joinToString("\n") { "Clothes rule $it: " + "x".repeat(40) }

        val report = BugReport.assemble(head, crash = null, recent = listOf("the newest event"))

        report shouldContain "details truncated"
        report shouldContain "the newest event"
        report.length shouldBeLessThanOrEqualTo BugReport.MAX_SHARE_PAYLOAD_CHARS
    }

    @Test
    fun `an oversized crash section keeps both the crash and the newest log lines`() {
        // last-crash.txt is written head-first (timestamp, exception, stack) and
        // then appends the log around the crash, so neither end can be dropped
        // wholesale: a tail-only cut loses the crash the report exists to carry.
        val crash = "=== 2026-01-01 ===\nUncaught exception on thread \"main\"\n" +
            (0 until 2_000).joinToString("\n") { "\tat frame-$it " + "x".repeat(60) } +
            "\n--- recent log ---\n" +
            (0 until 2_000).joinToString("\n") { "log-$it " + "x".repeat(60) } +
            "\nthe last line before the crash"

        val report = BugReport.assemble("head\n", crash, recent = emptyList())

        report shouldContain "Uncaught exception on thread"
        report shouldContain "\tat frame-0 "
        report shouldContain "the last line before the crash"
        report shouldNotContain "log-0 "
        report.length shouldBeLessThanOrEqualTo BugReport.MAX_SHARE_PAYLOAD_CHARS
    }

    @Test
    fun `a crash file of an unrecognized shape keeps its newest lines`() {
        // An older build's file, or a partial write: no "recent log" marker to
        // split on, so fall back to keeping the freshest end.
        val crash = (0 until 2_000).joinToString("\n") { "line-$it " + "x".repeat(60) } + "\nthe newest line"

        val report = BugReport.assemble("head\n", crash, recent = emptyList())

        report shouldContain "the newest line"
        report shouldNotContain "line-0 "
        report.length shouldBeLessThanOrEqualTo BugReport.MAX_SHARE_PAYLOAD_CHARS
    }

    @Test
    fun `an oversized log keeps its newest lines and says what it dropped`() {
        val recent = (0 until 300).map { "line-$it " + "x".repeat(600) } + "the newest event"

        val report = BugReport.assemble("head\n", crash = null, recent = recent)

        report shouldContain "the newest event"
        report shouldNotContain "line-0 "
        report shouldContain "older line(s) omitted"
        report.length shouldBeLessThanOrEqualTo BugReport.MAX_SHARE_PAYLOAD_CHARS
    }

    @Test
    fun `every section at its largest still fits`() {
        val report = BugReport.assemble(
            head = "x".repeat(200_000),
            crash = (0 until 2_000).joinToString("\n") { "frame-$it " + "x".repeat(60) },
            recent = (0 until 300).map { "line-$it " + "x".repeat(600) },
        )

        report.length shouldBeLessThanOrEqualTo BugReport.MAX_SHARE_PAYLOAD_CHARS
    }

    @Test
    fun `an empty log is called out rather than left blank`() {
        BugReport.assemble("head\n", crash = null, recent = emptyList()) shouldContain
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

    @Test
    fun `a single oversized crash line is clamped, not passed through`() {
        // `cacheDir` survives app upgrades, so last-crash.txt from an older
        // build can hold one arbitrarily long line and is read back unchanged.
        val report = BugReport.assemble("head\n", crash = "x".repeat(500_000), recent = emptyList())

        report shouldContain "…(truncated)"
        report.length shouldBeLessThanOrEqualTo BugReport.MAX_SHARE_PAYLOAD_CHARS
    }
}
