package app.clothescast.core.domain.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HolidayFlagsTest {

    @Test
    fun `a country code becomes its flag`() {
        flagEmoji("US") shouldBe "🇺🇸"
        flagEmoji("gb") shouldBe "🇬🇧"
    }

    @Test
    fun `pseudo-countries have no flag`() {
        flagEmoji(HolidayCatalog.GLOBAL_COUNTRY) shouldBe null
        flagEmoji(HolidayCatalog.FUNNY) shouldBe null
    }

    @Test
    fun `a Google regional holiday calendar names its country`() {
        holidayCalendarCountry("en.usa#holiday@group.v.calendar.google.com") shouldBe "US"
        holidayCalendarCountry("en.uk#holiday@group.v.calendar.google.com") shouldBe "GB"
        holidayCalendarCountry("en-gb.australian#holiday@group.v.calendar.google.com") shouldBe "AU"
        holidayCalendarCountry("fr.be#holiday@group.v.calendar.google.com") shouldBe "BE"
    }

    @Test
    fun `a device calendar id is read past its account`() {
        holidayCalendarCountry("alice@example.com/en.usa#holiday@group.v.calendar.google.com") shouldBe "US"
    }

    @Test
    fun `other calendars have no country`() {
        holidayCalendarCountry("en.judaism#holiday@group.v.calendar.google.com") shouldBe null
        holidayCalendarCountry("en.islamic#holiday@group.v.calendar.google.com") shouldBe null
        holidayCalendarCountry("alice@example.com") shouldBe null
        holidayCalendarCountry(null) shouldBe null
    }
}
