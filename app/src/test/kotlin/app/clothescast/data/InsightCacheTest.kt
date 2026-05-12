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
import app.clothescast.core.domain.model.ConfidenceInfo
import app.clothescast.core.domain.model.DeltaClause
import app.clothescast.core.domain.model.ForecastConfidence
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
    fun `latest is null when nothing stored`() = runTest {
        subject.latest.first() shouldBe null
    }

    @Test
    fun `store then latest round-trips all fields`() = runTest {
        subject.store(sample)

        val read = subject.latest.first()
        read shouldBe sample
    }

    @Test
    fun `forToday returns the cached insight when forDate matches`() = runTest {
        subject.store(sample)

        subject.forToday(today) shouldBe sample
    }

    @Test
    fun `forToday returns null when the cached insight is for a different day`() = runTest {
        subject.store(sample)

        subject.forToday(today.plusDays(1)) shouldBe null
    }

    @Test
    fun `clear removes the stored insight`() = runTest {
        subject.store(sample)
        subject.clear()

        subject.latest.first() shouldBe null
    }

    @Test
    fun `corrupt JSON in the slot maps to null rather than crashing`() = runTest {
        dataStore.edit {
            it[stringPreferencesKey("latest_insight_v5")] = "{not valid json"
        }

        subject.latest.first() shouldBe null
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
            it[stringPreferencesKey("latest_insight_v5")] = v2Json
        }

        subject.latest.first() shouldBe null
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

        subject.store(withHourly)

        subject.latest.first() shouldBe withHourly
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

        subject.store(withRationale)

        subject.latest.first() shouldBe withRationale
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

        subject.store(withLocation)

        subject.latest.first() shouldBe withLocation
    }

    @Test
    fun `payloads without optional fields decode with those fields null`() = runTest {
        // location, outfit and outfitRationale are all DTO-optional with null
        // defaults — a minimal v5 payload (e.g. an early build that hasn't
        // populated them yet, or a future field-pruning) still deserialises
        // rather than dropping to null and burning a regen on next launch.
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
            it[stringPreferencesKey("latest_insight_v5")] = minimalJson
        }

        val read = subject.latest.first()
        read shouldBe sample
        read?.location shouldBe null
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
                eveningEventTieIn = EveningEventTieInClause("jacket", rainTime = LocalTime.of(21, 0)),
            ),
        )

        subject.store(full)

        subject.latest.first() shouldBe full
    }
}
