package app.clothescast.core.domain.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HolidayThemeTest {

    /**
     * Remembrance Day's banner-text key flips to "Veterans Day" only when
     * the user's effective country is US. Everything else falls through to
     * the default. Country matching is case-insensitive and tolerant of
     * a null / blank country (e.g. an unresolved Region).
     */
    @Test
    fun `bannerTextKeyFor falls back to the default for non-US countries`() {
        val theme = HolidayCatalog.themeFor(HolidayId.REMEMBRANCE_DAY)
            ?: error("REMEMBRANCE_DAY missing from catalog")
        theme.bannerTextKeyFor("GB") shouldBe "holiday_banner_remembrance_day"
        theme.bannerTextKeyFor("AU") shouldBe "holiday_banner_remembrance_day"
        theme.bannerTextKeyFor("CA") shouldBe "holiday_banner_remembrance_day"
        theme.bannerTextKeyFor("NZ") shouldBe "holiday_banner_remembrance_day"
    }

    @Test
    fun `bannerTextKeyFor returns Veterans Day for US`() {
        val theme = HolidayCatalog.themeFor(HolidayId.REMEMBRANCE_DAY)
            ?: error("REMEMBRANCE_DAY missing from catalog")
        theme.bannerTextKeyFor("US") shouldBe "holiday_banner_us_veterans_day"
        // Case-insensitive: a lower-cased country from Locale.getCountry()
        // on some JDKs would still resolve.
        theme.bannerTextKeyFor("us") shouldBe "holiday_banner_us_veterans_day"
    }

    @Test
    fun `bannerTextKeyFor returns Armistice Day for FR`() {
        val theme = HolidayCatalog.themeFor(HolidayId.REMEMBRANCE_DAY)
            ?: error("REMEMBRANCE_DAY missing from catalog")
        theme.bannerTextKeyFor("FR") shouldBe "holiday_banner_fr_armistice_day"
        theme.bannerTextKeyFor("fr") shouldBe "holiday_banner_fr_armistice_day"
    }

    @Test
    fun `bannerTextKeyFor falls back to default on null or blank country`() {
        val theme = HolidayCatalog.themeFor(HolidayId.REMEMBRANCE_DAY)
            ?: error("REMEMBRANCE_DAY missing from catalog")
        theme.bannerTextKeyFor(null) shouldBe "holiday_banner_remembrance_day"
        theme.bannerTextKeyFor("") shouldBe "holiday_banner_remembrance_day"
        theme.bannerTextKeyFor("   ") shouldBe "holiday_banner_remembrance_day"
    }

    @Test
    fun `bannerTextKeyFor returns the default for holidays without per-country overrides`() {
        val theme = HolidayCatalog.themeFor(HolidayId.CHRISTMAS_DAY)
            ?: error("CHRISTMAS_DAY missing from catalog")
        theme.bannerTextKeyFor("US") shouldBe "holiday_banner_christmas_day"
        theme.bannerTextKeyFor("GB") shouldBe "holiday_banner_christmas_day"
    }
}
