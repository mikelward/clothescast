package app.clothescast.tts

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clothescast.core.domain.model.BandClause
import app.clothescast.core.domain.model.ClothesClause
import app.clothescast.core.domain.model.FestiveThemes
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.HolidayCatalog
import app.clothescast.core.domain.model.HolidayId
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.TemperatureBand
import app.clothescast.core.domain.model.VoiceLocale
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test
import org.junit.runner.RunWith
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

    private companion object {
        val christmas = HolidayCatalog.all.first { it.second.id == HolidayId.CHRISTMAS_DAY }.second
        val remembranceDay = HolidayCatalog.all.first { it.second.id == HolidayId.REMEMBRANCE_DAY }.second

        val sampleSummary = InsightSummary(
            period = ForecastPeriod.TODAY,
            band = BandClause(TemperatureBand.COLD, TemperatureBand.COOL),
            clothes = ClothesClause(listOf("sweater", "jacket")),
        )
    }
}
