package app.clothescast.work

import app.clothescast.core.domain.model.ClothesFormat
import app.clothescast.core.domain.model.ClothesMentionMode
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.DistanceUnit
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.ForecastSnapshot
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.RangeFormat
import app.clothescast.core.domain.model.Schedule
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.model.WeatherCondition
import app.clothescast.core.domain.repository.ForecastBundle
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Drives the app-open opportunistic-refresh decision used by
 * [app.clothescast.MainActivity.onStart]. The predicate has to fail closed in
 * the no-cache case (nothing to silently replace) and only kick a fresh fetch
 * once the stored snapshot's age has crossed the documented threshold —
 * otherwise routine app re-opens would burn Open-Meteo calls (and the
 * Gemini-keyed TTS budget on a forced-refresh equivalent).
 */
class SilentRefreshFreshnessTest {
    private val now = Instant.parse("2026-04-25T09:00:00Z")
    private val today = LocalDate.of(2026, 4, 25)
    private val yesterday = today.minusDays(1)

    private fun dailyFor(date: LocalDate) = DailyForecast(
        date = date,
        temperatureMinC = 10.0,
        temperatureMaxC = 18.0,
        feelsLikeMinC = 9.0,
        feelsLikeMaxC = 17.0,
        precipitationProbabilityMaxPct = 0.0,
        precipitationMmTotal = 0.0,
        condition = WeatherCondition.CLEAR,
        hourly = emptyList(),
    )

    private fun snapshotGeneratedAt(generatedAt: Instant) = ForecastSnapshot(
        bundle = ForecastBundle(
            today = dailyFor(today),
            yesterday = dailyFor(yesterday),
            forecastZone = ZoneId.of("UTC"),
        ),
        events = emptyList(),
        location = Location(latitude = 51.5, longitude = -0.1, displayName = "London"),
        period = ForecastPeriod.TODAY,
        generatedAt = generatedAt,
    )

    @Test
    fun `null snapshot does not trigger a silent refresh`() {
        FetchAndNotifyWorker.shouldSilentlyRefresh(snapshot = null, now = now) shouldBe false
    }

    @Test
    fun `snapshot just under the staleness threshold stays put`() {
        val justBefore = now.minus(FetchAndNotifyWorker.SILENT_REFRESH_MIN_AGE).plusSeconds(1)
        FetchAndNotifyWorker.shouldSilentlyRefresh(snapshotGeneratedAt(justBefore), now) shouldBe false
    }

    @Test
    fun `snapshot exactly at the staleness threshold refreshes`() {
        val atThreshold = now.minus(FetchAndNotifyWorker.SILENT_REFRESH_MIN_AGE)
        FetchAndNotifyWorker.shouldSilentlyRefresh(snapshotGeneratedAt(atThreshold), now) shouldBe true
    }

    @Test
    fun `snapshot well past the staleness threshold refreshes`() {
        val yesterdayMorning = now.minusSeconds(24 * 3600)
        FetchAndNotifyWorker.shouldSilentlyRefresh(snapshotGeneratedAt(yesterdayMorning), now) shouldBe true
    }

    private val zone = ZoneId.of("UTC")
    private val basePrefs = UserPreferences(
        schedule = Schedule(time = LocalTime.of(7, 0), days = Schedule.EVERY_DAY, zoneId = zone),
        tonightSchedule = Schedule(time = LocalTime.of(19, 0), days = Schedule.EVERY_DAY, zoneId = zone),
        deliveryMode = DeliveryMode.NOTIFICATION_ONLY,
        temperatureUnit = TemperatureUnit.CELSIUS,
        distanceUnit = DistanceUnit.KILOMETERS,
        clothesRules = ClothesRule.DEFAULTS,
        defaultBottom = OutfitSuggestion.Bottom.LONG_PANTS,
        defaultTop = OutfitSuggestion.Top.TSHIRT,
        clothesMentionMode = ClothesMentionMode.ALWAYS,
        rangeFormat = RangeFormat.DEGREES,
        clothesFormat = ClothesFormat.ITEMS,
        deltaThresholdC = 3.0,
        tonightEnabled = true,
    )

    @Test
    fun `morning inside daytime window picks TODAY`() {
        val prefs = basePrefs
        FetchAndNotifyWorker.currentPeriodForSchedule(prefs, LocalTime.of(9, 0)) shouldBe ForecastPeriod.TODAY
    }

    @Test
    fun `evening inside tonight window picks TONIGHT`() {
        val prefs = basePrefs
        FetchAndNotifyWorker.currentPeriodForSchedule(prefs, LocalTime.of(21, 0)) shouldBe ForecastPeriod.TONIGHT
    }

    @Test
    fun `after midnight before morning still picks TONIGHT`() {
        // The tonight window wraps midnight (19:00 inclusive → 07:00 exclusive)
        // — 02:00 lands inside it, so the cached snapshot stays in the tonight
        // slot until the morning alarm fires.
        val prefs = basePrefs
        FetchAndNotifyWorker.currentPeriodForSchedule(prefs, LocalTime.of(2, 0)) shouldBe ForecastPeriod.TONIGHT
    }

    @Test
    fun `tonight disabled still follows wall-clock, not forced to TODAY`() {
        // The enable toggles gate scheduled delivery, not refresh: the Today
        // screen shows both windows from the cache regardless, so a silent
        // refresh in the evening must still land on TONIGHT even when tonight
        // delivery is off. (Regression: the old short-circuit anchored to TODAY
        // here, leaving the Tonight card stale.)
        val prefs = basePrefs.copy(tonightEnabled = false)
        FetchAndNotifyWorker.currentPeriodForSchedule(prefs, LocalTime.of(21, 0)) shouldBe ForecastPeriod.TONIGHT
        FetchAndNotifyWorker.currentPeriodForSchedule(prefs, LocalTime.of(2, 0)) shouldBe ForecastPeriod.TONIGHT
        FetchAndNotifyWorker.currentPeriodForSchedule(prefs, LocalTime.of(9, 0)) shouldBe ForecastPeriod.TODAY
    }
}
