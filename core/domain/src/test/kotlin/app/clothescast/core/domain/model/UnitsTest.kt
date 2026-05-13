package app.clothescast.core.domain.model

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class UnitsTest {

    @Test
    fun `celsius is the identity for both directions`() {
        val c = 18.0
        c.toUnit(TemperatureUnit.CELSIUS) shouldBe c
        c.fromUnit(TemperatureUnit.CELSIUS) shouldBe c
    }

    @Test
    fun `fahrenheit toUnit matches the textbook conversion`() {
        0.0.toUnit(TemperatureUnit.FAHRENHEIT) shouldBe 32.0
        100.0.toUnit(TemperatureUnit.FAHRENHEIT) shouldBe 212.0
        18.0.toUnit(TemperatureUnit.FAHRENHEIT) shouldBe (64.4 plusOrMinus 1e-9)
    }

    @Test
    fun `fahrenheit fromUnit is the inverse`() {
        32.0.fromUnit(TemperatureUnit.FAHRENHEIT) shouldBe (0.0 plusOrMinus 1e-9)
        212.0.fromUnit(TemperatureUnit.FAHRENHEIT) shouldBe (100.0 plusOrMinus 1e-9)
        65.0.fromUnit(TemperatureUnit.FAHRENHEIT) shouldBe (18.333333333 plusOrMinus 1e-6)
    }

    @Test
    fun `roundtrip through fahrenheit returns the original celsius`() {
        listOf(-20.0, -5.5, 0.0, 12.3, 18.0, 37.0).forEach { c ->
            c.toUnit(TemperatureUnit.FAHRENHEIT).fromUnit(TemperatureUnit.FAHRENHEIT) shouldBe (c plusOrMinus 1e-9)
        }
    }

    @Test
    fun `kmh is the identity for wind speed`() {
        15.0.toWindSpeedUnit(WindSpeedUnit.KMH) shouldBe 15.0
        0.0.toWindSpeedUnit(WindSpeedUnit.KMH) shouldBe 0.0
    }

    @Test
    fun `mph conversion matches the textbook factor`() {
        // 1 mile = 1.609344 km exactly, so 1 km/h = 1 / 1.609344 mph.
        1.609344.toWindSpeedUnit(WindSpeedUnit.MPH) shouldBe (1.0 plusOrMinus 1e-9)
        100.0.toWindSpeedUnit(WindSpeedUnit.MPH) shouldBe (62.1371192 plusOrMinus 1e-6)
        0.0.toWindSpeedUnit(WindSpeedUnit.MPH) shouldBe (0.0 plusOrMinus 1e-9)
    }

    @Test
    fun `wind speed unit symbols are the lowercase abbreviations`() {
        WindSpeedUnit.KMH.symbol() shouldBe "km/h"
        WindSpeedUnit.MPH.symbol() shouldBe "mph"
    }

    @Test
    fun `wind speed unit derives from the chosen distance unit`() {
        DistanceUnit.KILOMETERS.windSpeedUnit() shouldBe WindSpeedUnit.KMH
        DistanceUnit.MILES.windSpeedUnit() shouldBe WindSpeedUnit.MPH
    }
}
