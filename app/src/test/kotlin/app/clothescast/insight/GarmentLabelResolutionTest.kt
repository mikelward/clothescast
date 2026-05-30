package app.clothescast.insight

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clothescast.core.domain.model.Garment
import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exhaustiveness guard for the hand-maintained garment → label wiring.
 *
 * [GARMENT_RES_IDS] in [ClothesPhraser] is a manual `Map<Garment, Int>`, and
 * [localizedGarmentLabel] returns null for any garment missing from it — at which
 * point the phraser silently falls back to the raw English item key in otherwise
 * localized prose. That's exactly the bug PR #812 shipped (gloves was absent from
 * the map even though its `garment_gloves` strings existed), so this test drives
 * the check off [Garment.entries]: the next garment that skips the map fails here
 * rather than reaching users as a stray English word. Runs under Robolectric so it
 * resolves real `R.string.*` resources, not a hand-rolled copy.
 */
@RunWith(AndroidJUnit4::class)
class GarmentLabelResolutionTest {
    private val resources =
        ApplicationProvider.getApplicationContext<android.content.Context>().resources

    @Test
    fun `every Garment resolves to a non-blank localized label`() {
        val unresolved = Garment.entries.filter {
            resources.localizedGarmentLabel(it.itemKey).isNullOrBlank()
        }
        // A non-empty list names the garment(s) missing from GARMENT_RES_IDS (or
        // whose garment_* string is blank) — add the mapping / resource for each.
        unresolved.shouldBeEmpty()
    }
}
