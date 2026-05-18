package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.CalendarEvent
import app.clothescast.core.domain.model.EventKind
import app.clothescast.core.domain.model.HolidayCatalog
import app.clothescast.core.domain.model.HolidayId
import app.clothescast.core.domain.model.HolidayOverride
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import org.junit.jupiter.api.Test

class ThemeForTodayTest {
    private val subject = ThemeForToday()
    private val noOverrides: Map<HolidayId, HolidayOverride> = emptyMap()
    private val allCountries: Set<String> = HolidayCatalog.allCountries
    private val random = LocalDate.of(2026, Month.MAY, 18)
    private val christmas = LocalDate.of(2026, Month.DECEMBER, 25)

    private fun publicHoliday(title: String) = CalendarEvent(
        title = title,
        start = LocalTime.MIDNIGHT,
        end = LocalTime.MIDNIGHT,
        allDay = true,
        kind = EventKind.PUBLIC_HOLIDAY,
    )

    private fun birthday(title: String) = CalendarEvent(
        title = title,
        start = LocalTime.MIDNIGHT,
        end = LocalTime.MIDNIGHT,
        allDay = true,
        kind = EventKind.BIRTHDAY,
    )

    @Test
    fun `user-off override on a curated date suppresses calendar fallback`() {
        // User opted out of Christmas via the per-holiday OverrideDropdown.
        // HolidayResolver returns null because of the override. We must
        // honour their choice — a Google-calendar "Christmas Day" event
        // should NOT slip in as a synthetic theme on the same date.
        val theme = subject.resolve(
            date = christmas,
            overrides = mapOf(HolidayId.CHRISTMAS_DAY to HolidayOverride.OFF),
            enabledCountries = allCountries,
            events = listOf(publicHoliday("Christmas Day")),
            themeFromCalendarHolidays = true,
            themeFromCalendarBirthdays = true,
        )
        theme.shouldBeNull()
    }

    @Test
    fun `country-bucket-off lets calendar fallback fire`() {
        // The user disabled the Global bucket (so curated Christmas doesn't
        // fire via its bucket) but didn't explicitly opt out of Christmas —
        // they just narrowed their country interest. If they ALSO opted
        // into calendar holidays, the calendar event SHOULD fire: the
        // bucket opt-out is about catalog scope, not a specific-holiday
        // opt-out. Per-holiday OFF overrides remain the strong signal.
        val theme = subject.resolve(
            date = christmas,
            overrides = noOverrides,
            enabledCountries = emptySet(),
            events = listOf(publicHoliday("Christmas Day")),
            themeFromCalendarHolidays = true,
            themeFromCalendarBirthdays = true,
        )
        theme.shouldNotBeNull()
        theme.id shouldBe HolidayId.GENERIC_PUBLIC_HOLIDAY
    }

    @Test
    fun `unrelated curated entry for an unenabled country doesn't suppress calendar fallback`() {
        // Codex regression: Apr 25 has Anzac Day (AU/NZ) and Italian
        // Liberation Day (IT) in the curated catalog. A Portuguese user
        // who opted into calendar holidays should still see Freedom Day
        // (Portugal's Apr 25 holiday) from their PT holiday calendar —
        // the catalog has no entry *they* could act on, so the fallback
        // must fire.
        val apr25 = LocalDate.of(2026, java.time.Month.APRIL, 25)
        val theme = subject.resolve(
            date = apr25,
            overrides = noOverrides,
            enabledCountries = setOf("PT"),
            events = listOf(publicHoliday("Freedom Day")),
            themeFromCalendarHolidays = true,
            themeFromCalendarBirthdays = false,
        )
        theme.shouldNotBeNull()
        theme.id shouldBe HolidayId.GENERIC_PUBLIC_HOLIDAY
        theme.displayTitleOverride shouldBe "Freedom Day"
    }

    @Test
    fun `birthday on a suppressed curated date still fires`() {
        // The suppression rule only blocks the public-holiday fallback —
        // a detected birthday on the same date is unrelated and still
        // themes (the user didn't opt out of birthdays, just Christmas).
        val theme = subject.resolve(
            date = christmas,
            overrides = mapOf(HolidayId.CHRISTMAS_DAY to HolidayOverride.OFF),
            enabledCountries = allCountries,
            events = listOf(birthday("Alice's birthday")),
            themeFromCalendarHolidays = true,
            themeFromCalendarBirthdays = true,
        )
        theme.shouldNotBeNull()
        theme.id shouldBe HolidayId.BIRTHDAY
    }

    @Test
    fun `curated catalog wins over calendar holiday on same date`() {
        val theme = subject.resolve(
            date = christmas,
            overrides = noOverrides,
            enabledCountries = allCountries,
            events = listOf(publicHoliday("Christmas Day")),
            themeFromCalendarHolidays = true,
            themeFromCalendarBirthdays = true,
        )
        theme.shouldNotBeNull()
        theme.id shouldBe HolidayId.CHRISTMAS_DAY
        theme.isSynthetic shouldBe false
    }

    @Test
    fun `calendar holiday fills catalog gap when toggle on`() {
        val theme = subject.resolve(
            date = random,
            overrides = noOverrides,
            enabledCountries = allCountries,
            events = listOf(publicHoliday("Diwali")),
            themeFromCalendarHolidays = true,
            themeFromCalendarBirthdays = false,
        )
        theme.shouldNotBeNull()
        theme.id shouldBe HolidayId.GENERIC_PUBLIC_HOLIDAY
        theme.isSynthetic shouldBe true
        theme.displayTitleOverride shouldBe "Diwali"
    }

    @Test
    fun `calendar holiday ignored when toggle off`() {
        subject.resolve(
            date = random,
            overrides = noOverrides,
            enabledCountries = allCountries,
            events = listOf(publicHoliday("Diwali")),
            themeFromCalendarHolidays = false,
            themeFromCalendarBirthdays = false,
        ).shouldBeNull()
    }

    @Test
    fun `birthday theme fires when toggle on and no curated match`() {
        val theme = subject.resolve(
            date = random,
            overrides = noOverrides,
            enabledCountries = allCountries,
            events = listOf(birthday("Alice's birthday")),
            themeFromCalendarHolidays = false,
            themeFromCalendarBirthdays = true,
        )
        theme.shouldNotBeNull()
        theme.id shouldBe HolidayId.BIRTHDAY
        theme.displayTitleOverride shouldBe "Alice's birthday"
    }

    @Test
    fun `birthday ignored when toggle off`() {
        subject.resolve(
            date = random,
            overrides = noOverrides,
            enabledCountries = allCountries,
            events = listOf(birthday("Alice's birthday")),
            themeFromCalendarHolidays = false,
            themeFromCalendarBirthdays = false,
        ).shouldBeNull()
    }

    @Test
    fun `public holiday takes precedence over birthday when both are present`() {
        val theme = subject.resolve(
            date = random,
            overrides = noOverrides,
            enabledCountries = allCountries,
            events = listOf(birthday("Alice's birthday"), publicHoliday("Diwali")),
            themeFromCalendarHolidays = true,
            themeFromCalendarBirthdays = true,
        )
        theme.shouldNotBeNull()
        theme.id shouldBe HolidayId.GENERIC_PUBLIC_HOLIDAY
    }

    @Test
    fun `empty event list with no curated match returns null`() {
        subject.resolve(
            date = random,
            overrides = noOverrides,
            enabledCountries = allCountries,
            events = emptyList(),
            themeFromCalendarHolidays = true,
            themeFromCalendarBirthdays = true,
        ).shouldBeNull()
    }

    @Test
    fun `NORMAL kind events are ignored`() {
        val normalEvent = CalendarEvent(
            title = "Standup",
            start = LocalTime.of(9, 0),
            end = LocalTime.of(9, 30),
            kind = EventKind.NORMAL,
        )
        subject.resolve(
            date = random,
            overrides = noOverrides,
            enabledCountries = allCountries,
            events = listOf(normalEvent),
            themeFromCalendarHolidays = true,
            themeFromCalendarBirthdays = true,
        ).shouldBeNull()
    }
}
