package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.WindSpeedUnit
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.Schedule
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.TtsEngine
import app.clothescast.core.domain.model.UserPreferences
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

/**
 * Behaviour-matrix coverage for [computeDeliveryGates]. The worker's
 * coroutine plumbing is intentionally out of scope here — these tests
 * pin the gate algebra so the spec's branching logic stays inspectable
 * without spinning up Robolectric or a fake Cast/MQTT/TTS stack.
 */
class DeliveryGatesTest {

    private val basePrefs = UserPreferences(
        schedule = Schedule.default(ZoneOffset.UTC),
        deliveryMode = DeliveryMode.NOTIFICATION_AND_TTS,
        temperatureUnit = TemperatureUnit.CELSIUS,
        windSpeedUnit = WindSpeedUnit.KMH,
        clothesRules = ClothesRule.DEFAULTS,
        // Cast now defaults off (opt-in); these gate tests exercise an
        // enabled-cast baseline and set castRouteId per case. The master-off
        // case overrides this back to false explicitly.
        castEnabled = true,
    )

    @Test
    fun `default morning + Gemini + no bridge — needsSynth for phone speech`() {
        val gates = computeDeliveryGates(
            prefs = basePrefs.copy(ttsEngine = TtsEngine.GEMINI),
            period = ForecastPeriod.TODAY,
            insightHasEvents = false,
            geminiAvailable = true,
            mqttPublishable = false,
        )
        gates.phoneTtsConfigured shouldBe true
        gates.emptyEveningSkip shouldBe false
        gates.mqttPublishable shouldBe false
        gates.needsSynth shouldBe true
    }

    @Test
    fun `default morning + Device TTS — no synth`() {
        // Device-engine path: on-device TTS does its own synth at
        // playback time, so the worker shouldn't burn a Gemini call.
        val gates = computeDeliveryGates(
            prefs = basePrefs.copy(ttsEngine = TtsEngine.DEVICE),
            period = ForecastPeriod.TODAY,
            insightHasEvents = false,
            geminiAvailable = false,
            mqttPublishable = false,
        )
        gates.phoneTtsConfigured shouldBe true
        gates.needsSynth shouldBe false
    }

    @Test
    fun `notification-only mode + no bridge — no synth`() {
        // Phone speaker disabled and nothing else wants the buffer.
        val gates = computeDeliveryGates(
            prefs = basePrefs.copy(
                ttsEngine = TtsEngine.GEMINI,
                deliveryMode = DeliveryMode.NOTIFICATION_ONLY,
            ),
            period = ForecastPeriod.TODAY,
            insightHasEvents = false,
            geminiAvailable = true,
            mqttPublishable = false,
        )
        gates.phoneTtsConfigured shouldBe false
        gates.needsSynth shouldBe false
    }

    @Test
    fun `notification-only + MQTT publishable + Gemini — synth for the bridge`() {
        // The retained ${topic}/audio is its own consumer; synth must
        // fire even when the phone won't speak.
        val gates = computeDeliveryGates(
            prefs = basePrefs.copy(
                ttsEngine = TtsEngine.GEMINI,
                deliveryMode = DeliveryMode.NOTIFICATION_ONLY,
            ),
            period = ForecastPeriod.TODAY,
            insightHasEvents = false,
            geminiAvailable = true,
            mqttPublishable = true,
        )
        gates.phoneTtsConfigured shouldBe false
        gates.mqttPublishable shouldBe true
        gates.needsSynth shouldBe true
    }

    @Test
    fun `MQTT publishable but engine = Device — no synth`() {
        // Gemini is the only PCM producer; Device-engine + bridge-on
        // can't satisfy the audio topic from this run, so no Gemini
        // request fires.
        val gates = computeDeliveryGates(
            prefs = basePrefs.copy(ttsEngine = TtsEngine.DEVICE),
            period = ForecastPeriod.TODAY,
            insightHasEvents = false,
            geminiAvailable = false,
            mqttPublishable = true,
        )
        gates.mqttPublishable shouldBe true
        gates.needsSynth shouldBe false
    }

    @Test
    fun `tonight + notify-only-on-events + no events — emptyEveningSkip`() {
        val gates = computeDeliveryGates(
            prefs = basePrefs.copy(
                tonightDeliveryMode = DeliveryMode.NOTIFICATION_AND_TTS,
                tonightNotifyOnlyOnEvents = true,
            ),
            period = ForecastPeriod.TONIGHT,
            insightHasEvents = false,
            geminiAvailable = true,
            mqttPublishable = false,
        )
        gates.emptyEveningSkip shouldBe true
        // Phone TTS is suppressed by emptyEveningSkip, and no bridge
        // means no consumer wants the buffer.
        gates.needsSynth shouldBe false
    }

    @Test
    fun `tonight + emptyEveningSkip + MQTT publishable — synth still fires`() {
        // SPEC.md: MQTT publishes survive emptyEveningSkip, so the
        // bridge's audio topic still wants a fresh buffer even on a
        // skipped tonight.
        val gates = computeDeliveryGates(
            prefs = basePrefs.copy(
                tonightDeliveryMode = DeliveryMode.NOTIFICATION_AND_TTS,
                tonightNotifyOnlyOnEvents = true,
                ttsEngine = TtsEngine.GEMINI,
            ),
            period = ForecastPeriod.TONIGHT,
            insightHasEvents = false,
            geminiAvailable = true,
            mqttPublishable = true,
        )
        gates.emptyEveningSkip shouldBe true
        gates.needsSynth shouldBe true
    }

    @Test
    fun `tonight + notify-only-on-events + events present — no skip`() {
        val gates = computeDeliveryGates(
            prefs = basePrefs.copy(
                tonightDeliveryMode = DeliveryMode.NOTIFICATION_AND_TTS,
                tonightNotifyOnlyOnEvents = true,
                ttsEngine = TtsEngine.GEMINI,
            ),
            period = ForecastPeriod.TONIGHT,
            insightHasEvents = true,
            geminiAvailable = true,
            mqttPublishable = false,
        )
        gates.emptyEveningSkip shouldBe false
        gates.phoneTtsConfigured shouldBe true
        gates.needsSynth shouldBe true
    }

    @Test
    fun `today never gets emptyEveningSkip even with notify-only-on-events`() {
        val gates = computeDeliveryGates(
            prefs = basePrefs.copy(tonightNotifyOnlyOnEvents = true),
            period = ForecastPeriod.TODAY,
            insightHasEvents = false,
            geminiAvailable = true,
            mqttPublishable = false,
        )
        gates.emptyEveningSkip shouldBe false
    }

    @Test
    fun `force phone speech overrides SILENT delivery — phone speaks and synth fires`() {
        // The Today screen's Play button is an explicit "speak now"
        // request. It must override the scheduled delivery mode: a user
        // on SILENT (no automatic chime) who taps Play still wants to
        // hear the forecast, and on Gemini that means synth has to run
        // so the phone speaker has a buffer to play.
        val gates = computeDeliveryGates(
            prefs = basePrefs.copy(
                ttsEngine = TtsEngine.GEMINI,
                deliveryMode = DeliveryMode.SILENT,
            ),
            period = ForecastPeriod.TODAY,
            insightHasEvents = false,
            geminiAvailable = true,
            mqttPublishable = false,
            forcePhoneSpeech = true,
        )
        gates.phoneTtsConfigured shouldBe true
        gates.needsSynth shouldBe true
    }

    @Test
    fun `force phone speech clears emptyEveningSkip — Play delivers on an eventless tonight`() {
        // A Play tap is an explicit "deliver this period now," so the
        // tonight "only notify on events" suppression doesn't apply: the
        // user gets the notification, speech, and synth even with no
        // evening events.
        val gates = computeDeliveryGates(
            prefs = basePrefs.copy(
                ttsEngine = TtsEngine.GEMINI,
                tonightDeliveryMode = DeliveryMode.SILENT,
                tonightNotifyOnlyOnEvents = true,
            ),
            period = ForecastPeriod.TONIGHT,
            insightHasEvents = false,
            geminiAvailable = true,
            mqttPublishable = false,
            forcePhoneSpeech = true,
        )
        gates.emptyEveningSkip shouldBe false
        gates.phoneTtsConfigured shouldBe true
        gates.needsSynth shouldBe true
    }

    @Test
    fun `force phone speech is off by default — SILENT stays silent on scheduled runs`() {
        // Without the explicit Play request, SILENT must keep the phone
        // quiet on a scheduled run — forcePhoneSpeech defaults to false.
        val gates = computeDeliveryGates(
            prefs = basePrefs.copy(
                ttsEngine = TtsEngine.GEMINI,
                deliveryMode = DeliveryMode.SILENT,
            ),
            period = ForecastPeriod.TODAY,
            insightHasEvents = false,
            geminiAvailable = true,
            mqttPublishable = false,
        )
        gates.phoneTtsConfigured shouldBe false
        gates.needsSynth shouldBe false
    }

    @Test
    fun `MQTT publishable + skip-phone-speech — bridge takes over phone speech on a scheduled run`() {
        val gates = computeDeliveryGates(
            prefs = basePrefs.copy(
                ttsEngine = TtsEngine.GEMINI,
                mqttSkipPhoneSpeech = true,
            ),
            period = ForecastPeriod.TODAY,
            insightHasEvents = false,
            geminiAvailable = true,
            mqttPublishable = true,
        )
        gates.mqttSuppressesPhone shouldBe true
    }

    @Test
    fun `force phone speech overrides MQTT takeover — tapped Play still speaks on the phone`() {
        // Bug report 2026-06-03: MQTT bridge on with skip-phone-speech
        // silenced a manual Play. The explicit tap must win over the
        // bridge takeover — the phone speaks even though the bridge
        // published the forecast.
        val gates = computeDeliveryGates(
            prefs = basePrefs.copy(
                ttsEngine = TtsEngine.GEMINI,
                mqttSkipPhoneSpeech = true,
            ),
            period = ForecastPeriod.TODAY,
            insightHasEvents = false,
            geminiAvailable = true,
            mqttPublishable = true,
            forcePhoneSpeech = true,
        )
        gates.mqttSuppressesPhone shouldBe false
    }

    @Test
    fun `MQTT skip-phone-speech off — bridge never takes over phone speech`() {
        val gates = computeDeliveryGates(
            prefs = basePrefs.copy(
                ttsEngine = TtsEngine.GEMINI,
                mqttSkipPhoneSpeech = false,
            ),
            period = ForecastPeriod.TODAY,
            insightHasEvents = false,
            geminiAvailable = true,
            mqttPublishable = true,
        )
        gates.mqttSuppressesPhone shouldBe false
    }

    @Test
    fun `cast + skip-phone-speech — display takes over phone speech on a scheduled run`() {
        val gates = computeDeliveryGates(
            prefs = basePrefs.copy(
                ttsEngine = TtsEngine.GEMINI,
                castRouteId = "route-1",
                castMorning = true,
                castSkipPhoneSpeech = true,
            ),
            period = ForecastPeriod.TODAY,
            insightHasEvents = false,
            geminiAvailable = true,
            mqttPublishable = false,
        )
        gates.willCast shouldBe true
        gates.castSuppressesPhone shouldBe true
    }

    @Test
    fun `force phone speech overrides cast takeover — tapped Play still speaks on the phone`() {
        val gates = computeDeliveryGates(
            prefs = basePrefs.copy(
                ttsEngine = TtsEngine.GEMINI,
                castRouteId = "route-1",
                castMorning = true,
                castSkipPhoneSpeech = true,
            ),
            period = ForecastPeriod.TODAY,
            insightHasEvents = false,
            geminiAvailable = true,
            mqttPublishable = false,
            forcePhoneSpeech = true,
        )
        gates.willCast shouldBe true
        gates.castSuppressesPhone shouldBe false
    }

    @Test
    fun `isMqttPublishable — toggle on but blank host is not publishable`() {
        isMqttPublishable(
            basePrefs.copy(mqttBridgeEnabled = true, mqttHost = null),
        ) shouldBe false
        isMqttPublishable(
            basePrefs.copy(mqttBridgeEnabled = true, mqttHost = ""),
        ) shouldBe false
        isMqttPublishable(
            basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "  "),
        ) shouldBe false
        isMqttPublishable(
            basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "homeassistant.local"),
        ) shouldBe true
    }

    @Test
    fun `isMqttPublishable — toggle off is never publishable`() {
        isMqttPublishable(
            basePrefs.copy(mqttBridgeEnabled = false, mqttHost = "homeassistant.local"),
        ) shouldBe false
    }

    // ─── Cast gates ────────────────────────────────────────────────────

    @Test
    fun `no route picked — willCast false even when both period toggles on`() {
        val gates = computeDeliveryGates(
            prefs = basePrefs.copy(
                castRouteId = null,
                castMorning = true,
                castTonight = true,
            ),
            period = ForecastPeriod.TODAY,
            insightHasEvents = false,
            geminiAvailable = true,
            mqttPublishable = false,
        )
        gates.willCast shouldBe false
        gates.castWillHaveAudio shouldBe false
    }

    @Test
    fun `route picked + morning toggle on — willCast on TODAY`() {
        val gates = computeDeliveryGates(
            prefs = basePrefs.copy(
                castRouteId = "route-1",
                castMorning = true,
                castTonight = false,
                ttsEngine = TtsEngine.GEMINI,
            ),
            period = ForecastPeriod.TODAY,
            insightHasEvents = false,
            geminiAvailable = true,
            mqttPublishable = false,
        )
        gates.willCast shouldBe true
        gates.castWillHaveAudio shouldBe true
        gates.needsSynth shouldBe true
    }

    @Test
    fun `route picked + master switch off — willCast off regardless of period toggle`() {
        val gates = computeDeliveryGates(
            prefs = basePrefs.copy(
                castRouteId = "route-1",
                castEnabled = false,
                castMorning = true,
                castTonight = true,
                ttsEngine = TtsEngine.GEMINI,
            ),
            period = ForecastPeriod.TODAY,
            insightHasEvents = false,
            geminiAvailable = true,
            mqttPublishable = false,
        )
        gates.willCast shouldBe false
        gates.castWillHaveAudio shouldBe false
    }

    @Test
    fun `route picked + morning toggle off — willCast off on TODAY`() {
        // Tonight-only user — morning runs skip cast even with route picked.
        val gates = computeDeliveryGates(
            prefs = basePrefs.copy(
                castRouteId = "route-1",
                castMorning = false,
                castTonight = true,
            ),
            period = ForecastPeriod.TODAY,
            insightHasEvents = false,
            geminiAvailable = true,
            mqttPublishable = false,
        )
        gates.willCast shouldBe false
        gates.castWillHaveAudio shouldBe false
    }

    @Test
    fun `route picked + tonight toggle on — willCast on TONIGHT`() {
        val gates = computeDeliveryGates(
            prefs = basePrefs.copy(
                castRouteId = "route-1",
                castMorning = false,
                castTonight = true,
                ttsEngine = TtsEngine.GEMINI,
            ),
            period = ForecastPeriod.TONIGHT,
            insightHasEvents = true,
            geminiAvailable = true,
            mqttPublishable = false,
        )
        gates.willCast shouldBe true
        gates.castWillHaveAudio shouldBe true
        gates.needsSynth shouldBe true
    }

    @Test
    fun `willCast + Gemini unavailable — castWillHaveAudio false, no synth`() {
        // Image-only cast path: smart display will show the outfit
        // PNG silently with a stub WAV; no Gemini call fires.
        val gates = computeDeliveryGates(
            prefs = basePrefs.copy(
                castRouteId = "route-1",
                ttsEngine = TtsEngine.DEVICE,
                deliveryMode = DeliveryMode.NOTIFICATION_ONLY,
            ),
            period = ForecastPeriod.TODAY,
            insightHasEvents = false,
            geminiAvailable = false,
            mqttPublishable = false,
        )
        gates.willCast shouldBe true
        gates.castWillHaveAudio shouldBe false
        gates.needsSynth shouldBe false
    }

    @Test
    fun `willCast + Gemini + notification-only mode — synth fires for cast`() {
        // No phone TTS configured, but the audio-carrying cast still
        // needs a buffer — synth must run.
        val gates = computeDeliveryGates(
            prefs = basePrefs.copy(
                castRouteId = "route-1",
                ttsEngine = TtsEngine.GEMINI,
                deliveryMode = DeliveryMode.NOTIFICATION_ONLY,
            ),
            period = ForecastPeriod.TODAY,
            insightHasEvents = false,
            geminiAvailable = true,
            mqttPublishable = false,
        )
        gates.phoneTtsConfigured shouldBe false
        gates.willCast shouldBe true
        gates.castWillHaveAudio shouldBe true
        gates.needsSynth shouldBe true
    }

    @Test
    fun `willCast + emptyEveningSkip — no cast synth, MQTT still drives`() {
        // emptyEveningSkip suppresses the cast destination. Without
        // a publishable bridge there's no consumer for the buffer.
        val gates = computeDeliveryGates(
            prefs = basePrefs.copy(
                castRouteId = "route-1",
                ttsEngine = TtsEngine.GEMINI,
                tonightDeliveryMode = DeliveryMode.NOTIFICATION_AND_TTS,
                tonightNotifyOnlyOnEvents = true,
            ),
            period = ForecastPeriod.TONIGHT,
            insightHasEvents = false,
            geminiAvailable = true,
            mqttPublishable = false,
        )
        gates.emptyEveningSkip shouldBe true
        gates.willCast shouldBe true  // pre-skip prediction unchanged
        gates.needsSynth shouldBe false

        // Same row + publishable bridge → bridge still wants the buffer.
        val withBridge = computeDeliveryGates(
            prefs = basePrefs.copy(
                castRouteId = "route-1",
                ttsEngine = TtsEngine.GEMINI,
                tonightDeliveryMode = DeliveryMode.NOTIFICATION_AND_TTS,
                tonightNotifyOnlyOnEvents = true,
            ),
            period = ForecastPeriod.TONIGHT,
            insightHasEvents = false,
            geminiAvailable = true,
            mqttPublishable = true,
        )
        withBridge.needsSynth shouldBe true
    }
}
