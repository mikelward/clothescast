package app.clothescast.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.clothescast.core.domain.model.DailyHistoryEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * A small rolling store of the feels-like daytime aggregates we actually
 * delivered each day. See [DailyHistoryEntry] for the rationale — short
 * version: Open-Meteo's `forecast?past_days=1` returns model output, not
 * observations, and was observed to be wildly wrong for the past day
 * (Liverpool 2026-05-23 came back as a flat 14..18 °C when reality was 29 °C).
 * Comparing today's forecast against the prior delivered aggregate sidesteps
 * the upstream miss and also matches what the user actually dressed for
 * yesterday morning, which is what they remember.
 *
 * Storage: a single JSON-serialized list of entries under one DataStore key,
 * sorted oldest-first. `DeriveInsight` only ever consults exactly
 * `today.minusDays(1)`, so entries older than that are dead weight — every
 * write prunes anything below `entry.date - 1`, leaving at most two records
 * in the store (yesterday + today, transitioning to today + tomorrow on the
 * next morning's write). At ~30 bytes per entry the file is sub-100 bytes.
 *
 * Concurrency: DataStore's `edit` serializes writes, and read-modify-write
 * runs inside the same transaction, so concurrent worker runs can't race a
 * duplicate-day write into the list.
 */
class DailyHistoryStore(
    dataStore: DataStore<Preferences>,
    json: Json = Json { ignoreUnknownKeys = true },
) : JsonPreferenceStore(dataStore, json) {

    /**
     * Returns the entry recorded for [date], or null when nothing was stored
     * for that day (day-one install, app disabled / device off that day, or
     * the entry was pruned by retention).
     */
    suspend fun entryFor(date: LocalDate): DailyHistoryEntry? {
        val list = read()
        return list.firstOrNull { it.date == date }
    }

    /**
     * Records [entry] for [DailyHistoryEntry.date]. If an entry for the same
     * day already exists it's overwritten (a same-day refresh delivers an
     * updated aggregate). Anything older than `entry.date - 1` is dropped —
     * `DeriveInsight` only ever consults `today.minusDays(1)`, so two-day-old
     * records are unreachable from the read path and don't earn their bytes.
     */
    suspend fun put(entry: DailyHistoryEntry) {
        dataStore.edit { prefs ->
            val cutoff = entry.date.minusDays(1)
            val existing = decodeJson(prefs[HISTORY_KEY], emptyList(), ::decodeEntries)
            val merged = (existing.filter { it.date != entry.date && !it.date.isBefore(cutoff) } + entry)
                .sortedBy { it.date }
            prefs.writeJson(HISTORY_KEY, merged.map { it.toDto() })
        }
    }

    private suspend fun read(): List<DailyHistoryEntry> =
        readJson(HISTORY_KEY, emptyList(), ::decodeEntries)

    private fun decodeEntries(raw: String): List<DailyHistoryEntry> =
        json.decodeFromString<List<EntryDto>>(raw).map { it.toDomain() }

    @Serializable
    private data class EntryDto(
        val dateEpochDays: Long,
        val feelsLikeMinC: Double,
        val feelsLikeMaxC: Double,
    ) {
        fun toDomain(): DailyHistoryEntry = DailyHistoryEntry(
            date = LocalDate.ofEpochDay(dateEpochDays),
            feelsLikeMinC = feelsLikeMinC,
            feelsLikeMaxC = feelsLikeMaxC,
        )
    }

    private fun DailyHistoryEntry.toDto(): EntryDto = EntryDto(
        dateEpochDays = date.toEpochDay(),
        feelsLikeMinC = feelsLikeMinC,
        feelsLikeMaxC = feelsLikeMaxC,
    )

    companion object {
        private val HISTORY_KEY = stringPreferencesKey("daily_history_v1")

        fun create(context: Context): DailyHistoryStore =
            DailyHistoryStore(context.dailyHistoryDataStore)
    }
}

private val Context.dailyHistoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "daily_history")
