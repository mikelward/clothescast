package app.clothescast.tts

import app.clothescast.core.domain.model.HolidayId
import app.clothescast.core.domain.model.TtsStyle
import app.clothescast.core.domain.model.VoiceGender
import app.clothescast.core.domain.model.holidayTtsStyle
import app.clothescast.core.domain.model.suggestedVoiceGender

/**
 * Curated voice list for the user-facing voice picker. Only Gemini's prebuilt
 * voices need a curated list — the on-device engine enumerates voices via
 * Android's TextToSpeech API.
 *
 * The [id] is what's persisted and sent to the API. The [displayName] is shown
 * in the dropdown and is intentionally English-only for v1; if/when we
 * localize, these move to strings.xml. [gender] is consulted only when a
 * holiday auto-selects a gendered persona (see [resolveHolidayVoice]); it
 * never filters the user-facing picker.
 */
data class TtsVoiceOption(
    val id: String,
    val displayName: String,
    val gender: VoiceGender = VoiceGender.NEUTRAL,
)

// Curated from listening tests across en-GB, en-AU, en-US, and de-* under the
// post-#353 shipping configuration (clarity-trimmed accents, B3 register-in-
// directive routing on en-AU). Despina is the default — validated across all
// six tested locales, four-roll en-AU B3 distribution all ≥8 (the user's
// acceptance bar). Order: Despina (default) first, then by character cluster.
//
// Leda showed wide en-AU B3 4-roll variance (5 / 7.5 / 8 / 7) in eval but
// stays in the picker on user preference — qualitatively strong on real-world
// audio. She loses the default slot to Despina's tighter distribution but is
// retained for users who want her character.
//
// Dropped from the previous 11-voice picker: Sadaltager, Vindemiatrix,
// Algieba, Sulafat (untested under current shipping config — trimming the
// picker to voices the eval has actually validated). Earlier drops stand:
// Orus (theatrical), Achird (vowel issues), Zephyr / Puck / Fenrir
// (theatrical / excitable). See docs/voice-evals.md for the data.
val GEMINI_VOICES: List<TtsVoiceOption> = listOf(
    TtsVoiceOption("Despina", "Despina — Smooth", VoiceGender.FEMALE),
    TtsVoiceOption("Charon", "Charon — Informative", VoiceGender.MALE),
    TtsVoiceOption("Iapetus", "Iapetus — Clear", VoiceGender.MALE),
    TtsVoiceOption("Kore", "Kore — Firm", VoiceGender.FEMALE),
    TtsVoiceOption("Erinome", "Erinome — Clear", VoiceGender.FEMALE),
    TtsVoiceOption("Aoede", "Aoede — Breezy", VoiceGender.FEMALE),
    TtsVoiceOption("Leda", "Leda — Youthful", VoiceGender.FEMALE),
)

/** Gender of the named Gemini voice, or [VoiceGender.NEUTRAL] if unknown. */
private fun voiceGenderOf(voiceId: String): VoiceGender =
    GEMINI_VOICES.firstOrNull { it.id == voiceId }?.gender ?: VoiceGender.NEUTRAL

/**
 * First curated voice matching [gender], falling back to the first voice in the
 * list (the default) when none match — keeps a sensible voice rather than
 * throwing if the catalog ever loses a gender.
 */
private fun defaultVoiceForGender(gender: VoiceGender): String =
    GEMINI_VOICES.firstOrNull { it.gender == gender }?.id ?: GEMINI_VOICES.first().id

/** Effective Gemini voice + persona after applying any holiday auto-selection. */
data class GeminiVoiceSelection(val voiceName: String, val style: TtsStyle)

/**
 * Resolves the voice and persona to speak today's briefing in, auto-switching
 * to a seasonal persona (and a matching-gender voice) when [holidayId] maps to
 * one — e.g. Presidents' Day → a male president, Towel Day → a male sci-fi
 * narrator.
 *
 * A deliberate non-default persona pick wins: if the user has chosen any
 * [TtsStyle] other than [TtsStyle.WEATHER_FORECASTER], their choice stands and
 * the holiday is ignored. Only the everyday forecaster default yields to the
 * day. The voice is only swapped when the persona implies a gender the user's
 * current voice doesn't already have, so a user already on a male voice keeps
 * it for a male persona.
 */
fun resolveHolidayVoice(
    holidayId: HolidayId?,
    userVoice: String,
    userStyle: TtsStyle,
): GeminiVoiceSelection {
    val persona = holidayId?.let { holidayTtsStyle(it) }
    if (persona == null || userStyle != TtsStyle.WEATHER_FORECASTER) {
        return GeminiVoiceSelection(userVoice, userStyle)
    }
    val gender = persona.suggestedVoiceGender
    val voice = if (gender == VoiceGender.NEUTRAL || voiceGenderOf(userVoice) == gender) {
        userVoice
    } else {
        defaultVoiceForGender(gender)
    }
    return GeminiVoiceSelection(voice, persona)
}
