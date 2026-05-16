package app.clothescast.tts

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Locale

class TtsSpeakerTest {

    @Test
    fun `splits ClothesCast into two words for TTS`() {
        prepareForTts("Welcome to ClothesCast.", Locale.ENGLISH) shouldBe "Welcome to Clothes Cast."
    }

    @Test
    fun `splits every occurrence`() {
        prepareForTts(
            "ClothesCast preview — this is the ClothesCast voice.",
            Locale.ENGLISH,
        ) shouldBe "Clothes Cast preview — this is the Clothes Cast voice."
    }

    @Test
    fun `leaves prose without the brand or degree sign untouched`() {
        val prose = "Today will be mild. Wear a sweater. Rain at 3pm."
        prepareForTts(prose, Locale.ENGLISH) shouldBe prose
    }

    @Test
    fun `english range — strips the inner degree and spells out the trailing one`() {
        prepareForTts("Today, it will be 8° to 14°.", Locale.ENGLISH) shouldBe
            "Today, it will be 8 to 14 degrees."
    }

    @Test
    fun `english single value — spells out the trailing degree as 'degrees'`() {
        prepareForTts("Today, it will be 14°.", Locale.ENGLISH) shouldBe
            "Today, it will be 14 degrees."
    }

    @Test
    fun `english delta — spells out the degree before 'warmer'`() {
        prepareForTts("Today, it will be 14°. 5° warmer than yesterday.", Locale.ENGLISH) shouldBe
            "Today, it will be 14 degrees. 5 degrees warmer than yesterday."
    }

    @Test
    fun `non-english locale strips the degree sign — the range word already anchors the unit`() {
        prepareForTts("Heute wird es 8° bis 14°.", Locale.GERMAN) shouldBe
            "Heute wird es 8 bis 14."
    }

    @Test
    fun `non-english single value also strips the degree sign`() {
        prepareForTts("Heute wird es 14°.", Locale.GERMAN) shouldBe "Heute wird es 14."
    }
}
