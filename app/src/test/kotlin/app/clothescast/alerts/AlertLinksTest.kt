package app.clothescast.alerts

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AlertLinksTest {
    private fun linked(text: String) = alertLinks(text).map { text.substring(it.range) to it.url }

    @Test
    fun `http and https addresses link as written`() {
        linked("Details: https://example.com/warnings/wind and http://example.org") shouldBe listOf(
            "https://example.com/warnings/wind" to "https://example.com/warnings/wind",
            "http://example.org" to "http://example.org",
        )
    }

    @Test
    fun `a www address opens over https`() {
        linked("See www.example.com/floods for updates") shouldBe listOf(
            "www.example.com/floods" to "https://www.example.com/floods",
        )
    }

    @Test
    fun `sentence punctuation after an address stays out of the link`() {
        linked("Updates at www.example.com/floods.") shouldBe listOf(
            "www.example.com/floods" to "https://www.example.com/floods",
        )
        linked("(see https://example.com/a), then") shouldBe listOf(
            "https://example.com/a" to "https://example.com/a",
        )
    }

    @Test
    fun `text without an address, or with only part of one, links nothing`() {
        alertLinks("Very strong winds are expected.").shouldBeEmpty()
        alertLinks("Visit https:// soon, or www. later").shouldBeEmpty()
        // Not the start of an address: glued to a word or an email.
        alertLinks("mail info@www.example.com").shouldBeEmpty()
    }
}
