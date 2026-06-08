package app.clothescast.core.domain.model

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ForecastModelDefaultsTest {

    private fun at(lat: Double, lon: Double): Location =
        Location(latitude = lat, longitude = lon, displayName = null)

    // The regional locals (UKMO / ARPEGE / JMA) and ICON are short-range, so
    // every default set keeps a long-range model painting the second-week
    // charts: AIFS (~day 15) everywhere, paired with ECMWF IFS 0.25° (~day
    // 15). GEM (~day 10) reinforces only where it's skillful — North America
    // and East Asia — and is left out of the European sets the same way GFS
    // is. Shared here so the per-city expectations stay readable.
    private val britishIsles = listOf(
        ForecastModel.UKMO_SEAMLESS,
        ForecastModel.ECMWF_IFS025,
        ForecastModel.ICON_SEAMLESS,
        ForecastModel.ECMWF_AIFS025_SINGLE,
    )
    private val westernEurope = listOf(
        ForecastModel.ECMWF_IFS025,
        ForecastModel.ICON_SEAMLESS,
        ForecastModel.METEOFRANCE_SEAMLESS,
        ForecastModel.ECMWF_AIFS025_SINGLE,
    )
    private val nordics = listOf(
        ForecastModel.ECMWF_IFS025,
        ForecastModel.ICON_SEAMLESS,
        ForecastModel.UKMO_SEAMLESS,
        ForecastModel.ECMWF_AIFS025_SINGLE,
    )
    private val northAmerica = listOf(
        ForecastModel.GFS_SEAMLESS,
        ForecastModel.GEM_SEAMLESS,
        ForecastModel.ECMWF_IFS025,
        ForecastModel.ECMWF_AIFS025_SINGLE,
    )
    private val eastAsia = listOf(
        ForecastModel.JMA_SEAMLESS,
        ForecastModel.ECMWF_IFS025,
        ForecastModel.ICON_SEAMLESS,
        ForecastModel.GEM_SEAMLESS,
        ForecastModel.ECMWF_AIFS025_SINGLE,
    )

    @Test
    fun `null location returns global default set`() {
        ForecastModel.defaultsFor(null) shouldBe ForecastModel.DEFAULTS
    }

    @Test
    fun `global default is the five-model blend with second-week coverage`() {
        ForecastModel.DEFAULTS shouldContainExactlyInAnyOrder listOf(
            ForecastModel.ECMWF_IFS025,
            ForecastModel.GFS_SEAMLESS,
            ForecastModel.ICON_SEAMLESS,
            ForecastModel.GEM_SEAMLESS,
            ForecastModel.ECMWF_AIFS025_SINGLE,
        )
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
    fun `every regional default carries a model that reaches the second week`() {
        // The whole point of the GEM + AIFS additions: no region should be
        // left with only short-range models painting days 8-14. AIFS reaches
        // ~day 15 everywhere, so assert it's present in each region's set.
        val cities = listOf(
            at(51.51, -0.13), // London
            at(48.86, 2.35), // Paris
            at(59.33, 18.07), // Stockholm
            at(64.13, -21.94), // Reykjavik
            at(40.71, -74.01), // New York
            at(35.68, 139.76), // Tokyo
            at(37.57, 126.98), // Seoul
        )
        for (city in cities) {
            ForecastModel.defaultsFor(city) shouldContain ForecastModel.ECMWF_AIFS025_SINGLE
        }
    }

    @Test
    fun `European defaults exclude both GFS and GEM`() {
        // GFS and GEM are both weak over Europe, so neither belongs in a
        // European default set — Europe leans on ECMWF IFS + AIFS for the
        // second week instead of GEM's tail reinforcement.
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
