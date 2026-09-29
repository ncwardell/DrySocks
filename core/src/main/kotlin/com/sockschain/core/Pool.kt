package com.sockschain.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
enum class UpdateMode {
    /** Only when asked. */
    MANUAL,

    /** On its schedule, and when too few of its proxies work. */
    AUTO,
}

/** How a pool keeps itself fresh. */
@Serializable
data class PoolUpdate(
    val mode: UpdateMode = UpdateMode.MANUAL,
    /** AUTO only: also update while not connected, on Android's schedule. */
    val background: Boolean = false,
    val everyMinutes: Int = 30,
    /** Scrape more when fewer than this many work. */
    val target: Int = 20,
    /** The route checks and scrapes travel: [ScrapeVia]. Test from where the proxies will be used. */
    val via: String = ScrapeVia.DIRECT,
    /** socks5, socks4a, http; empty for all. */
    val protocols: Set<String> = emptySet(),
    /** Two-letter exit countries; empty for any. */
    val countries: Set<String> = emptySet(),
    /** Failed checks in a row before a proxy is dropped. */
    val pruneAfter: Int = 3,
) {
    fun problem(): String? = when {
        target !in 1..500 -> "Keep between 1 and 500 working"
        mode == UpdateMode.AUTO && everyMinutes !in 15..1440 -> "Update every 15 minutes to 24 hours"
        pruneAfter !in 1..20 -> "Drop after 1 to 20 failed checks"
        countries.any { !it.matches(Regex("[A-Z]{2}")) } -> "Countries are two-letter codes, like US or DE"
        else -> null
    }
}

/** One proxy in a pool, and what the pool knows of it. */
@Serializable
data class PoolProxy(
    val url: String,
    val added: Long = 0,
    val lastChecked: Long = 0,
    /** When it last passed a check; 0 if never. */
    val lastOk: Long = 0,
    val ms: Long = 0,
    val exitIp: String? = null,
    val country: String? = null,
    /** Failed checks since it last passed. */
    val fails: Int = 0,
    /** The last deep inspection, and when; see [ProxyReport]. */
    val report: ProxyReport? = null,
    val inspectedAt: Long = 0,
) {
    val protocol: String get() = url.substringBefore("://")
    val working: Boolean get() = lastOk > 0 && fails == 0
    val unchecked: Boolean get() = lastChecked == 0L
}

@Serializable
data class ProxyPool(
    val id: String,
    val name: String = "New pool",
    val proxies: List<PoolProxy> = emptyList(),
    val update: PoolUpdate = PoolUpdate(),
    val lastUpdated: Long = 0,
    val lastError: String? = null,
    /** Stops automatic and background updates, and the engine's health checks of it. */
    val paused: Boolean = false,
) {
    val working: Int get() = proxies.count { it.working }

    fun problem(): String? = if (name.isBlank()) "Name the pool" else update.problem()

    /** Due for an automatic update: its interval is up, or it is running low. */
    fun due(now: Long): Boolean = !paused && update.mode == UpdateMode.AUTO &&
        (now - lastUpdated >= update.everyMinutes * 60_000L || working < (update.target + 1) / 2)

    /** Adds proxies by hand; they are unchecked until the next check. */
    fun add(urls: List<String>, now: Long): ProxyPool {
        val have = proxies.map { it.url }.toSet()
        return copy(proxies = proxies + urls.distinct().filter { it !in have }.map { PoolProxy(it, added = now) })
    }

    /**
     * Folds check or scrape results in: passes reset a proxy's strikes and record where it
     * exits; failures add a strike; proxies at [PoolUpdate.pruneAfter] strikes are dropped.
     * Results for proxies not yet in the pool are added, if they passed.
     */
    fun merge(results: List<ScrapeResult>, now: Long): ProxyPool {
        val byUrl = proxies.associateBy { it.url }.toMutableMap()
        for (r in results) {
            val old = byUrl[r.url]
            byUrl[r.url] = when {
                r.ok -> (old ?: PoolProxy(r.url, added = now)).copy(
                    lastChecked = now, lastOk = now, ms = r.ms, exitIp = r.exitIp, country = r.country, fails = 0,
                )
                old != null -> old.copy(lastChecked = now, fails = old.fails + 1)
                else -> continue
            }
        }
        return copy(proxies = byUrl.values.filter { it.fails < update.pruneAfter }.toList())
    }

    /** Stores inspection reports against the proxies they describe. */
    fun withReports(reports: List<ProxyReport>, now: Long): ProxyPool {
        val byUrl = reports.associateBy { it.url }
        return copy(proxies = proxies.map { p -> byUrl[p.url]?.let { p.copy(report = it, inspectedAt = now) } ?: p })
    }

    fun withoutDead(): ProxyPool = copy(proxies = proxies.filter { it.working || it.unchecked })

    /** What the engine rotates through: working ones fastest first, then the unchecked. */
    fun engineUrls(): List<String> =
        proxies.filter { it.working }.sortedBy { it.ms }.map { it.url } +
            proxies.filter { it.unchecked }.map { it.url } +
            proxies.filter { !it.working && !it.unchecked }.map { it.url }

    companion object {
        fun parseList(s: String): List<ProxyPool> = json.decodeFromString(ListSerializer(serializer()), s)
        fun encodeList(l: List<ProxyPool>): String = json.encodeToString(ListSerializer(serializer()), l)
    }
}

/**
 * Rotating hops used to carry their own proxy list and refill setting. Each such hop
 * becomes a pool of its own, keeping its list and, if it refilled, updating automatically
 * through the same route, and then points at it.
 */
fun migrateRotatingHops(profiles: List<Profile>, pools: List<ProxyPool>, now: Long): Pair<List<Profile>, List<ProxyPool>> {
    val made = mutableListOf<ProxyPool>()
    val out = profiles.map { p ->
        p.copy(hops = p.hops.map { h ->
            if (h.kind != HopKind.ROTATING || h.poolId.isNotEmpty()) return@map h
            val pool = ProxyPool(
                id = "pool-${h.id}",
                name = "${p.name} pool",
                update = PoolUpdate(
                    mode = if (h.refill) UpdateMode.AUTO else UpdateMode.MANUAL,
                    via = h.refillVia,
                ),
            ).add(h.pool, now)
            made += pool
            h.copy(poolId = pool.id, pool = emptyList(), refill = false)
        })
    }
    return out to pools + made
}
