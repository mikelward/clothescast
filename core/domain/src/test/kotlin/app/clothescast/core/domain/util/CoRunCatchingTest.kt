package app.clothescast.core.domain.util

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class CoRunCatchingTest {

    @Test
    fun `success wraps the value`() {
        coRunCatching { 42 }.getOrNull() shouldBe 42
    }

    @Test
    fun `non-cancellation throwable is captured as a failed Result`() {
        val result = coRunCatching { error("boom") }
        result.isFailure shouldBe true
        result.exceptionOrNull()!!.message shouldBe "boom"
    }

    @Test
    fun `CancellationException is re-thrown rather than wrapped`() {
        shouldThrow<CancellationException> {
            coRunCatching { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun `cancellation of the surrounding coroutine still cancels the parent`() = runTest {
        // Sanity check that using coRunCatching inside an async block lets
        // structured cancellation work: if the helper swallowed
        // CancellationException, awaiting the cancelled deferred would not
        // throw.
        val deferred = async {
            coRunCatching { throw CancellationException("inner") }
        }
        shouldThrow<CancellationException> { deferred.await() }
    }
}
