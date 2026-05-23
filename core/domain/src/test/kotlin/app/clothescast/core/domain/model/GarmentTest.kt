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
            val garment = Garment.fromKey(rule.item)
            (garment != null) shouldBe true
            garment!!.itemKey shouldBe rule.item
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
        // jkt/coat=3, puffer=4; bottoms don't contribute to the count.
        Garment.TSHIRT.layerCount shouldBe 1
        Garment.POLO.layerCount shouldBe 1
        Garment.SHIRT.layerCount shouldBe 1
        Garment.SWEATER.layerCount shouldBe 2
        Garment.HOODIE.layerCount shouldBe 2
        Garment.THIN_JACKET.layerCount shouldBe 2
        Garment.JACKET.layerCount shouldBe 3
        Garment.COAT.layerCount shouldBe 3
        Garment.PUFFER.layerCount shouldBe 4
        Garment.SHORTS.layerCount shouldBe 0
        Garment.SKIRT.layerCount shouldBe 0
        Garment.PANTS.layerCount shouldBe 0
        Garment.JEANS.layerCount shouldBe 0
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
