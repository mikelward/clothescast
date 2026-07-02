package app.clothescast.core.data.tts

import app.clothescast.core.data.insight.GeminiCallPlan
import app.clothescast.core.data.insight.GeminiCallPlanner
import app.clothescast.core.data.insight.GeminiEndpoint
import app.clothescast.core.domain.model.TtsStyle
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.util.Locale

class GeminiTtsClientTest {

    private fun mockClient(
        responseBody: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        captureRequest: (HttpRequestData) -> Unit = {},
    ): HttpClient {
        val engine = MockEngine { request ->
            captureRequest(request)
            respond(
                content = ByteReadChannel(responseBody),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    /**
     * Mimics `:app`'s BYOK branch — direct to Google, key in
     * `x-goog-api-key`. Mirrors the production planner so the test
     * exercises the same wiring without depending on Android types.
     */
    private fun directPlanner(key: String): GeminiCallPlanner = GeminiCallPlanner {
        GeminiCallPlan(
            endpoint = GeminiEndpoint.Direct,
            applyAuth = { it.headers.append("x-goog-api-key", key) },
        )
    }

    @Test
    fun `posts to the default tts model endpoint with the api key header`() = runTest {
        var captured: HttpRequestData? = null
        val client = GeminiTtsClient(
            httpClient = mockClient(SUCCESS_BODY) { captured = it },
            callPlanner = directPlanner("test-key"),
        )

        client.synthesize(text = "hello")

        val req = checkNotNull(captured)
        req.url.host shouldBe GEMINI_HOST
        req.url.encodedPath shouldBe "/v1beta/models/gemini-2.5-flash-preview-tts:generateContent"
        req.headers["x-goog-api-key"] shouldBe "test-key"
    }

    @Test
    fun `request body includes responseModalities AUDIO`() = runTest {
        // Regression for the kotlinx.serialization `encodeDefaults = false` trap:
        // when the runtime value equals the declared default, the field is dropped
        // and Gemini falls back to its TEXT modality default — which a TTS model
        // can't satisfy ("The requested combination of response modalities (TEXT)
        // is not supported by the model.").
        var capturedBody: String? = null
        val client = GeminiTtsClient(
            httpClient = mockClient(SUCCESS_BODY) {
                capturedBody = (it.body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
                    .bytes()
                    .toString(Charsets.UTF_8)
            },
            callPlanner = directPlanner("test-key"),
        )

        client.synthesize(text = "hello")

        val body = checkNotNull(capturedBody)
        body.shouldContain("\"responseModalities\":[\"AUDIO\"]")
    }

    @Test
    fun `request body prepends the weather-forecaster directive by default`() = runTest {
        var capturedBody: String? = null
        val client = GeminiTtsClient(
            httpClient = mockClient(SUCCESS_BODY) {
                capturedBody = (it.body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
                    .bytes()
                    .toString(Charsets.UTF_8)
            },
            callPlanner = directPlanner("test-key"),
        )

        client.synthesize(text = "hello world")

        val body = checkNotNull(capturedBody)
        body.shouldContain("national news service")
        body.shouldContain("hello world")
    }

    @Test
    fun `request body includes a british accent directive for en-GB locale`() = runTest {
        var capturedBody: String? = null
        val client = GeminiTtsClient(
            httpClient = mockClient(SUCCESS_BODY) {
                capturedBody = (it.body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
                    .bytes()
                    .toString(Charsets.UTF_8)
            },
            callPlanner = directPlanner("test-key"),
        )

        client.synthesize(text = "hello", locale = Locale.UK)

        val body = checkNotNull(capturedBody)
        body.shouldContain("Standard British accent")
    }

    @Test
    fun `request body includes an australian accent directive for en-AU locale`() = runTest {
        var capturedBody: String? = null
        val client = GeminiTtsClient(
            httpClient = mockClient(SUCCESS_BODY) {
                capturedBody = (it.body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
                    .bytes()
                    .toString(Charsets.UTF_8)
            },
            callPlanner = directPlanner("test-key"),
        )

        client.synthesize(text = "hello", locale = Locale.forLanguageTag("en-AU"))

        val body = checkNotNull(capturedBody)
        body.shouldContain("Australian accent")
        // "General" was dropped — the per-locale register sentence in the
        // directive carries the standard-variety signal instead.
        body.shouldNotContain("General Australian")
    }

    @Test
    fun `weather forecaster on en-AU uses the register-in-directive variant`() = runTest {
        // en-AU is the only locale where directiveFor returns the variant with
        // the "standard variety, not regional dialect" sentence — empirically
        // needed to rescue Iapetus on en-AU. See docs/voice-evals.md →
        // "B3 register-in-directive eval".
        var capturedBody: String? = null
        val client = GeminiTtsClient(
            httpClient = mockClient(SUCCESS_BODY) {
                capturedBody = (it.body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
                    .bytes()
                    .toString(Charsets.UTF_8)
            },
            callPlanner = directPlanner("test-key"),
        )

        client.synthesize(text = "hello", locale = Locale.forLanguageTag("en-AU"))

        val body = checkNotNull(capturedBody)
        body.shouldContain("standard variety, not a regional dialect")
    }

    @Test
    fun `weather forecaster on en-GB uses the default directive without the register sentence`() = runTest {
        // en-GB stays on the B2 directive — adding the register sentence
        // empirically nudged en-GB voices off-brand. See voice-evals.md.
        var capturedBody: String? = null
        val client = GeminiTtsClient(
            httpClient = mockClient(SUCCESS_BODY) {
                capturedBody = (it.body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
                    .bytes()
                    .toString(Charsets.UTF_8)
            },
            callPlanner = directPlanner("test-key"),
        )

        client.synthesize(text = "hello", locale = Locale.forLanguageTag("en-GB"))

        val body = checkNotNull(capturedBody)
        body.shouldNotContain("standard variety, not a regional dialect")
    }

    @Test
    fun `character style on en-AU does not get the register-in-directive variant`() = runTest {
        // The register sentence is WEATHER_FORECASTER-specific. Persona styles
        // (PIRATE here) get their own directive verbatim — adding register
        // direction would conflict with persona delivery.
        var capturedBody: String? = null
        val client = GeminiTtsClient(
            httpClient = mockClient(SUCCESS_BODY) {
                capturedBody = (it.body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
                    .bytes()
                    .toString(Charsets.UTF_8)
            },
            callPlanner = directPlanner("test-key"),
        )

        client.synthesize(
            text = "ahoy",
            locale = Locale.forLanguageTag("en-AU"),
            style = TtsStyle.PIRATE,
        )

        val body = checkNotNull(capturedBody)
        body.shouldNotContain("standard variety, not a regional dialect")
        body.shouldContain("swaggering pirate")
    }

    @Test
    fun `request body includes a german directive for de locale`() = runTest {
        // German is the first non-English entry in the directive table — verifies
        // the language-only fallback (no language-COUNTRY entry) actually fires
        // and that the model is told to read the prose as German rather than
        // the en-default North American English.
        var capturedBody: String? = null
        val client = GeminiTtsClient(
            httpClient = mockClient(SUCCESS_BODY) {
                capturedBody = (it.body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
                    .bytes()
                    .toString(Charsets.UTF_8)
            },
            callPlanner = directPlanner("test-key"),
        )

        client.synthesize(text = "hallo", locale = Locale.forLanguageTag("de-DE"))

        val body = checkNotNull(capturedBody)
        body.shouldContain("Sprich auf Deutsch")
        // de-DE gets the Hochdeutsch fallback, not an Austrian or Swiss directive
        body.shouldNotContain("österreichischen")
        body.shouldNotContain("deutschschweizerischen")
    }

    @Test
    fun `request body includes an austrian accent directive for de-AT locale`() = runTest {
        var capturedBody: String? = null
        val client = GeminiTtsClient(
            httpClient = mockClient(SUCCESS_BODY) {
                capturedBody = (it.body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
                    .bytes()
                    .toString(Charsets.UTF_8)
            },
            callPlanner = directPlanner("test-key"),
        )

        client.synthesize(text = "hallo", locale = Locale.forLanguageTag("de-AT"))

        val body = checkNotNull(capturedBody)
        body.shouldContain("österreichischen")
        body.shouldNotContain("hochdeutschen")
    }

    @Test
    fun `request body includes a swiss german accent directive for de-CH locale`() = runTest {
        var capturedBody: String? = null
        val client = GeminiTtsClient(
            httpClient = mockClient(SUCCESS_BODY) {
                capturedBody = (it.body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
                    .bytes()
                    .toString(Charsets.UTF_8)
            },
            callPlanner = directPlanner("test-key"),
        )

        client.synthesize(text = "hallo", locale = Locale.forLanguageTag("de-CH"))

        val body = checkNotNull(capturedBody)
        body.shouldContain("deutschschweizerischen")
        body.shouldNotContain("hochdeutschen")
    }

    @Test
    fun `request body includes a european portuguese directive for pt-PT locale`() = runTest {
        var capturedBody: String? = null
        val client = GeminiTtsClient(
            httpClient = mockClient(SUCCESS_BODY) {
                capturedBody = (it.body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
                    .bytes()
                    .toString(Charsets.UTF_8)
            },
            callPlanner = directPlanner("test-key"),
        )

        client.synthesize(text = "olá", locale = Locale.forLanguageTag("pt-PT"))

        val body = checkNotNull(capturedBody)
        body.shouldContain("português europeu")
        // Must not pick up the Brazilian directive
        body.shouldNotContain("brasileiro")
    }

    @Test
    fun `request body includes a taiwanese mandarin directive for zh-TW locale`() = runTest {
        var capturedBody: String? = null
        val client = GeminiTtsClient(
            httpClient = mockClient(SUCCESS_BODY) {
                capturedBody = (it.body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
                    .bytes()
                    .toString(Charsets.UTF_8)
            },
            callPlanner = directPlanner("test-key"),
        )

        client.synthesize(text = "你好", locale = Locale.forLanguageTag("zh-TW"))

        val body = checkNotNull(capturedBody)
        // zh-TW uses the Traditional Chinese "國語" (guóyǔ) directive,
        // not the Simplified Chinese "普通话" (pǔtōnghuà) fallback.
        body.shouldContain("國語")
        body.shouldNotContain("普通话")
    }

    @Test
    fun `request body picks the saudi arabic directive for ar-SA locale`() = runTest {
        // PR #218 split the previously-language-only `ar` directive into four
        // country-specific entries so the picker variants we offer in
        // Settings actually steer Gemini, not just label the picker. Pin on
        // the country-distinct "بنطق سعودي" (Saudi pronunciation) so a swap
        // to a different country's directive surfaces here.
        var capturedBody: String? = null
        val client = GeminiTtsClient(
            httpClient = mockClient(SUCCESS_BODY) {
                capturedBody = (it.body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
                    .bytes()
                    .toString(Charsets.UTF_8)
            },
            callPlanner = directPlanner("test-key"),
        )

        client.synthesize(text = "hi", locale = Locale.forLanguageTag("ar-SA"))

        val body = checkNotNull(capturedBody)
        body.shouldContain("بنطق سعودي")
    }

    @Test
    fun `request body picks the egyptian arabic directive for ar-EG locale`() = runTest {
        var capturedBody: String? = null
        val client = GeminiTtsClient(
            httpClient = mockClient(SUCCESS_BODY) {
                capturedBody = (it.body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
                    .bytes()
                    .toString(Charsets.UTF_8)
            },
            callPlanner = directPlanner("test-key"),
        )

        client.synthesize(text = "hi", locale = Locale.forLanguageTag("ar-EG"))

        val body = checkNotNull(capturedBody)
        body.shouldContain("بنطق مصري")
    }

    @Test
    fun `request body picks the emirati arabic directive for ar-AE locale`() = runTest {
        var capturedBody: String? = null
        val client = GeminiTtsClient(
            httpClient = mockClient(SUCCESS_BODY) {
                capturedBody = (it.body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
                    .bytes()
                    .toString(Charsets.UTF_8)
            },
            callPlanner = directPlanner("test-key"),
        )

        client.synthesize(text = "hi", locale = Locale.forLanguageTag("ar-AE"))

        val body = checkNotNull(capturedBody)
        body.shouldContain("بنطق إماراتي")
    }

    @Test
    fun `request body picks the moroccan arabic directive for ar-MA locale`() = runTest {
        var capturedBody: String? = null
        val client = GeminiTtsClient(
            httpClient = mockClient(SUCCESS_BODY) {
                capturedBody = (it.body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
                    .bytes()
                    .toString(Charsets.UTF_8)
            },
            callPlanner = directPlanner("test-key"),
        )

        client.synthesize(text = "hi", locale = Locale.forLanguageTag("ar-MA"))

        val body = checkNotNull(capturedBody)
        body.shouldContain("بنطق مغربي")
    }

    @Test
    fun `request body falls back to the bare ar directive for unrecognised arabic variants`() = runTest {
        // ar-LB (Lebanon) isn't enumerated — fall through to the language-only
        // `ar` entry so the model still gets a "read this in Arabic" nudge
        // rather than no directive at all. Pinning on the bare `ar` directive
        // (no country word) verifies the language-only fallback path.
        var capturedBody: String? = null
        val client = GeminiTtsClient(
            httpClient = mockClient(SUCCESS_BODY) {
                capturedBody = (it.body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
                    .bytes()
                    .toString(Charsets.UTF_8)
            },
            callPlanner = directPlanner("test-key"),
        )

        client.synthesize(text = "hi", locale = Locale.forLanguageTag("ar-LB"))

        val body = checkNotNull(capturedBody)
        // Bare `ar` directive omits both the country adjective and the
        // "الفصحى" (MSA) marker — the trailing period right after "بالعربية"
        // is unique to this fallback (country variants continue with
        // " الفصحى بنطق <country>." and have a space, not a period, there).
        body.shouldContain("اقرأ النص التالي بالعربية.")
        body.shouldNotContain("سعودي")
        body.shouldNotContain("مصري")
    }

    @Test
    fun `request body omits the accent directive for unknown english variants`() = runTest {
        // en-NZ isn't in our supported variant list — fall through to whatever the
        // model defaults to rather than picking a wrong-but-confident accent.
        var capturedBody: String? = null
        val client = GeminiTtsClient(
            httpClient = mockClient(SUCCESS_BODY) {
                capturedBody = (it.body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
                    .bytes()
                    .toString(Charsets.UTF_8)
            },
            callPlanner = directPlanner("test-key"),
        )

        client.synthesize(text = "hello", locale = Locale.forLanguageTag("en-NZ"))

        val body = checkNotNull(capturedBody)
        // No accent directive at all — none of the SSB / General Australian /
        // General American sentinels should leak in for unknown variants.
        body.shouldNotContain("Speak with a")
    }

    @Test
    fun `character styles get a bare language hint not the full accent directive`() = runTest {
        var capturedBody: String? = null
        val client = GeminiTtsClient(
            httpClient = mockClient(SUCCESS_BODY) {
                capturedBody = (it.body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
                    .bytes()
                    .toString(Charsets.UTF_8)
            },
            callPlanner = directPlanner("test-key"),
        )

        client.synthesize(text = "hello", locale = Locale.UK, style = TtsStyle.PIRATE)

        val body = checkNotNull(capturedBody)
        body.shouldContain("Speak in British English.")
        // Full accent directive must not appear — it conflicts with the persona.
        body.shouldNotContain("Standard British accent")
    }

    @Test
    fun `weather forecaster still gets the full accent directive not the bare language hint`() = runTest {
        var capturedBody: String? = null
        val client = GeminiTtsClient(
            httpClient = mockClient(SUCCESS_BODY) {
                capturedBody = (it.body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
                    .bytes()
                    .toString(Charsets.UTF_8)
            },
            callPlanner = directPlanner("test-key"),
        )

        client.synthesize(text = "hello", locale = Locale.UK, style = TtsStyle.WEATHER_FORECASTER)

        val body = checkNotNull(capturedBody)
        body.shouldContain("Standard British accent")
        body.shouldNotContain("Speak in British English.")
    }

    @Test
    fun `decodes inline pcm and parses sample rate from mime type`() = runTest {
        val client = GeminiTtsClient(
            httpClient = mockClient(SUCCESS_BODY),
            callPlanner = directPlanner("test-key"),
        )

        val audio = client.synthesize(text = "hello")

        // SUCCESS_BODY encodes the four bytes 0xDE 0xAD 0xBE 0xEF.
        audio.bytes.toList() shouldBe listOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        audio.sampleRate shouldBe 24_000
    }

    @Test
    fun `uses the planner's endpoint and lets it attach auth headers`() = runTest {
        // Mirrors `:app`'s shared-key path: a different host and a pair of
        // proxy-only headers (App Check token + Firebase install ID),
        // *no* `x-goog-api-key`. Locks in that GeminiTtsClient no longer
        // assumes a single backend or auth shape.
        var captured: HttpRequestData? = null
        val client = GeminiTtsClient(
            httpClient = mockClient(SUCCESS_BODY) { captured = it },
            callPlanner = GeminiCallPlanner {
                GeminiCallPlan(
                    endpoint = GeminiEndpoint(host = "example-proxy.test", apiVersion = "v1"),
                    applyAuth = {
                        it.headers.append("X-Firebase-AppCheck", "fake-app-check-token")
                        it.headers.append("X-Install-Id", "fake-fid")
                    },
                )
            },
        )

        client.synthesize(text = "hello")

        val req = checkNotNull(captured)
        req.url.host shouldBe "example-proxy.test"
        req.url.encodedPath shouldBe "/v1/models/gemini-2.5-flash-preview-tts:generateContent"
        req.headers["X-Firebase-AppCheck"] shouldBe "fake-app-check-token"
        req.headers["X-Install-Id"] shouldBe "fake-fid"
        req.headers["x-goog-api-key"] shouldBe null
    }

    @Test
    fun `throws GeminiTtsHttpException with parsed error message on 400`() = runTest {
        val client = GeminiTtsClient(
            httpClient = mockClient(
                status = HttpStatusCode.BadRequest,
                responseBody = """
                    {"error":{"code":400,"message":"Invalid voice 'Nope'.","status":"INVALID_ARGUMENT"}}
                """.trimIndent(),
            ),
            callPlanner = directPlanner("test-key"),
        )

        val ex = shouldThrow<GeminiTtsHttpException> { client.synthesize(text = "hi") }

        ex.status shouldBe HttpStatusCode.BadRequest
        ex.message shouldBe "Gemini TTS HTTP 400: Invalid voice 'Nope'."
        // Should NOT contain the raw JSON envelope keys — that's what made the old
        // toast unreadable.
        ex.message!!.shouldNotContain("INVALID_ARGUMENT")
        ex.message!!.shouldNotContain("\"error\"")
    }

    @Test
    fun `surfaces a placeholder when the error body is empty`() = runTest {
        val client = GeminiTtsClient(
            httpClient = mockClient(
                status = HttpStatusCode.BadGateway,
                responseBody = "",
            ),
            callPlanner = directPlanner("test-key"),
        )

        val ex = shouldThrow<GeminiTtsHttpException> { client.synthesize(text = "hi") }

        ex.message shouldBe "Gemini TTS HTTP 502: (empty body)"
    }

    @Test
    fun `falls back to truncated raw body when error envelope is unparseable`() = runTest {
        val raw = "x".repeat(500)
        val client = GeminiTtsClient(
            httpClient = mockClient(
                status = HttpStatusCode.InternalServerError,
                responseBody = raw,
            ),
            callPlanner = directPlanner("test-key"),
        )

        val ex = shouldThrow<GeminiTtsHttpException> { client.synthesize(text = "hi") }

        ex.message!!.shouldContain("Gemini TTS HTTP 500: ")
        // Truncated to the 160-char excerpt cap, not the full 500-char body.
        (ex.message!!.length < 250) shouldBe true
    }

    @Test
    fun `throws GeminiTtsDailyQuotaExhaustedException on shared-key proxy 429`() = runTest {
        val client = GeminiTtsClient(
            httpClient = mockClient(
                status = HttpStatusCode.TooManyRequests,
                responseBody = """
                    {"error":"daily_quota_exhausted","limit":5,"resetAtUtc":"2026-06-01T00:00:00.000Z"}
                """.trimIndent(),
            ),
            callPlanner = directPlanner("test-key"),
        )

        val ex = shouldThrow<GeminiTtsDailyQuotaExhaustedException> { client.synthesize(text = "hi") }

        ex.limit shouldBe 5
        ex.resetAtUtc shouldBe "2026-06-01T00:00:00.000Z"
        // Friendly copy — the Voice settings preview Toast shows this
        // directly, so it must read as user-facing English, not as a
        // raw error token.
        ex.message!!.shouldContain("Free TTS limit reached for today")
        ex.message!!.shouldNotContain("daily_quota_exhausted")
    }

    @Test
    fun `falls back to GeminiTtsHttpException on a non-quota 429 body`() = runTest {
        // Generic Gemini rate-limit response (BYOK path or proxy forwarding
        // a Gemini-side 429) keeps the existing path, not the typed quota
        // exception — surfacing the upstream message in the Toast is more
        // useful than the friendly "free limit" copy when the user's own
        // key is the one being throttled.
        val client = GeminiTtsClient(
            httpClient = mockClient(
                status = HttpStatusCode.TooManyRequests,
                responseBody = """
                    {"error":{"code":429,"message":"Rate limit exceeded.","status":"RESOURCE_EXHAUSTED"}}
                """.trimIndent(),
            ),
            callPlanner = directPlanner("test-key"),
        )

        val ex = shouldThrow<GeminiTtsHttpException> { client.synthesize(text = "hi") }

        ex.status shouldBe HttpStatusCode.TooManyRequests
        ex.message shouldBe "Gemini TTS HTTP 429: Rate limit exceeded."
    }

    @Test
    fun `request body uses each character-register directive for the matching TtsStyle`() = runTest {
        // One assertion per playful / persona register: pick a phrase that
        // only that directive contains and verify it lands in the prompt
        // when the style is set. Drives the `when` arm wiring in
        // styleDirectiveFor() — adding a new TtsStyle without adding the
        // map entry here is the regression this catches.
        val signatures: Map<TtsStyle, String> = mapOf(
            TtsStyle.PIRATE to "swaggering pirate",
            TtsStyle.COWBOY to "Old West drawl",
            TtsStyle.SCIFI_NARRATOR to "British science-fiction narrator",
            TtsStyle.SCIENCE_TEACHER to "high school science teacher",
            TtsStyle.HISTORIAN to "history documentary",
            TtsStyle.SPORTSCASTER to "animated sportscaster",
            TtsStyle.STADIUM_ANNOUNCER to "stadium announcer",
            TtsStyle.STORYTELLER to "storyteller",
            TtsStyle.FITNESS_INSTRUCTOR to "fitness instructor",
            TtsStyle.MORNING_PRESENTER to "morning radio presenter",
            TtsStyle.FATHER_CHRISTMAS to "Father Christmas",
            TtsStyle.SPOOKY_NARRATOR to "spooky Halloween narrator",
            TtsStyle.NEW_YEARS_HOST to "New Year's Eve party host",
            TtsStyle.LEPRECHAUN to "mischievous leprechaun",
            TtsStyle.KING to "King Charles III",
            TtsStyle.QUEEN to "Queen Elizabeth II",
            TtsStyle.PRESIDENT to "revered American president",
        )

        for ((style, signature) in signatures) {
            var capturedBody: String? = null
            val client = GeminiTtsClient(
                httpClient = mockClient(SUCCESS_BODY) {
                    capturedBody = (it.body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
                        .bytes()
                        .toString(Charsets.UTF_8)
                },
                callPlanner = directPlanner("test-key"),
            )

            client.synthesize(text = "hello world", style = style)

            val body = checkNotNull(capturedBody) { "no body captured for style=$style" }
            withClue("style=$style") {
                body.shouldContain(signature)
                body.shouldContain("hello world")
                // Baseline NORMAL phrasing must not leak into a non-NORMAL style.
                body.shouldNotContain("national news service")
            }
        }
    }

    @Test
    fun `styleDirectiveFor maps every TtsStyle to its directive constant`() {
        // Direct unit test of the pure `when` mapping. The integration-style
        // test above (`request body uses each character-register directive
        // for the matching TtsStyle`) exercises this through the full HTTP
        // path; this one pins each arm without spinning up an HttpClient so
        // a broken mapping fails fast and points at the right function.
        //
        // The map's keys double as an exhaustiveness check: when a new
        // TtsStyle is added, the `when` compile error in styleDirectiveFor
        // fires first, but if someone defaults the new arm to a
        // placeholder the `expected.keys shouldBe TtsStyle.entries.toSet()`
        // assertion below surfaces the gap.
        val expected: Map<TtsStyle, String> = mapOf(
            TtsStyle.WEATHER_FORECASTER to GEMINI_TTS_STYLE_DIRECTIVE_WEATHER_FORECASTER,
            TtsStyle.SCIENCE_TEACHER to GEMINI_TTS_STYLE_DIRECTIVE_SCIENCE_TEACHER,
            TtsStyle.HISTORIAN to GEMINI_TTS_STYLE_DIRECTIVE_HISTORIAN,
            TtsStyle.SPORTSCASTER to GEMINI_TTS_STYLE_DIRECTIVE_SPORTSCASTER,
            TtsStyle.STADIUM_ANNOUNCER to GEMINI_TTS_STYLE_DIRECTIVE_STADIUM_ANNOUNCER,
            TtsStyle.STORYTELLER to GEMINI_TTS_STYLE_DIRECTIVE_STORYTELLER,
            TtsStyle.FITNESS_INSTRUCTOR to GEMINI_TTS_STYLE_DIRECTIVE_FITNESS_INSTRUCTOR,
            TtsStyle.MORNING_PRESENTER to GEMINI_TTS_STYLE_DIRECTIVE_MORNING_PRESENTER,
            TtsStyle.PIRATE to GEMINI_TTS_STYLE_DIRECTIVE_PIRATE,
            TtsStyle.COWBOY to GEMINI_TTS_STYLE_DIRECTIVE_COWBOY,
            TtsStyle.SCIFI_NARRATOR to GEMINI_TTS_STYLE_DIRECTIVE_SCIFI_NARRATOR,
            TtsStyle.FATHER_CHRISTMAS to GEMINI_TTS_STYLE_DIRECTIVE_FATHER_CHRISTMAS,
            TtsStyle.SPOOKY_NARRATOR to GEMINI_TTS_STYLE_DIRECTIVE_SPOOKY_NARRATOR,
            TtsStyle.NEW_YEARS_HOST to GEMINI_TTS_STYLE_DIRECTIVE_NEW_YEARS_HOST,
            TtsStyle.LEPRECHAUN to GEMINI_TTS_STYLE_DIRECTIVE_LEPRECHAUN,
            TtsStyle.KING to GEMINI_TTS_STYLE_DIRECTIVE_KING,
            TtsStyle.QUEEN to GEMINI_TTS_STYLE_DIRECTIVE_QUEEN,
            TtsStyle.PRESIDENT to GEMINI_TTS_STYLE_DIRECTIVE_PRESIDENT,
        )

        expected.keys shouldBe TtsStyle.entries.toSet()

        for ((style, directive) in expected) {
            withClue("style=$style") {
                styleDirectiveFor(style) shouldBe directive
            }
        }
    }

    @Test
    fun `styleDirectiveFor returns the B2 weather-forecaster variant for WEATHER_FORECASTER`() {
        // Pin the WEATHER_FORECASTER arm on the B2 (no-register) directive
        // specifically — directiveFor() upgrades en-AU to the _REGISTER
        // variant, but styleDirectiveFor() itself is locale-agnostic and
        // must return the bare B2 wording so that routing decision stays
        // in directiveFor where the per-locale comment lives.
        val directive = styleDirectiveFor(TtsStyle.WEATHER_FORECASTER)

        directive shouldBe GEMINI_TTS_STYLE_DIRECTIVE_WEATHER_FORECASTER
        directive.shouldNotContain("standard variety, not a regional dialect")
    }

    @Test
    fun `throws GeminiTtsEmptyResponseException when response has no inline audio`() = runTest {
        val client = GeminiTtsClient(
            httpClient = mockClient("""{"candidates":[]}"""),
            callPlanner = directPlanner("test-key"),
        )

        shouldThrow<GeminiTtsEmptyResponseException> { client.synthesize(text = "hi") }
    }

    @Test
    fun `legacy ISO-639 language codes map to the directive tables' modern keys`() {
        // Android's Locale.getLanguage() reports "iw" / "in" / "ji" for
        // Hebrew / Indonesian / Yiddish — even for a locale built from the
        // modern tag "he-IL" — while JVMs since JDK 17 report the modern
        // codes, so this can only be covered at the string seam. Without the
        // normalization, the on-device lookup misses the "he" / "id" entries
        // and Hebrew / Indonesian voices lose their language steering.
        modernLanguageCode("iw") shouldBe "he"
        modernLanguageCode("in") shouldBe "id"
        modernLanguageCode("ji") shouldBe "yi"
        modernLanguageCode("he") shouldBe "he"
        modernLanguageCode("en") shouldBe "en"
    }

    @Test
    fun `Hebrew and Indonesian voice locales resolve their directives`() {
        geminiAccentDirectiveFor(Locale.forLanguageTag("he-IL")) shouldBe
            "קרא/י את הטקסט הבא בעברית."
        geminiLanguageDirectiveFor(Locale.forLanguageTag("he-IL")) shouldBe "Speak in Hebrew."
        geminiLanguageDirectiveFor(Locale.forLanguageTag("id-ID")) shouldBe "Speak in Indonesian."
    }

    private companion object {
        // 0xDE 0xAD 0xBE 0xEF base64-encoded.
        const val SUCCESS_BODY = """
            {
              "candidates": [{
                "content": {
                  "parts": [{
                    "inlineData": {
                      "mimeType": "audio/L16;codec=pcm;rate=24000",
                      "data": "3q2+7w=="
                    }
                  }]
                }
              }]
            }
        """
    }
}
