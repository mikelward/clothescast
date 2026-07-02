package app.clothescast.pairing

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import kotlin.concurrent.thread

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

    // Revoked synchronously by [stop], before the engine wind-down is handed
    // to its background thread. The routes authorize on `token && active`
    // rather than the token alone, so a submission landing in the wind-down
    // window — or after a failed engine shutdown left the socket open —
    // can't invoke [onKeyReceived] once pairing has been closed.
    @Volatile
    private var active = true

    /**
     * Starts the HTTP server on an available local port and returns that port.
     * Must be called at most once. The server runs until [stop] is called.
     *
     * Suspends until the engine is bound: the port comes from binding port 0
     * and reading the kernel's assignment back (no probe-then-rebind race),
     * and the bind itself runs off the caller's thread — the pairing
     * ViewModel calls this from the main dispatcher.
     */
    suspend fun start(): Int {
        val srv = embeddedServer(CIO, port = 0) {
            routing {
                get("/pair/$token") {
                    if (!active) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }
                    call.respondText(htmlForm(), ContentType.Text.Html)
                }
                post("/pair/$token") {
                    if (!active) {
                        call.respond(HttpStatusCode.NotFound)
                        return@post
                    }
                    val params = call.receiveParameters()
                    val key = params["gemini_key"]?.trim()
                    if (key.isNullOrEmpty()) {
                        call.respondText(htmlForm(error = true), ContentType.Text.Html)
                        return@post
                    }
                    // Serialize concurrent POSTs so callback invocations don't
                    // interleave; last submission wins (see the class doc).
                    // Re-check [active] under the lock: a stop() landing after
                    // the gate above (while receiveParameters was suspended)
                    // must not have its revocation overtaken by an in-flight
                    // submission — and when the revocation wins, answer 404
                    // like the gate does, not a success page claiming a key
                    // that was deliberately never stored was "sent".
                    val accepted = synchronized(this@PairingServer) {
                        if (active) {
                            onKeyReceived(key)
                            true
                        } else {
                            false
                        }
                    }
                    if (accepted) {
                        call.respondText(htmlSuccess(), ContentType.Text.Html)
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }
            }
        }
        // Register the engine before the first suspension point, and shut it
        // down on any failure *or cancellation* below — the pairing screen
        // can be dismissed while the caller is still suspended in here, and
        // an engine that started but was never handed back would keep the
        // LAN pairing endpoint (and its token) alive with no remaining owner.
        server = srv
        try {
            withContext(Dispatchers.IO) { srv.start(wait = false) }
            return srv.engine.resolvedConnectors().first().port
        } catch (t: Throwable) {
            stop()
            throw t
        }
    }

    /** Stops the server. Safe to call even if [start] was never called or already stopped. */
    fun stop() {
        // Revoke the token gate first, so no submission arriving after this
        // point can reach [onKeyReceived] — regardless of how long the
        // engine's asynchronous wind-down below takes (or whether it fails).
        active = false
        val srv = server
        server = null
        // Engine shutdown blocks the calling thread for up to its timeout,
        // and stop() arrives on the main thread (the pairing ViewModel's
        // onCleared / timeout path) — a 500 ms stall there is visible jank
        // as the user leaves the screen. Hand the engine to a background
        // thread; the [active] gate above already closed the routes.
        if (srv != null) {
            thread(name = "PairingServer.stop", isDaemon = true) {
                // An uncaught throw on a bare thread kills the process on
                // Android; a failed engine shutdown isn't worth that — log
                // and let the daemon thread die.
                runCatching { srv.stop(gracePeriodMillis = 0, timeoutMillis = 500) }
                    .onFailure { Log.w(TAG, "Pairing server engine shutdown failed", it) }
            }
        }
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
        private const val TAG = "PairingServer"

        private fun generateToken(): String {
            val bytes = ByteArray(6)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
