package app.clothescast.location

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [buildAddressDetail] — the helper that turns a
 * Geocoder Address's structured fields (subLocality, locality,
 * adminArea, postalCode, countryName) into the Location settings
 * page's neighbourhood-level line. Pure JVM; no Android deps.
 *
 * The structured-fields approach replaced an earlier formatted-line
 * parser that stripped the leading comma component; that parser
 * worked on the common US / UK shapes but broke for any backend that
 * prepended a POI to the formatted line (Governors Island Ferry in
 * water-adjacent NYC coords) or for Brazilian addresses where a
 * meaningful neighbourhood — Bela Vista, for instance — sits between
 * the street and the city.
 */
class DeriveAddressDetailTest {

    @Test
    fun `US-style address with no neighbourhood collapses to city + state + zip + country`() {
        buildAddressDetail(
            AddressParts(
                locality = "Cambridge",
                adminArea = "MA",
                postalCode = "02139",
                countryName = "United States",
                countryCode = "US",
            ),
        ) shouldBe "Cambridge, MA 02139, United States"
    }

    @Test
    fun `Brazilian address preserves the neighbourhood between street and city`() {
        // Real-world Geocoder output for São Paulo's Bela Vista bairro.
        // Earlier "drop leading comma part + cap last 3" rule dropped
        // Bela Vista — the part the user actually wanted to see.
        buildAddressDetail(
            AddressParts(
                subLocality = "Bela Vista",
                locality = "São Paulo",
                adminArea = "SP",
                postalCode = "01311-000",
                countryName = "Brasil",
                countryCode = "BR",
            ),
        ) shouldBe "Bela Vista, São Paulo, SP 01311-000, Brasil"
    }

    @Test
    fun `London neighbourhood survives alongside city, postcode, country`() {
        buildAddressDetail(
            AddressParts(
                subLocality = "Muswell Hill",
                locality = "London",
                adminArea = "England",
                postalCode = "N10 3BN",
                countryName = "United Kingdom",
                countryCode = "GB",
            ),
        ) shouldBe "Muswell Hill, London, England N10 3BN, United Kingdom"
    }

    @Test
    fun `NYC water-adjacent fix shows city + region + country, no POI`() {
        // The motivating case: coarsened 2dp coords land in the East
        // River, Geocoder returns "Governors Island Ferry, 10 South St
        // Slip 7, …" as the formatted line — but locality / adminArea /
        // postalCode / countryName are all clean.
        buildAddressDetail(
            AddressParts(
                subLocality = null,
                locality = "New York",
                adminArea = "NY",
                postalCode = "10004",
                countryName = "United States",
                countryCode = "US",
            ),
        ) shouldBe "New York, NY 10004, United States"
    }

    @Test
    fun `locality equal to subLocality drops the duplicate, and so does the matching country name`() {
        // City-states like Singapore populate every place-name field
        // (subLocality, locality, countryName) with the same string.
        // Show "Singapore" once — duplicating it twice on the line
        // ("Singapore, 238801, Singapore") reads as a bug to anyone
        // who notices.
        buildAddressDetail(
            AddressParts(
                subLocality = "Singapore",
                locality = "Singapore",
                postalCode = "238801",
                countryName = "Singapore",
                countryCode = "SG",
            ),
        ) shouldBe "Singapore, 238801"
    }

    @Test
    fun `Hong Kong shape collapses duplicate place plus country`() {
        // Hong Kong: structured locality is blank, pickCityName falls
        // back to the country name ("Hong Kong"). toResult passes it
        // as recoveredCity. countryName is also "Hong Kong" — drop the
        // duplicate so the line reads "Sheung Wan, Hong Kong" instead
        // of "Sheung Wan, Hong Kong, Hong Kong".
        buildAddressDetail(
            AddressParts(
                subLocality = "Sheung Wan",
                locality = null,
                recoveredCity = "Hong Kong",
                countryName = "Hong Kong",
                countryCode = "HK",
            ),
        ) shouldBe "Sheung Wan, Hong Kong"
    }

    @Test
    fun `missing postalCode leaves a bare adminArea`() {
        buildAddressDetail(
            AddressParts(
                locality = "Reykjavík",
                adminArea = "Capital Region",
                countryName = "Iceland",
                countryCode = "IS",
            ),
        ) shouldBe "Reykjavík, Capital Region, Iceland"
    }

    @Test
    fun `missing adminArea leaves a bare postalCode`() {
        buildAddressDetail(
            AddressParts(
                locality = "London",
                postalCode = "SW1A 2AA",
                countryName = "United Kingdom",
                countryCode = "GB",
            ),
        ) shouldBe "London, SW1A 2AA, United Kingdom"
    }

    @Test
    fun `countryCode falls back when countryName is missing`() {
        // Some Geocoder backends populate the ISO code but not the
        // localised country name — surface the code rather than blank.
        buildAddressDetail(
            AddressParts(
                locality = "Sydney",
                adminArea = "NSW",
                postalCode = "2000",
                countryCode = "AU",
            ),
        ) shouldBe "Sydney, NSW 2000, AU"
    }

    @Test
    fun `blank strings are treated as missing`() {
        buildAddressDetail(
            AddressParts(
                subLocality = "  ",
                locality = "Tokyo",
                adminArea = "",
                postalCode = " ",
                countryName = "Japan",
            ),
        ) shouldBe "Tokyo, Japan"
    }

    @Test
    fun `all fields null returns null`() {
        buildAddressDetail(AddressParts()) shouldBe null
    }

    @Test
    fun `UK inner-London structured locality blank, post town arrives via recoveredCity`() {
        // Inner-London Geocoder results leave structured locality
        // blank; pickCityName fishes the post town ("London") out of
        // addressLines. toResult passes that recovered city in the
        // recoveredCity slot, so it slots in as a place name alongside
        // (or in place of) the missing locality.
        buildAddressDetail(
            AddressParts(
                locality = null,
                recoveredCity = "London",
                adminArea = "England",
                postalCode = "SW1A 2AA",
                countryName = "United Kingdom",
                countryCode = "GB",
            ),
        ) shouldBe "London, England SW1A 2AA, United Kingdom"
    }

    @Test
    fun `UK hamlet locality renders alongside the recovered post town`() {
        // Outer-London villages like Oakley Green fill structured
        // locality with the hamlet; pickCityName recovers the post
        // town ("Windsor") from the postcode token. Both survive: the
        // hamlet sits in the locality slot, the post town in
        // recoveredCity, and the displayed line keeps both.
        buildAddressDetail(
            AddressParts(
                locality = "Oakley Green",
                recoveredCity = "Windsor",
                adminArea = "England",
                postalCode = "SL4 5XX",
                countryName = "United Kingdom",
                countryCode = "GB",
            ),
        ) shouldBe "Oakley Green, Windsor, England SL4 5XX, United Kingdom"
    }

    @Test
    fun `UK suburb + hamlet + post town all survive when buildAddressDetail receives all three`() {
        // The trickiest UK shape: Geocoder gives a real subLocality
        // (residential suburb) AND a hamlet-style locality, while
        // pickCityName recovers the post town from the postcode. All
        // three distinct place names slot into the output line — no
        // demotion, no dedup. The earlier shuffle-based attempt at
        // post-town recovery silently dropped the post town in this
        // case; the recoveredCity slot keeps it.
        buildAddressDetail(
            AddressParts(
                subLocality = "Suburb",
                locality = "Oakley Green",
                recoveredCity = "Windsor",
                adminArea = "England",
                postalCode = "SL4 5XX",
                countryName = "United Kingdom",
                countryCode = "GB",
            ),
        ) shouldBe "Suburb, Oakley Green, Windsor, England SL4 5XX, United Kingdom"
    }

    @Test
    fun `recoveredCity that matches locality is silently deduped`() {
        // The common case: Geocoder's locality already matches what
        // pickCityName resolves to. recoveredCity is passed but
        // collapses, so the line is identical to the no-recovery case.
        buildAddressDetail(
            AddressParts(
                locality = "Cambridge",
                recoveredCity = "Cambridge",
                adminArea = "MA",
                postalCode = "02139",
                countryName = "United States",
                countryCode = "US",
            ),
        ) shouldBe "Cambridge, MA 02139, United States"
    }

    @Test
    fun `only country returns the country alone`() {
        // Edge case: nothing more granular than the country was
        // resolvable. Still useful as a header / pin label rather than
        // showing nothing.
        buildAddressDetail(
            AddressParts(countryName = "Antarctica"),
        ) shouldBe "Antarctica"
    }
}
