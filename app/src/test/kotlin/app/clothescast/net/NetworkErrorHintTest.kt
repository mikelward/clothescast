package app.clothescast.net

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLHandshakeException

class NetworkErrorHintTest {

    @Test
    fun `unknown host is classified`() {
        classifyNetworkError(UnknownHostException("broker.local")) shouldBe NetworkErrorKind.UNKNOWN_HOST
    }

    @Test
    fun `no route to host is classified`() {
        classifyNetworkError(NoRouteToHostException("No route to host")) shouldBe NetworkErrorKind.NO_ROUTE
    }

    @Test
    fun `socket timeout is classified`() {
        classifyNetworkError(SocketTimeoutException("timed out")) shouldBe NetworkErrorKind.TIMEOUT
    }

    @Test
    fun `generic timeout is classified`() {
        classifyNetworkError(TimeoutException("deadline")) shouldBe NetworkErrorKind.TIMEOUT
    }

    @Test
    fun `tls handshake failure is classified`() {
        classifyNetworkError(SSLHandshakeException("cert untrusted")) shouldBe NetworkErrorKind.TLS
    }

    @Test
    fun `connection refused is split from no-route by message`() {
        classifyNetworkError(ConnectException("Connection refused")) shouldBe NetworkErrorKind.CONNECTION_REFUSED
        classifyNetworkError(ConnectException("Network is unreachable")) shouldBe NetworkErrorKind.NO_ROUTE
    }

    @Test
    fun `cause chain is walked, not just the top throwable`() {
        val wrapped = RuntimeException("publish failed", NoRouteToHostException("No route to host"))
        classifyNetworkError(wrapped) shouldBe NetworkErrorKind.NO_ROUTE
    }

    @Test
    fun `netty-style wrapper name is recognised even without a java-net supertype`() {
        // Simulate a wrapper that carries the name but doesn't subclass the
        // java.net type — the class-name fallback should still catch it.
        class AnnotatedNoRouteToHostException(message: String) : Exception(message)
        classifyNetworkError(AnnotatedNoRouteToHostException("No route to host")) shouldBe
            NetworkErrorKind.NO_ROUTE
    }

    @Test
    fun `non-network failure returns null so the caller surfaces the raw error`() {
        classifyNetworkError(IllegalStateException("broker rejected: not authorized")).shouldBeNull()
    }

    @Test
    fun `a very deep non-network chain terminates at the depth bound`() {
        // Build a chain longer than the walk's depth cap; it must return
        // promptly (null — no network cause) rather than walk forever.
        var chain: Throwable = IllegalStateException("root")
        repeat(50) { chain = RuntimeException("layer", chain) }
        classifyNetworkError(chain).shouldBeNull()
    }

    @Test
    fun `a network cause deeper than the depth bound is not required to be found`() {
        // Documents the trade-off: the depth cap means a network cause buried
        // under 12+ wrapper layers won't be classified (we fall back to the raw
        // message). Real socket stacks nest only a few layers, so this is fine.
        var chain: Throwable = NoRouteToHostException("No route to host")
        repeat(20) { chain = RuntimeException("layer", chain) }
        classifyNetworkError(chain).shouldBeNull()
    }
}
