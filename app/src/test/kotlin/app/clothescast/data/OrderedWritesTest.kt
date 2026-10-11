package app.clothescast.data

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class OrderedWritesTest {
    @Test
    fun `writes land in the order they were submitted, a slow one included`() = runTest {
        val landed = mutableListOf<Float>()
        val writes = OrderedWrites(backgroundScope, "test")

        // The first write takes longer than the second. Launched independently
        // the second would land first and leave 1.2 stored; queued, 1.4 is last.
        writes.submit("text size") { delay(100); landed += 1.2f }
        writes.submit("text size") { landed += 1.4f }
        // advanceUntilIdle doesn't run backgroundScope work, so step virtual time.
        advanceTimeBy(1_000)
        runCurrent()

        landed shouldBe listOf(1.2f, 1.4f)
    }

    @Test
    fun `a refused write does not stop the ones after it`() = runTest {
        val landed = mutableListOf<String>()
        val writes = OrderedWrites(backgroundScope, "test")

        writes.submit("text size") { throw IOException("disk full") }
        writes.submit("pinch switch") { landed += "pinch" }
        // advanceUntilIdle doesn't run backgroundScope work, so step virtual time.
        advanceTimeBy(1_000)
        runCurrent()

        landed shouldBe listOf("pinch")
    }
}
