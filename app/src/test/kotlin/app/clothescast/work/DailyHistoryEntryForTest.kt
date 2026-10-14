package app.clothescast.work

import app.clothescast.core.domain.model.BandClause
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.TemperatureBand
import app.clothescast.core.domain.model.WeatherCondition
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class DailyHistoryEntryForTest {

    private val day = LocalDate.of(2026, 4, 25)
    private val place = Location(latitude = 0.0, longitude = 0.0, displayName = "City A")

    @Test
    fun `a daytime insight records its feels-like min and max`() {
        val entry = FetchAndNotifyWorker.dailyHistoryEntryFor(insight(location = place))

        entry?.date shouldBe day
        entry?.feelsLikeMinC shouldBe 9.0
        entry?.feelsLikeMaxC shouldBe 17.0
    }

    @Test
    fun `a daytime insight for a mocked fix records nothing`() {
        // The bug: a mock-location test left tomorrow comparing against a
        // place the user never was.
        val entry = FetchAndNotifyWorker.dailyHistoryEntryFor(
            insight(location = place.copy(isMock = true)),
        )

        entry shouldBe null
    }

    @Test
    fun `an insight with no location still records`() {
        // A missing location isn't evidence of a mock; only the flag is.
        FetchAndNotifyWorker.dailyHistoryEntryFor(insight(location = null))?.date shouldBe day
    }

    @Test
    fun `a tonight insight records nothing`() {
        FetchAndNotifyWorker.dailyHistoryEntryFor(
            insight(location = place, period = ForecastPeriod.TONIGHT),
        ) shouldBe null
    }

    @Test
    fun `an insight with no hourly data records nothing`() {
        FetchAndNotifyWorker.dailyHistoryEntryFor(
            insight(location = place, hourly = emptyList()),
        ) shouldBe null
    }

    private fun insight(
        location: Location?,
        period: ForecastPeriod = ForecastPeriod.TODAY,
        hourly: List<HourlyForecast> = listOf(hour(7, 9.0), hour(13, 17.0), hour(18, 12.0)),
    ): Insight = Insight(
        summary = InsightSummary(
            period = period,
            band = BandClause(TemperatureBand.MILD, TemperatureBand.MILD),
        ),
        recommendedItems = emptyList(),
        generatedAt = Instant.parse("2026-04-25T06:00:00Z"),
        forDate = day,
        location = location,
        hourly = hourly,
        period = period,
    )

    private fun hour(h: Int, feelsLike: Double) = HourlyForecast(
        time = LocalTime.of(h, 0),
        temperatureC = feelsLike,
        feelsLikeC = feelsLike,
        precipitationProbabilityPct = 0.0,
        condition = WeatherCondition.CLEAR,
    )
}
