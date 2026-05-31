package app.clothescast.diag

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Unit-tests the disk-backed write-through helpers that back [DiagLog]'s
 * public surface. Exercises rotation + tail-read directly so we don't have
 * to spin up the singleton's executor or a Robolectric Context — those
 * pieces are mechanical glue around these two helpers.
 */
class DiagLogTest {

    @Test
    fun `appendAndRotate writes the line with a trailing newline`(@TempDir dir: Path) {
        val (file, rotated) = files(dir)
        DiagLog.appendAndRotate(file, rotated, "hello", maxBytes = 1024)
        file.readText() shouldBe "hello\n"
        rotated.exists() shouldBe false
    }

    @Test
    fun `appendAndRotate appends successive lines`(@TempDir dir: Path) {
        val (file, rotated) = files(dir)
        DiagLog.appendAndRotate(file, rotated, "first", maxBytes = 1024)
        DiagLog.appendAndRotate(file, rotated, "second", maxBytes = 1024)
        file.readLines() shouldBe listOf("first", "second")
    }

    @Test
    fun `appendAndRotate rotates when file exceeds maxBytes`(@TempDir dir: Path) {
        val (file, rotated) = files(dir)
        // maxBytes = 5 forces rotation after the first append (6 bytes incl. newline).
        DiagLog.appendAndRotate(file, rotated, "first", maxBytes = 5)
        rotated.readText() shouldBe "first\n"
        file.exists() shouldBe false

        // Next append starts a fresh diag.log; rotated retains the previous content.
        DiagLog.appendAndRotate(file, rotated, "second", maxBytes = 1024)
        file.readText() shouldBe "second\n"
        rotated.readText() shouldBe "first\n"
    }

    @Test
    fun `appendAndRotate replaces a previous rotated file`(@TempDir dir: Path) {
        val (file, rotated) = files(dir)
        rotated.writeText("ancient history\n")
        DiagLog.appendAndRotate(file, rotated, "overflow", maxBytes = 5)
        rotated.readText() shouldBe "overflow\n"
        file.exists() shouldBe false
    }

    @Test
    fun `appendAndRotate creates parent directory if missing`(@TempDir dir: Path) {
        val nested = dir.resolve("cache").resolve("diag.log").toFile()
        val nestedRotated = dir.resolve("cache").resolve("diag.log.1").toFile()
        DiagLog.appendAndRotate(nested, nestedRotated, "hi", maxBytes = 1024)
        nested.readText() shouldBe "hi\n"
    }

    @Test
    fun `appendAndRotate preserves multi-line entries with embedded newlines`(@TempDir dir: Path) {
        // Stacktraces ride along as one logical entry but multiple physical lines.
        val (file, rotated) = files(dir)
        DiagLog.appendAndRotate(file, rotated, "error: boom\n  at Foo.bar(Foo.kt:10)", maxBytes = 1024)
        file.readLines() shouldBe listOf("error: boom", "  at Foo.bar(Foo.kt:10)")
    }

    @Test
    fun `readTail returns empty when no files exist`(@TempDir dir: Path) {
        val (file, rotated) = files(dir)
        DiagLog.readTail(file, rotated, maxLines = 100) shouldBe emptyList()
    }

    @Test
    fun `readTail returns current file alone when no rotated`(@TempDir dir: Path) {
        val (file, rotated) = files(dir)
        file.writeText("a\nb\nc\n")
        DiagLog.readTail(file, rotated, maxLines = 100) shouldBe listOf("a", "b", "c")
    }

    @Test
    fun `readTail concatenates rotated then current in chronological order`(@TempDir dir: Path) {
        val (file, rotated) = files(dir)
        rotated.writeText("old1\nold2\n")
        file.writeText("new1\nnew2\n")
        DiagLog.readTail(file, rotated, maxLines = 100) shouldBe
            listOf("old1", "old2", "new1", "new2")
    }

    @Test
    fun `readTail caps result at maxLines preferring the newest`(@TempDir dir: Path) {
        val (file, rotated) = files(dir)
        rotated.writeText((1..50).joinToString("\n") { "old$it" } + "\n")
        file.writeText((1..10).joinToString("\n") { "new$it" } + "\n")
        val result = DiagLog.readTail(file, rotated, maxLines = 20)
        result.size shouldBe 20
        result.last() shouldBe "new10"
        result.first() shouldBe "old41"
    }

    @Test
    fun `readTail handles a missing rotated file`(@TempDir dir: Path) {
        val (file, rotated) = files(dir)
        file.writeText("only-current\n")
        rotated.exists() shouldBe false
        DiagLog.readTail(file, rotated, maxLines = 100) shouldBe listOf("only-current")
    }

    @Test
    fun `compactStackTraceString starts with exception class and message`() {
        val t = IllegalStateException("simulated broker rejection")
        val s = DiagLog.compactStackTraceString(t)
        s shouldStartWith "java.lang.IllegalStateException: simulated broker rejection"
    }

    @Test
    fun `compactStackTraceString caps frames per throwable and drops the rest silently`() {
        val t = makeWithStackDepth(10)
        val s = DiagLog.compactStackTraceString(t, maxFrames = 1)
        // Exactly one retained `at` line; no `... N more` summary so each
        // Throwable costs a flat `1 + maxFrames` lines in the snapshot.
        s.lines().count { it.startsWith("\tat ") } shouldBe 1
        s shouldNotContain "more"
    }

    @Test
    fun `compactStackTraceString summarises dropped frames when asked`() {
        // The crash-file path (writeCrashLog) opts into the omitted-count line
        // so a reader can tell the deep tail was elided, not absent — while the
        // snapshot path keeps the default silent truncation above.
        val t = makeWithStackDepth(10)
        val s = DiagLog.compactStackTraceString(t, maxFrames = 3, omittedSummary = true)
        s.lines().count { it.startsWith("\tat ") } shouldBe 3
        s shouldContain "\t... 7 more"
    }

    @Test
    fun `compactStackTraceString omits the summary when frames fit under the cap`() {
        val t = makeWithStackDepth(2)
        val s = DiagLog.compactStackTraceString(t, maxFrames = 5, omittedSummary = true)
        s.lines().count { it.startsWith("\tat ") } shouldBe 2
        s shouldNotContain "more"
    }

    @Test
    fun `compactStackTraceString preserves the cause chain with a 'Caused by' prefix`() {
        val cause = RuntimeException("inner")
        val outer = IllegalStateException("outer", cause)
        val s = DiagLog.compactStackTraceString(outer, maxFrames = 1)
        s shouldContain "java.lang.IllegalStateException: outer"
        s shouldContain "Caused by: java.lang.RuntimeException: inner"
    }

    @Test
    fun `compactStackTraceString does not duplicate frames for empty traces`() {
        // A Throwable whose stack we've cleared (mirrors R8-stripped wrappers
        // that arrive with no frames — common with HiveMQ's
        // ConnectionFailedException in release builds).
        val t = IllegalStateException("no frames here").apply { stackTrace = emptyArray() }
        val s = DiagLog.compactStackTraceString(t, maxFrames = 3)
        s shouldNotContain "\tat "
        s shouldNotContain "... "
    }

    @Test
    fun `compactStackTraceString terminates on a circular cause chain`() {
        // a.cause = b, b.cause = a — printStackTrace handles this by tracking
        // already-seen throwables; the compact formatter must do the same so a
        // pathological Throwable can't spin the calling thread.
        val a = IllegalStateException("a")
        val b = IllegalStateException("b")
        a.initCause(b)
        b.initCause(a)
        val s = DiagLog.compactStackTraceString(a, maxFrames = 1)
        s shouldContain "java.lang.IllegalStateException: a"
        s shouldContain "Caused by: java.lang.IllegalStateException: b"
        s shouldContain "[CIRCULAR REFERENCE: java.lang.IllegalStateException]"
    }

    @Test
    fun `compactStackTraceString surfaces suppressed exceptions as a one-line summary`() {
        // try-with-resources / .use add a close failure as a suppressed
        // exception on the primary throwable. Drop frames to keep the budget
        // tight, but keep class + message so the actionable bit isn't lost.
        val primary = IllegalStateException("primary")
        primary.addSuppressed(RuntimeException("close failed"))
        val s = DiagLog.compactStackTraceString(primary, maxFrames = 1)
        s shouldContain "java.lang.IllegalStateException: primary"
        s shouldContain "\tSuppressed: java.lang.RuntimeException: close failed"
    }

    /**
     * Builds a Throwable whose `stackTrace` has exactly [depth] entries — no
     * implicit dependency on the test runner's actual call depth (which
     * differs between JUnit and IDE).
     */
    private fun makeWithStackDepth(depth: Int): Throwable = IllegalStateException("deep").apply {
        stackTrace = Array(depth) { i -> StackTraceElement("Foo$i", "bar", "Foo$i.kt", i + 1) }
    }

    @Test
    fun `capEntryContinuations passes through entries within the cap`() {
        val input = listOf(
            "2026-05-27 12:00:00.000 -0400 I Foo: msg",
            "\tat com.example.A.run(A.kt:1)",
            "Caused by: java.io.IOException: boom",
            "\tat com.example.B.run(B.kt:2)",
        )
        DiagLog.capEntryContinuations(input, maxContinuation = 6) shouldBe input
    }

    @Test
    fun `capEntryContinuations elides continuation lines beyond the cap`() {
        val input = listOf(
            "2026-05-27 12:00:00.000 -0400 W ConfidenceFetcher: fetch failed",
            "\tat 1", "\tat 2", "\tat 3", "\tat 4",
            "\tat 5", "\tat 6", "\tat 7", "\tat 8",
        )
        DiagLog.capEntryContinuations(input, maxContinuation = 4) shouldBe listOf(
            "2026-05-27 12:00:00.000 -0400 W ConfidenceFetcher: fetch failed",
            "\tat 1", "\tat 2", "\tat 3", "\tat 4",
            "\t... [4 lines elided]",
        )
    }

    @Test
    fun `capEntryContinuations resets the counter at the next leading line`() {
        // Each timestamp-prefixed entry gets its own continuation budget so
        // an over-long earlier entry can't starve a later one's frames.
        val input = listOf(
            "2026-05-27 12:00:00.000 -0400 W Foo: first",
            "\tat 1", "\tat 2", "\tat 3", "\tat 4", "\tat 5",
            "2026-05-27 12:00:01.000 -0400 W Bar: second",
            "\tat 1", "\tat 2",
        )
        DiagLog.capEntryContinuations(input, maxContinuation = 2) shouldBe listOf(
            "2026-05-27 12:00:00.000 -0400 W Foo: first",
            "\tat 1", "\tat 2",
            "\t... [3 lines elided]",
            "2026-05-27 12:00:01.000 -0400 W Bar: second",
            "\tat 1", "\tat 2",
        )
    }

    @Test
    fun `capEntryContinuations caps orphan continuations at the start`() {
        // The snapshot can begin mid-entry when the rotated file's tail is
        // chopped — those leading continuation lines lack a header and must
        // not be allowed to dominate the buffer either.
        val input = listOf(
            "\tat 1", "\tat 2", "\tat 3", "\tat 4", "\tat 5",
            "2026-05-27 12:00:00.000 -0400 I Foo: ok",
        )
        DiagLog.capEntryContinuations(input, maxContinuation = 2) shouldBe listOf(
            "\tat 1", "\tat 2",
            "\t... [3 lines elided]",
            "2026-05-27 12:00:00.000 -0400 I Foo: ok",
        )
    }

    private fun files(dir: Path): Pair<File, File> =
        dir.resolve("diag.log").toFile() to dir.resolve("diag.log.1").toFile()
}
