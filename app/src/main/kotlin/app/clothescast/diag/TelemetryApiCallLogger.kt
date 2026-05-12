package app.clothescast.diag

import android.content.Context
import app.clothescast.core.data.diag.ApiCallEvent
import app.clothescast.core.data.diag.ApiCallLogger

/**
 * [ApiCallLogger] sink that classifies events into a small enum of outcomes
 * and pushes them at [Telemetry.logApiCall].
 *
 * The offline filter: a network failure (no HTTP status, exception class
 * looks IO-shaped) is dropped entirely when [isLikelyOnline] reports the
 * device has no active validated network. Airplane mode, a dead radio, or
 * a captive-portal Wi-Fi that hasn't been signed into yet will all flag as
 * "offline" — the resulting IOExceptions aren't useful rate-limit / 5xx
 * signal, so silencing them keeps the api_call stream actionable.
 *
 * A 4xx / 5xx with a real status code is always logged — those mean the
 * request reached the server, even if the local network was unstable, and
 * they're exactly the signal we care about.
 */
internal class TelemetryApiCallLogger(private val context: Context) : ApiCallLogger {
    override fun log(event: ApiCallEvent) {
        val status = event.httpStatus
        val outcome: String = when {
            status != null && status in SUCCESS_RANGE -> OUTCOME_SUCCESS
            status != null -> OUTCOME_HTTP_ERROR
            else -> classifyError(event.errorClass)
        }
        // Drop "offline" events entirely: they're noise for the rate-limit /
        // transient-server-error analysis the user actually wants.
        if (outcome == OUTCOME_OFFLINE) return
        Telemetry.logApiCall(
            endpoint = event.endpoint,
            outcome = outcome,
            statusCode = status ?: 0,
            latencyMs = event.latencyMs,
        )
    }

    /**
     * Maps an exception class name to a [Telemetry] outcome. Names below are
     * matched as substrings so we don't bind to specific FQNs across Ktor /
     * okhttp / java.net versions — `SocketTimeoutException`,
     * `HttpRequestTimeoutException`, `ConnectTimeoutException` all hit the
     * `timeout` arm without enumeration.
     *
     * "Network" classifications consult [isLikelyOnline] and downgrade to
     * `offline` when the device has no active validated network — the
     * filter the caller relies on to drop airplane-mode noise.
     */
    private fun classifyError(errorClass: String?): String {
        val name = errorClass.orEmpty()
        return when {
            name.contains("Timeout", ignoreCase = true) ->
                if (!isLikelyOnline(context)) OUTCOME_OFFLINE else OUTCOME_TIMEOUT
            name.contains("UnknownHost", ignoreCase = true) ||
                name.contains("ConnectException", ignoreCase = true) ||
                name.contains("NoRouteToHost", ignoreCase = true) ||
                name.contains("IOException", ignoreCase = true) ||
                name.contains("NetworkException", ignoreCase = true) ||
                name.contains("SocketException", ignoreCase = true) ->
                if (!isLikelyOnline(context)) OUTCOME_OFFLINE else OUTCOME_NETWORK_ERROR
            else -> OUTCOME_OTHER_ERROR
        }
    }

    companion object {
        private val SUCCESS_RANGE = 200..299
        private const val OUTCOME_SUCCESS = "success"
        private const val OUTCOME_HTTP_ERROR = "http_error"
        private const val OUTCOME_TIMEOUT = "timeout"
        private const val OUTCOME_NETWORK_ERROR = "network_error"
        private const val OUTCOME_OTHER_ERROR = "other_error"
        private const val OUTCOME_OFFLINE = "offline"
    }
}
