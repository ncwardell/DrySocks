package com.sockschain.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

@Serializable
enum class HopKind(val scheme: String, val label: String) {
    SOCKS5("socks5", "SOCKS5"),
    SOCKS4A("socks4a", "SOCKS4a"),
    HTTP("http", "HTTP CONNECT"),

    /**
     * Tor, run inside this app. It has no address of its own: the engine is pointed at
     * Tor's SOCKS port on this phone, and any hops before Tor become Tor's own upstream
     * (Socks5Proxy), so Tor can sit anywhere in the chain. See [Profile.engineHops].
     */
    TOR("socks5", "Tor"),

    /**
     * A pool of proxies used in turn: each new connection goes through the current one,
     * and the pool moves on every [Hop.intervalSec]. Filled from a list, from the
     * scraper in the background, or both. See engine/chain/pool.go.
     */
    ROTATING("rotate", "Rotating"),
}

/** How a rotating hop moves through its pool. */
@Serializable
enum class RotationMode(val engine: String) {
    /** Stays on one proxy; moves to the next in the pool's order only when it fails. */
    SEQUENTIAL("sequential"),

    /** A random proxy every [Hop.intervalSec] seconds. */
    AUTO("timed"),

    /** Every new connection gets a random proxy. */
    SCATTERED("scattered"),
}

/** How a scrape reaches the lists and the proxies it tests. */
object ScrapeVia {
    const val DIRECT = "direct"
    const val TOR = "tor"
    fun chain(profileId: String) = "chain:$profileId"
    fun chainId(via: String): String? = via.removePrefix("chain:").takeIf { via.startsWith("chain:") }
}

/** One proxy in a chain. Traffic reaches it through every hop listed before it. */
@Serializable
data class Hop(
    val kind: HopKind = HopKind.SOCKS5,
    val host: String = "",
    val port: Int = 1080,
    val user: String = "",
    val pass: String = "",
    /** Rotating only: names the engine's copy of the pool, so it must stay put when hops move. */
    val id: String = "",
    /** Rotating only: the [ProxyPool] it draws from. Several hops may share one. */
    val poolId: String = "",
    val rotation: RotationMode = RotationMode.AUTO,
    /** [RotationMode.AUTO] only. */
    val intervalSec: Int = 60,
    /** Before rotation modes; ignored. */
    val shuffle: Boolean = true,
    /** Before pools: the hop's own list and refill. Read once by [migrateRotatingHops]. */
    val pool: List<String> = emptyList(),
    val refill: Boolean = false,
    val refillVia: String = ScrapeVia.DIRECT,
) {
    /** The URL the engine parses; see engine/chain/hop.go. */
    fun toUrl(): String {
        check(kind != HopKind.TOR) { "Tor's address is only known at run time; use Profile.engineHops" }
        if (kind == HopKind.ROTATING) return "rotate://$id"
        val auth = when {
            user.isEmpty() -> ""
            pass.isEmpty() -> "${enc(user)}@"
            else -> "${enc(user)}:${enc(pass)}@"
        }
        val h = if (':' in host) "[$host]" else host
        return "${kind.scheme}://$auth$h:$port"
    }

    /** Null when usable, otherwise what is wrong, phrased for the editor. */
    fun problem(): String? = when {
        kind == HopKind.TOR -> null
        kind == HopKind.ROTATING -> when {
            id.isBlank() -> "Rotating hop has no id; remove and add it again"
            poolId.isBlank() -> "Choose a pool for the rotating hop"
            rotation == RotationMode.AUTO && intervalSec !in 1..86_400 -> "Rotate every 1 second to 24 hours"
            else -> null
        }
        host.isBlank() -> "Host is empty"
        host.any { it.isWhitespace() || it in "/@[]" } -> "Host has characters a host name cannot"
        port !in 1..65535 -> "Port must be 1–65535"
        kind == HopKind.SOCKS4A && pass.isNotEmpty() -> "SOCKS4a has no password, only a user id"
        user.length > 255 || pass.length > 255 -> "Credentials longer than 255 characters"
        else -> null
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    /** True when the host is this phone, which only the first hop can reach. */
    val isLoopback: Boolean
        get() = kind != HopKind.TOR && kind != HopKind.ROTATING &&
            (host == "localhost" || host == "::1" || host.startsWith("127."))

    companion object {
        fun tor() = Hop(HopKind.TOR, host = "", port = 0)

        fun rotating(poolId: String = "") = Hop(
            HopKind.ROTATING, host = "", port = 0,
            id = java.util.UUID.randomUUID().toString().take(12), poolId = poolId,
        )

        /** Reads what the scraper returns: scheme://host:port, no credentials. */
        fun fromUrl(url: String): Hop? {
            val m = Regex("""^(socks5|socks4a|http)://([^:/@\s]+):(\d{1,5})$""").matchEntire(url) ?: return null
            val kind = HopKind.entries.first { it != HopKind.TOR && it.scheme == m.groupValues[1] }
            return Hop(kind, m.groupValues[2], m.groupValues[3].toInt())
        }
    }
}

@Serializable
enum class AppMode {
    /** Every app goes through the chain except those listed. */
    ALL_EXCEPT,

    /** Only the listed apps go through the chain. */
    ONLY,
}

@Serializable
data class Profile(
    val id: String,
    val name: String = "New chain",
    val hops: List<Hop> = listOf(Hop()),
    /**
     * Resolver the exit hop reaches: an https:// URL for DNS over HTTPS, or host:port
     * for DNS over TCP. HTTPS is the default because it needs only port 443, which
     * nearly every proxy allows, and the hops cannot read the lookups.
     */
    val dns: String = DEFAULT_DNS,
    val appMode: AppMode = AppMode.ALL_EXCEPT,
    val apps: Set<String> = emptySet(),
    /** Leave private ranges (home network, printers, the router) off the VPN. */
    val bypassLan: Boolean = true,
) {
    fun problem(): String? {
        if (hops.isEmpty()) return "A chain needs at least one hop"
        hops.forEachIndexed { i, h -> h.problem()?.let { return "Hop ${i + 1}: $it" } }
        if (hops.count { it.kind == HopKind.TOR } > 1) return "Tor can appear once; it builds its own three-relay circuit"
        // A later hop would dial the previous proxy's own localhost, not this phone's.
        hops.drop(1).forEachIndexed { i, h ->
            if (h.isLoopback) return "Hop ${i + 2}: only the first hop can be on this phone"
        }
        if (!dns.matches(Regex("""^(\[[0-9a-fA-F:]+]|[^:\s/]+):\d{1,5}$""")) &&
            !dns.matches(Regex("""^https://[^\s/]+(/\S*)?$"""))
        ) {
            return "DNS must be an https:// URL or host:port, e.g. $DEFAULT_DNS"
        }
        if (appMode == AppMode.ONLY && apps.isEmpty()) return "Pick at least one app, or route all apps"
        return null
    }

    companion object {
        const val DEFAULT_DNS = "https://1.1.1.1/dns-query"

        /** Before DNS over HTTPS existed this was the default; chains still on it move up. */
        const val OLD_DEFAULT_DNS = "1.1.1.1:53"
    }

    val usesTor: Boolean get() = hops.any { it.kind == HopKind.TOR }

    val rotatingHops: List<Hop> get() = hops.filter { it.kind == HopKind.ROTATING }

    val poolIds: Set<String> get() = rotatingHops.map { it.poolId }.filter { it.isNotEmpty() }.toSet()

    /** Hops Tor itself connects through: its upstream, served by the engine. */
    val hopsBeforeTor: List<Hop>
        get() = hops.indexOfFirst { it.kind == HopKind.TOR }.let { if (it < 0) emptyList() else hops.take(it) }

    /**
     * The hop URLs the engine dials. With Tor, the chain starts at Tor's SOCKS port on
     * this phone: the hops before Tor are behind Tor (see [hopsBeforeTor]), and only
     * the hops after it remain for the engine.
     */
    fun engineHops(torSocksPort: Int?): List<String> {
        val t = hops.indexOfFirst { it.kind == HopKind.TOR }
        if (t < 0) return hops.map { it.toUrl() }
        requireNotNull(torSocksPort) { "chain has Tor but Tor is not running" }
        return listOf("socks5://127.0.0.1:$torSocksPort") + hops.drop(t + 1).map { it.toUrl() }
    }

    /** The JSON the engine's Start and Probe take; see engine/vpn/engine.go Config. */
    fun engineConfig(mtu: Int, torSocksPort: Int? = null, tunDns: String = ""): String =
        json.encodeToString(EngineConfig.serializer(), EngineConfig(engineHops(torSocksPort), dns, mtu, tunDns))
}

@Serializable
internal data class EngineConfig(val hops: List<String>, val dns: String, val mtu: Int, val tunDns: String)

@Serializable
data class ProbeResult(
    val steps: List<ProbeStep> = emptyList(),
    val exitIp: String? = null,
    val error: String? = null,
) {
    companion object {
        fun parse(s: String): ProbeResult = json.decodeFromString(serializer(), s)
    }
}

@Serializable
data class ProbeStep(val to: String, val ms: Long)

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
