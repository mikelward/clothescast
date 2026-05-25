package app.clothescast.diag

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Validates the file-based ack tracking that backs the post-crash banner on
 * the Today screen — see [DiagLog.unacknowledgedCrash] and
 * [DiagLog.acknowledgePersistedCrash]. Identity of "the current crash" is
 * the crash file's last-modified time, so a fresh crash bumps mtime and the
 * banner re-surfaces even when an older crash was already acked.
 */
class CrashAckTest {

    @Test
    fun `no crash file means nothing to acknowledge`(@TempDir dir: Path) {
        val (crash, ack) = files(dir)
        DiagLog.isCrashUnacknowledged(crash, ack) shouldBe false
    }

    @Test
    fun `empty crash file is treated as no crash`(@TempDir dir: Path) {
        val (crash, ack) = files(dir)
        crash.writeText("")
        DiagLog.isCrashUnacknowledged(crash, ack) shouldBe false
    }

    @Test
    fun `unacked crash surfaces`(@TempDir dir: Path) {
        val (crash, ack) = files(dir)
        crash.writeText("boom")
        DiagLog.isCrashUnacknowledged(crash, ack) shouldBe true
    }

    @Test
    fun `acknowledging silences the banner`(@TempDir dir: Path) {
        val (crash, ack) = files(dir)
        crash.writeText("boom")
        DiagLog.writeCrashAcknowledgement(crash, ack)
        DiagLog.isCrashUnacknowledged(crash, ack) shouldBe false
    }

    @Test
    fun `a fresh crash after an ack re-surfaces`(@TempDir dir: Path) {
        val (crash, ack) = files(dir)
        crash.writeText("first")
        DiagLog.writeCrashAcknowledgement(crash, ack)
        DiagLog.isCrashUnacknowledged(crash, ack) shouldBe false

        // Force a different last-modified time. Filesystem mtime resolution
        // varies (1s on ext4 by default) so explicit setLastModified rather
        // than relying on a sleep + write keeps the test fast and robust.
        crash.writeText("second")
        crash.setLastModified(crash.lastModified() + 5_000)
        DiagLog.isCrashUnacknowledged(crash, ack) shouldBe true
    }

    @Test
    fun `corrupt ack file falls back to surfacing`(@TempDir dir: Path) {
        // If the ack file ever ends up with non-numeric junk (manual tamper,
        // truncated write), prefer to show the banner once more rather than
        // swallow a real crash report.
        val (crash, ack) = files(dir)
        crash.writeText("boom")
        ack.writeText("not-a-number")
        DiagLog.isCrashUnacknowledged(crash, ack) shouldBe true
    }

    @Test
    fun `acknowledgement is a no-op when there's no crash to ack`(@TempDir dir: Path) {
        val (crash, ack) = files(dir)
        DiagLog.writeCrashAcknowledgement(crash, ack)
        ack.exists() shouldBe false
    }

    private fun files(dir: Path): Pair<File, File> =
        dir.resolve("last-crash.txt").toFile() to dir.resolve("last-crash.ack").toFile()
}
