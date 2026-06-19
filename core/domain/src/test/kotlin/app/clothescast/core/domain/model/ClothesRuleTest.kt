package app.clothescast.core.domain.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ClothesRuleTest {
    private val date = LocalDate.of(2026, 4, 25)

    /**
     * Builds a forecast where feels-like equals the raw temp by default — most tests
     * don't need to distinguish, and the rules now key off feels-like.
     */
    private fun forecast(
        min: Double,
        max: Double,
        precip: Double = 0.0,
        feelsLikeMin: Double = min,
        feelsLikeMax: Double = max,
        condition: WeatherCondition = WeatherCondition.CLEAR,
    ): DailyForecast = DailyForecast(
        date = date,
        temperatureMinC = min,
        temperatureMaxC = max,
        feelsLikeMinC = feelsLikeMin,
        feelsLikeMaxC = feelsLikeMax,
        precipitationProbabilityMaxPct = precip,
        precipitationMmTotal = 0.0,
        condition = condition,
    )

    @Test
    fun `temperature below applies when feels-like min is colder`() {
        val rule = ClothesRule(Garment.SWEATER, ClothesRule.TemperatureBelow(18.0))
        rule.appliesTo(forecast(min = 14.0, max = 22.0)) shouldBe true
    }

    @Test
    fun `temperature below uses feels-like, not raw temperature`() {
        // Raw min is 20°C (above threshold) but wind chill makes it feel like 14°C.
        val rule = ClothesRule(Garment.SWEATER, ClothesRule.TemperatureBelow(18.0))
        rule.appliesTo(forecast(min = 20.0, max = 24.0, feelsLikeMin = 14.0, feelsLikeMax = 22.0)) shouldBe true
    }

    @Test
    fun `temperature below does not apply when feels-like min meets threshold`() {
        val rule = ClothesRule(Garment.SWEATER, ClothesRule.TemperatureBelow(18.0))
        rule.appliesTo(forecast(min = 18.0, max = 22.0)) shouldBe false
    }

    @Test
    fun `temperature above applies when feels-like max is warmer`() {
        val rule = ClothesRule(Garment.SHORTS, ClothesRule.TemperatureAbove(24.0))
        rule.appliesTo(forecast(min = 12.0, max = 26.5)) shouldBe true
    }

    @Test
    fun `temperature above does not apply when feels-like max meets threshold`() {
        val rule = ClothesRule(Garment.SHORTS, ClothesRule.TemperatureAbove(24.0))
        rule.appliesTo(forecast(min = 12.0, max = 24.0)) shouldBe false
    }

    @Test
    fun `fahrenheit-typed below threshold compares against the converted value`() {
        // 65°F ≈ 18.33°C. A feels-like min of 17°C should trigger; 19°C should not.
        val rule = ClothesRule(
            Garment.SWEATER,
            ClothesRule.TemperatureBelow(65.0, TemperatureUnit.FAHRENHEIT),
        )
        rule.appliesTo(forecast(min = 17.0, max = 22.0)) shouldBe true
        rule.appliesTo(forecast(min = 19.0, max = 22.0)) shouldBe false
    }

    @Test
    fun `fahrenheit-typed above threshold compares against the converted value`() {
        // 80°F ≈ 26.67°C.
        val rule = ClothesRule(
            Garment.SHORTS,
            ClothesRule.TemperatureAbove(80.0, TemperatureUnit.FAHRENHEIT),
        )
        rule.appliesTo(forecast(min = 12.0, max = 27.0)) shouldBe true
        rule.appliesTo(forecast(min = 12.0, max = 26.0)) shouldBe false
    }

    @Test
    fun `default unit on temperature conditions stays celsius`() {
        // Source-compat guard: callers that don't pass a unit (incl. legacy DTO
        // round-trip) keep behaving as before.
        val rule = ClothesRule.TemperatureBelow(18.0)
        rule.unit shouldBe TemperatureUnit.CELSIUS
    }

    @Test
    fun `precipitation rule uses peak probability and is inclusive at the gate`() {
        val rule = ClothesRule(Garment.JACKET, ClothesRule.PrecipitationProbabilityAbove(50.0))
        rule.appliesTo(forecast(min = 10.0, max = 18.0, precip = 65.0)) shouldBe true
        rule.appliesTo(forecast(min = 10.0, max = 18.0, precip = 30.0)) shouldBe false
        // Inclusive (≥) so the boundary matches the prose / strip: a forecast
        // exactly on the gate fires.
        rule.appliesTo(forecast(min = 10.0, max = 18.0, precip = 50.0)) shouldBe true
    }

    @Test
    fun `defaults cover the temperature cases plus the precip-keyed rain gear`() {
        // Two precip-keyed rain-gear defaults (umbrella, rain jacket) ship
        // alongside the temperature rules covering the cold / warm cases.
        val items = ClothesRule.DEFAULTS.map { it.item.itemKey }
        items shouldBe listOf("sweater", "jacket", "coat", "gloves", "shorts", "umbrella", "rain-jacket")
    }

    @Test
    fun `umbrella default fires on its 10 percent chance-of-rain gate`() {
        val umbrella = ClothesRule.DEFAULTS.first { it.item == Garment.UMBRELLA }
        // Gate is the 10% chance-of-rain bar, lower than the rain jacket's.
        umbrella.appliesTo(forecast(min = 10.0, max = 18.0, precip = 25.0)) shouldBe true
        // Inclusive at the gate.
        umbrella.appliesTo(forecast(min = 10.0, max = 18.0, precip = 10.0)) shouldBe true
        umbrella.appliesTo(forecast(min = 10.0, max = 18.0, precip = 5.0)) shouldBe false
    }

    @Test
    fun `rain jacket default fires on its 50 percent likely-rain gate`() {
        val jacket = ClothesRule.DEFAULTS.first { it.item == Garment.RAIN_JACKET }
        // Heavier 50% bar than the umbrella's.
        jacket.appliesTo(forecast(min = 10.0, max = 18.0, precip = 55.0)) shouldBe true
        jacket.appliesTo(forecast(min = 10.0, max = 18.0, precip = 50.0)) shouldBe true
        jacket.appliesTo(forecast(min = 10.0, max = 18.0, precip = 40.0)) shouldBe false
    }

    @Test
    fun `cold morning warm afternoon triggers both sweater and shorts`() {
        // Realistic spring day: chilly start, warm peak. Min stays above the
        // coat threshold (4°C), so coat shouldn't fire.
        val day = forecast(min = 8.0, max = 25.0)
        val triggered = ClothesRule.DEFAULTS.filter { it.appliesTo(day) }.map { it.item.itemKey }
        triggered shouldBe listOf("sweater", "jacket", "shorts")
    }

    @Test
    fun `freezing morning triggers coat and gloves alongside sweater and jacket`() {
        // Sub-zero feels-like crosses every cold-weather threshold including coat
        // and the gloves default (both below 4°C).
        val day = forecast(min = -2.0, max = 4.0)
        val triggered = ClothesRule.DEFAULTS.filter { it.appliesTo(day) }.map { it.item.itemKey }
        triggered shouldBe listOf("sweater", "jacket", "coat", "gloves")
    }
}
