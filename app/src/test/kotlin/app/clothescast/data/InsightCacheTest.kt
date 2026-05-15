package app.clothescast.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.clothescast.core.domain.model.AlertClause
import app.clothescast.core.domain.model.BandClause
import app.clothescast.core.domain.model.CalendarTieInClause
import app.clothescast.core.domain.model.ClothesClause
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.ConfidenceInfo
import app.clothescast.core.domain.model.DeltaClause
import app.clothescast.core.domain.model.ForecastConfidence
import app.clothescast.core.domain.model.PerModelHour
import app.clothescast.core.domain.model.PerModelHourly
import app.clothescast.core.domain.model.EveningEventTieInClause
import app.clothescast.core.domain.model.Fact
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.GarmentReason
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.OutfitRationale
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.PrecipClause
import app.clothescast.core.domain.model.PrecipLikelihood
import app.clothescast.core.domain.model.TemperatureBand
import app.clothescast.core.domain.model.WeatherCondition
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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

@OptIn(ExperimentalCoroutinesApi::class)
class InsightCacheTest {
    @TempDir lateinit var tempDir: Path

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var subject: InsightCache

    private val today = LocalDate.of(2026, 4, 25)
    private val now = Instant.parse("2026-04-25T07:00:00Z")

    private val sampleSummary = InsightSummary(
        period = ForecastPeriod.TODAY,
        band = BandClause(TemperatureBand.COOL, TemperatureBand.MILD),
        delta = DeltaClause(4, DeltaClause.Direction.COOLER),
        clothes = ClothesClause(listOf("sweater", "umbrella")),
    )

    private val sample = Insight(
        summary = sampleSummary,
        recommendedItems = listOf("sweater", "umbrella"),
        generatedAt = now,
        forDate = today,
        confidence = ConfidenceInfo(
            level = ForecastConfidence.LOW,
            tempSpreadC = 4.2,
            precipSpreadPp = 35.0,
            modelsConsulted = listOf("ecmwf_ifs04", "gfs_seamless", "icon_seamless"),
        ),
        perModelHourly = PerModelHourly(
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
                    PerModelHour(
                        time = LocalDateTime.of(today, LocalTime.of(1, 0)),
                        apparentTemperatureC = 11.5,
                        temperatureC = 13.5,
                        precipitationProbabilityPct = 15.0,
                        windSpeedKmh = 9.5,
                        relativeHumidityPct = 80.0,
                        cloudCoverPct = 70.0,
                    ),
                ),
                // Second model exercises the null-diagnostic-field path: the
                // upstream model run didn't return wind / humidity / cloud,
                // but temp + precip are present so the entry survives.
                "gfs_seamless" to listOf(
                    PerModelHour(LocalDateTime.of(today, LocalTime.of(0, 0)), 12.2, 14.2, 12.0),
                    PerModelHour(LocalDateTime.of(today, LocalTime.of(1, 0)), 11.8, 13.8, 18.0),
                ),
            ),
        ),
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
    fun `store then thisPeriod round-trips all fields`() = runTest {
        subject.store(InsightCache.Slot.THIS_PERIOD, sample)

        val read = subject.thisPeriod.first()
        read shouldBe sample
    }

    @Test
    fun `deliveredForToday returns the cached insight when forDate and period match`() = runTest {
        subject.store(InsightCache.Slot.THIS_PERIOD, sample)

        subject.deliveredForToday(today, sample.period) shouldBe sample
    }

    @Test
    fun `deliveredForToday returns null when the cached insight is for a different day`() = runTest {
        subject.store(InsightCache.Slot.THIS_PERIOD, sample)

        subject.deliveredForToday(today.plusDays(1), sample.period) shouldBe null
    }

    @Test
    fun `deliveredForToday returns null when the cached insight is for a different period`() = runTest {
        // The morning alarm fires for TODAY; if THIS_PERIOD holds a TONIGHT
        // insight (e.g. last night's evening delivery), the dedup check
        // misses and the worker refetches — fresh forecast instead of
        // redelivering yesterday's payload.
        subject.store(InsightCache.Slot.THIS_PERIOD, sample.copy(period = ForecastPeriod.TONIGHT))

        subject.deliveredForToday(today, ForecastPeriod.TODAY) shouldBe null
    }

    @Test
    fun `deliveredForToday ignores the NEXT_PERIOD slot`() = runTest {
        // The morning alarm shouldn't dedup against yesterday-evening's
        // pre-cached tomorrow-daytime insight in NEXT_PERIOD; otherwise it
        // would silently redeliver 12-hour-old forecast data.
        subject.store(InsightCache.Slot.NEXT_PERIOD, sample)

        subject.deliveredForToday(today, sample.period) shouldBe null
    }

    @Test
    fun `clear removes the stored insight`() = runTest {
        subject.store(InsightCache.Slot.THIS_PERIOD, sample)
        subject.clear()

        subject.thisPeriod.first() shouldBe null
    }

    @Test
    fun `corrupt JSON in the slot maps to null rather than crashing`() = runTest {
        dataStore.edit {
            it[stringPreferencesKey("this_period_insight_v6")] = "{not valid json"
        }

        subject.thisPeriod.first() shouldBe null
    }

    @Test
    fun `pre-v5 payloads are dropped rather than crashing the cache`() = runTest {
        // Older app versions stored `summary` as a rendered string (v2),
        // later as a structured object without `Fact.thresholdKind` (v3),
        // then with the kind tag (v4). The current schema (v5) replaces
        // `thresholdKind` with `ruleItem`, so older payloads no longer
        // deserialise and the slot drops to null. The next worker run
        // regenerates on the new key.
        val v2Json = """
            {
              "summary": "Cooler than yesterday — bring a sweater.",
              "recommendedItems": ["sweater", "umbrella"],
              "generatedAtEpochMillis": ${now.toEpochMilli()},
              "forDateEpochDays": ${today.toEpochDay()}
            }
        """.trimIndent()
        dataStore.edit {
            // v5 key, v2-shaped payload — the decoder fails on the `summary`
            // field and the runCatching in the cache returns null.
            it[stringPreferencesKey("this_period_insight_v6")] = v2Json
        }

        subject.thisPeriod.first() shouldBe null
    }

    @Test
    fun `hourly entries round-trip through the cache`() = runTest {
        val hourly = listOf(
            HourlyForecast(
                time = LocalTime.of(7, 0),
                temperatureC = 9.5,
                feelsLikeC = 7.2,
                precipitationProbabilityPct = 10.0,
                condition = WeatherCondition.PARTLY_CLOUDY,
            ),
            HourlyForecast(
                time = LocalTime.of(13, 0),
                temperatureC = 14.0,
                feelsLikeC = 13.0,
                precipitationProbabilityPct = 60.0,
                condition = WeatherCondition.RAIN,
            ),
        )
        val withHourly = sample.copy(hourly = hourly)

        subject.store(InsightCache.Slot.THIS_PERIOD, withHourly)

        subject.thisPeriod.first() shouldBe withHourly
    }

    @Test
    fun `outfit rationale round-trips through the cache`() = runTest {
        val rationale = OutfitRationale(
            top = GarmentReason(
                facts = listOf(
                    Fact(
                        metric = Fact.Metric.FEELS_LIKE_MIN,
                        observedC = 13.0,
                        observedAt = LocalTime.of(7, 0),
                        thresholdC = 18.0,
                        ruleItem = "sweater",
                        comparison = Fact.Comparison.BELOW,
                    ),
                ),
            ),
            bottom = GarmentReason(
                facts = listOf(
                    Fact(
                        metric = Fact.Metric.FEELS_LIKE_MAX,
                        observedC = 19.0,
                        observedAt = null,
                        thresholdC = 24.0,
                        ruleItem = "shorts",
                        comparison = Fact.Comparison.BELOW,
                    ),
                ),
            ),
        )
        val withRationale = sample.copy(
            outfit = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS),
            outfitRationale = rationale,
        )

        subject.store(InsightCache.Slot.THIS_PERIOD, withRationale)

        subject.thisPeriod.first() shouldBe withRationale
    }

    @Test
    fun `location round-trips through the cache`() = runTest {
        val withLocation = sample.copy(
            location = Location(
                latitude = 42.3601,
                longitude = -71.0589,
                displayName = "Boston",
            ),
        )

        subject.store(InsightCache.Slot.THIS_PERIOD, withLocation)

        subject.thisPeriod.first() shouldBe withLocation
    }

    @Test
    fun `payloads without optional fields decode with those fields null`() = runTest {
        // location, outfit, outfitRationale and confidence are all DTO-optional
        // with null defaults — a minimal v5 payload (e.g. an early build that
        // hasn't populated them yet, or a future field-pruning) still
        // deserialises rather than dropping to null and burning a regen on
        // next launch.
        val minimalJson = """
            {
              "summary": {
                "period": "TODAY",
                "band": {"low": "COOL", "high": "MILD"},
                "delta": {"degrees": 4, "direction": "COOLER"},
                "clothes": {"items": ["sweater", "umbrella"]}
              },
              "recommendedItems": ["sweater", "umbrella"],
              "generatedAtEpochMillis": ${now.toEpochMilli()},
              "forDateEpochDays": ${today.toEpochDay()}
            }
        """.trimIndent()
        dataStore.edit {
            it[stringPreferencesKey("this_period_insight_v6")] = minimalJson
        }

        val read = subject.thisPeriod.first()
        // Sample carries both `confidence` and `perModelHourly` so the
        // round-trip test catches a future regression where the DTO drops
        // either; the legacy/minimal payload here can't possibly carry them,
        // so strip them from the expected value.
        read shouldBe sample.copy(confidence = null, perModelHourly = null)
        read?.location shouldBe null
        read?.confidence shouldBe null
        read?.perModelHourly shouldBe null
    }

    @Test
    fun `every clause type round-trips through the cache`() = runTest {
        val full = sample.copy(
            summary = InsightSummary(
                period = ForecastPeriod.TODAY,
                band = BandClause(TemperatureBand.FREEZING, TemperatureBand.HOT),
                alert = AlertClause("Tornado Warning"),
                delta = DeltaClause(8, DeltaClause.Direction.WARMER),
                clothes = ClothesClause(listOf("sweater", "jacket", "shorts", "umbrella")),
                precip = PrecipClause(WeatherCondition.RAIN, LocalTime.of(15, 0)),
                calendarTieIn = CalendarTieInClause("umbrella"),
                eveningEventTieIn = EveningEventTieInClause(
                    items = listOf("jacket", "coat"),
                    rainTime = LocalTime.of(21, 0),
                ),
            ),
        )

        subject.store(InsightCache.Slot.THIS_PERIOD, full)

        subject.thisPeriod.first() shouldBe full
    }

    @Test
    fun `bare-rain evening tie-in round-trips through the cache`() = runTest {
        // The bare-rain emission shape: empty items (no clothes rule triggered),
        // rainTime set, POSSIBLE likelihood from a single-model rain signal.
        // The DTO has to preserve all three across a serde cycle — a regression
        // that nulled likelihood would silently downgrade every cached
        // bare-rain clause to LIKELY on read.
        val bareRain = sample.copy(
            summary = sample.summary.copy(
                eveningEventTieIn = EveningEventTieInClause(
                    items = emptyList(),
                    rainTime = LocalTime.of(21, 0),
                    likelihood = PrecipLikelihood.POSSIBLE,
                ),
            ),
        )

        subject.store(InsightCache.Slot.THIS_PERIOD, bareRain)

        val tie = subject.thisPeriod.first()?.summary?.eveningEventTieIn
        tie?.items shouldBe emptyList()
        tie?.rainTime shouldBe LocalTime.of(21, 0)
        tie?.likelihood shouldBe PrecipLikelihood.POSSIBLE
    }

    @Test
    fun `legacy single-item evening tie-in payload decodes into the items list`() = runTest {
        // Pre-multi-item payloads stored the chosen tie-in item in a singular
        // "item" field. The DTO keeps that field for read back-compat and
        // folds it into [items] on toDomain so a cached morning insight from
        // an older build doesn't lose its evening tie-in item on read.
        val legacyJson = """
            {
              "summary": {
                "period": "TODAY",
                "band": {"low": "COOL", "high": "MILD"},
                "eveningEventTieIn": {
                  "item": "jacket",
                  "rainSecondOfDay": 75600,
                  "likelihood": "LIKELY"
                }
              },
              "recommendedItems": [],
              "generatedAtEpochMillis": ${now.toEpochMilli()},
              "forDateEpochDays": ${today.toEpochDay()}
            }
        """.trimIndent()
        dataStore.edit {
            it[stringPreferencesKey("this_period_insight_v6")] = legacyJson
        }

        val tie = subject.thisPeriod.first()?.summary?.eveningEventTieIn
        tie?.items shouldBe listOf("jacket")
        tie?.rainTime shouldBe LocalTime.of(21, 0)
        tie?.likelihood shouldBe PrecipLikelihood.LIKELY
    }

    @Test
    fun `recomputeOutfits flips the cached fallback bottom to the new default`() = runTest {
        // Hourly never crosses the shorts (24°C) or jeans (no rule) thresholds,
        // so the outfit picker lands on the user's chosen defaultBottom.
        // Storing with LONG_PANTS, then recomputing with JEANS, should rewrite
        // the cached outfit's bottom — that's the Today screen / widget fix
        // for changing the picker after the day's insight has been generated.
        val hourly = listOf(
            HourlyForecast(
                time = LocalTime.of(7, 0),
                temperatureC = 14.0,
                feelsLikeC = 13.0,
                precipitationProbabilityPct = 5.0,
                condition = WeatherCondition.CLEAR,
            ),
            HourlyForecast(
                time = LocalTime.of(14, 0),
                temperatureC = 19.0,
                feelsLikeC = 18.0,
                precipitationProbabilityPct = 5.0,
                condition = WeatherCondition.CLEAR,
            ),
        )
        val stored = sample.copy(
            hourly = hourly,
            outfit = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS),
        )
        subject.store(InsightCache.Slot.THIS_PERIOD, stored)

        subject.recomputeOutfits(ClothesRule.DEFAULTS, OutfitSuggestion.Bottom.JEANS)

        subject.thisPeriod.first()?.outfit?.bottom shouldBe OutfitSuggestion.Bottom.JEANS
    }

    @Test
    fun `recomputeOutfits also flips the today slot's nextOutfit using tonight's hourly`() = runTest {
        // The "Today + Tonight" home-screen row reads both icons off the same
        // cached TODAY insight (today's `outfit` + that insight's `nextOutfit`),
        // not off the separate TONIGHT slot. Flipping the default-bottom picker
        // therefore has to recompute *both* fields against the new prefs, or
        // the user sees the today icon switch while the tonight icon next to it
        // stays on the old pick — the setting looks half-applied.
        val mildHourly = listOf(
            HourlyForecast(
                time = LocalTime.of(7, 0),
                temperatureC = 14.0,
                feelsLikeC = 13.0,
                precipitationProbabilityPct = 5.0,
                condition = WeatherCondition.CLEAR,
            ),
            HourlyForecast(
                time = LocalTime.of(14, 0),
                temperatureC = 19.0,
                feelsLikeC = 18.0,
                precipitationProbabilityPct = 5.0,
                condition = WeatherCondition.CLEAR,
            ),
        )
        val todayInsight = sample.copy(
            period = ForecastPeriod.TODAY,
            hourly = mildHourly,
            outfit = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS),
            nextOutfit = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS),
        )
        val tonightInsight = sample.copy(
            period = ForecastPeriod.TONIGHT,
            hourly = mildHourly,
            outfit = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS),
        )
        subject.store(InsightCache.Slot.THIS_PERIOD, todayInsight)
        subject.store(InsightCache.Slot.NEXT_PERIOD, tonightInsight)

        subject.recomputeOutfits(ClothesRule.DEFAULTS, OutfitSuggestion.Bottom.JEANS)

        val refreshedToday = subject.deliveredForToday(today, ForecastPeriod.TODAY)
        refreshedToday?.outfit?.bottom shouldBe OutfitSuggestion.Bottom.JEANS
        refreshedToday?.nextOutfit?.bottom shouldBe OutfitSuggestion.Bottom.JEANS
    }

    @Test
    fun `recomputeOutfits preserves todays nextOutfit when tonight slot is absent`() = runTest {
        // No TONIGHT slot cached → no paired hourly to recompute TODAY's
        // nextOutfit against. The existing nextOutfit is preserved verbatim
        // (the next worker run is what'll repair it) so we don't accidentally
        // drop the user's tomorrow preview while waiting on the worker.
        val mildHourly = listOf(
            HourlyForecast(
                time = LocalTime.of(7, 0),
                temperatureC = 14.0,
                feelsLikeC = 13.0,
                precipitationProbabilityPct = 5.0,
                condition = WeatherCondition.CLEAR,
            ),
        )
        val storedNext = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS)
        subject.store(
            InsightCache.Slot.THIS_PERIOD,
            sample.copy(
                period = ForecastPeriod.TODAY,
                hourly = mildHourly,
                outfit = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS),
                nextOutfit = storedNext,
            ),
        )

        subject.recomputeOutfits(ClothesRule.DEFAULTS, OutfitSuggestion.Bottom.JEANS)

        val refreshed = subject.deliveredForToday(today, ForecastPeriod.TODAY)
        refreshed?.outfit?.bottom shouldBe OutfitSuggestion.Bottom.JEANS
        refreshed?.nextOutfit shouldBe storedNext
    }

    @Test
    fun `recomputeOutfits preserves nextOutfit when NEXT_PERIOD slot has the same period kind as THIS_PERIOD`() = runTest {
        // A partial-write degraded state: the morning worker wrote
        // THIS_PERIOD (today daytime) but the paired NEXT_PERIOD write
        // failed, so NEXT_PERIOD still holds yesterday-evening's paired
        // write (also a daytime insight, ~12 h stale). Borrowing the
        // stale daytime hourly to rewrite today's `nextOutfit` (which
        // represents *tonight*) would mis-describe the upcoming window —
        // the opposite-period guard preserves the existing `nextOutfit`
        // until the next worker run repairs the pairing.
        val todayHourly = listOf(
            HourlyForecast(
                time = LocalTime.of(7, 0),
                temperatureC = 14.0,
                feelsLikeC = 13.0,
                precipitationProbabilityPct = 5.0,
                condition = WeatherCondition.CLEAR,
            ),
        )
        // Yesterday's stale daytime hourly — if we mis-pair this with
        // today's THIS_PERIOD slot's `nextOutfit` (which represents
        // *tonight*) we'd describe an evening's clothes off morning
        // weather data.
        val stalePairedHourly = listOf(
            HourlyForecast(
                time = LocalTime.of(12, 0),
                temperatureC = 2.0,
                feelsLikeC = 0.0,
                precipitationProbabilityPct = 5.0,
                condition = WeatherCondition.CLEAR,
            ),
        )
        val storedNext = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS)
        subject.store(
            InsightCache.Slot.THIS_PERIOD,
            sample.copy(
                period = ForecastPeriod.TODAY,
                forDate = today,
                hourly = todayHourly,
                outfit = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, OutfitSuggestion.Bottom.LONG_PANTS),
                nextOutfit = storedNext,
            ),
        )
        subject.store(
            InsightCache.Slot.NEXT_PERIOD,
            sample.copy(
                // Same period kind as THIS_PERIOD — partial-paired-write
                // signature. Should NOT be borrowed for THIS_PERIOD's
                // nextOutfit.
                period = ForecastPeriod.TODAY,
                forDate = today.minusDays(1),
                hourly = stalePairedHourly,
                outfit = OutfitSuggestion(OutfitSuggestion.Top.THICK_COAT, OutfitSuggestion.Bottom.LONG_PANTS),
            ),
        )

        subject.recomputeOutfits(ClothesRule.DEFAULTS, OutfitSuggestion.Bottom.JEANS)

        val refreshed = subject.deliveredForToday(today, ForecastPeriod.TODAY)
        refreshed?.outfit?.bottom shouldBe OutfitSuggestion.Bottom.JEANS
        // nextOutfit preserved verbatim — not silently rebuilt from the
        // same-period-kind partial-write leftover.
        refreshed?.nextOutfit shouldBe storedNext
    }

    @Test
    fun `recomputeOutfits leaves a slot without hourly data alone`() = runTest {
        // No hourly entries → no way to reconstruct the day's aggregates, so
        // the recompute can't re-evaluate rules. The slot's outfit stays as
        // last written and refreshes on the next worker run.
        val storedBottom = OutfitSuggestion.Bottom.LONG_PANTS
        subject.store(
            InsightCache.Slot.THIS_PERIOD,
            sample.copy(
                hourly = emptyList(),
                outfit = OutfitSuggestion(OutfitSuggestion.Top.SWEATER, storedBottom),
            ),
        )

        subject.recomputeOutfits(ClothesRule.DEFAULTS, OutfitSuggestion.Bottom.JEANS)

        subject.thisPeriod.first()?.outfit?.bottom shouldBe storedBottom
    }

    @Test
    fun `precip likelihood round-trips through the cache`() = runTest {
        val possible = sample.copy(
            summary = sample.summary.copy(
                precip = PrecipClause(
                    WeatherCondition.RAIN,
                    LocalTime.of(10, 0),
                    PrecipLikelihood.POSSIBLE,
                ),
            ),
        )

        subject.store(InsightCache.Slot.THIS_PERIOD, possible)

        subject.thisPeriod.first()?.summary?.precip?.likelihood shouldBe PrecipLikelihood.POSSIBLE
    }
}
