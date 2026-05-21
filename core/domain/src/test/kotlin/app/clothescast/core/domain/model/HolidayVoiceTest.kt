package app.clothescast.core.domain.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HolidayVoiceTest {

    @Test
    fun `seasonal holidays map to their persona`() {
        holidayTtsStyle(HolidayId.US_PRESIDENTS_DAY) shouldBe TtsStyle.PRESIDENT
        holidayTtsStyle(HolidayId.TOWEL_DAY) shouldBe TtsStyle.SCIFI_NARRATOR
        holidayTtsStyle(HolidayId.TALK_LIKE_A_PIRATE_DAY) shouldBe TtsStyle.PIRATE
        holidayTtsStyle(HolidayId.ST_PATRICKS_DAY) shouldBe TtsStyle.LEPRECHAUN
        holidayTtsStyle(HolidayId.HALLOWEEN) shouldBe TtsStyle.SPOOKY_NARRATOR
        holidayTtsStyle(HolidayId.NEW_YEARS_DAY) shouldBe TtsStyle.NEW_YEARS_HOST
        holidayTtsStyle(HolidayId.UK_KINGS_BIRTHDAY) shouldBe TtsStyle.KING
        holidayTtsStyle(HolidayId.CHRISTMAS_DAY) shouldBe TtsStyle.FATHER_CHRISTMAS
        holidayTtsStyle(HolidayId.BOXING_DAY) shouldBe TtsStyle.FATHER_CHRISTMAS
    }

    @Test
    fun `ordinary holidays carry no special voice`() {
        holidayTtsStyle(HolidayId.VALENTINES_DAY) shouldBe null
        holidayTtsStyle(HolidayId.AUSTRALIA_DAY) shouldBe null
        holidayTtsStyle(HolidayId.US_INDEPENDENCE_DAY) shouldBe null
    }

    @Test
    fun `the president is male and towel day's narrator is male`() {
        TtsStyle.PRESIDENT.suggestedVoiceGender shouldBe VoiceGender.MALE
        TtsStyle.SCIFI_NARRATOR.suggestedVoiceGender shouldBe VoiceGender.MALE
    }

    @Test
    fun `the queen is female and the king is male`() {
        TtsStyle.QUEEN.suggestedVoiceGender shouldBe VoiceGender.FEMALE
        TtsStyle.KING.suggestedVoiceGender shouldBe VoiceGender.MALE
    }

    @Test
    fun `everyday registers leave the voice gender unconstrained`() {
        TtsStyle.WEATHER_FORECASTER.suggestedVoiceGender shouldBe VoiceGender.NEUTRAL
        TtsStyle.MORNING_PRESENTER.suggestedVoiceGender shouldBe VoiceGender.NEUTRAL
        TtsStyle.SPOOKY_NARRATOR.suggestedVoiceGender shouldBe VoiceGender.NEUTRAL
    }
}
