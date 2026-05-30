package app.clothescast.ui.garment

import app.clothescast.core.domain.model.OutfitSuggestion
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.Test

/**
 * Exhaustiveness guard for the hand-maintained render-path lookup tables.
 *
 * Every outfit render surface — the Compose home-screen icons
 * ([GarmentTopIcon] / [GarmentBottomIcon]), the rasterised notification /
 * widget bitmap ([renderOutfitBitmap]), and the Nest-Hub card
 * ([renderOutfitCard]) — looks a garment tier up in [outfitTopDefaults] /
 * [outfitBottomDefaults] via `getValue`, which throws
 * `NoSuchElementException` for a missing key. A new
 * [OutfitSuggestion.Top] / [OutfitSuggestion.Bottom] added without its
 * defaults entry therefore compiles cleanly and only blows up at render
 * time — on the user's home screen, in the widget, and in the cast card all
 * at once.
 *
 * That's the same hand-maintained-parallel-structure gap that bit the gloves
 * work (a new garment fed one structure but skipped another): #813 guarded
 * the phraser's `GARMENT_RES_IDS` map and #815 the snapshot delta fields;
 * this is the render-path sibling. The `topDrawable` / `bottomDrawable`
 * resolvers are already compiler-guarded — they're exhaustive `when`s with no
 * `else` — so a missing drawable case can't compile; the defaults `Map`s are
 * the unguarded ones, so this drives the check off `entries` to close that
 * gap.
 *
 * When a future tier lands a third slot (e.g. an `OutfitSuggestion.Hands`
 * gloves icon — the `ic_outfit_gloves` drawable already exists), add its
 * `outfitHandsDefaults` map and a sibling assertion here so every render
 * surface is held to handling it before it can ship.
 *
 * Pure JVM — the defaults maps are plain Kotlin, so no Robolectric is needed.
 */
class OutfitRenderExhaustivenessTest {

    @Test
    fun `every Top tier has a render-path defaults entry`() {
        // A non-empty list names the Top(s) missing from outfitTopDefaults —
        // add the GarmentDefaults entry for each (matching its drawable's
        // baked fill / stroke) or renderOutfitBitmap.getValue blows up.
        OutfitSuggestion.Top.entries.filterNot { it in outfitTopDefaults }.shouldBeEmpty()
        outfitTopDefaults.keys.shouldContainExactlyInAnyOrder(*OutfitSuggestion.Top.entries.toTypedArray())
    }

    @Test
    fun `every Bottom tier has a render-path defaults entry`() {
        OutfitSuggestion.Bottom.entries.filterNot { it in outfitBottomDefaults }.shouldBeEmpty()
        outfitBottomDefaults.keys.shouldContainExactlyInAnyOrder(*OutfitSuggestion.Bottom.entries.toTypedArray())
    }

    @Test
    fun `every Hands tier has a render-path defaults entry`() {
        // The optional gloves overlay (renderTopWithHandsBitmap / GarmentHandsIcon)
        // reads outfitHandsDefaults.getValue(hands), so a new Hands tier without
        // its entry blows up only when a hands rule actually fires at render time.
        OutfitSuggestion.Hands.entries.filterNot { it in outfitHandsDefaults }.shouldBeEmpty()
        outfitHandsDefaults.keys.shouldContainExactlyInAnyOrder(*OutfitSuggestion.Hands.entries.toTypedArray())
    }
}
