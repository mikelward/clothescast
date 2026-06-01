package app.clothescast.core.data.diag

import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException

/**
 * Short, stable identifiers for the network endpoints we instrument. Lowercase
 * snake_case, ≤40 chars to fit Firebase Analytics' parameter-value limit. The
 * sink in :app uses these verbatim as the `endpoint` event param.
 */
object ApiEndpoints {
    const val OPEN_METEO_FORECAST: String = "open_meteo_forecast"
    const val OPEN_METEO_CONFIDENCE: String = "open_meteo_confidence"
    const val OPEN_METEO_GEOCODING: String = "open_meteo_geocoding"
    const val GEMINI_TTS: String = "gemini_tts"
}

/**
 * One completed API call. [httpStatus] is set when the call reached the
 * server (success or 4xx/5xx); [errorClass] is the exception's simple class
 * name when no status was returned (timeouts, IO failures, content-type
 * mismatches). Exactly one of the two is non-null per event; both null is
 * not produced by [instrument]. [latencyMs] is wall-clock from request start.
 *
 * The sink in :app classifies these into Firebase Analytics outcomes —
 * `success`, `http_error`, `timeout`, `network_error`, `other_error` — and
 * drops events that look like "device offline" so airplane-mode noise
 * doesn't pollute the rate-limit / transient-failure signal.
 */
data class ApiCallEvent(
    val endpoint: String,
    val httpStatus: Int?,
    val errorClass: String?,
    val latencyMs: Long,
)

/**
 * Sink for API-call telemetry. Wired by :app to push events into Firebase
 * Analytics; defaults to [NoOpApiCallLogger] so tests and JVM-only consumers
 * don't take a dependency on the analytics stack.
 *
 * Implementations are called inline with the HTTP request and must not
 * throw — the in-flight response is still being consumed by the caller.
 */
fun interface ApiCallLogger {
    fun log(event: ApiCallEvent)
}

val NoOpApiCallLogger: ApiCallLogger = ApiCallLogger { _ -> }

/**
 * Wraps an HTTP-bearing block, logging one [ApiCallEvent] when it completes
 * (success or failure) and rethrowing the original exception untouched.
 *
 * Success path records the call as `httpStatus = 200`; we lose the 200/201/204
 * distinction in exchange for a single code path. The interesting cases are
 * the failures, and those are surfaced with the real status (via Ktor's
 * [ResponseException]) or the exception class name (timeouts, IO, content-
 * type mismatches).
 *
 * [CancellationException] is propagated without logging — it means the caller
 * cancelled, not that the API was slow or broken.
 */
internal suspend fun <T> ApiCallLogger.instrument(
    endpoint: String,
    block: suspend () -> T,
): T {
    val started = System.currentTimeMillis()
    return try {
        val result = block()
        log(
            ApiCallEvent(
                endpoint = endpoint,
                httpStatus = SUCCESS_STATUS,
                errorClass = null,
                latencyMs = System.currentTimeMillis() - started,
            ),
        )
        result
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: ResponseException) {
        log(
            ApiCallEvent(
                endpoint = endpoint,
                httpStatus = e.response.status.value,
                errorClass = null,
                latencyMs = System.currentTimeMillis() - started,
            ),
        )
        throw e
    } catch (t: Throwable) {
        log(
            ApiCallEvent(
                endpoint = endpoint,
                httpStatus = null,
                errorClass = t.javaClass.simpleName,
                latencyMs = System.currentTimeMillis() - started,
            ),
        )
        throw t
    }
}

private const val SUCCESS_STATUS = 200
