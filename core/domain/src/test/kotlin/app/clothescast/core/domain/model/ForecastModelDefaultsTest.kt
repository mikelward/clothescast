package app.clothescast.core.domain.model

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ForecastModelDefaultsTest {

    private fun at(lat: Double, lon: Double): Location =
        Location(latitude = lat, longitude = lon, displayName = null)

    // Three models per region: the local official (where Open-Meteo has
    // one), ECMWF IFS 0.25° and ICON. Shared here so the per-city
    // expectations stay readable.
    private val britishIsles = listOf(
        ForecastModel.UKMO_SEAMLESS,
        ForecastModel.ECMWF_IFS025,
        ForecastModel.ICON_SEAMLESS,
    )
    private val westernEurope = listOf(
        ForecastModel.ECMWF_IFS025,
        ForecastModel.ICON_SEAMLESS,
        ForecastModel.METEOFRANCE_SEAMLESS,
    )
    private val nordics = listOf(
        ForecastModel.ECMWF_IFS025,
        ForecastModel.ICON_SEAMLESS,
        ForecastModel.UKMO_SEAMLESS,
    )
    private val northAmerica = listOf(
        ForecastModel.GEM_SEAMLESS,
        ForecastModel.ECMWF_IFS025,
        ForecastModel.ICON_SEAMLESS,
    )
    private val eastAsia = listOf(
        ForecastModel.JMA_SEAMLESS,
        ForecastModel.ECMWF_IFS025,
        ForecastModel.ICON_SEAMLESS,
    )

    @Test
    fun `null location returns global default set`() {
        ForecastModel.defaultsFor(null) shouldBe ForecastModel.DEFAULTS
    }

    @Test
    fun `global default is the three-model blend`() {
        ForecastModel.DEFAULTS shouldContainExactlyInAnyOrder listOf(
            ForecastModel.ECMWF_IFS025,
            ForecastModel.ICON_SEAMLESS,
            ForecastModel.GEM_SEAMLESS,
        )
    }

    @Test
    fun `no default set anywhere carries GFS`() {
        // GFS is out of every default set, regional and global alike: a
        // mean-based consensus cannot discount a member that is wrong in a
        // consistent direction, and GFS's rainfall errors are systematically
        // dry rather than noisy. Users can still select it explicitly in the
        // Forecasters picker.
        val everywhere = listOf(
            null, // no location yet
            at(51.51, -0.13), // London
            at(48.86, 2.35), // Paris
            at(59.33, 18.07), // Stockholm
            at(64.13, -21.94), // Reykjavik
            at(40.71, -74.01), // New York
            at(49.28, -123.12), // Vancouver
            at(19.43, -99.13), // Mexico City
            at(35.68, 139.76), // Tokyo
            at(37.57, 126.98), // Seoul
            at(-33.87, 151.21), // Sydney
            at(-23.55, -46.63), // Sao Paulo
            at(1.35, 103.82), // Singapore
        )
        for (location in everywhere) {
            ForecastModel.defaultsFor(location) shouldNotContain ForecastModel.GFS_SEAMLESS
        }
    }

    @Test
    fun `London picks the British Isles set`() {
        ForecastModel.defaultsFor(at(51.51, -0.13)) shouldContainExactlyInAnyOrder britishIsles
    }

    @Test
    fun `Dublin picks the British Isles set`() {
        ForecastModel.defaultsFor(at(53.35, -6.26)) shouldContainExactlyInAnyOrder britishIsles
    }

    @Test
    fun `Edinburgh picks the British Isles set`() {
        ForecastModel.defaultsFor(at(55.95, -3.19)) shouldContainExactlyInAnyOrder britishIsles
    }

    @Test
    fun `Paris picks the Western Europe set`() {
        ForecastModel.defaultsFor(at(48.86, 2.35)) shouldContainExactlyInAnyOrder westernEurope
    }

    @Test
    fun `Berlin picks the Western Europe set`() {
        ForecastModel.defaultsFor(at(52.52, 13.41)) shouldContainExactlyInAnyOrder westernEurope
    }

    @Test
    fun `Madrid picks the Western Europe set`() {
        ForecastModel.defaultsFor(at(40.42, -3.70)) shouldContainExactlyInAnyOrder westernEurope
    }

    @Test
    fun `Stockholm picks the Nordics set`() {
        ForecastModel.defaultsFor(at(59.33, 18.07)) shouldContainExactlyInAnyOrder nordics
    }

    @Test
    fun `Reykjavik picks the Nordics set`() {
        ForecastModel.defaultsFor(at(64.13, -21.94)) shouldContainExactlyInAnyOrder nordics
    }

    @Test
    fun `New York picks the North America set`() {
        ForecastModel.defaultsFor(at(40.71, -74.01)) shouldContainExactlyInAnyOrder northAmerica
    }

    @Test
    fun `Vancouver picks the North America set`() {
        ForecastModel.defaultsFor(at(49.28, -123.12)) shouldContainExactlyInAnyOrder northAmerica
    }

    @Test
    fun `Mexico City picks the North America set`() {
        ForecastModel.defaultsFor(at(19.43, -99.13)) shouldContainExactlyInAnyOrder northAmerica
    }

    @Test
    fun `Tokyo picks the East Asia set`() {
        ForecastModel.defaultsFor(at(35.68, 139.76)) shouldContainExactlyInAnyOrder eastAsia
    }

    @Test
    fun `Seoul picks the East Asia set`() {
        ForecastModel.defaultsFor(at(37.57, 126.98)) shouldContainExactlyInAnyOrder eastAsia
    }

    @Test
    fun `Sydney falls back to global default while BOM is suspended`() {
        // Open-Meteo currently has BOM open-data delivery suspended, so the
        // ANZ branch points at DEFAULTS until BOM comes back. When BOM is
        // re-added to the enum + resolver, update this test to expect the
        // local-official set instead.
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

    @Test
    fun `every default set is three models with ECMWF reaching the second week`() {
        // Auto is the best three (maintainer, 2026-10-18). ECMWF IFS is the one
        // member reaching ~day 15, so every set must keep it.
        val everywhere = listOf(
            null,
            at(51.51, -0.13), // London
            at(48.86, 2.35), // Paris
            at(59.33, 18.07), // Stockholm
            at(64.13, -21.94), // Reykjavik
            at(40.71, -74.01), // New York
            at(35.68, 139.76), // Tokyo
            at(37.57, 126.98), // Seoul
            at(-33.87, 151.21), // Sydney
        )
        for (city in everywhere) {
            val models = ForecastModel.defaultsFor(city)
            models.size shouldBe 3
            models shouldContain ForecastModel.ECMWF_IFS025
            models shouldContain ForecastModel.ICON_SEAMLESS
        }
    }

    @Test
    fun `European defaults exclude both GFS and GEM`() {
        // GFS and GEM are both weak over Europe, so neither belongs in a
        // European default set.
        val europeanCities = listOf(
            at(51.51, -0.13), // London (British Isles)
            at(48.86, 2.35), // Paris (Western Europe)
            at(40.42, -3.70), // Madrid (Western Europe)
            at(59.33, 18.07), // Stockholm (Nordics)
            at(64.13, -21.94), // Reykjavik (Iceland)
        )
        for (city in europeanCities) {
            val models = ForecastModel.defaultsFor(city)
            models shouldNotContain ForecastModel.GFS_SEAMLESS
            models shouldNotContain ForecastModel.GEM_SEAMLESS
        }
    }
}
