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
 * route audio vs. video. When the [CastDevice] bundle isn't populated
 * yet (the AndroidX route API allows `extras` to be null, common for
 * speaker groups), MediaRouter's own metadata is the fallback: a route
 * that is a group, or whose device type is a speaker, has no screen.
 * Google Cast groups are audio-only by design — there are no video
 * groups — so a group route never wants the image+audio MP4.
 *
 * Returns [CastDeviceClass.UNKNOWN] when no signal is conclusive;
 * callers treat UNKNOWN as a display ([mediaPlanFor]) so an
 * unidentified route keeps the proven image+audio path rather than
 * being silently downgraded to audio-only. Worst case an MP4 lands on a
 * speaker, which plays only its audio track — exactly what it would do
 * with the audio-only path anyway.
 */
internal fun classifyRoute(route: MediaRouter.RouteInfo): CastDeviceClass {
    val device = route.extras?.let { CastDevice.getFromBundle(it) }
    return classifyDevice(
        hasCastDevice = device != null,
        hasVideoOut = device?.hasCapability(CastDevice.CAPABILITY_VIDEO_OUT) == true,
        isGroup = route.isGroup,
        deviceType = route.deviceType,
    )
}

/**
 * MediaRouter device types with no screen. The fallback when no
 * [CastDevice] is attached — covers Cast speaker groups
 * ([MediaRouter.RouteInfo.DEVICE_TYPE_GROUP]) and bare speakers.
 */
// DEVICE_TYPE_SPEAKER is deprecated in favor of the split
// builtin/remote-speaker constants, but a route can still report the old
// value, so it's kept alongside DEVICE_TYPE_REMOTE_SPEAKER to preserve the
// audio-only classification. The deprecation is suppressed rather than the
// constant dropped.
@Suppress("DEPRECATION")
private val AUDIO_ONLY_DEVICE_TYPES = setOf(
    MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER,
    MediaRouter.RouteInfo.DEVICE_TYPE_REMOTE_SPEAKER,
    MediaRouter.RouteInfo.DEVICE_TYPE_GROUP,
)

/**
 * Pure decision behind [classifyRoute], split out so the
 * group / speaker / video-out matrix is unit-testable without a real
 * [MediaRouter.RouteInfo].
 */
internal fun classifyDevice(
    hasCastDevice: Boolean,
    hasVideoOut: Boolean,
    isGroup: Boolean,
    deviceType: Int,
): CastDeviceClass = when {
    hasCastDevice && hasVideoOut -> CastDeviceClass.DISPLAY
    hasCastDevice -> CastDeviceClass.AUDIO_ONLY
    isGroup -> CastDeviceClass.AUDIO_ONLY
    deviceType in AUDIO_ONLY_DEVICE_TYPES -> CastDeviceClass.AUDIO_ONLY
    else -> CastDeviceClass.UNKNOWN
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
