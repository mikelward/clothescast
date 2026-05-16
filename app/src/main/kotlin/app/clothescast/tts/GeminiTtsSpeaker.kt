package app.clothescast.tts

import app.clothescast.core.data.tts.DEFAULT_GEMINI_TTS_VOICE
import app.clothescast.core.data.tts.GeminiTtsClient
import app.clothescast.core.data.tts.PcmAudio
import app.clothescast.core.domain.model.TtsStyle
import java.util.Locale

/**
 * High-quality TTS via Gemini's audio-output model. Synthesises PCM audio in one
 * network call, hands the buffer to [PcmAudioPlayer] for playback.
 *
 * Voice is configurable; defaults to `Despina` (smooth). Other prebuilt voices
 * are surfaced via the Settings voice picker.
 *
 * Synthesis and playback are split into [synthesize] and [play] so the worker
 * can capture the same audio buffer for an MQTT publish to the Smart Home
 * bridge between the two — the Hub gets the exact clip the phone speaks.
 *
 * Fallback is the caller's responsibility — see [app.clothescast.work.FetchAndNotifyWorker]
 * which retries with [AndroidTtsSpeaker] when this throws.
 */
class GeminiTtsSpeaker(
    private val client: GeminiTtsClient,
    private val voiceName: String = DEFAULT_GEMINI_TTS_VOICE,
    private val style: TtsStyle = TtsStyle.WEATHER_FORECASTER,
) : TtsSpeaker {

    suspend fun synthesize(text: String, locale: Locale = Locale.getDefault()): PcmAudio =
        client.synthesize(
            text = prepareForTts(text, locale),
            voiceName = voiceName,
            locale = locale,
            style = style,
        )

    suspend fun play(audio: PcmAudio) {
        PcmAudioPlayer.play(audio)
    }

    override suspend fun speak(text: String, locale: Locale) {
        play(synthesize(text, locale))
    }
}
