package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.WeatherCondition
import app.clothescast.core.domain.model.WeekAheadClause
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class DeriveWeekAheadInsightTest {
    private val subject = DeriveWeekAheadInsight()

    private val today = day(LocalDate.of(2026, 4, 27), feelsHigh = 18.0)

    private fun day(
        date: LocalDate,
        feelsHigh: Double = 18.0,
        feelsLow: Double = feelsHigh - 6.0,
        precipPct: Double = 5.0,
        condition: WeatherCondition = WeatherCondition.PARTLY_CLOUDY,
    ): DailyForecast = DailyForecast(
        date = date,
        temperatureMinC = feelsLow + 1.0,
        temperatureMaxC = feelsHigh + 1.0,
        feelsLikeMinC = feelsLow,
        feelsLikeMaxC = feelsHigh,
        precipitationProbabilityMaxPct = precipPct,
        precipitationMmTotal = 0.0,
        condition = condition,
    )

    @Test
    fun `returns null when no upcoming days are supplied`() {
        subject(today, emptyList()).shouldBeNull()
    }

    @Test
    fun `returns null on a calm flat week`() {
        val upcoming = (1L..6L).map { day(today.date.plusDays(it), feelsHigh = 18.0 + it * 0.3) }
        subject(today, upcoming).shouldBeNull()
    }

    @Test
    fun `picks the nearest rainy day at or above 50 percent`() {
        val upcoming = listOf(
            day(today.date.plusDays(1)),
            day(today.date.plusDays(2), precipPct = 75.0, condition = WeatherCondition.RAIN),
            day(today.date.plusDays(3), precipPct = 80.0, condition = WeatherCondition.RAIN),
        )
        val out = subject(today, upcoming).shouldNotBeNull()
        val rain = out.rain.shouldNotBeNull()
        rain.date shouldBe today.date.plusDays(2)
        rain.isTomorrow shouldBe false
        rain.condition shouldBe WeatherCondition.RAIN
        out.firstWarmer.shouldBeNull()
        out.firstCooler.shouldBeNull()
        out.persistence.shouldBeNull()
    }

    @Test
    fun `marks tomorrow when the rainy day is tomorrow`() {
        val upcoming = listOf(
            day(today.date.plusDays(1), precipPct = 70.0, condition = WeatherCondition.RAIN),
            day(today.date.plusDays(2)),
        )
        val out = subject(today, upcoming).shouldNotBeNull()
        out.rain.shouldNotBeNull().isTomorrow shouldBe true
    }

    @Test
    fun `falls back to RAIN when the day condition is non-precipitating`() {
        // 60% probability but the day's headline condition is CLOUDY (base
        // under-called the type). Headline still announces rain.
        val upcoming = listOf(
            day(today.date.plusDays(2), precipPct = 60.0, condition = WeatherCondition.CLOUDY),
        )
        val out = subject(today, upcoming).shouldNotBeNull()
        out.rain.shouldNotBeNull().condition shouldBe WeatherCondition.RAIN
    }

    @Test
    fun `rain rule does not fire below the 50 percent floor`() {
        // 35% would have hedged as POSSIBLE under the old rule, but the
        // weekly headline now only mentions rain when a majority of
        // consulted models call it (50% on the day-level rollup).
        val upcoming = listOf(
            day(today.date.plusDays(1), precipPct = 35.0, condition = WeatherCondition.DRIZZLE),
            day(today.date.plusDays(2), precipPct = 49.0, condition = WeatherCondition.RAIN),
        )
        subject(today, upcoming).shouldBeNull()
    }

    @Test
    fun `emits firstCooler at the first day that drops by the threshold`() {
        // Today high 18. Day +2 already drops to 14 (-4) — that should win
        // over a bigger swing later (day +3 at 11 / -7).
        val upcoming = listOf(
            day(today.date.plusDays(1), feelsHigh = 17.0),
            day(today.date.plusDays(2), feelsHigh = 14.0),
            day(today.date.plusDays(3), feelsHigh = 11.0),
        )
        val out = subject(today, upcoming).shouldNotBeNull()
        val cooler = out.firstCooler.shouldNotBeNull()
        cooler.date shouldBe today.date.plusDays(2)
        cooler.isTomorrow shouldBe false
        out.firstWarmer.shouldBeNull()
        out.rain.shouldBeNull()
        out.persistence.shouldBeNull()
    }

    @Test
    fun `emits firstWarmer with isTomorrow when the swing lands tomorrow`() {
        val upcoming = listOf(
            day(today.date.plusDays(1), feelsHigh = 23.0),
            day(today.date.plusDays(2), feelsHigh = 19.0),
        )
        val out = subject(today, upcoming).shouldNotBeNull()
        val warmer = out.firstWarmer.shouldNotBeNull()
        warmer.isTomorrow shouldBe true
        out.firstCooler.shouldBeNull()
    }

    @Test
    fun `emits both firstWarmer and firstCooler when the week swings both ways`() {
        // Today 18°. Day +1 hits 22° (warmer, +4). Day +3 drops to 14° (cooler, -4).
        val upcoming = listOf(
            day(today.date.plusDays(1), feelsHigh = 22.0),
            day(today.date.plusDays(2), feelsHigh = 20.0),
            day(today.date.plusDays(3), feelsHigh = 14.0),
        )
        val out = subject(today, upcoming).shouldNotBeNull()
        val warmer = out.firstWarmer.shouldNotBeNull()
        warmer.date shouldBe today.date.plusDays(1)
        warmer.isTomorrow shouldBe true
        val cooler = out.firstCooler.shouldNotBeNull()
        cooler.date shouldBe today.date.plusDays(3)
        cooler.isTomorrow shouldBe false
    }

    @Test
    fun `temperature shift is suppressed under the 3 degree threshold`() {
        val upcoming = (1L..6L).map { day(today.date.plusDays(it), feelsHigh = 20.0) }
        // +2°C swing, no rain — both rules below threshold.
        subject(today, upcoming).shouldBeNull()
    }

    @Test
    fun `null deltaThresholdC disables the temperature shift rules entirely`() {
        // +12°C swing tomorrow would normally fire firstWarmer; with the
        // rule disabled (the user's "Temperature change: Off" setting),
        // neither shift slot fills and a dry week emits nothing.
        val upcoming = listOf(
            day(today.date.plusDays(1), feelsHigh = 30.0),
            day(today.date.plusDays(2), feelsHigh = 31.0),
        )
        subject(today, upcoming, deltaThresholdC = null).shouldBeNull()
    }

    @Test
    fun `null deltaThresholdC still lets rain and persistence rules fire`() {
        val rainyUpcoming = listOf(
            day(today.date.plusDays(1), feelsHigh = 30.0), // would trip the shift rule if enabled
            day(today.date.plusDays(2), precipPct = 60.0, condition = WeatherCondition.RAIN),
        )
        val out = subject(today, rainyUpcoming, deltaThresholdC = null).shouldNotBeNull()
        out.rain.shouldNotBeNull()
        out.firstWarmer.shouldBeNull()
        out.firstCooler.shouldBeNull()
    }

    @Test
    fun `emits temperature shift and rain together when both fire`() {
        // +12°C swing tomorrow, 60% rain in two days — both clauses should appear.
        val upcoming = listOf(
            day(today.date.plusDays(1), feelsHigh = 30.0),
            day(today.date.plusDays(2), precipPct = 60.0, condition = WeatherCondition.RAIN),
        )
        val out = subject(today, upcoming).shouldNotBeNull()
        val warmer = out.firstWarmer.shouldNotBeNull()
        warmer.date shouldBe today.date.plusDays(1)
        val rain = out.rain.shouldNotBeNull()
        rain.date shouldBe today.date.plusDays(2)
        out.firstCooler.shouldBeNull()
        out.persistence.shouldBeNull()
    }

    @Test
    fun `combines temperature shift and rain on the same day into two clauses`() {
        // The rainy day is the same day as the first cooler day.
        // The renderer emits both clauses; the formatter prints two day
        // references — the duplication is by design.
        val upcoming = listOf(
            day(today.date.plusDays(1), feelsHigh = 18.0),
            day(today.date.plusDays(2), feelsHigh = 11.0, precipPct = 70.0, condition = WeatherCondition.RAIN),
        )
        val out = subject(today, upcoming).shouldNotBeNull()
        val cooler = out.firstCooler.shouldNotBeNull()
        cooler.date shouldBe today.date.plusDays(2)
        val rain = out.rain.shouldNotBeNull()
        rain.date shouldBe today.date.plusDays(2)
    }

    @Test
    fun `emits StaysHot when today and every upcoming day are HOT`() {
        val hotToday = day(today.date, feelsHigh = 32.0)
        val upcoming = (1L..6L).map { day(today.date.plusDays(it), feelsHigh = 30.0 + it * 0.2) }
        val out = subject(hotToday, upcoming).shouldNotBeNull()
        out.persistence shouldBe WeekAheadClause.StaysHot
        out.firstWarmer.shouldBeNull()
        out.firstCooler.shouldBeNull()
        out.rain.shouldBeNull()
    }

    @Test
    fun `emits StaysCold when today and every upcoming day are COLD or FREEZING`() {
        // Highs cluster within 3° so neither shift rule fires alongside
        // persistence — the user's mental model of "stays cold" is that
        // *no* day stands out.
        val coldToday = day(today.date, feelsHigh = 6.0)
        val upcoming = listOf(
            day(today.date.plusDays(1), feelsHigh = 5.0),
            day(today.date.plusDays(2), feelsHigh = 7.0),
            day(today.date.plusDays(3), feelsHigh = 4.5),
            day(today.date.plusDays(4), feelsHigh = 8.0),
            day(today.date.plusDays(5), feelsHigh = 6.5),
            day(today.date.plusDays(6), feelsHigh = 3.5), // FREEZING (boundary)
        )
        val out = subject(coldToday, upcoming).shouldNotBeNull()
        out.persistence shouldBe WeekAheadClause.StaysCold
    }

    @Test
    fun `emits persistence and rain together when a hot week also has a rainy day`() {
        val hotToday = day(today.date, feelsHigh = 32.0)
        val upcoming = listOf(
            day(today.date.plusDays(1), feelsHigh = 31.0),
            day(today.date.plusDays(2), feelsHigh = 30.0),
            day(today.date.plusDays(3), feelsHigh = 30.5),
            day(today.date.plusDays(4), feelsHigh = 31.5, precipPct = 65.0, condition = WeatherCondition.THUNDERSTORM),
            day(today.date.plusDays(5), feelsHigh = 32.5),
            day(today.date.plusDays(6), feelsHigh = 30.0),
        )
        val out = subject(hotToday, upcoming).shouldNotBeNull()
        out.persistence shouldBe WeekAheadClause.StaysHot
        val rain = out.rain.shouldNotBeNull()
        rain.date shouldBe today.date.plusDays(4)
        out.firstWarmer.shouldBeNull()
        out.firstCooler.shouldBeNull()
    }

    @Test
    fun `persistence does not fire when one day breaks the band`() {
        // Today and 5 days HOT, one day cool — no persistence call, but the
        // cooler slot fires for the cool day.
        val hotToday = day(today.date, feelsHigh = 30.0)
        val upcoming = (1L..5L).map { day(today.date.plusDays(it), feelsHigh = 31.0) } +
            day(today.date.plusDays(6), feelsHigh = 20.0)
        val out = subject(hotToday, upcoming).shouldNotBeNull()
        out.firstCooler.shouldNotBeNull()
        out.persistence.shouldBeNull()
    }

    @Test
    fun `shift slot fills and persistence stays empty when both could fire`() {
        // Today HOT, one upcoming day HOT but a much cooler intermediate day.
        // Persistence breaks because of the cool day; the cooler slot fires.
        val hotToday = day(today.date, feelsHigh = 30.0)
        val upcoming = listOf(
            day(today.date.plusDays(1), feelsHigh = 20.0),
            day(today.date.plusDays(2), feelsHigh = 31.0),
        )
        val out = subject(hotToday, upcoming).shouldNotBeNull()
        out.firstCooler.shouldNotBeNull()
        out.persistence.shouldBeNull()
    }
}
