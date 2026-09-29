package io.github.ncwardell.drysocks.core

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class NetworkTest {
    @Test
    fun `rules accept domains, addresses and ranges`() {
        assertThat(BlockRules.normalize(" Ads.Example.com ")).isEqualTo("ads.example.com")
        assertThat(BlockRules.normalize("*.tracker.test.")).isEqualTo("tracker.test")
        assertThat(BlockRules.normalize("203.0.113.7")).isEqualTo("203.0.113.7")
        assertThat(BlockRules.normalize("198.51.100.0/24")).isEqualTo("198.51.100.0/24")
        assertThat(BlockRules.normalize("2001:DB8::/32")).isEqualTo("2001:db8::/32")
        assertThat(BlockRules.normalize("::1")).isEqualTo("::1")
        assertThat(BlockRules.kind("2001:db8::/32")).isEqualTo(BlockRules.Kind.RANGE)
        assertThat(BlockRules.kind("203.0.113.7")).isEqualTo(BlockRules.Kind.ADDRESS)
        assertThat(BlockRules.kind("example.com")).isEqualTo(BlockRules.Kind.DOMAIN)
    }

    @Test
    fun `rules reject what the engine would misread`() {
        for (bad in listOf("", "localhost", "300.1.1.1", "10.0.0.0/33", "2001:db8::/129", "exa mple.com",
            "http://example.com", "1.2.3.4/x", "2001:::1", "-bad.example")) {
            assertThat(BlockRules.normalize(bad)).isNull()
        }
    }

    @Test
    fun `snapshots parse and name connections`() {
        val s = NetSnapshot.parse(
            """{"active":[{"id":3,"proto":"tcp","src":"198.18.0.1:5000","dst":"[2001:db8::1]:443","started":1,"up":5,"down":7}],
               "recent":[{"id":2,"dst":"192.0.2.1:80","host":"example.com","started":1,"ended":2,"blocked":true,"error":"blocked: example.com"}],
               "dns":[{"time":1,"name":"example.com","type":"A","blocked":true}],"up":5,"down":7,"udpDropped":1,"blocked":2}""",
        )
        assertThat(s.active.single().ip).isEqualTo("2001:db8::1")
        assertThat(s.active.single().label).isEqualTo("[2001:db8::1]:443")
        assertThat(s.recent.single().label).isEqualTo("example.com")
        assertThat(s.recent.single().active).isFalse()
        assertThat(NetSnapshot.parse("{}").active).isEmpty()
    }
}
