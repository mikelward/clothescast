package app.clothescast.core.domain.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FallbackRangeTest {
    @Test
    fun `default rules give a lower bound for the top fallback at the sweater threshold`() {
        // Default rules: sweater below 16, jacket below 10, coat below 4.
        // The warmest "below" threshold (sweater @ 16) gates the top fallback —
        // it can only fire at 16°C and above.
        fallbackRange(ClothesRule.DEFAULTS, FallbackTier.TOP) shouldBe
            FallbackRange(lowerC = 16.0, upperC = null)
    }

    @Test
    fun `default rules give an upper bound for the bottom fallback at the shorts threshold`() {
        // Default rules: shorts above 23. The bottom fallback only fires when
        // shorts doesn't — i.e. at 23°C and below.
        fallbackRange(ClothesRule.DEFAULTS, FallbackTier.BOTTOM) shouldBe
            FallbackRange(lowerC = null, upperC = 23.0)
    }

    @Test
    fun `no rules at all leave the fallback unbounded`() {
        fallbackRange(emptyList(), FallbackTier.TOP) shouldBe FallbackRange(null, null)
        fallbackRange(emptyList(), FallbackTier.BOTTOM) shouldBe FallbackRange(null, null)
    }

    @Test
    fun `unrelated tier rules don't constrain the fallback`() {
        // A bottom-tier `shorts` rule doesn't gate the top fallback, and a
        // top-tier `sweater` rule doesn't gate the bottom fallback.
        fallbackRange(
            listOf(ClothesRule(Garment.SHORTS, ClothesRule.TemperatureAbove(24.0))),
            FallbackTier.TOP,
        ) shouldBe FallbackRange(null, null)
        fallbackRange(
            listOf(ClothesRule(Garment.SWEATER, ClothesRule.TemperatureBelow(18.0))),
            FallbackTier.BOTTOM,
        ) shouldBe FallbackRange(null, null)
    }

    @Test
    fun `precipitation rules are ignored`() {
        fallbackRange(
            listOf(ClothesRule(Garment.SWEATER, ClothesRule.PrecipitationProbabilityAbove(30.0))),
            FallbackTier.TOP,
        ) shouldBe FallbackRange(null, null)
    }

    @Test
    fun `inverted bounds mark the range as empty`() {
        // A user who pinned a sweater rule below 25°C AND a polo rule above 10°C
        // has the top fallback shadowed — it would never apply. The "If no rules
        // match" row reads "never" so the Settings UI surfaces the conflict.
        val range = fallbackRange(
            listOf(
                ClothesRule(Garment.SWEATER, ClothesRule.TemperatureBelow(25.0)),
                ClothesRule(Garment.POLO, ClothesRule.TemperatureAbove(10.0)),
            ),
            FallbackTier.TOP,
        )
        range.empty shouldBe true
    }

    @Test
    fun `Fahrenheit-stored thresholds normalize to Celsius`() {
        val range = fallbackRange(
            listOf(ClothesRule(Garment.SWEATER, ClothesRule.TemperatureBelow(65.0, TemperatureUnit.FAHRENHEIT))),
            FallbackTier.TOP,
        )
        range.lowerC shouldBe (65.0 - 32.0) * 5.0 / 9.0
        range.upperC shouldBe null
    }

    @Test
    fun `multiple below thresholds pick the warmest`() {
        // sweater@18 and jacket@12 — the warmest wins because the fallback only
        // fires once the warmer rule stops firing.
        fallbackRange(
            listOf(
                ClothesRule(Garment.JACKET, ClothesRule.TemperatureBelow(12.0)),
                ClothesRule(Garment.SWEATER, ClothesRule.TemperatureBelow(18.0)),
            ),
            FallbackTier.TOP,
        ).lowerC shouldBe 18.0
    }
}
