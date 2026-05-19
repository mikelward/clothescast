package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.TtsEngine
import app.clothescast.core.domain.model.UserPreferences

/**
 * Pre-alignment gate algebra for a single worker run. SPEC.md
 * §"Compute the gates" — distilled into a pure function so the worker
 * can read the right flags off one snapshot of [UserPreferences]
 * without scattering the rules across [deliverToday] / [deliverTonight]
 * branches.
 *
 * Everything here can be decided from preferences + period + a couple
 * of pre-computed booleans ([geminiAvailable], [mqttPublishable]).
 * Runtime-only signals stay in the worker:
 *
 *  - `phoneRequested` depends on a fresh location fix (skip-at-home),
 *    evaluated post-alignment.
 *  - MQTT-audio gating depends on whether the synth coroutine actually
 *    produced a PCM buffer, evaluated post-synth.
 *
 * Keeping the gates pure means the spec's behaviour-matrix rows
 * become straight unit tests, independent of the worker's coroutine
 * scaffolding.
 *
 * Pre-cast-feature scope. The Cast destination's gates
 * (`willCast`, `castWillHaveAudio`) will join this struct when the
 * cast wiring lands; the disjunction in [needsSynth] is shaped to
 * extend cleanly when they do.
 */
data class DeliveryGates(
    /**
     * User wants the phone to speak this period (delivery-mode says so).
     * Doesn't fold in skip-at-home — that's a runtime check.
     */
    val phoneTtsConfigured: Boolean,
    /**
     * Tonight runs with "only notify on events" AND no events in the
     * evening window. Suppresses notification and phone speaker.
     * **Does not suppress MQTT publishes** — HA still wants fresh
     * retained topics on every run.
     */
    val emptyEveningSkip: Boolean,
    /**
     * Bridge publishable: toggle on AND host non-empty. A blank host
     * effectively disables the bridge even when the toggle is on, so
     * `mqttPublishable = false` for a misconfigured bridge.
     */
    val mqttPublishable: Boolean,
    /**
     * True when a Gemini PCM buffer is needed by some destination:
     * MQTT audio publish, or phone speaker on the Gemini engine when
     * the evening isn't being skipped.
     *
     * Gemini is the only producer of routable PCM, so [geminiAvailable]
     * is a hard prerequisite — Device TTS does its own synth at
     * playback time and exposes no buffer.
     */
    val needsSynth: Boolean,
)

/**
 * Compute [DeliveryGates] from a single preferences snapshot.
 *
 * @param prefs preferences read once at the top of the worker run
 * @param period TODAY or TONIGHT — selects which delivery-mode applies
 * @param insightHasEvents true if the calendar has at least one event
 *   in this period's window. Only consulted on TONIGHT to evaluate
 *   `tonightNotifyOnlyOnEvents`.
 * @param geminiAvailable pre-computed: TTS engine = Gemini AND API
 *   key configured. The worker checks the keystore once and feeds
 *   that boolean in. A later synth failure inside the worker doesn't
 *   change this gate; it just makes the MQTT-audio check fall
 *   through (no buffer to publish).
 * @param mqttPublishable bridge enabled AND host non-empty (see
 *   [isMqttPublishable]).
 */
fun computeDeliveryGates(
    prefs: UserPreferences,
    period: ForecastPeriod,
    insightHasEvents: Boolean,
    geminiAvailable: Boolean,
    mqttPublishable: Boolean,
): DeliveryGates {
    val mode = when (period) {
        ForecastPeriod.TODAY -> prefs.deliveryMode
        ForecastPeriod.TONIGHT -> prefs.tonightDeliveryMode
    }
    val phoneTtsConfigured = mode == DeliveryMode.TTS_ONLY ||
        mode == DeliveryMode.NOTIFICATION_AND_TTS

    // Empty-evening only applies to TONIGHT. TODAY is never skipped
    // here regardless of `tonightNotifyOnlyOnEvents`.
    val emptyEveningSkip = period == ForecastPeriod.TONIGHT &&
        prefs.tonightNotifyOnlyOnEvents &&
        !insightHasEvents

    // Gemini is the only producer of routable PCM. When the engine is
    // Device, on-device TTS synthesises at playback time with no
    // exposed buffer — there's nothing to route to MQTT, so needsSynth
    // stays false on the default Device-TTS path and a BYOK Gemini
    // request never fires there.
    //
    // Two consumers want a buffer pre-alignment:
    //   - mqttPublishable: the retained ${topic}/audio is a destination
    //     in its own right and survives skip-at-home / emptyEveningSkip
    //     (HA automations want fresh audio regardless).
    //   - phoneTtsConfigured && !emptyEveningSkip: phone speaker on
    //     Gemini, evening isn't being skipped.
    val needsSynth = geminiAvailable && (
        mqttPublishable ||
            (phoneTtsConfigured && !emptyEveningSkip)
        )

    return DeliveryGates(
        phoneTtsConfigured = phoneTtsConfigured,
        emptyEveningSkip = emptyEveningSkip,
        mqttPublishable = mqttPublishable,
        needsSynth = needsSynth,
    )
}

/**
 * MQTT bridge publishability rule, captured once so the worker and
 * the gate function agree on it. Matches [MqttPublisher.preparePublish]:
 * toggle on AND host non-empty. A blank-host config effectively
 * disables the bridge regardless of the toggle, so downstream code
 * shouldn't read `mqttBridgeEnabled` directly.
 */
fun isMqttPublishable(prefs: UserPreferences): Boolean =
    prefs.mqttBridgeEnabled && !prefs.mqttHost.isNullOrBlank()

/**
 * Whether the Gemini engine is currently selected. The worker passes
 * the API-key-set check separately because the keystore lookup is a
 * side effect (touches the encrypted store) that doesn't belong on a
 * pure function. Caller AND's this with the key-set boolean to feed
 * [computeDeliveryGates]' `geminiAvailable` parameter.
 */
fun isGeminiEngineSelected(prefs: UserPreferences): Boolean =
    prefs.ttsEngine == TtsEngine.GEMINI
