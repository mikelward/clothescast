package app.clothescast.diag

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Guards the privacy contract for non-fatal error reporting: a caught
 * exception's free-form messages must never survive [sanitizeHandledError],
 * because they can quote place names, forecast prose, parsed input, or a URL
 * carrying a key. Only the exception type, a coarse category, and the stack
 * trace (file + line, no values) may cross the device boundary.
 */
class HandledErrorSanitizerTest {

    // A message a real exception might carry that we must NOT let through.
    private val pii = "lat=51.5074,lon=-0.1278 near Privet Drive"

    @Test
    fun `strips the top exception message and keeps type plus category plus tag`() {
        val original = IllegalStateException(pii)

        val sanitized = sanitizeHandledError("ReverseGeocoder", original)

        sanitized.shouldBeInstanceOf<HandledError>()
        sanitized.message shouldBe "[location] ReverseGeocoder: java.lang.IllegalStateException"
        sanitized.message!! shouldNotContain "Privet Drive"
        sanitized.message!! shouldNotContain "51.5074"
    }

    @Test
    fun `strips messages from every link in the cause chain`() {
        val root = java.io.IOException("socket to https://api.example/?key=SECRET failed")
        val mid = RuntimeException("parsing $pii", root)
        val top = IllegalStateException("publish failed for $pii", mid)

        val sanitized = sanitizeHandledError("MqttPublisher", top)

        // Walk the whole rebuilt chain and assert no original message text — and
        // no PII / secret fragment — appears in any message.
        var current: Throwable? = sanitized
        val messages = buildList {
            while (current != null) {
                add(current!!.message.orEmpty())
                current = current!!.cause
            }
        }
        messages.forEach { msg ->
            msg shouldNotContain "Privet Drive"
            msg shouldNotContain "SECRET"
            msg shouldNotContain "publish failed for"
            msg shouldNotContain "parsing"
        }
        // The cause chain is preserved by class name (type signal kept).
        messages shouldContainExactly listOf(
            "[mqtt] MqttPublisher: java.lang.IllegalStateException",
            "java.lang.RuntimeException",
            "java.io.IOException",
        )
    }

    @Test
    fun `preserves the original stack trace for call-site grouping`() {
        val original = RuntimeException(pii)
        val originalFrames = original.stackTrace.toList()

        val sanitized = sanitizeHandledError("CastInsightController", original)

        sanitized.stackTrace.toList() shouldBe originalFrames
    }

    @Test
    fun `a cyclic cause chain terminates without looping`() {
        val a = RuntimeException("a $pii")
        val b = RuntimeException("b $pii")
        a.initCause(b)
        b.initCause(a)

        // Must return rather than spin; the cycle is broken after one pass.
        val sanitized = sanitizeHandledError("FetchAndNotifyWorker", a)

        var depth = 0
        var current: Throwable? = sanitized
        while (current != null) {
            current.message!! shouldNotContain "Privet Drive"
            current = current.cause
            depth++
        }
        // top (a) -> b, then a is already seen so the chain stops: 2 links.
        depth shouldBe 2
    }

    @Test
    fun `categoryForTag maps known areas and falls back to other`() {
        categoryForTag("MqttPublisher") shouldBe ErrorCategory.MQTT
        categoryForTag("ReverseGeocoder") shouldBe ErrorCategory.LOCATION
        categoryForTag("CastInsightController") shouldBe ErrorCategory.CAST
        categoryForTag("FetchAndNotifyWorker") shouldBe ErrorCategory.WORKER
        categoryForTag("CalendarContractEventReader") shouldBe ErrorCategory.CALENDAR
        categoryForTag("SomethingElse") shouldBe ErrorCategory.OTHER
    }
}
