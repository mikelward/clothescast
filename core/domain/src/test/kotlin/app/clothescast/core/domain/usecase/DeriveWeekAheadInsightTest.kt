package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.PrecipLikelihood
import app.clothescast.core.domain.model.WeatherCondition
import app.clothescast.core.domain.model.WeekAheadInsight
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
    fun `picks the nearest rainy day and labels Tuesday as on Tuesday`() {
        val upcoming = listOf(
            day(today.date.plusDays(1)),
            day(today.date.plusDays(2), precipPct = 75.0, condition = WeatherCondition.RAIN),
            day(today.date.plusDays(3), precipPct = 80.0, condition = WeatherCondition.RAIN),
        )
        val out = subject(today, upcoming).shouldBeInstanceOf<WeekAheadInsight.Rain>()
        out.date shouldBe today.date.plusDays(2)
        out.isTomorrow shouldBe false
        out.likelihood shouldBe PrecipLikelihood.LIKELY
        out.condition shouldBe WeatherCondition.RAIN
    }

    @Test
    fun `marks tomorrow when the rainy day is tomorrow`() {
        val upcoming = listOf(
            day(today.date.plusDays(1), precipPct = 70.0, condition = WeatherCondition.RAIN),
            day(today.date.plusDays(2)),
        )
        val out = subject(today, upcoming).shouldBeInstanceOf<WeekAheadInsight.Rain>()
        out.isTomorrow shouldBe true
    }

    @Test
    fun `hedges as POSSIBLE when probability is between 30 and 50`() {
        val upcoming = listOf(
            day(today.date.plusDays(1), precipPct = 35.0, condition = WeatherCondition.DRIZZLE),
        )
        val out = subject(today, upcoming).shouldBeInstanceOf<WeekAheadInsight.Rain>()
        out.likelihood shouldBe PrecipLikelihood.POSSIBLE
        out.condition shouldBe WeatherCondition.DRIZZLE
    }

    @Test
    fun `falls back to RAIN when the day condition is non-precipitating`() {
        // 60% probability but the day's headline condition is CLOUDY (base
        // under-called the type). Headline still announces rain.
        val upcoming = listOf(
            day(today.date.plusDays(2), precipPct = 60.0, condition = WeatherCondition.CLOUDY),
        )
        val out = subject(today, upcoming).shouldBeInstanceOf<WeekAheadInsight.Rain>()
        out.condition shouldBe WeatherCondition.RAIN
    }

    @Test
    fun `rain rule does not fire below the 30 percent floor`() {
        val upcoming = (1L..6L).map { day(today.date.plusDays(it), precipPct = 25.0) }
        subject(today, upcoming).shouldBeNull()
    }

    @Test
    fun `emits Cooler when the biggest swing is downward`() {
        // Today high 18. Day +3 drops to 11 (-7) — biggest swing in the window.
        val upcoming = listOf(
            day(today.date.plusDays(1), feelsHigh = 17.0),
            day(today.date.plusDays(2), feelsHigh = 16.0),
            day(today.date.plusDays(3), feelsHigh = 11.0),
            day(today.date.plusDays(4), feelsHigh = 14.0),
        )
        val out = subject(today, upcoming).shouldBeInstanceOf<WeekAheadInsight.Cooler>()
        out.date shouldBe today.date.plusDays(3)
        out.degrees shouldBe 7
        out.isTomorrow shouldBe false
    }

    @Test
    fun `emits Warmer with isTomorrow when the swing lands tomorrow`() {
        val upcoming = listOf(
            day(today.date.plusDays(1), feelsHigh = 23.0),
            day(today.date.plusDays(2), feelsHigh = 19.0),
        )
        val out = subject(today, upcoming).shouldBeInstanceOf<WeekAheadInsight.Warmer>()
        out.isTomorrow shouldBe true
        out.degrees shouldBe 5
    }

    @Test
    fun `temperature shift is suppressed under the 3 degree threshold`() {
        val upcoming = (1L..6L).map { day(today.date.plusDays(it), feelsHigh = 20.0) }
        // +2°C swing, no rain — both rules below threshold.
        subject(today, upcoming).shouldBeNull()
    }

    @Test
    fun `temperature shift uses the unrounded threshold so 2_6 rounds-to-3 still suppresses`() {
        val upcoming = listOf(day(today.date.plusDays(1), feelsHigh = today.feelsLikeMaxC + 2.6))
        subject(today, upcoming).shouldBeNull()
    }

    @Test
    fun `null deltaThresholdC disables the temperature shift rule entirely`() {
        // Same +12°C swing the priority test exercises — but with the rule
        // disabled (the user's "Temperature change: Off" setting), the
        // headline falls through to the rain rule and emits nothing here
        // because the upcoming days are dry.
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
        subject(today, rainyUpcoming, deltaThresholdC = null)
            .shouldBeInstanceOf<WeekAheadInsight.Rain>()
    }

    @Test
    fun `rain takes priority over a bigger temperature swing`() {
        val upcoming = listOf(
            day(today.date.plusDays(1), feelsHigh = 30.0), // +12°C swing
            day(today.date.plusDays(2), precipPct = 60.0, condition = WeatherCondition.RAIN),
        )
        subject(today, upcoming).shouldBeInstanceOf<WeekAheadInsight.Rain>()
    }

    @Test
    fun `emits StaysHot when today and every upcoming day are HOT`() {
        val hotToday = day(today.date, feelsHigh = 32.0)
        val upcoming = (1L..6L).map { day(today.date.plusDays(it), feelsHigh = 30.0 + it * 0.2) }
        subject(hotToday, upcoming) shouldBe WeekAheadInsight.StaysHot
    }

    @Test
    fun `emits StaysCold when today and every upcoming day are COLD or FREEZING`() {
        // Highs cluster within 3° so the shift rule doesn't fire before
        // persistence — the user's mental model of "stays cold" is that
        // *neither* day stands out.
        val coldToday = day(today.date, feelsHigh = 6.0)
        val upcoming = listOf(
            day(today.date.plusDays(1), feelsHigh = 5.0),
            day(today.date.plusDays(2), feelsHigh = 7.0),
            day(today.date.plusDays(3), feelsHigh = 4.5),
            day(today.date.plusDays(4), feelsHigh = 8.0),
            day(today.date.plusDays(5), feelsHigh = 6.5),
            day(today.date.plusDays(6), feelsHigh = 3.5), // FREEZING (boundary)
        )
        subject(coldToday, upcoming) shouldBe WeekAheadInsight.StaysCold
    }

    @Test
    fun `persistence does not fire when one day breaks the band`() {
        // Today and 5 days HOT, one day cool — no persistence call.
        val hotToday = day(today.date, feelsHigh = 30.0)
        val upcoming = (1L..5L).map { day(today.date.plusDays(it), feelsHigh = 31.0) } +
            day(today.date.plusDays(6), feelsHigh = 20.0)
        // The cool day is only -10° below today, so the shift rule will fire instead.
        subject(hotToday, upcoming).shouldBeInstanceOf<WeekAheadInsight.Cooler>()
    }

    @Test
    fun `shift rule beats persistence when both could fire`() {
        // Today HOT, one upcoming day HOT but a much cooler intermediate day.
        val hotToday = day(today.date, feelsHigh = 30.0)
        val upcoming = listOf(
            day(today.date.plusDays(1), feelsHigh = 20.0),
            day(today.date.plusDays(2), feelsHigh = 31.0),
        )
        subject(hotToday, upcoming).shouldBeInstanceOf<WeekAheadInsight.Cooler>()
    }
}
