package app.clothescast.core.domain.repository

import app.clothescast.core.domain.model.Location
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToLong

/**
 * Decorates a [WeatherRepository] with a single-entry in-memory cache so that
 * manual refreshes (and other near-simultaneous fetches) don't hammer the
 * Open-Meteo endpoint. Open-Meteo's grid is several km and the forecast it
 * returns doesn't materially change within an hour, so a 1 h TTL is plenty
 * fresh for the Today screen and the daily insight worker.
 *
 * Cache key is the [Location] rounded to [locationGridDegrees] in each
 * dimension — at the default 0.01° (~1 km at the equator), GPS jitter and
 * walking around the same neighbourhood reuse the cached forecast, while a
 * real move (different suburb, a drive across town) misses and triggers a
 * fresh fetch. No separate location TTL is needed; staleness in the lat/lon
 * sense falls out of the rounded key.
 *
 * Errors from the delegate propagate; the cache is only written on success.
 *
 * TODO: alerts ride along with the cached bundle, so a severe warning that
 * Open-Meteo issues mid-TTL is invisible to the worker until the next
 * miss. Acceptable for now (the alerts feed is best-effort and the worker
 * runs at least twice a day) but if alert latency starts mattering, give
 * alerts a shorter TTL or split them out of `WeatherRepository.fetchForecast`
 * onto their own method so the caching decorator can leave them alone.
 */
class CachingWeatherRepository(
    private val delegate: WeatherRepository,
    // Default to the device's local zone so the date-rollover check below
    // compares against the same wall-clock day the user — and the insight
    // worker, which stamps `Insight.forDate` from the device's local date —
    // is looking at.
    private val clock: Clock = Clock.systemDefaultZone(),
    private val ttl: Duration = Duration.ofHours(1),
    private val locationGridDegrees: Double = 0.01,
    /**
     * Pull-based opaque discriminator for inputs *other than location* that
     * change which forecast the delegate would return — currently the user's
     * Forecasters model selection (see [ClothesCastApplication]). The cache
     * stores whatever value this returned at fetch time and treats the entry
     * as stale on mismatch, so a settings change takes effect on the next
     * refresh instead of waiting out the TTL. Default returns [Unit] (a
     * single value that matches itself), preserving the location-only
     * behaviour for tests and any delegate whose output doesn't depend on
     * out-of-band state.
     *
     * Suspend so a settings-backed provider can `dataStore.first()` without
     * needing a separately-maintained snapshot. DataStore caches the latest
     * emission in memory, so on the warm path this is a memory hit.
     */
    private val freshnessKeyProvider: suspend () -> Any = { Unit },
) : WeatherRepository {

    private data class Entry(
        val key: LocationKey,
        val freshnessKey: Any,
        val fetchedAt: Instant,
        val bundle: ForecastBundle,
    )

    private data class LocationKey(val latBucket: Long, val lonBucket: Long)

    private val mutex = Mutex()
    private var entry: Entry? = null

    override suspend fun fetchForecast(location: Location): ForecastBundle = mutex.withLock {
        val key = keyOf(location)
        val freshnessKey = freshnessKeyProvider()
        val now = clock.instant()
        val today = LocalDate.now(clock)
        val cached = entry
        // Date check uses `isBefore` rather than `==` because Open-Meteo
        // returns dates in the forecast location's local zone (timezone=auto).
        // When the device zone is east of the location's zone, the cached
        // bundle's today.date can sit one day ahead of the device's local
        // date without being stale — only invalidate when it lags behind.
        if (
            cached != null &&
            cached.key == key &&
            cached.freshnessKey == freshnessKey &&
            !cached.bundle.today.date.isBefore(today) &&
            Duration.between(cached.fetchedAt, now) < ttl
        ) {
            return@withLock cached.bundle
        }
        val fresh = delegate.fetchForecast(location)
        entry = Entry(key = key, freshnessKey = freshnessKey, fetchedAt = now, bundle = fresh)
        fresh
    }

    private fun keyOf(location: Location): LocationKey = LocationKey(
        latBucket = (location.latitude / locationGridDegrees).roundToLong(),
        lonBucket = (location.longitude / locationGridDegrees).roundToLong(),
    )
}
