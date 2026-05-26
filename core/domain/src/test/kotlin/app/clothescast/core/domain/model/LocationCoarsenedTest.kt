package app.clothescast.core.domain.model

import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * `Location.coarsened()` is the privacy hinge for every location entry
 * point in the app — these tests pin down the rounding contract so any
 * later tweak (3dp grid, asymmetric rules) shows up as a deliberate test
 * change.
 */
class LocationCoarsenedTest {

    @Test
    fun `coarsens to two decimal places`() {
        val precise = Location(latitude = 51.50725, longitude = -0.12776)
        val coarse = precise.coarsened()
        coarse.latitude shouldBe 51.51
        coarse.longitude shouldBe -0.13
    }

    @Test
    fun `idempotent — coarsening an already-coarse location is a no-op`() {
        val coarse = Location(latitude = 51.51, longitude = -0.13)
        coarse.coarsened() shouldBe coarse
    }

    @Test
    fun `preserves displayName and countryCode`() {
        val precise = Location(
            latitude = -33.86882,
            longitude = 151.20931,
            displayName = "Sydney",
            countryCode = "AU",
        )
        val coarse = precise.coarsened()
        coarse.displayName shouldBe "Sydney"
        coarse.countryCode shouldBe "AU"
    }

    @Test
    fun `worst-case shift stays within ~785m at the equator`() {
        // 0.01° lat is ~1113m at the equator; max round-up shift on either
        // axis is half a grid step (~557m). Combined Euclidean shift caps
        // at sqrt(2) × 557m ≈ 787m — well clear of, say, a 1km radius gate.
        // Tracking explicitly so a future grid change makes the privacy /
        // utility tradeoff visible in this number.
        val point = Location(latitude = 0.005, longitude = 0.005)
        val coarse = point.coarsened()
        val latShiftMeters = (coarse.latitude - point.latitude) * 111_320.0
        val lonShiftMeters = (coarse.longitude - point.longitude) * 111_320.0
        val combined = kotlin.math.sqrt(latShiftMeters * latShiftMeters + lonShiftMeters * lonShiftMeters)
        combined shouldBeLessThanOrEqual 790.0
    }
}
