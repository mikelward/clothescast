package app.clothescast.ui.today

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalInspectionMode
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import kotlinx.coroutines.runBlocking

/**
 * Feeds [producer] a fresh model from [block] whenever [keys] change. Call it
 * before the `CartesianChartHost` that reads [producer].
 *
 * At runtime this is a [LaunchedEffect]. Vico's `runTransaction` always builds
 * the model on `Dispatchers.Default`, and the chart draws it when it lands.
 *
 * Under [LocalInspectionMode] (Studio previews and the snapshot tests) the
 * transaction instead finishes during composition, with `runBlocking`, as
 * Vico's own preview samples do. The host composed below then registers with
 * the model already cached and draws it synchronously, on the preview
 * dispatcher Vico switches to in inspection mode. Left as an effect, the
 * transaction raced the host. When the host registered first, the model was
 * built on a background thread that the pinned test clock cannot wait on, so
 * a snapshot could capture the card with its plot missing, twice in a row,
 * and keep it as settled.
 */
@Composable
internal fun ChartModelTransaction(
    producer: CartesianChartModelProducer,
    vararg keys: Any?,
    block: CartesianChartModelProducer.Transaction.() -> Unit,
) {
    if (LocalInspectionMode.current) {
        remember(producer, *keys) { runBlocking { producer.runTransaction(block) } }
    } else {
        LaunchedEffect(producer, *keys) { producer.runTransaction(block) }
    }
}
