package app.clothescast.core.domain.util

import kotlinx.coroutines.CancellationException

/**
 * Coroutine-aware [runCatching]. Identical to the stdlib version except that
 * [CancellationException] is re-thrown rather than wrapped into a failed
 * [Result] — keeping the body cooperatively cancellable instead of letting
 * a `withTimeout`, `Job.cancel`, or `WorkManager` stop request sail through
 * to a stale write.
 *
 * Use this anywhere you'd reach for `runCatching` inside a `suspend` function
 * or a coroutine builder. See KT-58938 for why the stdlib version is unsafe
 * here.
 */
inline fun <R> coRunCatching(block: () -> R): Result<R> = try {
    Result.success(block())
} catch (c: CancellationException) {
    throw c
} catch (t: Throwable) {
    Result.failure(t)
}
