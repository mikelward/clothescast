package app.clothescast.cast

import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastDevice

/**
 * Whether a Cast route points at a device with a screen (Nest Hub,
 * Chromecast-with-a-TV) or an audio-only smart speaker (Nest Mini, Nest
 * Audio, Google Home, speaker groups). Drives the media shape we send:
 * a display gets the outfit image muxed into an MP4 so the picture shows
 * while the forecast plays; a speaker gets the spoken forecast as
 * audio-only, with no wasted image mux and no trailing silence to hold a
 * screen that isn't there.
 */
enum class CastDeviceClass { DISPLAY, AUDIO_ONLY, UNKNOWN }

/**
 * Classifies a discovered / selected Cast route.
 *
 * [CastDevice.CAPABILITY_VIDEO_OUT] is the authoritative signal — it's
 * the receiver-reported capability Google's own Cast dialog uses to
 * route audio vs. video. The MediaRouter speaker device-type is a
 * fallback for when the [CastDevice] bundle isn't populated yet.
 *
 * Returns [CastDeviceClass.UNKNOWN] when neither signal is conclusive;
 * callers treat UNKNOWN as a display ([mediaPlanFor]) so an
 * unidentified route keeps the proven image+audio path rather than
 * being silently downgraded to audio-only. Worst case an MP4 lands on a
 * speaker, which plays only its audio track — exactly what it would do
 * with the audio-only path anyway.
 */
internal fun classifyRoute(route: MediaRouter.RouteInfo): CastDeviceClass {
    val device = route.extras?.let { CastDevice.getFromBundle(it) }
    return when {
        device != null && device.hasCapability(CastDevice.CAPABILITY_VIDEO_OUT) ->
            CastDeviceClass.DISPLAY
        device != null -> CastDeviceClass.AUDIO_ONLY
        route.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER ->
            CastDeviceClass.AUDIO_ONLY
        else -> CastDeviceClass.UNKNOWN
    }
}

/**
 * The media shape to send for a given device class + audio availability.
 * Pure and Android-free so the full branch matrix is unit-testable
 * without a real Cast device (which neither CI nor the sandbox has).
 */
internal sealed interface CastMediaPlan {
    /** Mux the outfit image + audio into an MP4 — smart displays (and unknowns). */
    data object Mp4 : CastMediaPlan

    /** Host the spoken forecast as audio only — smart speakers. */
    data object Wav : CastMediaPlan

    /**
     * Nothing to play: an audio-only device with no spoken audio
     * (Gemini unavailable / synth failed). A silent track is meaningful
     * on a display — it keeps the outfit image up — but pointless on a
     * speaker, so skip the load entirely.
     */
    data object SkipNoAudio : CastMediaPlan
}

internal fun mediaPlanFor(deviceClass: CastDeviceClass, hasRealAudio: Boolean): CastMediaPlan =
    when (deviceClass) {
        CastDeviceClass.AUDIO_ONLY ->
            if (hasRealAudio) CastMediaPlan.Wav else CastMediaPlan.SkipNoAudio
        CastDeviceClass.DISPLAY, CastDeviceClass.UNKNOWN -> CastMediaPlan.Mp4
    }
