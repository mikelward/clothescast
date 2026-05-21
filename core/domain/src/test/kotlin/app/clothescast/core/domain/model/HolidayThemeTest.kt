package app.clothescast.core.domain.model

import io.kotest.matchers.shouldBe
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
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

    @Test
    fun `dateIn materialises Fixed at month and day`() {
        HolidayDate.Fixed(Month.JULY, 4).dateIn(2026) shouldBe LocalDate.of(2026, 7, 4)
        HolidayDate.Fixed(Month.DECEMBER, 25).dateIn(2027) shouldBe LocalDate.of(2027, 12, 25)
    }

    @Test
    fun `dateIn materialises NthWeekday correctly`() {
        // 2026 — May 10 is 2nd Sunday (May 3 is 1st), Nov 26 is 4th Thursday.
        HolidayDate.NthWeekday(Month.MAY, 2, DayOfWeek.SUNDAY).dateIn(2026) shouldBe
            LocalDate.of(2026, 5, 10)
        HolidayDate.NthWeekday(Month.NOVEMBER, 4, DayOfWeek.THURSDAY).dateIn(2026) shouldBe
            LocalDate.of(2026, 11, 26)
        // First-of-month edge: when month starts on the target weekday, nth=1 IS day 1.
        HolidayDate.NthWeekday(Month.NOVEMBER, 1, DayOfWeek.SUNDAY).dateIn(2026) shouldBe
            LocalDate.of(2026, 11, 1)
    }

    @Test
    fun `dateIn materialises LastWeekday correctly`() {
        // 2026 — May 25 (Mon) is the last Monday of May; Feb 28 (Sat) the last
        // Saturday of February in a non-leap year.
        HolidayDate.LastWeekday(Month.MAY, DayOfWeek.MONDAY).dateIn(2026) shouldBe
            LocalDate.of(2026, 5, 25)
        HolidayDate.LastWeekday(Month.FEBRUARY, DayOfWeek.SATURDAY).dateIn(2026) shouldBe
            LocalDate.of(2026, 2, 28)
        // 2024 was a leap year — Feb 29 was a Thursday, so the last Thursday is the 29th.
        HolidayDate.LastWeekday(Month.FEBRUARY, DayOfWeek.THURSDAY).dateIn(2024) shouldBe
            LocalDate.of(2024, 2, 29)
    }

    @Test
    fun `easterSundayGregorian computes the known reference years`() {
        // Cross-checked against published Western Easter tables.
        HolidayDate.EasterRelative.easterSundayGregorian(2024) shouldBe LocalDate.of(2024, 3, 31)
        HolidayDate.EasterRelative.easterSundayGregorian(2025) shouldBe LocalDate.of(2025, 4, 20)
        HolidayDate.EasterRelative.easterSundayGregorian(2026) shouldBe LocalDate.of(2026, 4, 5)
        HolidayDate.EasterRelative.easterSundayGregorian(2027) shouldBe LocalDate.of(2027, 3, 28)
        HolidayDate.EasterRelative.easterSundayGregorian(2028) shouldBe LocalDate.of(2028, 4, 16)
        HolidayDate.EasterRelative.easterSundayGregorian(2029) shouldBe LocalDate.of(2029, 4, 1)
        HolidayDate.EasterRelative.easterSundayGregorian(2030) shouldBe LocalDate.of(2030, 4, 21)
    }

    @Test
    fun `dateIn materialises EasterRelative against the per-year Easter`() {
        // 2026: Easter Sunday Apr 5 → Good Friday Apr 3, Easter Monday Apr 6,
        // Mothering Sunday (4th Sun of Lent, Easter − 21) → Mar 15.
        HolidayDate.EasterRelative(0).dateIn(2026) shouldBe LocalDate.of(2026, 4, 5)
        HolidayDate.EasterRelative(-2).dateIn(2026) shouldBe LocalDate.of(2026, 4, 3)
        HolidayDate.EasterRelative(1).dateIn(2026) shouldBe LocalDate.of(2026, 4, 6)
        HolidayDate.EasterRelative(-21).dateIn(2026) shouldBe LocalDate.of(2026, 3, 15)
    }

    @Test
    fun `EasterRelative matches only the materialised date for that year`() {
        val goodFriday = HolidayDate.EasterRelative(-2)
        // Apr 3 2026 = Good Friday in 2026.
        goodFriday.matches(LocalDate.of(2026, 4, 3)) shouldBe true
        // Apr 3 in another year is *not* Good Friday — matches() recomputes
        // per-year rather than hard-coding the 2026 date.
        goodFriday.matches(LocalDate.of(2027, 4, 3)) shouldBe false
        // Mar 26 2027 is the right Good Friday for that year.
        goodFriday.matches(LocalDate.of(2027, 3, 26)) shouldBe true
    }

    @Test
    fun `dateIn materialises US Election Day as the Tuesday after the first Monday`() {
        val electionDay = HolidayDate.FirstWeekdayOnOrAfter(Month.NOVEMBER, DayOfWeek.TUESDAY, 2)
        // Cross-checked against published US general-election dates.
        electionDay.dateIn(2024) shouldBe LocalDate.of(2024, 11, 5)
        electionDay.dateIn(2026) shouldBe LocalDate.of(2026, 11, 3)
        electionDay.dateIn(2028) shouldBe LocalDate.of(2028, 11, 7)
    }

    @Test
    fun `FirstWeekdayOnOrAfter matches only the single weekday in its window`() {
        val electionDay = HolidayDate.FirstWeekdayOnOrAfter(Month.NOVEMBER, DayOfWeek.TUESDAY, 2)
        electionDay.matches(LocalDate.of(2026, 11, 3)) shouldBe true
        // A later Tuesday in November is outside the Nov 2–8 window.
        electionDay.matches(LocalDate.of(2026, 11, 10)) shouldBe false
        // Nov 1 (before the floor) never qualifies even when it's a Tuesday.
        val nov1Tuesday = HolidayDate.FirstWeekdayOnOrAfter(Month.NOVEMBER, DayOfWeek.TUESDAY, 2)
        nov1Tuesday.matches(LocalDate.of(2027, 11, 1)) shouldBe false
        // Wrong weekday in range is rejected.
        electionDay.matches(LocalDate.of(2026, 11, 4)) shouldBe false
    }

    @Test
    fun `dateIn materialises Melbourne Cup as the first Tuesday of November`() {
        HolidayDate.NthWeekday(Month.NOVEMBER, 1, DayOfWeek.TUESDAY).dateIn(2025) shouldBe
            LocalDate.of(2025, 11, 4)
        HolidayDate.NthWeekday(Month.NOVEMBER, 1, DayOfWeek.TUESDAY).dateIn(2026) shouldBe
            LocalDate.of(2026, 11, 3)
    }

    @Test
    fun `Labor Day banner uses the Canadian spelling for CA`() {
        val theme = HolidayCatalog.themeFor(HolidayId.US_LABOR_DAY)
            ?: error("US_LABOR_DAY missing from catalog")
        theme.bannerTextKeyFor("US") shouldBe "holiday_banner_us_labor_day"
        theme.bannerTextKeyFor("CA") shouldBe "holiday_banner_ca_labour_day"
        theme.bannerTextKeyFor("ca") shouldBe "holiday_banner_ca_labour_day"
    }

    @Test
    fun `normalizeCalendarHolidayTitle strips a trailing region in parentheses`() {
        normalizeCalendarHolidayTitle("King's Birthday (Western Australia)") shouldBe
            "King's Birthday"
        normalizeCalendarHolidayTitle("Labour Day (most regions)") shouldBe "Labour Day"
        normalizeCalendarHolidayTitle("Presidents' Day (Observed)") shouldBe "Presidents' Day"
    }

    @Test
    fun `normalizeCalendarHolidayTitle folds apostrophes and trims trailing punctuation`() {
        // Curly apostrophe folded to straight; trailing period dropped.
        normalizeCalendarHolidayTitle("Presidents’ Day.") shouldBe "Presidents' Day"
        // Region suffix plus trailing punctuation in combination.
        normalizeCalendarHolidayTitle("King’s Birthday (WA)!") shouldBe "King's Birthday"
        // No region, no punctuation — passes through unchanged.
        normalizeCalendarHolidayTitle("Cinco de Mayo") shouldBe "Cinco de Mayo"
    }

    @Test
    fun `normalizeCalendarHolidayTitle keeps the raw text when stripping would empty it`() {
        // A title that is *only* a parenthetical falls back to the raw trim so
        // the banner never goes blank.
        normalizeCalendarHolidayTitle("(Western Australia)") shouldBe "(Western Australia)"
    }
}
