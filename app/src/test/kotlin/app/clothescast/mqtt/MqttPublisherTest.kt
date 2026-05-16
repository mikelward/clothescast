package app.clothescast.mqtt

import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.DistanceUnit
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.Schedule
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.UserPreferences
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
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
        call.topic shouldBe "clothescast/insight/today"
        call.payload shouldBe "Today, cool and mild. Wear a sweater."
        call.config.host shouldBe "192.168.1.10"
        call.config.port shouldBe 1883
        call.config.useTls shouldBe false
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

        captured.single().topic shouldBe "home/forecast/tonight"
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
            publish = { _, _, _ ->
                attempted = true
                error("simulated broker rejection")
            },
        )

        // No exception escapes; the call returns normally.
        subject.publishIfEnabled(ForecastPeriod.TODAY, "x")

        attempted.shouldBeTrue()
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
    fun `publish throwing CancellationException propagates upward`() = runTest {
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.local"),
            ),
            passwordProvider = { null },
            publish = { _, _, _ -> throw CancellationException("simulated worker cancel") },
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
            publish = { _, _, _ -> error("must not run when prefs read is cancelled") },
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
            publish = { _, _, _ -> error("must not run when password read is cancelled") },
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
            publish = { _, _, _ ->
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

    private data class PublishCall(val config: MqttConfig, val topic: String, val payload: String)

    private fun capturing(into: MutableList<PublishCall>): suspend (MqttConfig, String, String) -> Unit =
        { config, topic, payload -> into.add(PublishCall(config, topic, payload)) }

    private val basePrefs = UserPreferences(
        schedule = Schedule(time = LocalTime.of(7, 0), days = Schedule.EVERY_DAY, zoneId = ZoneId.of("UTC")),
        deliveryMode = DeliveryMode.NOTIFICATION_AND_TTS,
        temperatureUnit = TemperatureUnit.CELSIUS,
        distanceUnit = DistanceUnit.KILOMETERS,
        clothesRules = emptyList(),
    )
}
