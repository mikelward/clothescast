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
 */
class CachingWeatherRepository(
    private val delegate: WeatherRepository,
    // The date-rollover check reads the current date in the cached bundle's
    // own forecast zone via clock.instant(); the clock's zone only matters
    // as the fallback for legacy bundles that don't carry a zone.
    private val clock: Clock = Clock.systemDefaultZone(),
    private val ttl: Duration = Duration.ofHours(1),
    private val locationGridDegrees: Double = 0.01,
    /**
     * Pull-based opaque discriminator for inputs *other than location-grid-key*
     * that change which forecast the delegate would return — currently the
     * user's Forecasters model selection (see [ClothesCastApplication]),
     * which itself can depend on location when set to Auto. The cache stores
     * whatever value this returned at fetch time and treats the entry as
     * stale on mismatch, so a settings change takes effect on the next
     * refresh instead of waiting out the TTL.
     *
     * Takes the request's [Location] because an Auto-mode forecasters
     * selection resolves to different models in different regions (UKMO
     * trio in the British Isles, GFS trio over North America, etc.), so
     * the freshness key must vary with location too — otherwise a user
     * who moves from London to Paris on Auto would keep getting the
     * London-trio bundle until the TTL expired.
     *
     * Default ignores the location and returns [Unit] (a single value that
     * matches itself), preserving the location-only behaviour for tests
     * and any delegate whose output doesn't depend on out-of-band state.
     *
     * Suspend so a settings-backed provider can `dataStore.first()` without
     * needing a separately-maintained snapshot. DataStore caches the latest
     * emission in memory, so on the warm path this is a memory hit.
     */
    private val freshnessKeyProvider: suspend (Location) -> Any = { _ -> Unit },
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
        val freshnessKey = freshnessKeyProvider(location)
        val now = clock.instant()
        val cached = entry
        // Open-Meteo returns dates in the forecast location's local zone
        // (timezone=auto), so "the bundle no longer describes today" is
        // judged against the *location's* current date, not the device's.
        // Comparing against the device date breaks whenever the two zones
        // straddle midnight: a device east of the location rolls its date
        // hours earlier, and every refetch returns the same location-local
        // date — still "stale" — so the cache would be defeated (and
        // Open-Meteo hammered) for the rest of the device's day. Bundles
        // without a zone (legacy fixtures) fall back to the device date.
        // `isBefore` rather than `==` so a bundle dated ahead (device west
        // of the location) still hits.
        val bundleStale = cached != null && run {
            val todayAtLocation = cached.bundle.forecastZone
                ?.let { now.atZone(it).toLocalDate() }
                ?: LocalDate.now(clock)
            cached.bundle.today.date.isBefore(todayAtLocation)
        }
        if (
            cached != null &&
            cached.key == key &&
            cached.freshnessKey == freshnessKey &&
            !bundleStale &&
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
