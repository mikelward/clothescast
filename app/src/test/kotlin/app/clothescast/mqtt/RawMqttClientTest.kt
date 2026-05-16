package app.clothescast.mqtt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Tests the hand-rolled MQTT 3.1.1 publisher end-to-end against a fake broker
 * running on `localhost:0`. The fake broker accepts one connection, parses
 * each packet, sends the canonical responses, and stores the parsed packets
 * for inspection.
 *
 * Coverage:
 * - Happy path with username / password — verifies CONNECT flags, payload
 *   layout, PUBLISH topic + retained + QoS 1, and a clean DISCONNECT.
 * - Anonymous (no username) — verifies the username / password connect flags
 *   are cleared and the payload omits the auth fields.
 * - Broker rejects with non-zero CONNACK return code — verifies the client
 *   surfaces a meaningful exception.
 * - Variable-length integer encoding — covers the edge cases 0 / 127 / 128 /
 *   16_383 / 16_384 / 268_435_455 across the 1-to-4-byte boundaries.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RawMqttClientTest {

    private lateinit var server: ServerSocket

    @BeforeEach
    fun setUp() {
        // Same setMain dance as the other coroutine tests — the
        // androidx-lifecycle transitive on the test classpath pulls
        // kotlinx-coroutines-android, whose AndroidDispatcherFactory calls the
        // unmocked Looper.getMainLooper() at static init.
        Dispatchers.setMain(UnconfinedTestDispatcher(TestCoroutineScheduler()))
        server = ServerSocket(0)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        runCatching { server.close() }
    }

    @Test
    fun `publishes connect publish disconnect over a real socket and returns normally`() = runTest {
        val broker = FakeBroker.runOn(server)

        publishViaRawSocket(
            config = MqttConfig(
                host = "127.0.0.1",
                port = server.localPort,
                useTls = false,
                username = "alice",
                password = "s3cret",
            ),
            topic = "clothescast/insight/today",
            payload = "Today, cool and mild. Wear a sweater.",
            clientIdProvider = { "test-client-id" },
        )
        broker.await()

        val connect = broker.connect!!
        connect.protocolName shouldBe "MQTT"
        connect.protocolLevel shouldBe 4
        connect.hasUsername shouldBe true
        connect.hasPassword shouldBe true
        connect.cleanSession shouldBe true
        connect.clientId shouldBe "test-client-id"
        connect.username shouldBe "alice"
        connect.password shouldBe "s3cret"

        val publish = broker.publish!!
        publish.topic shouldBe "clothescast/insight/today"
        publish.qos shouldBe 1
        publish.retained shouldBe true
        publish.payload shouldBe "Today, cool and mild. Wear a sweater."

        broker.disconnectReceived shouldBe true
    }

    @Test
    fun `anonymous connect omits username and password fields`() = runTest {
        val broker = FakeBroker.runOn(server)

        publishViaRawSocket(
            config = MqttConfig(
                host = "127.0.0.1",
                port = server.localPort,
                useTls = false,
                username = null,
                password = null,
            ),
            topic = "t",
            payload = "x",
            clientIdProvider = { "id" },
        )
        broker.await()

        broker.connect!!.hasUsername shouldBe false
        broker.connect!!.hasPassword shouldBe false
        broker.connect!!.username shouldBe null
        broker.connect!!.password shouldBe null
    }

    @Test
    fun `broker connack with non-zero return code surfaces as IOException`() = runTest {
        FakeBroker.runOn(server, connackReturnCode = 4 /* bad username or password */)

        val ex = shouldThrow<IOException> {
            publishViaRawSocket(
                config = MqttConfig("127.0.0.1", server.localPort, useTls = false, username = "u", password = "wrong"),
                topic = "t",
                payload = "x",
                clientIdProvider = { "id" },
            )
        }
        ex.message!!.contains("bad username or password") shouldBe true
    }

    @Test
    fun `broker closing the socket before CONNACK surfaces as IOException`() = runTest {
        thread(start = true, isDaemon = true) {
            server.accept().close()
        }

        shouldThrow<IOException> {
            publishViaRawSocket(
                config = MqttConfig("127.0.0.1", server.localPort, useTls = false, username = null, password = null),
                topic = "t",
                payload = "x",
                clientIdProvider = { "id" },
            )
        }
    }

    @Test
    fun `variable-length integer round-trips across 1-to-4-byte boundaries`() {
        // The MQTT remaining-length field shifts encoding width at 128, 16_384,
        // 2_097_152. Test both sides of each boundary plus the 0 / max edges.
        val cases = listOf(0, 1, 127, 128, 129, 16_383, 16_384, 2_097_151, 2_097_152, 268_435_455)
        for (value in cases) {
            val baos = ByteArrayOutputStream()
            writeRemainingLength(DataOutputStream(baos), value)
            val decoded = readRemainingLength(DataInputStream(ByteArrayInputStream(baos.toByteArray())))
            decoded shouldBe value
        }
    }

    @Test
    fun `variable-length integer uses the expected byte widths`() {
        fun encodedSize(value: Int): Int {
            val baos = ByteArrayOutputStream()
            writeRemainingLength(DataOutputStream(baos), value)
            return baos.size()
        }
        // One byte for 0..127, two for 128..16_383, three for 16_384..2_097_151,
        // four for 2_097_152..268_435_455 — matches the MQTT 3.1.1 spec table.
        listOf(0, 127).map(::encodedSize) shouldContainExactly listOf(1, 1)
        listOf(128, 16_383).map(::encodedSize) shouldContainExactly listOf(2, 2)
        listOf(16_384, 2_097_151).map(::encodedSize) shouldContainExactly listOf(3, 3)
        listOf(2_097_152, 268_435_455).map(::encodedSize) shouldContainExactly listOf(4, 4)
    }

    @Test
    fun `TLS wrapper enables HTTPS hostname verification before the handshake`() {
        // Stand up a localhost ServerSocket purely to give wrapWithTls a real
        // connected plain socket to wrap; we never actually drive the TLS
        // handshake here (there's no TLS server on the other end). The
        // assertion is on the SSL parameters set before any I/O — if those
        // are wrong, every real-broker TLS connection would silently accept
        // a mismatched-hostname cert.
        val serverSocket = ServerSocket(0)
        try {
            val plain = Socket()
            plain.connect(java.net.InetSocketAddress("127.0.0.1", serverSocket.localPort), 2_000)
            try {
                val sslSocket = wrapWithTls(plain, "broker.example.com", 8883)
                try {
                    sslSocket.sslParameters.endpointIdentificationAlgorithm shouldBe "HTTPS"
                } finally {
                    runCatching { sslSocket.close() }
                }
            } finally {
                runCatching { plain.close() }
            }
        } finally {
            runCatching { serverSocket.close() }
        }
    }

    @Test
    fun `mqtt string writes 2-byte length prefix plus UTF-8 bytes`() {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).writeMqttString("héllo")
        val out = baos.toByteArray()
        // "héllo" in UTF-8 is 6 bytes (é = 0xC3 0xA9).
        out.size shouldBe 8
        ((out[0].toInt() shl 8) or (out[1].toInt() and 0xFF)) shouldBe 6
        out.copyOfRange(2, 8).toList() shouldContainExactly "héllo".toByteArray(Charsets.UTF_8).toList()
    }

    // ---- FakeBroker --------------------------------------------------

    /**
     * A throwaway MQTT 3.1.1 broker that runs on a worker thread, accepts one
     * connection, processes a single CONNECT + PUBLISH + DISCONNECT
     * round-trip, and records the parsed packets. Used only by these tests;
     * the production publisher doesn't care about the broker side at all.
     */
    private class FakeBroker {
        var connect: ParsedConnect? = null
        var publish: ParsedPublish? = null
        var disconnectReceived: Boolean = false
        private val done = CountDownLatch(1)

        fun await(timeoutMs: Long = 5_000) {
            check(done.await(timeoutMs, TimeUnit.MILLISECONDS)) { "Broker thread did not finish in $timeoutMs ms" }
        }

        companion object {
            fun runOn(server: ServerSocket, connackReturnCode: Int = 0): FakeBroker {
                val broker = FakeBroker()
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "fake-mqtt-broker").apply { isDaemon = true }
                }.execute {
                    try {
                        server.accept().use { socket ->
                            val inp = DataInputStream(socket.getInputStream())
                            val out = DataOutputStream(socket.getOutputStream())
                            broker.connect = parseConnect(inp)
                            sendConnack(out, returnCode = connackReturnCode)
                            if (connackReturnCode != 0) return@use
                            broker.publish = parsePublish(inp)
                            sendPuback(out, broker.publish!!.packetId)
                            val nextType = inp.read()
                            if (nextType == 0xE0) {
                                // remaining length should be 0
                                val rem = inp.read()
                                broker.disconnectReceived = (rem == 0)
                            }
                        }
                    } catch (_: SocketException) {
                        // Client closed the socket — normal at end of run.
                    } catch (_: IOException) {
                        // Tests that close the connection early land here.
                    } finally {
                        broker.done.countDown()
                    }
                }
                return broker
            }
        }
    }

    private data class ParsedConnect(
        val protocolName: String,
        val protocolLevel: Int,
        val cleanSession: Boolean,
        val hasUsername: Boolean,
        val hasPassword: Boolean,
        val clientId: String,
        val username: String?,
        val password: String?,
    )

    private data class ParsedPublish(
        val qos: Int,
        val retained: Boolean,
        val topic: String,
        val packetId: Int,
        val payload: String,
    )

    private companion object {
        fun parseConnect(inp: DataInputStream): ParsedConnect {
            val typeByte = inp.read()
            require(typeByte == 0x10) { "Expected CONNECT (0x10), got 0x${typeByte.toString(16)}" }
            val remaining = readRemainingLength(inp)
            val body = ByteArray(remaining)
            inp.readFully(body)
            val reader = ByteArrayInputStream(body).let(::DataInputStream)
            val protocolName = readMqttString(reader)
            val protocolLevel = reader.readByte().toInt() and 0xFF
            val flags = reader.readByte().toInt() and 0xFF
            reader.readShort() // keep-alive
            val clientId = readMqttString(reader)
            val hasUsername = (flags and 0x80) != 0
            val hasPassword = (flags and 0x40) != 0
            val username = if (hasUsername) readMqttString(reader) else null
            val password = if (hasPassword) readMqttString(reader) else null
            return ParsedConnect(
                protocolName = protocolName,
                protocolLevel = protocolLevel,
                cleanSession = (flags and 0x02) != 0,
                hasUsername = hasUsername,
                hasPassword = hasPassword,
                clientId = clientId,
                username = username,
                password = password,
            )
        }

        fun parsePublish(inp: DataInputStream): ParsedPublish {
            val typeByte = inp.read()
            require((typeByte and 0xF0) == 0x30) { "Expected PUBLISH (0x30…), got 0x${typeByte.toString(16)}" }
            val qos = (typeByte shr 1) and 0x03
            val retained = (typeByte and 1) != 0
            val remaining = readRemainingLength(inp)
            val body = ByteArray(remaining)
            inp.readFully(body)
            val reader = ByteArrayInputStream(body).let(::DataInputStream)
            val topic = readMqttString(reader)
            val packetId = if (qos > 0) reader.readShort().toInt() and 0xFFFF else 0
            val payloadBytes = ByteArray(reader.available())
            reader.readFully(payloadBytes)
            return ParsedPublish(
                qos = qos,
                retained = retained,
                topic = topic,
                packetId = packetId,
                payload = String(payloadBytes, Charsets.UTF_8),
            )
        }

        fun readMqttString(reader: DataInputStream): String {
            val length = reader.readShort().toInt() and 0xFFFF
            val bytes = ByteArray(length)
            reader.readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        fun sendConnack(out: DataOutputStream, returnCode: Int) {
            out.write(byteArrayOf(0x20, 0x02, 0x00, returnCode.toByte()))
            out.flush()
        }

        fun sendPuback(out: DataOutputStream, packetId: Int) {
            out.writeByte(0x40)
            out.writeByte(0x02)
            out.writeShort(packetId)
            out.flush()
        }
    }
}
