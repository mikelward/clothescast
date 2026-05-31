package app.clothescast.core.data.insight

import io.ktor.client.request.HttpRequestBuilder

/**
 * Where a Gemini API call should be sent — the host + API version that
 * appear in the request URL. The path (`models/{model}:generateContent`)
 * is fixed by the upstream API and built by [app.clothescast.core.data.tts.GeminiTtsClient].
 *
 * Two presets:
 *  - [Direct] — Google's Generative Language API, used when the user has
 *    supplied their own Gemini API key (BYOK). The key travels as
 *    `x-goog-api-key` via the plan's `applyAuth` callback.
 *  - the developer's Cloud Function proxy, constructed at the call site
 *    from `BuildConfig.GEMINI_PROXY_URL` in `:app`. App Check token +
 *    Firebase Installation ID travel in headers via `applyAuth`.
 */
data class GeminiEndpoint(
    val host: String,
    val apiVersion: String,
) {
    companion object {
        val Direct: GeminiEndpoint = GeminiEndpoint(
            host = "generativelanguage.googleapis.com",
            apiVersion = "v1beta",
        )
    }
}

/**
 * A per-request decision: which Gemini-compatible endpoint to hit, and
 * how to attach auth / identity headers. `applyAuth` is invoked once
 * inside `httpClient.post { … }` so the planner can call into Firebase
 * (or read the encrypted key store) at request build time, without
 * `GeminiTtsClient` knowing which backend is involved.
 */
data class GeminiCallPlan(
    val endpoint: GeminiEndpoint,
    val applyAuth: suspend (HttpRequestBuilder) -> Unit,
)

/**
 * Builds a [GeminiCallPlan] for the upcoming request. The `:app` impl
 * branches on whether the user has set their own key — BYOK → direct
 * to Google, otherwise → the developer's proxy. Pure-Kotlin so the
 * tests in `:core:data` can drive it with a trivial inline planner.
 */
fun interface GeminiCallPlanner {
    suspend fun plan(): GeminiCallPlan
}
