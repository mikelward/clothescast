package app.clothescast.diag

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Covers the bug-report screenshot cache pruning. Each new capture prunes the
 * directory but must keep the most recent previous captures: a FileProvider URI
 * from an earlier share can still be held by its target (an unsent email draft,
 * a lazily-reading messaging app), and the old delete-everything sweep broke
 * that grant retroactively — the attachment failed with FileNotFoundException
 * when the target finally read it.
 */
class BugReportScreenshotPruneTest {

    @Test
    fun `prune keeps the newest captures and deletes older ones`(@TempDir tmp: Path) {
        val dir = tmp.toFile()
        val oldest = dir.resolve("screenshot-1000.png").apply { writeBytes(byteArrayOf(1)) }
        val middle = dir.resolve("screenshot-2000.png").apply { writeBytes(byteArrayOf(2)) }
        val newest = dir.resolve("screenshot-3000.png").apply { writeBytes(byteArrayOf(3)) }

        BugReport.prunePersistedScreenshots(dir, keepNewest = 2)

        newest.exists() shouldBe true
        middle.exists() shouldBe true
        oldest.exists() shouldBe false
    }

    @Test
    fun `the just-shared screenshot survives the next capture cycle`(@TempDir tmp: Path) {
        // Regression scenario: share bug report A, then trigger bug report B.
        // B's pre-write prune must leave A's file (and therefore A's shared URI)
        // intact instead of deleting every file in the directory.
        val dir = tmp.toFile()
        val reportA = dir.resolve("screenshot-1000.png").apply { writeBytes(byteArrayOf(1)) }

        BugReport.prunePersistedScreenshots(dir, keepNewest = 2)
        val reportB = dir.resolve("screenshot-2000.png").apply { writeBytes(byteArrayOf(2)) }

        reportA.exists() shouldBe true
        reportB.exists() shouldBe true
    }

    @Test
    fun `prune ignores unrelated files`(@TempDir tmp: Path) {
        val dir = tmp.toFile()
        val unrelated = dir.resolve("notes.txt").apply { writeBytes(byteArrayOf(9)) }
        repeat(5) { index ->
            dir.resolve("screenshot-${1000 + index}.png").writeBytes(byteArrayOf(index.toByte()))
        }

        BugReport.prunePersistedScreenshots(dir, keepNewest = 2)

        unrelated.exists() shouldBe true
        dir.listFiles().orEmpty().map { it.name }.filter { it.startsWith("screenshot-") }.sorted() shouldBe
            listOf("screenshot-1003.png", "screenshot-1004.png")
    }
}
