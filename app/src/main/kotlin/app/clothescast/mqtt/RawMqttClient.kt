package app.clothescast.mqtt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Minimal MQTT 3.1.1 publisher built directly on [Socket] / [SSLSocketFactory].
 *
 * Opens a TCP (or TLS) connection to [MqttConfig.host]:[MqttConfig.port],
 * performs an MQTT CONNECT + PUBLISH (retained, QoS 1) + DISCONNECT, then
 * closes — designed for clothescast's twice-daily fire-and-forget publish.
 * No reconnect, no keep-alive, no subscribe, no persistent session, no QoS 2;
 * we don't need any of those for a "tell HA the next forecast sentence"
 * round-trip, and the absence of all that machinery is why the whole file is
 * ~200 lines instead of the ~5MB Netty/JCTools/RxJava graph the previous
 * HiveMQ-based publisher pulled in.
 *
 * The MQTT 3.1.1 wire format is straightforward — every packet is a 1-byte
 * type + 1-to-4-byte variable-length remaining-length + a body of length-
 * prefixed UTF-8 strings and raw payload bytes. The spec lives at
 * [https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html] if
 * you want to read it alongside the code; the byte layout for each packet is
 * comment-annotated below.
 *
 * Cancellation: the function suspends inside `withContext(Dispatchers.IO)`,
 * and on coroutine cancellation an `invokeOnCompletion` hook closes the
 * socket — any blocked read/write then surfaces as a `SocketException` and
 * unwinds cleanly. Socket-level [Socket.connect] and [Socket.setSoTimeout]
 * cap the TCP-connect and per-read phases independently of the outer
 * `withTimeoutOrNull` in [MqttPublisher].
 */
internal suspend fun publishViaRawSocket(
    config: MqttConfig,
    topic: String,
    payload: String,
    clientIdProvider: () -> String = { "clothescast-${UUID.randomUUID()}" },
) = withContext(Dispatchers.IO) {
    val plain = Socket()
    plain.soTimeout = READ_TIMEOUT_MS
    // Register the cancel hook before connect() — that way an outer cancel
    // before the TCP connect even completes still closes the underlying
    // socket and lets connect() throw SocketException to unwind.
    val cancelHook = currentCoroutineContext()[Job]?.invokeOnCompletion {
        runCatching { plain.close() }
    }
    try {
        plain.connect(InetSocketAddress(config.host, config.port), CONNECT_TIMEOUT_MS)
        val socket: Socket = if (config.useTls) {
            wrapWithTls(plain, config.host, config.port)
        } else {
            plain
        }
        val out = DataOutputStream(socket.getOutputStream())
        val inp = DataInputStream(socket.getInputStream())

        sendConnect(out, clientIdProvider(), config.username, config.password)
        readConnack(inp)

        // We only ever have one in-flight QoS 1 publish per connection (we
        // connect → publish → disconnect), so a fixed packet ID is fine.
        val packetId = 1
        sendPublish(out, topic, payload.toByteArray(Charsets.UTF_8), packetId)
        readPuback(inp, expectedPacketId = packetId)

        sendDisconnect(out)
    } finally {
        cancelHook?.dispose()
        runCatching { plain.close() }
    }
}

/**
 * Wraps a TCP-connected [plain] socket with a TLS layer for [host]:[port] and
 * enables RFC-6125 hostname verification on the resulting [SSLSocket].
 *
 * By default a raw [SSLSocket] returned by [SSLSocketFactory.createSocket]
 * validates the certificate chain but does **not** verify the certificate
 * subject matches the host you connected to — so a network attacker with any
 * CA-trusted certificate (for some other name they legitimately own) could
 * intercept the broker connection and the MQTT credentials we send on it.
 * `HttpsURLConnection` does this verification for you automatically; raw
 * sockets do not. Setting
 * `SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")` opts the
 * handshake into the standard HTTPS-style hostname check (RFC 6125) so a
 * mismatch fails before any application data is exchanged.
 *
 * Internal (not private) so [RawMqttClientTest] can assert the setting is
 * applied.
 */
internal fun wrapWithTls(plain: Socket, host: String, port: Int): SSLSocket {
    val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
    // autoClose=true means closing the SSL layer tears down the plain socket
    // too (and vice versa via the cancel hook in publishViaRawSocket).
    val sslSocket = sslFactory.createSocket(plain, host, port, /* autoClose = */ true) as SSLSocket
    val params = sslSocket.sslParameters
    params.endpointIdentificationAlgorithm = "HTTPS"
    sslSocket.sslParameters = params
    sslSocket.soTimeout = READ_TIMEOUT_MS
    return sslSocket
}

// ---- packet types (high nibble of the first byte) -----------------------

private const val CONNECT = 0x10
private const val CONNACK = 0x20
private const val PUBLISH = 0x30
private const val PUBACK = 0x40
private const val DISCONNECT = 0xE0

private const val PROTOCOL_NAME = "MQTT"
private const val PROTOCOL_LEVEL = 4 // MQTT 3.1.1

private const val CONNECT_TIMEOUT_MS = 4_000
private const val READ_TIMEOUT_MS = 4_000

// ---- CONNECT ------------------------------------------------------------
//
// Variable header (10 bytes minimum):
//   bytes  1- 6  : length-prefixed protocol name ("MQTT")
//   byte   7     : protocol level (4 for 3.1.1)
//   byte   8     : connect flags — clean-session + optional username/password bits
//   bytes  9-10  : keep-alive seconds (big-endian)
// Payload:
//   length-prefixed client identifier
//   optional length-prefixed username (if username flag set)
//   optional length-prefixed password (if password flag set)

private fun sendConnect(
    out: DataOutputStream,
    clientId: String,
    username: String?,
    password: String?,
) {
    val variableHeader = buildPacket {
        writeMqttString(PROTOCOL_NAME)
        writeByte(PROTOCOL_LEVEL)
        var flags = 0x02 // clean session
        if (!username.isNullOrEmpty()) flags = flags or 0x80
        if (!password.isNullOrEmpty()) flags = flags or 0x40
        writeByte(flags)
        writeShort(60) // keep-alive seconds; the connection lives <1s anyway
    }
    val body = buildPacket {
        writeMqttString(clientId)
        if (!username.isNullOrEmpty()) writeMqttString(username)
        if (!password.isNullOrEmpty()) writeMqttString(password)
    }
    writeFixedHeader(out, CONNECT, variableHeader + body)
}

private fun readConnack(inp: DataInputStream) {
    val type = inp.read().also { if (it == -1) throw IOException("Broker closed before CONNACK") }
    if (type != CONNACK) throw IOException("Expected CONNACK (0x20), got 0x${type.toString(16)}")
    val remaining = readRemainingLength(inp)
    require(remaining == 2) { "Unexpected CONNACK length: $remaining" }
    inp.readByte() // session-present flag (ignored on clean-session)
    val returnCode = inp.readByte().toInt()
    if (returnCode != 0) throw IOException("CONNACK refused: ${connackReason(returnCode)}")
}

// ---- PUBLISH ------------------------------------------------------------
//
// Fixed-header type-byte low nibble carries flags:
//   bit 3        : DUP (retransmit) — always 0 here
//   bits 2-1     : QoS (we use 1 → 0b01)
//   bit 0        : RETAIN — always 1 here
// Variable header:
//   length-prefixed topic name
//   2-byte packet identifier (only present for QoS > 0)
// Payload: raw bytes.

private fun sendPublish(
    out: DataOutputStream,
    topic: String,
    payload: ByteArray,
    packetId: Int,
) {
    val typeByte = PUBLISH or (1 shl 1) or 1 // QoS 1, retained
    val body = buildPacket {
        writeMqttString(topic)
        writeShort(packetId)
        write(payload)
    }
    writeFixedHeader(out, typeByte, body)
}

private fun readPuback(inp: DataInputStream, expectedPacketId: Int) {
    val type = inp.read().also { if (it == -1) throw IOException("Broker closed before PUBACK") }
    if ((type and 0xF0) != PUBACK) throw IOException("Expected PUBACK (0x40), got 0x${type.toString(16)}")
    val remaining = readRemainingLength(inp)
    require(remaining == 2) { "Unexpected PUBACK length: $remaining" }
    val packetId = inp.readShort().toInt() and 0xFFFF
    require(packetId == expectedPacketId) { "PUBACK packetId $packetId != expected $expectedPacketId" }
}

// ---- DISCONNECT ---------------------------------------------------------
//
// A 2-byte packet: type + zero-length remaining-length. No body.

private fun sendDisconnect(out: DataOutputStream) {
    out.write(byteArrayOf(DISCONNECT.toByte(), 0))
    out.flush()
}

// ---- shared helpers -----------------------------------------------------

private fun writeFixedHeader(out: DataOutputStream, typeByte: Int, body: ByteArray) {
    out.writeByte(typeByte)
    writeRemainingLength(out, body.size)
    out.write(body)
    out.flush()
}

/**
 * Encodes an MQTT "variable byte integer": 7 bits per byte, MSB=continuation.
 * Range 0..268_435_455, encoded as 1 to 4 bytes. The remaining-length field
 * is the only place in the wire format that uses this encoding.
 */
internal fun writeRemainingLength(out: DataOutputStream, length: Int) {
    require(length in 0..268_435_455) { "Remaining length out of range: $length" }
    var value = length
    do {
        var byte = value and 0x7F
        value = value ushr 7
        if (value > 0) byte = byte or 0x80
        out.writeByte(byte)
    } while (value > 0)
}

internal fun readRemainingLength(inp: DataInputStream): Int {
    var multiplier = 1
    var value = 0
    for (i in 0 until 4) {
        val byte = inp.read().also { if (it == -1) throw IOException("Stream closed reading remaining length") }
        value += (byte and 0x7F) * multiplier
        if ((byte and 0x80) == 0) return value
        multiplier *= 128
    }
    // Reached the 5th continuation byte — the encoding tops out at 4 bytes
    // per the MQTT 3.1.1 spec, so the stream is malformed.
    throw IOException("Malformed remaining length: more than 4 continuation bytes")
}

/**
 * UTF-8 length-prefixed string: 2-byte length + UTF-8 bytes. The only string
 * framing MQTT uses — for protocol name, topic, client id, username,
 * password.
 */
internal fun DataOutputStream.writeMqttString(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    require(bytes.size <= 65_535) { "MQTT string too long: ${bytes.size}" }
    writeShort(bytes.size)
    write(bytes)
}

private fun buildPacket(block: DataOutputStream.() -> Unit): ByteArray {
    val baos = ByteArrayOutputStream()
    DataOutputStream(baos).use(block)
    return baos.toByteArray()
}

private fun connackReason(code: Int): String = when (code) {
    1 -> "unacceptable protocol version"
    2 -> "identifier rejected"
    3 -> "server unavailable"
    4 -> "bad username or password"
    5 -> "not authorised"
    else -> "unknown ($code)"
}
