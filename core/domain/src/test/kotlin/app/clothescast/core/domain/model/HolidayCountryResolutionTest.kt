package app.clothescast.core.domain.model

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HolidayCountryResolutionTest {
    private val allCountries = setOf("AU", "GB", "US", HolidayCatalog.GLOBAL_COUNTRY)

    @Test
    fun `all=true returns the allCountries set verbatim`() {
        HolidayCountrySelection(home = false, current = false, all = true)
            .resolveEnabledCountries(
                localeCountry = "JP",
                weatherLocationCountry = "KR",
                allCountries = allCountries,
            ) shouldBe allCountries
    }

    @Test
    fun `all=true ignores home current and countries`() {
        HolidayCountrySelection(
            home = true,
            current = true,
            all = true,
            countries = setOf("FR"),
        ).resolveEnabledCountries(
            localeCountry = "AU",
            weatherLocationCountry = "GB",
            allCountries = allCountries,
        ) shouldBe allCountries
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
    fun `per-country opt-ins union with home and current`() {
        HolidayCountrySelection(countries = setOf("FR", "DE"))
            .resolveEnabledCountries(
                localeCountry = "AU",
                weatherLocationCountry = "GB",
                allCountries = allCountries,
            ).shouldContainExactlyInAnyOrder("AU", "GB", "FR", "DE", HolidayCatalog.GLOBAL_COUNTRY)
    }

    @Test
    fun `null or blank locale and weather country with no opt-ins returns empty set`() {
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
    fun `per-country opt-ins alone enable Global without home or current`() {
        HolidayCountrySelection(home = false, current = false, countries = setOf("FR"))
            .resolveEnabledCountries(
                localeCountry = "AU",
                weatherLocationCountry = "GB",
                allCountries = allCountries,
            ).shouldContainExactlyInAnyOrder("FR", HolidayCatalog.GLOBAL_COUNTRY)
    }
}
