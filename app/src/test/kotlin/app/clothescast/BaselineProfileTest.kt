package app.clothescast

import io.kotest.matchers.collections.shouldContainAll
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Pins the baseline profile's rules. AGP picks the file up by its path alone,
 * so a rename or a lost rule fails nothing else: the release build would just
 * ship without ahead-of-time compiled code, and the first scrolls after every
 * update would stutter while the JIT warms up.
 */
class BaselineProfileTest {
    @Test
    fun `profile compiles the app's and Vico's code ahead of time`() {
        val rules = File("src/main/baseline-prof.txt").readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        rules.shouldContainAll(
            "HSPLapp/clothescast/**->**(**)**",
            "Lapp/clothescast/**;",
            "HSPLcom/patrykandpatrick/vico/**->**(**)**",
            "Lcom/patrykandpatrick/vico/**;",
        )
    }
}
