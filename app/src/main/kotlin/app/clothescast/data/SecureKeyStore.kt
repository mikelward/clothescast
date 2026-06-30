package app.clothescast.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.clothescast.core.data.insight.InvalidApiKeyException
import app.clothescast.core.data.insight.MissingApiKeyException
import app.clothescast.diag.DiagLog
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Base64

/**
 * Encrypted on-device storage of the user's API keys.
 *
 * Each key is encrypted with a Tink AEAD primitive whose keyset is itself encrypted
 * by an Android-Keystore master key — so the secret material never appears in
 * SharedPreferences in plaintext. Ciphertext is persisted in DataStore Preferences
 * under per-provider preference keys.
 *
 * EncryptedSharedPreferences is deprecated as of androidx.security:security-crypto
 * 1.1.0-alpha07 — Tink + DataStore is its modern replacement.
 *
 * Constructor takes an [Aead] and a [DataStore] so the class is unit-testable with
 * a fake AEAD and a test DataStore (e.g. temp-file-backed via PreferenceDataStoreFactory).
 * Production callers use [SecureKeyStore.create].
 *
 * `get()` reports any decode or decrypt failure as [InvalidApiKeyException],
 * clears the stored value, and records that the key needs re-entry. This
 * handles the Keystore master key rotating (factory reset, device-to-device
 * transfer that doesn't preserve hardware-backed keys, etc.) without silently
 * falling back to a shared-key network path.
 *
 * Read by the Gemini call planner on the BYOK branch: when the user has stored
 * their own Gemini key, the planner pulls it via [get] and routes the request
 * directly to Google instead of the developer's TTS proxy.
 */
class SecureKeyStore(
    private val aead: Aead,
    private val dataStore: DataStore<Preferences>,
) {

    suspend fun get(): String = read(
        prefKey = GEMINI_PREF_KEY,
        invalidPrefKey = GEMINI_NEEDS_REENTRY_KEY,
        aad = GEMINI_AAD,
        provider = "Gemini",
    )

    suspend fun set(key: String) = write(GEMINI_PREF_KEY, GEMINI_AAD, key)

    suspend fun clear() = remove(GEMINI_PREF_KEY, GEMINI_NEEDS_REENTRY_KEY)

    /**
     * MQTT broker password for the optional Smart Home bridge. Returns `null`
     * if no password is set — anonymous-auth brokers and the bridge-disabled
     * state are both indistinguishable from "unset" by design.
     */
    suspend fun getMqttPassword(): String? = try {
        read(
            prefKey = MQTT_PASS_PREF_KEY,
            invalidPrefKey = null,
            aad = MQTT_PASS_AAD,
            provider = "MQTT",
        )
    } catch (_: MissingApiKeyException) {
        null
    }

    suspend fun setMqttPassword(password: String) = write(MQTT_PASS_PREF_KEY, MQTT_PASS_AAD, password)

    suspend fun clearMqttPassword() = remove(MQTT_PASS_PREF_KEY)

    /**
     * Google Maps-Platform API key for the Google Weather forecaster. Kept in a
     * separate slot from the Gemini key: although both are project-scoped
     * `AIza*` Cloud keys, the Weather API must be enabled (and billing on) for
     * whichever project the key belongs to, so a user may want a different key
     * here than the one driving Gemini TTS — or the same one if its project has
     * the Weather API enabled. Returns `null` when unset (or after a decrypt
     * failure clears a corrupt value), mirroring [getMqttPassword]: there's no
     * shared fallback for Weather, so "no key" simply means Google sits out of
     * the blend.
     */
    suspend fun getGoogleApiKey(): String? = try {
        read(
            prefKey = GOOGLE_PREF_KEY,
            invalidPrefKey = null,
            aad = GOOGLE_AAD,
            provider = "Google",
        )
    } catch (_: MissingApiKeyException) {
        null
    }

    suspend fun setGoogleApiKey(key: String) = write(GOOGLE_PREF_KEY, GOOGLE_AAD, key)

    suspend fun clearGoogleApiKey() = remove(GOOGLE_PREF_KEY)

    /**
     * Reactive "is a Google API key stored." Drives the Google forecaster's
     * checkbox-enabled state and the key-status indicator on the Forecasters
     * page. Presence only — no decrypt.
     */
    val googleApiKeyConfiguredFlow: Flow<Boolean> = dataStore.data
        .map { it[GOOGLE_PREF_KEY] != null }
        .distinctUntilChanged()

    /**
     * Sibling of [geminiKeyFingerprint] for the Google key — folded into the
     * weather-cache freshness key so adding / replacing / clearing the Google
     * key busts a stale forecast bundle (e.g. swapping a key that 403'd the
     * Weather API for a working one refreshes Google into the blend immediately
     * instead of waiting out the TTL). Hashes ciphertext only, no decrypt.
     */
    suspend fun googleApiKeyFingerprint(): Int? =
        dataStore.data.map { it[GOOGLE_PREF_KEY] }.first()?.hashCode()

    /**
     * The Google key plaintext and its [googleApiKeyFingerprint] read from a
     * single DataStore snapshot. A caller that needs both — the weather blend,
     * which fetches with the key and then caches the result under the fingerprint
     * — must read them atomically: two separate reads can straddle a key edit and
     * cache the *old* key's series under the *new* key's fingerprint, defeating
     * the swap-invalidation the fingerprint exists for. Returns `(null, null)`
     * when unset or after a decrypt failure clears a corrupt value, mirroring
     * [getGoogleApiKey]. The fingerprint matches [googleApiKeyFingerprint] (same
     * ciphertext hash), so the inner and outer caches key on the same value.
     */
    suspend fun getGoogleApiKeyWithFingerprint(): Pair<String?, Int?> {
        val ciphertextB64 = dataStore.data.map { it[GOOGLE_PREF_KEY] }.first() ?: return null to null
        val fingerprint = ciphertextB64.hashCode()
        return try {
            val ciphertext = Base64.getDecoder().decode(ciphertextB64)
            aead.decrypt(ciphertext, GOOGLE_AAD).toString(Charsets.UTF_8) to fingerprint
        } catch (e: Exception) {
            // Corrupt ciphertext / unrecoverable Keystore state — log why Google
            // is about to drop out of the blend (no diag trace otherwise), then
            // drop the bad value so the next attempt starts clean, mirroring
            // [read]. Returns nulls rather than rethrowing: the forecast path
            // treats a missing Google key as "Google sits out", not an error.
            DiagLog.w("SecureKeyStore", "Google key decrypt failed; clearing the corrupt value", e)
            dataStore.edit { it.remove(GOOGLE_PREF_KEY) }
            null to null
        }
    }

    /**
     * Reactive view of "is a Gemini key currently stored." Used by the Today screen's
     * welcome card to decide whether to nudge the user to set a key. Reads only the
     * presence of ciphertext, not the plaintext value, so no decrypt happens here.
     */
    val geminiKeyConfiguredFlow: Flow<Boolean> = dataStore.data
        .map { it[GEMINI_PREF_KEY] != null }
        .distinctUntilChanged()

    /**
     * In-memory fingerprint of the stored Gemini key ciphertext, or null when no
     * key is stored. Changes whenever the key is set, replaced, or cleared — Tink
     * re-encrypts with a fresh nonce on every save, so even re-saving the same
     * key yields a new fingerprint. Used as a cache discriminator (see
     * [app.clothescast.ClothesCastApplication]'s weather-cache freshness key) so
     * replacing the key busts a stale forecast bundle — e.g. swapping a key that
     * 403'd Google Weather for a working one refreshes immediately instead of
     * waiting out the TTL. Reads only the ciphertext's hash, never the plaintext,
     * and performs no decrypt.
     */
    suspend fun geminiKeyFingerprint(): Int? =
        dataStore.data.map { it[GEMINI_PREF_KEY] }.first()?.hashCode()

    /**
     * True after a stored Gemini key failed to decrypt and was cleared. Stays
     * true until the user re-enters or explicitly clears the key so retries
     * keep the BYOK privacy boundary instead of falling through to the shared
     * TTS proxy.
     */
    val geminiKeyNeedsReentryFlow: Flow<Boolean> = dataStore.data
        .map { it[GEMINI_NEEDS_REENTRY_KEY] == true }
        .distinctUntilChanged()

    /**
     * Sibling of [geminiKeyConfiguredFlow] for the MQTT broker password. The
     * Smart Home settings card uses it to render the "Password set" indicator
     * without ever decrypting the stored value.
     */
    val mqttPasswordConfiguredFlow: Flow<Boolean> = dataStore.data
        .map { it[MQTT_PASS_PREF_KEY] != null }
        .distinctUntilChanged()

    private suspend fun read(
        prefKey: Preferences.Key<String>,
        invalidPrefKey: Preferences.Key<Boolean>?,
        aad: ByteArray,
        provider: String,
    ): String {
        val ciphertextB64 = dataStore.data.map { it[prefKey] }.first()
            ?: if (invalidPrefKey != null && dataStore.data.map { it[invalidPrefKey] == true }.first()) {
                throw InvalidApiKeyException(provider)
            } else {
                throw MissingApiKeyException(provider)
            }
        return try {
            val ciphertext = Base64.getDecoder().decode(ciphertextB64)
            aead.decrypt(ciphertext, aad).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            // Corrupt ciphertext or unrecoverable Keystore state — drop the bad value so
            // the next attempt prompts the user to re-enter their key cleanly.
            dataStore.edit {
                it.remove(prefKey)
                if (invalidPrefKey != null) it[invalidPrefKey] = true
            }
            if (invalidPrefKey != null) {
                throw InvalidApiKeyException(provider)
            } else {
                throw MissingApiKeyException(provider)
            }
        }
    }

    private suspend fun write(prefKey: Preferences.Key<String>, aad: ByteArray, key: String) {
        val ciphertext = aead.encrypt(key.toByteArray(Charsets.UTF_8), aad)
        val ciphertextB64 = Base64.getEncoder().encodeToString(ciphertext)
        dataStore.edit {
            it[prefKey] = ciphertextB64
            if (prefKey == GEMINI_PREF_KEY) it.remove(GEMINI_NEEDS_REENTRY_KEY)
        }
    }

    private suspend fun remove(
        prefKey: Preferences.Key<String>,
        invalidPrefKey: Preferences.Key<Boolean>? = null,
    ) {
        dataStore.edit {
            it.remove(prefKey)
            if (invalidPrefKey != null) it.remove(invalidPrefKey)
        }
    }

    companion object {
        private val GEMINI_PREF_KEY = stringPreferencesKey("gemini_api_key_v1")
        private val GEMINI_NEEDS_REENTRY_KEY = booleanPreferencesKey("gemini_api_key_needs_reentry_v1")
        private val GEMINI_AAD = "clothescast:gemini_api_key:v1".toByteArray(Charsets.UTF_8)
        private val MQTT_PASS_PREF_KEY = stringPreferencesKey("mqtt_password_v1")
        private val MQTT_PASS_AAD = "clothescast:mqtt_password:v1".toByteArray(Charsets.UTF_8)
        private val GOOGLE_PREF_KEY = stringPreferencesKey("google_api_key_v1")
        private val GOOGLE_AAD = "clothescast:google_api_key:v1".toByteArray(Charsets.UTF_8)

        private const val MASTER_KEY_URI = "android-keystore://clothescast_master_key"
        private const val KEYSET_PREFS = "clothescast_master_prefs"
        private const val KEYSET_NAME = "clothescast_master_keyset"

        fun create(context: Context): SecureKeyStore {
            AeadConfig.register()
            val aead: Aead = AndroidKeysetManager.Builder()
                .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
                .getPrimitive(Aead::class.java)
            return SecureKeyStore(aead, context.secureKeyDataStore)
        }
    }
}

private val Context.secureKeyDataStore: DataStore<Preferences> by preferencesDataStore(name = "secure_key_store")
