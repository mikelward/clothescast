package app.clothescast.core.domain.model

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

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

    @Test
    fun `withGoogle adds Google only when asked`() {
        val picks = setOf(ForecastModel.ECMWF_IFS025, ForecastModel.ICON_SEAMLESS)
        ForecastModel.withGoogle(picks, google = false) shouldBe picks
        ForecastModel.withGoogle(picks + ForecastModel.GOOGLE_WEATHER, google = false) shouldBe picks
        ForecastModel.withGoogle(picks, google = true) shouldBe picks + ForecastModel.GOOGLE_WEATHER
    }

    @Test
    fun `withGoogle on an Auto set stays within the cap, dropping the least local model`() {
        // Every region, including any whose Auto set already sits at the cap.
        listOf(
            Location(51.5, -0.12, displayName = null),
            Location(48.86, 2.35, displayName = null),
            Location(59.33, 18.07, displayName = null),
            Location(40.7, -74.0, displayName = null),
            Location(35.7, 139.7, displayName = null),
            null,
        ).forEach { loc ->
            val defaults = ForecastModel.defaultsFor(loc)
            val withGoogle = ForecastModel.withGoogle(defaults, google = true)
            withGoogle shouldContain ForecastModel.GOOGLE_WEATHER
            (withGoogle.size <= ForecastModel.MAX_MODELS) shouldBe true
            withGoogle - ForecastModel.GOOGLE_WEATHER shouldBe defaults.take(ForecastModel.MAX_MODELS - 1).toSet()
        }
    }

    @Test
    fun `effectiveForecastModels adds Google to Auto and to an explicit pick alike`() {
        val london = Location(51.5, -0.12, displayName = null)
        val pick = setOf(ForecastModel.ECMWF_IFS025, ForecastModel.GFS_SEAMLESS)
        val base = UserPreferences(
            schedule = Schedule.default(ZoneOffset.UTC),
            deliveryMode = DeliveryMode.NOTIFICATION_AND_TTS,
            temperatureUnit = TemperatureUnit.CELSIUS,
            windSpeedUnit = WindSpeedUnit.KMH,
            clothesRules = ClothesRule.DEFAULTS,
            location = london,
        )
        base.copy(forecastModels = pick, googleForecast = true)
            .effectiveForecastModels() shouldBe pick + ForecastModel.GOOGLE_WEATHER
        base.copy(googleForecast = true)
            .effectiveForecastModels() shouldContain ForecastModel.GOOGLE_WEATHER
        base.copy(forecastModels = pick)
            .effectiveForecastModels() shouldBe pick
        base
            .effectiveForecastModels() shouldBe ForecastModel.defaultsFor(london)
    }
}
