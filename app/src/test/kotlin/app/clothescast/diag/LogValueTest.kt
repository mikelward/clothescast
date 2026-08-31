package app.clothescast.diag

import app.clothescast.cast.CastDeviceClass
import com.mikelward.androidlog.OFF_DEVICE_PLACEHOLDER
import com.mikelward.androidlog.formatLogMessage
import com.mikelward.androidlog.logArgumentMayLeaveDevice
import com.mikelward.androidlog.safe
import com.mikelward.androidlog.sensitive
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/**
 * This app's conformance test against the shared privacy floor
 * (`mikelward/androidlog`), kept here deliberately rather than left to that
 * repository's own suite.
 *
 * Consumers track `@main` with no pin, so a floor that widened or narrowed
 * upstream reaches this APK on the next build with nothing in between. The
 * cases below are the ones *this* app's call sites depend on — a geocoded
 * place name, a coordinate, a weather model id, a cast device class — asserted
 * in both directions, so a regression fails here rather than shipping.
 *
 * The value is in the *default*: a filter that matches only what it has been
 * taught fails open on every category nobody anticipated, so the interesting
 * assertions are about types nobody wrote a rule for.
 */
class LogValueTest {

    private fun mirrored(format: String, vararg args: Any?) =
        formatLogMessage(format, args, leavingDevice = true)

    private fun onDevice(format: String, vararg args: Any?) =
        formatLogMessage(format, args, leavingDevice = false)

    @Test
    fun `string arguments are withheld from the mirror`() {
        mirrored("fetch place=%s cached=%s", "Fitzroy North", false) shouldBe
            "fetch place=$OFF_DEVICE_PLACEHOLDER cached=false"
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
        ) shouldBe "deliver address=$OFF_DEVICE_PLACEHOLDER event=$OFF_DEVICE_PLACEHOLDER " +
            "prose=$OFF_DEVICE_PLACEHOLDER"
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
            "located at=$OFF_DEVICE_PLACEHOLDER accuracy=12"
    }

    // Sensitive dominates wherever it appears, so a value cannot be un-marked
    // by wrapping it again — the case an app hits by passing an already-tagged
    // value through a helper that tags what it is given.
    @Test
    fun `a sensitive value stays withheld under a safe wrapper`() {
        mirrored("located at=%s", safe(sensitive(-37.8))) shouldBe
            "located at=$OFF_DEVICE_PLACEHOLDER"
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

    // A throwable names a type, never the user — and the no-messages floor is
    // what makes that true. This app logs `it.javaClass.simpleName` at a dozen
    // call sites for exactly this reason; the floor carrying the type itself is
    // what lets those eventually pass the exception instead.
    @Test
    fun `a throwable is carried as its type and never its message`() {
        val line = mirrored("fetch failed: %s", IllegalStateException("secret detail"))

        line shouldBe "fetch failed: java.lang.IllegalStateException"
        line shouldNotContain "secret detail"
    }

    // The rule that makes the one above safe: the floor renders only what it
    // defines the rendering of. An unknown type is named, never `toString()`d —
    // otherwise any object holding a place name or an exception message could
    // print it through a `toString()` nobody reviewed.
    @Test
    fun `an unknown type renders as its class name rather than its toString`() {
        class Holder {
            override fun toString() = "1 Example St, Suburb"
        }

        val line = onDevice("state=%s", Holder())

        line shouldNotContain "Example St"
        line shouldContain "Holder"
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

    // Both sides of a location change reach the on-device log in full — naming
    // them is what makes the line worth keeping — and neither reaches a mirror,
    // which has no per-share review.
    @Test
    fun `a location change keeps both places on device and withholds them from a mirror`() {
        onDevice("location %s -> %s", "Fitzroy North", "Docklands") shouldBe
            "location Fitzroy North -> Docklands"
        mirrored("location %s -> %s", "Fitzroy North", "Docklands") shouldBe
            "location $OFF_DEVICE_PLACEHOLDER -> $OFF_DEVICE_PLACEHOLDER"
    }
}
