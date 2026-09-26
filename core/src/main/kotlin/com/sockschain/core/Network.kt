package com.sockschain.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** Mirrors engine/monitor.Conn. */
@Serializable
data class NetConn(
    val id: Long,
    val proto: String = "tcp",
    val src: String = "",
    val dst: String = "",
    val host: String? = null,
    val started: Long = 0,
    val ended: Long? = null,
    val up: Long = 0,
    val down: Long = 0,
    val blocked: Boolean = false,
    val error: String? = null,
) {
    val active: Boolean get() = ended == null
    val ip: String get() = dst.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
    /** What a person reads: the name if DNS gave one, else the address. */
    val label: String get() = host ?: dst
}

/** Mirrors engine/monitor.Lookup. */
@Serializable
data class NetLookup(
    val time: Long = 0,
    val name: String = "",
    val type: String = "",
    val answers: List<String> = emptyList(),
    val blocked: Boolean = false,
    val error: String? = null,
)

/** Mirrors engine/monitor.Snapshot. */
@Serializable
data class NetSnapshot(
    val active: List<NetConn> = emptyList(),
    val recent: List<NetConn> = emptyList(),
    val dns: List<NetLookup> = emptyList(),
    val up: Long = 0,
    val down: Long = 0,
    val udpDropped: Long = 0,
    val blocked: Long = 0,
) {
    companion object {
        fun parse(s: String): NetSnapshot = json.decodeFromString(serializer(), s)
    }
}

/**
 * A blocklist entry: a domain (blocking every name under it too), an IP address, or a
 * CIDR range. The engine decides the same way; see engine/monitor/names.go.
 */
object BlockRules {
    private val domain = Regex("""^(\*\.)?([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)*[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.?$""")
    private val ipv4 = Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")

    enum class Kind { DOMAIN, ADDRESS, RANGE }

    /** The normal form of [raw], or null when it is none of the three. */
    fun normalize(raw: String): String? {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return null
        val (addr, bits) = s.split('/').let { it[0] to it.getOrNull(1) }
        if (s.count { it == '/' } > 1) return null
        val v4 = ipv4.matches(addr)
        val v6 = !v4 && ':' in addr && isIpv6(addr)
        if (v4 || v6) {
            if (bits == null) return addr
            val n = bits.toIntOrNull() ?: return null
            return if (n in 0..(if (v4) 32 else 128)) "$addr/$n" else null
        }
        if (bits != null || !domain.matches(s) || '.' !in s.trimEnd('.')) return null
        // A top-level domain has a letter; without this, "300.1.1.1" passes as a name.
        if (s.trimEnd('.').substringAfterLast('.').none { it.isLetter() }) return null
        return s.removePrefix("*.").trimEnd('.')
    }

    fun kind(rule: String): Kind = when {
        '/' in rule -> Kind.RANGE
        ipv4.matches(rule) || ':' in rule -> Kind.ADDRESS
        else -> Kind.DOMAIN
    }

    private fun isIpv6(s: String): Boolean {
        if (s.count { it == ':' } < 2 || s.contains(":::")) return false
        val groups = s.split("::")
        if (groups.size > 2) return false
        val parts = groups.flatMap { g -> if (g.isEmpty()) emptyList() else g.split(':') }
        if (parts.any { it.isEmpty() || it.length > 4 || !it.all { c -> c.isDigit() || c in 'a'..'f' } }) return false
        return if (groups.size == 2) parts.size < 8 else parts.size == 8
    }

    fun encode(rules: List<String>): String = json.encodeToString(ListSerializer(String.serializer()), rules)
    fun decode(s: String): List<String> = json.decodeFromString(ListSerializer(String.serializer()), s)
}
