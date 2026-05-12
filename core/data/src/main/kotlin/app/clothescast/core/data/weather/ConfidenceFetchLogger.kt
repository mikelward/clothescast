package app.clothescast.core.data.weather

/**
 * Lightweight diagnostic seam for the multi-model confidence fetch path. The fetcher
 * is best-effort and historically swallowed every failure as `null`, which made the
 * Today screen's confidence chip silently disappear without surfacing why. This hook
 * lets `:app` wire the drop / fail reasons to logcat without `:core:data` taking an
 * Android dependency.
 *
 * The default implementation is [NoOpConfidenceFetchLogger]; tests and production
 * code wire in their own.
 */
fun interface ConfidenceFetchLogger {
    fun log(message: String, throwable: Throwable?)
}

/** No-throwable convenience for the common case. */
internal fun ConfidenceFetchLogger.log(message: String) = log(message, null)

internal val NoOpConfidenceFetchLogger = ConfidenceFetchLogger { _, _ -> }
