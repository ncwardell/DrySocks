package io.github.ncwardell.drysocks

import android.content.Context
import android.telephony.TelephonyManager
import io.github.ncwardell.drysocks.core.HopKind
import io.github.ncwardell.drysocks.core.NetSnapshot
import io.github.ncwardell.drysocks.core.Profile
import io.github.ncwardell.drysocks.core.ProxyPool
import java.util.Locale

/** One stop on the way out: what it is, what it's called, and where it is. */
data class MapNode(
    val role: Role,
    val label: String,
    val detail: String,
    val country: Country?,
) {
    enum class Role { PHONE, PROXY, ROTATING, TOR_GUARD, TOR_MIDDLE, TOR_EXIT, TOR }
}

/** Where connections end up, one entry per country. */
data class MapDestination(val country: Country, val connections: Int, val bytes: Long, val hosts: List<String>)

/** The route as a traceroute would show it, if one could see through proxies and Tor. */
data class RouteMap(
    val route: List<MapNode> = emptyList(),
    val destinations: List<MapDestination> = emptyList(),
    val exit: Country? = null,
    val live: Boolean = false,
)

/**
 * Builds [RouteMap]s. Everything is located offline, by [Geo]; host names are never
 * resolved here, so a proxy given by name is left unplaced rather than leaked.
 */
class RouteMapper(context: Context) {
    private val app = context.applicationContext

    fun map(profile: Profile?, pools: List<ProxyPool>, snapshot: NetSnapshot, running: Boolean, exitIp: String?): RouteMap {
        val route = buildList {
            add(MapNode(MapNode.Role.PHONE, "This phone", "roughly, from its network or language settings", phoneCountry()))
            profile?.hops?.forEach { h ->
                when (h.kind) {
                    HopKind.TOR -> {
                        val relays = if (running) TorController.circuit() else emptyList()
                        if (relays.size == 3) {
                            val roles = listOf(MapNode.Role.TOR_GUARD, MapNode.Role.TOR_MIDDLE, MapNode.Role.TOR_EXIT)
                            val names = listOf("Tor guard", "Tor middle", "Tor exit")
                            relays.forEachIndexed { i, r ->
                                add(MapNode(roles[i], names[i], "${r.nickname} · ${r.address}", Geo.locate(app, r.address)))
                            }
                        } else {
                            add(MapNode(MapNode.Role.TOR, "Tor", if (running) "building a circuit" else "3 relays, chosen on connect", null))
                        }
                    }
                    HopKind.ROTATING -> {
                        val pool = pools.firstOrNull { it.id == h.poolId }
                        val current = if (running) Pools.state(h.id).current else null
                        val host = current?.substringAfter("://")?.substringBeforeLast(':')
                        add(MapNode(MapNode.Role.ROTATING, pool?.name ?: "Rotating", current?.substringAfter("://") ?: "rotates on connect", host?.let { Geo.locate(app, it) }))
                    }
                    else -> add(MapNode(MapNode.Role.PROXY, h.host, "${h.kind.scheme} · ${h.port}", Geo.locate(app, h.host)))
                }
            }
        }

        val destinations = (snapshot.active + snapshot.recent)
            .filter { !it.blocked && it.error == null }
            .mapNotNull { c -> Geo.locate(app, c.ip)?.let { it to c } }
            .groupBy({ it.first }, { it.second })
            .map { (country, conns) ->
                MapDestination(
                    country, conns.size, conns.sumOf { it.up + it.down },
                    conns.map { it.label }.distinct().take(4),
                )
            }
            .sortedByDescending { it.bytes }

        return RouteMap(route, destinations, exitIp?.let { Geo.locate(app, it) }, running)
    }

    /**
     * Roughly where the phone is, without asking anyone: the mobile network's country,
     * else the SIM's, else the language setting's. Good enough for a dot on a globe.
     */
    private fun phoneCountry(): Country? {
        val tm = app.getSystemService(TelephonyManager::class.java)
        val cc = listOf(tm?.networkCountryIso, tm?.simCountryIso, Locale.getDefault().country)
            .firstOrNull { !it.isNullOrBlank() }?.uppercase() ?: return null
        return Geo.country(app, cc)
    }

}
