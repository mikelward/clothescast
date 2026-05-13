package app.clothescast.tts

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clothescast.core.domain.model.BandClause
import app.clothescast.core.domain.model.ClothesClause
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.TemperatureBand
import app.clothescast.core.domain.model.VoiceLocale
import io.kotest.matchers.shouldBe
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
        utterance.text shouldBe "Heute wird es kalt bis kühl. Trag Pullover und Jacke."
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
        utterance.text shouldBe "Today, it will be cold to cool. Wear a jumper and jacket."
    }

    private companion object {
        val sampleSummary = InsightSummary(
            period = ForecastPeriod.TODAY,
            band = BandClause(TemperatureBand.COLD, TemperatureBand.COOL),
            clothes = ClothesClause(listOf("sweater", "jacket")),
        )
    }
}
