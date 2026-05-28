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
    fun `tonight disabled always picks TODAY regardless of time`() {
        // The tonight slot is off, so refreshing it would never surface
        // anywhere — anchor to TODAY so the silent refresh updates the only
        // slot the user can see.
        val prefs = basePrefs.copy(tonightEnabled = false)
        FetchAndNotifyWorker.currentPeriodForSchedule(prefs, LocalTime.of(21, 0)) shouldBe ForecastPeriod.TODAY
        FetchAndNotifyWorker.currentPeriodForSchedule(prefs, LocalTime.of(2, 0)) shouldBe ForecastPeriod.TODAY
    }

    @Test
    fun `live manual refresh honours the requested period even when tonight is disabled`() {
        // A same-day evening Refresh tap is silent + forced and carries TONIGHT.
        // It must refresh TONIGHT even with tonight delivery off — otherwise
        // resolveRefreshPeriod falls through to currentPeriodForSchedule, which
        // forces TODAY there and leaves the Tonight card stale.
        val prefs = basePrefs.copy(tonightEnabled = false)
        FetchAndNotifyWorker.resolveRefreshPeriod(
            isSilent = true,
            isSameDayForced = true,
            requestedPeriod = ForecastPeriod.TONIGHT,
            prefs = prefs,
            now = LocalTime.of(21, 0),
        ) shouldBe ForecastPeriod.TONIGHT
    }

    @Test
    fun `live manual refresh honours a requested TODAY`() {
        FetchAndNotifyWorker.resolveRefreshPeriod(
            isSilent = true,
            isSameDayForced = true,
            requestedPeriod = ForecastPeriod.TODAY,
            prefs = basePrefs,
            now = LocalTime.of(21, 0),
        ) shouldBe ForecastPeriod.TODAY
    }

    @Test
    fun `stale manual refresh that crossed midnight falls back to the schedule`() {
        // A forced tap enqueued before midnight but run after the date rolled
        // over is no longer live (isSameDayForced = false). With tonight
        // disabled it must steer to TODAY via the schedule rather than caching
        // the stale, disabled TONIGHT slot it originally requested.
        val prefs = basePrefs.copy(tonightEnabled = false)
        FetchAndNotifyWorker.resolveRefreshPeriod(
            isSilent = true,
            isSameDayForced = false,
            requestedPeriod = ForecastPeriod.TONIGHT,
            prefs = prefs,
            now = LocalTime.of(21, 0),
        ) shouldBe ForecastPeriod.TODAY
    }

    @Test
    fun `opportunistic silent refresh ignores the requested period and follows the schedule`() {
        // App-open / onboarding refreshes aren't same-day-forced; they
        // auto-correct to the current schedule window and ignore any requested
        // period.
        FetchAndNotifyWorker.resolveRefreshPeriod(
            isSilent = true,
            isSameDayForced = false,
            requestedPeriod = ForecastPeriod.TODAY,
            prefs = basePrefs,
            now = LocalTime.of(21, 0),
        ) shouldBe ForecastPeriod.TONIGHT
    }

    @Test
    fun `scheduled alarm honours the requested period`() {
        // Alarms are neither silent nor force-flagged; the requested period
        // (set by AlarmReceiver) is taken verbatim.
        FetchAndNotifyWorker.resolveRefreshPeriod(
            isSilent = false,
            isSameDayForced = false,
            requestedPeriod = ForecastPeriod.TONIGHT,
            prefs = basePrefs,
            now = LocalTime.of(9, 0),
        ) shouldBe ForecastPeriod.TONIGHT
    }

    @Test
    fun `a run with no requested period defaults to TODAY`() {
        FetchAndNotifyWorker.resolveRefreshPeriod(
            isSilent = false,
            isSameDayForced = false,
            requestedPeriod = null,
            prefs = basePrefs,
            now = LocalTime.of(21, 0),
        ) shouldBe ForecastPeriod.TODAY
    }
}
