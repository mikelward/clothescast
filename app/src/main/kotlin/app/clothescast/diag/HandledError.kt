package app.clothescast.diag

import java.util.IdentityHashMap
import java.util.Locale

/**
 * Coarse bucket a handled (non-fatal) error is grouped under when it's reported
 * to the crash-reporting service. Derived from the [DiagLog] tag — a developer
 * constant like `"MqttPublisher"`, never user content — so the dashboard can
 * slice reliability problems by area without any free-form text. The [code] is
 * the stable string that ships in the report; keep it short and PII-free.
 */
internal enum class ErrorCategory(val code: String) {
    NETWORK("network"),
    TTS("tts"),
    CAST("cast"),
    MQTT("mqtt"),
    CALENDAR("calendar"),
    WIDGET("widget"),
    LOCATION("location"),
    ALARM("alarm"),
    NOTIFICATION("notification"),
    DISCOVERY("discovery"),
    WORKER("worker"),
    OTHER("other"),
}

/** Best-effort mapping from a [DiagLog] tag to an [ErrorCategory]. */
internal fun categoryForTag(tag: String): ErrorCategory {
    val t = tag.lowercase(Locale.ROOT)
    return when {
        "mqtt" in t -> ErrorCategory.MQTT
        "cast" in t -> ErrorCategory.CAST
        "geocod" in t || "location" in t -> ErrorCategory.LOCATION
        "tts" in t || "speech" in t || "voice" in t -> ErrorCategory.TTS
        "calendar" in t || "holiday" in t || "event" in t -> ErrorCategory.CALENDAR
        "widget" in t || "glance" in t -> ErrorCategory.WIDGET
        // Checked before notification / alarm: "FetchAndNotifyWorker" contains
        // "notif", and a refresh receiver contains "schedule" — but both are
        // worker-pipeline failures first.
        "worker" in t || "fetch" in t || "refresh" in t -> ErrorCategory.WORKER
        "alarm" in t || "schedule" in t -> ErrorCategory.ALARM
        "notif" in t -> ErrorCategory.NOTIFICATION
        "discovery" in t || "mdns" in t -> ErrorCategory.DISCOVERY
        else -> ErrorCategory.OTHER
    }
}

/**
 * The sanitized exception handed to the crash reporter in place of a caught
 * throwable. Its message is the controlled `"[category] tag: OriginalClass"`
 * string (all PII-free constants); each link in the cause chain carries only
 * its original class name. Stack traces are preserved verbatim — frames are
 * `class.method(File:line)`, which carry no runtime values — so the reporter
 * still groups by exception type and call site.
 */
internal class HandledError(message: String, cause: Throwable?) : RuntimeException(message, cause)

/**
 * Number of cause-chain links to copy. Caps a pathological deep chain and,
 * together with the identity guard, prevents a cyclic `cause` from spinning.
 */
private const val MAX_CAUSE_DEPTH = 10

/**
 * Rebuilds [throwable] into a [HandledError] safe to send to the crash-reporting
 * service: every free-form message in the chain is dropped (replaced by the
 * exception's class name) and only the class names + stack traces survive. This
 * is the single chokepoint that keeps a caught exception's message — which can
 * quote a place name, a forecast sentence, a URL with a key, or parsed input —
 * from crossing the device boundary, while preserving the type + call-site
 * signal that makes the report useful.
 *
 * Pure and Firebase-free so it's unit-testable and can't itself leak.
 */
internal fun sanitizeHandledError(tag: String, throwable: Throwable): HandledError {
    val category = categoryForTag(tag)
    val topMessage = "[${category.code}] $tag: ${throwable.javaClass.name}"
    val seen = IdentityHashMap<Throwable, Boolean>()
    seen[throwable] = true
    return buildSanitized(throwable, topMessage, seen, depth = 0)
}

private fun buildSanitized(
    source: Throwable,
    message: String,
    seen: IdentityHashMap<Throwable, Boolean>,
    depth: Int,
): HandledError {
    val cause = source.cause
    val sanitizedCause = if (cause != null && depth < MAX_CAUSE_DEPTH && !seen.containsKey(cause)) {
        seen[cause] = true
        // The cause's own message is intentionally dropped — only its class
        // name is carried forward.
        buildSanitized(cause, cause.javaClass.name, seen, depth + 1)
    } else {
        null
    }
    return HandledError(message, sanitizedCause).apply { stackTrace = source.stackTrace }
}
