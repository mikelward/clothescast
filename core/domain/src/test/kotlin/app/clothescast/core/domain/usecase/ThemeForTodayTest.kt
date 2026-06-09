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
import java.time.Month
import org.junit.jupiter.api.Test

class ThemeForTodayTest {
    private val subject = ThemeForToday()
    private val noOverrides: Map<HolidayId, HolidayOverride> = emptyMap()
    private val allCountries: Set<String> = HolidayCatalog.allCountries
    private val random = LocalDate.of(2026, Month.MAY, 18)
    private val christmas = LocalDate.of(2026, Month.DECEMBER, 25)

    // ThemeForToday reads only title/kind/ownerAccount, so the events'
    // anchor date is irrelevant to these tests; [random] keeps it stable.
    private fun publicHoliday(title: String, ownerAccount: String? = null) = CalendarEvent(
        title = title,
        start = random.atStartOfDay(),
        end = random.atStartOfDay(),
        allDay = true,
        kind = EventKind.PUBLIC_HOLIDAY,
        ownerAccount = ownerAccount,
    )

    private fun birthday(title: String) = CalendarEvent(
        title = title,
        start = random.atStartOfDay(),
        end = random.atStartOfDay(),
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
    fun `same birthday synced from two calendars dedupes to one contributor`() {
        // Eva's birthday lives in both the personal calendar ("Eva's birthday")
        // and a shared one ("Eva's birthday!"). They should collapse to a single
        // standalone birthday banner, not double up as "Eva and Eva".
        val theme = subject.resolve(
            date = random,
            overrides = noOverrides,
            enabledCountries = allCountries,
            events = listOf(birthday("Eva's birthday"), birthday("Eva's birthday!")),
            themeFromCalendarHolidays = false,
            themeFromCalendarBirthdays = true,
        )
        theme.shouldNotBeNull()
        theme.id shouldBe HolidayId.BIRTHDAY
        // A single contributor renders its standalone banner (no join segments).
        theme.bannerSegments.shouldBeNull()
        theme.displayTitleOverride shouldBe "Eva's birthday"
    }

    @Test
    fun `two different people with birthdays today both surface`() {
        val theme = subject.resolve(
            date = random,
            overrides = noOverrides,
            enabledCountries = allCountries,
            events = listOf(birthday("Alice's birthday"), birthday("Bob's birthday")),
            themeFromCalendarHolidays = false,
            themeFromCalendarBirthdays = true,
        )
        theme.shouldNotBeNull()
        theme.id shouldBe HolidayId.BIRTHDAY
        val segments = theme.bannerSegments
        segments.shouldNotBeNull()
        segments.map { it.literalText } shouldBe listOf("Alice's birthday", "Bob's birthday")
    }

    @Test
    fun `public holiday and birthday on the same day join into one banner`() {
        val theme = subject.resolve(
            date = random,
            overrides = noOverrides,
            enabledCountries = allCountries,
            events = listOf(birthday("Alice's birthday"), publicHoliday("Diwali")),
            themeFromCalendarHolidays = true,
            themeFromCalendarBirthdays = true,
        )
        theme.shouldNotBeNull()
        // Colours come from the public holiday (no Funny theme present).
        theme.id shouldBe HolidayId.GENERIC_PUBLIC_HOLIDAY
        // Both celebrations surface, public holiday first, birthday second.
        val segments = theme.bannerSegments
        segments.shouldNotBeNull()
        segments.map { it.literalText } shouldBe listOf("Diwali", "Alice's birthday")
    }

    @Test
    fun `curated holiday and birthday on the same day join`() {
        val theme = subject.resolve(
            date = christmas,
            overrides = noOverrides,
            enabledCountries = allCountries,
            events = listOf(birthday("Alice's birthday")),
            themeFromCalendarHolidays = true,
            themeFromCalendarBirthdays = true,
        )
        theme.shouldNotBeNull()
        // Christmas supplies identity + colours; birthday joins the banner.
        theme.id shouldBe HolidayId.CHRISTMAS_DAY
        theme.isSynthetic shouldBe false
        val segments = theme.bannerSegments
        segments.shouldNotBeNull()
        segments[0].textKey shouldBe "holiday_banner_christmas_day"
        segments[1].literalText shouldBe "Alice's birthday"
    }

    @Test
    fun `bank holiday and Towel Day collision composes with funny colours and join clause`() {
        // 2026-05-25 is the last Monday of May, so UK Spring Bank Holiday
        // coincides with Towel Day for a GB user with Funny on.
        val theme = subject.resolve(
            date = LocalDate.of(2026, Month.MAY, 25),
            overrides = noOverrides,
            enabledCountries = setOf("GB", HolidayCatalog.FUNNY),
            events = emptyList(),
            themeFromCalendarHolidays = false,
            themeFromCalendarBirthdays = false,
        )
        theme.shouldNotBeNull()
        // Colours/emoji come from Towel Day (the Funny theme).
        theme.id shouldBe HolidayId.TOWEL_DAY
        theme.emoji shouldBe "🪐"
        val segments = theme.bannerSegments
        segments.shouldNotBeNull()
        // Primary banner first, funny *join* clause (not the standalone) last.
        segments[0].textKey shouldBe "holiday_banner_uk_bank_holiday"
        segments[1].textKey shouldBe "holiday_banner_join_towel_day"
    }

    @Test
    fun `solemn day suppresses the Towel Day clause on a collision`() {
        // Same date, but a US user: Memorial Day is solemn, so Towel Day is
        // dropped entirely — no playful clause beside "Honoring our fallen".
        val theme = subject.resolve(
            date = LocalDate.of(2026, Month.MAY, 25),
            overrides = noOverrides,
            enabledCountries = setOf("US", HolidayCatalog.FUNNY),
            events = emptyList(),
            themeFromCalendarHolidays = false,
            themeFromCalendarBirthdays = false,
        )
        theme.shouldNotBeNull()
        theme.id shouldBe HolidayId.US_MEMORIAL_DAY
        theme.bannerSegments.shouldBeNull()
    }

    @Test
    fun `lone Funny theme uses its standalone banner, not the join clause`() {
        val theme = subject.resolve(
            date = LocalDate.of(2026, Month.MAY, 25),
            overrides = noOverrides,
            enabledCountries = setOf(HolidayCatalog.FUNNY),
            events = emptyList(),
            themeFromCalendarHolidays = false,
            themeFromCalendarBirthdays = false,
        )
        theme.shouldNotBeNull()
        theme.id shouldBe HolidayId.TOWEL_DAY
        theme.bannerSegments.shouldBeNull()
        theme.bannerTextKey shouldBe "holiday_banner_towel_day"
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
    fun `Muslim Holidays calendar gets the Islamic green-gold palette`() {
        val theme = subject.resolve(
            date = random,
            overrides = noOverrides,
            enabledCountries = allCountries,
            events = listOf(publicHoliday("Eid al-Fitr", "en.islamic#holiday@group.v.calendar.google.com")),
            themeFromCalendarHolidays = true,
            themeFromCalendarBirthdays = false,
        )
        theme.shouldNotBeNull()
        theme.id shouldBe HolidayId.GENERIC_PUBLIC_HOLIDAY
        theme.emoji shouldBe "🌙"
        theme.bannerArgb shouldBe 0xFF1B8A4BL
        theme.displayTitleOverride shouldBe "Eid al-Fitr"
    }

    @Test
    fun `Jewish Holidays calendar gets the Judaism blue-white palette`() {
        val theme = subject.resolve(
            date = random,
            overrides = noOverrides,
            enabledCountries = allCountries,
            events = listOf(publicHoliday("Yom Kippur", "en.judaism#holiday@group.v.calendar.google.com")),
            themeFromCalendarHolidays = true,
            themeFromCalendarBirthdays = false,
        )
        theme.shouldNotBeNull()
        theme.emoji shouldBe "✡️"
        theme.bannerArgb shouldBe 0xFF0038B8L
        theme.displayTitleOverride shouldBe "Yom Kippur"
    }

    @Test
    fun `Hindu Holidays calendar gets the saffron-magenta palette`() {
        val theme = subject.resolve(
            date = random,
            overrides = noOverrides,
            enabledCountries = allCountries,
            events = listOf(publicHoliday("Diwali", "en.hinduism#holiday@group.v.calendar.google.com")),
            themeFromCalendarHolidays = true,
            themeFromCalendarBirthdays = false,
        )
        theme.shouldNotBeNull()
        theme.emoji shouldBe "🪔"
        theme.bannerArgb shouldBe 0xFFFF9933L
    }

    @Test
    fun `non-locale-en religion calendar still themes by religion`() {
        // Detection is locale-stable: a French phone subscribed to Muslim
        // Holidays surfaces "Aïd el-Fitr" via `fr.islamic#holiday@…`. Religion
        // key is fixed regardless of locale prefix.
        val theme = subject.resolve(
            date = random,
            overrides = noOverrides,
            enabledCountries = allCountries,
            events = listOf(publicHoliday("Aïd el-Fitr", "fr.islamic#holiday@group.v.calendar.google.com")),
            themeFromCalendarHolidays = true,
            themeFromCalendarBirthdays = false,
        )
        theme.shouldNotBeNull()
        theme.emoji shouldBe "🌙"
        theme.bannerArgb shouldBe 0xFF1B8A4BL
    }

    @Test
    fun `region-keyed holiday calendar keeps the generic festive palette`() {
        // `en.usa#holiday@…` is a country calendar, not a religion calendar —
        // no religion-specific palette, falls through to the generic 🎊
        // gold/purple so a Thanksgiving from the catalog-gap path still
        // themes neutrally.
        val theme = subject.resolve(
            date = random,
            overrides = noOverrides,
            enabledCountries = allCountries,
            events = listOf(publicHoliday("Some Holiday", "en.usa#holiday@group.v.calendar.google.com")),
            themeFromCalendarHolidays = true,
            themeFromCalendarBirthdays = false,
        )
        theme.shouldNotBeNull()
        theme.emoji shouldBe "🎊"
        theme.bannerArgb shouldBe 0xFFD4AF37L
    }

    @Test
    fun `NORMAL kind events are ignored`() {
        val normalEvent = CalendarEvent(
            title = "Standup",
            start = random.atTime(9, 0),
            end = random.atTime(9, 30),
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
