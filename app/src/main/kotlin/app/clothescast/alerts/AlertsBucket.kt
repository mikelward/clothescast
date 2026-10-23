package app.clothescast.alerts

import java.net.URI
import java.net.URISyntaxException

// The host of a first-generation Cloud Functions URL: `<region>-<project>.cloudfunctions.net`.
private val FUNCTIONS_HOST = Regex("""^[a-z]+-[a-z]+\d+-([a-z][a-z0-9-]{4,28}[a-z0-9])\.cloudfunctions\.net$""")

/**
 * Where the alerts ingester's files are (docs/ALERTS.md → The published
 * files): [override] (the build's `ALERTS_BASE_URL`) when it's an https
 * address, else the `<project>-alerts` bucket of the project the TTS proxy at
 * [proxyUrl] runs in. Null when neither gives one, and alerts then read as
 * unavailable everywhere.
 */
internal fun alertsBaseUrl(override: String, proxyUrl: String): String? {
    override.trim().trimEnd('/').takeIf { it.startsWith("https://") }?.let { return it }
    val host = try {
        URI(proxyUrl.trim()).host
    } catch (e: URISyntaxException) {
        // Reported by the caller as "no alerts source"; the TTS proxy logs its own.
        null
    } ?: return null
    val project = FUNCTIONS_HOST.matchEntire(host)?.groupValues?.get(1) ?: return null
    return "https://storage.googleapis.com/$project-alerts"
}
