package app.clothescast.core.domain.model

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ForecastModelGoogleTest {
    @Test
    fun `google is flagged as requiring a Gemini key, the others are not`() {
        ForecastModel.GOOGLE_WEATHER.requiresGeminiKey.shouldBeTrue()
        ForecastModel.entries
            .filter { it != ForecastModel.GOOGLE_WEATHER }
            .forEach { it.requiresGeminiKey shouldBe false }
    }

    @Test
    fun `openMeteoIds drops google so it never reaches the Open-Meteo models param`() {
        val selection = setOf(
            ForecastModel.ECMWF_IFS025,
            ForecastModel.GFS_SEAMLESS,
            ForecastModel.GOOGLE_WEATHER,
        )
        val ids = ForecastModel.openMeteoIds(selection)
        ids shouldContain "ecmwf_ifs025"
        ids shouldContain "gfs_seamless"
        ids shouldNotContain "google"
    }

    @Test
    fun `google is never an Auto default - it is opt-in only`() {
        ForecastModel.DEFAULTS shouldNotContain ForecastModel.GOOGLE_WEATHER
        // Spot-check a few regions' Auto resolution too.
        listOf(
            Location(51.5, -0.12, displayName = null), // British Isles
            Location(40.7, -74.0, displayName = null), // North America
            Location(35.7, 139.7, displayName = null), // Japan
            Location(0.0, 0.0, displayName = null), // global fallback
        ).forEach { loc ->
            ForecastModel.defaultsFor(loc) shouldNotContain ForecastModel.GOOGLE_WEATHER
        }
    }
}
