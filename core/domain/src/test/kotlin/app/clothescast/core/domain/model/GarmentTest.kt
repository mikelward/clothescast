package app.clothescast.core.domain.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Locale

class GarmentTest {

    @Test
    fun `default rule items round-trip through the catalog`() {
        // The defaults shipped in ClothesRule.DEFAULTS must all be picker-editable;
        // if a default's item key isn't a Garment, the editor would have no way
        // to round-trip it through the dropdown.
        ClothesRule.DEFAULTS.forEach { rule ->
            // The stored key round-trips back to the same catalog garment.
            Garment.fromKey(rule.item.itemKey) shouldBe rule.item
        }
    }

    @Test
    fun `fromKey is case-insensitive and trims whitespace`() {
        Garment.fromKey("Sweater") shouldBe Garment.SWEATER
        Garment.fromKey("  shorts  ") shouldBe Garment.SHORTS
    }

    @Test
    fun `fromKey accepts common spelling variants`() {
        // Pre-catalog rules may have used these variants when the editor was
        // free-form — round-trip them so the dropdown can preselect correctly.
        Garment.fromKey("tshirt") shouldBe Garment.TSHIRT
        Garment.fromKey("trousers") shouldBe Garment.PANTS
        Garment.fromKey("long pants") shouldBe Garment.PANTS
        Garment.fromKey("jumper") shouldBe Garment.SWEATER
    }

    @Test
    fun `fromKey returns null for unknown items`() {
        Garment.fromKey("kilt") shouldBe null
        Garment.fromKey("") shouldBe null
    }

    @Test
    fun `layerCount maps tops by perceived warmth and bottoms to zero`() {
        // Drives ClothesFormat.LAYER_COUNT — t/p/sh=1, thin-jkt/sw/hood=2,
        // jkt/coat/puffer=3 (a puffer is a warm shell, not a 4th layer);
        // bottoms and gloves don't contribute to the count.
        Garment.TSHIRT.layerCount shouldBe 1
        Garment.POLO.layerCount shouldBe 1
        Garment.SHIRT.layerCount shouldBe 1
        Garment.SWEATER.layerCount shouldBe 2
        Garment.HOODIE.layerCount shouldBe 2
        Garment.THIN_JACKET.layerCount shouldBe 2
        Garment.JACKET.layerCount shouldBe 3
        Garment.COAT.layerCount shouldBe 3
        // A puffer is a warm shell, not a literal fourth layer — same count as
        // the other shells; "extra cold" is signalled by gloves, not a higher count.
        Garment.PUFFER.layerCount shouldBe 3
        Garment.SHORTS.layerCount shouldBe 0
        Garment.SKIRT.layerCount shouldBe 0
        Garment.PANTS.layerCount shouldBe 0
        Garment.JEANS.layerCount shouldBe 0
        // Extremity gear isn't a torso layer.
        Garment.GLOVES.layerCount shouldBe 0
    }

    @Test
    fun `umbrella is a carried accessory that round-trips and reads as an accessory`() {
        // The umbrella joins the catalog as carried gear: it round-trips via
        // fromKey, sits in the CARRIED slot (so it never competes with the worn
        // top/bottom/hands stack), and reads as an accessory so the wear-clause
        // prose keeps naming it with "bring/carry" rather than "wear".
        Garment.fromKey("umbrella") shouldBe Garment.UMBRELLA
        Garment.UMBRELLA.slot shouldBe Garment.Slot.CARRIED
        Garment.UMBRELLA.layerCount shouldBe 0
        Garment.isAccessoryKey("umbrella") shouldBe true
        // Worn garments are not accessories.
        Garment.isAccessoryKey("sweater") shouldBe false
    }

    @Test
    fun `layerReduce keeps a single carried umbrella alongside the worn stack`() {
        // A firing umbrella rule passes through reduction next to the top stack
        // — carried gear substitutes within its own slot (you carry one
        // umbrella) and never displaces a top or bottom.
        val rules = listOf(
            ClothesRule(Garment.SWEATER, ClothesRule.TemperatureBelow(16.0)),
            ClothesRule(Garment.UMBRELLA, ClothesRule.PrecipitationProbabilityAbove(50.0)),
        )
        Garment.layerReduce(rules) shouldBe rules
    }

    @Test
    fun `rain jacket is an outer-layer worn shell that stacks over the warmth tier`() {
        // The rain jacket joins the catalog as a worn TOP-slot garment in the
        // OUTER layer (above SHELL), so it round-trips via fromKey, reads as a
        // worn garment (not a "bring/carry" accessory), and — being its own
        // layer — survives reduction alongside the sweater rather than
        // displacing it. The prose then reads "a sweater and a rain jacket".
        Garment.fromKey("rain-jacket") shouldBe Garment.RAIN_JACKET
        Garment.RAIN_JACKET.slot shouldBe Garment.Slot.TOP
        Garment.RAIN_JACKET.layer shouldBe Garment.Layer.OUTER
        Garment.isAccessoryKey("rain-jacket") shouldBe false
        val rules = listOf(
            ClothesRule(Garment.SWEATER, ClothesRule.TemperatureBelow(16.0)),
            ClothesRule(Garment.RAIN_JACKET, ClothesRule.PrecipitationProbabilityAbove(30.0)),
        )
        Garment.layerReduce(rules) shouldBe rules
    }

    @Test
    fun `a bare rain jacket does not suppress the base top, but a warm layer does`() {
        // The base layer is implicit only under an *insulating* covering layer.
        // A thin OUTER rain shell isn't one, so an explicit base-top rule
        // survives next to a rain jacket ("a t-shirt and a rain jacket") — the
        // prose then matches the icon, which shows the shell over the base top.
        val baseAndShell = listOf(
            ClothesRule(Garment.TSHIRT, ClothesRule.TemperatureAbove(20.0)),
            ClothesRule(Garment.RAIN_JACKET, ClothesRule.PrecipitationProbabilityAbove(30.0)),
        )
        Garment.layerReduce(baseAndShell) shouldBe baseAndShell

        // A warm MID/SHELL layer still hides the base: a t-shirt under a sweater
        // (and a rain jacket over it) reduces to "a sweater and a rain jacket".
        val baseMidShell = listOf(
            ClothesRule(Garment.TSHIRT, ClothesRule.TemperatureAbove(20.0)),
            ClothesRule(Garment.SWEATER, ClothesRule.TemperatureBelow(16.0)),
            ClothesRule(Garment.RAIN_JACKET, ClothesRule.PrecipitationProbabilityAbove(30.0)),
        )
        Garment.layerReduce(baseMidShell) shouldBe listOf(baseMidShell[1], baseMidShell[2])
    }

    @Test
    fun `fromKey does not depend on process locale`() {
        val originalDefault = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        try {
            // In Turkish locale, lowercase("SHIRT") with default-locale rules
            // becomes "shırt" (dotless ı), which won't match the stored key
            // "shirt". fromKey must stay locale-invariant.
            Garment.fromKey("SHIRT") shouldBe Garment.SHIRT
        } finally {
            Locale.setDefault(originalDefault)
        }
    }
}
