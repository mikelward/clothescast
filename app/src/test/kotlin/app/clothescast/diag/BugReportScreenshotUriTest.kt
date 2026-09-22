package app.clothescast.diag

import com.mikelward.androidlog.DebugLog
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The bug-report screenshot's URI-minting guard. A `FileProvider` failure must degrade to a
 * text-only report (null), never a dropped share or a crash of the application-scoped share
 * coroutine, and must not leave the unshareable PNG behind. The provider needs a device, so the
 * mint is injected; these run on a plain JVM.
 */
class BugReportScreenshotUriTest {

    private fun log(): DebugLog = object : DebugLog() {}

    @Test
    fun `a failed URI mint yields null and deletes the orphaned file`(@TempDir tmp: Path) {
        val file = tmp.resolve("screenshot-1.png").toFile().apply { writeBytes(byteArrayOf(1)) }

        val result = BugReport.bugReportScreenshotUri(file, log()) {
            throw IllegalArgumentException("outside the configured paths")
        }

        result.shouldBeNull()
        file.exists() shouldBe false
    }

    @Test
    fun `cancellation propagates rather than becoming a null screenshot`(@TempDir tmp: Path) {
        val file = tmp.resolve("screenshot-2.png").toFile().apply { writeBytes(byteArrayOf(1)) }

        shouldThrow<CancellationException> {
            BugReport.bugReportScreenshotUri(file, log()) { throw CancellationException("cancelled") }
        }
    }
}
