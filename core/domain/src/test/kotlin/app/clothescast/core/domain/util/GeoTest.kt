package app.clothescast.core.domain.util

import app.clothescast.core.domain.model.Location
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GeoTest {

    @Test
    fun `identical points have zero distance`() {
        val home = Location(51.5074, -0.1278, "London")
        home.distanceMetersTo(home) shouldBe 0.0
    }

    @Test
    fun `London to Paris is roughly 344 km`() {
        // Reference value: ~343.5km great-circle. Allow ±2km for the spherical
        // approximation vs. the WGS-84 ellipsoid.
        val london = Location(51.5074, -0.1278)
        val paris = Location(48.8566, 2.3522)
        val d = london.distanceMetersTo(paris)
        (d - 343_500.0).let { delta ->
            (if (delta < 0) -delta else delta) shouldBeLessThan 2_000.0
        }
    }

    @Test
    fun `antipodes are roughly half Earth circumference apart`() {
        // Mean Earth circumference is ~40,030km; antipodes ≈ 20,015km. Allow
        // ±100m for the arc-cos-style numerical wobble at exact antipodes.
        val north = Location(0.0, 0.0)
        val south = Location(0.0, 180.0)
        val d = north.distanceMetersTo(south)
        (d - 20_015_086.0).let { delta ->
            (if (delta < 0) -delta else delta) shouldBeLessThan 1_000.0
        }
    }

    @Test
    fun `antimeridian crossing handled correctly`() {
        // Two points one degree apart in longitude but straddling the 180°
        // line — equirectangular without a wrap-aware longitude diff would
        // report ~40,000km instead of ~111km at the equator. Haversine
        // doesn't care, but pin the behaviour.
        val east = Location(0.0, 179.5)
        val west = Location(0.0, -179.5)
        val d = east.distanceMetersTo(west)
        (d - 111_195.0).let { delta ->
            (if (delta < 0) -delta else delta) shouldBeLessThan 500.0
        }
    }

    @Test
    fun `isWithin matches for points inside radius`() {
        val home = Location(51.5074, -0.1278)
        // ~50m north of home — well inside the 1km at-home radius.
        val nudged = Location(51.50785, -0.1278)
        nudged.isWithin(1_000.0, of = home) shouldBe true
    }

    @Test
    fun `isWithin rejects points outside radius`() {
        val home = Location(51.5074, -0.1278)
        // Tower Bridge — ~4km from Trafalgar Square, well outside 1km.
        val far = Location(51.5055, -0.0754)
        far.isWithin(1_000.0, of = home) shouldBe false
    }

    @Test
    fun `isWithin is inclusive at the boundary`() {
        val home = Location(51.5074, -0.1278)
        val distance = home.distanceMetersTo(home)
        home.isWithin(distance, of = home) shouldBe true
    }
}
