package app.clothescast.tts

import app.clothescast.core.domain.model.HolidayId
import app.clothescast.core.domain.model.TtsStyle
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HolidayVoiceTest {

    @Test
    fun `no holiday keeps the user's voice and style`() {
        resolveHolidayVoice(null, "Despina", TtsStyle.WEATHER_FORECASTER) shouldBe
            GeminiVoiceSelection("Despina", TtsStyle.WEATHER_FORECASTER)
    }

    @Test
    fun `presidents day switches a default female voice to a male president`() {
        val selection = resolveHolidayVoice(
            HolidayId.US_PRESIDENTS_DAY,
            "Despina",
            TtsStyle.WEATHER_FORECASTER,
        )
        selection.style shouldBe TtsStyle.PRESIDENT
        selection.voiceName shouldBe "Charon"
    }

    @Test
    fun `towel day speaks as a male sci-fi narrator`() {
        val selection = resolveHolidayVoice(
            HolidayId.TOWEL_DAY,
            "Kore",
            TtsStyle.WEATHER_FORECASTER,
        )
        selection.style shouldBe TtsStyle.SCIFI_NARRATOR
        selection.voiceName shouldBe "Charon"
    }

    @Test
    fun `a male persona keeps a voice that is already male`() {
        val selection = resolveHolidayVoice(
            HolidayId.US_PRESIDENTS_DAY,
            "Iapetus",
            TtsStyle.WEATHER_FORECASTER,
        )
        selection.style shouldBe TtsStyle.PRESIDENT
        selection.voiceName shouldBe "Iapetus"
    }

    @Test
    fun `a neutral persona leaves the voice untouched`() {
        val selection = resolveHolidayVoice(
            HolidayId.HALLOWEEN,
            "Despina",
            TtsStyle.WEATHER_FORECASTER,
        )
        selection.style shouldBe TtsStyle.SPOOKY_NARRATOR
        selection.voiceName shouldBe "Despina"
    }

    @Test
    fun `a deliberate persona pick overrides the holiday`() {
        val selection = resolveHolidayVoice(
            HolidayId.CHRISTMAS_DAY,
            "Leda",
            TtsStyle.PIRATE,
        )
        selection shouldBe GeminiVoiceSelection("Leda", TtsStyle.PIRATE)
    }
}
