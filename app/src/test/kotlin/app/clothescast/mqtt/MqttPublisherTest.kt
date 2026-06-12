package app.clothescast.mqtt

import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.DistanceUnit
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.Schedule
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.UserPreferences
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun `prose-only bundle clears now image and audio with empty payload before now text`() = runTest {
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

        val topics = captured.map { it.topic }
        topics shouldContainAll listOf(
            "clothescast/default/day/text",
            "clothescast/default/now/image",
            "clothescast/default/now/audio",
            "clothescast/default/now/text",
        )
        // now/image and now/audio land *before* now/text so a now/text-triggered
        // consumer never reads a stale image/audio from a previous bundle.
        val nowTextIdx = topics.indexOf("clothescast/default/now/text")
        nowTextIdx shouldBeGreaterThan topics.indexOf("clothescast/default/now/image")
        nowTextIdx shouldBeGreaterThan topics.indexOf("clothescast/default/now/audio")
        // Absent modalities are cleared with an empty retained payload — the
        // MQTT convention for "delete the retained message".
        captured.first { it.topic == "clothescast/default/now/image" }.payload.size shouldBe 0
        captured.first { it.topic == "clothescast/default/now/audio" }.payload.size shouldBe 0
        captured.first { it.topic == "clothescast/default/now/text" }.payload.decodeToString() shouldBe
            "Today, cool and mild. Wear a sweater."
    }

    @Test
    fun `tonight period publishes to tonight text topic, mirrored to now`() = runTest {
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

        captured.map { it.topic } shouldContainAll listOf(
            "home/forecast/night/text",
            "home/forecast/now/image",
            "home/forecast/now/audio",
            "home/forecast/now/text",
        )
    }

    @Test
    fun `next-window publish writes the period topics but never mirrors to now`() = runTest {
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
        val fakeImage = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

        // The next window (e.g. tomorrow's day cast published at the evening
        // run) lands on its own segment with mirrorToNow = false so /now keeps
        // reflecting the current period.
        subject.publishIfEnabled(
            ForecastPeriod.TODAY,
            "Tomorrow, sunny.",
            image = fakeImage,
            mirrorToNow = false,
        )

        captured.map { it.topic } shouldContainAll listOf(
            "home/forecast/day/text",
            "home/forecast/day/image",
        )
        captured.none { it.topic.contains("/now/") }.shouldBeTrue()
    }

    @Test
    fun `next-window publish clears absent period media so the day-night bundle stays coherent`() = runTest {
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
        val fakeImage = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

        // Image only — no audio/video. The next window has no /now backstop, so
        // its own day/audio and day/video must be cleared with empty retained
        // payloads or a consumer reading the day bundle would play a previous
        // run's clip.
        subject.publishIfEnabled(
            ForecastPeriod.TODAY,
            "Tomorrow, sunny.",
            image = fakeImage,
            mirrorToNow = false,
        )

        captured.first { it.topic == "home/forecast/day/image" }.payload.size shouldBeGreaterThan 0
        captured.first { it.topic == "home/forecast/day/audio" }.payload.size shouldBe 0
        captured.first { it.topic == "home/forecast/day/video" }.payload.size shouldBe 0
        captured.none { it.topic.contains("/now/") }.shouldBeTrue()
    }

    @Test
    fun `period bundle writes its own timestamp marker, held back when a period publish fails`() = runTest {
        // Success: the next-window bundle gets a <period>/timestamp commit
        // marker so a direct day/night consumer can trigger on it.
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.local", mqttTopic = "home/forecast"),
            ),
            passwordProvider = { null },
            publish = capturing(captured),
        )
        subject.publishIfEnabled(ForecastPeriod.TONIGHT, "Tonight, clear.", mirrorToNow = false)
        captured.any { it.topic == "home/forecast/night/timestamp" }.shouldBeTrue()

        // Failure: a rejected period audio clear holds the marker back so a
        // consumer never acts on a half-updated <period>/* set.
        val capturedFail = mutableListOf<PublishCall>()
        val failing = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.local", mqttTopic = "home/forecast"),
            ),
            passwordProvider = { null },
            publish = { config, topic, payload ->
                capturedFail.add(PublishCall(config, topic, payload))
                if (topic == "home/forecast/night/audio") error("audio clear rejected")
            },
            retryDelayMs = 1L,
        )
        failing.publishIfEnabled(ForecastPeriod.TONIGHT, "Tonight, clear.", mirrorToNow = false)
        capturedFail.none { it.topic == "home/forecast/night/timestamp" }.shouldBeTrue()
    }

    @Test
    fun `now mirror is skipped when period publish fails`() = runTest {
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.local"),
            ),
            passwordProvider = { null },
            publish = { config, topic, payload ->
                captured.add(PublishCall(config, topic, payload))
                error("simulated broker rejection")
            },
            retryDelayMs = 1L,
        )

        val outcome = subject.publishIfEnabled(ForecastPeriod.TODAY, "x")

        outcome.shouldBeInstanceOf<MqttPublishOutcome.Failure>()
        // Only the period topics (prose + image/audio/video clears) are
        // attempted; the /now mirror is deliberately skipped so a
        // stale-but-truthful retained payload survives instead of being
        // overwritten by a half-failed run.
        captured.none { it.topic.contains("/now/") }.shouldBeTrue()
        captured.any { it.topic == "clothescast/default/day/text" }.shouldBeTrue()
    }

    @Test
    fun `now text mirror failure does not override period success outcome`() = runTest {
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.local"),
            ),
            passwordProvider = { null },
            publish = { config, topic, payload ->
                captured.add(PublishCall(config, topic, payload))
                if (topic.endsWith("/now/text")) error("mirror failure")
            },
            retryDelayMs = 1L,
        )

        val outcome = subject.publishIfEnabled(ForecastPeriod.TODAY, "x")

        outcome shouldBe MqttPublishOutcome.Success
        // /now/text exhausts its retries (broker keeps rejecting it). The
        // prose primary already succeeded, so the Settings UI status row
        // continues to surface Success — the mirror is a side channel.
        captured.count { it.topic == "clothescast/default/now/text" } shouldBe 2
    }

    @Test
    fun `now text mirror is held back when now image mirror fails`() = runTest {
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.local"),
            ),
            passwordProvider = { null },
            publish = { config, topic, payload ->
                captured.add(PublishCall(config, topic, payload))
                if (topic.endsWith("/now/image")) error("now/image mirror failure")
            },
            retryDelayMs = 1L,
        )

        val outcome = subject.publishIfEnabled(ForecastPeriod.TODAY, "x")

        outcome shouldBe MqttPublishOutcome.Success
        // /now/text is deliberately held back so consumers triggered on it
        // don't read a now/image that's still the previous bundle's payload.
        captured.none { it.topic.endsWith("/now/text") }.shouldBeTrue()
    }

    @Test
    fun `now text mirror is held back when now audio mirror fails`() = runTest {
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.local"),
            ),
            passwordProvider = { null },
            publish = { config, topic, payload ->
                captured.add(PublishCall(config, topic, payload))
                if (topic.endsWith("/now/audio")) error("now/audio mirror failure")
            },
            retryDelayMs = 1L,
        )

        val outcome = subject.publishIfEnabled(ForecastPeriod.TODAY, "x")

        outcome shouldBe MqttPublishOutcome.Success
        captured.none { it.topic.endsWith("/now/text") }.shouldBeTrue()
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
        captured.shouldNotBeEmpty()
        captured.first().config.password shouldBe null
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

        val call = captured.first()
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
            publish = { _, _, _ -> },
        )

        subject.publishIfEnabled(ForecastPeriod.TODAY, "x") shouldBe MqttPublishOutcome.NotConfigured
    }

    @Test
    fun `blank host returns NotConfigured`() = runTest {
        val subject = MqttPublisher(
            preferences = flowOf(basePrefs.copy(mqttBridgeEnabled = true, mqttHost = null)),
            passwordProvider = { null },
            publish = { _, _, _ -> },
        )

        subject.publishIfEnabled(ForecastPeriod.TODAY, "x") shouldBe MqttPublishOutcome.NotConfigured
    }

    @Test
    fun `publish failure returns Failure outcome with message`() = runTest {
        val subject = MqttPublisher(
            preferences = flowOf(basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.local")),
            passwordProvider = { null },
            publish = { _, _, _ -> error("simulated broker rejection") },
        )

        val outcome = subject.publishIfEnabled(ForecastPeriod.TODAY, "x")

        val failure = outcome.shouldBeInstanceOf<MqttPublishOutcome.Failure>()
        failure.message shouldBe "IllegalStateException: simulated broker rejection"
    }

    @Test
    fun `network failure surfaces a plain-English hint plus the raw cause`() = runTest {
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "192.168.1.50", mqttPort = 1883),
            ),
            passwordProvider = { null },
            publish = { _, _, _ -> throw java.net.NoRouteToHostException("No route to host") },
            retryDelayMs = 1L,
        )

        val outcome = subject.publishIfEnabled(ForecastPeriod.TODAY, "x")

        val failure = outcome.shouldBeInstanceOf<MqttPublishOutcome.Failure>()
        // Leads with the actionable hint (host:port + firewall guidance) and
        // keeps the raw exception in parens for the bug report.
        failure.message shouldContain "192.168.1.50:1883"
        failure.message shouldContain "firewall"
        failure.message shouldContain "NoRouteToHostException"
    }

    @Test
    fun `unknown-host failure names the address and the same-network hint`() = runTest {
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.invalid"),
            ),
            passwordProvider = { null },
            publish = { _, _, _ -> throw java.net.UnknownHostException("broker.invalid") },
            retryDelayMs = 1L,
        )

        val failure = subject.publishIfEnabled(ForecastPeriod.TODAY, "x")
            .shouldBeInstanceOf<MqttPublishOutcome.Failure>()
        failure.message shouldContain "broker.invalid"
        failure.message shouldContain "same network"
    }

    @Test
    fun `non-network broker rejection keeps the raw message without a hint`() = runTest {
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.local"),
            ),
            passwordProvider = { null },
            publish = { _, _, _ -> error("not authorized") },
            retryDelayMs = 1L,
        )

        val failure = subject.publishIfEnabled(ForecastPeriod.TODAY, "x")
            .shouldBeInstanceOf<MqttPublishOutcome.Failure>()
        // An ACL rejection isn't a network problem, so no firewall hint — the
        // raw class+message is surfaced as-is.
        failure.message shouldBe "IllegalStateException: not authorized"
    }

    @Test
    fun `networkHintFor wording covers each connection-failure kind`() {
        networkHintFor(
            app.clothescast.net.NetworkErrorKind.CONNECTION_REFUSED,
            "broker.local",
            8883,
        )!! shouldContain "refused"
        networkHintFor(app.clothescast.net.NetworkErrorKind.TLS, "broker.local", 8883)!! shouldContain "TLS"
        networkHintFor(app.clothescast.net.NetworkErrorKind.TIMEOUT, "broker.local", 1883)!! shouldContain "offline"
        // Null kind (not a network failure) yields no hint.
        networkHintFor(null, "broker.local", 1883).let { it shouldBe null }
    }

    @Test
    fun `timeout returns Failure outcome`() = runTest {
        val subject = MqttPublisher(
            preferences = flowOf(basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.local")),
            passwordProvider = { null },
            publish = { _, _, _ ->
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
    fun `publishTest routes to test topic, not today or tonight`() = runTest {
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
        captured shouldHaveSize 1
        captured.single().topic shouldBe "home/forecast/test"
    }

    @Test
    fun `topic prefix is sanitised — leading and trailing slashes trimmed`() {
        MqttPublisher.topicFor("/clothescast/default/", ForecastPeriod.TODAY) shouldBe
            "clothescast/default/day/text"
        MqttPublisher.topicFor("home/forecast", ForecastPeriod.TONIGHT) shouldBe
            "home/forecast/night/text"
        // Empty / blank base falls back to the documented default so a
        // hand-edited DataStore can't produce a bare "/day/text" topic.
        MqttPublisher.topicFor("", ForecastPeriod.TODAY) shouldBe "clothescast/default/day/text"
        MqttPublisher.topicFor("   ", ForecastPeriod.TONIGHT) shouldBe "clothescast/default/night/text"
    }

    @Test
    fun `imageTopicFor builds image-suffixed topic`() {
        MqttPublisher.imageTopicFor("clothescast/default", ForecastPeriod.TODAY) shouldBe
            "clothescast/default/day/image"
        MqttPublisher.imageTopicFor("home/forecast", ForecastPeriod.TONIGHT) shouldBe
            "home/forecast/night/image"
        MqttPublisher.imageTopicFor("", ForecastPeriod.TODAY) shouldBe
            "clothescast/default/day/image"
    }

    @Test
    fun `now topic builders use the now segment regardless of period`() {
        MqttPublisher.nowTopicFor("clothescast/default") shouldBe "clothescast/default/now/text"
        MqttPublisher.nowImageTopicFor("home/forecast") shouldBe "home/forecast/now/image"
        MqttPublisher.nowAudioTopicFor("/clothescast/default/") shouldBe "clothescast/default/now/audio"
        // Empty / blank base falls back to the documented default.
        MqttPublisher.nowTopicFor("") shouldBe "clothescast/default/now/text"
        MqttPublisher.nowTopicFor("   ") shouldBe "clothescast/default/now/text"
    }

    @Test
    fun `audioTopicFor builds audio-suffixed topic`() {
        MqttPublisher.audioTopicFor("clothescast/default", ForecastPeriod.TODAY) shouldBe
            "clothescast/default/day/audio"
        MqttPublisher.audioTopicFor("home/forecast", ForecastPeriod.TONIGHT) shouldBe
            "home/forecast/night/audio"
        MqttPublisher.audioTopicFor("", ForecastPeriod.TODAY) shouldBe
            "clothescast/default/day/audio"
    }

    @Test
    fun `videoTopicFor and nowVideoTopicFor build video-suffixed topics`() {
        MqttPublisher.videoTopicFor("clothescast/default", ForecastPeriod.TODAY) shouldBe
            "clothescast/default/day/video"
        MqttPublisher.videoTopicFor("home/forecast", ForecastPeriod.TONIGHT) shouldBe
            "home/forecast/night/video"
        MqttPublisher.videoTopicFor("", ForecastPeriod.TODAY) shouldBe
            "clothescast/default/day/video"
        MqttPublisher.nowVideoTopicFor("/clothescast/default/") shouldBe
            "clothescast/default/now/video"
    }

    @Test
    fun `nowTimestampTopicFor builds timestamp-suffixed now topic`() {
        MqttPublisher.nowTimestampTopicFor("clothescast/default") shouldBe
            "clothescast/default/now/timestamp"
        MqttPublisher.nowTimestampTopicFor("home/forecast") shouldBe
            "home/forecast/now/timestamp"
        MqttPublisher.nowTimestampTopicFor("") shouldBe "clothescast/default/now/timestamp"
    }

    @Test
    fun `successful publish emits Home Assistant discovery configs`() = runTest {
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

        subject.publishIfEnabled(ForecastPeriod.TODAY, "prose")

        val discovery = captured.filter { isDiscoveryTopic(it.topic) }
        discovery.map { it.topic } shouldContainAll listOf(
            "homeassistant/sensor/clothescast_home_forecast_day/config",
            "homeassistant/sensor/clothescast_home_forecast_night/config",
            "homeassistant/sensor/clothescast_home_forecast_now/config",
            "homeassistant/sensor/clothescast_home_forecast_now_updated/config",
            "homeassistant/image/clothescast_home_forecast_day_image/config",
            "homeassistant/image/clothescast_home_forecast_night_image/config",
            "homeassistant/image/clothescast_home_forecast_now_image/config",
        )
        val today = discoveryPayload(discovery, "homeassistant/sensor/clothescast_home_forecast_day/config")
        today["unique_id"]!!.jsonPrimitive.content shouldBe "clothescast_home_forecast_day"
        today["default_entity_id"]!!.jsonPrimitive.content shouldBe "sensor.clothescast_home_forecast_day"
        today["state_topic"]!!.jsonPrimitive.content shouldBe "home/forecast/day/text"
        today["device"]!!.jsonObject["name"]!!.jsonPrimitive.content shouldBe "ClothesCast"

        val updated = discoveryPayload(discovery, "homeassistant/sensor/clothescast_home_forecast_now_updated/config")
        updated["state_topic"]!!.jsonPrimitive.content shouldBe "home/forecast/now/timestamp"
        updated["device_class"]!!.jsonPrimitive.content shouldBe "timestamp"
        updated["value_template"]!!.jsonPrimitive.content shouldBe "{{ as_datetime((value | int) / 1000) }}"

        val image = discoveryPayload(discovery, "homeassistant/image/clothescast_home_forecast_now_image/config")
        image["default_entity_id"]!!.jsonPrimitive.content shouldBe "image.clothescast_home_forecast_now_image"
        image["image_topic"]!!.jsonPrimitive.content shouldBe "home/forecast/now/image"
        image["content_type"]!!.jsonPrimitive.content shouldBe "image/png"
    }

    @Test
    fun `successful publish clears the pre-rename today and tonight discovery configs`() = runTest {
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

        subject.publishIfEnabled(ForecastPeriod.TODAY, "prose")

        // Each old discovery config is cleared with an empty retained payload so
        // HA removes the orphaned today/tonight entities after the rename.
        listOf(
            "homeassistant/sensor/clothescast_home_forecast_today/config",
            "homeassistant/sensor/clothescast_home_forecast_tonight/config",
            "homeassistant/image/clothescast_home_forecast_today_image/config",
            "homeassistant/image/clothescast_home_forecast_tonight_image/config",
        ).forEach { topic ->
            captured.first { it.topic == topic }.payload.size shouldBe 0
        }
    }

    @Test
    fun `default Home Assistant discovery ids omit the default topic suffix`() {
        val entries = MqttPublisher.homeAssistantDiscoveryEntries("clothescast/default")

        entries.map { it.configTopic } shouldContainAll listOf(
            "homeassistant/sensor/clothescast_day/config",
            "homeassistant/sensor/clothescast_night/config",
            "homeassistant/sensor/clothescast_now/config",
            "homeassistant/sensor/clothescast_now_updated/config",
            "homeassistant/image/clothescast_now_image/config",
        )
        val now = Json.parseToJsonElement(
            entries.first { it.configTopic == "homeassistant/sensor/clothescast_now/config" }.payload,
        ).jsonObject
        now["default_entity_id"]!!.jsonPrimitive.content shouldBe "sensor.clothescast_now"
    }

    @Test
    fun `Home Assistant discovery failure does not block forecast publish outcome`() = runTest {
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.local"),
            ),
            passwordProvider = { null },
            publish = { config, topic, payload ->
                captured.add(PublishCall(config, topic, payload))
                if (topic.startsWith("homeassistant/")) error("discovery ACL rejected")
            },
            retryDelayMs = 1L,
        )

        val outcome = subject.publishIfEnabled(ForecastPeriod.TODAY, "prose")

        outcome shouldBe MqttPublishOutcome.Success
        captured.any { it.topic == "clothescast/default/day/text" }.shouldBeTrue()
        captured.any { it.topic.startsWith("homeassistant/") }.shouldBeTrue()
    }

    @Test
    fun `bundle with image publishes period prose and image, mirrors both with text last and audio cleared`() = runTest {
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(
                    mqttBridgeEnabled = true,
                    mqttHost = "broker.local",
                    mqttTopic = "clothescast/default",
                ),
            ),
            passwordProvider = { null },
            publish = capturing(captured),
        )
        val fakeImage = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) // PNG magic bytes

        subject.publishIfEnabled(ForecastPeriod.TODAY, "prose", image = fakeImage)

        val topics = captured.map { it.topic }
        topics shouldContainAll listOf(
            "clothescast/default/day/text",
            "clothescast/default/day/image",
            "clothescast/default/now/image",
            "clothescast/default/now/audio",
            "clothescast/default/now/text",
        )
        // /now/text must land *after* both /now/image and /now/audio so a
        // text-triggered HA automation sees a consistent now bundle.
        val nowTextIdx = topics.indexOf("clothescast/default/now/text")
        nowTextIdx shouldBeGreaterThan topics.indexOf("clothescast/default/now/image")
        nowTextIdx shouldBeGreaterThan topics.indexOf("clothescast/default/now/audio")
        captured.first { it.topic == "clothescast/default/day/image" }.payload contentEquals fakeImage shouldBe true
        captured.first { it.topic == "clothescast/default/now/image" }.payload contentEquals fakeImage shouldBe true
        // Audio absent from the bundle → /now/audio cleared with empty payload.
        captured.first { it.topic == "clothescast/default/now/audio" }.payload.size shouldBe 0
    }

    @Test
    fun `bundle with image and audio sequences now-text last after now-image and now-audio`() = runTest {
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(
                    mqttBridgeEnabled = true,
                    mqttHost = "broker.local",
                    mqttTopic = "clothescast/default",
                ),
            ),
            passwordProvider = { null },
            publish = capturing(captured),
        )
        val fakeImage = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val fakeWav = byteArrayOf(0x52, 0x49, 0x46, 0x46) // "RIFF"

        subject.publishIfEnabled(ForecastPeriod.TONIGHT, "prose", image = fakeImage, audio = fakeWav)

        val topics = captured.map { it.topic }
        topics shouldContainAll listOf(
            "clothescast/default/night/text",
            "clothescast/default/night/image",
            "clothescast/default/night/audio",
            "clothescast/default/now/image",
            "clothescast/default/now/audio",
            "clothescast/default/now/text",
        )
        val nowTextIdx = topics.indexOf("clothescast/default/now/text")
        nowTextIdx shouldBeGreaterThan topics.indexOf("clothescast/default/now/image")
        nowTextIdx shouldBeGreaterThan topics.indexOf("clothescast/default/now/audio")
    }

    @Test
    fun `bundle with video publishes period and now video and a fresh now timestamp last`() = runTest {
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(
                    mqttBridgeEnabled = true,
                    mqttHost = "broker.local",
                    mqttTopic = "clothescast/default",
                ),
            ),
            passwordProvider = { null },
            publish = capturing(captured),
        )
        val fakeImage = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val fakeWav = byteArrayOf(0x52, 0x49, 0x46, 0x46)
        val fakeMp4 = byteArrayOf(0x00, 0x00, 0x00, 0x18) // ftyp box size prefix-ish

        subject.publishIfEnabled(
            ForecastPeriod.TODAY,
            "prose",
            image = fakeImage,
            audio = fakeWav,
            video = fakeMp4,
        )

        val topics = captured.map { it.topic }
        topics shouldContainAll listOf(
            "clothescast/default/day/video",
            "clothescast/default/now/video",
            "clothescast/default/now/timestamp",
            "clothescast/default/now/text",
        )
        // now/video lands before now/text, and now/timestamp lands *last* of
        // all — it's the commit marker a consumer triggers on, so it must
        // follow every content mirror including now/text.
        val nowTextIdx = topics.indexOf("clothescast/default/now/text")
        nowTextIdx shouldBeGreaterThan topics.indexOf("clothescast/default/now/video")
        topics.indexOf("clothescast/default/now/timestamp") shouldBeGreaterThan nowTextIdx
        // Period + now video carry the muxed MP4 bytes verbatim.
        captured.first { it.topic == "clothescast/default/day/video" }.payload contentEquals fakeMp4 shouldBe true
        captured.first { it.topic == "clothescast/default/now/video" }.payload contentEquals fakeMp4 shouldBe true
        // Timestamp is a non-empty decimal epoch-millis string.
        val ts = captured.first { it.topic == "clothescast/default/now/timestamp" }.payload.decodeToString()
        ts.toLongOrNull().shouldBeInstanceOf<Long>()
    }

    @Test
    fun `video absent clears now video with empty payload`() = runTest {
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.local"),
            ),
            passwordProvider = { null },
            publish = capturing(captured),
        )

        subject.publishIfEnabled(ForecastPeriod.TODAY, "prose")

        // With no video submitted, both the period and now video topics are
        // cleared with an empty retained payload so a stale clip can't outlive
        // its bundle on either surface.
        captured.first { it.topic == "clothescast/default/day/video" }.payload.size shouldBe 0
        captured.first { it.topic == "clothescast/default/now/video" }.payload.size shouldBe 0
    }

    @Test
    fun `now video clear failure holds back the marker so a stale video can't outlive its bundle`() = runTest {
        // Even with no video this delivery, now/video carries an empty "delete
        // retained" clear and still gates the marker: if that clear fails, a
        // stale video from a prior bundle could otherwise survive and pair with
        // the fresh timestamp. So now/text and now/timestamp are held back.
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.local"),
            ),
            passwordProvider = { null },
            publish = { config, topic, payload ->
                captured.add(PublishCall(config, topic, payload))
                if (topic == "clothescast/default/now/video") error("now/video clear rejected")
            },
            retryDelayMs = 1L,
        )

        val outcome = subject.publishIfEnabled(ForecastPeriod.TODAY, "prose")

        outcome shouldBe MqttPublishOutcome.Success
        captured.none { it.topic == "clothescast/default/now/text" }.shouldBeTrue()
        captured.none { it.topic == "clothescast/default/now/timestamp" }.shouldBeTrue()
    }

    @Test
    fun `now text and timestamp held back when a submitted video's now mirror fails`() = runTest {
        // When a video *was* produced this delivery, now/video gates the
        // commit marker — a consumer that triggers on now/timestamp must not
        // act on a bundle whose video failed to publish.
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.local"),
            ),
            passwordProvider = { null },
            publish = { config, topic, payload ->
                captured.add(PublishCall(config, topic, payload))
                if (topic == "clothescast/default/now/video") error("now/video mirror failure")
            },
            retryDelayMs = 1L,
        )

        val outcome = subject.publishIfEnabled(
            ForecastPeriod.TODAY,
            "prose",
            image = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
            audio = byteArrayOf(0x52, 0x49, 0x46, 0x46),
            video = byteArrayOf(0x00, 0x00, 0x00, 0x18),
        )

        outcome shouldBe MqttPublishOutcome.Success
        captured.none { it.topic == "clothescast/default/now/text" }.shouldBeTrue()
        captured.none { it.topic == "clothescast/default/now/timestamp" }.shouldBeTrue()
    }

    @Test
    fun `now timestamp mirror failure does not override period success outcome`() = runTest {
        // The commit marker advances last; if it fails the content mirrors have
        // already settled, so the prose primary's Success still stands (the
        // failure is logged, not surfaced as the call's outcome).
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.local"),
            ),
            passwordProvider = { null },
            publish = { config, topic, payload ->
                captured.add(PublishCall(config, topic, payload))
                if (topic.endsWith("/now/timestamp")) error("commit marker rejected")
            },
            retryDelayMs = 1L,
        )

        val outcome = subject.publishIfEnabled(ForecastPeriod.TODAY, "prose")

        outcome shouldBe MqttPublishOutcome.Success
        // now/text still landed (content advanced); the marker exhausts retries.
        captured.any { it.topic == "clothescast/default/now/text" }.shouldBeTrue()
        captured.count { it.topic == "clothescast/default/now/timestamp" } shouldBe 2
    }

    @Test
    fun `bundle skips all now mirrors when image primary fails`() = runTest {
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.local"),
            ),
            passwordProvider = { null },
            publish = { config, topic, payload ->
                captured.add(PublishCall(config, topic, payload))
                if (topic.endsWith("/day/image")) error("image broker rejection")
            },
            retryDelayMs = 1L,
        )

        val outcome = subject.publishIfEnabled(
            ForecastPeriod.TODAY,
            "prose",
            image = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
        )

        // Prose primary succeeded so the outcome is Success — what the
        // Settings UI surfaces. The /now bundle is still skipped because
        // image primary failed, so the previous retained /now bundle stays
        // intact instead of mixing today's text with last period's image.
        outcome shouldBe MqttPublishOutcome.Success
        captured.map { it.topic }.none { "/now/" in it }.shouldBeTrue()
    }

    @Test
    fun `bundle skips all now mirrors when audio primary fails`() = runTest {
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.local"),
            ),
            passwordProvider = { null },
            publish = { config, topic, payload ->
                captured.add(PublishCall(config, topic, payload))
                if (topic.endsWith("/day/audio")) error("audio broker rejection")
            },
            retryDelayMs = 1L,
        )

        val outcome = subject.publishIfEnabled(
            ForecastPeriod.TODAY,
            "prose",
            image = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
            audio = byteArrayOf(0x52, 0x49, 0x46, 0x46),
        )

        outcome shouldBe MqttPublishOutcome.Success
        captured.map { it.topic }.none { "/now/" in it }.shouldBeTrue()
    }

    @Test
    fun `bundle with bridge disabled performs no publishes`() = runTest {
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(basePrefs.copy(mqttBridgeEnabled = false, mqttHost = "broker.local")),
            passwordProvider = { null },
            publish = capturing(captured),
        )

        subject.publishIfEnabled(
            ForecastPeriod.TODAY,
            "prose",
            image = byteArrayOf(1, 2, 3),
            audio = byteArrayOf(4, 5, 6),
        )

        captured shouldHaveSize 0
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
    fun `preferences flow throwing non-cancellation error skips publish and returns NotConfigured`() = runTest {
        // The catch around preferences.first() must swallow a non-cancellation
        // throwable and return NotConfigured so a transient DataStore read
        // failure doesn't crash the worker — the publish is silently skipped
        // until the next refresh.
        var publishCalls = 0
        val subject = MqttPublisher(
            preferences = flow { throw IllegalStateException("DataStore IO failure") },
            passwordProvider = { null },
            publish = { _, _, _ -> publishCalls++ },
        )

        val outcome = subject.publishIfEnabled(ForecastPeriod.TODAY, "x")

        outcome shouldBe MqttPublishOutcome.NotConfigured
        publishCalls shouldBe 0
    }

    @Test
    fun `password provider throwing non-cancellation error falls back to anonymous connect`() = runTest {
        // The catch around passwordProvider() must swallow a non-cancellation
        // throwable and fall through with a null password — a keystore read
        // failure shouldn't block the publish entirely; some brokers accept
        // an anonymous connect, and a clean publish failure later is more
        // useful than a silently-dropped delivery.
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(
                    mqttBridgeEnabled = true,
                    mqttHost = "broker.local",
                    mqttUsername = "mqtt-user",
                ),
            ),
            passwordProvider = { throw RuntimeException("keystore unavailable") },
            publish = capturing(captured),
        )

        val outcome = subject.publishIfEnabled(ForecastPeriod.TODAY, "x")

        outcome shouldBe MqttPublishOutcome.Success
        captured.shouldNotBeEmpty()
        captured.first().config.username shouldBe "mqtt-user"
        captured.first().config.password shouldBe null
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
    fun `failed first attempt is retried and a successful second attempt returns Success`() = runTest {
        val attempts = mutableListOf<PublishCall>()
        var dayTextCalls = 0
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.local"),
            ),
            passwordProvider = { null },
            publish = { config, topic, payload ->
                attempts.add(PublishCall(config, topic, payload))
                // Fail the period prose's first attempt only; everything else
                // (the other period topics + the /now bundle) succeeds.
                if (topic == "clothescast/default/day/text" && ++dayTextCalls == 1) {
                    error("first-attempt failure")
                }
            },
            retryDelayMs = 1L,
        )

        val outcome = subject.publishIfEnabled(ForecastPeriod.TODAY, "x")

        outcome shouldBe MqttPublishOutcome.Success
        val topics = nonDiscoveryTopics(attempts)
        // Period prose retried once (two day/text attempts); the other period
        // topics published once each, then the /now bundle settled with
        // timestamp last as the commit marker.
        topics.count { it == "clothescast/default/day/text" } shouldBe 2
        topics.last() shouldBe "clothescast/default/now/timestamp"
        topics.toSet() shouldContainAll listOf(
            "clothescast/default/day/image",
            "clothescast/default/day/audio",
            "clothescast/default/day/video",
            "clothescast/default/now/image",
            "clothescast/default/now/audio",
            "clothescast/default/now/video",
            "clothescast/default/now/text",
        )
    }

    @Test
    fun `both attempts failing returns the last attempt's Failure message`() = runTest {
        val attempts = mutableListOf<String>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.local"),
            ),
            passwordProvider = { null },
            publish = { _, topic, _ ->
                attempts += topic
                error("broker down")
            },
            retryDelayMs = 1L,
        )

        val outcome = subject.publishIfEnabled(ForecastPeriod.TODAY, "x")

        val failure = outcome.shouldBeInstanceOf<MqttPublishOutcome.Failure>()
        failure.message shouldBe "IllegalStateException: broker down"
        // The returned outcome is the period prose's: it exhausts both attempts.
        attempts.count { it == "clothescast/default/day/text" } shouldBe 2
        // The /now mirror is skipped entirely when the period publish fails.
        attempts.none { it.contains("/now/") }.shouldBeTrue()
    }

    @Test
    fun `successful first attempt does not retry`() = runTest {
        val attempts = mutableListOf<String>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.local"),
            ),
            passwordProvider = { null },
            publish = { _, topic, _ -> attempts += topic },
            retryDelayMs = 1L,
        )

        subject.publishIfEnabled(ForecastPeriod.TODAY, "x") shouldBe MqttPublishOutcome.Success
        // No retries: the period bundle publishes all four topics once (prose
        // plus image/audio/video clears, since no media was submitted) then its
        // own timestamp commit marker, then the coordinated /now bundle
        // (image/audio/video clears, text, then timestamp last).
        attempts.filterNot(::isDiscoveryTopic) shouldBe listOf(
            "clothescast/default/day/text",
            "clothescast/default/day/image",
            "clothescast/default/day/audio",
            "clothescast/default/day/video",
            "clothescast/default/day/timestamp",
            "clothescast/default/now/image",
            "clothescast/default/now/audio",
            "clothescast/default/now/video",
            "clothescast/default/now/text",
            "clothescast/default/now/timestamp",
        )
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

    private data class PublishCall(val config: MqttConfig, val topic: String, val payload: ByteArray)

    private fun capturing(into: MutableList<PublishCall>): suspend (MqttConfig, String, ByteArray) -> Unit =
        { config, topic, payload -> into.add(PublishCall(config, topic, payload)) }

    private fun nonDiscoveryTopics(calls: List<PublishCall>): List<String> =
        calls.map { it.topic }.filterNot(::isDiscoveryTopic)

    private fun isDiscoveryTopic(topic: String): Boolean =
        topic.startsWith("homeassistant/")

    private fun discoveryPayload(calls: List<PublishCall>, topic: String) =
        Json.parseToJsonElement(calls.first { it.topic == topic }.payload.decodeToString()).jsonObject

    private val basePrefs = UserPreferences(
        schedule = Schedule(time = LocalTime.of(7, 0), days = Schedule.EVERY_DAY, zoneId = ZoneId.of("UTC")),
        deliveryMode = DeliveryMode.NOTIFICATION_AND_TTS,
        temperatureUnit = TemperatureUnit.CELSIUS,
        distanceUnit = DistanceUnit.KILOMETERS,
        clothesRules = emptyList(),
    )
}
