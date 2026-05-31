package app.clothescast.core.data.tts

import app.clothescast.core.data.diag.ApiCallEvent
import app.clothescast.core.data.diag.ApiCallLogger
import app.clothescast.core.data.diag.ApiEndpoints
import app.clothescast.core.data.diag.NoOpApiCallLogger
import app.clothescast.core.data.insight.GeminiCallPlanner
import app.clothescast.core.domain.model.TtsStyle
import io.ktor.client.HttpClient
import io.ktor.client.call.body
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64
import java.util.Locale

// `gemini-2.5-flash-preview-tts` is the audio-output variant of Flash 2.5
// (text-only Flash 2.5 is much cheaper but doesn't speak). Pricing as of
// April 2026: standard-tier pricing is $1.00/M text input tokens + $20.00/M
// audio output tokens; a typical ~100-char insight ≈ 25 input tokens + ~160
// audio output tokens for a ~5 s clip, which works out to ~$0.003 per call
// (~$0.20/month at two clips/day). The Gemini free tier covers low-volume
// BYOK use without billing at all. Note this model is still on the
// `-preview-` track — see CLAUDE.md's "Don't rename Gemini models from
// web-search guesses" before swapping it for a GA-sounding name.
const val DEFAULT_GEMINI_TTS_MODEL: String = "gemini-2.5-flash-preview-tts"
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
 * Natural-language style instructions prepended to every TTS request. Gemini
 * documents the input text as a style-steerable prompt — preambles like this
 * are interpreted as direction and not spoken back.
 *
 * [GEMINI_TTS_STYLE_DIRECTIVE_WEATHER_FORECASTER] is the default register —
 * national-news broadcast delivery, deliberate cadence, sentence-final lift,
 * gentle emphasis on clothing advice. Chosen via listening eval over a neutral
 * "studio voice" and a newsreader variant.
 *
 * [GEMINI_TTS_STYLE_DIRECTIVE_WEATHER_FORECASTER_REGISTER] is the same with
 * an extra "Use the language's standard variety, not a regional dialect"
 * sentence. Empirically rescues Iapetus on en-AU (5 → 9) and lifts other
 * voices that were below the user's quality bar; on en-GB it nudged voices
 * off-brand and is **not** used there. [directiveFor] picks the variant per
 * locale. See docs/voice-evals.md → "B3 register-in-directive eval".
 *
 * Below the defaults are character / persona registers (pirate, cowboy, etc.).
 * Each one starts with "Read the following…" so the model treats it as
 * delivery direction rather than a rewrite, permits brief in-character
 * interjections, and ends with the standard "no audio effects, background
 * noise, or vinyl-style texture" trailer — important for personas like
 * PIRATE that might otherwise tempt the model to add ocean ambience.
 */
internal const val GEMINI_TTS_STYLE_DIRECTIVE_WEATHER_FORECASTER: String =
    "Read the following weather forecast in the style of a weather report on a " +
        "national news service. Enunciate clearly and use a measured speed. " +
        "Accentuate the ends of sentences and give a gentle emphasis to clothing " +
        "recommendations. No audio effects, background noise, or vinyl-style " +
        "texture.\n\n"

internal const val GEMINI_TTS_STYLE_DIRECTIVE_WEATHER_FORECASTER_REGISTER: String =
    "Read the following weather forecast in the style of a weather report on a " +
        "national news service. Use the language's standard variety, not a " +
        "regional dialect. Enunciate clearly and use a measured speed. " +
        "Accentuate the ends of sentences and give a gentle emphasis to clothing " +
        "recommendations. No audio effects, background noise, or vinyl-style " +
        "texture.\n\n"

internal const val GEMINI_TTS_STYLE_DIRECTIVE_PIRATE: String =
    "Read the following as a swaggering pirate, with hearty nautical flair. " +
        "You may add brief in-character exclamations such as 'Arrr' or 'Aye, " +
        "matey'. No audio effects, background noise, or vinyl-style texture.\n\n"

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
    "Read the following as a jolly, warm-hearted Father Christmas — a deep, " +
        "booming, good-natured voice brimming with festive cheer. You may add " +
        "brief in-character exclamations such as 'Ho ho ho' or 'Merry " +
        "Christmas'. No audio effects, background noise, or vinyl-style " +
        "texture.\n\n"

internal const val GEMINI_TTS_STYLE_DIRECTIVE_SPOOKY_NARRATOR: String =
    "Read the following as a spooky Halloween narrator — hushed, eerie, and " +
        "theatrically sinister, savouring each ominous pause. Brief in-character " +
        "interjections such as a low, ghostly laugh are fine. No audio effects, " +
        "background noise, or vinyl-style texture.\n\n"

internal const val GEMINI_TTS_STYLE_DIRECTIVE_NEW_YEARS_HOST: String =
    "Read the following as an exuberant New Year's Eve party host counting down " +
        "to midnight — celebratory, building excitement, full of warmth and " +
        "anticipation. Brief in-character interjections such as 'Happy New " +
        "Year!' are fine. No audio effects, background noise, or vinyl-style " +
        "texture.\n\n"

internal const val GEMINI_TTS_STYLE_DIRECTIVE_LEPRECHAUN: String =
    "Read the following as a playful, mischievous leprechaun with a lilting " +
        "Irish brogue and a twinkle in the eye. You may add brief in-character " +
        "exclamations such as 'Top o' the morning'. No audio effects, " +
        "background noise, or vinyl-style texture.\n\n"

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
    TtsStyle.SPOOKY_NARRATOR -> GEMINI_TTS_STYLE_DIRECTIVE_SPOOKY_NARRATOR
    TtsStyle.NEW_YEARS_HOST -> GEMINI_TTS_STYLE_DIRECTIVE_NEW_YEARS_HOST
    TtsStyle.LEPRECHAUN -> GEMINI_TTS_STYLE_DIRECTIVE_LEPRECHAUN
    TtsStyle.KING -> GEMINI_TTS_STYLE_DIRECTIVE_KING
    TtsStyle.QUEEN -> GEMINI_TTS_STYLE_DIRECTIVE_QUEEN
    TtsStyle.PRESIDENT -> GEMINI_TTS_STYLE_DIRECTIVE_PRESIDENT
}

/**
 * Picks the right WEATHER_FORECASTER variant for [locale]. Most locales get
 * [GEMINI_TTS_STYLE_DIRECTIVE_WEATHER_FORECASTER] (B2 wording). en-AU gets
 * [GEMINI_TTS_STYLE_DIRECTIVE_WEATHER_FORECASTER_REGISTER] (B2 + the explicit
 * "standard variety, not regional dialect" sentence), which empirically
 * rescues Iapetus on en-AU from a near-degenerate 5/10 to 9/10 and lifts
 * other voices that were below the user's quality bar. The same change on
 * en-GB nudged voices off-brand, hence the per-locale routing rather than a
 * global directive update. See docs/voice-evals.md.
 *
 * Character styles (PIRATE, COWBOY, etc.) ignore the locale routing and use
 * the persona's directive verbatim — adding a register-direction sentence
 * would conflict with persona delivery.
 */
internal fun directiveFor(style: TtsStyle, locale: Locale?): String {
    if (style == TtsStyle.WEATHER_FORECASTER &&
        locale?.language == "en" && locale.country == "AU"
    ) {
        return GEMINI_TTS_STYLE_DIRECTIVE_WEATHER_FORECASTER_REGISTER
    }
    return styleDirectiveFor(style)
}

/**
 * Returns a bare language hint ("Speak in British English.") for character /
 * persona TTS styles. Unlike [geminiAccentDirectiveFor] this carries no
 * register, accent, or clarity words that could conflict with the persona.
 * Returns null for locales where the model's default (North American English)
 * is acceptable.
 */
internal fun geminiLanguageDirectiveFor(locale: Locale): String? {
    val country = locale.country
    if (country.isNotEmpty()) {
        LANGUAGE_DIRECTIVES["${locale.language}-$country"]?.let { return it }
    }
    return LANGUAGE_DIRECTIVES[locale.language]
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
 * accent steering in the same prompt that carries the spoken text. The
 * instruction is prepended to the user text alongside the chosen style
 * directive (see [styleDirectiveFor]) and is interpreted as direction, not
 * spoken back.
 *
 * Lookup is two-step: an exact `language-COUNTRY` match wins (so variants like
 * en-GB / en-AU / en-US can carry their own directive), otherwise a
 * language-only key. The table mixes both shapes — most non-English entries
 * are language-only (`de`, `fr`, `vi`, …), while English and Arabic
 * enumerate per-country variants. Adding a new locale is a one-line entry in
 * [ACCENT_DIRECTIVES].
 */
internal fun geminiAccentDirectiveFor(locale: Locale): String? {
    val country = locale.country
    if (country.isNotEmpty()) {
        ACCENT_DIRECTIVES["${locale.language}-$country"]?.let { return it }
    }
    return ACCENT_DIRECTIVES[locale.language]
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
    "en-GB" to "Speak with a Standard British accent.",
    // en-AU drops "General" — paired with the per-locale register sentence in
    // GEMINI_TTS_STYLE_DIRECTIVE_WEATHER_FORECASTER_REGISTER (selected by
    // directiveFor for en-AU only). The combination empirically rescues
    // Iapetus on en-AU; "General" was redundant once the directive carries
    // "standard variety, not regional dialect" for this locale. See
    // docs/voice-evals.md → "B3 register-in-directive eval".
    "en-AU" to "Speak with an Australian accent, not broad.",
    "en-US" to "Speak with a General American accent.",
    "en-CA" to "Speak with a Canadian English accent.",
    // Language-only fallback for Standard German (de-DE) and any de-* variant
    // not explicitly enumerated. Austrian (de-AT) and Swiss German (de-CH) have
    // their own entries below so the audio sounds like those regions rather than
    // the Hochdeutsch that the language-only fallback would give them.
    "de" to "Sprich auf Deutsch in einem hochdeutschen Akzent.",
    "de-AT" to "Sprich auf Deutsch mit einem österreichischen Akzent.",
    "de-CH" to "Sprich auf Deutsch mit einem deutschschweizerischen Akzent.",
    "fr-FR" to "Parle en français de France avec un accent parisien.",
    "fr-CA" to "Parle en français québécois avec un accent canadien-français.",
    "it" to "Parla in italiano.",
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
    "en-ZA" to "Speak with a South African English accent.",
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
 * Calls Gemini's audio-output model (e.g. `gemini-2.5-flash-preview-tts`). The
 * target host and request-time auth headers come from [callPlanner] — `:app`'s
 * planner picks between the developer's Cloud Function proxy (default) and the
 * direct Generative Language host (when the user has set their own key).
 *
 * The model returns a single 16-bit signed PCM audio stream at a sample rate carried
 * in the `mimeType` (`audio/L16;codec=pcm;rate=24000`). [PcmAudio.sampleRate] parses
 * that out of the response so the caller can hand the bytes straight to AudioTrack.
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
    private val model: String = DEFAULT_GEMINI_TTS_MODEL,
    private val apiCallLogger: ApiCallLogger = NoOpApiCallLogger,
) {
    suspend fun synthesize(
        text: String,
        voiceName: String = DEFAULT_GEMINI_TTS_VOICE,
        locale: Locale? = null,
        style: TtsStyle = TtsStyle.WEATHER_FORECASTER,
    ): PcmAudio {
        val plan = callPlanner.plan()

        // WEATHER_FORECASTER gets the full accent directive ("Speak with a
        // Standard British accent."). Character styles get only a bare language
        // hint ("Speak in British English.") so the accent words don't conflict
        // with the persona.
        val accent = if (style == TtsStyle.WEATHER_FORECASTER) {
            locale?.let { geminiAccentDirectiveFor(it) }
        } else {
            locale?.let { geminiLanguageDirectiveFor(it) }
        }
        val prompt = buildString {
            append(directiveFor(style, locale))
            if (accent != null) {
                append(accent)
                append("\n\n")
            }
            append(text)
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
                plan.applyAuth(this)
                contentType(ContentType.Application.Json)
                setBody(
                    TtsRequest(
                        contents = listOf(
                            TtsContent(parts = listOf(TtsTextPart(prompt))),
                        ),
                        generationConfig = TtsGenerationConfig(
                            responseModalities = listOf("AUDIO"),
                            speechConfig = SpeechConfig(
                                voiceConfig = VoiceConfig(
                                    prebuiltVoiceConfig = PrebuiltVoiceConfig(voiceName),
                                ),
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
            throw GeminiTtsHttpException(httpResponse.status, httpResponse.bodyAsBytes())
        }

        val response: TtsResponse = httpResponse.body()

        response.promptFeedback?.blockReason?.let {
            throw GeminiTtsBlockedException("Gemini TTS prompt blocked: $it")
        }

        val candidate = response.candidates.firstOrNull()
        val inline = candidate?.content?.parts?.firstOrNull { it.inlineData != null }
            ?.inlineData
            ?: throw GeminiTtsEmptyResponseException(candidate?.finishReason)

        val pcm = Base64.getDecoder().decode(inline.data)
        val sampleRate = parseSampleRate(inline.mimeType) ?: DEFAULT_SAMPLE_RATE_HZ
        return PcmAudio(bytes = pcm, sampleRate = sampleRate)
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
