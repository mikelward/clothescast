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

        captured shouldHaveSize 2
        val periodCall = captured[0]
        periodCall.topic shouldBe "clothescast/default/today/text"
        periodCall.payload.decodeToString() shouldBe "Today, cool and mild. Wear a sweater."
        periodCall.config.host shouldBe "192.168.1.10"
        periodCall.config.port shouldBe 1883
        periodCall.config.useTls shouldBe false
        val nowCall = captured[1]
        nowCall.topic shouldBe "clothescast/default/now/text"
        nowCall.payload.decodeToString() shouldBe "Today, cool and mild. Wear a sweater."
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

        captured shouldHaveSize 2
        captured[0].topic shouldBe "home/forecast/tonight/text"
        captured[1].topic shouldBe "home/forecast/now/text"
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

        outcome shouldBe MqttPublishOutcome.Success()
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

        outcome shouldBe MqttPublishOutcome.Success()
        captured shouldHaveSize 1
        captured.single().topic shouldBe "home/forecast/test"
    }

    @Test
    fun `topic prefix is sanitised — leading and trailing slashes trimmed`() {
        MqttPublisher.topicFor("/clothescast/default/", ForecastPeriod.TODAY) shouldBe
            "clothescast/default/today/text"
        MqttPublisher.topicFor("home/forecast", ForecastPeriod.TONIGHT) shouldBe
            "home/forecast/tonight/text"
        // Empty / blank base falls back to the documented default so a
        // hand-edited DataStore can't produce a bare "/today/text" topic.
        MqttPublisher.topicFor("", ForecastPeriod.TODAY) shouldBe "clothescast/default/today/text"
        MqttPublisher.topicFor("   ", ForecastPeriod.TONIGHT) shouldBe "clothescast/default/tonight/text"
    }

    @Test
    fun `imageTopicFor appends image suffix to prose topic`() {
        MqttPublisher.imageTopicFor("clothescast/default", ForecastPeriod.TODAY) shouldBe
            "clothescast/default/today/image"
        MqttPublisher.imageTopicFor("home/forecast", ForecastPeriod.TONIGHT) shouldBe
            "home/forecast/tonight/image"
        MqttPublisher.imageTopicFor("", ForecastPeriod.TODAY) shouldBe
            "clothescast/default/today/image"
    }

    @Test
    fun `audioTopicFor appends audio suffix to prose topic`() {
        MqttPublisher.audioTopicFor("clothescast/default", ForecastPeriod.TODAY) shouldBe
            "clothescast/default/today/audio"
        MqttPublisher.audioTopicFor("home/forecast", ForecastPeriod.TONIGHT) shouldBe
            "home/forecast/tonight/audio"
        MqttPublisher.audioTopicFor("", ForecastPeriod.TODAY) shouldBe
            "clothescast/default/today/audio"
    }

    @Test
    fun `now topic helpers compose under the same base as period topics`() {
        MqttPublisher.nowTopicFor("clothescast/default") shouldBe "clothescast/default/now/text"
        MqttPublisher.nowImageTopicFor("clothescast/default") shouldBe "clothescast/default/now/image"
        MqttPublisher.nowAudioTopicFor("clothescast/default") shouldBe "clothescast/default/now/audio"
        MqttPublisher.nowTopicFor("/home/forecast/") shouldBe "home/forecast/now/text"
        MqttPublisher.nowTopicFor("") shouldBe "clothescast/default/now/text"
    }

    @Test
    fun `now mirror is skipped when the period publish fails`() = runTest {
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(
                    mqttBridgeEnabled = true,
                    mqttHost = "broker.local",
                ),
            ),
            passwordProvider = { null },
            publish = { config, topic, payload ->
                captured += PublishCall(config, topic, payload)
                throw java.io.IOException("simulated broker failure")
            },
            maxAttempts = 1,
        )

        subject.publishIfEnabled(ForecastPeriod.TODAY, "ignored")

        // Only the period topic should have been attempted — the mirror to
        // /now is suppressed when the canonical publish doesn't succeed.
        captured shouldHaveSize 1
        captured.single().topic shouldBe "clothescast/default/today/text"
    }

    @Test
    fun `publishImageIfEnabled publishes PNG bytes to image topic`() = runTest {
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

        subject.publishImageIfEnabled(ForecastPeriod.TODAY, fakeImage)

        captured shouldHaveSize 2
        captured[0].topic shouldBe "clothescast/default/today/image"
        (captured[0].payload contentEquals fakeImage).shouldBeTrue()
        captured[1].topic shouldBe "clothescast/default/now/image"
        (captured[1].payload contentEquals fakeImage).shouldBeTrue()
    }

    @Test
    fun `publishIfEnabled with mirrorNow false skips the now mirror so the trigger can fire last`() = runTest {
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

        val outcome = subject.publishIfEnabled(ForecastPeriod.TODAY, "deferred", mirrorNow = false)

        outcome shouldBe MqttPublishOutcome.Success(nowMirrored = false)
        captured shouldHaveSize 1
        captured.single().topic shouldBe "clothescast/default/today/text"
    }

    @Test
    fun `mirrorProseToNow publishes only to the now text topic`() = runTest {
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

        val outcome = subject.mirrorProseToNow(ForecastPeriod.TONIGHT, "trigger")

        outcome shouldBe MqttPublishOutcome.Success()
        captured shouldHaveSize 1
        val call = captured.single()
        call.topic shouldBe "clothescast/default/now/text"
        call.payload.decodeToString() shouldBe "trigger"
    }

    @Test
    fun `publishIfEnabled returns Success with nowMirrored false when only the now mirror fails`() = runTest {
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
            // Models a broker ACL that grants `<prefix>/today/#` but denies
            // `<prefix>/now/#` — the period publish lands, the mirror to
            // /now/text gets rejected.
            publish = { config, topic, payload ->
                captured += PublishCall(config, topic, payload)
                if (topic.contains("/now/")) throw java.io.IOException("ACL deny on /now")
            },
            maxAttempts = 1,
        )

        val outcome = subject.publishIfEnabled(ForecastPeriod.TODAY, "atomicity-test")

        outcome shouldBe MqttPublishOutcome.Success(nowMirrored = false)
        captured shouldHaveSize 2
        captured[0].topic shouldBe "clothescast/default/today/text"
        captured[1].topic shouldBe "clothescast/default/now/text"
    }

    @Test
    fun `publishImageIfEnabled with mirrorNow false skips the now mirror and returns nowMirrored false`() = runTest {
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

        val outcome = subject.publishImageIfEnabled(
            ForecastPeriod.TODAY,
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
            mirrorNow = false,
        )

        outcome shouldBe MqttPublishOutcome.Success(nowMirrored = false)
        captured shouldHaveSize 1
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
    fun `publishAudioIfEnabled publishes WAV bytes to audio topic`() = runTest {
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
        val fakeWav = byteArrayOf(0x52, 0x49, 0x46, 0x46) // "RIFF" header magic

        subject.publishAudioIfEnabled(ForecastPeriod.TONIGHT, fakeWav)

        captured shouldHaveSize 2
        captured[0].topic shouldBe "clothescast/default/tonight/audio"
        (captured[0].payload contentEquals fakeWav).shouldBeTrue()
        captured[1].topic shouldBe "clothescast/default/now/audio"
        (captured[1].payload contentEquals fakeWav).shouldBeTrue()
    }

    @Test
    fun `publishAudioIfEnabled no-op when bridge disabled`() = runTest {
        val captured = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(basePrefs.copy(mqttBridgeEnabled = false, mqttHost = "broker.local")),
            passwordProvider = { null },
            publish = capturing(captured),
        )

        subject.publishAudioIfEnabled(ForecastPeriod.TODAY, byteArrayOf(1, 2, 3))

        captured shouldHaveSize 0
    }

    @Test
    fun `empty audio bytes clear both the period and now audio topics`() = runTest {
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

        // Empty payload + retain=true (always set by publishWithHiveMq) is
        // MQTT's "drop retained message" semantics — used by the worker on
        // no-audio runs so HA doesn't keep advertising the previous
        // briefing's clip.
        subject.publishAudioIfEnabled(ForecastPeriod.TODAY, ByteArray(0))

        captured shouldHaveSize 2
        captured[0].topic shouldBe "clothescast/default/today/audio"
        captured[0].payload.size shouldBe 0
        captured[1].topic shouldBe "clothescast/default/now/audio"
        captured[1].payload.size shouldBe 0
    }

    @Test
    fun `mirrorNow false on image publish keeps the now alias stale (atomic with failed prose)`() = runTest {
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

        // mirrorNow=false models "this run's prose publish failed so we
        // must keep /now/* atomic with the stale /now/text". The period
        // topic still updates (independently subscribable), but the
        // /now/image mirror is suppressed.
        subject.publishImageIfEnabled(ForecastPeriod.TODAY, fakeImage, mirrorNow = false)

        captured shouldHaveSize 1
        captured.single().topic shouldBe "clothescast/default/today/image"
    }

    @Test
    fun `mirrorNow false on audio publish keeps the now alias stale (atomic with failed prose)`() = runTest {
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
        val fakeWav = byteArrayOf(0x52, 0x49, 0x46, 0x46)

        subject.publishAudioIfEnabled(ForecastPeriod.TONIGHT, fakeWav, mirrorNow = false)

        captured shouldHaveSize 1
        captured.single().topic shouldBe "clothescast/default/tonight/audio"
    }

    @Test
    fun `empty image bytes clear both the period and now image topics`() = runTest {
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

        subject.publishImageIfEnabled(ForecastPeriod.TONIGHT, ByteArray(0))

        captured shouldHaveSize 2
        captured[0].topic shouldBe "clothescast/default/tonight/image"
        captured[0].payload.size shouldBe 0
        captured[1].topic shouldBe "clothescast/default/now/image"
        captured[1].payload.size shouldBe 0
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
    fun `failed first attempt is retried and a successful second attempt returns Success`() = runTest {
        val attempts = mutableListOf<PublishCall>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.local"),
            ),
            passwordProvider = { null },
            publish = { config, topic, payload ->
                attempts.add(PublishCall(config, topic, payload))
                if (attempts.size == 1) error("first-attempt failure")
            },
            retryDelayMs = 1L,
        )

        val outcome = subject.publishIfEnabled(ForecastPeriod.TODAY, "x")

        outcome shouldBe MqttPublishOutcome.Success()
        // Two period attempts (first fails, retry succeeds) plus the /now
        // mirror that follows the canonical publish.
        attempts.map { it.topic } shouldBe listOf(
            "clothescast/default/today/text",
            "clothescast/default/today/text",
            "clothescast/default/now/text",
        )
    }

    @Test
    fun `both attempts failing returns the last attempt's Failure message`() = runTest {
        var attempts = 0
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.local"),
            ),
            passwordProvider = { null },
            publish = { _, _, _ ->
                attempts += 1
                error("attempt $attempts failure")
            },
            retryDelayMs = 1L,
        )

        val outcome = subject.publishIfEnabled(ForecastPeriod.TODAY, "x")

        val failure = outcome.shouldBeInstanceOf<MqttPublishOutcome.Failure>()
        attempts shouldBe 2
        failure.message shouldBe "IllegalStateException: attempt 2 failure"
    }

    @Test
    fun `successful first attempt does not retry`() = runTest {
        val topics = mutableListOf<String>()
        val subject = MqttPublisher(
            preferences = flowOf(
                basePrefs.copy(mqttBridgeEnabled = true, mqttHost = "broker.local"),
            ),
            passwordProvider = { null },
            publish = { _, topic, _ -> topics += topic },
            retryDelayMs = 1L,
        )

        subject.publishIfEnabled(ForecastPeriod.TODAY, "x") shouldBe MqttPublishOutcome.Success()
        // The period topic publishes once (no retry on success); the /now
        // mirror follows it but is not part of the retry budget.
        topics.count { it == "clothescast/default/today/text" } shouldBe 1
        topics shouldHaveSize 2
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

    private val basePrefs = UserPreferences(
        schedule = Schedule(time = LocalTime.of(7, 0), days = Schedule.EVERY_DAY, zoneId = ZoneId.of("UTC")),
        deliveryMode = DeliveryMode.NOTIFICATION_AND_TTS,
        temperatureUnit = TemperatureUnit.CELSIUS,
        distanceUnit = DistanceUnit.KILOMETERS,
        clothesRules = emptyList(),
    )
}
