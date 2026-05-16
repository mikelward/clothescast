package app.clothescast.mqtt

import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.DistanceUnit
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.Schedule
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.UserPreferences
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalTime
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class MqttPublisherTest {

    @BeforeEach
    fun setUp() {
        // Mirrors SecureKeyStoreTest — the AndroidDispatcherFactory on the
        // test classpath calls Looper.getMainLooper() at static init, which
        // throws under the AGP stub jar. Install a TestMainDispatcher up front.
        Dispatchers.setMain(UnconfinedTestDispatcher(TestCoroutineScheduler()))
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `publishes today insight to today topic with retained payload`() = runTest {
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(
                    mqttBridgeEnabled = true,
                    mqttHost = "192.168.1.10",
                    mqttPort = 1883,
                ),
            ),
            passwordProvider = { null },
            publish = capturing(captured),
        )

        subject.publishIfEnabled(ForecastPeriod.TODAY, "Today, cool and mild. Wear a sweater.")

        captured shouldHaveSize 1
        val call = captured.single()
        val state = call.messages.single { it.topic == "clothescast/insight/today" }
        state.payload.decodeToString() shouldBe "Today, cool and mild. Wear a sweater."
        state.retain shouldBe true
        call.config.host shouldBe "192.168.1.10"
        call.config.port shouldBe 1883
        call.config.useTls shouldBe false
    }

    @Test
    fun `each refresh re-publishes the HA sensor discovery config alongside the state`() = runTest {
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.local"),
            ),
            passwordProvider = { null },
            publish = capturing(captured),
        )

        subject.publishIfEnabled(ForecastPeriod.TODAY, "Today, cool and mild.")

        val messages = captured.single().messages
        messages shouldHaveSize 2
        val discovery = messages.single { it.topic.startsWith("homeassistant/") }
        discovery.topic shouldBe "homeassistant/sensor/clothescast_today/config"
        discovery.retain shouldBe true
        val discoveryJson = discovery.payload.decodeToString()
        discoveryJson shouldContain "\"state_topic\":\"clothescast/insight/today\""
        discoveryJson shouldContain "\"unique_id\":\"clothescast_today\""
        discoveryJson shouldContain "\"name\":\"Today\""
        discoveryJson shouldContain "\"identifiers\":[\"clothescast\"]"
        discoveryJson shouldContain "\"manufacturer\":\"ClothesCast\""
    }

    @Test
    fun `discovery state_topic mirrors the user's custom prefix`() = runTest {
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(
                    mqttBridgeEnabled = true,
                    mqttHost = "broker.local",
                    mqttTopic = "home/forecast",
                ),
            ),
            passwordProvider = { null },
            publish = capturing(captured),
        )

        subject.publishIfEnabled(ForecastPeriod.TONIGHT, "Tonight, clear and cool.")

        val messages = captured.single().messages
        val discovery = messages.single { it.topic == "homeassistant/sensor/clothescast_tonight/config" }
        val discoveryJson = discovery.payload.decodeToString()
        discoveryJson shouldContain "\"state_topic\":\"home/forecast/tonight\""
        discoveryJson shouldContain "\"unique_id\":\"clothescast_tonight\""
        discoveryJson shouldContain "\"name\":\"Tonight\""
        messages.single { it.topic == "home/forecast/tonight" }
            .payload.decodeToString() shouldBe "Tonight, clear and cool."
    }

    @Test
    fun `tonight period publishes to tonight topic`() = runTest {
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(
                    mqttBridgeEnabled = true,
                    mqttHost = "broker.local",
                    mqttTopic = "home/forecast",
                ),
            ),
            passwordProvider = { null },
            publish = capturing(captured),
        )

        subject.publishIfEnabled(ForecastPeriod.TONIGHT, "Tonight, clear and cool.")

        captured.single().messages.map { it.topic } shouldContainExactly listOf(
            "homeassistant/sensor/clothescast_tonight/config",
            "home/forecast/tonight",
        )
    }

    @Test
    fun `no-op when bridge disabled`() = runTest {
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(
                    mqttBridgeEnabled = false,
                    mqttHost = "broker.local",
                ),
            ),
            passwordProvider = { null },
            publish = capturing(captured),
        )

        subject.publishIfEnabled(ForecastPeriod.TODAY, "ignored")

        captured shouldHaveSize 0
    }

    @Test
    fun `no-op when host blank even if bridge enabled`() = runTest {
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(
                    mqttBridgeEnabled = true,
                    mqttHost = null,
                ),
            ),
            passwordProvider = { null },
            publish = capturing(captured),
        )

        subject.publishIfEnabled(ForecastPeriod.TODAY, "ignored")

        captured shouldHaveSize 0
    }

    @Test
    fun `auth-less prefs do not invoke password provider`() = runTest {
        val captured = mutableListOf<PublishCall>()
        var passwordCalls = 0
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(
                    mqttBridgeEnabled = true,
                    mqttHost = "broker.local",
                    mqttUsername = null,
                ),
            ),
            passwordProvider = {
                passwordCalls++
                "should-not-be-fetched"
            },
            publish = capturing(captured),
        )

        subject.publishIfEnabled(ForecastPeriod.TODAY, "x")

        passwordCalls shouldBe 0
        captured.single().config.password shouldBe null
    }

    @Test
    fun `username present pulls password from provider`() = runTest {
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(
                    mqttBridgeEnabled = true,
                    mqttHost = "broker.local",
                    mqttUsername = "mqtt-user",
                ),
            ),
            passwordProvider = { "secret-from-keystore" },
            publish = capturing(captured),
        )

        subject.publishIfEnabled(ForecastPeriod.TODAY, "x")

        val call = captured.single()
        call.config.username shouldBe "mqtt-user"
        call.config.password shouldBe "secret-from-keystore"
    }

    @Test
    fun `publish failure is swallowed so worker is not impacted`() = runTest {
        var attempted = false
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(
                    mqttBridgeEnabled = true,
                    mqttHost = "broker.local",
                ),
            ),
            passwordProvider = { null },
            publish = { _, _ ->
                attempted = true
                error("simulated broker rejection")
            },
        )

        // No exception escapes; the call returns normally.
        subject.publishIfEnabled(ForecastPeriod.TODAY, "x")

        attempted.shouldBeTrue()
    }

    @Test
    fun `successful publish returns Success outcome`() = runTest {
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.local"),
            ),
            passwordProvider = { null },
            publish = capturing(mutableListOf()),
        )

        val outcome = subject.publishIfEnabled(ForecastPeriod.TODAY, "x")

        outcome shouldBe MqttPublishOutcome.Success
    }

    @Test
    fun `disabled bridge returns NotConfigured`() = runTest {
        val subject = MqttPublisher(
            preferences = flowOf(basePrefs.copy(mqttBridgeEnabled = false, mqttHost = "broker.local")),
            passwordProvider = { null },
            publish = { _, _ -> },
        )

        subject.publishIfEnabled(ForecastPeriod.TODAY, "x") shouldBe MqttPublishOutcome.NotConfigured
    }

    @Test
    fun `blank host returns NotConfigured`() = runTest {
        val subject = MqttPublisher(
            preferences = flowOf(basePrefs.copy(mqttBridgeEnabled = true, mqttHost = null)),
            passwordProvider = { null },
            publish = { _, _ -> },
        )

        subject.publishIfEnabled(ForecastPeriod.TODAY, "x") shouldBe MqttPublishOutcome.NotConfigured
    }

    @Test
    fun `publish failure returns Failure outcome with message`() = runTest {
        val subject = MqttPublisher(
            preferences = flowOf(basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.local")),
            passwordProvider = { null },
            publish = { _, _ -> error("simulated broker rejection") },
        )

        val outcome = subject.publishIfEnabled(ForecastPeriod.TODAY, "x")

        val failure = outcome.shouldBeInstanceOf<MqttPublishOutcome.Failure>()
        failure.message shouldBe "IllegalStateException: simulated broker rejection"
    }

    @Test
    fun `timeout returns Failure outcome`() = runTest {
        val subject = MqttPublisher(
            preferences = flowOf(basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.local")),
            passwordProvider = { null },
            publish = { _, _ ->
                while (true) {
                    coroutineContext.ensureActive()
                    kotlinx.coroutines.delay(1_000)
                }
            },
            publishTimeoutMs = 50L,
        )

        val outcome = subject.publishIfEnabled(ForecastPeriod.TODAY, "x")

        outcome.shouldBeInstanceOf<MqttPublishOutcome.Failure>()
    }

    @Test
    fun `publishTest routes to test topic and emits discovery for every sensor and image entity`() = runTest {
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(
                    mqttBridgeEnabled = true,
                    mqttHost = "broker.local",
                    mqttTopic = "home/forecast",
                ),
            ),
            passwordProvider = { null },
            publish = capturing(captured),
        )

        val outcome = subject.publishTest()

        outcome shouldBe MqttPublishOutcome.Success
        val messages = captured.single().messages
        messages.map { it.topic } shouldContainExactly listOf(
            "homeassistant/sensor/clothescast_today/config",
            "homeassistant/sensor/clothescast_tonight/config",
            "homeassistant/image/clothescast_today_image/config",
            "homeassistant/image/clothescast_tonight_image/config",
            "home/forecast/test",
        )
    }

    @Test
    fun `topic prefix is sanitised — leading and trailing slashes trimmed`() {
        MqttPublisher.topicFor("/clothescast/insight/", ForecastPeriod.TODAY) shouldBe
            "clothescast/insight/today"
        MqttPublisher.topicFor("home/forecast", ForecastPeriod.TONIGHT) shouldBe
            "home/forecast/tonight"
        // Empty / blank base falls back to the documented default so a
        // hand-edited DataStore can't produce a bare "/today" topic.
        MqttPublisher.topicFor("", ForecastPeriod.TODAY) shouldBe "clothescast/insight/today"
        MqttPublisher.topicFor("   ", ForecastPeriod.TONIGHT) shouldBe "clothescast/insight/tonight"
    }

    @Test
    fun `imageTopicFor appends image suffix to prose topic`() {
        MqttPublisher.imageTopicFor("clothescast/insight", ForecastPeriod.TODAY) shouldBe
            "clothescast/insight/today/image"
        MqttPublisher.imageTopicFor("home/forecast", ForecastPeriod.TONIGHT) shouldBe
            "home/forecast/tonight/image"
        MqttPublisher.imageTopicFor("", ForecastPeriod.TODAY) shouldBe
            "clothescast/insight/today/image"
    }

    @Test
    fun `discovery topic shapes match the HA-standard config-topic layout`() {
        MqttPublisher.discoveryTopicFor(ForecastPeriod.TODAY, MqttPublisher.Component.SENSOR) shouldBe
            "homeassistant/sensor/clothescast_today/config"
        MqttPublisher.discoveryTopicFor(ForecastPeriod.TONIGHT, MqttPublisher.Component.SENSOR) shouldBe
            "homeassistant/sensor/clothescast_tonight/config"
        MqttPublisher.discoveryTopicFor(ForecastPeriod.TODAY, MqttPublisher.Component.IMAGE) shouldBe
            "homeassistant/image/clothescast_today_image/config"
        MqttPublisher.discoveryTopicFor(ForecastPeriod.TONIGHT, MqttPublisher.Component.IMAGE) shouldBe
            "homeassistant/image/clothescast_tonight_image/config"
    }

    @Test
    fun `publishImageIfEnabled publishes PNG bytes alongside image-entity discovery config`() = runTest {
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(
                    mqttBridgeEnabled = true,
                    mqttHost = "broker.local",
                    mqttTopic = "clothescast/insight",
                ),
            ),
            passwordProvider = { null },
            publish = capturing(captured),
        )
        val fakeImage = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) // PNG magic bytes

        subject.publishImageIfEnabled(ForecastPeriod.TODAY, fakeImage)

        captured shouldHaveSize 1
        val messages = captured.single().messages
        messages.map { it.topic } shouldContainExactly listOf(
            "homeassistant/image/clothescast_today_image/config",
            "clothescast/insight/today/image",
        )
        val imageMessage = messages.last()
        imageMessage.payload contentEquals fakeImage shouldBe true
        val discoveryJson = messages.first().payload.decodeToString()
        discoveryJson shouldContain "\"image_topic\":\"clothescast/insight/today/image\""
        discoveryJson shouldContain "\"content_type\":\"image/png\""
        discoveryJson shouldContain "\"unique_id\":\"clothescast_today_outfit\""
    }

    @Test
    fun `publishImageIfEnabled no-op when bridge disabled`() = runTest {
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(basePrefs.copy(mqttBridgeEnabled = false, mqttHost = "broker.local")),
            passwordProvider = { null },
            publish = capturing(captured),
        )

        subject.publishImageIfEnabled(ForecastPeriod.TODAY, byteArrayOf(1, 2, 3))

        captured shouldHaveSize 0
    }

    @Test
    fun `publish throwing CancellationException propagates upward`() = runTest {
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.local"),
            ),
            passwordProvider = { null },
            publish = { _, _ -> throw CancellationException("simulated worker cancel") },
        )

        // Without explicit CancellationException rethrow inside the publish
        // try/catch, this would be swallowed and the call would return
        // normally — letting WorkManager's stopped state be misreported as
        // a successful delivery.
        shouldThrow<CancellationException> {
            subject.publishIfEnabled(ForecastPeriod.TODAY, "x")
        }
    }

    @Test
    fun `preferences flow throwing CancellationException propagates upward`() = runTest {
        val subject = MqttPublisher(
            preferences = flow { throw CancellationException("flow cancelled") },
            passwordProvider = { null },
            publish = { _, _ -> error("must not run when prefs read is cancelled") },
        )

        shouldThrow<CancellationException> {
            subject.publishIfEnabled(ForecastPeriod.TODAY, "x")
        }
    }

    @Test
    fun `password provider throwing CancellationException propagates upward`() = runTest {
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(
                    mqttBridgeEnabled = true,
                    mqttHost = "broker.local",
                    mqttUsername = "mqtt-user",
                ),
            ),
            passwordProvider = { throw CancellationException("keystore read cancelled") },
            publish = { _, _ -> error("must not run when password read is cancelled") },
        )

        shouldThrow<CancellationException> {
            subject.publishIfEnabled(ForecastPeriod.TODAY, "x")
        }
    }

    @Test
    fun `publish timeout triggers a no-throw fallthrough`() = runTest {
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(
                    mqttBridgeEnabled = true,
                    mqttHost = "broker.local",
                ),
            ),
            passwordProvider = { null },
            publish = { _, _ ->
                // Hang past the configured timeout. Yields so withTimeoutOrNull
                // can fire; runTest's virtual time skips ahead deterministically.
                while (true) {
                    coroutineContext.ensureActive()
                    kotlinx.coroutines.delay(1_000)
                }
            },
            publishTimeoutMs = 50L,
        )

        subject.publishIfEnabled(ForecastPeriod.TODAY, "x")
        // Reaching this line means withTimeoutOrNull returned without surfacing
        // a TimeoutCancellationException — the contract the worker relies on.
    }

    private data class PublishCall(val config: MqttConfig, val messages: List<MqttMessage>)

    private fun capturing(into: MutableList<PublishCall>): suspend (MqttConfig, List<MqttMessage>) -> Unit =
        { config, messages -> into.add(PublishCall(config, messages)) }

    private val basePrefs = UserPreferences(
        schedule = Schedule(time = LocalTime.of(7, 0), days = Schedule.EVERY_DAY, zoneId = ZoneId.of("UTC")),
        deliveryMode = DeliveryMode.NOTIFICATION_AND_TTS,
        temperatureUnit = TemperatureUnit.CELSIUS,
        distanceUnit = DistanceUnit.KILOMETERS,
        clothesRules = emptyList(),
    )
}
