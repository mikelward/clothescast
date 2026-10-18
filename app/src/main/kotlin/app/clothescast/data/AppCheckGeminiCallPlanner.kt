package app.clothescast.data

import androidx.core.net.toUri
import app.clothescast.core.data.insight.GeminiCallPlan
import app.clothescast.core.data.insight.GeminiCallPlanner
import app.clothescast.core.data.insight.GeminiEndpoint
import app.clothescast.core.data.insight.MissingApiKeyException
import app.clothescast.diag.DiagLog
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import kotlinx.coroutines.tasks.await

/**
 * Per-call routing of Gemini TTS requests.
 *
 * - **User has set their own Gemini key (BYOK):** plan a DIRECT call —
 *   straight to Google's Generative Language API, key in `x-goog-api-key`.
 *   The developer's proxy never sees the request.
 * - **No key set, signed in with Google, proxy configured:** plan a SHARED
 *   call — to the developer-operated Cloud Function at [proxyBaseUrl],
 *   carrying a Firebase App Check attestation token (`X-Firebase-AppCheck`),
 *   the signed-in user's Firebase ID token (`Authorization: Bearer
 *   <idToken>`) and the model name (`X-Gemini-Model`). The function holds
 *   the developer's Gemini key, verifies the token, counts the call against
 *   that Google account's daily quota and forwards it.
 * - **No key set and the shared path unusable** ([sharedPathEnabled] reads
 *   false — not signed in, or a build without `google-services.json` /
 *   `GEMINI_PROXY_URL`): throw [MissingApiKeyException], the same surface
 *   as before, so callers fall back to the device voice.
 *
 * Older builds signed in anonymously and also sent the Firebase
 * Installation ID; the proxy still accepts those for app versions already
 * installed (SPEC.md → "Accounts and the developer-funded paths"), but this
 * build sends neither.
 *
 * On the SHARED path, App Check and ID token fetches can fail legitimately
 * (offline, Play Integrity unavailable). Those propagate as the SDK's own
 * exceptions — surfaced as the existing diagnostic Toast via
 * [app.clothescast.ui.settings.VoiceSettings]'s catch-all.
 */
class AppCheckGeminiCallPlanner(
    private val secureKeyStore: SecureKeyStore,
    private val proxyBaseUrl: String,
    private val sharedPathEnabled: () -> Boolean,
    private val model: String,
    private val appCheck: () -> FirebaseAppCheck = { FirebaseAppCheck.getInstance() },
    private val auth: () -> FirebaseAuth = { FirebaseAuth.getInstance() },
) : GeminiCallPlanner {

    override suspend fun plan(): GeminiCallPlan {
        val ownKey = readOwnKey()
        if (ownKey != null) {
            return directPlan(ownKey)
        }
        if (!sharedPathEnabled() || proxyBaseUrl.isBlank()) {
            throw MissingApiKeyException()
        }
        return sharedPlan()
    }

    private suspend fun readOwnKey(): String? = try {
        secureKeyStore.get().takeIf { it.isNotBlank() }
    } catch (_: MissingApiKeyException) {
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
                it.headers.append("X-Firebase-AppCheck", appCheckToken)
                it.headers.append("Authorization", "Bearer ${signedInIdToken()}")
                it.headers.append("X-Gemini-Model", model)
            },
        )
    }

    /**
     * The signed-in user's ID token. [com.google.firebase.auth.FirebaseUser.getIdToken]
     * returns a cached token until it expires (~1 h) and refreshes
     * transparently after that, so steady-state calls don't hit the network.
     *
     * If the account was deleted or disabled server-side while this install
     * kept it cached, the refresh throws [FirebaseAuthInvalidUserException].
     * Sign the stale user out so the UI shows the signed-out state and the
     * next call falls back to the device voice, then rethrow so this call
     * does too.
     */
    private suspend fun signedInIdToken(): String {
        val firebaseAuth = auth()
        val user = firebaseAuth.currentUser ?: throw MissingApiKeyException()
        return try {
            user.getIdToken(false).await().token
                ?: error("Signed-in user returned no ID token")
        } catch (e: FirebaseAuthInvalidUserException) {
            DiagLog.w(TAG, e, "Signed-in user is no longer valid; signing out")
            firebaseAuth.signOut()
            throw e
        }
    }

    companion object {
        private const val TAG = "GeminiCallPlanner"

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
            val parsed = url.toUri()
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
