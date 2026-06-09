package app.clothescast.cast

import android.content.Context
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Wraps the Cast SDK's [MediaRouter] for the Settings → Cast picker.
 * Each [discoverRoutes] subscription starts an active discovery scan
 * filtered to the Default Media Receiver app id; on cancel / dispose
 * the scan stops. The emitted list updates as routes appear and
 * disappear on the LAN — the typical "click Scan, watch the list fill
 * over 1–3 seconds, pick one" picker flow.
 *
 * For mid-cast / test-cast orchestration (selecting a saved route by
 * id and waiting for the resulting session to start) see
 * [CastTestAction].
 */
class CastRouteDiscovery(private val context: Context) {

    private val selector: MediaRouteSelector = MediaRouteSelector.Builder()
        .addControlCategory(
            CastMediaControlIntent.categoryForCast(
                CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID,
            ),
        )
        .build()

    /**
     * Cold flow that registers a [MediaRouter] callback for the duration
     * of the subscription. Emits the current snapshot of discovered
     * Cast routes (sorted alphabetically by name) on every change.
     * The first emission is the routes already known to MediaRouter
     * when the scan starts — usually empty on a cold open.
     */
    fun discoverRoutes(): Flow<List<DiscoveredCastRoute>> = callbackFlow {
        val router = MediaRouter.getInstance(context)
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
                trySend(snapshot(router))
            }
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
                trySend(snapshot(router))
            }
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) {
                trySend(snapshot(router))
            }
        }
        router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        trySend(snapshot(router))
        awaitClose {
            router.removeCallback(callback)
        }
    }

    private fun snapshot(router: MediaRouter): List<DiscoveredCastRoute> =
        router.routes
            .filter { it.matchesSelector(selector) && !it.isDefault }
            .map {
                DiscoveredCastRoute(
                    id = it.id,
                    name = it.name,
                    isAudioOnly = classifyRoute(it) == CastDeviceClass.AUDIO_ONLY,
                )
            }
            .sortedBy { it.name.lowercase() }
}

/**
 * The picker's view of a single Cast route. [id] is the stable
 * MediaRouter route id we persist to [app.clothescast.data.SettingsRepository.setCastRoute];
 * [name] is the friendly label the user sees in the picker and in
 * the Settings row. [isAudioOnly] is true for a device with no screen
 * (Nest Mini/Audio, Google Home, speaker groups) so the picker can flag
 * it as a speaker; false for displays and for routes we couldn't
 * classify (treated as displays — see [classifyRoute]).
 */
data class DiscoveredCastRoute(
    val id: String,
    val name: String,
    val isAudioOnly: Boolean = false,
)
