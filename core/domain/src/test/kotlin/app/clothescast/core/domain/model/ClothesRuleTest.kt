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
        peakRainCondition: WeatherCondition = WeatherCondition.UNKNOWN,
    ): DailyForecast = DailyForecast(
        date = date,
        temperatureMinC = min,
        temperatureMaxC = max,
        feelsLikeMinC = feelsLikeMin,
        feelsLikeMaxC = feelsLikeMax,
        precipitationProbabilityMaxPct = precip,
        precipitationMmTotal = 0.0,
        condition = condition,
        peakRainCondition = peakRainCondition,
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
    fun `umbrella default fires on its 20 percent gate or a drizzle code`() {
        val umbrella = ClothesRule.DEFAULTS.first { it.item == Garment.UMBRELLA }
        // Probability arm: gate is 20%, lower than the rain jacket's.
        umbrella.appliesTo(forecast(min = 10.0, max = 18.0, precip = 25.0)) shouldBe true
        umbrella.appliesTo(forecast(min = 10.0, max = 18.0, precip = 15.0)) shouldBe false
        // Code arm: a drizzle code with no measurable probability still fires it.
        umbrella.appliesTo(
            forecast(min = 10.0, max = 18.0, precip = 0.0, peakRainCondition = WeatherCondition.DRIZZLE),
        ) shouldBe true
    }

    @Test
    fun `rain jacket default fires on its 50 percent gate or a rain code`() {
        val jacket = ClothesRule.DEFAULTS.first { it.item == Garment.RAIN_JACKET }
        // Probability arm: heavier 50% bar than the umbrella's.
        jacket.appliesTo(forecast(min = 10.0, max = 18.0, precip = 55.0)) shouldBe true
        jacket.appliesTo(forecast(min = 10.0, max = 18.0, precip = 40.0)) shouldBe false
        // Code arm needs rain (or worse), not mere drizzle.
        jacket.appliesTo(
            forecast(min = 10.0, max = 18.0, precip = 0.0, peakRainCondition = WeatherCondition.DRIZZLE),
        ) shouldBe false
        jacket.appliesTo(
            forecast(min = 10.0, max = 18.0, precip = 0.0, peakRainCondition = WeatherCondition.RAIN),
        ) shouldBe true
    }

    @Test
    fun `rain code rule keys on the per-model peak, not the base condition`() {
        val rule = ClothesRule(Garment.UMBRELLA, ClothesRule.RainCode(WeatherCondition.DRIZZLE))
        // Fires on the per-model rain evidence the prose's drizzle tier renders...
        rule.appliesTo(forecast(min = 10.0, max = 18.0, peakRainCondition = WeatherCondition.RAIN)) shouldBe true
        // ...snow is off the rain scale, so it never fires rain gear...
        rule.appliesTo(forecast(min = 10.0, max = 18.0, peakRainCondition = WeatherCondition.SNOW)) shouldBe false
        // ...and a base RAIN code with no per-model signal does NOT fire it. That
        // keeps the rule aligned with the rain clause: in the base-code,
        // below-threshold, no-per-model case the prose stays silent, so the rule
        // must too (peakRainCondition is UNKNOWN when per-model data is absent).
        rule.appliesTo(forecast(min = 10.0, max = 18.0, condition = WeatherCondition.RAIN)) shouldBe false
    }

    @Test
    fun `anyOf matches when either arm matches and never on an empty list`() {
        val rule = ClothesRule.AnyOf(
            listOf(
                ClothesRule.PrecipitationProbabilityAbove(50.0),
                ClothesRule.RainCode(WeatherCondition.DRIZZLE),
            ),
        )
        rule.matches(forecast(min = 10.0, max = 18.0, precip = 60.0)) shouldBe true
        rule.matches(forecast(min = 10.0, max = 18.0, peakRainCondition = WeatherCondition.DRIZZLE)) shouldBe true
        rule.matches(forecast(min = 10.0, max = 18.0, precip = 10.0)) shouldBe false
        ClothesRule.AnyOf(emptyList()).matches(forecast(min = 10.0, max = 18.0, precip = 99.0)) shouldBe false
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
