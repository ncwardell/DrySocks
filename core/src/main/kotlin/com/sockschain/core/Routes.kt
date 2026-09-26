package com.sockschain.core

/** An IPv4 prefix. */
data class Cidr(val base: Long, val prefix: Int) {
    init {
        require(prefix in 0..32) { "prefix $prefix" }
        require(base and mask(prefix).inv() and 0xffffffffL == 0L) { "host bits set in ${this}" }
    }

    val address: String
        get() = (24 downTo 0 step 8).joinToString(".") { ((base shr it) and 0xff).toString() }

    val size: Long get() = 1L shl (32 - prefix)

    operator fun contains(other: Cidr): Boolean =
        other.prefix >= prefix && (other.base and mask(prefix)) == base

    override fun toString() = "$address/$prefix"

    companion object {
        fun parse(s: String): Cidr {
            val (ip, len) = s.split('/').let { it[0] to it[1].toInt() }
            val octets = ip.split('.').map { it.toLong() }
            require(octets.size == 4 && octets.all { it in 0..255 }) { "not IPv4: $s" }
            return Cidr(octets.fold(0L) { acc, o -> (acc shl 8) or o }, len)
        }

        private fun mask(prefix: Int): Long = if (prefix == 0) 0L else (0xffffffffL shl (32 - prefix)) and 0xffffffffL
    }
}

object Routes {
    /**
     * Private ranges left off the VPN when a profile bypasses the LAN. 100.64/10
     * (carrier NAT, and Tailscale) is deliberately not here: on some carriers the
     * whole internet looks like it.
     */
    val LAN = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16").map(Cidr::parse)

    /**
     * The smallest set of prefixes covering all of IPv4 except [excluded]. Android
     * 13 can exclude routes itself; before it, the VPN has to be given this instead.
     */
    fun complement(excluded: List<Cidr>): List<Cidr> {
        val out = mutableListOf<Cidr>()
        fun split(c: Cidr) {
            when {
                excluded.any { c in it } -> Unit
                excluded.none { it in c } -> out += c
                else -> {
                    val child = c.prefix + 1
                    split(Cidr(c.base, child))
                    split(Cidr(c.base + (c.size / 2), child))
                }
            }
        }
        split(Cidr(0, 0))
        return out
    }
}
