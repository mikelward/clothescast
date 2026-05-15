package app.clothescast.core.domain.repository

import app.clothescast.core.domain.model.DailyForecast
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.WeatherCondition
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class CachingWeatherRepositoryTest {

    private val london = Location(latitude = 51.5074, longitude = -0.1278, displayName = "London")

    private val today = DailyForecast(
        date = LocalDate.of(2026, 4, 25),
        temperatureMinC = 8.0,
        temperatureMaxC = 25.0,
        feelsLikeMinC = 6.0,
        feelsLikeMaxC = 25.0,
        precipitationProbabilityMaxPct = 60.0,
        precipitationMmTotal = 4.5,
        condition = WeatherCondition.RAIN,
    )

    private val yesterday = today.copy(
        date = LocalDate.of(2026, 4, 24),
        feelsLikeMaxC = 17.0,
    )

    private fun bundle() = ForecastBundle(today = today, yesterday = yesterday)

    private class CountingRepository(
        private val response: () -> ForecastBundle = { throw AssertionError("not stubbed") },
    ) : WeatherRepository {
        val callCount = AtomicInteger(0)
        var lastLocation: Location? = null
            private set

        override suspend fun fetchForecast(location: Location): ForecastBundle {
            callCount.incrementAndGet()
            lastLocation = location
            return response()
        }
    }

    private class MutableClock(initial: Instant) : Clock() {
        private var now: Instant = initial
        fun advance(duration: Duration) {
            now = now.plus(duration)
        }
        override fun instant(): Instant = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
    }

    @Test
    fun `first call hits the delegate`() = runTest {
        val delegate = CountingRepository(response = ::bundle)
        val subject = CachingWeatherRepository(
            delegate = delegate,
            clock = Clock.fixed(Instant.parse("2026-04-25T07:00:00Z"), ZoneOffset.UTC),
        )

        subject.fetchForecast(london)

        delegate.callCount.get() shouldBe 1
        delegate.lastLocation shouldBe london
    }

    @Test
    fun `second call within TTL returns cached bundle without hitting the delegate`() = runTest {
        val delegate = CountingRepository(response = ::bundle)
        val clock = MutableClock(Instant.parse("2026-04-25T07:00:00Z"))
        val subject = CachingWeatherRepository(delegate, clock = clock)

        val first = subject.fetchForecast(london)
        clock.advance(Duration.ofMinutes(59))
        val second = subject.fetchForecast(london)

        delegate.callCount.get() shouldBe 1
        second shouldBe first
    }

    @Test
    fun `call after TTL refetches`() = runTest {
        val delegate = CountingRepository(response = ::bundle)
        val clock = MutableClock(Instant.parse("2026-04-25T07:00:00Z"))
        val subject = CachingWeatherRepository(delegate, clock = clock)

        subject.fetchForecast(london)
        clock.advance(Duration.ofMinutes(61))
        subject.fetchForecast(london)

        delegate.callCount.get() shouldBe 2
    }

    @Test
    fun `tiny location jitter still hits cache`() = runTest {
        val delegate = CountingRepository(response = ::bundle)
        val clock = MutableClock(Instant.parse("2026-04-25T07:00:00Z"))
        val subject = CachingWeatherRepository(delegate, clock = clock)

        subject.fetchForecast(london)
        // ~10 m of lat/lon noise — same grid cell at the default 0.01° precision.
        subject.fetchForecast(london.copy(latitude = london.latitude + 0.0001))

        delegate.callCount.get() shouldBe 1
    }

    @Test
    fun `large location move misses cache`() = runTest {
        val delegate = CountingRepository(response = ::bundle)
        val clock = MutableClock(Instant.parse("2026-04-25T07:00:00Z"))
        val subject = CachingWeatherRepository(delegate, clock = clock)

        subject.fetchForecast(london)
        val paris = Location(latitude = 48.8566, longitude = 2.3522, displayName = "Paris")
        subject.fetchForecast(paris)

        delegate.callCount.get() shouldBe 2
    }

    @Test
    fun `bundle dated ahead of the device's local date still hits cache`() = runTest {
        // When the forecast location is east of the device (Open-Meteo returns
        // dates in the location's local zone via timezone=auto), the bundle's
        // today.date can legitimately be a day ahead of the device's local date.
        // The cache must not treat that as stale.
        val aheadBundle = ForecastBundle(
            today = today.copy(date = LocalDate.of(2026, 4, 26)),
            yesterday = today.copy(date = LocalDate.of(2026, 4, 25)),
        )
        val delegate = CountingRepository(response = { aheadBundle })
        val clock = MutableClock(Instant.parse("2026-04-25T13:00:00Z"))
        val subject = CachingWeatherRepository(delegate, clock = clock)

        subject.fetchForecast(london)
        clock.advance(Duration.ofMinutes(5))
        subject.fetchForecast(london)

        delegate.callCount.get() shouldBe 1
    }

    @Test
    fun `cache invalidated when local date rolls over even within TTL`() = runTest {
        val delegate = CountingRepository(response = ::bundle)
        // Fixture bundle's today.date = 2026-04-25. Start the clock at
        // 23:55 on the same UTC day so LocalDate.now(clock) matches.
        val clock = MutableClock(Instant.parse("2026-04-25T23:55:00Z"))
        val subject = CachingWeatherRepository(delegate, clock = clock)

        subject.fetchForecast(london)
        // 15 minutes later: still inside the 1 h TTL, but the UTC date has
        // rolled over to 2026-04-26 while the cached bundle's today.date
        // still says 2026-04-25 — must refetch.
        clock.advance(Duration.ofMinutes(15))
        subject.fetchForecast(london)

        delegate.callCount.get() shouldBe 2
    }

    @Test
    fun `cache invalidates when the freshness key changes`() = runTest {
        // Simulates the Forecasters-settings flow: a user changes the model
        // set mid-TTL and refreshes. The cache must miss, not silently return
        // the prior model set's bundle. The freshness key is opaque to the
        // cache — we use Set<String> here as a stand-in for the real
        // Set<ForecastModel> the app wires in.
        val delegate = CountingRepository(response = ::bundle)
        var currentKey: Set<String> = setOf("ecmwf", "gfs", "icon")
        val subject = CachingWeatherRepository(
            delegate = delegate,
            clock = Clock.fixed(Instant.parse("2026-04-25T07:00:00Z"), ZoneOffset.UTC),
            freshnessKeyProvider = { currentKey },
        )

        subject.fetchForecast(london)
        delegate.callCount.get() shouldBe 1

        // Identical key → cache hit.
        subject.fetchForecast(london)
        delegate.callCount.get() shouldBe 1

        // User swaps GFS for UKMO mid-TTL → cache must miss.
        currentKey = setOf("ecmwf", "ukmo", "icon")
        subject.fetchForecast(london)
        delegate.callCount.get() shouldBe 2

        // Subsequent fetch with the new key is cached again.
        subject.fetchForecast(london)
        delegate.callCount.get() shouldBe 2
    }

    @Test
    fun `freshness keys are compared by value not identity`() = runTest {
        // The provider returns a fresh Set instance on every call (typical
        // for a flow.first()-backed implementation). The cache must compare
        // by value or it would invalidate on every fetch and the TTL would
        // never bite.
        val delegate = CountingRepository(response = ::bundle)
        val subject = CachingWeatherRepository(
            delegate = delegate,
            clock = Clock.fixed(Instant.parse("2026-04-25T07:00:00Z"), ZoneOffset.UTC),
            freshnessKeyProvider = { setOf("ecmwf", "gfs", "icon") },
        )

        subject.fetchForecast(london)
        subject.fetchForecast(london)
        subject.fetchForecast(london)
        delegate.callCount.get() shouldBe 1
    }

    @Test
    fun `delegate errors propagate and the cache stays empty`() = runTest {
        val delegate = CountingRepository(response = { throw IOException("boom") })
        val clock = MutableClock(Instant.parse("2026-04-25T07:00:00Z"))
        val subject = CachingWeatherRepository(delegate, clock = clock)

        shouldThrow<IOException> { subject.fetchForecast(london) }

        // A retry that succeeds should populate the cache normally.
        val good = CountingRepository(response = ::bundle)
        val retry = CachingWeatherRepository(good, clock = clock)
        retry.fetchForecast(london)
        retry.fetchForecast(london)
        good.callCount.get() shouldBe 1
    }
}
