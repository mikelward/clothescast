package app.clothescast.cast

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.InetAddress

class LanAddressTest {

    @Test
    fun `picks the first IPv4 site-local address`() {
        val pick = LanAddress.pickSiteLocal(
            listOf(
                InetAddress.getByName("fe80::1"),       // IPv6 link-local
                InetAddress.getByName("192.168.1.42"),  // wins
                InetAddress.getByName("10.0.0.7"),
            ),
        )
        pick shouldBe "192.168.1.42"
    }

    @Test
    fun `returns null when only loopback and link-local addresses are available`() {
        LanAddress.pickSiteLocal(
            listOf(
                InetAddress.getByName("127.0.0.1"),
                InetAddress.getByName("169.254.10.20"),
            ),
        ) shouldBe null
    }

    @Test
    fun `returns null for an empty list`() {
        LanAddress.pickSiteLocal(emptyList()) shouldBe null
    }

    @Test
    fun `accepts 10 and 172_16 ranges as site-local`() {
        LanAddress.pickSiteLocal(listOf(InetAddress.getByName("10.1.2.3"))) shouldBe "10.1.2.3"
        LanAddress.pickSiteLocal(listOf(InetAddress.getByName("172.20.5.6"))) shouldBe "172.20.5.6"
    }
}
