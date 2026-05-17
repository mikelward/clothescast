package app.clothescast.core.domain.model

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HolidayCountryResolutionTest {
    private val allCountries = setOf("AU", "GB", "US", HolidayCatalog.GLOBAL_COUNTRY)

    @Test
    fun `ALL returns the allCountries set verbatim`() {
        resolveEffectiveHolidayCountries(
            mode = HolidayCountryMode.ALL,
            customCountries = setOf("XX"),
            localeCountry = "JP",
            weatherLocationCountry = "KR",
            allCountries = allCountries,
        ) shouldBe allCountries
    }

    @Test
    fun `CUSTOM returns the user pick verbatim`() {
        val pick = setOf("US", HolidayCatalog.GLOBAL_COUNTRY)
        resolveEffectiveHolidayCountries(
            mode = HolidayCountryMode.CUSTOM,
            customCountries = pick,
            localeCountry = "JP",
            weatherLocationCountry = "KR",
            allCountries = allCountries,
        ) shouldBe pick
    }

    @Test
    fun `AUTO is locale plus weather plus GLOBAL`() {
        resolveEffectiveHolidayCountries(
            mode = HolidayCountryMode.AUTO,
            customCountries = emptySet(),
            localeCountry = "AU",
            weatherLocationCountry = "GB",
            allCountries = allCountries,
        ).shouldContainExactlyInAnyOrder("AU", "GB", HolidayCatalog.GLOBAL_COUNTRY)
    }

    @Test
    fun `AUTO uppercases lowercase ISO codes`() {
        resolveEffectiveHolidayCountries(
            mode = HolidayCountryMode.AUTO,
            customCountries = emptySet(),
            localeCountry = "au",
            weatherLocationCountry = "gb",
            allCountries = allCountries,
        ).shouldContainExactlyInAnyOrder("AU", "GB", HolidayCatalog.GLOBAL_COUNTRY)
    }

    @Test
    fun `AUTO ignores null and blank locale or weather country`() {
        resolveEffectiveHolidayCountries(
            mode = HolidayCountryMode.AUTO,
            customCountries = emptySet(),
            localeCountry = null,
            weatherLocationCountry = "   ",
            allCountries = allCountries,
        ) shouldBe setOf(HolidayCatalog.GLOBAL_COUNTRY)
    }

    @Test
    fun `AUTO deduplicates identical locale and weather countries`() {
        resolveEffectiveHolidayCountries(
            mode = HolidayCountryMode.AUTO,
            customCountries = emptySet(),
            localeCountry = "AU",
            weatherLocationCountry = "AU",
            allCountries = allCountries,
        ).shouldContainExactlyInAnyOrder("AU", HolidayCatalog.GLOBAL_COUNTRY)
    }
}
