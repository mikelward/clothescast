package app.clothescast.tts

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import app.clothescast.diag.DiagLog
import app.clothescast.diag.safe
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Production [TtsSpeaker] that wraps Android's [TextToSpeech] engine.
 *
 * Each call creates a fresh engine connection (via [initAndroidTtsEngine]),
 * picks a voice for the requested locale, speaks one utterance, and shuts
 * down. The Worker fires once a day, so per-call init cost (~50–200ms) is
 * negligible.
 *
 * Voice selection:
 * 1. If [voiceId] is set and matches a voice the engine reports for the
 *    requested locale's *language*, use it. This is the user's pin from
 *    Settings → Voice → Device → voice picker.
 * 2. Otherwise, fall back to [pickBestVoice] — the highest-quality voice
 *    matching the requested locale, with same-language as a relaxation.
 *    This is the default behaviour for installs that haven't opened the
 *    picker.
 *
 * The language match (rather than exact-locale) for the pinned path is
 * deliberate: a user who picked "Wavenet F" on en-US shouldn't be silently
 * downgraded to a different voice just because [voiceLocale] is set to
 * SYSTEM and the phone is on en-GB this morning. Only when the requested
 * locale's *language* is different from the pin's do we abandon it.
 */
class AndroidTtsSpeaker(
    private val context: Context,
    private val voiceId: String? = null,
) : TtsSpeaker {

    override suspend fun speak(text: String, locale: Locale) {
        val tts = initAndroidTtsEngine(context)
        try {
            tts.setAudioAttributes(SPEECH_AUDIO_ATTRS)
            applyVoice(tts, locale)
            speakAndAwait(tts, prepareForTts(text, locale))
        } finally {
            tts.shutdown()
        }
    }

    private fun applyVoice(tts: TextToSpeech, locale: Locale) {
        tts.setLanguage(locale)
        val voices: Set<Voice> = runCatching { tts.voices }.getOrNull() ?: emptySet()
        if (voices.isEmpty()) return

        val pinned = voiceId
            ?.let { id -> voices.firstOrNull { it.name == id } }
            ?.takeIf { it.locale.language == locale.language }
        val chosen = pinned ?: pickBestVoice(voices, locale)
        if (chosen != null) {
            runCatching { tts.voice = chosen }
                .onSuccess {
                    val source = if (pinned != null) "pinned" else "auto"
                    DiagLog.i(
                        TAG,
                        "TTS voice (%s): %s (quality=%s, locale=%s)",
                        // Either "pinned" or "auto" — a literal from two lines up.
                        safe(source),
                        chosen.name,
                        chosen.quality,
                        chosen.locale,
                    )
                }
                .onFailure { DiagLog.w(TAG, it, "Setting TTS voice failed; using engine default.") }
        }
    }

    private suspend fun speakAndAwait(tts: TextToSpeech, text: String) {
        val utteranceId = UUID.randomUUID().toString()
        suspendCancellableCoroutine<Unit> { cont ->
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}

                override fun onDone(id: String?) {
                    if (id == utteranceId && cont.isActive) cont.resume(Unit)
                }

                @Deprecated(
                    "Required override; modern TTS engines call onError(id, errorCode) instead.",
                    ReplaceWith("onError(id, TextToSpeech.ERROR)"),
                )
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(id: String?) {
                    onError(id, TextToSpeech.ERROR)
                }

                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId && cont.isActive) {
                        cont.resumeWithException(
                            IllegalStateException("TTS synthesis failed (errorCode=$errorCode)"),
                        )
                    }
                }
            })

            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result == TextToSpeech.ERROR) {
                cont.resumeWithException(IllegalStateException("TTS speak() rejected the utterance"))
            }
            cont.invokeOnCancellation { runCatching { tts.stop() } }
        }
        DiagLog.i(TAG, "Spoke utterance %s…", utteranceId.take(8))
    }

    companion object {
        private const val TAG = "AndroidTtsSpeaker"

        // Match the cloud-TTS path (PcmAudioPlayer): tag the stream as assistant
        // speech so audio focus / ducking works the same way regardless of engine.
        private val SPEECH_AUDIO_ATTRS: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
