package app.clothescast.location

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [deriveAddressDetail] — the helper that turns a Geocoder
 * Address's `getAddressLine` outputs into the Location settings page's
 * neighbourhood-level line. Pure JVM; no Android deps.
 */
class DeriveAddressDetailTest {

    @Test
    fun `US single-line address drops street and number`() {
        deriveAddressDetail(listOf("1 Beacon St, Boston, MA 02108, USA")) shouldBe
            "Boston, MA 02108, USA"
    }

    @Test
    fun `UK single-line address drops street and number`() {
        deriveAddressDetail(listOf("10 Downing Street, London SW1A 2AA, UK")) shouldBe
            "London SW1A 2AA, UK"
    }

    @Test
    fun `single-line with no comma returns null`() {
        deriveAddressDetail(listOf("Some Place")) shouldBe null
    }

    @Test
    fun `single-line with trailing comma after a single component returns null`() {
        // "Some Place," → after the comma there's nothing useful left.
        deriveAddressDetail(listOf("Some Place,")) shouldBe null
    }

    @Test
    fun `whitespace after the first comma is trimmed`() {
        deriveAddressDetail(listOf("1 Main St,    Springfield, IL 62701")) shouldBe
            "Springfield, IL 62701"
    }

    @Test
    fun `unicode characters survive the strip`() {
        deriveAddressDetail(listOf("Rua Augusta 100, São Paulo, Brazil")) shouldBe
            "São Paulo, Brazil"
    }

    @Test
    fun `empty list returns null`() {
        deriveAddressDetail(emptyList()) shouldBe null
    }

    @Test
    fun `multi-line address joins remaining lines after dropping street`() {
        // Some Geocoder backends split across line 0 (street), line 1
        // (city/state), and line 2 (country). Join with ", " before
        // stripping so the user still sees city + state + country.
        deriveAddressDetail(
            listOf("1 Vassar St", "Cambridge, MA 02139", "USA"),
        ) shouldBe "Cambridge, MA 02139, USA"
    }

    @Test
    fun `multi-line address with no comma on first line still drops first line`() {
        // Line 0 has no comma; after joining, the first comma lives at the
        // boundary between line 0 and line 1 — so "1 Vassar St" is dropped
        // wholesale, leaving the city + state + country.
        deriveAddressDetail(
            listOf("1 Vassar St", "Cambridge MA 02139", "USA"),
        ) shouldBe "Cambridge MA 02139, USA"
    }
}
