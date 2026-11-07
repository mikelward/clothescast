package app.clothescast.core.data.tts

import app.clothescast.core.data.diag.ApiCallEvent
import app.clothescast.core.data.diag.ApiCallLogger
import app.clothescast.core.data.diag.ApiEndpoints
import app.clothescast.core.data.diag.NoOpApiCallLogger
import app.clothescast.core.data.insight.GeminiCallPlanner
import app.clothescast.core.data.insight.GeminiEndpoint
import app.clothescast.core.data.insight.InvalidApiKeyException
import app.clothescast.core.data.insight.MissingApiKeyException
import app.clothescast.core.domain.model.TtsStyle
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale

// `gemini-3.8-flash-lite-tts` is Google's high-volume TTS model, and the
// named replacement for `gemini-2.5-flash-preview-tts`, which Google shuts
// down on 2026-11-17. `gemini-3.8-flash-tts` is the pricier creative tier.
// Standard-tier pricing as reported in November 2026 (Google's pricing page is
// the authority): $0.50/M text input + $6.00/M audio output tokens until
// 2026-12-31, then $1.00/M + $12.00/M.
// Audio is 25 tokens a second, so a ~5 s clip is ~125 output tokens: about
// $0.001 a call now and $0.002 from 2027, against ~$0.003 on the 2.5 model.
// The Gemini free tier covers low-volume BYOK use without billing at all.
// The proxy allowlists these ids (functions/src/models.ts); change both
// together, and check the names against the live API, not search results.
const val DEFAULT_GEMINI_TTS_MODEL: String = "gemini-3.8-flash-lite-tts"

// The full Flash TTS model, for the languages Flash-Lite doesn't list (see
// [geminiTtsModelFor]). $9.00/M audio output until 2026-12-31, then $18.00/M:
// dearer than Flash-Lite, still below the 2.5 model's $20.00/M.
const val GEMINI_TTS_FULL_MODEL: String = "gemini-3.8-flash-tts"

/**
 * Languages the app offers that Google's model page lists for
 * [GEMINI_TTS_FULL_MODEL] but not [DEFAULT_GEMINI_TTS_MODEL]: Flash-Lite covers
 * 101 languages, Flash 130. Thai was one of the 2.5 model's own 24.
 */
private val FULL_MODEL_LANGUAGES = setOf("fi", "lt", "sl", "sv", "sw", "th")

/** The TTS model for a briefing spoken in [locale]. */
fun geminiTtsModelFor(locale: Locale?): String =
    if (locale != null && modernLanguageCode(locale.language) in FULL_MODEL_LANGUAGES) {
        GEMINI_TTS_FULL_MODEL
    } else {
        DEFAULT_GEMINI_TTS_MODEL
    }

/** Names the model for the shared proxy, which reads it here rather than from the path. */
internal const val GEMINI_MODEL_HEADER = "X-Gemini-Model"
// Despina chosen via the post-#353 evals: clears the user's ≥8 bar across
// en-GB, en-AU (B3, four rolls all ≥8), en-US, de, de-AT, de-CH. Reliable
// rather than dazzling — it's the most consistently-acceptable voice across
// the languages we ship. See docs/voice-evals.md → "Despina-as-default
// decision".
const val DEFAULT_GEMINI_TTS_VOICE: String = "Despina"

// Kept for the existing test that asserts BYOK requests still hit
// `generativelanguage.googleapis.com`. `GeminiEndpoint.Direct` is the
// production source of truth — these constants mirror it for tests
// that pre-date the per-call planner.
internal const val GEMINI_HOST = "generativelanguage.googleapis.com"
internal const val GEMINI_API_VERSION = "v1beta"

/**
 * Natural-language style instructions sent with every TTS request as the turn's
 * `speechMetadata.style`. Gemini 3.8 TTS reads the text part verbatim, so these
 * must never be joined onto the text: there they would be spoken aloud. (The
 * 2.5 model treated a preamble in the text as direction, which is how these
 * were written and evaluated.)
 *
 * English briefings in the WEATHER_FORECASTER style use a short, delivery-only
 * line instead ([GEMINI_TTS_STYLE_FORECASTER_EN_US], [GEMINI_TTS_STYLE_FORECASTER_EN]),
 * with the accent carried by the request's `languageCode`: see
 * [englishForecasterStyleFor]. [GEMINI_TTS_STYLE_DIRECTIVE_WEATHER_FORECASTER]
 * is the register every other language still gets — national-news broadcast
 * delivery, deliberate cadence, sentence-final lift, gentle emphasis on
 * clothing advice — chosen on 2.5 via listening eval over a neutral "studio
 * voice" and a newsreader variant, and not yet re-auditioned on 3.8.
 *
 * Below the defaults are character / persona registers (pirate, cowboy, etc.).
 * Each one starts with "Read the following…" so the model treats it as
 * delivery direction rather than a rewrite, permits brief in-character
 * interjections, and ends with the standard "no audio effects, background
 * noise, or vinyl-style texture" trailer — important for personas like
 * PIRATE that might otherwise tempt the model to add ocean ambience.
 *
 * The leprechaun, Father Christmas, the New Year's Eve host and the pirate ask
 * for the over-the-top film or cartoon character and say it isn't an ordinary
 * speaker: asked politely, Gemini read the leprechaun as a normal Irish voice.
 * They end on the forecast itself: a holiday greeting opens the briefing,
 * but a trailing laugh or catchphrase makes the forecast harder to remember.
 * The vampire's closing laugh is the one exception, kept on purpose. The King, the sci-fi narrator and the
 * president scored better as they were (docs/voice-evals.md → "Holiday
 * personas (2026-11)").
 */
/**
 * The en-US forecaster, chosen by ear on Gemini 3.8 (docs/voice-evals.md →
 * "Gemini 3.8 English forecaster (2026-11)"): the calm newsreader line the
 * other English locales take sounded numb in American English, and this one
 * human. Its "anchor" and "viewers" are American cues, which is the point here
 * and why en-GB must not get it: one en-GB roll came out American.
 */
internal const val GEMINI_TTS_STYLE_FORECASTER_EN_US: String =
    "A warm, engaged evening-news weather anchor talking to viewers they like: natural " +
        "and conversational, unhurried and clear, with real interest in the forecast. " +
        "No audio effects or background noise."

/**
 * Every other English forecaster (en-GB, en-AU and the rest), chosen by ear on
 * Gemini 3.8: warm but composed, with no accent words at all, since every
 * accent phrase tried pulled somewhere ("General" toward American, "educated"
 * toward British, "ABC News" toward the US network). The request's
 * `languageCode` says which English it is.
 */
internal const val GEMINI_TTS_STYLE_FORECASTER_EN: String =
    "A warm but composed weather presenter on the national news, speaking to people " +
        "they like: natural and unhurried, clear, with quiet interest in the forecast. " +
        "No audio effects or background noise."

internal const val GEMINI_TTS_STYLE_DIRECTIVE_WEATHER_FORECASTER: String =
    "Read the following weather forecast in the style of a weather report on a " +
        "national news service. Enunciate clearly and use a measured speed. " +
        "Accentuate the ends of sentences and give a gentle emphasis to clothing " +
        "recommendations. No audio effects, background noise, or vinyl-style " +
        "texture.\n\n"

internal const val GEMINI_TTS_STYLE_DIRECTIVE_PIRATE: String =
    "Read the following as a gloriously over-the-top, swaggering pirate captain " +
        "from a swashbuckling adventure film — a gravelly, salt-cured growl in a " +
        "thick West Country accent, with rolling, growled Rs and booming, " +
        "roguish relish, as if the forecast were treasure-map directions. Not " +
        "an ordinary sailor. You may add brief in-character exclamations such " +
        "as 'Arrr' or 'Aye, me hearties', but end on the forecast itself, with " +
        "nothing added after it. No audio effects, background noise, or " +
        "vinyl-style texture.\n\n"

internal const val GEMINI_TTS_STYLE_DIRECTIVE_COWBOY: String =
    "Read the following in a slow, friendly American Old West drawl. You may " +
        "add brief in-character exclamations such as 'Howdy' or 'Well, partner'. " +
        "No audio effects, background noise, or vinyl-style texture.\n\n"

internal const val GEMINI_TTS_STYLE_DIRECTIVE_SCIFI_NARRATOR: String =
    "Read the following as a dry, witty British science-fiction narrator — the " +
        "wry, deadpan voice of an omniscient interstellar guidebook, delivering " +
        "everyday facts with cosmic grandeur and a knowing twinkle. No audio " +
        "effects, background noise, or vinyl-style texture.\n\n"

internal const val GEMINI_TTS_STYLE_DIRECTIVE_FATHER_CHRISTMAS: String =
    "Read the following as a gloriously over-the-top, jolly Father Christmas " +
        "from a classic Christmas film — not an ordinary friendly man, but the " +
        "big, round, rosy-cheeked Santa himself: a deep, rich, booming voice, " +
        "warm and slow, bubbling with chuckles and twinkly delight, as if " +
        "reading the forecast to children on his knee. Lean into the cheer on " +
        "every cosy word. You may add brief in-character touches such as " +
        "'Well, well, well', but end on the forecast itself, with nothing added " +
        "after it. No audio effects, background noise, or vinyl-style " +
        "texture.\n\n"

internal const val GEMINI_TTS_STYLE_DIRECTIVE_VAMPIRE: String =
    "Read the following as a gloriously over-the-top vampire count from an old " +
        "black-and-white horror film — a thick, exaggerated Transylvanian accent " +
        "with heavily rolled Rs and drawn-out vowels, dripping with theatrical " +
        "menace and aristocratic relish, as if the weather were a delicious dark " +
        "secret. Linger dramatically on ominous words. You may add brief " +
        "in-character touches such as 'Goood eeevening', and end with a slow, " +
        "sinister laugh. No audio effects, background noise, or vinyl-style " +
        "texture.\n\n"

internal const val GEMINI_TTS_STYLE_DIRECTIVE_NEW_YEARS_HOST: String =
    "Read the following as a gloriously over-the-top New Year's Eve party host " +
        "on stage in front of a cheering crowd, moments before midnight — loud, " +
        "breathless and giddy, every fact a reason to celebrate, the energy " +
        "climbing with each sentence as if the countdown were about to start. " +
        "Not a calm presenter. You may add brief in-character touches such as " +
        "'Here we go, everybody!', but end on the forecast itself, with nothing " +
        "added after it. No audio effects, background noise, or vinyl-style " +
        "texture.\n\n"

/**
 * The St Patrick's Day persona. "A lilting Irish brogue" alone came out as an
 * ordinary Irish speaker, so like the vampire this asks for the cartoon: a
 * tiny, cheeky fairy-tale creature, pitched up and quick, with an exaggerated
 * brogue. It ends on the forecast itself: a trailing giggle would make the
 * forecast harder to remember.
 */
internal const val GEMINI_TTS_STYLE_DIRECTIVE_LEPRECHAUN: String =
    "Read the following as a gloriously over-the-top, mischievous leprechaun " +
        "from a children's cartoon — a tiny, impish fairy-tale trickster, not " +
        "an ordinary Irish person. Use a thick, exaggerated stage-Irish brogue, " +
        "a higher, squeaky little voice, and a quick, bouncy, sing-song rhythm " +
        "that skips up and down, as if letting the listener in on a secret " +
        "about a pot of gold. Sound gleeful and cheeky throughout. You may add " +
        "brief in-character touches such as 'Top o' the mornin'' or " +
        "'Begorrah', but end on the forecast itself, with nothing added after " +
        "it. No audio effects, background noise, or vinyl-style texture.\n\n"

internal const val GEMINI_TTS_STYLE_DIRECTIVE_KING: String =
    "Read the following in the style of King Charles III delivering a formal " +
        "royal address — measured, dignified Received Pronunciation, warm yet " +
        "reserved, with a gentle gravitas at the close of each sentence. No " +
        "audio effects, background noise, or vinyl-style texture.\n\n"

internal const val GEMINI_TTS_STYLE_DIRECTIVE_QUEEN: String =
    "Read the following in the style of Queen Elizabeth II giving her Christmas " +
        "broadcast — crisp, precise Received Pronunciation, poised and gracious, " +
        "with quiet warmth. No audio effects, background noise, or vinyl-style " +
        "texture.\n\n"

internal const val GEMINI_TTS_STYLE_DIRECTIVE_PRESIDENT: String =
    "Read the following in the style of a revered American president addressing " +
        "the nation — the solemn oratory of George Washington or Abraham " +
        "Lincoln, stately and resolute, with weighty pauses and quiet " +
        "conviction. No audio effects, background noise, or vinyl-style " +
        "texture.\n\n"

internal const val GEMINI_TTS_STYLE_DIRECTIVE_STADIUM_ANNOUNCER: String =
    "Read the following as a booming stadium announcer — big voice, dramatic " +
        "pauses, each fact delivered like a headline moment. Brief interjections " +
        "such as 'Ladies and gentlemen' are fine. No audio effects, background " +
        "noise, or vinyl-style texture.\n\n"

internal const val GEMINI_TTS_STYLE_DIRECTIVE_STORYTELLER: String =
    "Read the following as a warm, engaging storyteller drawing the listener " +
        "in — unhurried pacing, a hint of wonder, gentle rises on important " +
        "details. No audio effects, background noise, or vinyl-style texture.\n\n"

internal const val GEMINI_TTS_STYLE_DIRECTIVE_FITNESS_INSTRUCTOR: String =
    "Read the following as an upbeat fitness instructor — positive, motivating, " +
        "crisp diction, energy that makes you want to move. Brief encouraging " +
        "interjections such as 'Let\\'s go!' are fine. No audio effects, " +
        "background noise, or vinyl-style texture.\n\n"

internal const val GEMINI_TTS_STYLE_DIRECTIVE_MORNING_PRESENTER: String =
    "Read the following as a cheerful morning radio presenter starting the " +
        "day — bright, friendly, flowing delivery, the kind of voice that " +
        "makes getting up worth it. Brief warm interjections are fine. No " +
        "audio effects, background noise, or vinyl-style texture.\n\n"

internal const val GEMINI_TTS_STYLE_DIRECTIVE_SCIENCE_TEACHER: String =
    "Read the following as an enthusiastic high school science teacher " +
        "walking the class through today's weather reasoning — clear, " +
        "curious, mildly excitable about the physics. No audio effects, " +
        "background noise, or vinyl-style texture.\n\n"

internal const val GEMINI_TTS_STYLE_DIRECTIVE_HISTORIAN: String =
    "Read the following as the narrator of a serious history documentary — " +
        "measured, gravely interested, every sentence matters. No audio " +
        "effects, background noise, or vinyl-style texture.\n\n"

internal const val GEMINI_TTS_STYLE_DIRECTIVE_SPORTSCASTER: String =
    "Read the following as an animated sportscaster calling a play — " +
        "building energy, crisp emphasis on the key facts. Brief interjections " +
        "are fine. No audio effects, background noise, or vinyl-style " +
        "texture.\n\n"

/**
 * A voice a persona always speaks in, overriding the user's pick, or null to
 * keep it. The vampire is pinned to Algenib, the top-rated voice in
 * scripts/compare-persona-voices.py's rounds. Charon, the first male voice
 * the holiday switch would otherwise take, read the count less convincingly.
 * The president is pinned the other way: Charon beat Algenib with both the
 * current prompt and a grander one (docs/voice-evals.md → "Holiday personas
 * (2026-11)"), so a user who picked Algenib or another male voice still hears
 * Charon. Father Christmas is pinned to Algenib, which beat Charon and
 * Enceladus with all three Santa prompts tried.
 */
internal fun pinnedVoiceFor(style: TtsStyle): String? = when (style) {
    TtsStyle.VAMPIRE -> "Algenib"
    TtsStyle.FATHER_CHRISTMAS -> "Algenib"
    TtsStyle.PRESIDENT -> "Charon"
    else -> null
}

internal fun styleDirectiveFor(style: TtsStyle): String = when (style) {
    TtsStyle.WEATHER_FORECASTER -> GEMINI_TTS_STYLE_DIRECTIVE_WEATHER_FORECASTER
    TtsStyle.PIRATE -> GEMINI_TTS_STYLE_DIRECTIVE_PIRATE
    TtsStyle.COWBOY -> GEMINI_TTS_STYLE_DIRECTIVE_COWBOY
    TtsStyle.SCIFI_NARRATOR -> GEMINI_TTS_STYLE_DIRECTIVE_SCIFI_NARRATOR
    TtsStyle.SCIENCE_TEACHER -> GEMINI_TTS_STYLE_DIRECTIVE_SCIENCE_TEACHER
    TtsStyle.HISTORIAN -> GEMINI_TTS_STYLE_DIRECTIVE_HISTORIAN
    TtsStyle.SPORTSCASTER -> GEMINI_TTS_STYLE_DIRECTIVE_SPORTSCASTER
    TtsStyle.STADIUM_ANNOUNCER -> GEMINI_TTS_STYLE_DIRECTIVE_STADIUM_ANNOUNCER
    TtsStyle.STORYTELLER -> GEMINI_TTS_STYLE_DIRECTIVE_STORYTELLER
    TtsStyle.FITNESS_INSTRUCTOR -> GEMINI_TTS_STYLE_DIRECTIVE_FITNESS_INSTRUCTOR
    TtsStyle.MORNING_PRESENTER -> GEMINI_TTS_STYLE_DIRECTIVE_MORNING_PRESENTER
    TtsStyle.FATHER_CHRISTMAS -> GEMINI_TTS_STYLE_DIRECTIVE_FATHER_CHRISTMAS
    TtsStyle.VAMPIRE -> GEMINI_TTS_STYLE_DIRECTIVE_VAMPIRE
    TtsStyle.NEW_YEARS_HOST -> GEMINI_TTS_STYLE_DIRECTIVE_NEW_YEARS_HOST
    TtsStyle.LEPRECHAUN -> GEMINI_TTS_STYLE_DIRECTIVE_LEPRECHAUN
    TtsStyle.KING -> GEMINI_TTS_STYLE_DIRECTIVE_KING
    TtsStyle.QUEEN -> GEMINI_TTS_STYLE_DIRECTIVE_QUEEN
    TtsStyle.PRESIDENT -> GEMINI_TTS_STYLE_DIRECTIVE_PRESIDENT
}

/**
 * The English forecaster's style line, or null for anything else: [locale] is
 * English and [style] is WEATHER_FORECASTER. American English takes
 * [GEMINI_TTS_STYLE_FORECASTER_EN_US]; every other English, or English with no
 * region, takes [GEMINI_TTS_STYLE_FORECASTER_EN]. Personas keep their own
 * directives, and other languages the long forecaster directive and accent line.
 */
internal fun englishForecasterStyleFor(style: TtsStyle, locale: Locale?): String? {
    if (style != TtsStyle.WEATHER_FORECASTER || locale?.language != "en") return null
    return if (locale.country == "US") GEMINI_TTS_STYLE_FORECASTER_EN_US else GEMINI_TTS_STYLE_FORECASTER_EN
}

/**
 * The `speechConfig.languageCode` to send, or null to send none: only the
 * English forecaster sends one, as that's what was listened to; with it the
 * accent holds without accent words in the style. Built from the language and
 * region alone, since Android hands over extensions such as `-u-mu-celsius`
 * that say nothing about speech. Gemini accepted every tag the app offers, and
 * `en-001` and `en_AU`-style ones, refusing only made-up languages.
 */
internal fun speechLanguageCodeFor(style: TtsStyle, locale: Locale?): String? {
    if (englishForecasterStyleFor(style, locale) == null) return null
    val country = locale?.country.orEmpty()
    return if (country.isEmpty()) "en" else "en-$country"
}

/**
 * Returns a bare language hint ("Speak in British English.") for character /
 * persona TTS styles. Unlike [geminiAccentDirectiveFor] this carries no
 * register, accent, or clarity words that could conflict with the persona.
 * Returns null for locales where the model's default (North American English)
 * is acceptable.
 */
internal fun geminiLanguageDirectiveFor(locale: Locale): String? {
    val language = modernLanguageCode(locale.language)
    val country = locale.country
    if (country.isNotEmpty()) {
        LANGUAGE_DIRECTIVES["$language-$country"]?.let { return it }
    }
    return LANGUAGE_DIRECTIVES[language]
}

/**
 * On Android, [Locale.getLanguage] returns the *legacy* ISO-639 codes for a
 * handful of languages ("iw" for Hebrew, "in" for Indonesian, "ji" for
 * Yiddish) — even when the locale was built from a modern BCP-47 tag like
 * "he-IL". (JVMs since JDK 17 default to the modern codes, so unit tests
 * can't reproduce the device behavior — hence this string-level seam.) The
 * directive tables key on the modern codes, so normalize before lookup or
 * Hebrew/Indonesian voices silently lose their language steering.
 */
internal fun modernLanguageCode(language: String): String = when (language) {
    "iw" -> "he"
    "in" -> "id"
    "ji" -> "yi"
    else -> language
}

private val LANGUAGE_DIRECTIVES: Map<String, String> = mapOf(
    "en-GB" to "Speak in British English.",
    "en-AU" to "Speak in Australian English.",
    "en-CA" to "Speak in Canadian English.",
    "en-ZA" to "Speak in South African English.",
    "de" to "Speak in German.",
    "de-AT" to "Speak in Austrian German.",
    "de-CH" to "Speak in Swiss German.",
    "fr" to "Speak in French.",
    "fr-CA" to "Speak in Canadian French.",
    "it" to "Speak in Italian.",
    "es" to "Speak in Spanish.",
    "es-ES" to "Speak in Spanish.",
    "es-MX" to "Speak in Mexican Spanish.",
    "ca" to "Speak in Catalan.",
    "ru" to "Speak in Russian.",
    "pl" to "Speak in Polish.",
    "hr" to "Speak in Croatian.",
    "sl" to "Speak in Slovenian.",
    "sr" to "Speak in Serbian.",
    "bg" to "Speak in Bulgarian.",
    "cs" to "Speak in Czech.",
    "sk" to "Speak in Slovak.",
    "hu" to "Speak in Hungarian.",
    "ro" to "Speak in Romanian.",
    "el" to "Speak in Greek.",
    "uk" to "Speak in Ukrainian.",
    "pt-BR" to "Speak in Brazilian Portuguese.",
    "pt-PT" to "Speak in European Portuguese.",
    "pt" to "Speak in Portuguese.",
    "nl" to "Speak in Dutch.",
    "sv" to "Speak in Swedish.",
    "da" to "Speak in Danish.",
    "nb" to "Speak in Norwegian.",
    "fi" to "Speak in Finnish.",
    "et" to "Speak in Estonian.",
    "lv" to "Speak in Latvian.",
    "lt" to "Speak in Lithuanian.",
    "tr" to "Speak in Turkish.",
    "id" to "Speak in Indonesian.",
    "ms" to "Speak in Malay.",
    "fil" to "Speak in Filipino.",
    "sw" to "Speak in Swahili.",
    "vi" to "Speak in Vietnamese.",
    "th" to "Speak in Thai.",
    "zh-CN" to "Speak in Mandarin Chinese.",
    "zh-TW" to "Speak in Mandarin Chinese.",
    "zh" to "Speak in Mandarin Chinese.",
    "hi" to "Speak in Hindi.",
    "bn" to "Speak in Bengali.",
    "ja" to "Speak in Japanese.",
    "ko" to "Speak in Korean.",
    "ar" to "Speak in Arabic.",
    "he" to "Speak in Hebrew.",
    "fa" to "Speak in Persian.",
)

/**
 * Returns a one-line accent / language instruction for Gemini's TTS prompt, or
 * null when the locale has no entry in [ACCENT_DIRECTIVES] (in which case the
 * model's default — North American English — applies).
 *
 * Gemini's prebuilt voices are language-agnostic and accept natural-language
 * accent steering. The instruction rides in `speechMetadata.style` after the
 * chosen style directive (see [styleDirectiveFor]), never in the spoken text.
 *
 * Lookup is two-step: an exact `language-COUNTRY` match wins (so variants like
 * de-AT / de-CH can carry their own directive), otherwise a language-only key.
 * The table mixes both shapes — most entries are language-only (`de`, `fr`,
 * `vi`, …), while German, French, Spanish, Portuguese and Arabic enumerate
 * per-country variants. Adding a new locale is a one-line entry in
 * [ACCENT_DIRECTIVES]. English has none: the English forecaster sends a
 * `languageCode` instead (see [speechLanguageCodeFor]).
 */
internal fun geminiAccentDirectiveFor(locale: Locale): String? {
    val language = modernLanguageCode(locale.language)
    val country = locale.country
    if (country.isNotEmpty()) {
        ACCENT_DIRECTIVES["$language-$country"]?.let { return it }
    }
    return ACCENT_DIRECTIVES[language]
}

// Each entry names the language and (where meaningful) a regional variety.
// Clarity / naturalness adjectives that used to live here ("clear and
// natural", "klaren", "claro", etc.) were stripped after the en-GB
// listening eval (docs/voice-evals.md → "Accent clarity-trim eval, 2026-05")
// showed they duplicate the WEATHER_FORECASTER directive's "Enunciate
// clearly" clause and actively destabilise some voices toward
// over-articulation. Where the only thing left after the trim was a
// tautology (e.g. "Italian with an Italian accent"), the entry collapses
// to a bare language directive.
private val ACCENT_DIRECTIVES: Map<String, String> = mapOf(
    // Language-only fallback for Standard German (de-DE) and any de-* variant
    // not explicitly enumerated. Austrian (de-AT) and Swiss German (de-CH) have
    // their own entries below so the audio sounds like those regions rather than
    // the Hochdeutsch that the language-only fallback would give them.
    "de" to "Sprich auf Deutsch in einem hochdeutschen Akzent.",
    "de-AT" to "Sprich auf Deutsch mit einem österreichischen Akzent.",
    "de-CH" to "Sprich auf Deutsch mit einem deutschschweizerischen Akzent.",
    // Language-only fallback for bare `fr` (an app locale) and any fr-*
    // variant not enumerated (fr-CH, fr-BE, …); France and Québec keep
    // their own regional entries below.
    "fr" to "Parle en français.",
    "fr-FR" to "Parle en français de France avec un accent parisien.",
    "fr-CA" to "Parle en français québécois avec un accent canadien-français.",
    "it" to "Parla in italiano.",
    // Language-only fallback for bare `es` (an app locale) and any es-*
    // variant not enumerated (es-AR, es-US, …); Spain and Mexico keep
    // their own regional entries below.
    "es" to "Habla en español.",
    "es-ES" to "Habla en español de España con un acento castellano.",
    "es-MX" to "Habla en español mexicano con un acento neutro.",
    "ca" to "Parla en català.",
    // Russian / Ukrainian keep "литературным" / "літературним" — that's a
    // register descriptor (Standard / literary register, the broadcast norm),
    // not a clarity adjective.
    "ru" to "Говори по-русски с литературным произношением.",
    "pl" to "Mów po polsku.",
    "hr" to "Govori na hrvatskom jeziku.",
    "sl" to "Govori v slovenščini.",
    "sr" to "Govori srpskim jezikom.",
    "bg" to "Говори на български.",
    "cs" to "Mluvte česky.",
    "sk" to "Hovorte po slovensky.",
    "hu" to "Beszélj magyarul.",
    "ro" to "Vorbiți în română.",
    "el" to "Μίλα στα ελληνικά.",
    "uk" to "Говори українською мовою з літературним вимовленням.",
    "pt-BR" to "Fale em português brasileiro com sotaque neutro.",
    "pt-PT" to "Fale em português europeu com um sotaque de Portugal.",
    // Language-only fallback covers any pt-* variant not explicitly listed.
    "pt" to "Fale em português.",
    "nl" to "Spreek in het Nederlands.",
    "sv" to "Tala på svenska.",
    "da" to "Tal på dansk.",
    "nb" to "Snakk på norsk bokmål.",
    "fi" to "Puhu suomea.",
    "et" to "Räägi eesti keelt.",
    "lv" to "Runā latviski.",
    "lt" to "Kalbėk lietuviškai.",
    "tr" to "Türkçe konuş.",
    "id" to "Bicara dalam bahasa Indonesia.",
    "ms" to "Bertutur dalam bahasa Melayu.",
    "fil" to "Magsalita sa Filipino.",
    // Swahili keeps "fasaha" — an "eloquent / standard" register descriptor,
    // analogous to Russian's "литературным".
    "sw" to "Soma maandishi yafuatayo kwa Kiswahili fasaha.",
    "vi" to "Nói tiếng Việt.",
    "th" to "กรุณาอ่านเป็นภาษาไทย",
    // Mandarin keeps "标准 / 標準" (Standard) — Putonghua / Guoyu vary across
    // the Chinese-speaking world and the standard register is what news uses.
    "zh-CN" to "请用标准普通话朗读。",
    "zh-TW" to "請用標準國語朗讀。",
    // Language-only fallback covers generic zh locale and any zh-* variant
    // not explicitly listed (zh-HK, etc.) — all are Mandarin-readable.
    "zh" to "请用标准普通话朗读。",
    "hi" to "कृपया हिंदी में पढ़ें।",
    "bn" to "অনুগ্রহ করে বাংলায় পড়ুন।",
    "ja" to "日本語で読んでください。",
    "ko" to "한국어로 읽어주세요。",
    // Arabic: directives nudge the model to read MSA prose with the named
    // country's accent (Egyptian / Saudi / Emirati / Moroccan), not to
    // code-switch to the colloquial dialect — the :app formatter produces
    // MSA, so a code-switch instruction would mismatch the input. The bare
    // `ar` entry stays as the fallback for any future ar-* locale we don't
    // explicitly enumerate. Whether Gemini's audio model reliably produces
    // a recognisably-different accent per country for Arabic is empirical
    // and worth a listening test (it does so well for English / Spanish /
    // Mandarin variants; community reports for Arabic are mixed). Worst
    // case it ignores the country qualifier and we get generic MSA — same
    // as before the split, no regression.
    //
    // "الفصحى" (al-Fuṣḥā = MSA) stays — that's the register the formatter
    // produces, not a clarity adjective.
    "ar-SA" to "اقرأ النص التالي بالعربية الفصحى بنطق سعودي.",
    "ar-EG" to "اقرأ النص التالي بالعربية الفصحى بنطق مصري.",
    "ar-AE" to "اقرأ النص التالي بالعربية الفصحى بنطق إماراتي.",
    "ar-MA" to "اقرأ النص التالي بالعربية الفصحى بنطق مغربي.",
    "ar" to "اقرأ النص التالي بالعربية.",
    // Hebrew / Persian remain language-only — only he-IL / fa-IR are offered
    // in the picker, so there's nothing to disambiguate.
    "he" to "קרא/י את הטקסט הבא בעברית.",
    "fa" to "متن زیر را به فارسی بخوانید.",
)

/**
 * Read timeout for a TTS request. generateContent returns nothing until the
 * whole clip is synthesized, and a slow, drawn-out persona (the Halloween
 * vampire) can take longer than OkHttp's 10 s default, which timed out every
 * attempt and fell back to the device voice.
 */
internal const val TTS_PURPOSE_HEADER = "X-TTS-Purpose"

internal const val GEMINI_TTS_SOCKET_TIMEOUT_MS: Long = 45_000L

/**
 * Calls Gemini's audio-output model, [geminiTtsModelFor] the briefing's locale.
 * The target host and request-time auth headers come from [callPlanner] — `:app`'s
 * planner picks between the developer's Cloud Function proxy (default) and the
 * direct Generative Language host (when the user has set their own key).
 *
 * The request carries only the words to speak as text; delivery direction goes
 * in `speechMetadata.style`, since Gemini 3.8 TTS speaks its text verbatim.
 *
 * Gemini 3.8 returns a complete WAV file (`audio/wav`), which [WavDecoder]
 * unwraps to 16-bit mono PCM at the file's own sample rate. Headerless PCM
 * (`audio/L16;codec=pcm;rate=24000`, what earlier models sent) is still
 * accepted, with the rate read from the `mimeType`. Either way the caller can
 * hand [PcmAudio.bytes] straight to AudioTrack.
 *
 * Despite the `audio/L16` mime label (which per RFC 2586 specifies network byte
 * order), Gemini's payload is already little-endian — the same encoding Android's
 * `ENCODING_PCM_16BIT` consumes. A previous attempt to byte-swap on decode was
 * reverted because it turned correctly-decoded speech into noise.
 *
 * Default voice is [DEFAULT_GEMINI_TTS_VOICE]. Other prebuilt voices are listed
 * in Google's docs; pass via [voiceName] when calling.
 */
class GeminiTtsClient(
    private val httpClient: HttpClient,
    private val callPlanner: GeminiCallPlanner,
    private val apiCallLogger: ApiCallLogger = NoOpApiCallLogger,
) {
    suspend fun synthesize(
        text: String,
        voiceName: String = DEFAULT_GEMINI_TTS_VOICE,
        locale: Locale? = null,
        style: TtsStyle = TtsStyle.WEATHER_FORECASTER,
        preview: Boolean = false,
    ): PcmAudio {
        val plan = callPlanner.plan()
        val model = geminiTtsModelFor(locale)

        // English in the forecaster style: a short delivery-only line, with
        // the accent in languageCode. Otherwise WEATHER_FORECASTER gets the
        // full accent directive ("Sprich auf Deutsch …"), and character styles
        // only a bare language hint ("Speak in British English.") so accent
        // words don't conflict with the persona.
        val languageCode = speechLanguageCodeFor(style, locale)
        val deliveryStyle = englishForecasterStyleFor(style, locale) ?: run {
            val accent = if (style == TtsStyle.WEATHER_FORECASTER) {
                locale?.let { geminiAccentDirectiveFor(it) }
            } else {
                locale?.let { geminiLanguageDirectiveFor(it) }
            }
            buildString {
                append(styleDirectiveFor(style).trim())
                if (accent != null) {
                    append("\n\n")
                    append(accent)
                }
            }
        }

        // Hand-rolled (not via core.data.diag.instrument) because Gemini doesn't use
        // Ktor's expectSuccess — a 4xx surfaces as a success HttpResponse with a JSON
        // error envelope that we re-throw as GeminiTtsHttpException further down. We
        // want the real HTTP status logged either way (rate-limit 429s, auth 403s),
        // so capture status immediately after post() and emit the event before the
        // isSuccess() check.
        val started = System.currentTimeMillis()
        val httpResponse: HttpResponse = try {
            httpClient.post {
                url {
                    protocol = URLProtocol.HTTPS
                    host = plan.endpoint.host
                    path(plan.endpoint.apiVersion, "models", "$model:generateContent")
                }
                timeout { socketTimeoutMillis = GEMINI_TTS_SOCKET_TIMEOUT_MS }
                plan.applyAuth(this)
                // The shared proxy counts Settings previews in their own daily
                // bucket. Google's own endpoint doesn't know the header, so a
                // BYOK call never sends it.
                if (plan.endpoint != GeminiEndpoint.Direct) {
                    headers.append(GEMINI_MODEL_HEADER, model)
                }
                if (preview && plan.endpoint != GeminiEndpoint.Direct) {
                    headers.append(TTS_PURPOSE_HEADER, "preview")
                }
                contentType(ContentType.Application.Json)
                setBody(
                    TtsRequest(
                        contents = listOf(
                            TtsContent(
                                parts = listOf(
                                    TtsTextPart(
                                        text = text,
                                        speechMetadata = SpeechMetadata(style = deliveryStyle),
                                    ),
                                ),
                            ),
                        ),
                        generationConfig = TtsGenerationConfig(
                            responseModalities = listOf("AUDIO"),
                            speechConfig = SpeechConfig(
                                voiceConfig = VoiceConfig(
                                    prebuiltVoiceConfig = PrebuiltVoiceConfig(pinnedVoiceFor(style) ?: voiceName),
                                ),
                                languageCode = languageCode,
                            ),
                        ),
                    ),
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            apiCallLogger.log(
                ApiCallEvent(
                    endpoint = ApiEndpoints.GEMINI_TTS,
                    httpStatus = null,
                    errorClass = t.javaClass.simpleName,
                    latencyMs = System.currentTimeMillis() - started,
                ),
            )
            throw t
        }
        apiCallLogger.log(
            ApiCallEvent(
                endpoint = ApiEndpoints.GEMINI_TTS,
                httpStatus = httpResponse.status.value,
                errorClass = null,
                latencyMs = System.currentTimeMillis() - started,
            ),
        )

        // Without an explicit status check we'd quietly deserialize a 4xx error body
        // (e.g. {"error": {"code": 403, "message": "..."}}) as a TtsResponse with
        // default empty `candidates`, hiding the actual reason behind the generic
        // empty-response exception. Pull the body on a non-success status and
        // surface it.
        if (!httpResponse.status.isSuccess()) {
            val body = httpResponse.bodyAsBytes()
            // 429 from the shared-key proxy carries a structured
            // `{"error":"daily_quota_exhausted", "limit": N, "resetAtUtc": "…"}`
            // body distinct from Gemini's `{"error":{"message":…}}` envelope.
            // Surface it as a typed exception so the diagnostic Toast in
            // VoiceSettings shows a friendly "free TTS limit reached today"
            // message instead of the raw "daily_quota_exhausted" token, and
            // callers can match on the type for future Settings nudges.
            if (httpResponse.status == HttpStatusCode.TooManyRequests) {
                parseDailyQuotaExhausted(body)?.let { throw it }
            }
            throw GeminiTtsHttpException(httpResponse.status, body)
        }

        val response: TtsResponse = httpResponse.body()

        response.promptFeedback?.blockReason?.let {
            throw GeminiTtsBlockedException("Gemini TTS prompt blocked: $it")
        }

        val candidate = response.candidates.firstOrNull()
        val inlineParts = candidate?.content?.parts?.mapNotNull { it.inlineData }.orEmpty()
        if (inlineParts.isEmpty()) throw GeminiTtsEmptyResponseException(candidate?.finishReason)

        // One part is the norm, but a response may split the clip across
        // several (each its own WAV on 3.8); keeping only the first would cut
        // the briefing short.
        val pieces = inlineParts.map { decodeAudio(Base64.getDecoder().decode(it.data), it.mimeType) }
        val sampleRate = pieces.first().sampleRate
        if (pieces.any { it.sampleRate != sampleRate }) {
            throw UnsupportedTtsAudioException("parts at different sample rates")
        }
        val speech = if (pieces.size == 1) {
            pieces.single()
        } else {
            val joined = ByteArrayOutputStream()
            pieces.forEach { joined.write(it.bytes) }
            PcmAudio(bytes = joined.toByteArray(), sampleRate = sampleRate)
        }
        return withIntroFor(style, speech)
    }

    private fun decodeAudio(audio: ByteArray, mimeType: String): PcmAudio {
        if (WavDecoder.isWav(audio)) return WavDecoder.decode(audio)
        // Labelled WAV but no RIFF header: playing it as PCM would be noise.
        if (mimeType.contains("wav", ignoreCase = true)) {
            throw UnsupportedTtsAudioException("$mimeType without a RIFF header")
        }
        val sampleRate = parseSampleRate(mimeType) ?: DEFAULT_SAMPLE_RATE_HZ
        return PcmAudio(bytes = audio, sampleRate = sampleRate)
    }

    private fun parseSampleRate(mimeType: String): Int? {
        // mimeType looks like "audio/L16;codec=pcm;rate=24000". Pull the rate token out
        // rather than assume — the model could change defaults.
        return mimeType.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("rate=") }
            ?.removePrefix("rate=")
            ?.toIntOrNull()
    }

    companion object {
        private const val DEFAULT_SAMPLE_RATE_HZ = 24_000
    }
}

/**
 * [speech] with its persona's musical intro in front, if it has one: the
 * vampire opens with [OrganSting], Father Christmas with [SleighBells].
 * Applied here, where the audio is made, so every way the briefing is heard
 * (the phone, Cast, the smart-home MQTT audio and video) gets the same intro.
 * Rendering and splicing run on [Dispatchers.Default] since callers may be on
 * the main thread.
 */
internal suspend fun withIntroFor(style: TtsStyle, speech: PcmAudio): PcmAudio {
    val intro: (Int) -> ByteArray = when (style) {
        TtsStyle.VAMPIRE -> OrganSting::pcm
        TtsStyle.FATHER_CHRISTMAS -> SleighBells::pcm
        else -> return speech
    }
    return withContext(Dispatchers.Default) {
        PcmAudio(bytes = intro(speech.sampleRate) + speech.bytes, sampleRate = speech.sampleRate)
    }
}

/** Decoded TTS audio: signed 16-bit PCM, mono, at [sampleRate] Hz. */
data class PcmAudio(val bytes: ByteArray, val sampleRate: Int) {
    override fun equals(other: Any?): Boolean =
        other is PcmAudio && bytes.contentEquals(other.bytes) && sampleRate == other.sampleRate

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + sampleRate
}

class GeminiTtsEmptyResponseException(finishReason: String?) :
    IllegalStateException(
        if (finishReason.isNullOrBlank()) {
            "Gemini TTS returned no inline-audio part"
        } else {
            "Gemini TTS returned no inline-audio part (finishReason=$finishReason)"
        },
    )

class GeminiTtsBlockedException(message: String) : IllegalStateException(message)

/**
 * HTTP failure surfaced with a short body excerpt so the diagnostic Toast in
 * Settings shows the actual reason (auth failure / quota / model unavailable /
 * deprecated preview model). Pulls just `error.message` out of Gemini's standard
 * error envelope; falls back to a truncated raw excerpt when the body isn't
 * shaped that way.
 */
/**
 * Surfaced when the shared-key proxy reports the install has used its full
 * daily allowance (HTTP 429 with `{"error":"daily_quota_exhausted", …}`).
 * The friendly [message] is what the Voice settings preview Toast shows;
 * [resetAtUtc] is the ISO-8601 timestamp at which the next slot opens, for
 * any UI that wants to render "available again at HH:MM your time".
 *
 * Callers that want to fall through to device TTS should treat this as the
 * same recoverable failure mode as other `GeminiTts*Exception`s — but the
 * type lets a future Settings banner identify the specific case and prompt
 * the user to add their own Gemini key.
 */
class GeminiTtsDailyQuotaExhaustedException(
    val limit: Int?,
    val resetAtUtc: String?,
) : IllegalStateException(
    "Free TTS limit reached for today. Add your own Gemini key in Settings " +
        "for unlimited use.",
)

private fun parseDailyQuotaExhausted(body: ByteArray): GeminiTtsDailyQuotaExhaustedException? {
    val raw = runCatching { body.toString(Charsets.UTF_8) }.getOrNull() ?: return null
    val root = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
    val error = (root["error"] as? JsonPrimitive)?.content ?: return null
    if (error != "daily_quota_exhausted") return null
    val limit = (root["limit"] as? JsonPrimitive)?.content?.toIntOrNull()
    val resetAtUtc = (root["resetAtUtc"] as? JsonPrimitive)?.content
    return GeminiTtsDailyQuotaExhaustedException(limit = limit, resetAtUtc = resetAtUtc)
}

/**
 * Classifies a Gemini TTS synthesis failure for the caller's retry decision.
 *
 * Transient failures — network timeouts, dropped connections, a 429 rate-limit
 * or a 5xx from the shared-key proxy, and the occasional empty-candidate model
 * hiccup — are worth another attempt before degrading to the device engine; the
 * user picked Gemini, so a single blip shouldn't downgrade the whole briefing.
 *
 * Failures that won't change on a retry return false so the caller falls back
 * immediately: the spent daily free-tier quota, a blocked prompt, a missing or
 * unreadable BYOK key (it won't reappear between attempts — and re-keying is
 * the user's call, not a backoff loop's), and a non-retryable HTTP 4xx
 * (auth / bad request). [CancellationException] is never retryable — it must
 * propagate to unwind structured concurrency rather than be caught and
 * retried.
 */
fun isRetryableGeminiTtsFailure(t: Throwable): Boolean = when (t) {
    is CancellationException -> false
    is GeminiTtsDailyQuotaExhaustedException -> false
    is GeminiTtsBlockedException -> false
    is UnsupportedTtsAudioException -> false
    is MissingApiKeyException -> false
    is InvalidApiKeyException -> false
    is GeminiTtsHttpException -> t.status.value == 429 || t.status.value in 500..599
    else -> true
}

class GeminiTtsHttpException(val status: HttpStatusCode, body: ByteArray) :
    IllegalStateException(buildMessage(status, body)) {

    companion object {
        private fun buildMessage(status: HttpStatusCode, body: ByteArray): String {
            val raw = runCatching { body.toString(Charsets.UTF_8) }.getOrNull().orEmpty()
            val excerpt = extractErrorMessage(raw)
                ?: raw.take(MAX_EXCERPT_CHARS).ifBlank { "(empty body)" }
            return "Gemini TTS HTTP ${status.value}: $excerpt"
        }

        private fun extractErrorMessage(body: String): String? = runCatching {
            val root = Json.parseToJsonElement(body) as? JsonObject
            val error = root?.get("error") as? JsonObject
            (error?.get("message") as? JsonPrimitive)?.content?.take(MAX_EXCERPT_CHARS)
        }.getOrNull()

        private const val MAX_EXCERPT_CHARS = 160
    }
}
