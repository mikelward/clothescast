package app.clothescast.core.domain.model

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ForecastModelDefaultsTest {

    private fun at(lat: Double, lon: Double): Location =
        Location(latitude = lat, longitude = lon, displayName = null)

    @Test
    fun `null location returns global default trio`() {
        ForecastModel.defaultsFor(null) shouldBe ForecastModel.DEFAULTS
    }

    @Test
    fun `London picks UKMO + ECMWF + ICON`() {
        ForecastModel.defaultsFor(at(51.51, -0.13)) shouldContainExactlyInAnyOrder listOf(
            ForecastModel.UKMO_SEAMLESS,
            ForecastModel.ECMWF_IFS04,
            ForecastModel.ICON_SEAMLESS,
        )
    }

    @Test
    fun `Dublin picks UKMO + ECMWF + ICON`() {
        ForecastModel.defaultsFor(at(53.35, -6.26)) shouldContainExactlyInAnyOrder listOf(
            ForecastModel.UKMO_SEAMLESS,
            ForecastModel.ECMWF_IFS04,
            ForecastModel.ICON_SEAMLESS,
        )
    }

    @Test
    fun `Edinburgh picks UKMO + ECMWF + ICON`() {
        ForecastModel.defaultsFor(at(55.95, -3.19)) shouldContainExactlyInAnyOrder listOf(
            ForecastModel.UKMO_SEAMLESS,
            ForecastModel.ECMWF_IFS04,
            ForecastModel.ICON_SEAMLESS,
        )
    }

    @Test
    fun `Paris picks ECMWF + ICON + ARPEGE`() {
        ForecastModel.defaultsFor(at(48.86, 2.35)) shouldContainExactlyInAnyOrder listOf(
            ForecastModel.ECMWF_IFS04,
            ForecastModel.ICON_SEAMLESS,
            ForecastModel.METEOFRANCE_SEAMLESS,
        )
    }

    @Test
    fun `Berlin picks ECMWF + ICON + ARPEGE`() {
        ForecastModel.defaultsFor(at(52.52, 13.41)) shouldContainExactlyInAnyOrder listOf(
            ForecastModel.ECMWF_IFS04,
            ForecastModel.ICON_SEAMLESS,
            ForecastModel.METEOFRANCE_SEAMLESS,
        )
    }

    @Test
    fun `Madrid picks ECMWF + ICON + ARPEGE`() {
        ForecastModel.defaultsFor(at(40.42, -3.70)) shouldContainExactlyInAnyOrder listOf(
            ForecastModel.ECMWF_IFS04,
            ForecastModel.ICON_SEAMLESS,
            ForecastModel.METEOFRANCE_SEAMLESS,
        )
    }

    @Test
    fun `Stockholm picks ECMWF + ICON + UKMO`() {
        ForecastModel.defaultsFor(at(59.33, 18.07)) shouldContainExactlyInAnyOrder listOf(
            ForecastModel.ECMWF_IFS04,
            ForecastModel.ICON_SEAMLESS,
            ForecastModel.UKMO_SEAMLESS,
        )
    }

    @Test
    fun `Reykjavik picks ECMWF + ICON + UKMO`() {
        ForecastModel.defaultsFor(at(64.13, -21.94)) shouldContainExactlyInAnyOrder listOf(
            ForecastModel.ECMWF_IFS04,
            ForecastModel.ICON_SEAMLESS,
            ForecastModel.UKMO_SEAMLESS,
        )
    }

    @Test
    fun `New York picks GFS + GEM + ECMWF`() {
        ForecastModel.defaultsFor(at(40.71, -74.01)) shouldContainExactlyInAnyOrder listOf(
            ForecastModel.GFS_SEAMLESS,
            ForecastModel.GEM_SEAMLESS,
            ForecastModel.ECMWF_IFS04,
        )
    }

    @Test
    fun `Vancouver picks GFS + GEM + ECMWF`() {
        ForecastModel.defaultsFor(at(49.28, -123.12)) shouldContainExactlyInAnyOrder listOf(
            ForecastModel.GFS_SEAMLESS,
            ForecastModel.GEM_SEAMLESS,
            ForecastModel.ECMWF_IFS04,
        )
    }

    @Test
    fun `Mexico City picks GFS + GEM + ECMWF`() {
        ForecastModel.defaultsFor(at(19.43, -99.13)) shouldContainExactlyInAnyOrder listOf(
            ForecastModel.GFS_SEAMLESS,
            ForecastModel.GEM_SEAMLESS,
            ForecastModel.ECMWF_IFS04,
        )
    }

    @Test
    fun `Tokyo picks JMA + ECMWF + ICON`() {
        ForecastModel.defaultsFor(at(35.68, 139.76)) shouldContainExactlyInAnyOrder listOf(
            ForecastModel.JMA_SEAMLESS,
            ForecastModel.ECMWF_IFS04,
            ForecastModel.ICON_SEAMLESS,
        )
    }

    @Test
    fun `Seoul picks JMA + ECMWF + ICON`() {
        ForecastModel.defaultsFor(at(37.57, 126.98)) shouldContainExactlyInAnyOrder listOf(
            ForecastModel.JMA_SEAMLESS,
            ForecastModel.ECMWF_IFS04,
            ForecastModel.ICON_SEAMLESS,
        )
    }

    @Test
    fun `Sydney falls back to global default while BOM is suspended`() {
        // Open-Meteo currently has BOM open-data delivery suspended, so the
        // ANZ branch points at DEFAULTS until BOM comes back. When BOM is
        // re-added to the enum + resolver, update this test to expect the
        // local-official trio instead.
        ForecastModel.defaultsFor(at(-33.87, 151.21)) shouldBe ForecastModel.DEFAULTS
    }

    @Test
    fun `Sao Paulo falls back to global default`() {
        ForecastModel.defaultsFor(at(-23.55, -46.63)) shouldBe ForecastModel.DEFAULTS
    }

    @Test
    fun `Cape Town falls back to global default`() {
        ForecastModel.defaultsFor(at(-33.93, 18.42)) shouldBe ForecastModel.DEFAULTS
    }

    @Test
    fun `Singapore falls back to global default`() {
        ForecastModel.defaultsFor(at(1.35, 103.82)) shouldBe ForecastModel.DEFAULTS
    }
}
