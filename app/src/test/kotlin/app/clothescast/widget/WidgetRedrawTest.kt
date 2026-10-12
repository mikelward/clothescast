package app.clothescast.widget

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * [launchLiveBuild] decides when an open widget session redraws. Missing a
 * change leaves the widget on "tap to open" after the refresh it kicked has
 * landed; a spurious build re-renders the charts for nothing.
 */
class WidgetRedrawTest {
    @Test
    fun `the first build is returned`() = runTest {
        val keys = MutableStateFlow("empty")
        val (initial, _) = launchLiveBuild(keys) { "built from ${keys.value}" }
        initial shouldBe "built from empty"
        coroutineContext.cancelChildren()
    }

    @Test
    fun `a cache write after the first build rebuilds`() = runTest {
        val keys = MutableStateFlow("empty")
        val (_, updates) = launchLiveBuild(keys) { "built from ${keys.value}" }
        val seen = mutableListOf<String>()
        backgroundScope.launch { updates.collect { seen += it } }
        runCurrent()
        keys.value = "fresh"
        runCurrent()
        seen.last() shouldBe "built from fresh"
        coroutineContext.cancelChildren()
    }

    @Test
    fun `an equal key does not rebuild`() = runTest {
        var builds = 0
        val keys = flow {
            emit("a")
            emit("a")
            emit("a")
        }
        launchLiveBuild(keys) { builds++ }
        runCurrent()
        builds shouldBe 1
        coroutineContext.cancelChildren()
    }

    @Test
    fun `a key that flips and flips back during the first build ends on the latest`() = runTest {
        val keys = MutableStateFlow("a")
        val firstBuildRunning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var builds = 0
        var latest: String? = null
        backgroundScope.launch {
            val (initial, updates) = launchLiveBuild(keys) {
                val seenKey = keys.value
                if (++builds == 1) {
                    firstBuildRunning.complete(Unit)
                    // Reads "a", then a "b" arrives before it lands.
                    release.await()
                    return@launchLiveBuild "b"
                }
                seenKey
            }
            latest = initial
            updates.collect { latest = it }
        }
        firstBuildRunning.await()
        keys.value = "b"
        runCurrent()
        keys.value = "a"
        runCurrent()
        release.complete(Unit)
        runCurrent()
        // The stale first build was superseded, so "a" is what stays drawn.
        latest shouldBe "a"
    }
}
