package io.github.ncwardell.drysocks.core

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RoutesTest {
    @Test
    fun `complement of the LAN covers everything else exactly once`() {
        val routes = Routes.complement(Routes.LAN)

        val lanSize = Routes.LAN.sumOf { it.size }
        assertThat(routes.sumOf { it.size }).isEqualTo((1L shl 32) - lanSize)

        for (r in routes) for (lan in Routes.LAN) {
            assertThat(r in lan || lan in r).isFalse()
        }
        // No two routes overlap either.
        for (i in routes.indices) for (j in i + 1 until routes.size) {
            assertThat(routes[i] in routes[j] || routes[j] in routes[i]).isFalse()
        }
    }

    @Test
    fun `complement of nothing is the default route`() {
        assertThat(Routes.complement(emptyList())).containsExactly(Cidr.parse("0.0.0.0/0"))
    }

    @Test
    fun `the tun and its dns stay routed`() {
        // ChainVpnService puts both in 198.18.0.0/30; bypassing the LAN must not
        // strand them, or DNS goes nowhere.
        val routes = Routes.complement(Routes.LAN)
        assertThat(routes.any { Cidr.parse("198.18.0.0/30") in it }).isTrue()
    }

    @Test
    fun `parse rejects host bits`() {
        runCatching { Cidr.parse("10.0.0.1/8") }.let { assertThat(it.isFailure).isTrue() }
    }
}
