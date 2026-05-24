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
    fun `null or blank locale and weather country still keeps GLOBAL_COUNTRY by default`() {
        HolidayCountrySelection().resolveEnabledCountries(
            localeCountry = null,
            weatherLocationCountry = "   ",
            allCountries = allCountries,
        ).shouldContainExactlyInAnyOrder(HolidayCatalog.GLOBAL_COUNTRY)
    }

    @Test
    fun `null locale and weather with global=false returns empty set`() {
        HolidayCountrySelection(global = false).resolveEnabledCountries(
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
        HolidayCountrySelection(home = false, current = false, global = false, all = false)
            .resolveEnabledCountries(
                localeCountry = "AU",
                weatherLocationCountry = "GB",
                allCountries = allCountries,
            ) shouldBe emptySet()
    }

    @Test
    fun `per-country ON override enables a country without home or current`() {
        HolidayCountrySelection(
            home = false,
            current = false,
            global = false,
            countryOverrides = mapOf("FR" to HolidayOverride.ON),
        ).resolveEnabledCountries(
            localeCountry = "AU",
            weatherLocationCountry = "GB",
            allCountries = allCountries,
        ).shouldContainExactlyInAnyOrder("FR")
    }

    @Test
    fun `global=false drops GLOBAL_COUNTRY even with home and current on`() {
        HolidayCountrySelection(global = false).resolveEnabledCountries(
            localeCountry = "AU",
            weatherLocationCountry = "GB",
            allCountries = allCountries,
        ).shouldContainExactlyInAnyOrder("AU", "GB")
    }

    @Test
    fun `global=true alone keeps GLOBAL_COUNTRY when home and current are off`() {
        HolidayCountrySelection(home = false, current = false).resolveEnabledCountries(
            localeCountry = "AU",
            weatherLocationCountry = "GB",
            allCountries = allCountries,
        ).shouldContainExactlyInAnyOrder(HolidayCatalog.GLOBAL_COUNTRY)
    }

    @Test
    fun `per-country OFF on GLOBAL wins over global=true`() {
        HolidayCountrySelection(
            countryOverrides = mapOf(HolidayCatalog.GLOBAL_COUNTRY to HolidayOverride.OFF),
        ).resolveEnabledCountries(
            localeCountry = "AU",
            weatherLocationCountry = "GB",
            allCountries = allCountries,
        ).shouldContainExactlyInAnyOrder("AU", "GB")
    }

    @Test
    fun `per-country ON on GLOBAL wins over global=false`() {
        HolidayCountrySelection(
            home = false,
            current = false,
            global = false,
            countryOverrides = mapOf(HolidayCatalog.GLOBAL_COUNTRY to HolidayOverride.ON),
        ).resolveEnabledCountries(
            localeCountry = "AU",
            weatherLocationCountry = "GB",
            allCountries = allCountries,
        ).shouldContainExactlyInAnyOrder(HolidayCatalog.GLOBAL_COUNTRY)
    }

    @Test
    fun `all=true short-circuits global=false to include GLOBAL_COUNTRY`() {
        HolidayCountrySelection(
            home = false,
            current = false,
            global = false,
            all = true,
        ).resolveEnabledCountries(
            localeCountry = null,
            weatherLocationCountry = null,
            allCountries = allCountries,
        ).shouldContainExactlyInAnyOrder("AU", "GB", "US", "FR", "DE", HolidayCatalog.GLOBAL_COUNTRY)
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

    // --- Funny bucket: mirrors the Global bucket, on by default. ---

    private val funnyCountries = setOf("AU", "GB", HolidayCatalog.FUNNY, HolidayCatalog.GLOBAL_COUNTRY)

    @Test
    fun `funny=true by default rides along with home and current`() {
        HolidayCountrySelection().resolveEnabledCountries(
            localeCountry = "AU",
            weatherLocationCountry = "GB",
            allCountries = funnyCountries,
        ).shouldContainExactlyInAnyOrder(
            "AU", "GB", HolidayCatalog.FUNNY, HolidayCatalog.GLOBAL_COUNTRY,
        )
    }

    @Test
    fun `funny=false drops FUNNY even with home and current on`() {
        HolidayCountrySelection(funny = false).resolveEnabledCountries(
            localeCountry = "AU",
            weatherLocationCountry = "GB",
            allCountries = funnyCountries,
        ).shouldContainExactlyInAnyOrder("AU", "GB", HolidayCatalog.GLOBAL_COUNTRY)
    }

    @Test
    fun `funny=true alone keeps FUNNY when home current and global are off`() {
        HolidayCountrySelection(home = false, current = false, global = false)
            .resolveEnabledCountries(
                localeCountry = "AU",
                weatherLocationCountry = "GB",
                allCountries = funnyCountries,
            ).shouldContainExactlyInAnyOrder(HolidayCatalog.FUNNY)
    }

    @Test
    fun `per-country OFF on FUNNY wins over funny=true`() {
        HolidayCountrySelection(
            countryOverrides = mapOf(HolidayCatalog.FUNNY to HolidayOverride.OFF),
        ).resolveEnabledCountries(
            localeCountry = "AU",
            weatherLocationCountry = "GB",
            allCountries = funnyCountries,
        ).shouldContainExactlyInAnyOrder("AU", "GB", HolidayCatalog.GLOBAL_COUNTRY)
    }

    @Test
    fun `all=true short-circuits funny=false to include FUNNY`() {
        HolidayCountrySelection(
            home = false,
            current = false,
            funny = false,
            all = true,
        ).resolveEnabledCountries(
            localeCountry = null,
            weatherLocationCountry = null,
            allCountries = funnyCountries,
        ).shouldContainExactlyInAnyOrder(
            "AU", "GB", HolidayCatalog.FUNNY, HolidayCatalog.GLOBAL_COUNTRY,
        )
    }

    // --- Christian and Orthodox religious buckets. Both are off by
    // default — these are religious-tradition observances rather than
    // universal civic days. Users in Christian-tradition countries with
    // public holidays still pick up the relevant entries via their
    // `home` country (each holiday carries both the bucket sentinel and
    // the per-country ISO codes where it's a public holiday).

    private val religiousCountries = setOf(
        "AU", "GB", HolidayCatalog.GLOBAL_COUNTRY,
        HolidayCatalog.CHRISTIAN, HolidayCatalog.ORTHODOX,
    )

    @Test
    fun `christian and orthodox are both off by default`() {
        HolidayCountrySelection().resolveEnabledCountries(
            localeCountry = "AU",
            weatherLocationCountry = "GB",
            allCountries = religiousCountries,
        ).shouldContainExactlyInAnyOrder(
            "AU", "GB", HolidayCatalog.GLOBAL_COUNTRY,
        )
    }

    @Test
    fun `christian=true picks up the Christian bucket`() {
        HolidayCountrySelection(christian = true).resolveEnabledCountries(
            localeCountry = "AU",
            weatherLocationCountry = "GB",
            allCountries = religiousCountries,
        ).shouldContainExactlyInAnyOrder(
            "AU", "GB", HolidayCatalog.GLOBAL_COUNTRY, HolidayCatalog.CHRISTIAN,
        )
    }

    @Test
    fun `orthodox=true picks up the Orthodox bucket`() {
        HolidayCountrySelection(orthodox = true).resolveEnabledCountries(
            localeCountry = "AU",
            weatherLocationCountry = "GB",
            allCountries = religiousCountries,
        ).shouldContainExactlyInAnyOrder(
            "AU", "GB", HolidayCatalog.GLOBAL_COUNTRY, HolidayCatalog.ORTHODOX,
        )
    }

    @Test
    fun `per-country ON on CHRISTIAN wins over christian=false`() {
        HolidayCountrySelection(
            countryOverrides = mapOf(HolidayCatalog.CHRISTIAN to HolidayOverride.ON),
        ).resolveEnabledCountries(
            localeCountry = "AU",
            weatherLocationCountry = "GB",
            allCountries = religiousCountries,
        ).shouldContainExactlyInAnyOrder(
            "AU", "GB", HolidayCatalog.GLOBAL_COUNTRY, HolidayCatalog.CHRISTIAN,
        )
    }

    @Test
    fun `per-country ON on ORTHODOX wins over orthodox=false`() {
        HolidayCountrySelection(
            countryOverrides = mapOf(HolidayCatalog.ORTHODOX to HolidayOverride.ON),
        ).resolveEnabledCountries(
            localeCountry = "AU",
            weatherLocationCountry = "GB",
            allCountries = religiousCountries,
        ).shouldContainExactlyInAnyOrder(
            "AU", "GB", HolidayCatalog.GLOBAL_COUNTRY, HolidayCatalog.ORTHODOX,
        )
    }

    @Test
    fun `all=true short-circuits christian=false and orthodox=false to include both`() {
        HolidayCountrySelection(
            home = false,
            current = false,
            all = true,
        ).resolveEnabledCountries(
            localeCountry = null,
            weatherLocationCountry = null,
            allCountries = religiousCountries,
        ).shouldContainExactlyInAnyOrder(
            "AU", "GB", HolidayCatalog.GLOBAL_COUNTRY,
            HolidayCatalog.CHRISTIAN, HolidayCatalog.ORTHODOX,
        )
    }
}
