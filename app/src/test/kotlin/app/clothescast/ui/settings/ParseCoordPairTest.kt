package app.clothescast.ui.settings

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [parseCoordPair] — the helper that turns the
 * Developer settings page's "lat, lon" text input into a coordinate
 * pair for the reverse-geocode tester. Pure JVM; no Android deps.
 */
class ParseCoordPairTest {

    @Test
    fun `comma plus single space splits cleanly`() {
        parseCoordPair("40.70, -74.01") shouldBe (40.70 to -74.01)
    }

    @Test
    fun `comma with no space also works`() {
        parseCoordPair("40.70,-74.01") shouldBe (40.70 to -74.01)
    }

    @Test
    fun `extra whitespace around the comma is tolerated`() {
        parseCoordPair("  40.70   ,   -74.01  ") shouldBe (40.70 to -74.01)
    }

    @Test
    fun `negative latitude and positive longitude parse`() {
        parseCoordPair("-33.87, 151.21") shouldBe (-33.87 to 151.21)
    }

    @Test
    fun `latitude beyond the pole rejects`() {
        parseCoordPair("90.5, 0.0") shouldBe null
        parseCoordPair("-90.5, 0.0") shouldBe null
    }

    @Test
    fun `longitude beyond the antimeridian rejects`() {
        parseCoordPair("0.0, 180.5") shouldBe null
        parseCoordPair("0.0, -180.5") shouldBe null
    }

    @Test
    fun `single value without comma rejects`() {
        parseCoordPair("40.70") shouldBe null
    }

    @Test
    fun `extra comma-separated parts reject`() {
        parseCoordPair("40.70, -74.01, 0") shouldBe null
    }

    @Test
    fun `non-numeric halves reject`() {
        parseCoordPair("lat, lon") shouldBe null
        parseCoordPair("40.70, ten") shouldBe null
    }

    @Test
    fun `empty string rejects`() {
        parseCoordPair("") shouldBe null
        parseCoordPair("   ") shouldBe null
    }
}
