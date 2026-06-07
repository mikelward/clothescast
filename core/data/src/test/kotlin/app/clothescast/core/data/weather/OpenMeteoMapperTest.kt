package app.clothescast.core.data.weather

import app.clothescast.core.domain.model.WeatherCondition
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

class OpenMeteoMapperTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun loadFixture(): OpenMeteoResponse {
        val text = checkNotNull(javaClass.getResourceAsStream("/openmeteo_london.json")) {
            "fixture missing"
        }.bufferedReader().readText()
        return json.decodeFromString(text)
    }

    @Test
    fun `bundle preserves yesterday and today dates`() {
        val bundle = OpenMeteoMapper.toBundle(loadFixture())

        bundle.yesterday.date shouldBe LocalDate.of(2026, 4, 24)
        bundle.today.date shouldBe LocalDate.of(2026, 4, 25)
    }

    @Test
    fun `yesterday daily values are mapped`() {
        val y = OpenMeteoMapper.toBundle(loadFixture()).yesterday

        y.temperatureMinC shouldBe 12.0
        y.temperatureMaxC shouldBe 18.0
        y.feelsLikeMinC shouldBe 10.0
        y.feelsLikeMaxC shouldBe 17.0
        y.precipitationProbabilityMaxPct shouldBe 5.0
        y.precipitationMmTotal shouldBe 0.0
        y.condition shouldBe WeatherCondition.PARTLY_CLOUDY
    }

    @Test
    fun `yesterday hourly is filtered to yesterday's date only`() {
        val y = OpenMeteoMapper.toBundle(loadFixture()).yesterday

        // Fixture has 2 hours on 2026-04-24 + 8 hours on 2026-04-25; yesterday
        // gets the 2026-04-24 entries so the delta clause can slice both today
        // and yesterday to the same daytime window.
        y.hourly shouldHaveSize 2
        y.hourly.first().time shouldBe LocalTime.of(22, 0)
        y.hourly.last().time shouldBe LocalTime.of(23, 0)
    }

    @Test
    fun `today daily values are mapped`() {
        val t = OpenMeteoMapper.toBundle(loadFixture()).today

        t.temperatureMinC shouldBe 16.0
        t.temperatureMaxC shouldBe 24.0
        t.feelsLikeMinC shouldBe 15.0
        t.feelsLikeMaxC shouldBe 23.0
        t.precipitationProbabilityMaxPct shouldBe 60.0
        t.precipitationMmTotal shouldBe 4.5
        t.condition shouldBe WeatherCondition.RAIN
    }

    @Test
    fun `today hourly is filtered to today's date only`() {
        val t = OpenMeteoMapper.toBundle(loadFixture()).today

        // Fixture has 2 hours on 2026-04-24 + 8 hours on 2026-04-25.
        t.hourly shouldHaveSize 8
        t.hourly.first().time shouldBe LocalTime.of(0, 0)
        t.hourly.last().time shouldBe LocalTime.of(21, 0)
    }

    @Test
    fun `forecast zone is parsed from response timezone`() {
        val bundle = OpenMeteoMapper.toBundle(loadFixture())

        bundle.forecastZone shouldBe ZoneId.of("Europe/London")
    }

    @Test
    fun `forecast zone collapses to null on an unparseable timezone string`() {
        // Defensive: an upstream proxy that rewrote the field to something
        // ZoneId can't accept (or a future Open-Meteo format change) must not
        // fail the whole forecast — the caller falls back to the device zone.
        val response = OpenMeteoResponse(
            timezone = "not a real zone",
            daily = DailyData(
                time = listOf("2026-04-24", "2026-04-25"),
                temperatureMin = listOf(10.0, 12.0),
                temperatureMax = listOf(18.0, 20.0),
                feelsLikeMin = listOf(10.0, 12.0),
                feelsLikeMax = listOf(18.0, 20.0),
                precipitationProbabilityMax = listOf(0, 0),
                precipitationSum = listOf(0.0, 0.0),
                weatherCode = listOf(0, 0),
            ),
            hourly = HourlyData(
                time = listOf("2026-04-25T00:00"),
                temperature = listOf(10.0),
                feelsLike = listOf(10.0),
                precipitationProbability = listOf(0),
                weatherCode = listOf(0),
            ),
        )

        OpenMeteoMapper.toBundle(response).forecastZone shouldBe null
    }

    @Test
    fun `peak rain hour is preserved for downstream insight rendering`() {
        val t = OpenMeteoMapper.toBundle(loadFixture()).today

        val peak = t.hourly.maxByOrNull { it.precipitationProbabilityPct }
        peak shouldNotBe null
        peak!!.time shouldBe LocalTime.of(15, 0)
        peak.precipitationProbabilityPct shouldBe 60.0
        peak.condition shouldBe WeatherCondition.RAIN
    }

    @Test
    fun `today hourly stays full when response includes a tomorrow day`() {
        // Pinned by the forecast_days=2 request — we ask for tomorrow's pre-dawn
        // hourly so the tonight insight can wrap past midnight. The mapper must
        // keep ignoring tomorrow's daily slot and keep date-filtering tomorrow's
        // hourly entries out of today, so today's chart is unaffected by the
        // extra day in the response.
        val threeDay = OpenMeteoResponse(
            timezone = "UTC",
            daily = DailyData(
                time = listOf("2026-04-24", "2026-04-25", "2026-04-26"),
                temperatureMin = listOf(12.0, 16.0, 14.0),
                temperatureMax = listOf(18.0, 24.0, 22.0),
                feelsLikeMin = listOf(10.0, 15.0, 13.0),
                feelsLikeMax = listOf(17.0, 23.0, 21.0),
                precipitationProbabilityMax = listOf(5, 60, 10),
                precipitationSum = listOf(0.0, 4.5, 0.0),
                weatherCode = listOf(2, 63, 1),
            ),
            hourly = HourlyData(
                time = (0..23).map { String.format(Locale.ROOT, "2026-04-25T%02d:00", it) } +
                    listOf("2026-04-26T00:00", "2026-04-26T01:00"),
                temperature = (0..23).map { 16.0 + it } + listOf(14.0, 14.5),
                feelsLike = (0..23).map { 15.0 + it } + listOf(13.0, 13.5),
                precipitationProbability = (0..25).map { 0 },
                weatherCode = (0..25).map { 0 },
            ),
        )

        val today = OpenMeteoMapper.toBundle(threeDay).today

        today.date shouldBe LocalDate.of(2026, 4, 25)
        today.temperatureMaxC shouldBe 24.0
        today.hourly shouldHaveSize 24
        today.hourly.first().time shouldBe LocalTime.of(0, 0)
        today.hourly.last().time shouldBe LocalTime.of(23, 0)
    }

    @Test
    fun `null temperatures and probabilities are tolerated as zero`() {
        val sparse = OpenMeteoResponse(
            timezone = "UTC",
            daily = DailyData(
                time = listOf("2026-04-24", "2026-04-25"),
                temperatureMin = listOf(null, 16.0),
                temperatureMax = listOf(null, 24.0),
                feelsLikeMin = listOf(null, null),
                feelsLikeMax = listOf(null, null),
                precipitationProbabilityMax = listOf(null, null),
                precipitationSum = listOf(null, 4.5),
                weatherCode = listOf(null, 63),
            ),
            hourly = HourlyData(
                time = listOf("2026-04-25T15:00"),
                temperature = listOf(null),
                feelsLike = listOf(null),
                precipitationProbability = listOf(null),
                weatherCode = listOf(null),
            ),
        )

        val bundle = OpenMeteoMapper.toBundle(sparse)

        bundle.yesterday.temperatureMinC shouldBe 0.0
        bundle.yesterday.temperatureMaxC shouldBe 0.0
        bundle.yesterday.feelsLikeMinC shouldBe 0.0
        bundle.yesterday.feelsLikeMaxC shouldBe 0.0
        bundle.yesterday.precipitationProbabilityMaxPct shouldBe 0.0
        bundle.yesterday.condition shouldBe WeatherCondition.UNKNOWN
        bundle.today.precipitationProbabilityMaxPct shouldBe 0.0
        bundle.today.hourly.single().temperatureC shouldBe 0.0
        bundle.today.hourly.single().feelsLikeC shouldBe 0.0
        bundle.today.hourly.single().condition shouldBe WeatherCondition.UNKNOWN
    }

    @Test
    fun `feels-like falls back to raw temperature when missing`() {
        // Open-Meteo can return apparent_temperature null even when temperature_2m is set
        // (rare, but observed). Fall back so clothes rules still match something sensible.
        val partial = OpenMeteoResponse(
            timezone = "UTC",
            daily = DailyData(
                time = listOf("2026-04-24", "2026-04-25"),
                temperatureMin = listOf(12.0, 16.0),
                temperatureMax = listOf(18.0, 24.0),
                feelsLikeMin = listOf(null, null),
                feelsLikeMax = listOf(null, null),
                precipitationProbabilityMax = listOf(0, 0),
                precipitationSum = listOf(0.0, 0.0),
                weatherCode = listOf(0, 0),
            ),
            hourly = HourlyData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
        )

        val bundle = OpenMeteoMapper.toBundle(partial)

        bundle.yesterday.feelsLikeMinC shouldBe 12.0
        bundle.yesterday.feelsLikeMaxC shouldBe 18.0
        bundle.today.feelsLikeMinC shouldBe 16.0
        bundle.today.feelsLikeMaxC shouldBe 24.0
    }

    @Test
    fun `tomorrow hourly is exposed when the response carries a third daily entry`() {
        val threeDay = OpenMeteoResponse(
            timezone = "UTC",
            daily = DailyData(
                time = listOf("2026-04-24", "2026-04-25", "2026-04-26"),
                temperatureMin = listOf(12.0, 16.0, 14.0),
                temperatureMax = listOf(18.0, 24.0, 21.0),
                feelsLikeMin = listOf(10.0, 15.0, 13.0),
                feelsLikeMax = listOf(17.0, 23.0, 20.0),
                precipitationProbabilityMax = listOf(5, 60, 20),
                precipitationSum = listOf(0.0, 4.5, 0.5),
                weatherCode = listOf(2, 63, 3),
            ),
            hourly = HourlyData(
                time = listOf(
                    "2026-04-25T20:00",
                    "2026-04-25T23:00",
                    "2026-04-26T03:00",
                    "2026-04-26T06:00",
                ),
                temperature = listOf(16.0, 12.0, 6.0, 8.0),
                feelsLike = listOf(14.0, 10.0, 3.0, 5.0),
                precipitationProbability = listOf(10, 5, 5, 5),
                weatherCode = listOf(2, 2, 2, 2),
            ),
        )

        val bundle = OpenMeteoMapper.toBundle(threeDay)

        bundle.today.hourly shouldHaveSize 2
        bundle.tomorrowHourly shouldHaveSize 2
        bundle.tomorrowHourly.first().time shouldBe LocalTime.of(3, 0)
        bundle.tomorrowHourly.last().time shouldBe LocalTime.of(6, 0)
    }

    @Test
    fun `tomorrow hourly is empty when the response only carries two daily entries`() {
        // Backwards compat for older fixtures / a forecast_days=1 caller.
        val bundle = OpenMeteoMapper.toBundle(loadFixture())

        bundle.tomorrowHourly shouldBe emptyList()
    }

    @Test
    fun `tomorrow daily is populated when the response carries a third daily entry`() {
        // The third daily entry is what feeds the home screen's "Tomorrow" outfit
        // card on a tonight insight — populate min / max / feels-like / condition
        // so [OutfitSuggestion.fromForecast] can pick a sensible top + bottom.
        val threeDay = OpenMeteoResponse(
            timezone = "UTC",
            daily = DailyData(
                time = listOf("2026-04-24", "2026-04-25", "2026-04-26"),
                temperatureMin = listOf(12.0, 16.0, 14.0),
                temperatureMax = listOf(18.0, 24.0, 21.0),
                feelsLikeMin = listOf(10.0, 15.0, 13.0),
                feelsLikeMax = listOf(17.0, 23.0, 20.0),
                precipitationProbabilityMax = listOf(5, 60, 20),
                precipitationSum = listOf(0.0, 4.5, 0.5),
                weatherCode = listOf(2, 63, 3),
            ),
            hourly = HourlyData(
                time = listOf("2026-04-26T03:00", "2026-04-26T06:00"),
                temperature = listOf(6.0, 8.0),
                feelsLike = listOf(3.0, 5.0),
                precipitationProbability = listOf(5, 5),
                weatherCode = listOf(2, 2),
            ),
        )

        val tomorrow = OpenMeteoMapper.toBundle(threeDay).tomorrow

        tomorrow shouldNotBe null
        tomorrow!!.date shouldBe LocalDate.of(2026, 4, 26)
        tomorrow.feelsLikeMinC shouldBe 13.0
        tomorrow.feelsLikeMaxC shouldBe 20.0
        tomorrow.condition shouldBe WeatherCondition.CLOUDY
    }

    @Test
    fun `tomorrow daily is null when the response only carries two daily entries`() {
        val bundle = OpenMeteoMapper.toBundle(loadFixture())

        bundle.tomorrow shouldBe null
    }

    @Test
    fun `upcomingDays carries every day after today with hourly attached`() {
        // Fifteen-entry response (yesterday + today + thirteen future days) is
        // what the production client now asks for via forecast_days=14 — enough
        // to feed both the "Next 7 days" (days 1-7) and "Following 7 days"
        // (days 8-14) pager pages. The mapper must populate `upcomingDays` with
        // tomorrow + the rest, in date order, with each day's hourly slice
        // attached.
        val days = (0L..14L).map { LocalDate.of(2026, 4, 24).plusDays(it) }
        val response = OpenMeteoResponse(
            timezone = "UTC",
            daily = DailyData(
                time = days.map { it.toString() },
                temperatureMin = days.indices.map { 10.0 + it },
                temperatureMax = days.indices.map { 20.0 + it },
                feelsLikeMin = days.indices.map { 9.0 + it },
                feelsLikeMax = days.indices.map { 19.0 + it },
                precipitationProbabilityMax = days.indices.map { it * 5 },
                precipitationSum = days.indices.map { it * 0.5 },
                weatherCode = days.indices.map { 2 },
            ),
            hourly = HourlyData(
                // One marker hour per day at 12:00 so the mapper has something
                // to bucket — proves each day's hourly is sliced from the
                // shared stream and attached to the right day.
                time = days.map { String.format(Locale.ROOT, "%sT12:00", it) },
                temperature = days.indices.map { 15.0 + it },
                feelsLike = days.indices.map { 14.0 + it },
                precipitationProbability = days.indices.map { 0 },
                weatherCode = days.indices.map { 2 },
            ),
        )

        val bundle = OpenMeteoMapper.toBundle(response)

        bundle.upcomingDays shouldHaveSize 13
        bundle.upcomingDays.first().date shouldBe LocalDate.of(2026, 4, 26)
        bundle.upcomingDays.last().date shouldBe LocalDate.of(2026, 5, 8)
        bundle.upcomingDays.first().hourly shouldHaveSize 1
        bundle.upcomingDays.first().hourly.first().time shouldBe LocalTime.of(12, 0)
        // Tomorrow is still exposed via [ForecastBundle.tomorrow] for the
        // existing tonight-insight paths, and matches `upcomingDays[0]`.
        bundle.tomorrow!!.date shouldBe bundle.upcomingDays.first().date
    }

    @Test
    fun `upcomingDays is empty when the response only carries two daily entries`() {
        val bundle = OpenMeteoMapper.toBundle(loadFixture())

        bundle.upcomingDays shouldBe emptyList()
    }

    @Test
    fun `mismatched-length hourly parallel arrays are tolerated without throwing`() {
        // Open-Meteo always sends matching arrays, but a buggy proxy or a future
        // field-by-field rollout could produce shorter temperature/feelsLike/etc.
        // arrays than `time`. The hourly mapper must degrade gracefully (using
        // 0.0 / UNKNOWN defaults) rather than throwing IndexOutOfBoundsException.
        val mismatched = OpenMeteoResponse(
            timezone = "UTC",
            daily = DailyData(
                time = listOf("2026-04-24", "2026-04-25"),
                temperatureMin = listOf(12.0, 16.0),
                temperatureMax = listOf(18.0, 24.0),
                feelsLikeMin = listOf(10.0, 15.0),
                feelsLikeMax = listOf(17.0, 23.0),
                precipitationProbabilityMax = listOf(5, 60),
                precipitationSum = listOf(0.0, 4.5),
                weatherCode = listOf(2, 63),
            ),
            hourly = HourlyData(
                // Three timestamps but only one entry in each value array.
                time = listOf("2026-04-25T09:00", "2026-04-25T15:00", "2026-04-25T21:00"),
                temperature = listOf(18.0),
                feelsLike = listOf(17.0),
                precipitationProbability = listOf(20),
                weatherCode = listOf(63),
            ),
        )

        val today = OpenMeteoMapper.toBundle(mismatched).today

        // First hour is fully mapped; the other two fall back to zero / UNKNOWN.
        today.hourly shouldHaveSize 3
        today.hourly[0].temperatureC shouldBe 18.0
        today.hourly[0].feelsLikeC shouldBe 17.0
        today.hourly[1].temperatureC shouldBe 0.0
        today.hourly[1].feelsLikeC shouldBe 0.0
        today.hourly[1].condition shouldBe WeatherCondition.UNKNOWN
        today.hourly[2].temperatureC shouldBe 0.0
    }

    @Test
    fun `rejects responses missing two daily entries`() {
        val short = OpenMeteoResponse(
            timezone = "UTC",
            daily = DailyData(
                time = listOf("2026-04-25"),
                temperatureMin = listOf(16.0),
                temperatureMax = listOf(24.0),
                feelsLikeMin = listOf(15.0),
                feelsLikeMax = listOf(23.0),
                precipitationProbabilityMax = listOf(60),
                precipitationSum = listOf(4.5),
                weatherCode = listOf(63),
            ),
            hourly = HourlyData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
        )

        try {
            OpenMeteoMapper.toBundle(short)
            error("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }
}
