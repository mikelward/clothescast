package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.CalendarEvent
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.DailyHistoryEntry
import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.DeltaClause
import app.clothescast.core.domain.model.DistanceUnit
import app.clothescast.core.domain.model.EventKind
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.ForecastSnapshot
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.Schedule
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.model.WeatherCondition
import app.clothescast.core.domain.repository.ForecastBundle
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Covers [DeriveInsight]'s "prefer our own history over upstream past-days data"
 * override for the delta clause. Motivating bug: Open-Meteo's
 * `forecast?past_days=1` for Liverpool 2026-05-23 returned a flat 14.0..17.8 °C
 * full-day swing when the user observed a 29 °C high in person, so the morning
 * insight confidently emitted "4° warmer than yesterday" when it should have
 * been the other direction. The fix records the daytime aggregate we actually
 * delivered each day and prefers that for the next morning's comparison.
 */
class DeriveInsightHistoricYesterdayTest {
    private val today = DailyForecast(
        date = LocalDate.of(2026, 5, 24),
        temperatureMinC = 12.0,
        temperatureMaxC = 23.0,
        feelsLikeMinC = 12.0,
        feelsLikeMaxC = 22.0,
        precipitationProbabilityMaxPct = 0.0,
        precipitationMmTotal = 0.0,
        condition = WeatherCondition.CLOUDY,
    )

    // Bogus upstream past-days data — flat 14..18, much lower than what was
    // actually delivered yesterday morning, mirrors the observed Open-Meteo
    // failure mode.
    private val bogusBundleYesterday = DailyForecast(
        date = LocalDate.of(2026, 5, 23),
        temperatureMinC = 14.0,
        temperatureMaxC = 18.0,
        feelsLikeMinC = 14.0,
        feelsLikeMaxC = 18.0,
        precipitationProbabilityMaxPct = 0.0,
        precipitationMmTotal = 0.0,
        condition = WeatherCondition.CLOUDY,
    )

    private val prefs = UserPreferences(
        schedule = Schedule.default(ZoneOffset.UTC),
        deliveryMode = DeliveryMode.NOTIFICATION_ONLY,
        temperatureUnit = TemperatureUnit.CELSIUS,
        distanceUnit = DistanceUnit.KILOMETERS,
        clothesRules = ClothesRule.DEFAULTS,
    )

    private fun snapshot(historic: DailyHistoryEntry?): ForecastSnapshot = ForecastSnapshot(
        bundle = ForecastBundle(today = today, yesterday = bogusBundleYesterday),
        events = emptyList(),
        location = Location(latitude = 53.4054, longitude = -2.9924, displayName = "Liverpool"),
        period = ForecastPeriod.TODAY,
        generatedAt = Instant.parse("2026-05-24T07:00:00Z"),
        historicYesterday = historic,
    )

    @Test
    fun `historic yesterday override flips the delta direction when upstream data was wrong`() {
        // The day we actually delivered: feels-like 15..28 in Liverpool. With
        // today's 12..22 that's a -6 max delta — today should read as cooler.
        val historic = DailyHistoryEntry(
            date = LocalDate.of(2026, 5, 23),
            feelsLikeMinC = 15.0,
            feelsLikeMaxC = 28.0,
        )

        val withOverride = DeriveInsight()(snapshot(historic), prefs)
        val withoutOverride = DeriveInsight()(snapshot(historic = null), prefs)

        // Without the override: bogus upstream feels-like max 18.0 vs today
        // 22.0 → +4 warmer.
        withoutOverride.insight.summary.delta.shouldNotBeNull()
        withoutOverride.insight.summary.delta!!.degrees shouldBe 4
        withoutOverride.insight.summary.delta!!.direction shouldBe DeltaClause.Direction.WARMER

        // With the override: real delivered max 28.0 vs today 22.0 → 6 cooler.
        withOverride.insight.summary.delta.shouldNotBeNull()
        withOverride.insight.summary.delta!!.degrees shouldBe 6
        withOverride.insight.summary.delta!!.direction shouldBe DeltaClause.Direction.COOLER
    }

    @Test
    fun `historic yesterday with a non-adjacent date is ignored`() {
        // An entry from two days ago — likely because the app didn't run
        // yesterday — must not be used; the user would read "vs two days ago"
        // as "vs yesterday". Fall back to bundle.yesterday (here, the bogus
        // upstream value), which is the same as the unguarded behaviour.
        val staleHistoric = DailyHistoryEntry(
            date = LocalDate.of(2026, 5, 22),
            feelsLikeMinC = 15.0,
            feelsLikeMaxC = 28.0,
        )

        val result = DeriveInsight()(snapshot(staleHistoric), prefs)

        result.insight.summary.delta.shouldNotBeNull()
        result.insight.summary.delta!!.degrees shouldBe 4
        result.insight.summary.delta!!.direction shouldBe DeltaClause.Direction.WARMER
    }

    @Test
    fun `historic yesterday absent falls through to bundle yesterday cleanly`() {
        val result = DeriveInsight()(snapshot(historic = null), prefs)
        result.insight.summary.delta.shouldNotBeNull()
        result.insight.summary.delta!!.degrees shouldBe 4
    }

    private val locatedEvent = CalendarEvent(
        title = "Concert",
        start = LocalDate.of(2026, 5, 24).atTime(14, 0),
        end = LocalDate.of(2026, 5, 24).atTime(15, 0),
        location = "Arena",
        allDay = false,
        kind = EventKind.NORMAL,
    )

    private fun snapshotWithEvents(events: List<CalendarEvent>): ForecastSnapshot = ForecastSnapshot(
        bundle = ForecastBundle(today = today, yesterday = bogusBundleYesterday),
        events = events,
        location = Location(latitude = 53.4054, longitude = -2.9924, displayName = "Liverpool"),
        period = ForecastPeriod.TODAY,
        generatedAt = Instant.parse("2026-05-24T07:00:00Z"),
        historicYesterday = null,
    )

    @Test
    fun `hasEvents is true when the calendar extras is active`() {
        val active = prefs.copy(calendarEnabled = true, useCalendarEvents = true)
        DeriveInsight()(snapshotWithEvents(listOf(locatedEvent)), active).insight.hasEvents shouldBe true
    }

    @Test
    fun `cached events are dropped when the calendar extras is off`() {
        // Simulates a cache hit / replay after the user turned the extras off:
        // the snapshot still holds an event captured while it was on, but the
        // re-derive must not resurface it in hasEvents (and so not in the prose
        // extras, the delivery gates, or the MQTT has_events flag either).
        val off = prefs.copy(calendarEnabled = true, useCalendarEvents = false)
        DeriveInsight()(snapshotWithEvents(listOf(locatedEvent)), off).insight.hasEvents shouldBe false
    }

    @Test
    fun `historic yesterday delta below threshold yields no clause`() {
        // Today 22 vs delivered yesterday 23 → only 1° cooler, under the 3°
        // default threshold. Clause must be null. Without the override the
        // upstream data (18.0 max) would trigger a +4 warmer clause — easy
        // failure mode if the override silently fell back to upstream when
        // its own delta is too small.
        val historic = DailyHistoryEntry(
            date = LocalDate.of(2026, 5, 23),
            feelsLikeMinC = 11.0,
            feelsLikeMaxC = 23.0,
        )

        val result = DeriveInsight()(snapshot(historic), prefs)

        result.insight.summary.delta.shouldBeNull()
    }
}
