package app.clothescast.tts

import android.content.Context
import android.speech.tts.Voice
import app.clothescast.diag.DiagLog
import java.util.Locale

/**
 * One row in the device-voice picker. [id] is what gets persisted to
 * [app.clothescast.core.domain.model.UserPreferences.deviceVoice] and what
 * [AndroidTtsSpeaker] looks up at speak-time. The other fields are for the
 * picker's display label and the "currently using" line.
 */
data class DeviceVoice(
    val id: String,
    val locale: Locale,
    val quality: Int,
    val isNetworkRequired: Boolean,
)

/**
 * Enumerator interface for the device-voice picker. Decouples the Settings
 * ViewModel from [AndroidTtsVoiceEnumerator]'s Android-context dependency so
 * JVM-only ViewModel tests can stub it.
 */
interface TtsVoiceEnumerator {
    /**
     * Voices the device's TTS engine reports as supporting [locale], for
     * display in the picker. Filters to exact-locale matches when any
     * exist; relaxes to same-language and then to "all voices" when none
     * do, so the picker is never empty if the engine has *any* voices.
     */
    suspend fun listVoices(locale: Locale): List<DeviceVoice>

    /**
     * The voice [AndroidTtsSpeaker] would auto-pick for [locale] when the
     * user hasn't pinned one. Used by Settings' "Currently using" line.
     */
    suspend fun resolveAutoPick(locale: Locale): DeviceVoice?

    /**
     * Looks up a voice by id against the engine's *full* catalogue,
     * regardless of locale. Use this for resolving the user's pinned voice
     * — the speaker accepts a pin across locale variants as long as the
     * language matches (e.g. an en-US pin is still honoured when
     * [voiceLocale] is en-GB), so the picker UI's "Currently using" line
     * needs to find the pinned voice even when the locale-filtered
     * [listVoices] excludes it.
     */
    suspend fun findVoice(id: String): DeviceVoice?
}

/**
 * Enumerates voices from the Android TTS engine for the device-voice picker.
 *
 * Same engine-init strategy as [AndroidTtsSpeaker] (Google's engine if
 * installed, system default otherwise) — see [initAndroidTtsEngine].
 *
 * Listing voices and resolving the auto-pick are separate methods because
 * the auto-pick needs to run [pickBestVoice] over the engine's reported set
 * — easier to keep that one round-trip in here than to push the heuristic
 * into the UI layer.
 */
class AndroidTtsVoiceEnumerator(private val context: Context) : TtsVoiceEnumerator {

    /**
     * Returns voices the device's TTS engine reports as supporting [locale],
     * relaxing to same-language voices if no exact match exists, then to the
     * full set if neither does. Empty when the engine reports no voices at
     * all (very old / broken installs).
     */
    override suspend fun listVoices(locale: Locale): List<DeviceVoice> {
        val tts = runCatching { initAndroidTtsEngine(context) }.getOrElse {
            DiagLog.w(TAG, it, "Couldn't init TTS for voice enumeration; returning empty list.")
            return emptyList()
        }
        return try {
            val voices = runCatching { tts.voices }.getOrNull().orEmpty()
            if (voices.isEmpty()) return emptyList()
            val exact = voices.filter { it.locale == locale }
            val sameLang = voices.filter { it.locale.language == locale.language }
            val pool = when {
                exact.isNotEmpty() -> exact
                sameLang.isNotEmpty() -> sameLang
                else -> voices.toList()
            }
            pool
                .sortedWith(
                    // Best first: highest quality, then offline before network at
                    // the same quality (mirrors pickBestVoice's tie-break), then
                    // alphabetical by id for stable ordering across runs.
                    compareByDescending<Voice> { it.quality }
                        .thenBy { it.isNetworkConnectionRequired }
                        .thenBy { it.name },
                )
                .map { it.toDomain() }
        } finally {
            tts.shutdown()
        }
    }

    /**
     * Returns the voice [AndroidTtsSpeaker] would auto-pick for [locale] when
     * [app.clothescast.core.domain.model.UserPreferences.deviceVoice] is null.
     * Used by the "currently using" line in Settings so users can see what
     * the auto path resolves to without first hitting Test.
     */
    override suspend fun resolveAutoPick(locale: Locale): DeviceVoice? {
        val tts = runCatching { initAndroidTtsEngine(context) }.getOrElse { return null }
        return try {
            val voices = runCatching { tts.voices }.getOrNull().orEmpty()
            pickBestVoice(voices, locale)?.toDomain()
        } finally {
            tts.shutdown()
        }
    }

    override suspend fun findVoice(id: String): DeviceVoice? {
        val tts = runCatching { initAndroidTtsEngine(context) }.getOrElse { return null }
        return try {
            runCatching { tts.voices }.getOrNull().orEmpty()
                .firstOrNull { it.name == id }
                ?.toDomain()
        } finally {
            tts.shutdown()
        }
    }

    companion object {
        private const val TAG = "AndroidTtsVoiceEnumerator"
    }
}

private fun Voice.toDomain(): DeviceVoice = DeviceVoice(
    id = name,
    locale = locale,
    quality = quality,
    isNetworkRequired = isNetworkConnectionRequired,
)
