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
 */
data class DeliveryGates(
    /**
     * User wants the phone to speak this period (delivery-mode says so).
     * Doesn't fold in skip-at-home — that's a runtime check.
     */
    val phoneTtsConfigured: Boolean,
    /**
     * A smart display is picked AND the period's cast toggle is on.
     * Lights up the cast destination in the post-alignment fan-out.
     */
    val willCast: Boolean,
    /**
     * Pre-synth prediction: "based on settings, this run should try
     * to carry audio on the cast." True iff [willCast] AND
     * `geminiAvailable`. Drives the cast-side contribution to
     * [needsSynth].
     *
     * Doesn't guarantee a buffer will exist by the cast-load step —
     * a runtime synth failure can leave us with
     * `castWillHaveAudio = true` but no PCM. The runtime "does the
     * cast actually have audio?" decision is `willCast && (synth
     * produced a buffer)`, evaluated in the worker post-synth.
     */
    val castWillHaveAudio: Boolean,
    /**
     * Tonight runs with "only notify on events" AND no events in the
     * evening window. Suppresses notification, phone speaker, **and
     * cast**. Does not suppress MQTT publishes — HA still wants
     * fresh retained topics on every run.
     *
     * Cleared by [forcePhoneSpeech]: an explicit Play tap is a "deliver
     * this period now" request, so the empty-evening suppression doesn't
     * apply — the user gets the notification, speech, and cast they asked
     * for even on an eventless evening.
     */
    val emptyEveningSkip: Boolean,
    /**
     * Bridge publishable: toggle on AND host non-empty. A blank host
     * effectively disables the bridge even when the toggle is on, so
     * `mqttPublishable = false` for a misconfigured bridge.
     */
    val mqttPublishable: Boolean,
    /**
     * A successful audio-carrying cast should take over for the phone
     * speaker on this run — `willCast` AND `castSkipPhoneSpeech`. The
     * worker still requires a synth buffer and a *successful* cast at
     * runtime before it actually stays quiet; this flag is just the
     * "settings say the display is the speaker" half.
     *
     * Cleared by [forcePhoneSpeech]: an explicit Play tap is the user
     * asking *this phone* to speak now, so it overrides the takeover —
     * the phone speaks even when the cast also plays.
     */
    val castSuppressesPhone: Boolean,
    /**
     * Mirror of [castSuppressesPhone] for the MQTT bridge: a successful
     * audio publish should take over for the phone speaker when
     * `mqttPublishable` AND `mqttSkipPhoneSpeech`. Same runtime caveat
     * (needs a buffer and a successful publish) and same
     * [forcePhoneSpeech] override — a tapped Play is never silenced just
     * because the bridge published the forecast.
     */
    val mqttSuppressesPhone: Boolean,
    /**
     * True when a Gemini PCM buffer is needed by some destination:
     * MQTT audio publish, audio-carrying cast, or phone speaker on
     * the Gemini engine when the evening isn't being skipped.
     *
     * Gemini is the only producer of routable PCM, so `geminiAvailable`
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
 * @param insightHasEvents true if the calendar has at least one
 *   away-from-home event (non-all-day, with a location — see
 *   [Insight.hasEvents]) in this period's window. Only consulted on
 *   TONIGHT to evaluate `tonightNotifyOnlyOnEvents`.
 * @param geminiAvailable pre-computed: TTS engine = Gemini AND API
 *   key configured. The worker checks the keystore once and feeds
 *   that boolean in. A later synth failure inside the worker doesn't
 *   change this gate; it just makes the MQTT-audio check fall
 *   through (no buffer to publish).
 * @param mqttPublishable bridge enabled AND host non-empty (see
 *   [isMqttPublishable]).
 * @param forcePhoneSpeech the user explicitly asked the phone to speak
 *   *now* (the Today screen's Play button), independent of the scheduled
 *   delivery mode. Overrides `phoneTtsConfigured` so a SILENT /
 *   notification-only user still hears a tapped forecast, and — because
 *   Gemini is the only PCM producer — pulls the phone-speaker term into
 *   `needsSynth` so the buffer exists when the speaker reaches for it.
 *   Also clears `emptyEveningSkip`: an explicit "deliver now" tap is
 *   never suppressed by the tonight "only notify on events" rule, and
 *   clears `castSuppressesPhone` / `mqttSuppressesPhone` so a tapped
 *   forecast is heard on the phone even when a cast or the MQTT bridge
 *   would otherwise take over as the speaker.
 *   Defaults to false: scheduled runs and the Schedule "Play now"
 *   preview keep honouring the delivery mode exactly as before.
 */
fun computeDeliveryGates(
    prefs: UserPreferences,
    period: ForecastPeriod,
    insightHasEvents: Boolean,
    geminiAvailable: Boolean,
    mqttPublishable: Boolean,
    forcePhoneSpeech: Boolean = false,
): DeliveryGates {
    val mode = when (period) {
        ForecastPeriod.TODAY -> prefs.deliveryMode
        ForecastPeriod.TONIGHT -> prefs.tonightDeliveryMode
    }
    val phoneTtsConfigured = forcePhoneSpeech ||
        mode == DeliveryMode.TTS_ONLY ||
        mode == DeliveryMode.NOTIFICATION_AND_TTS

    val castPeriodEnabled = when (period) {
        ForecastPeriod.TODAY -> prefs.castMorning
        ForecastPeriod.TONIGHT -> prefs.castTonight
    }
    val willCast = prefs.castEnabled && prefs.castRouteId != null && castPeriodEnabled
    val castWillHaveAudio = willCast && geminiAvailable

    // Phone-speaker takeover: a cast / MQTT publish only speaks for the
    // phone on a scheduled run. A forced Play is the user asking this
    // phone to speak now, so it overrides both takeovers.
    val castSuppressesPhone = !forcePhoneSpeech && willCast && prefs.castSkipPhoneSpeech
    val mqttSuppressesPhone = !forcePhoneSpeech && mqttPublishable && prefs.mqttSkipPhoneSpeech

    // Empty-evening only applies to TONIGHT. TODAY is never skipped
    // here regardless of `tonightNotifyOnlyOnEvents`. A forced Play
    // ("deliver now") never skips — the user explicitly asked for it.
    val emptyEveningSkip = !forcePhoneSpeech &&
        period == ForecastPeriod.TONIGHT &&
        prefs.tonightNotifyOnlyOnEvents &&
        !insightHasEvents

    // Gemini is the only producer of routable PCM. When the engine is
    // Device, on-device TTS synthesises at playback time with no
    // exposed buffer — there's nothing to route to MQTT or cast, so
    // needsSynth stays false on the default Device-TTS / no-cast path
    // and a BYOK Gemini request never fires there.
    //
    // Three consumers want a buffer pre-alignment:
    //   - mqttPublishable: the retained ${topic}/audio is a destination
    //     in its own right and survives skip-at-home / emptyEveningSkip
    //     (HA automations want fresh audio regardless).
    //   - phoneTtsConfigured && !emptyEveningSkip: phone speaker on
    //     Gemini, evening isn't being skipped.
    //   - willCast && !emptyEveningSkip: audio-carrying cast — under
    //     the outer geminiAvailable guard, bare willCast is sufficient.
    //     Image-only cast (willCast && !geminiAvailable) doesn't drive
    //     synth; the worker attaches a silent WAV stub instead.
    val needsSynth = geminiAvailable && (
        mqttPublishable ||
            (!emptyEveningSkip && (phoneTtsConfigured || willCast))
        )

    return DeliveryGates(
        phoneTtsConfigured = phoneTtsConfigured,
        willCast = willCast,
        castWillHaveAudio = castWillHaveAudio,
        emptyEveningSkip = emptyEveningSkip,
        mqttPublishable = mqttPublishable,
        castSuppressesPhone = castSuppressesPhone,
        mqttSuppressesPhone = mqttSuppressesPhone,
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
