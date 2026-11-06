package app.clothescast.core.data.tts

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Wire types for the Gemini TTS variant of generateContent.
 *
 * The shape is `generateContent` like the text endpoint, but the request specifies
 * `responseModalities: ["AUDIO"]` and a [SpeechConfig], and the response carries an
 * [InlineData] audio payload instead of plain text. Kept in its own file so the
 * existing text DTOs in `:core:data/insight` stay focused.
 */
@Serializable
internal data class TtsRequest(
    val contents: List<TtsContent>,
    val generationConfig: TtsGenerationConfig,
)

@Serializable
internal data class TtsContent(val parts: List<TtsTextPart>)

/**
 * One turn of speech. Gemini 3.8 TTS reads [text] as a verbatim transcript, so
 * anything written there is spoken; how to say it goes in [speechMetadata].
 */
@Serializable
internal data class TtsTextPart(
    val text: String,
    val speechMetadata: SpeechMetadata,
)

/** Delivery direction for a turn: tone, pacing, accent. Never spoken. */
@Serializable
internal data class SpeechMetadata(val style: String)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
internal data class TtsGenerationConfig(
    // `@EncodeDefault(ALWAYS)` forces the field onto the wire even when its
    // runtime value equals the declared default. Without it, kotlinx's default
    // `encodeDefaults = false` silently drops the field and Gemini falls back
    // to its server-side default of `responseModalities = ["TEXT"]`, which a
    // TTS model can't satisfy — error: "The requested combination of response
    // modalities (TEXT) is not supported by the model."
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val responseModalities: List<String> = listOf("AUDIO"),
    val speechConfig: SpeechConfig,
)

/**
 * [languageCode] is a BCP-47 tag ("en-AU") naming the language and accent to
 * speak in. It anchors the accent far more reliably than accent words in the
 * style do. Null leaves it off the wire, where Gemini infers the language.
 */
@Serializable
internal data class SpeechConfig(val voiceConfig: VoiceConfig, val languageCode: String? = null)

@Serializable
internal data class VoiceConfig(val prebuiltVoiceConfig: PrebuiltVoiceConfig)

@Serializable
internal data class PrebuiltVoiceConfig(val voiceName: String)

@Serializable
internal data class TtsResponse(
    val candidates: List<TtsCandidate> = emptyList(),
    val promptFeedback: TtsPromptFeedback? = null,
)

@Serializable
internal data class TtsCandidate(
    val content: TtsCandidateContent? = null,
    val finishReason: String? = null,
)

@Serializable
internal data class TtsCandidateContent(val parts: List<TtsResponsePart> = emptyList())

@Serializable
internal data class TtsResponsePart(
    val inlineData: InlineData? = null,
)

@Serializable
internal data class InlineData(
    val mimeType: String,
    val data: String,
)

@Serializable
internal data class TtsPromptFeedback(
    val blockReason: String? = null,
)
