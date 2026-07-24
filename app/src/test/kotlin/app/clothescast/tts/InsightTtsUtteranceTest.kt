package app.clothescast.tts

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clothescast.core.domain.model.BandClause
import app.clothescast.core.domain.model.CalendarEvent
import app.clothescast.core.domain.model.ClothesClause
import app.clothescast.core.domain.model.EventKind
import app.clothescast.core.domain.model.FestiveThemes
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.HolidayCatalog
import app.clothescast.core.domain.model.HolidayId
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.RangeFormat
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.TemperatureBand
import app.clothescast.core.domain.model.VoiceLocale
import app.clothescast.core.domain.usecase.ThemeForToday
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.Month
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class InsightTtsUtteranceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `explicit de-AT voice locale renders German speech even when app region is English`() {
        val utterance = insightTtsUtterance(
            context = context,
            summary = sampleSummary,
            region = Region.EN_GB,
            voiceLocale = VoiceLocale.DE_AT,
            fallbackLocale = Locale.US,
        )

        utterance.locale.toLanguageTag() shouldBe "de-AT"
        // BandClause(COLD, COOL) defaults to the bands' midpoints (8°C / 15°C);
        // German prose uses "bis" as the range connector.
        utterance.text shouldBe "Heute wird es 8° bis 15°. Trag Pullover und Jacke."
    }

    @Test
    fun `system voice locale continues to follow the app region`() {
        val utterance = insightTtsUtterance(
            context = context,
            summary = sampleSummary,
            region = Region.EN_GB,
            voiceLocale = VoiceLocale.SYSTEM,
            fallbackLocale = Locale.US,
        )

        utterance.locale.toLanguageTag() shouldBe "en-GB"
        utterance.text shouldBe "Today, it will be 8° to 15°. Wear a jumper and jacket."
    }

    @Test
    fun `catalog holiday prepends a localised greeting to the briefing`() {
        val utterance = insightTtsUtterance(
            context = context,
            summary = sampleSummary,
            region = Region.EN_GB,
            voiceLocale = VoiceLocale.SYSTEM,
            fallbackLocale = Locale.US,
            holidayTheme = christmas,
        )

        utterance.text shouldBe "Merry Christmas! Today, it will be 8° to 15°. Wear a jumper and jacket."
    }

    @Test
    fun `birthday speaks a generic greeting and never the calendar event title`() {
        val utterance = insightTtsUtterance(
            context = context,
            summary = sampleSummary,
            region = Region.EN_GB,
            voiceLocale = VoiceLocale.SYSTEM,
            fallbackLocale = Locale.US,
            holidayTheme = FestiveThemes.birthday("Alice's birthday"),
        )

        utterance.text shouldBe "Happy birthday! Today, it will be 8° to 15°. Wear a jumper and jacket."
        utterance.text shouldNotContain "Alice"
    }

    @Test
    fun `birthday greeting is spoken in the voice locale's language`() {
        val utterance = insightTtsUtterance(
            context = context,
            summary = sampleSummary,
            region = Region.EN_GB,
            voiceLocale = VoiceLocale.DE_AT,
            fallbackLocale = Locale.US,
            holidayTheme = FestiveThemes.birthday("Alice's birthday"),
        )

        utterance.text shouldBe "Alles Gute zum Geburtstag! Heute wird es 8° bis 15°. Trag Pullover und Jacke."
        utterance.text shouldNotContain "Alice"
    }

    @Test
    fun `calendar public holiday is not greeted to keep its title off-device`() {
        val utterance = insightTtsUtterance(
            context = context,
            summary = sampleSummary,
            region = Region.EN_GB,
            voiceLocale = VoiceLocale.SYSTEM,
            fallbackLocale = Locale.US,
            holidayTheme = FestiveThemes.publicHoliday("Diwali"),
        )

        utterance.text shouldBe "Today, it will be 8° to 15°. Wear a jumper and jacket."
    }

    @Test
    fun `holiday-name override follows the app region, not the voice locale`() {
        // US region with an en-GB voice: the spoken greeting should mirror the
        // Today banner's US variant ("Veterans Day"), not the en-GB-locale
        // default ("Remembrance Day").
        val utterance = insightTtsUtterance(
            context = context,
            summary = sampleSummary,
            region = Region.EN_US,
            voiceLocale = VoiceLocale.EN_GB,
            fallbackLocale = Locale.US,
            holidayTheme = remembranceDay,
        )

        utterance.text shouldBe "Honoring our Veterans. Today, it will be 8° to 15°. Wear a jumper and jacket."
    }

    @Test
    fun `two birthdays on one day still speak a single generic greeting`() {
        // The composed banner reads "Alice's birthday and Bob's birthday", but
        // both segments are calendar titles that can't be spoken — the greeting
        // used to collapse to nothing, so a day with two birthdays was quieter
        // than a day with one.
        val utterance = insightTtsUtterance(
            context = context,
            summary = sampleSummary,
            region = Region.EN_GB,
            voiceLocale = VoiceLocale.SYSTEM,
            fallbackLocale = Locale.US,
            holidayTheme = composedTheme(
                LocalDate.of(2026, Month.MAY, 18),
                birthday("Alice's birthday"),
                birthday("Bob's birthday"),
            ),
        )

        utterance.text shouldBe "Happy birthday! Today, it will be 8° to 15°. Wear a jumper and jacket."
        utterance.text shouldNotContain "Alice"
        utterance.text shouldNotContain "Bob"
    }

    @Test
    fun `a birthday sharing a catalog holiday is greeted alongside it`() {
        val utterance = insightTtsUtterance(
            context = context,
            summary = sampleSummary,
            region = Region.EN_GB,
            voiceLocale = VoiceLocale.SYSTEM,
            fallbackLocale = Locale.US,
            holidayTheme = composedTheme(
                LocalDate.of(2026, Month.DECEMBER, 25),
                birthday("Alice's birthday"),
            ),
        )

        utterance.text shouldBe
            "Merry Christmas and Happy birthday! Today, it will be 8° to 15°. Wear a jumper and jacket."
        utterance.text shouldNotContain "Alice"
    }

    @Test
    fun `a greeting that brings its own punctuation starts a new sentence`() {
        // "¡Feliz Cinco de Mayo!" closes its own sentence, so joining the
        // birthday on with "and" would read as one broken sentence. Trimming
        // the "!" isn't the answer either — it would orphan the opening "¡".
        val utterance = insightTtsUtterance(
            context = context,
            summary = sampleSummary,
            region = Region.EN_GB,
            voiceLocale = VoiceLocale.SYSTEM,
            fallbackLocale = Locale.US,
            holidayTheme = composedTheme(
                LocalDate.of(2026, Month.MAY, 5),
                birthday("Alice's birthday"),
            ),
        )

        utterance.text shouldBe
            "¡Feliz Cinco de Mayo! Happy birthday! Today, it will be 8° to 15°. Wear a jumper and jacket."
    }

    @Test
    fun `a calendar public holiday sharing a birthday keeps its title off-device`() {
        val utterance = insightTtsUtterance(
            context = context,
            summary = sampleSummary,
            region = Region.EN_GB,
            voiceLocale = VoiceLocale.SYSTEM,
            fallbackLocale = Locale.US,
            holidayTheme = composedTheme(
                LocalDate.of(2026, Month.MAY, 18),
                CalendarEvent(
                    title = "Diwali",
                    start = LocalDate.of(2026, Month.MAY, 18).atStartOfDay(),
                    end = LocalDate.of(2026, Month.MAY, 18).atStartOfDay(),
                    allDay = true,
                    kind = EventKind.PUBLIC_HOLIDAY,
                ),
                birthday("Alice's birthday"),
            ),
        )

        utterance.text shouldBe "Happy birthday! Today, it will be 8° to 15°. Wear a jumper and jacket."
        utterance.text shouldNotContain "Diwali"
        utterance.text shouldNotContain "Alice"
    }

    @Test
    fun `nothing-to-say insight stays silent rather than speaking a placeholder`() {
        // RangeFormat.NONE drops the band; with no other clause the spoken
        // briefing is empty (the display card shows "Today, it will be the same
        // as yesterday.").
        val utterance = insightTtsUtterance(
            context = context,
            summary = InsightSummary(
                period = ForecastPeriod.TODAY,
                band = BandClause(TemperatureBand.COLD, TemperatureBand.COOL),
            ),
            region = Region.EN_GB,
            voiceLocale = VoiceLocale.SYSTEM,
            fallbackLocale = Locale.US,
            rangeFormat = RangeFormat.NONE,
        )

        utterance.text shouldBe ""
    }

    @Test
    fun `nothing-to-say insight still speaks the holiday greeting alone`() {
        val utterance = insightTtsUtterance(
            context = context,
            summary = InsightSummary(
                period = ForecastPeriod.TODAY,
                band = BandClause(TemperatureBand.COLD, TemperatureBand.COOL),
            ),
            region = Region.EN_GB,
            voiceLocale = VoiceLocale.SYSTEM,
            fallbackLocale = Locale.US,
            rangeFormat = RangeFormat.NONE,
            holidayTheme = christmas,
        )

        utterance.text shouldBe "Merry Christmas!"
    }

    private companion object {
        val christmas = HolidayCatalog.all.first { it.second.id == HolidayId.CHRISTMAS_DAY }.second
        val remembranceDay = HolidayCatalog.all.first { it.second.id == HolidayId.REMEMBRANCE_DAY }.second

        fun birthday(title: String) = CalendarEvent(
            title = title,
            start = LocalDate.of(2026, Month.MAY, 18).atStartOfDay(),
            end = LocalDate.of(2026, Month.MAY, 18).atStartOfDay(),
            allDay = true,
            kind = EventKind.BIRTHDAY,
        )

        /** The multi-celebration theme `ThemeForToday` composes for [date]. */
        fun composedTheme(date: LocalDate, vararg events: CalendarEvent) =
            ThemeForToday().resolve(
                date = date,
                overrides = emptyMap(),
                enabledCountries = HolidayCatalog.allCountries,
                events = events.toList(),
                themeFromCalendarHolidays = true,
                themeFromCalendarBirthdays = true,
            )

        val sampleSummary = InsightSummary(
            period = ForecastPeriod.TODAY,
            band = BandClause(TemperatureBand.COLD, TemperatureBand.COOL),
            clothes = ClothesClause(listOf("sweater", "jacket")),
        )
    }
}
