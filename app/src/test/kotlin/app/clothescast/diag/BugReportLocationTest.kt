package app.clothescast.diag

import app.clothescast.core.domain.model.Location
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BugReportLocationTest {

    private val place = Location(latitude = 0.0, longitude = 0.0, displayName = "City A")

    @Test
    fun `a real fix reads as coordinates and name`() {
        BugReport.locationDescription(place) shouldBe "0.00, 0.00 — City A"
    }

    @Test
    fun `a mocked fix says so`() {
        BugReport.locationDescription(place.copy(isMock = true)) shouldBe "0.00, 0.00 — City A (mock)"
    }

    @Test
    fun `an unnamed location is called out`() {
        BugReport.locationDescription(place.copy(displayName = null)) shouldBe "0.00, 0.00 — (unnamed)"
    }
}
