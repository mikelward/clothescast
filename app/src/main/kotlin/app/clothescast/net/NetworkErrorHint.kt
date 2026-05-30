package app.clothescast.net

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

/**
 * The connection-layer cause behind a failed network call, distilled to the
 * cases that change what we tell the user. Used to turn an opaque socket / TLS
 * exception ("AnnotatedNoRouteToHostException: No route to host") into a hint
 * that helps them tell a *network / firewall* problem (the device is on the
 * wrong Wi-Fi, a firewall is dropping the packets) from a *service* problem
 * (the broker is up but rejected us / the port is closed).
 */
enum class NetworkErrorKind {
    /** Name didn't resolve — typically a typo in the host or no DNS on this network. */
    UNKNOWN_HOST,

    /** Host didn't route — different subnet/VLAN, VPN, or a firewall dropping the packets. */
    NO_ROUTE,

    /** Host reachable but the port actively refused — the service is down or the port is wrong. */
    CONNECTION_REFUSED,

    /** Connect/read timed out — a black-holed host or a firewall silently dropping the SYN. */
    TIMEOUT,

    /** TLS handshake failed — wrong TLS toggle, an untrusted/expired cert, or a TLS-stripping proxy. */
    TLS,
}

/**
 * Walk [error]'s cause chain and classify it as a connection-layer failure, or
 * return null when it isn't one (a broker ACL rejection, a malformed payload, a
 * bug) so the caller can fall back to the raw exception text.
 *
 * Matches on exception *type* rather than message text: the messages vary by
 * Android version and locale, but the types don't. Netty's wrappers
 * (`AnnotatedNoRouteToHostException`, `AnnotatedConnectException`) subclass the
 * matching `java.net` type, so the `is` checks catch them; a class-name fallback
 * covers any wrapper that carries the name without subclassing. The cause walk
 * is depth-bounded and self-cycle-guarded so a pathological chain can't loop.
 */
fun classifyNetworkError(error: Throwable): NetworkErrorKind? {
    var cause: Throwable? = error
    var depth = 0
    while (cause != null && depth < 12) {
        when (cause) {
            // NoRouteToHostException / ConnectException both extend
            // SocketException, so order the most specific first.
            is UnknownHostException -> return NetworkErrorKind.UNKNOWN_HOST
            is NoRouteToHostException -> return NetworkErrorKind.NO_ROUTE
            is PortUnreachableException -> return NetworkErrorKind.NO_ROUTE
            is SSLException -> return NetworkErrorKind.TLS
            is SocketTimeoutException -> return NetworkErrorKind.TIMEOUT
            is TimeoutException -> return NetworkErrorKind.TIMEOUT
            is ConnectException -> {
                // ConnectException covers both "Connection refused" (service
                // down / wrong port) and "Network is unreachable" (no route);
                // the kind hinges on the message here, the one spot type alone
                // can't separate them.
                val message = cause.message?.lowercase().orEmpty()
                return when {
                    "refused" in message -> NetworkErrorKind.CONNECTION_REFUSED
                    "timed out" in message -> NetworkErrorKind.TIMEOUT
                    else -> NetworkErrorKind.NO_ROUTE
                }
            }
        }
        // Defensive net for wrappers that carry the name as a suffix but don't
        // subclass the java.net type. Netty's `AnnotatedNoRouteToHostException`
        // et al. *do* subclass (so the `is` checks above already caught them);
        // this covers any future wrapper that only prefixes the name. Matched by
        // suffix so an "Annotated…"/"Wrapped…" prefix still resolves.
        val simpleName = cause.javaClass.simpleName
        when {
            simpleName.endsWith("UnknownHostException") -> return NetworkErrorKind.UNKNOWN_HOST
            simpleName.endsWith("NoRouteToHostException") -> return NetworkErrorKind.NO_ROUTE
            simpleName.endsWith("PortUnreachableException") -> return NetworkErrorKind.NO_ROUTE
        }
        val next = cause.cause
        // Guard against a throwable whose cause is itself (or already seen at
        // this hop) — depth alone bounds longer cycles.
        cause = if (next === cause) null else next
        depth++
    }
    return null
}
