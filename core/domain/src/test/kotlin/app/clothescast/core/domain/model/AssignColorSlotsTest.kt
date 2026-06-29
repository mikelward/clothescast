package app.clothescast.core.domain.model

import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AssignColorSlotsTest {
    private val ecmwf = ForecastModel.ECMWF_IFS025.openMeteoId
    private val gfs = ForecastModel.GFS_SEAMLESS.openMeteoId
    private val icon = ForecastModel.ICON_SEAMLESS.openMeteoId
    private val gem = ForecastModel.GEM_SEAMLESS.openMeteoId

    @Test
    fun `assigns enum-order slots from an empty prior`() {
        val slots = ForecastModel.assignColorSlots(
            setOf(ForecastModel.ICON_SEAMLESS, ForecastModel.ECMWF_IFS025, ForecastModel.GFS_SEAMLESS),
            emptyMap(),
        )
        // Set order doesn't matter — slots fill in enum order: ECMWF, GFS, ICON.
        slots[ecmwf] shouldBe 0
        slots[gfs] shouldBe 1
        slots[icon] shouldBe 2
    }

    @Test
    fun `survivors keep their slot and a new model takes the freed slot`() {
        val prior = mapOf(ecmwf to 0, gfs to 1, icon to 2)
        // Drop GFS (slot 1), add GEM: survivors keep 0 and 2, GEM takes freed 1.
        val slots = ForecastModel.assignColorSlots(
            setOf(ForecastModel.ECMWF_IFS025, ForecastModel.ICON_SEAMLESS, ForecastModel.GEM_SEAMLESS),
            prior,
        )
        slots[ecmwf] shouldBe 0
        slots[icon] shouldBe 2
        slots[gem] shouldBe 1
        slots shouldNotContainKey gfs
    }

    @Test
    fun `stays within the pool size`() {
        val slots = ForecastModel.assignColorSlots(ForecastModel.DEFAULTS, emptyMap())
        slots.values.forEach { (it in 0 until ForecastModel.MAX_MODELS) shouldBe true }
        slots.values.toSet().size shouldBe ForecastModel.DEFAULTS.size // all distinct
    }
}
