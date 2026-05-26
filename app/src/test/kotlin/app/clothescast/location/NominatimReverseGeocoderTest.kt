package app.clothescast.location

import app.clothescast.location.NominatimReverseGeocoder.NominatimAddress
import app.clothescast.location.NominatimReverseGeocoder.NominatimResponse
import io.kotest.matchers.shouldBe
import org.junit.Test

private fun toResult(response: NominatimResponse) = NominatimReverseGeocoder.toResult(response)

/**
 * Pure-JVM coverage of the response → [NominatimReverseGeocoder.Result]
 * transform. The HTTP layer isn't exercised here — what matters for
 * regression coverage is the field-fallback chain and the "Greater " strip,
 * which only depend on the parsed response object.
 */
class NominatimReverseGeocoderTest {
    @Test
    fun `passes city through when present`() {
        val response = NominatimResponse(
            address = NominatimAddress(city = "Sydney", countryCode = "au"),
        )

        toResult(response) shouldBe NominatimReverseGeocoder.Result(city = "Sydney", countryCode = "AU")
    }

    @Test
    fun `strips a leading 'Greater ' from city`() {
        val response = NominatimResponse(
            address = NominatimAddress(city = "Greater London", countryCode = "gb"),
        )

        toResult(response) shouldBe NominatimReverseGeocoder.Result(city = "London", countryCode = "GB")
    }

    @Test
    fun `falls back to town when city is missing`() {
        val response = NominatimResponse(
            address = NominatimAddress(town = "Knaresborough", countryCode = "gb"),
        )

        toResult(response) shouldBe NominatimReverseGeocoder.Result(city = "Knaresborough", countryCode = "GB")
    }

    @Test
    fun `falls back to village when city and town are missing`() {
        val response = NominatimResponse(
            address = NominatimAddress(village = "Hambleden", countryCode = "gb"),
        )

        toResult(response) shouldBe NominatimReverseGeocoder.Result(city = "Hambleden", countryCode = "GB")
    }

    @Test
    fun `falls back to municipality as a last resort`() {
        val response = NominatimResponse(
            address = NominatimAddress(municipality = "Reykjavik", countryCode = "is"),
        )

        toResult(response) shouldBe NominatimReverseGeocoder.Result(city = "Reykjavik", countryCode = "IS")
    }

    @Test
    fun `treats blank city as missing and falls through`() {
        val response = NominatimResponse(
            address = NominatimAddress(city = "  ", town = "Margate", countryCode = "gb"),
        )

        toResult(response) shouldBe NominatimReverseGeocoder.Result(city = "Margate", countryCode = "GB")
    }

    @Test
    fun `returns empty when no city-like field is present`() {
        val response = NominatimResponse(
            address = NominatimAddress(countryCode = "gb"),
        )

        toResult(response) shouldBe NominatimReverseGeocoder.Result(city = null, countryCode = "GB")
    }

    @Test
    fun `returns empty when address is absent entirely`() {
        toResult(NominatimResponse(address = null)) shouldBe
            NominatimReverseGeocoder.Result(city = null, countryCode = null)
    }

    @Test
    fun `treats blank country code as missing`() {
        val response = NominatimResponse(
            address = NominatimAddress(city = "Auckland", countryCode = "  "),
        )

        toResult(response) shouldBe NominatimReverseGeocoder.Result(city = "Auckland", countryCode = null)
    }
}
