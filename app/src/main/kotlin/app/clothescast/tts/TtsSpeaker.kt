package app.clothescast.tts

import java.util.Locale

/**
 * Speaks text aloud through the platform TTS engine. Suspending so the Worker can
 * await completion before declaring the run successful.
 *
 * Implementations should:
 * - Manage the lifecycle of the underlying engine (init on first use, shutdown after).
 * - Set the language to the requested [Locale] when supported, falling back to the
 *   engine default when not.
 * - Throw on init / synthesis failure so the caller can route to Result.retry.
 * - Run incoming text through [prepareForTts] before handing it to the engine, so
 *   the brand "ClothesCast" is pronounced as two clear syllables instead of being
 *   collapsed to "ClotheCast".
 */
interface TtsSpeaker {
    suspend fun speak(text: String, locale: Locale = Locale.getDefault())
}

/**
 * Rewrites text before handing it to a TTS engine. Two transforms:
 *
 *  - "ClothesCast" → "Clothes Cast". Both engines we ship — device TTS and
 *    Gemini — tend to elide the `s` at the `s‑c` cluster boundary, producing
 *    "ClotheCast". Splitting on a literal space is the only trick that works
 *    across both (SSML / phoneme markup support is uneven).
 *  - Replace the `°` glyph with a locale-appropriate spoken form. In English
 *    the trailing ° of a range expands to "degrees" while the inner one is
 *    stripped, so "8° to 14°." reads as "8 to 14 degrees." rather than the
 *    wordier "8 degrees to 14 degrees."; bare values ("14°.", "5° warmer")
 *    pick up the same "degrees" suffix. Non-English locales already encode
 *    the range word naturally ("8 bis 14", "entre 8 y 14") so the ° is
 *    simply dropped — appending the English word "degrees" mid-German would
 *    be worse than silence about the unit.
 *
 * The notification body keeps the original visual spelling — only the spoken
 * path goes through this transform.
 */
internal fun prepareForTts(text: String, locale: Locale = Locale.getDefault()): String {
    val withoutBrand = text.replace("ClothesCast", "Clothes Cast")
    return if (locale.language == "en") {
        withoutBrand
            // "X° <conn> Y°" → strip the inner °, leave the trailing one for
            // the next pass to spell out as "degrees".
            .replace(Regex("(\\d+)°( \\S+ \\d+)°"), "$1$2°")
            .replace(Regex("(\\d+)°"), "$1 degrees")
    } else {
        withoutBrand.replace("°", "")
    }
}
