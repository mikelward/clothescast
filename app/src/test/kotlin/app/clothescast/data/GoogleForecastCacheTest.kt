package app.clothescast.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.PerModelHour
import app.clothescast.core.domain.model.WeatherCondition
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class GoogleForecastCacheTest {
    @TempDir lateinit var tempDir: Path

    private lateinit var dataStore: DataStore<Preferences>

    private val london = Location(latitude = 51.5074, longitude = -0.1278, displayName = "London")
    private val t0 = Instant.parse("2026-06-30T06:00:00Z")

    private val series = listOf(
        PerModelHour(
            time = LocalDateTime.of(2026, 6, 30, 7, 0),
            apparentTemperatureC = 16.0,
            temperatureC = 17.5,
            precipitationProbabilityPct = 40.0,
            precipitationMm = 0.8,
            windSpeedKmh = 12.0,
            relativeHumidityPct = 70.0,
            cloudCoverPct = 80.0,
            uvIndex = 2.0,
            condition = WeatherCondition.RAIN,
        ),
        // A next-day pre-dawn hour: exercises the per-entry date round-trip.
        PerModelHour(
            time = LocalDateTime.of(2026, 7, 1, 2, 0),
            apparentTemperatureC = 11.0,
            temperatureC = 12.0,
            precipitationProbabilityPct = null,
            condition = WeatherCondition.CLOUDY,
        ),
    )

    @BeforeEach
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(tempDir.toFile(), "google_forecast.preferences_pb") },
        )
    }

    private fun cacheAt(instant: Instant) =
        GoogleForecastCache(dataStore, clock = Clock.fixed(instant, ZoneOffset.UTC))

    // Fingerprint of the key that fetched the series; varied per test.
    private val keyA = 111
    private val keyB = 222

    @Test
    fun `fresh is null when nothing stored`() = runTest {
        cacheAt(t0).fresh(london, keyA) shouldBe null
    }

    @Test
    fun `put then fresh round-trips the series`() = runTest {
        cacheAt(t0).put(london, keyA, series)

        cacheAt(t0.plus(Duration.ofHours(1))).fresh(london, keyA) shouldBe series
    }

    @Test
    fun `fresh returns the series right up to the TTL boundary`() = runTest {
        cacheAt(t0).put(london, keyA, series)

        // 11 h old, inside the 12 h default window.
        cacheAt(t0.plus(Duration.ofHours(11))).fresh(london, keyA) shouldBe series
    }

    @Test
    fun `fresh is null once the entry is older than the TTL`() = runTest {
        cacheAt(t0).put(london, keyA, series)

        cacheAt(t0.plus(Duration.ofHours(13))).fresh(london, keyA) shouldBe null
    }

    @Test
    fun `fresh honors a caller-supplied max age`() = runTest {
        cacheAt(t0).put(london, keyA, series)

        cacheAt(t0.plus(Duration.ofHours(2))).fresh(london, keyA, maxAge = Duration.ofHours(1)) shouldBe null
    }

    @Test
    fun `small GPS jitter within the grid cell still hits`() = runTest {
        cacheAt(t0).put(london, keyA, series)

        // ~hundreds of metres — same ~1 km bucket.
        val jittered = london.copy(latitude = 51.5079, longitude = -0.1281)
        cacheAt(t0.plus(Duration.ofHours(1))).fresh(jittered, keyA) shouldBe series
    }

    @Test
    fun `a real move to a different cell misses`() = runTest {
        cacheAt(t0).put(london, keyA, series)

        val paris = Location(latitude = 48.8566, longitude = 2.3522, displayName = "Paris")
        cacheAt(t0.plus(Duration.ofHours(1))).fresh(paris, keyA) shouldBe null
    }

    @Test
    fun `a different key fingerprint misses so a swapped key re-fetches`() = runTest {
        // Series fetched with key A; the user has since swapped to key B (which
        // may 403). Reusing A's series would keep Google in the blend for a key
        // that can't authorize Weather, so this must miss.
        cacheAt(t0).put(london, keyA, series)

        cacheAt(t0.plus(Duration.ofHours(1))).fresh(london, keyB) shouldBe null
    }

    @Test
    fun `putting an empty series stores nothing`() = runTest {
        cacheAt(t0).put(london, keyA, emptyList())

        cacheAt(t0.plus(Duration.ofHours(1))).fresh(london, keyA) shouldBe null
    }

    // --- merge: retain the current day's already-elapsed hours on re-put ---

    /** Minimal hour on 2026-06-[day] at [hour]:00, just enough to round-trip. */
    private fun hr(day: Int, hour: Int, temp: Double = 15.0) = PerModelHour(
        time = LocalDateTime.of(2026, 6, day, hour, 0),
        apparentTemperatureC = temp,
        temperatureC = temp + 1.0,
        precipitationProbabilityPct = null,
    )

    @Test
    fun `put carries earlier same-day hours forward instead of overwriting them`() = runTest {
        // A morning walk captures 06:00–09:00 today.
        val morning = listOf(hr(30, 6), hr(30, 7), hr(30, 8), hr(30, 9))
        cacheAt(t0).put(london, keyA, morning)

        // A later walk returns only from 10:00 onward, as Google's endpoint does.
        val fromTen = listOf(hr(30, 10), hr(30, 11), hr(30, 12))
        cacheAt(t0.plus(Duration.ofHours(4))).put(london, keyA, fromTen)

        // The stored series still spans 06:00–12:00 rather than starting at 10:00.
        cacheAt(t0.plus(Duration.ofHours(5))).fresh(london, keyA) shouldBe (morning + fromTen)
    }

    @Test
    fun `a stale prior entry still backfills today's already-elapsed hours`() = runTest {
        // Yesterday evening's walk forecast into today, including this morning.
        // It's now older than the TTL, but its morning hours are the only Google
        // data we'll have for hours Google no longer returns.
        val priorWalk = listOf(hr(29, 21), hr(30, 6), hr(30, 7), hr(30, 8), hr(30, 9), hr(30, 10))
        cacheAt(t0.minus(Duration.ofHours(13))).put(london, keyA, priorWalk)

        // Today's first walk only reaches back to 10:00, with a fresher value there.
        val todayWalk = listOf(hr(30, 10, temp = 20.0), hr(30, 11), hr(30, 12))
        cacheAt(t0.plus(Duration.ofHours(4))).put(london, keyA, todayWalk)

        // Morning (06–09) from the prior walk; today's walk owns 10:00 onward
        // (its fresher 10:00 wins), and yesterday's 21:00 is not carried.
        cacheAt(t0.plus(Duration.ofHours(5))).fresh(london, keyA) shouldBe
            (listOf(hr(30, 6), hr(30, 7), hr(30, 8), hr(30, 9)) + todayWalk)
    }

    @Test
    fun `put does not carry forward hours from an earlier day`() = runTest {
        val yesterday = listOf(hr(29, 18), hr(29, 20), hr(29, 22))
        cacheAt(t0.minus(Duration.ofHours(12))).put(london, keyA, yesterday)

        val today = listOf(hr(30, 10), hr(30, 11))
        cacheAt(t0.plus(Duration.ofHours(4))).put(london, keyA, today)

        cacheAt(t0.plus(Duration.ofHours(5))).fresh(london, keyA) shouldBe today
    }

    @Test
    fun `put does not carry forward hours fetched under a different key`() = runTest {
        val morning = listOf(hr(30, 6), hr(30, 7))
        cacheAt(t0).put(london, keyA, morning)

        // The user swapped keys; keyB's entry must not inherit keyA's morning.
        val fromTen = listOf(hr(30, 10), hr(30, 11))
        cacheAt(t0.plus(Duration.ofHours(4))).put(london, keyB, fromTen)

        cacheAt(t0.plus(Duration.ofHours(5))).fresh(london, keyB) shouldBe fromTen
    }
}
