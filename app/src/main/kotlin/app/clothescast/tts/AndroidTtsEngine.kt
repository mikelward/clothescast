package app.clothescast.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import app.clothescast.diag.DiagLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Speech Services by Google. Most vendor-default Android TTS engines sound
 * notably worse, so where Google's engine is installed we always prefer it.
 * Public so the install-CTA in Settings can detect when the user is missing
 * it and offer a Play-Store deep link.
 */
const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"

/**
 * Initialises a [TextToSpeech] engine, preferring [GOOGLE_TTS_PACKAGE] and
 * falling back to the system default when Google's engine isn't installed
 * (or fails to bind). Returns a fully-initialised engine — the caller is
 * responsible for `shutdown()`. Throws on any init failure so the caller can
 * surface the error rather than synthesise into a dead engine.
 *
 * Shared by [AndroidTtsSpeaker] and [AndroidTtsVoiceEnumerator] — both want
 * the same "Google if available, else default" preference.
 */
internal suspend fun initAndroidTtsEngine(context: Context): TextToSpeech =
    runCatching { initOneAttempt(context, GOOGLE_TTS_PACKAGE) }
        .getOrElse {
            // A cancelled caller must unwind, not bind a second engine
            // connection inside an already-cancelled coroutine.
            if (it is CancellationException) throw it
            DiagLog.w(INIT_TAG, "Google TTS unavailable; falling back to system default.", it)
            initOneAttempt(context, null)
        }

private suspend fun initOneAttempt(context: Context, enginePackage: String?): TextToSpeech =
    suspendCancellableCoroutine { cont ->
        var engineRef: TextToSpeech? = null
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                cont.resume(engineRef!!)
            } else {
                cont.resumeWithException(
                    IllegalStateException("TTS engine init failed (status=$status)"),
                )
            }
        }
        val engine = if (enginePackage != null) {
            TextToSpeech(context, listener, enginePackage)
        } else {
            TextToSpeech(context, listener)
        }
        engineRef = engine
        cont.invokeOnCancellation { runCatching { engine.shutdown() } }
    }

/**
 * Auto-picks the highest-quality voice for [locale] from [voices], or `null`
 * when [voices] is empty. Preference order: exact-locale match → same-
 * language match → any voice. Within the chosen pool, [Voice.quality] wins
 * (VERY_LOW=100 .. VERY_HIGH=500), with non-network voices breaking ties so
 * a flaky connection at speak-time doesn't break playback.
 */
internal fun pickBestVoice(voices: Collection<Voice>, locale: Locale): Voice? {
    if (voices.isEmpty()) return null
    val exact = voices.filter { it.locale == locale }
    val sameLanguage = voices.filter { it.locale.language == locale.language }
    val candidates = when {
        exact.isNotEmpty() -> exact
        sameLanguage.isNotEmpty() -> sameLanguage
        else -> voices.toList()
    }
    return candidates.maxWithOrNull(
        compareBy<Voice> { it.quality }.thenBy { !it.isNetworkConnectionRequired },
    )
}

private const val INIT_TAG = "AndroidTtsEngine"
