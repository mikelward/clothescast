package app.clothescast.location

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [pickCityName]. Pure JVM — exercises the picker logic
 * with the same field shapes Android's `Geocoder` returns, without needing
 * Robolectric or an actual `Address`.
 */
class CityNamePickerTest {

    @Test
    fun `UK address with empty locality returns post town from addressLine`() {
        // Muswell Hill (London N10): Geocoder leaves locality blank; we
        // recover "London" from the addressLine token containing the postcode.
        pickCityName(
            locality = null,
            subLocality = null,
            subAdminArea = "Greater London",
            adminArea = "England",
            countryCode = "GB",
            countryName = "United Kingdom",
            postalCode = "N10 1XX",
            addressLines = listOf("1 Some Street, Muswell Hill, London N10 1XX, UK"),
        ) shouldBe "London"
    }

    @Test
    fun `UK address prefers post town over hamlet locality`() {
        // Oakley Green (Windsor SL4): Geocoder fills locality with the hamlet,
        // but the post town "Windsor" is what people expect.
        pickCityName(
            locality = "Oakley Green",
            subLocality = null,
            subAdminArea = "Windsor and Maidenhead",
            adminArea = "England",
            countryCode = "GB",
            countryName = "United Kingdom",
            postalCode = "SL4 5XX",
            addressLines = listOf("1 Some Lane, Oakley Green, Windsor SL4 5XX, UK"),
        ) shouldBe "Windsor"
    }

    @Test
    fun `US address falls through to locality`() {
        // Boston, Suffolk County: not an NYC borough; locality is the right answer.
        pickCityName(
            locality = "Boston",
            subLocality = null,
            subAdminArea = "Suffolk County",
            adminArea = "Massachusetts",
            countryCode = "US",
            countryName = "United States",
            postalCode = "02108",
            addressLines = listOf("1 Beacon St, Boston, MA 02108, USA"),
        ) shouldBe "Boston"
    }

    @Test
    fun `US locality is preferred even when postcode appears in addressLine`() {
        // Confirms the GB extraction logic doesn't accidentally fire for non-GB.
        // Uses Boston (Suffolk County, MA) to avoid triggering the NYC borough
        // collapse — we want this test to exercise the locality fallback path.
        pickCityName(
            locality = "Boston",
            subLocality = null,
            subAdminArea = "Suffolk County",
            adminArea = "Massachusetts",
            countryCode = "US",
            countryName = "United States",
            postalCode = "02108",
            addressLines = listOf("1 Beacon St, Boston, MA 02108, USA"),
        ) shouldBe "Boston"
    }

    @Test
    fun `country code is matched case-insensitively`() {
        pickCityName(
            locality = null,
            subLocality = null,
            subAdminArea = "Greater London",
            adminArea = "England",
            countryCode = "gb",
            countryName = "United Kingdom",
            postalCode = "N10 1XX",
            addressLines = listOf("1 Some Street, Muswell Hill, London N10 1XX, UK"),
        ) shouldBe "London"
    }

    @Test
    fun `UK postcode without space in addressLine still matches`() {
        // postalCode field is "N10 1XX" but the addressLine packs it as "N101XX".
        pickCityName(
            locality = null,
            subLocality = null,
            subAdminArea = "Greater London",
            adminArea = "England",
            countryCode = "GB",
            countryName = "United Kingdom",
            postalCode = "N10 1XX",
            addressLines = listOf("1 Some Street, Muswell Hill, London N101XX, UK"),
        ) shouldBe "London"
    }

    @Test
    fun `UK address with no postcode falls back to locality chain`() {
        pickCityName(
            locality = "Edinburgh",
            subLocality = null,
            subAdminArea = "City of Edinburgh",
            adminArea = "Scotland",
            countryCode = "GB",
            countryName = "United Kingdom",
            postalCode = null,
            addressLines = listOf("Some Street, Edinburgh, UK"),
        ) shouldBe "Edinburgh"
    }

    @Test
    fun `UK address whose addressLines do not contain the postcode falls back`() {
        pickCityName(
            locality = "Edinburgh",
            subLocality = null,
            subAdminArea = "City of Edinburgh",
            adminArea = "Scotland",
            countryCode = "GB",
            countryName = "United Kingdom",
            postalCode = "EH1 1AA",
            addressLines = listOf("Some Street, no postcode here, UK"),
        ) shouldBe "Edinburgh"
    }

    @Test
    fun `UK addressLine token that is only the postcode is skipped`() {
        // Stripping the postcode leaves the token empty; don't return blank.
        pickCityName(
            locality = "Edinburgh",
            subLocality = null,
            subAdminArea = "City of Edinburgh",
            adminArea = "Scotland",
            countryCode = "GB",
            countryName = "United Kingdom",
            postalCode = "EH1 1AA",
            addressLines = listOf("Some Street, EH1 1AA, UK"),
        ) shouldBe "Edinburgh"
    }

    @Test
    fun `UK with empty locality and no addressLine match falls back to subAdminArea`() {
        pickCityName(
            locality = null,
            subLocality = null,
            subAdminArea = "Greater London",
            adminArea = "England",
            countryCode = "GB",
            countryName = "United Kingdom",
            postalCode = "N10 1XX",
            addressLines = listOf("Some Street, no postcode here, UK"),
        ) shouldBe "Greater London"
    }

    @Test
    fun `multi-line addressLines are scanned`() {
        // Some Geocoder responses split across multiple addressLines.
        pickCityName(
            locality = null,
            subLocality = null,
            subAdminArea = "Greater London",
            adminArea = "England",
            countryCode = "GB",
            countryName = "United Kingdom",
            postalCode = "N10 1XX",
            addressLines = listOf("1 Some Street", "Muswell Hill, London N10 1XX", "UK"),
        ) shouldBe "London"
    }

    @Test
    fun `everything blank returns null`() {
        pickCityName(
            locality = null,
            subLocality = null,
            subAdminArea = null,
            adminArea = null,
            countryCode = "GB",
            countryName = null,
            postalCode = null,
            addressLines = emptyList(),
        ) shouldBe null
    }

    @Test
    fun `locality is trimmed`() {
        pickCityName(
            locality = "  Boston  ",
            subLocality = null,
            subAdminArea = null,
            adminArea = "Massachusetts",
            countryCode = "US",
            countryName = "United States",
            postalCode = "02108",
            addressLines = listOf("1 Beacon St, Boston, MA 02108, USA"),
        ) shouldBe "Boston"
    }

    // --- City-state / SAR cases ------------------------------------------------

    @Test
    fun `Hong Kong returns canonical name even when locality is blank`() {
        // Real-world: Geocoder leaves locality empty for HK SAR and fills
        // adminArea / subAdminArea with district names. Users expect "Hong Kong".
        pickCityName(
            locality = null,
            subLocality = "Sheung Wan",
            subAdminArea = "Central and Western District",
            adminArea = "Hong Kong Island",
            countryCode = "HK",
            countryName = "Hong Kong",
            postalCode = null,
            addressLines = listOf(
                "Gold Union Commercial Building, 70-72 Connaught Road West, " +
                    "Sheung Wan, Central and Western District, Hong Kong",
            ),
        ) shouldBe "Hong Kong"
    }

    @Test
    fun `Hong Kong honours locality when Geocoder fills it`() {
        // If a future Geocoder version fills locality with the district, we trust
        // it — that's what the device locale chose. The canonical short form is
        // strictly a fallback for the empty-locality case.
        pickCityName(
            locality = "Central and Western District",
            subLocality = null,
            subAdminArea = null,
            adminArea = null,
            countryCode = "HK",
            countryName = "Hong Kong",
            postalCode = null,
            addressLines = emptyList(),
        ) shouldBe "Central and Western District"
    }

    @Test
    fun `Liechtenstein with locality returns the municipality`() {
        // Liechtenstein has 11 municipalities; Geocoder fills `locality` with
        // them. The picker must NOT collapse to the country label.
        pickCityName(
            locality = "Vaduz",
            subLocality = null,
            subAdminArea = null,
            adminArea = null,
            countryCode = "LI",
            countryName = "Liechtenstein",
            postalCode = "9490",
            addressLines = emptyList(),
        ) shouldBe "Vaduz"
    }

    @Test
    fun `Andorra with locality returns the parish`() {
        pickCityName(
            locality = "Andorra la Vella",
            subLocality = null,
            subAdminArea = null,
            adminArea = null,
            countryCode = "AD",
            countryName = "Andorra",
            postalCode = "AD500",
            addressLines = emptyList(),
        ) shouldBe "Andorra la Vella"
    }

    @Test
    fun `Singapore returns canonical name`() {
        pickCityName(
            locality = "Singapore",
            subLocality = null,
            subAdminArea = null,
            adminArea = null,
            countryCode = "SG",
            countryName = "Singapore",
            postalCode = "238801",
            addressLines = listOf("2 Orchard Turn, Singapore 238801"),
        ) shouldBe "Singapore"
    }

    // --- Governorate-model country (Egypt) -------------------------------------

    @Test
    fun `Cairo prefers cleaned governorate over neighborhood locality`() {
        // Real-world: Geocoder fills locality with the neighbourhood ("Khairat")
        // and adminArea with "Cairo Governorate". Strip "Governorate" and use it.
        pickCityName(
            locality = "Khairat",
            subLocality = "Al-Sayyida Zeinab",
            subAdminArea = null,
            adminArea = "Cairo Governorate",
            countryCode = "EG",
            countryName = "Egypt",
            postalCode = "11617",
            addressLines = listOf(
                "18 Al Baraka Al Nasereya Street, Khairat, " +
                    "Al-Sayyida Zeinab, Cairo Governorate 11617, Egypt",
            ),
        ) shouldBe "Cairo"
    }

    @Test
    fun `Cairo without Governorate suffix is returned as-is`() {
        // If adminArea already comes through as "Cairo", no stripping needed.
        pickCityName(
            locality = "Khairat",
            subLocality = null,
            subAdminArea = null,
            adminArea = "Cairo",
            countryCode = "EG",
            countryName = "Egypt",
            postalCode = "11617",
            addressLines = emptyList(),
        ) shouldBe "Cairo"
    }

    @Test
    fun `Egypt with blank adminArea falls back to locality`() {
        pickCityName(
            locality = "Alexandria",
            subLocality = null,
            subAdminArea = null,
            adminArea = null,
            countryCode = "EG",
            countryName = "Egypt",
            postalCode = null,
            addressLines = emptyList(),
        ) shouldBe "Alexandria"
    }

    // --- Ireland (County prefix on adminArea / subAdminArea) -------------------

    @Test
    fun `Dublin with empty locality strips County prefix from subAdminArea`() {
        // Real-world: Geocoder leaves locality / subLocality blank in central
        // Dublin (the user-reported "no name" symptom); the recognisable name
        // lives under subAdminArea or adminArea as "County Dublin". Strip the
        // "County " prefix.
        pickCityName(
            locality = null,
            subLocality = null,
            subAdminArea = "County Dublin",
            adminArea = "Leinster",
            countryCode = "IE",
            countryName = "Ireland",
            postalCode = "D04 E7N2",
            addressLines = listOf("50-60 Mespil Road, Dublin, County Dublin, D04 E7N2, Ireland"),
        ) shouldBe "Dublin"
    }

    @Test
    fun `Dublin with County prefix only on adminArea is also stripped`() {
        // Variant: subAdminArea blank too; Geocoder put "County Dublin" only
        // in adminArea.
        pickCityName(
            locality = null,
            subLocality = null,
            subAdminArea = null,
            adminArea = "County Dublin",
            countryCode = "IE",
            countryName = "Ireland",
            postalCode = "D04 E7N2",
            addressLines = emptyList(),
        ) shouldBe "Dublin"
    }

    @Test
    fun `Dublin Co dot prefix is stripped`() {
        pickCityName(
            locality = null,
            subLocality = null,
            subAdminArea = "Co. Dublin",
            adminArea = null,
            countryCode = "IE",
            countryName = "Ireland",
            postalCode = "D04 E7N2",
            addressLines = emptyList(),
        ) shouldBe "Dublin"
    }

    @Test
    fun `Dublin with locality returns locality`() {
        // If Geocoder does fill locality (some Dublin lookups), we trust it.
        pickCityName(
            locality = "Dublin",
            subLocality = null,
            subAdminArea = "County Dublin",
            adminArea = "Leinster",
            countryCode = "IE",
            countryName = "Ireland",
            postalCode = "D04 E7N2",
            addressLines = emptyList(),
        ) shouldBe "Dublin"
    }

    // --- New York City borough collapse ----------------------------------------

    @Test
    fun `Brooklyn collapses to New York`() {
        // Brooklyn (Kings County) is a NYC borough — show "New York", not the
        // borough name.
        pickCityName(
            locality = "Brooklyn",
            subLocality = null,
            subAdminArea = "Kings County",
            adminArea = "New York",
            countryCode = "US",
            countryName = "United States",
            postalCode = "11201",
            addressLines = listOf("123 Main St, Brooklyn, NY 11201, USA"),
        ) shouldBe "New York"
    }

    @Test
    fun `Manhattan returns New York`() {
        pickCityName(
            locality = "New York",
            subLocality = null,
            subAdminArea = "New York County",
            adminArea = "New York",
            countryCode = "US",
            countryName = "United States",
            postalCode = "10001",
            addressLines = listOf("West 33rd Street, New York, NY 10001, USA"),
        ) shouldBe "New York"
    }

    @Test
    fun `Queens collapses to New York`() {
        pickCityName(
            locality = "Flushing",
            subLocality = null,
            subAdminArea = "Queens County",
            adminArea = "New York",
            countryCode = "US",
            countryName = "United States",
            postalCode = "11354",
            addressLines = emptyList(),
        ) shouldBe "New York"
    }

    @Test
    fun `Bronx collapses to New York`() {
        pickCityName(
            locality = "Bronx",
            subLocality = null,
            subAdminArea = "Bronx County",
            adminArea = "New York",
            countryCode = "US",
            countryName = "United States",
            postalCode = "10451",
            addressLines = emptyList(),
        ) shouldBe "New York"
    }

    @Test
    fun `Staten Island collapses to New York`() {
        pickCityName(
            locality = "Staten Island",
            subLocality = null,
            subAdminArea = "Richmond County",
            adminArea = "New York",
            countryCode = "US",
            countryName = "United States",
            postalCode = "10301",
            addressLines = emptyList(),
        ) shouldBe "New York"
    }

    @Test
    fun `Buffalo upstate is not collapsed`() {
        // Erie County (Buffalo) is in NY state but not in NYC; locality wins.
        pickCityName(
            locality = "Buffalo",
            subLocality = null,
            subAdminArea = "Erie County",
            adminArea = "New York",
            countryCode = "US",
            countryName = "United States",
            postalCode = "14202",
            addressLines = emptyList(),
        ) shouldBe "Buffalo"
    }

    @Test
    fun `Chicago returns the city not the county`() {
        // Chicago / Cook County: county name is clearly different from the city
        // name. Locality must win — guards against ever returning "Cook County"
        // by accident (e.g. if cleanAdminArea ever started stripping " County"
        // off the end, "Cook County" → "Cook" would also be wrong, so a
        // distinctly-named county is the strong test).
        pickCityName(
            locality = "Chicago",
            subLocality = null,
            subAdminArea = "Cook County",
            adminArea = "Illinois",
            countryCode = "US",
            countryName = "United States",
            postalCode = "60601",
            addressLines = listOf("233 S Wacker Dr, Chicago, IL 60601, USA"),
        ) shouldBe "Chicago"
    }

    // --- Suffix-style "X County" must NOT be stripped (US/UK convention) ------

    @Test
    fun `US Orange County suffix is not stripped`() {
        // If locality is missing, "Orange County" (a non-NYC US county) should
        // fall through unchanged — we don't want "Orange". Guards the helper.
        pickCityName(
            locality = null,
            subLocality = null,
            subAdminArea = "Orange County",
            adminArea = "California",
            countryCode = "US",
            countryName = "United States",
            postalCode = null,
            addressLines = emptyList(),
        ) shouldBe "Orange County"
    }

    // --- Fallback to countryName as last resort -------------------------------

    @Test
    fun `falls back to countryName when everything else is blank`() {
        pickCityName(
            locality = null,
            subLocality = null,
            subAdminArea = null,
            adminArea = null,
            countryCode = "FR",
            countryName = "France",
            postalCode = null,
            addressLines = emptyList(),
        ) shouldBe "France"
    }
}
