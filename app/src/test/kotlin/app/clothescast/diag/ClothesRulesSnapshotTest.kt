package app.clothescast.diag

import app.clothescast.core.domain.model.Garment
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
        snap.glovesDeltaC shouldBe "0"
        snap.shortsDeltaC shouldBe "0"
        snap.umbrellaDeltaPct shouldBe "0"
        snap.rainJacketDeltaPct shouldBe "0"
    }

    @Test
    fun `umbrella gate customisation is bucketed as a percentage-point delta`() {
        // The umbrella default is precip-keyed (probability arm at 20%, OR'd with
        // a drizzle-code arm). Raising the gate to 60% is a +40pp change that must
        // register as a customised category — not silently report "0" the way
        // routing it through the °C bucket would. The probability gate is read out
        // of the composite, so a plain-probability replacement compares cleanly.
        val rules = ClothesRule.DEFAULTS.map { rule ->
            if (rule.item == Garment.UMBRELLA) {
                rule.copy(condition = ClothesRule.PrecipitationProbabilityAbove(60.0))
            } else {
                rule
            }
        }

        val snap = ClothesRulesSnapshot.from(rules)

        snap.umbrellaDeltaPct shouldBe "+40"
        snap.customisedCount shouldBe 1
        snap.categoriesCustomised shouldBe "umbrella"
        snap.allDefaults shouldBe false
    }

    @Test
    fun `lowering the umbrella gate reports a signed negative bucket`() {
        // Default probability arm 20% → 10% is a -10pp change.
        val rules = ClothesRule.DEFAULTS.map { rule ->
            if (rule.item == Garment.UMBRELLA) {
                rule.copy(condition = ClothesRule.PrecipitationProbabilityAbove(10.0))
            } else {
                rule
            }
        }

        val snap = ClothesRulesSnapshot.from(rules)

        snap.umbrellaDeltaPct shouldBe "-10"
        snap.customisedCount shouldBe 1
    }

    @Test
    fun `rain jacket gate customisation is bucketed like the umbrella`() {
        // The rain jacket ships as a precip-keyed default too (probability arm at
        // 50%, OR'd with a rain-code arm). Lowering the gate to 30% is a -20pp
        // change that registers the same way the umbrella's does.
        val rules = ClothesRule.DEFAULTS.map { rule ->
            if (rule.item == Garment.RAIN_JACKET) {
                rule.copy(condition = ClothesRule.PrecipitationProbabilityAbove(30.0))
            } else {
                rule
            }
        }

        val snap = ClothesRulesSnapshot.from(rules)

        snap.rainJacketDeltaPct shouldBe "-20"
        snap.customisedCount shouldBe 1
        snap.categoriesCustomised shouldBe "rain-jacket"
        snap.allDefaults shouldBe false
    }

    @Test
    fun `changing only the rain-code floor counts as a customisation`() {
        // The umbrella default is `≥20% OR drizzle code`. Switching the rain-code
        // floor off (a bare probability rule at the same 20% gate) leaves the
        // probability delta at "0", but the selector edit must still register —
        // otherwise it vanishes from the aggregate and reports all_defaults=true.
        val rules = ClothesRule.DEFAULTS.map { rule ->
            if (rule.item == Garment.UMBRELLA) {
                rule.copy(condition = ClothesRule.PrecipitationProbabilityAbove(20.0))
            } else {
                rule
            }
        }

        val snap = ClothesRulesSnapshot.from(rules)

        // Probability gate unchanged, so the reported pct delta stays "0"...
        snap.umbrellaDeltaPct shouldBe "0"
        // ...but the floor edit still surfaces as a customisation.
        snap.customisedCount shouldBe 1
        snap.categoriesCustomised shouldBe "umbrella"
        snap.allDefaults shouldBe false
    }

    @Test
    fun `deleting the umbrella default reports it MISSING and customised`() {
        val rules = ClothesRule.DEFAULTS.filterNot { it.item == Garment.UMBRELLA }

        val snap = ClothesRulesSnapshot.from(rules)

        snap.umbrellaDeltaPct shouldBe ClothesRulesSnapshot.MISSING
        snap.customisedCount shouldBe 1
        snap.categoriesCustomised shouldBe "umbrella"
        snap.allDefaults shouldBe false
    }

    @Test
    fun `deleting the gloves default reports it MISSING and customised`() {
        // Gloves ships as a freezing-day default; removing it from the rule list
        // must surface as a customised category with a MISSING delta, the same
        // way coat does — so the new default is measurable like the others.
        val rules = ClothesRule.DEFAULTS.filterNot { it.item.itemKey == "gloves" }

        val snap = ClothesRulesSnapshot.from(rules)

        snap.glovesDeltaC shouldBe ClothesRulesSnapshot.MISSING
        snap.customisedCount shouldBe 1
        snap.categoriesCustomised shouldBe "gloves"
        snap.allDefaults shouldBe false
    }

    @Test
    fun `single category nudged reports signed integer delta`() {
        // Default jacket is TemperatureBelow(10.0); nudge to 8°C → delta of -2.
        val rules = ClothesRule.DEFAULTS.map { rule ->
            if (rule.item.itemKey == "jacket") rule.copy(condition = ClothesRule.TemperatureBelow(8.0))
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
            if (rule.item.itemKey == "shorts") rule.copy(condition = ClothesRule.TemperatureAbove(26.0))
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
            when (rule.item.itemKey) {
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
        val rules = ClothesRule.DEFAULTS.filter { it.item.itemKey != "coat" }

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
            item = Garment.PUFFER,
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
            if (rule.item.itemKey == "jacket") rule.copy(
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
            when (rule.item.itemKey) {
                "shorts" -> rule.copy(condition = ClothesRule.TemperatureAbove(26.0))
                "jacket" -> rule.copy(condition = ClothesRule.TemperatureBelow(8.0))
                else -> rule
            }
        }

        val snap = ClothesRulesSnapshot.from(rules)

        snap.categoriesCustomised shouldBe "jacket,shorts"
    }
}
