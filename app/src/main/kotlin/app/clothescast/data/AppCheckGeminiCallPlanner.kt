package app.clothescast.data

import android.net.Uri
import app.clothescast.core.data.insight.GeminiCallPlan
import app.clothescast.core.data.insight.GeminiCallPlanner
import app.clothescast.core.data.insight.GeminiEndpoint
import app.clothescast.core.data.insight.MissingApiKeyException
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.installations.FirebaseInstallations
import kotlinx.coroutines.tasks.await

/**
 * Per-call routing of Gemini TTS requests.
 *
 * - **User has set their own Gemini key (BYOK):** plan a DIRECT call —
 *   straight to Google's Generative Language API, key in `x-goog-api-key`.
 *   The developer's proxy never sees the request.
 * - **No key set, proxy configured:** plan a SHARED call — to the
 *   developer-operated Cloud Function at [proxyBaseUrl], carrying a
 *   Firebase App Check attestation token (`X-Firebase-AppCheck`),
 *   the user's Firebase Installation ID (`X-Install-Id`), and the model
 *   name (`X-Gemini-Model`). The function holds the developer's Gemini
 *   key and forwards the call.
 * - **No key set and shared path disabled** ([sharedPathEnabled] is
 *   false — CI builds without `google-services.json`, or forks that
 *   haven't configured `GEMINI_PROXY_URL`): throw
 *   [MissingApiKeyException], same surface as before.
 *
 * On the SHARED path, App Check token and FID fetches can fail
 * legitimately (offline, Play Integrity unavailable). Those propagate as
 * the SDK's own exceptions — surfaced as the existing diagnostic Toast
 * via [app.clothescast.ui.settings.VoiceSettings]'s catch-all. We don't
 * map them to a dedicated app-side type until the trial-exhausted (429)
 * mapping lands; see `docs/TODO.md` "Enforce shared-key TTS trial limit".
 */
class AppCheckGeminiCallPlanner(
    private val secureKeyStore: SecureKeyStore,
    private val proxyBaseUrl: String,
    private val sharedPathEnabled: Boolean,
    private val model: String,
    private val appCheck: () -> FirebaseAppCheck = { FirebaseAppCheck.getInstance() },
    private val installations: () -> FirebaseInstallations = { FirebaseInstallations.getInstance() },
) : GeminiCallPlanner {

    override suspend fun plan(): GeminiCallPlan {
        val ownKey = readOwnKey()
        if (ownKey != null) {
            return directPlan(ownKey)
        }
        if (!sharedPathEnabled || proxyBaseUrl.isBlank()) {
            throw MissingApiKeyException()
        }
        return sharedPlan()
    }

    private suspend fun readOwnKey(): String? = try {
        secureKeyStore.get().takeIf { it.isNotBlank() }
    } catch (_: MissingApiKeyException) {
        // TODO: SecureKeyStore.get() throws MissingApiKeyException for two
        // distinct reasons — "user never stored a key" and "stored
        // ciphertext failed to decrypt and was cleared" (Keystore master
        // rotation after factory reset / cross-device transfer). The
        // second case should keep the user on the BYOK path and prompt
        // them to re-enter the key, not silently route the next request
        // through the developer proxy. Distinguish by checking
        // SecureKeyStore.geminiKeyConfiguredFlow.first() *before* calling
        // get(): if a doc was present at decision time, treat a
        // subsequent decrypt failure as "ask user to re-enter" rather
        // than falling through to sharedPlan().
        null
    }

    private fun directPlan(key: String) = GeminiCallPlan(
        endpoint = GeminiEndpoint.Direct,
        applyAuth = { it.headers.append("x-goog-api-key", key) },
    )

    private fun sharedPlan(): GeminiCallPlan {
        val endpoint = parseProxyEndpoint(proxyBaseUrl)
        return GeminiCallPlan(
            endpoint = endpoint,
            applyAuth = {
                val appCheckToken = appCheck().getAppCheckToken(false).await().token
                val installId = installations().id.await()
                it.headers.append("X-Firebase-AppCheck", appCheckToken)
                it.headers.append("X-Install-Id", installId)
                it.headers.append("X-Gemini-Model", model)
            },
        )
    }

    companion object {
        /**
         * Splits the configured proxy base URL into the host + single-segment
         * path prefix that `GeminiTtsClient` expects. The client builds the
         * final path as `<apiVersion>/models/<model>:generateContent`, so a
         * proxy URL like `https://us-central1-myproj.cloudfunctions.net/tts`
         * yields `host="…cloudfunctions.net"`, `apiVersion="tts"` — the
         * function receives `POST /tts/models/<…>:generateContent`, which it
         * routes by the `X-Gemini-Model` header and the request body
         * (the path is ignored server-side).
         *
         * Cloud Functions URLs always have exactly one path segment (the
         * function name). Nested paths (`/api/tts/…`) would be encoded as a
         * single segment by Ktor's path builder and break the upstream
         * routing — if you need that, change the call planner to set the
         * URL directly instead of going through `GeminiEndpoint.apiVersion`.
         */
        internal fun parseProxyEndpoint(url: String): GeminiEndpoint {
            val parsed = Uri.parse(url)
            val host = parsed.host
                ?: error("GEMINI_PROXY_URL has no host: $url")
            val pathPrefix = parsed.path.orEmpty().trim('/')
            require(!pathPrefix.contains('/')) {
                "GEMINI_PROXY_URL must have at most one path segment, got: $url"
            }
            return GeminiEndpoint(host = host, apiVersion = pathPrefix)
        }
    }
}
