package app.clothescast.core.domain.model

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HolidayCountryResolutionTest {
    private val allCountries = setOf("AU", "GB", "US", "FR", "DE", HolidayCatalog.GLOBAL_COUNTRY)

    @Test
    fun `all=true picks up every catalog country and Global rides along`() {
        HolidayCountrySelection(home = false, current = false, all = true)
            .resolveEnabledCountries(
                localeCountry = "JP",
                weatherLocationCountry = "KR",
                allCountries = allCountries,
            ).shouldContainExactlyInAnyOrder("AU", "GB", "US", "FR", "DE", HolidayCatalog.GLOBAL_COUNTRY)
    }

    @Test
    fun `home plus current is the default Auto behaviour and Global rides along`() {
        HolidayCountrySelection().resolveEnabledCountries(
            localeCountry = "AU",
            weatherLocationCountry = "GB",
            allCountries = allCountries,
        ).shouldContainExactlyInAnyOrder("AU", "GB", HolidayCatalog.GLOBAL_COUNTRY)
    }

    @Test
    fun `home off drops the locale country but Global still rides on current`() {
        HolidayCountrySelection(home = false).resolveEnabledCountries(
            localeCountry = "AU",
            weatherLocationCountry = "GB",
            allCountries = allCountries,
        ).shouldContainExactlyInAnyOrder("GB", HolidayCatalog.GLOBAL_COUNTRY)
    }

    @Test
    fun `current off drops the weather location country but Global still rides on home`() {
        HolidayCountrySelection(current = false).resolveEnabledCountries(
            localeCountry = "AU",
            weatherLocationCountry = "GB",
            allCountries = allCountries,
        ).shouldContainExactlyInAnyOrder("AU", HolidayCatalog.GLOBAL_COUNTRY)
    }

    @Test
    fun `per-country ON override unions with the home and current buckets`() {
        HolidayCountrySelection(
            countryOverrides = mapOf("FR" to HolidayOverride.ON, "DE" to HolidayOverride.ON),
        ).resolveEnabledCountries(
            localeCountry = "AU",
            weatherLocationCountry = "GB",
            allCountries = allCountries,
        ).shouldContainExactlyInAnyOrder("AU", "GB", "FR", "DE", HolidayCatalog.GLOBAL_COUNTRY)
    }

    @Test
    fun `per-country OFF override removes a bucket match`() {
        HolidayCountrySelection(
            countryOverrides = mapOf("AU" to HolidayOverride.OFF),
        ).resolveEnabledCountries(
            localeCountry = "AU",
            weatherLocationCountry = "GB",
            allCountries = allCountries,
        ).shouldContainExactlyInAnyOrder("GB", HolidayCatalog.GLOBAL_COUNTRY)
    }

    @Test
    fun `per-country OFF override removes a country that All would otherwise enable`() {
        HolidayCountrySelection(
            home = false,
            current = false,
            all = true,
            countryOverrides = mapOf("US" to HolidayOverride.OFF),
        ).resolveEnabledCountries(
            localeCountry = null,
            weatherLocationCountry = null,
            allCountries = allCountries,
        ).shouldContainExactlyInAnyOrder("AU", "GB", "FR", "DE", HolidayCatalog.GLOBAL_COUNTRY)
    }

    @Test
    fun `null or blank locale and weather country with no overrides returns empty set`() {
        HolidayCountrySelection().resolveEnabledCountries(
            localeCountry = null,
            weatherLocationCountry = "   ",
            allCountries = allCountries,
        ) shouldBe emptySet()
    }

    @Test
    fun `lowercase locale and weather country normalise to uppercase`() {
        HolidayCountrySelection().resolveEnabledCountries(
            localeCountry = "au",
            weatherLocationCountry = "gb",
            allCountries = allCountries,
        ).shouldContainExactlyInAnyOrder("AU", "GB", HolidayCatalog.GLOBAL_COUNTRY)
    }

    @Test
    fun `everything off returns empty set`() {
        HolidayCountrySelection(home = false, current = false, all = false)
            .resolveEnabledCountries(
                localeCountry = "AU",
                weatherLocationCountry = "GB",
                allCountries = allCountries,
            ) shouldBe emptySet()
    }

    @Test
    fun `per-country ON override alone enables Global without home or current`() {
        HolidayCountrySelection(
            home = false,
            current = false,
            countryOverrides = mapOf("FR" to HolidayOverride.ON),
        ).resolveEnabledCountries(
            localeCountry = "AU",
            weatherLocationCountry = "GB",
            allCountries = allCountries,
        ).shouldContainExactlyInAnyOrder("FR", HolidayCatalog.GLOBAL_COUNTRY)
    }

    @Test
    fun `countryAutoEffective reflects the buckets without honouring overrides`() {
        val selection = HolidayCountrySelection(
            countryOverrides = mapOf("AU" to HolidayOverride.OFF, "FR" to HolidayOverride.ON),
        )
        // AU matches locale, so AUTO would resolve on regardless of the explicit OFF.
        selection.countryAutoEffective("AU", "AU", "GB") shouldBe true
        // FR matches nothing, so AUTO would resolve off regardless of the explicit ON.
        selection.countryAutoEffective("FR", "AU", "GB") shouldBe false
        // Case-insensitive.
        selection.countryAutoEffective("au", "AU", "GB") shouldBe true
    }

    @Test
    fun `countryEffective honours the explicit override`() {
        val selection = HolidayCountrySelection(
            countryOverrides = mapOf("AU" to HolidayOverride.OFF, "FR" to HolidayOverride.ON),
        )
        selection.countryEffective("AU", "AU", "GB") shouldBe false
        selection.countryEffective("FR", "AU", "GB") shouldBe true
        // No override → falls back to buckets.
        selection.countryEffective("GB", "AU", "GB") shouldBe true
        selection.countryEffective("US", "AU", "GB") shouldBe false
    }
}
