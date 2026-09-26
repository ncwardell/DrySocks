package com.sockschain.core

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ProfileTest {
    @Test
    fun `hop urls escape credentials and bracket IPv6`() {
        assertThat(Hop(HopKind.SOCKS5, "h", 1080, "a b", "p@ss:w/d").toUrl())
            .isEqualTo("socks5://a%20b:p%40ss%3Aw%2Fd@h:1080")
        assertThat(Hop(HopKind.HTTP, "::1", 8080).toUrl()).isEqualTo("http://[::1]:8080")
        assertThat(Hop(HopKind.SOCKS4A, "h", 1, user = "id").toUrl()).isEqualTo("socks4a://id@h:1")
    }

    @Test
    fun `engine config carries hops in order`() {
        val p = Profile(
            id = "x",
            hops = listOf(Hop(HopKind.SOCKS5, "a", 1), Hop(HopKind.HTTP, "b", 2)),
        )
        assertThat(p.engineConfig(1500, tunDns = "198.18.0.2"))
            .isEqualTo("""{"hops":["socks5://a:1","http://b:2"],"dns":"https://1.1.1.1/dns-query","mtu":1500,"tunDns":"198.18.0.2"}""")
    }

    @Test
    fun `problems name the hop`() {
        val p = Profile(id = "x", hops = listOf(Hop(host = "a"), Hop(host = "")))
        assertThat(p.problem()).isEqualTo("Hop 2: Host is empty")
        assertThat(Profile(id = "x", hops = listOf(Hop(host = "a")), dns = "1.1.1.1").problem())
            .contains("DNS")
        assertThat(Profile(id = "x", hops = listOf(Hop(host = "a")), dns = "[2606:4700::1111]:53").problem())
            .isNull()
        assertThat(Profile(id = "x", hops = listOf(Hop(host = "a")), dns = "https://dns.example/q").problem()).isNull()
        assertThat(Profile(id = "x", hops = listOf(Hop(host = "a")), dns = "http://dns.example/q").problem()).contains("DNS")
    }

    @Test
    fun `tor first leaves the rest to the engine`() {
        val p = Profile(id = "x", hops = listOf(Hop.tor(), Hop(host = "exit.example", port = 1080)))
        assertThat(p.problem()).isNull()
        assertThat(p.hopsBeforeTor).isEmpty()
        assertThat(p.engineHops(9150)).containsExactly("socks5://127.0.0.1:9150", "socks5://exit.example:1080").inOrder()
    }

    @Test
    fun `hops before tor become tor's upstream`() {
        val first = Hop(HopKind.HTTP, "bridge.example", 3128)
        val p = Profile(id = "x", hops = listOf(first, Hop.tor(), Hop(host = "exit.example")))
        assertThat(p.problem()).isNull()
        assertThat(p.hopsBeforeTor).containsExactly(first)
        assertThat(p.engineHops(9150)).containsExactly("socks5://127.0.0.1:9150", "socks5://exit.example:1080").inOrder()
    }

    @Test
    fun `tor once, and not without a port`() {
        assertThat(Profile(id = "x", hops = listOf(Hop.tor(), Hop.tor())).problem()).contains("once")
        val p = Profile(id = "x", hops = listOf(Hop.tor()))
        assertThat(runCatching { p.engineHops(null) }.isFailure).isTrue()
    }

    @Test
    fun `rotating hops go to the engine by their own id`() {
        val r = Hop.rotating("pool-1")
        val p = Profile(id = "x", hops = listOf(r, Hop.tor()))
        assertThat(p.problem()).isNull()
        assertThat(p.hopsBeforeTor.single().toUrl()).isEqualTo("rotate://${r.id}")
        assertThat(p.poolIds).containsExactly("pool-1")
        assertThat(Profile(id = "y", hops = listOf(Hop.rotating())).problem()).contains("pool")
        assertThat(Profile(id = "y", hops = listOf(r.copy(intervalSec = 0))).problem()).contains("1 second")
        // The interval only matters to AUTO.
        assertThat(Profile(id = "y", hops = listOf(r.copy(rotation = RotationMode.SCATTERED, intervalSec = 0))).problem()).isNull()
    }

    @Test
    fun `pasted proxy lists`() {
        val text = """
            # my list
            1.2.3.4:1080
            socks4://5.6.7.8:4145  US
            http://user:pw@proxy.example:3128
            socks5h://[2001:db8::1]:1080
            not a proxy
            1.2.3.4:1080
            9.9.9.9:99999
        """.trimIndent()
        assertThat(parseProxyList(text)).containsExactly(
            "socks5://1.2.3.4:1080",
            "socks4a://5.6.7.8:4145",
            "http://user:pw@proxy.example:3128",
            "socks5://[2001:db8::1]:1080",
        ).inOrder()
    }

    @Test
    fun `scraped urls become hops`() {
        assertThat(Hop.fromUrl("socks4a://1.2.3.4:4145")).isEqualTo(Hop(HopKind.SOCKS4A, "1.2.3.4", 4145))
        assertThat(Hop.fromUrl("http://1.2.3.4:80")?.kind).isEqualTo(HopKind.HTTP)
        assertThat(Hop.fromUrl("https://1.2.3.4:443")).isNull()
    }

    @Test
    fun `probe results parse, including failures`() {
        val r = ProbeResult.parse("""{"steps":[{"to":"a:1","ms":12}],"error":"hop 2: refused"}""")
        assertThat(r.steps).containsExactly(ProbeStep("a:1", 12))
        assertThat(r.exitIp).isNull()
        assertThat(r.error).isEqualTo("hop 2: refused")
    }
}
