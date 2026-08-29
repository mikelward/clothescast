package app.clothescast.diag

import app.clothescast.cast.CastDeviceClass
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/**
 * The default-safe contract for what a diagnostic line may carry off the device.
 *
 * The value of these tests is the *default*: a filter that matches values it
 * has been taught fails open on every category nobody anticipated, so the
 * interesting assertions here are the ones about types nobody wrote a rule for.
 * Nothing leaves this app's device today, which is exactly why the rule is
 * worth having before something does.
 */
class LogValueTest {

    private fun mirrored(format: String, vararg args: Any?) =
        formatLogMessage(format, args, redactSensitive = true)

    private fun onDevice(format: String, vararg args: Any?) =
        formatLogMessage(format, args, redactSensitive = false)

    @Test
    fun `the on-device copy keeps every argument in full`() {
        onDevice("fetch place=%s cached=%s", "Fitzroy North", false) shouldBe
            "fetch place=Fitzroy North cached=false"
    }

    @Test
    fun `string arguments are withheld from the mirror`() {
        mirrored("fetch place=%s cached=%s", "Fitzroy North", false) shouldBe
            "fetch place=<redacted> cached=false"
    }

    // The point of inverting the default: nobody taught this about a geocoded
    // address, a calendar title or a line of insight prose, and it withholds
    // all three anyway.
    @Test
    fun `a string nobody wrote a rule for is still withheld`() {
        mirrored(
            "deliver address=%s event=%s prose=%s",
            "1 Example St, Suburb",
            "Morning standup",
            "Cold and wet — take the big coat.",
        ) shouldBe "deliver address=<redacted> event=<redacted> prose=<redacted>"
    }

    @Test
    fun `numbers booleans chars enums and null are carried`() {
        mirrored(
            "state hour=%s epoch=%s on=%s key=%s device=%s feels=%s missing=%s",
            9,
            9_000_000_000L,
            true,
            'k',
            CastDeviceClass.DISPLAY,
            12.5,
            null,
        ) shouldBe "state hour=9 epoch=9000000000 on=true key=k device=DISPLAY feels=12.5 missing=null"
    }

    @Test
    fun `a safe tag carries a string that is fixed vocabulary`() {
        mirrored("fetch model=%s", safe("gfs_seamless")) shouldBe "fetch model=gfs_seamless"
    }

    // The counterpart, and the reason the type rule is a default rather than a
    // verdict. A latitude is a `Double`, so the type rule alone would carry it
    // — and it names where the user is standing.
    @Test
    fun `a sensitive tag withholds an identifying number`() {
        mirrored("located at=%s accuracy=%s", sensitive(-37.8), 12) shouldBe
            "located at=<redacted> accuracy=12"
        onDevice("located at=%s accuracy=%s", sensitive(-37.8), 12) shouldBe
            "located at=-37.8 accuracy=12"
    }

    @Test
    fun `a summary chooses its own rendering per field`() {
        val summary = LogSummary(
            full = "model=gfs lat=-37.8 lon=144.9",
            mirrored = "model=gfs lat=<redacted> lon=<redacted>",
        )

        onDevice("request=%s", summary) shouldBe "request=model=gfs lat=-37.8 lon=144.9"
        mirrored("request=%s", summary) shouldBe "request=model=gfs lat=<redacted> lon=<redacted>"
    }

    @Test
    fun `classification follows the type`() {
        logArgumentMayLeaveDevice(1) shouldBe true
        logArgumentMayLeaveDevice(1L) shouldBe true
        logArgumentMayLeaveDevice(true) shouldBe true
        logArgumentMayLeaveDevice(null) shouldBe true
        logArgumentMayLeaveDevice(CastDeviceClass.AUDIO_ONLY) shouldBe true
        logArgumentMayLeaveDevice("Fitzroy North") shouldBe false
        logArgumentMayLeaveDevice(listOf("Fitzroy North")) shouldBe false
        logArgumentMayLeaveDevice(Any()) shouldBe false
    }

    // A wrong format string must never turn into a silent leak: a surplus
    // argument is appended, and it goes through the same redaction.
    @Test
    fun `a surplus argument is surfaced and still redacted`() {
        val line = mirrored("refresh reason=%s", safe("alarm"), "Fitzroy North")

        line shouldStartWith "refresh reason=alarm"
        line shouldContain "unplaced arg"
        line shouldNotContain "Fitzroy North"
    }

    @Test
    fun `a surplus placeholder is left visible rather than dropped`() {
        mirrored("a=%s b=%s", 1) shouldBe "a=1 b=%s"
    }

    @Test
    fun `a literal percent survives`() {
        mirrored("rain=50%% hour=%s", 9) shouldBe "rain=50% hour=9"
    }

    @Test
    fun `a format with no arguments is unchanged`() {
        mirrored("Daily insight is disabled; skipping.") shouldBe "Daily insight is disabled; skipping."
    }

    // Both sides of a location change must reach the on-device log in full —
    // naming them is what makes the line worth keeping — and neither may reach
    // a mirror, which has no per-share review.
    @Test
    fun `a location change keeps both places on device and withholds them from a mirror`() {
        onDevice("location %s -> %s", "Fitzroy North", "Docklands") shouldBe
            "location Fitzroy North -> Docklands"
        mirrored("location %s -> %s", "Fitzroy North", "Docklands") shouldBe
            "location <redacted> -> <redacted>"
    }
}
