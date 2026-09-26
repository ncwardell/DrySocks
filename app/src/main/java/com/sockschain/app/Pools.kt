package com.sockschain.app

import android.content.Context
import com.sockschain.core.PoolConfig
import com.sockschain.core.PoolState
import com.sockschain.core.Profile
import com.sockschain.core.ScrapeResult
import com.sockschain.core.ScrapeVia
import com.sockschain.core.checkConfig
import com.sockschain.core.scrapeConfig
import com.sockschain.engine.mobile.Mobile
import com.sockschain.engine.mobile.ScrapeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** Hands pools to the engine. The engine keeps one copy per rotating hop, by the hop's id. */
object Pools {
    /** Gives every rotating hop in [profile] its pool's current proxies. Safe to repeat. */
    suspend fun sync(context: Context, profile: Profile) {
        val store = PoolStore(context)
        for (hop in profile.rotatingHops) {
            val pool = store.get(hop.poolId)
            val cfg = PoolConfig(pool?.engineUrls().orEmpty(), hop.rotation.engine, hop.intervalSec, pool?.paused ?: false)
            Mobile.setPool(hop.id, cfg.encode())
        }
    }

    fun state(hopId: String): PoolState = PoolState.parse(Mobile.poolStatus(hopId))

    /** Runs one engine scrape or check to completion and returns every result it reported. */
    suspend fun scrapeOnce(session: String, config: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<ScrapeResult> =
        suspendCancellableCoroutine { cont ->
            val found = mutableListOf<ScrapeResult>()
            Mobile.scrapeStart(session, config, object : ScrapeListener {
                override fun onFetched(candidates: Long, summaryJSON: String) {}
                override fun onResult(resultJSON: String) {
                    synchronized(found) { found += ScrapeResult.parse(resultJSON) }
                }
                override fun onProgress(tested: Long, total: Long, found: Long) = onProgress(tested.toInt(), total.toInt())
                override fun onDone(error: String) {
                    if (cont.isActive) cont.resume(synchronized(found) { found.toList() })
                }
            })
            cont.invokeOnCancellation { Mobile.scrapeCancel(session) }
        }
}

/** What an update is doing, for the pool screen. */
data class UpdateProgress(val step: String, val done: Int = 0, val of: Int = 0)

/**
 * Keeps pools fresh. One update: check the proxies the pool has (a failure is a strike;
 * enough strikes and it goes), then, if fewer than the target work, scrape for more — all
 * through the pool's own route, which may be Tor, any chain, or a chain whose rotating hop
 * draws from a different pool.
 */
object PoolUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locks = ConcurrentHashMap<String, Mutex>()

    private val _progress = MutableStateFlow<Map<String, UpdateProgress>>(emptyMap())
    val progress: StateFlow<Map<String, UpdateProgress>> = _progress.asStateFlow()

    private var autoJob: Job? = null

    /** Checks, then scrapes if short. With [scrape] false, only checks. */
    suspend fun update(context: Context, poolId: String, scrape: Boolean = true) {
        val app = context.applicationContext
        val lock = locks.getOrPut(poolId) { Mutex() }
        if (!lock.tryLock()) return // already updating
        val holder = "pool-$poolId"
        val store = PoolStore(app)
        try {
            val pool = store.get(poolId) ?: return
            step(poolId, "Preparing the route")
            refuseSelfRoute(app, pool.update.via, poolId, pool.working)
            val via = Via.resolve(app, pool.update.via, holder)

            if (pool.proxies.isNotEmpty()) {
                step(poolId, "Checking", 0, pool.proxies.size)
                val results = Pools.scrapeOnce(
                    "$holder-check", checkConfig(pool.proxies.map { it.url }, via, pool.update.countries),
                ) { done, of -> step(poolId, "Checking", done, of) }
                store.update(poolId) { it.merge(results, System.currentTimeMillis()) }
            }

            val now = store.get(poolId) ?: return
            val short = now.update.target - now.working
            if (scrape && short > 0) {
                step(poolId, "Scraping for $short more")
                val sources = SourceStore(app).sources.first()
                val found = Pools.scrapeOnce(
                    "$holder-scrape",
                    scrapeConfig(sources, via, short, protocols = now.update.protocols, countries = now.update.countries),
                ) { done, of -> step(poolId, "Scraping for $short more", done, of) }
                store.update(poolId) { it.merge(found, System.currentTimeMillis()) }
            }
            store.update(poolId) { it.copy(lastUpdated = System.currentTimeMillis(), lastError = null) }
            resyncEngine(app)
        } catch (e: Exception) {
            store.update(poolId) { it.copy(lastUpdated = System.currentTimeMillis(), lastError = e.message ?: e.toString()) }
        } finally {
            Via.release(app, holder)
            _progress.value = _progress.value - poolId
            lock.unlock()
        }
    }

    fun updateAsync(context: Context, poolId: String, scrape: Boolean = true) {
        val app = context.applicationContext
        scope.launch { update(app, poolId, scrape) }
    }

    fun cancel(poolId: String) {
        Mobile.scrapeCancel("pool-$poolId-check")
        Mobile.scrapeCancel("pool-$poolId-scrape")
    }

    /** While the VPN runs: every minute, update the automatic pools that are due. */
    fun startAuto(context: Context) {
        stopAuto()
        val app = context.applicationContext
        autoJob = scope.launch {
            while (isActive) {
                runDue(app)
                delay(60_000)
            }
        }
    }

    fun stopAuto() {
        autoJob?.cancel()
        autoJob = null
    }

    /** Updates every automatic pool that is due, one after another; [backgroundOnly] for the worker. */
    suspend fun runDue(context: Context, backgroundOnly: Boolean = false) {
        val now = System.currentTimeMillis()
        PoolStore(context).all()
            .filter { it.due(now) && (!backgroundOnly || it.update.background) }
            .forEach { update(context, it.id) }
    }

    /**
     * A pool cannot fill itself from nothing: updating through a chain that rotates over
     * this very pool works only while the pool has working proxies to carry it.
     */
    private suspend fun refuseSelfRoute(context: Context, via: String, poolId: String, working: Int) {
        val chain = ScrapeVia.chainId(via)?.let { ProfileStore(context).get(it) } ?: return
        if (poolId in chain.poolIds && working == 0) {
            error("“${chain.name}” rotates over this pool, which has no working proxies to carry the update")
        }
    }

    suspend fun resyncEngine(context: Context) {
        if (ChainVpnService.status.value is VpnStatus.Running) {
            ProfileStore(context).selected()?.let { Pools.sync(context, it) }
        }
    }

    private fun step(id: String, what: String, done: Int = 0, of: Int = 0) {
        _progress.value = _progress.value + (id to UpdateProgress(what, done, of))
    }
}

/** Turns a [ScrapeVia] choice into the hop URLs a scrape or check dials through first. */
object Via {
    suspend fun resolve(context: Context, via: String, holder: String): List<String> {
        if (via == ScrapeVia.TOR) {
            val port = TorController.start(context, holder, adopt = true)
            if (!TorController.awaitCircuit()) error("Tor did not build a circuit within two minutes")
            return listOf("socks5://127.0.0.1:$port")
        }
        val id = ScrapeVia.chainId(via) ?: return emptyList()
        val p = ProfileStore(context).get(id) ?: error("That chain no longer exists")
        p.problem()?.let { error("${p.name}: $it") }
        Pools.sync(context, p) // a chain with a rotating hop needs its pool in the engine
        val port = if (p.usesTor) {
            TorController.start(context, holder, p.hopsBeforeTor, adopt = true).also {
                if (!TorController.awaitCircuit()) error("Tor did not build a circuit within two minutes")
            }
        } else {
            null
        }
        return p.engineHops(port)
    }

    suspend fun release(context: Context, holder: String) = TorController.release(context, holder)
}
