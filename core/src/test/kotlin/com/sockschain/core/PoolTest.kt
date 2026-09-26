package com.sockschain.core

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PoolTest {
    private fun ok(url: String, ms: Long, cc: String = "NL") = ScrapeResult(url, ok = true, ms = ms, exitIp = "203.0.113.1", country = cc)
    private fun fail(url: String) = ScrapeResult(url, ok = false, error = "i/o timeout")

    @Test
    fun `checks record passes and strike out failures`() {
        var p = ProxyPool("p", update = PoolUpdate(pruneAfter = 2)).add(listOf("socks5://a:1", "socks5://b:1"), now = 1)
        assertThat(p.proxies.all { it.unchecked }).isTrue()

        p = p.merge(listOf(ok("socks5://a:1", 300), fail("socks5://b:1")), now = 10)
        assertThat(p.working).isEqualTo(1)
        assertThat(p.proxies.first { it.url == "socks5://b:1" }.fails).isEqualTo(1)

        p = p.merge(listOf(fail("socks5://b:1")), now = 20)
        assertThat(p.proxies.map { it.url }).containsExactly("socks5://a:1")
    }

    @Test
    fun `a pass clears strikes, and new finds join only if they passed`() {
        var p = ProxyPool("p").add(listOf("socks5://a:1"), now = 1)
        p = p.merge(listOf(fail("socks5://a:1")), now = 2)
        p = p.merge(listOf(ok("socks5://a:1", 100), ok("http://new:8080", 50), fail("socks5://never:1")), now = 3)
        assertThat(p.proxies.map { it.url }).containsExactly("socks5://a:1", "http://new:8080")
        assertThat(p.proxies.all { it.fails == 0 && it.working }).isTrue()
    }

    @Test
    fun `the engine gets the fastest working first, then the unchecked`() {
        val p = ProxyPool("p").add(listOf("socks5://slow:1", "socks5://fast:1", "socks5://new:1"), now = 1)
            .merge(listOf(ok("socks5://slow:1", 900), ok("socks5://fast:1", 100)), now = 2)
        assertThat(p.engineUrls()).containsExactly("socks5://fast:1", "socks5://slow:1", "socks5://new:1").inOrder()
    }

    @Test
    fun `auto pools are due on schedule or when low`() {
        val auto = ProxyPool("p", update = PoolUpdate(mode = UpdateMode.AUTO, everyMinutes = 30, target = 4), lastUpdated = 0)
            .add(listOf("socks5://a:1", "socks5://b:1"), now = 0)
            .merge(listOf(ok("socks5://a:1", 1), ok("socks5://b:1", 1)), now = 1)
        assertThat(auto.due(now = 29 * 60_000L)).isFalse()
        assertThat(auto.due(now = 30 * 60_000L)).isTrue()
        assertThat(auto.copy(proxies = auto.proxies.take(1)).due(now = 60_000L)).isTrue() // 1 of 4: low
        assertThat(auto.copy(update = auto.update.copy(mode = UpdateMode.MANUAL)).due(now = Long.MAX_VALUE)).isFalse()
        assertThat(auto.copy(paused = true).due(now = Long.MAX_VALUE)).isFalse()
    }

    @Test
    fun `old rotating hops become pools`() {
        val old = Hop(HopKind.ROTATING, id = "h1", pool = listOf("socks5://a:1"), refill = true, refillVia = ScrapeVia.TOR)
        val (profiles, pools) = migrateRotatingHops(listOf(Profile(id = "x", name = "Mine", hops = listOf(old))), emptyList(), now = 5)
        val hop = profiles.single().hops.single()
        val pool = pools.single()
        assertThat(hop.poolId).isEqualTo(pool.id)
        assertThat(hop.pool).isEmpty()
        assertThat(pool.name).isEqualTo("Mine pool")
        assertThat(pool.update.mode).isEqualTo(UpdateMode.AUTO)
        assertThat(pool.update.via).isEqualTo(ScrapeVia.TOR)
        assertThat(pool.proxies.map { it.url }).containsExactly("socks5://a:1")
        // Idempotent: a migrated hop is left alone.
        assertThat(migrateRotatingHops(profiles, pools, now = 6).second).hasSize(1)
    }
}
