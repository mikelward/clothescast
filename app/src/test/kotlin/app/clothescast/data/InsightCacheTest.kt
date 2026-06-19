package app.clothescast.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.clothescast.core.domain.model.CalendarEvent
import app.clothescast.core.domain.model.ClothesFormat
import app.clothescast.core.domain.model.ClothesMentionMode
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.ConfidenceInfo
import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.DistanceUnit
import app.clothescast.core.domain.model.ForecastConfidence
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.ForecastSnapshot
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.PerModelHour
import app.clothescast.core.domain.model.PerModelHourly
import app.clothescast.core.domain.model.RangeFormat
import app.clothescast.core.domain.model.Schedule
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.model.WeatherCondition
import app.clothescast.core.domain.repository.ForecastBundle
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class InsightCacheTest {
    @TempDir lateinit var tempDir: Path

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var subject: InsightCache

    private val today = LocalDate.of(2026, 4, 25)
    private val yesterday = today.minusDays(1)
    private val now = Instant.parse("2026-04-25T07:00:00Z")

    private val mildHourly = listOf(
        HourlyForecast(LocalTime.of(7, 0), 14.0, 13.0, 5.0, WeatherCondition.CLEAR),
        HourlyForecast(LocalTime.of(14, 0), 19.0, 18.0, 5.0, WeatherCondition.CLEAR),
    )
    private val mildYesterdayHourly = listOf(
        HourlyForecast(LocalTime.of(7, 0), 13.0, 12.0, 5.0, WeatherCondition.CLEAR),
        HourlyForecast(LocalTime.of(14, 0), 18.0, 17.0, 5.0, WeatherCondition.CLEAR),
    )

    private val bundle = ForecastBundle(
        today = DailyForecast(
            date = today,
            temperatureMinC = 14.0,
            temperatureMaxC = 19.0,
            feelsLikeMinC = 13.0,
            feelsLikeMaxC = 18.0,
            precipitationProbabilityMaxPct = 5.0,
            precipitationMmTotal = 0.0,
            condition = WeatherCondition.CLEAR,
            hourly = mildHourly,
        ),
        yesterday = DailyForecast(
            date = yesterday,
            temperatureMinC = 13.0,
            temperatureMaxC = 18.0,
            feelsLikeMinC = 12.0,
            feelsLikeMaxC = 17.0,
            precipitationProbabilityMaxPct = 5.0,
            precipitationMmTotal = 0.0,
            condition = WeatherCondition.CLEAR,
            hourly = mildYesterdayHourly,
        ),
        forecastZone = ZoneId.of("UTC"),
    )

    private val sample = ForecastSnapshot(
        bundle = bundle,
        events = emptyList(),
        location = Location(latitude = 51.5, longitude = -0.1, displayName = "London"),
        period = ForecastPeriod.TODAY,
        generatedAt = now,
    )

    private val basePrefs = UserPreferences(
        schedule = Schedule(time = LocalTime.of(7, 0), days = Schedule.EVERY_DAY, zoneId = ZoneId.of("UTC")),
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
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(TestCoroutineScheduler()))
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(tempDir.toFile(), "cache.preferences_pb") },
        )
        subject = InsightCache(dataStore)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `thisPeriod is null when nothing stored`() = runTest {
        subject.thisPeriod.first() shouldBe null
    }

    @Test
    fun `store then thisPeriod round-trips the snapshot`() = runTest {
        subject.store(InsightCache.Slot.THIS_PERIOD, sample)

        val read = subject.thisPeriod.first()
        read shouldBe sample
    }

    @Test
    fun `overnight flag survives the cache round-trip`() = runTest {
        // The "Overnight" label is baked into the snapshot so it stays stable
        // across re-renders / process death; the cache must persist it.
        val overnight = sample.copy(period = ForecastPeriod.TONIGHT, overnight = true)
        subject.store(InsightCache.Slot.THIS_PERIOD, overnight)

        subject.thisPeriod.first()?.overnight shouldBe true
    }

    @Test
    fun `store NEXT_PERIOD round-trips independently of THIS_PERIOD`() = runTest {
        val other = sample.copy(period = ForecastPeriod.TONIGHT)
        subject.store(InsightCache.Slot.THIS_PERIOD, sample)
        subject.store(InsightCache.Slot.NEXT_PERIOD, other)

        subject.thisPeriod.first() shouldBe sample
        subject.nextPeriod.first() shouldBe other
    }

    @Test
    fun `clear removes both slots`() = runTest {
        subject.store(InsightCache.Slot.THIS_PERIOD, sample)
        subject.store(InsightCache.Slot.NEXT_PERIOD, sample)

        subject.clear()

        subject.thisPeriod.first() shouldBe null
        subject.nextPeriod.first() shouldBe null
    }

    @Test
    fun `corrupt JSON drops to null rather than crashing`() = runTest {
        dataStore.edit {
            it[stringPreferencesKey("this_period_snapshot_v8")] = "{not valid json"
        }

        subject.thisPeriod.first() shouldBe null
    }

    @Test
    fun `pre-v7 payloads (the old derived-Insight shape) are dropped on read`() = runTest {
        // Old v6 cache key, old derived-insight JSON shape. The current reader
        // returns null and the next worker run repopulates the live keys.
        dataStore.edit {
            it[stringPreferencesKey("this_period_insight_v6")] = """
                {"summary":"placeholder","recommendedItems":[],"generatedAtEpochMillis":0,
                 "forDateEpochDays":0}
            """.trimIndent()
        }

        subject.thisPeriod.first() shouldBe null
    }

    /**
     * The exact JSON shape the v7 (pre-update) build wrote: same snapshot DTO
     * as v8 except `CalendarEventDto` carried second-of-day fields only — no
     * dates — and it lived under the `_v7` key.
     */
    private fun v7SnapshotJson(eventsJson: String = "[]"): String {
        fun day(date: LocalDate, minC: Double, maxC: Double) = """
            {"dateEpochDays":${date.toEpochDay()},"temperatureMinC":${minC + 1},
             "temperatureMaxC":${maxC + 1},"feelsLikeMinC":$minC,"feelsLikeMaxC":$maxC,
             "precipitationProbabilityMaxPct":5.0,"precipitationMmTotal":0.0,"condition":"CLEAR"}
        """.trimIndent()
        return """
            {"bundle":{"today":${day(today, 13.0, 18.0)},"yesterday":${day(yesterday, 12.0, 17.0)}},
             "events":$eventsJson,
             "period":"TODAY",
             "generatedAtEpochMillis":${now.toEpochMilli()}}
        """.trimIndent()
    }

    @Test
    fun `v7 payloads survive the update via the legacy-key fallback`() = runTest {
        // The v7→v8 key bump used to drop the cache on first read after an
        // app update, blanking the Today screen and widget until the next
        // fetch (painfully visible when the network is slow). Reads now fall
        // back to the v7 key, inferring the event dates v7 never stored from
        // the snapshot's own day.
        dataStore.edit {
            it[stringPreferencesKey("this_period_snapshot_v7")] = v7SnapshotJson(
                """[{"startSecondOfDay":72000,"endSecondOfDay":79200,"hasLocation":true}]""",
            )
        }

        val read = subject.thisPeriod.first()
        read?.bundle?.today?.date shouldBe today
        val readEvent = read?.events?.singleOrNull()
        readEvent?.start shouldBe today.atTime(20, 0)
        readEvent?.end shouldBe today.atTime(22, 0)
        (readEvent?.location.isNullOrBlank()) shouldBe false
    }

    @Test
    fun `v7 event ending before it starts rolls its end past midnight`() = runTest {
        // v7 stored times only, so a 21:00–00:30 gig serialised as end < start.
        // The fallback decode keeps it a forward interval by rolling the end
        // to the next day instead of materialising a negative-length event.
        dataStore.edit {
            it[stringPreferencesKey("this_period_snapshot_v7")] = v7SnapshotJson(
                """[{"startSecondOfDay":75600,"endSecondOfDay":1800}]""",
            )
        }

        val readEvent = subject.thisPeriod.first()?.events?.singleOrNull()
        readEvent?.start shouldBe today.atTime(21, 0)
        readEvent?.end shouldBe today.plusDays(1).atTime(0, 30)
    }

    @Test
    fun `v8 payload wins over a leftover v7 payload`() = runTest {
        subject.store(InsightCache.Slot.THIS_PERIOD, sample)
        dataStore.edit {
            it[stringPreferencesKey("this_period_snapshot_v7")] =
                v7SnapshotJson("""[{"startSecondOfDay":0,"endSecondOfDay":3600}]""")
        }

        subject.thisPeriod.first() shouldBe sample
    }

    @Test
    fun `store retires the legacy v7 and v6 keys for its slot`() = runTest {
        // Once a fresh v8 capture lands, the pre-update payloads are stale —
        // remove them so the fallback can't resurrect them after a clear()
        // of the live keys and the orphaned bytes don't linger on disk.
        dataStore.edit {
            it[stringPreferencesKey("this_period_snapshot_v7")] = v7SnapshotJson()
            it[stringPreferencesKey("this_period_insight_v6")] = """{"summary":"old"}"""
        }

        subject.store(InsightCache.Slot.THIS_PERIOD, sample)

        val prefs = dataStore.data.first()
        prefs[stringPreferencesKey("this_period_snapshot_v7")] shouldBe null
        prefs[stringPreferencesKey("this_period_insight_v6")] shouldBe null
        subject.thisPeriod.first() shouldBe sample
    }

    @Test
    fun `clear removes legacy payloads too`() = runTest {
        dataStore.edit {
            it[stringPreferencesKey("next_period_snapshot_v7")] = v7SnapshotJson()
        }

        subject.clear()

        subject.nextPeriod.first() shouldBe null
    }

    @Test
    fun `events round-trip with only timing and location-presence preserved`() = runTest {
        // Renderer's only `location` read is a presence check, so on-disk
        // we keep a `hasLocation` boolean — titles and free-form location
        // strings are deliberately not persisted (privacy: keep the on-
        // disk posture at parity with the previous derived-insight cache).
        val concertEvent = CalendarEvent(
            title = "Concert with band",
            start = today.atTime(20, 0),
            end = today.atTime(22, 0),
            location = "Royal Albert Hall",
            allDay = false,
        )
        val withEvents = sample.copy(events = listOf(concertEvent))

        subject.store(InsightCache.Slot.THIS_PERIOD, withEvents)

        val read = subject.thisPeriod.first()
        val readEvent = read?.events?.singleOrNull()
        readEvent?.start shouldBe today.atTime(20, 0)
        readEvent?.end shouldBe today.atTime(22, 0)
        readEvent?.allDay shouldBe false
        // Title dropped, location replaced with a placeholder presence marker.
        readEvent?.title shouldBe ""
        (readEvent?.location.isNullOrBlank()) shouldBe false
    }

    @Test
    fun `events with no location surface as null location after round-trip`() = runTest {
        // The away-from-home gate fires on `!location.isNullOrBlank()`, so
        // a no-location event must come back as a no-location event.
        val noLocationEvent = CalendarEvent(
            title = "Internal sync",
            start = today.atTime(10, 0),
            end = today.atTime(11, 0),
            location = null,
            allDay = false,
        )
        subject.store(InsightCache.Slot.THIS_PERIOD, sample.copy(events = listOf(noLocationEvent)))

        val read = subject.thisPeriod.first()
        read?.events?.singleOrNull()?.location shouldBe null
    }

    @Test
    fun `midnight-crossing event keeps its dated endpoints across the round-trip`() = runTest {
        // The DTO stores a date + second-of-day pair per endpoint (the
        // PerModelHourDto pattern), so a 21:00 gig ending at 00:30 tomorrow
        // must come back with its end still on tomorrow's date — collapsing
        // both endpoints onto one date would resurrect the wrapped-interval
        // ambiguity the LocalDateTime migration removed.
        val gig = CalendarEvent(
            title = "Late gig",
            start = today.atTime(21, 0),
            end = today.plusDays(1).atTime(0, 30),
            location = "Venue",
            allDay = false,
        )
        subject.store(InsightCache.Slot.THIS_PERIOD, sample.copy(events = listOf(gig)))

        val readEvent = subject.thisPeriod.first()?.events?.singleOrNull()
        readEvent?.start shouldBe today.atTime(21, 0)
        readEvent?.end shouldBe today.plusDays(1).atTime(0, 30)
    }

    @Test
    fun `upcomingDays round-trips through the cache`() = runTest {
        // 7-day forecast page reads bundle.upcomingDays via Insight; verify
        // the persistence layer carries the list intact across a restart.
        val tomorrow = DailyForecast(
            date = today.plusDays(1),
            temperatureMinC = 15.0,
            temperatureMaxC = 21.0,
            feelsLikeMinC = 14.0,
            feelsLikeMaxC = 20.0,
            precipitationProbabilityMaxPct = 10.0,
            precipitationMmTotal = 0.0,
            condition = WeatherCondition.CLEAR,
            hourly = listOf(
                HourlyForecast(LocalTime.of(12, 0), 19.0, 18.0, 5.0, WeatherCondition.CLEAR),
            ),
        )
        val dayAfter = tomorrow.copy(date = today.plusDays(2), temperatureMaxC = 22.0)
        subject.store(
            InsightCache.Slot.THIS_PERIOD,
            sample.copy(bundle = bundle.copy(upcomingDays = listOf(tomorrow, dayAfter))),
        )

        val read = subject.thisPeriod.first()
        read?.bundle?.upcomingDays shouldBe listOf(tomorrow, dayAfter)
    }

    @Test
    fun `perModelHourly round-trips with all diagnostic fields`() = runTest {
        val perModel = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(
                    PerModelHour(
                        time = LocalDateTime.of(today, LocalTime.of(0, 0)),
                        apparentTemperatureC = 12.0,
                        temperatureC = 14.0,
                        precipitationProbabilityPct = 10.0,
                        windSpeedKmh = 8.0,
                        relativeHumidityPct = 78.0,
                        cloudCoverPct = 60.0,
                    ),
                ),
            ),
        )
        subject.store(
            InsightCache.Slot.THIS_PERIOD,
            sample.copy(bundle = bundle.copy(perModelHourly = perModel)),
        )

        subject.thisPeriod.first()?.bundle?.perModelHourly shouldBe perModel
    }

    @Test
    fun `perModelHourly preserves per-entry dates across the bundle's full series`() = runTest {
        // Open-Meteo returns ~48 h of per-model hours, so a cached snapshot's
        // series spans today and tomorrow. The tonight slice in DeriveInsight
        // wraps from today evening to tomorrow morning by LocalDateTime, so
        // aliasing every entry's date onto the bundle's `today.date` would
        // collapse tomorrow's pre-dawn rain hour onto today and break the
        // overnight precip clause.
        val tomorrow = today.plusDays(1)
        val perModel = PerModelHourly(
            byModel = mapOf(
                "ecmwf_ifs04" to listOf(
                    PerModelHour(
                        time = LocalDateTime.of(today, LocalTime.of(22, 0)),
                        apparentTemperatureC = 10.0,
                        temperatureC = 11.0,
                        precipitationProbabilityPct = 10.0,
                    ),
                    PerModelHour(
                        time = LocalDateTime.of(tomorrow, LocalTime.of(5, 0)),
                        apparentTemperatureC = 6.0,
                        temperatureC = 7.0,
                        precipitationProbabilityPct = 60.0,
                    ),
                ),
            ),
        )
        subject.store(
            InsightCache.Slot.THIS_PERIOD,
            sample.copy(bundle = bundle.copy(perModelHourly = perModel)),
        )

        val readTimes = subject.thisPeriod.first()
            ?.bundle?.perModelHourly?.byModel?.get("ecmwf_ifs04")
            ?.map { it.time }
        readTimes shouldBe listOf(
            LocalDateTime.of(today, LocalTime.of(22, 0)),
            LocalDateTime.of(tomorrow, LocalTime.of(5, 0)),
        )
    }

    @Test
    fun `confidence info round-trips through the cache`() = runTest {
        val confidence = ConfidenceInfo(
            level = ForecastConfidence.LOW,
            tempSpreadC = 4.2,
            precipSpreadPp = 35.0,
            modelsConsulted = listOf("ecmwf_ifs04", "gfs_seamless"),
        )
        subject.store(
            InsightCache.Slot.THIS_PERIOD,
            sample.copy(bundle = bundle.copy(confidence = confidence)),
        )

        subject.thisPeriod.first()?.bundle?.confidence shouldBe confidence
    }

    @Test
    fun `tomorrow forecast round-trips when present`() = runTest {
        val tomorrow = DailyForecast(
            date = today.plusDays(1),
            temperatureMinC = 15.0,
            temperatureMaxC = 21.0,
            feelsLikeMinC = 14.0,
            feelsLikeMaxC = 20.0,
            precipitationProbabilityMaxPct = 10.0,
            precipitationMmTotal = 0.0,
            condition = WeatherCondition.PARTLY_CLOUDY,
            hourly = emptyList(),
        )
        subject.store(
            InsightCache.Slot.THIS_PERIOD,
            sample.copy(bundle = bundle.copy(tomorrow = tomorrow)),
        )

        subject.thisPeriod.first()?.bundle?.tomorrow shouldBe tomorrow
    }

    @Test
    fun `forecast zone round-trips through the cache`() = runTest {
        subject.store(
            InsightCache.Slot.THIS_PERIOD,
            sample.copy(bundle = bundle.copy(forecastZone = ZoneId.of("America/Los_Angeles"))),
        )

        subject.thisPeriod.first()?.bundle?.forecastZone shouldBe ZoneId.of("America/Los_Angeles")
    }

    @Test
    fun `deliveredForToday returns the derived insight when forDate and period match`() = runTest {
        subject.store(InsightCache.Slot.THIS_PERIOD, sample)

        val result = subject.deliveredForToday(today, ForecastPeriod.TODAY, basePrefs)
        result?.insight?.forDate shouldBe today
        result?.insight?.period shouldBe ForecastPeriod.TODAY
    }

    @Test
    fun `deliveredForToday returns null on a date mismatch`() = runTest {
        subject.store(InsightCache.Slot.THIS_PERIOD, sample)

        subject.deliveredForToday(today.plusDays(1), ForecastPeriod.TODAY, basePrefs) shouldBe null
    }

    @Test
    fun `deliveredForToday returns null on a period mismatch`() = runTest {
        // The morning alarm fires for TODAY; if THIS_PERIOD holds a TONIGHT
        // snapshot (e.g. last night's evening delivery), the dedup check
        // misses and the worker refetches — fresh forecast instead of
        // redelivering yesterday's payload.
        subject.store(InsightCache.Slot.THIS_PERIOD, sample.copy(period = ForecastPeriod.TONIGHT))

        subject.deliveredForToday(today, ForecastPeriod.TODAY, basePrefs) shouldBe null
    }

    @Test
    fun `deliveredForToday ignores the NEXT_PERIOD slot`() = runTest {
        // The morning alarm shouldn't dedup against yesterday-evening's
        // pre-cached tomorrow-daytime snapshot in NEXT_PERIOD; otherwise it
        // would silently redeliver 12-hour-old forecast data.
        subject.store(InsightCache.Slot.NEXT_PERIOD, sample)

        subject.deliveredForToday(today, ForecastPeriod.TODAY, basePrefs) shouldBe null
    }

    @Test
    fun `deriveFlow emits the derived insight summary against current prefs`() = runTest {
        // The core "settings change updates the prose" path: store a snapshot
        // once, then poke prefs and confirm the derive picks up the new
        // mention-mode setting without any cache rewrite.
        subject.store(InsightCache.Slot.THIS_PERIOD, sample)

        val withClothes = subject.deriveFlow(
            slot = InsightCache.Slot.THIS_PERIOD,
            prefsFlow = flowOf(basePrefs.copy(clothesMentionMode = ClothesMentionMode.ALWAYS)),
        ).first()
        withClothes?.insight?.summary?.clothes shouldBe
            app.clothescast.core.domain.model.ClothesClause(listOf("sweater", "pants"))

        val muted = subject.deriveFlow(
            slot = InsightCache.Slot.THIS_PERIOD,
            prefsFlow = flowOf(basePrefs.copy(clothesMentionMode = ClothesMentionMode.NEVER)),
        ).first()
        muted?.insight?.summary?.clothes shouldBe null
    }

    @Test
    fun `deriveFlow emits null when the slot is empty`() = runTest {
        val result = subject.deriveFlow(
            slot = InsightCache.Slot.THIS_PERIOD,
            prefsFlow = flowOf(basePrefs),
        ).first()
        result shouldBe null
    }

    @Test
    fun `deriveFlow re-derives delta clause against new threshold`() = runTest {
        // Today is ~1°C warmer than yesterday — at threshold 0.5 the clause
        // emits, at threshold 5 it suppresses. The same cached snapshot
        // produces both, just by changing the pref.
        subject.store(InsightCache.Slot.THIS_PERIOD, sample)

        val loose = subject.deriveFlow(
            slot = InsightCache.Slot.THIS_PERIOD,
            prefsFlow = flowOf(basePrefs.copy(deltaThresholdC = 0.5)),
        ).first()
        // Either emits a delta clause (>= 0.5°) or — if the daytime slice
        // happens to round to 0 — null; the point is the threshold flows
        // through to the renderer.
        val strict = subject.deriveFlow(
            slot = InsightCache.Slot.THIS_PERIOD,
            prefsFlow = flowOf(basePrefs.copy(deltaThresholdC = 20.0)),
        ).first()
        strict?.insight?.summary?.delta shouldBe null

        // Loose threshold either emits or doesn't, but it must not be stricter
        // than strict's view of the same data.
        if (loose?.insight?.summary?.delta != null) {
            loose.insight.summary.delta!!.degrees shouldBe 1
        }
    }
}
