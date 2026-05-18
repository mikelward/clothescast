package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.EventKind
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CalendarEventClassifierTest {

    @Test
    fun `apostrophe-s birthday title classifies as BIRTHDAY via title regex`() {
        val result = CalendarEventClassifier.classify("Alice's birthday", ownerAccount = null)
        result.kind shouldBe EventKind.BIRTHDAY
        result.reason shouldBe CalendarEventClassifier.Reason.BIRTHDAY_TITLE_REGEX
    }

    @Test
    fun `curly-apostrophe birthday title classifies as BIRTHDAY`() {
        CalendarEventClassifier.classify("Alice’s birthday", ownerAccount = null).kind shouldBe EventKind.BIRTHDAY
    }

    @Test
    fun `birthday colon prefix classifies as BIRTHDAY`() {
        CalendarEventClassifier.classify("Birthday: Bob", ownerAccount = null).kind shouldBe EventKind.BIRTHDAY
    }

    @Test
    fun `birthday dash prefix classifies as BIRTHDAY`() {
        CalendarEventClassifier.classify("Birthday - Bob", ownerAccount = null).kind shouldBe EventKind.BIRTHDAY
    }

    @Test
    fun `birthday classification is case-insensitive`() {
        CalendarEventClassifier.classify("ALICE'S BIRTHDAY", ownerAccount = null).kind shouldBe EventKind.BIRTHDAY
    }

    @Test
    fun `bare Birthday party does not classify as BIRTHDAY`() {
        CalendarEventClassifier.classify("Birthday party", ownerAccount = null).kind shouldBe EventKind.NORMAL
    }

    @Test
    fun `bare Birthdays does not classify as BIRTHDAY`() {
        CalendarEventClassifier.classify("Birthdays", ownerAccount = null).kind shouldBe EventKind.NORMAL
    }

    @Test
    fun `non-english birthday wording falls through to NORMAL`() {
        // v1 locale gap: regex is English-only. Documented in the classifier KDoc.
        CalendarEventClassifier.classify("Anniversaire de Marie", ownerAccount = null).kind shouldBe EventKind.NORMAL
        CalendarEventClassifier.classify("Geburtstag von Hans", ownerAccount = null).kind shouldBe EventKind.NORMAL
    }

    @Test
    fun `non-english title with EVENT_TYPE_BIRTHDAY classifies as BIRTHDAY`() {
        // Closes the locale gap: when the device exposes the eventType column,
        // we trust it over the title.
        val result = CalendarEventClassifier.classify(
            title = "Anniversaire de Marie",
            ownerAccount = "user@gmail.com",
            eventType = CalendarEventClassifier.EVENT_TYPE_BIRTHDAY,
        )
        result.kind shouldBe EventKind.BIRTHDAY
        result.reason shouldBe CalendarEventClassifier.Reason.EVENT_TYPE_BIRTHDAY
    }

    @Test
    fun `eventType null leaves classification to title regex`() {
        // Devices that don't expose the column pass null — fall through to the
        // title regex as before.
        val result = CalendarEventClassifier.classify(
            title = "Anniversaire de Marie",
            ownerAccount = "user@gmail.com",
            eventType = null,
        )
        result.kind shouldBe EventKind.NORMAL
        result.reason shouldBe CalendarEventClassifier.Reason.DEFAULT_NORMAL
    }

    @Test
    fun `eventType default value leaves classification to title regex`() {
        // A device that exposes the column but the row carries no specific type
        // (eventType=0) shouldn't be treated as a birthday.
        val result = CalendarEventClassifier.classify(
            title = "Lunch with Sam",
            ownerAccount = "user@gmail.com",
            eventType = 0,
        )
        result.kind shouldBe EventKind.NORMAL
    }

    @Test
    fun `google holiday-calendar owner classifies as PUBLIC_HOLIDAY`() {
        val result = CalendarEventClassifier.classify(
            title = "Diwali",
            ownerAccount = "en.indian#holiday@group.v.calendar.google.com",
        )
        result.kind shouldBe EventKind.PUBLIC_HOLIDAY
        result.reason shouldBe CalendarEventClassifier.Reason.HOLIDAY_CALENDAR_OWNER
    }

    @Test
    fun `direct holiday-calendar-google-com owner classifies as PUBLIC_HOLIDAY`() {
        CalendarEventClassifier.classify(
            title = "Independence Day",
            ownerAccount = "en.usa@holiday.calendar.google.com",
        ).kind shouldBe EventKind.PUBLIC_HOLIDAY
    }

    @Test
    fun `regular gmail owner stays NORMAL`() {
        CalendarEventClassifier.classify(
            title = "Lunch with Sam",
            ownerAccount = "user@gmail.com",
        ).kind shouldBe EventKind.NORMAL
    }

    @Test
    fun `null owner with normal title stays NORMAL`() {
        CalendarEventClassifier.classify("Lunch with Sam", ownerAccount = null).kind shouldBe EventKind.NORMAL
    }

    @Test
    fun `holiday-calendar owner wins over a birthday-shaped title`() {
        // Defensive: in practice Google's holiday calendars never have birthday titles,
        // but the priority order makes the intent explicit.
        CalendarEventClassifier.classify(
            title = "Alice's birthday",
            ownerAccount = "en.usa#holiday@group.v.calendar.google.com",
        ).kind shouldBe EventKind.PUBLIC_HOLIDAY
    }

    @Test
    fun `holiday-calendar owner wins over eventType birthday`() {
        // Holiday calendars are read-only; if a row there ever carried
        // eventType=BIRTHDAY (shouldn't happen, but defensive) we'd still
        // rather show it as the holiday.
        CalendarEventClassifier.classify(
            title = "King's Birthday",
            ownerAccount = "en.australian#holiday@group.v.calendar.google.com",
            eventType = CalendarEventClassifier.EVENT_TYPE_BIRTHDAY,
        ).kind shouldBe EventKind.PUBLIC_HOLIDAY
    }

    @Test
    fun `King's Birthday from holiday calendar classifies as PUBLIC_HOLIDAY`() {
        // The commonwealth public holiday. Title is birthday-shaped (greedy `.+`
        // happily matches "King") but the holiday-calendar owner wins.
        CalendarEventClassifier.classify(
            title = "King's Birthday",
            ownerAccount = "en.australian#holiday@group.v.calendar.google.com",
        ).kind shouldBe EventKind.PUBLIC_HOLIDAY
    }

    @Test
    fun `Rob King's Birthday from personal calendar classifies as BIRTHDAY`() {
        // A real person named Rob King with a birthday on the user's primary
        // calendar. The owner is personal so the title regex takes effect.
        CalendarEventClassifier.classify(
            title = "Rob King's Birthday",
            ownerAccount = "user@gmail.com",
        ).kind shouldBe EventKind.BIRTHDAY
    }

    @Test
    fun `generic group calendar owner is not treated as holiday`() {
        // `@group.v.calendar.google.com` without the `#holiday` infix is any
        // shared Google calendar; classifying those as PUBLIC_HOLIDAY would be
        // far too broad.
        CalendarEventClassifier.classify(
            title = "Team standup",
            ownerAccount = "abc123@group.v.calendar.google.com",
        ).kind shouldBe EventKind.NORMAL
    }
}
