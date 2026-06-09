package app.clothescast.pairing

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.net.ServerSocket
import java.security.SecureRandom

/**
 * Minimal HTTP server that brokers the phone→TV API key transfer.
 *
 * Lifecycle:
 *   1. Call [start] — returns the (token, port) pair needed to build the pairing URL.
 *   2. The caller constructs `http://<local-ip>:<port>/pair/<token>` and shows it as a QR code.
 *   3. The user's phone browser opens the URL, fills in the Gemini key, and submits.
 *   4. [start] triggers [onKeyReceived] with the trimmed key; the server keeps running until
 *      [stop] is called (so the phone's browser receives its success page).
 *   5. Call [stop] once the key has been persisted.
 *
 * Security:
 *   - The random hex [token] in the URL path is the only shared secret; it's never logged.
 *   - Every valid POST with the token calls [onKeyReceived]; the last submission wins.
 *     Deliberate: a user who pastes a truncated key and notices the TV's voice failing
 *     can go back and resubmit the corrected key in the same session — the previous
 *     single-shot behavior answered "Done!" while silently keeping the wrong key. The
 *     token gates who can submit at all, and the callback's persist is idempotent, so
 *     accepting overwrites within the session costs nothing.
 *   - The server is only reachable from the local network (LAN). It is the caller's
 *     responsibility to call [stop] promptly after success or timeout.
 */
class PairingServer(
    private val onKeyReceived: (String) -> Unit,
) {
    val token: String = generateToken()

    private var server: EmbeddedServer<*, *>? = null

    /**
     * Starts the HTTP server on an available local port and returns that port.
     * Must be called at most once. The server runs until [stop] is called.
     */
    fun start(): Int {
        val port = findFreePort()
        val srv = embeddedServer(CIO, port = port) {
            routing {
                get("/pair/$token") {
                    call.respondText(htmlForm(), ContentType.Text.Html)
                }
                post("/pair/$token") {
                    val params = call.receiveParameters()
                    val key = params["gemini_key"]?.trim()
                    if (key.isNullOrEmpty()) {
                        call.respondText(htmlForm(error = true), ContentType.Text.Html)
                        return@post
                    }
                    // Serialize concurrent POSTs so callback invocations don't
                    // interleave; last submission wins (see the class doc).
                    synchronized(this@PairingServer) {
                        onKeyReceived(key)
                    }
                    call.respondText(htmlSuccess(), ContentType.Text.Html)
                }
            }
        }
        srv.start(wait = false)
        server = srv
        return port
    }

    /** Stops the server. Safe to call even if [start] was never called or already stopped. */
    fun stop() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 500)
        server = null
    }

    private fun htmlForm(error: Boolean = false): String = buildString {
        append(
            """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>ClothesCast — pair with TV</title>
  <style>
    body { font-family: sans-serif; max-width: 480px; margin: 40px auto; padding: 0 16px; }
    input { width: 100%; padding: 10px; font-size: 16px; box-sizing: border-box; margin-top: 4px; }
    button { margin-top: 12px; width: 100%; padding: 12px; font-size: 16px; cursor: pointer; }
    .err { color: #c00; margin-top: 8px; }
  </style>
</head>
<body>
  <h1>ClothesCast</h1>
  <p>Paste your <strong>Gemini API key</strong> below to send it to your TV.</p>""",
        )
        if (error) append("\n  <p class=\"err\">Please enter a key before submitting.</p>")
        append(
            """
  <form method="post">
    <label>Gemini API key
      <input name="gemini_key" type="text" placeholder="AIza…" autocomplete="off" autofocus>
    </label>
    <button type="submit">Send to TV</button>
  </form>
</body>
</html>""",
        )
    }

    private fun htmlSuccess(): String =
        """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>ClothesCast — paired!</title>
  <style>body { font-family: sans-serif; max-width: 480px; margin: 40px auto; padding: 0 16px; text-align: center; }</style>
</head>
<body>
  <h1>&#x2713; Done!</h1>
  <p>Your API key has been sent to the TV. You can close this tab.</p>
</body>
</html>"""

    companion object {
        private fun generateToken(): String {
            val bytes = ByteArray(6)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun findFreePort(): Int =
            ServerSocket(0).use { it.localPort }
    }
}
