package app.clothescast.diag

import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.TemperatureUnit
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ClothesRulesSnapshotTest {

    @Test
    fun `unchanged defaults report all zeros`() {
        val snap = ClothesRulesSnapshot.from(ClothesRule.DEFAULTS)

        snap.customisedCount shouldBe 0
        snap.extraRulesCount shouldBe 0
        snap.categoriesCustomised shouldBe ""
        snap.allDefaults shouldBe true
        snap.sweaterDeltaC shouldBe "0"
        snap.jacketDeltaC shouldBe "0"
        snap.coatDeltaC shouldBe "0"
        snap.shortsDeltaC shouldBe "0"
    }

    @Test
    fun `single category nudged reports signed integer delta`() {
        // Default jacket is TemperatureBelow(10.0); nudge to 8°C → delta of -2.
        val rules = ClothesRule.DEFAULTS.map { rule ->
            if (rule.item == "jacket") rule.copy(condition = ClothesRule.TemperatureBelow(8.0))
            else rule
        }

        val snap = ClothesRulesSnapshot.from(rules)

        snap.customisedCount shouldBe 1
        snap.extraRulesCount shouldBe 0
        snap.categoriesCustomised shouldBe "jacket"
        snap.allDefaults shouldBe false
        snap.jacketDeltaC shouldBe "-2"
        snap.sweaterDeltaC shouldBe "0"
    }

    @Test
    fun `positive delta is sign-prefixed`() {
        // Default shorts is TemperatureAbove(23.0); nudge to 26°C → delta of +3.
        val rules = ClothesRule.DEFAULTS.map { rule ->
            if (rule.item == "shorts") rule.copy(condition = ClothesRule.TemperatureAbove(26.0))
            else rule
        }

        val snap = ClothesRulesSnapshot.from(rules)

        snap.shortsDeltaC shouldBe "+3"
        snap.customisedCount shouldBe 1
    }

    @Test
    fun `deltas beyond the clamp saturate to bucket boundary`() {
        // jacket default 10, set to 30 → delta +20 → clamps to "+5+";
        // coat default 4, set to 50 → delta +46 → clamps to "+5+".
        val rules = ClothesRule.DEFAULTS.map { rule ->
            when (rule.item) {
                "jacket" -> rule.copy(condition = ClothesRule.TemperatureBelow(30.0))
                "coat" -> rule.copy(condition = ClothesRule.TemperatureBelow(50.0))
                else -> rule
            }
        }

        val snap = ClothesRulesSnapshot.from(rules)

        // jacket: default 10, user 30, delta = +20 → "+5+"
        snap.jacketDeltaC shouldBe "+5+"
        // coat: default 4, user 50, delta = +46 → "+5+"
        snap.coatDeltaC shouldBe "+5+"
    }

    @Test
    fun `deleted category is reported as MISSING`() {
        val rules = ClothesRule.DEFAULTS.filter { it.item != "coat" }

        val snap = ClothesRulesSnapshot.from(rules)

        snap.coatDeltaC shouldBe ClothesRulesSnapshot.MISSING
        // Missing categories count as customisation so the dashboard sees the user
        // has tweaked their setup, not just left the defaults.
        snap.customisedCount shouldBe 1
        snap.categoriesCustomised shouldBe "coat"
        snap.allDefaults shouldBe false
    }

    @Test
    fun `extra non-default rule increments extra count without affecting default deltas`() {
        // Hat isn't in DEFAULTS; should bump extraRulesCount but not customisedCount.
        val rules = ClothesRule.DEFAULTS + ClothesRule(
            item = "hat",
            condition = ClothesRule.TemperatureBelow(5.0),
        )

        val snap = ClothesRulesSnapshot.from(rules)

        snap.customisedCount shouldBe 0
        snap.extraRulesCount shouldBe 1
        snap.allDefaults shouldBe false
        snap.sweaterDeltaC shouldBe "0"
    }

    @Test
    fun `unit-typed Fahrenheit threshold is converted to Celsius before bucketing`() {
        // 50°F = 10°C exactly; delta from default 10°C is 0. Confirms that
        // thresholdC() does the unit conversion under the bucket math.
        val rules = ClothesRule.DEFAULTS.map { rule ->
            if (rule.item == "jacket") rule.copy(
                condition = ClothesRule.TemperatureBelow(50.0, TemperatureUnit.FAHRENHEIT),
            ) else rule
        }

        val snap = ClothesRulesSnapshot.from(rules)

        snap.jacketDeltaC shouldBe "0"
        snap.customisedCount shouldBe 0
    }

    @Test
    fun `categories_customised is sorted alphabetically`() {
        // Customise shorts and jacket; expect "jacket,shorts" not "shorts,jacket".
        val rules = ClothesRule.DEFAULTS.map { rule ->
            when (rule.item) {
                "shorts" -> rule.copy(condition = ClothesRule.TemperatureAbove(26.0))
                "jacket" -> rule.copy(condition = ClothesRule.TemperatureBelow(8.0))
                else -> rule
            }
        }

        val snap = ClothesRulesSnapshot.from(rules)

        snap.categoriesCustomised shouldBe "jacket,shorts"
    }
}
