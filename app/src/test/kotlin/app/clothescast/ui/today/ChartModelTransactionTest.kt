package app.clothescast.ui.today

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Pins when [ChartModelTransaction] builds a chart's model.
 *
 * The snapshot tests need the model built before the chart host below the
 * call registers, or a capture can catch the card with its plot missing. The
 * app needs the opposite: composition must never wait on the build. Each test
 * reads the transaction's state from the line right after the call, which
 * runs in the same composition pass, so neither result depends on how fast
 * the background thread is.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ChartModelTransactionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `under inspection mode the model is built before composition moves on`() {
        val producer = CartesianChartModelProducer()
        var builtBeforeNextLine: Boolean? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                var built = false
                ChartModelTransaction(producer, 1) {
                    built = true
                    lineModel { series(1, 2, 3) }
                }
                if (builtBeforeNextLine == null) builtBeforeNextLine = built
            }
        }
        assertEquals(true, builtBeforeNextLine)
    }

    @Test
    fun `at runtime the model is built after composition, off the composing thread`() {
        val producer = CartesianChartModelProducer()
        var builtBeforeNextLine: Boolean? = null
        val built = CountDownLatch(1)
        composeRule.setContent {
            var builtHere = false
            ChartModelTransaction(producer, 1) {
                builtHere = true
                built.countDown()
                lineModel { series(1, 2, 3) }
            }
            if (builtBeforeNextLine == null) builtBeforeNextLine = builtHere
        }
        assertEquals(false, builtBeforeNextLine)
        // The effect still runs the transaction; the bound only turns a
        // transaction that never ran into a failure instead of a hang.
        assertTrue(built.await(10, TimeUnit.SECONDS))
    }
}
