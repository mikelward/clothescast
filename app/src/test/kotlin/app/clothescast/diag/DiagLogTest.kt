package app.clothescast.diag

import io.kotest.matchers.shouldBe
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

    private fun files(dir: Path): Pair<File, File> =
        dir.resolve("diag.log").toFile() to dir.resolve("diag.log.1").toFile()
}
