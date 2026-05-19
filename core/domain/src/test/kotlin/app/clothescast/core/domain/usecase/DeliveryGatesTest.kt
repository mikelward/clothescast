package app.clothescast.core.domain.usecase

import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.DistanceUnit
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
 *
 * Cast-destination gates land in a follow-up; for now the matrix
 * covers the existing destinations (phone notification, phone TTS,
 * MQTT bridge).
 */
class DeliveryGatesTest {

    private val basePrefs = UserPreferences(
        schedule = Schedule.default(ZoneOffset.UTC),
        deliveryMode = DeliveryMode.NOTIFICATION_AND_TTS,
        temperatureUnit = TemperatureUnit.CELSIUS,
        distanceUnit = DistanceUnit.KILOMETERS,
        clothesRules = ClothesRule.DEFAULTS,
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
}
