package app.clothescast.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.PerModelHour
import app.clothescast.core.domain.model.WeatherCondition
import app.clothescast.diag.DiagLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.roundToLong

/**
 * Persists the most recent *extended* (10-day) Google forecast series for one
 * location so the expensive multi-page walk runs at most a couple of times a
 * day rather than on every forecast refresh.
 *
 * Why this exists: the Google forecaster's full horizon
 * ([app.clothescast.core.data.weather.GoogleWeatherModelClient.EXTENDED_HOURS])
 * costs ~10 paginated, billed Google API calls. We only want to pay that when
 * the user opens the app, then reuse the result — for both later app opens and
 * the cheap background refreshes — until it goes stale ([defaultTtl], "once or
 * twice a day"). [ClothesCastApplication] reads [fresh] first and only walks the
 * full horizon (then [put]s it) on a foreground miss.
 *
 * Single entry, keyed by location rounded to ~1 km — the same grid
 * [app.clothescast.core.domain.repository.CachingWeatherRepository] uses, so the
 * two caches move together — and by the Google key's fingerprint, so swapping a
 * working key for one that 403s the Weather API drops the stale series instead
 * of keeping Google in the blend for the rest of the TTL. Persisted across
 * process death via DataStore; a deserialization failure drops the entry and
 * re-fetches, the same posture as [InsightCache].
 *
 * Privacy: stores only the forecast numbers Google returns for the location,
 * keyed by a coarse (~1 km) lat/lon bucket and a non-reversible 32-bit hash of
 * the key's ciphertext (the same [SecureKeyStore.googleApiKeyFingerprint] the
 * outer cache uses — not the key itself, and no decrypt). No PII, no API key.
 */
class GoogleForecastCache(
    private val dataStore: DataStore<Preferences>,
    private val clock: Clock = Clock.systemUTC(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val locationGridDegrees: Double = 0.01,
) {
    /**
     * The cached extended series if one is stored for [location] (same ~1 km
     * grid cell), was fetched with the same key ([apiKeyFingerprint]), and is
     * younger than [maxAge], else null. Returns null — never throws — on a decode
     * failure, dropping the corrupt entry so the caller re-fetches.
     */
    suspend fun fresh(
        location: Location,
        apiKeyFingerprint: Int?,
        maxAge: Duration = defaultTtl,
    ): List<PerModelHour>? {
        val raw = readRaw() ?: return null
        val entry = try {
            json.decodeFromString<Entry>(raw)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagLog.w(TAG, "Google forecast cache decode failed; dropping the entry", e)
            clear()
            return null
        }
        // Key change (e.g. a working key swapped for one that 403s) → treat the
        // old key's series as stale so Google re-fetches under the current key.
        if (entry.apiKeyFingerprint != apiKeyFingerprint) return null
        val key = keyOf(location)
        if (entry.latBucket != key.first || entry.lonBucket != key.second) return null
        val age = Duration.between(Instant.ofEpochMilli(entry.fetchedAtMs), clock.instant())
        // A negative age (clock moved backwards / stored on a different device
        // time) is treated as fresh — the data is still the latest we fetched.
        if (age >= maxAge) return null
        return entry.hours.mapNotNull { it.toDomain() }.ifEmpty { null }
    }

    /**
     * Stores [hours] as the extended series for [location], stamped now. A
     * persistence failure is logged and swallowed — the caller already has the
     * series in hand for this fetch, and the next foreground walk re-stamps it.
     */
    suspend fun put(location: Location, apiKeyFingerprint: Int?, hours: List<PerModelHour>) {
        if (hours.isEmpty()) return
        val key = keyOf(location)
        val entry = Entry(
            latBucket = key.first,
            lonBucket = key.second,
            apiKeyFingerprint = apiKeyFingerprint,
            fetchedAtMs = clock.instant().toEpochMilli(),
            hours = hours.map { it.toDto() },
        )
        val encoded = try {
            json.encodeToString(entry)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagLog.w(TAG, "Google forecast cache encode failed; not persisting", e)
            return
        }
        try {
            dataStore.edit { it[ENTRY_KEY] = encoded }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagLog.w(TAG, "Google forecast cache write failed", e)
        }
    }

    private suspend fun readRaw(): String? = try {
        dataStore.data.first()[ENTRY_KEY]
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DiagLog.w(TAG, "Google forecast cache read failed", e)
        null
    }

    private suspend fun clear() {
        try {
            dataStore.edit { it.remove(ENTRY_KEY) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagLog.w(TAG, "Google forecast cache clear failed", e)
        }
    }

    private fun keyOf(location: Location): Pair<Long, Long> =
        (location.latitude / locationGridDegrees).roundToLong() to
            (location.longitude / locationGridDegrees).roundToLong()

    @Serializable
    private data class Entry(
        val latBucket: Long,
        val lonBucket: Long,
        // Fingerprint of the Google key that fetched this series; null only on
        // legacy entries written before keying was added (treated as a miss
        // against any real fingerprint, so they re-fetch).
        val apiKeyFingerprint: Int? = null,
        val fetchedAtMs: Long,
        val hours: List<HourDto>,
    )

    // Mirrors InsightCache.PerModelHourDto's date split (epoch-day + second-of-day)
    // so a midnight-crossing series round-trips with each hour's own date intact.
    // Only the fields Google populates are carried; the rest default absent.
    @Serializable
    private data class HourDto(
        val dateEpochDays: Long,
        val secondOfDay: Int,
        val apparentTemperatureC: Double,
        val temperatureC: Double,
        val precipitationProbabilityPct: Double? = null,
        val precipitationMm: Double? = null,
        val windSpeedKmh: Double? = null,
        val relativeHumidityPct: Double? = null,
        val cloudCoverPct: Double? = null,
        val uvIndex: Double? = null,
        val conditionName: String? = null,
    ) {
        fun toDomain(): PerModelHour = PerModelHour(
            time = LocalDateTime.of(
                LocalDate.ofEpochDay(dateEpochDays),
                LocalTime.ofSecondOfDay(secondOfDay.toLong()),
            ),
            apparentTemperatureC = apparentTemperatureC,
            temperatureC = temperatureC,
            precipitationProbabilityPct = precipitationProbabilityPct,
            precipitationMm = precipitationMm,
            windSpeedKmh = windSpeedKmh,
            relativeHumidityPct = relativeHumidityPct,
            cloudCoverPct = cloudCoverPct,
            uvIndex = uvIndex,
            condition = conditionName?.let {
                runCatching { WeatherCondition.valueOf(it) }.getOrNull()
            },
        )
    }

    private fun PerModelHour.toDto(): HourDto = HourDto(
        dateEpochDays = time.toLocalDate().toEpochDay(),
        secondOfDay = time.toLocalTime().toSecondOfDay(),
        apparentTemperatureC = apparentTemperatureC,
        temperatureC = temperatureC,
        precipitationProbabilityPct = precipitationProbabilityPct,
        precipitationMm = precipitationMm,
        windSpeedKmh = windSpeedKmh,
        relativeHumidityPct = relativeHumidityPct,
        cloudCoverPct = cloudCoverPct,
        uvIndex = uvIndex,
        conditionName = condition?.name,
    )

    companion object {
        // "Once or twice a day": reuse the extended walk's result for 12 h, so a
        // normal day of app opens triggers at most two full-horizon Google pulls.
        val defaultTtl: Duration = Duration.ofHours(12)

        private const val TAG = "GoogleForecast"
        private val ENTRY_KEY = stringPreferencesKey("extended_series_v1")

        fun create(context: Context): GoogleForecastCache =
            GoogleForecastCache(context.googleForecastDataStore)
    }
}

private val Context.googleForecastDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "google_forecast_cache")
