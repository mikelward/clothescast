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
    fun `autoModels with Google puts Google in ICON's place`() {
        // UK: Google, UKMO and ECMWF (maintainer, 2026-10-18).
        ForecastModel.autoModels(Location(51.5, -0.12, displayName = null), google = true) shouldBe
            setOf(ForecastModel.UKMO_SEAMLESS, ForecastModel.ECMWF_IFS025, ForecastModel.GOOGLE_WEATHER)
        listOf(
            Location(48.86, 2.35, displayName = null),
            Location(59.33, 18.07, displayName = null),
            Location(40.7, -74.0, displayName = null),
            Location(35.7, 139.7, displayName = null),
            null,
        ).forEach { loc ->
            ForecastModel.autoModels(loc, google = true) shouldBe
                ForecastModel.defaultsFor(loc) - ForecastModel.ICON_SEAMLESS + ForecastModel.GOOGLE_WEATHER
            ForecastModel.autoModels(loc, google = false) shouldBe ForecastModel.defaultsFor(loc)
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
            .effectiveForecastModels(googleReachable = true) shouldBe pick + ForecastModel.GOOGLE_WEATHER
        base.copy(googleForecast = true)
            .effectiveForecastModels(googleReachable = true) shouldBe ForecastModel.autoModels(london, google = true)
        base.copy(forecastModels = pick)
            .effectiveForecastModels(googleReachable = true) shouldBe pick
        // A one-model pick beside an unreachable Google (signed out, no key)
        // fetches as Auto, while the stored pick stays as it is.
        val one = setOf(ForecastModel.UKMO_SEAMLESS)
        base.copy(forecastModels = one, googleForecast = true)
            .effectiveForecastModels(googleReachable = false) shouldBe ForecastModel.defaultsFor(london)
        base.copy(forecastModels = one, googleForecast = true)
            .effectiveForecastModels(googleReachable = true) shouldBe one + ForecastModel.GOOGLE_WEATHER
        base
            .effectiveForecastModels(googleReachable = true) shouldBe ForecastModel.defaultsFor(london)

        // Chart colours stay distinct whichever set the fetch ends up using.
        val slots = base.copy(forecastModels = one, googleForecast = true).chartColorSlots()
        val shown = ForecastModel.defaultsFor(london) + one + ForecastModel.GOOGLE_WEATHER
        shown.map { slots.getValue(it.openMeteoId) }.toSet().size shouldBe shown.size
    }

    @Test
    fun `a single-model pick is used only beside Google, else Auto`() {
        // An older build's Google plus one model carries over as one pick plus
        // the switch; turning Google off must not leave a lone model.
        val london = Location(51.5, -0.12, displayName = null)
        val one = setOf(ForecastModel.ECMWF_IFS025)
        ForecastModel.effective(one, london, google = true) shouldBe one + ForecastModel.GOOGLE_WEATHER
        ForecastModel.effective(one, london, google = false) shouldBe ForecastModel.defaultsFor(london)
        ForecastModel.usablePick(one, google = false) shouldBe null
        val two = setOf(ForecastModel.ECMWF_IFS025, ForecastModel.GFS_SEAMLESS)
        ForecastModel.effective(two, london, google = false) shouldBe two
    }
}
