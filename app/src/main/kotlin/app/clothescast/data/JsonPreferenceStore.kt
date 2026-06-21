package app.clothescast.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Shared JSON-in-Preferences I/O plumbing for the DataStore-backed stores in
 * this package ([DailyHistoryStore], [InsightCache], [SettingsRepository]).
 *
 * Each persists one or more values as JSON strings under a
 * `stringPreferencesKey`, and every read does the same three things: skip an
 * absent / blank key, decode defensively (a corrupt or forward-incompatible
 * payload must fall back to a default rather than throw on every flow
 * emission — a thrown exception in a DataStore `map` crashes all collectors
 * and pins the worker on retry), and map the on-disk DTO to the domain type.
 * This base owns that guard-and-decode dance — the `isNullOrBlank` +
 * `runCatching { … }.getOrDefault(default)` boilerplate — so the stores don't
 * each re-implement it.
 *
 * It deliberately does NOT own the DTOs. Each store keeps its own private
 * `@Serializable` DTOs as a versioned schema boundary (`_v1` / `_v8` keys,
 * `ignoreUnknownKeys`) decoupling the on-disk format from the domain model;
 * collapsing them into `@Serializable` domain types would couple the wire
 * format to the domain shape. The decode / encode lambdas carry the reified
 * DTO type, so they run in the subclass where those private DTOs are visible.
 */
abstract class JsonPreferenceStore(
    protected val dataStore: DataStore<Preferences>,
    protected val json: Json = Json { ignoreUnknownKeys = true },
) {
    /**
     * Decodes [raw] via [decode], returning [default] when the value is absent
     * or blank, or when decoding throws (corrupt payload, schema drift). The
     * [decode] lambda owns the reified DTO type and the DTO → domain mapping.
     */
    protected fun <T> decodeJson(raw: String?, default: T, decode: (String) -> T): T {
        if (raw.isNullOrBlank()) return default
        return runCatching { decode(raw) }.getOrDefault(default)
    }

    /**
     * Reads the JSON string under [key] from the latest DataStore snapshot and
     * decodes it via [decodeJson]. A one-shot read (`data.first()`); a store
     * wanting a reactive view maps over [dataStore]`.data` and calls
     * [decodeJson] per emission instead.
     */
    protected suspend fun <T> readJson(
        key: Preferences.Key<String>,
        default: T,
        decode: (String) -> T,
    ): T = decodeJson(dataStore.data.first()[key], default, decode)

    /**
     * Encodes [value] to JSON and stores it under [key] within the current
     * [androidx.datastore.preferences.core.edit] transaction. The reified type
     * resolves against the DTO the caller passes, keeping the wire shape the
     * store's concern while the `encodeToString`-into-key step stays here.
     */
    protected inline fun <reified T> MutablePreferences.writeJson(
        key: Preferences.Key<String>,
        value: T,
    ) {
        this[key] = json.encodeToString(value)
    }
}
