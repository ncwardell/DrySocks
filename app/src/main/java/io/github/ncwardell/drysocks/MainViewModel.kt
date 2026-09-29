package io.github.ncwardell.drysocks

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.Manifest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ncwardell.drysocks.core.Hop
import io.github.ncwardell.drysocks.core.ScrapeResult
import io.github.ncwardell.drysocks.core.ScrapeSource
import io.github.ncwardell.drysocks.core.ScrapeVia
import io.github.ncwardell.drysocks.core.scrapeConfig
import io.github.ncwardell.drysocks.engine.mobile.ScrapeListener
import io.github.ncwardell.drysocks.core.Profile
import io.github.ncwardell.drysocks.core.ProxyPool
import io.github.ncwardell.drysocks.core.ProbeResult
import io.github.ncwardell.drysocks.engine.mobile.Mobile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class InstalledApp(val pkg: String, val label: String, val system: Boolean)

sealed interface Probe {
    data object Idle : Probe
    data object Running : Probe
    data class Done(val result: ProbeResult) : Probe
}

enum class ScrapePhase { Idle, Preparing, Fetching, Testing, Done }

data class ScrapeState(
    val phase: ScrapePhase = ScrapePhase.Idle,
    val via: String = ScrapeVia.DIRECT,
    val total: Int = 0,
    val tested: Int = 0,
    val found: List<ScrapeResult> = emptyList(),
    val summary: String? = null,
    val error: String? = null,
) {
    val running get() = phase in setOf(ScrapePhase.Preparing, ScrapePhase.Fetching, ScrapePhase.Testing)
}

private const val PROBE_HOLDER = "probe"
private const val SCRAPE_HOLDER = "scrape"
private const val SCRAPE_SESSION = "user"

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ProfileStore(app)

    val profiles: StateFlow<List<Profile>> =
        store.profiles.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val selectedId: StateFlow<String?> =
        store.selectedId.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _probe = MutableStateFlow<Probe>(Probe.Idle)
    val probe: StateFlow<Probe> = _probe.asStateFlow()

    private val _apps = MutableStateFlow<List<InstalledApp>?>(null)
    val apps: StateFlow<List<InstalledApp>?> = _apps.asStateFlow()

    fun newProfile() = Profile(id = UUID.randomUUID().toString())

    fun save(p: Profile) = viewModelScope.launch { store.save(p) }
    fun delete(id: String) = viewModelScope.launch { store.delete(id) }
    fun select(id: String) = viewModelScope.launch { store.select(id) }

    /**
     * Dials the draft's chain from this app, which is always outside the VPN, so it tests
     * the chain itself and not whatever the VPN is doing.
     */
    fun probe(draft: Profile) {
        _probe.value = Probe.Running
        viewModelScope.launch {
            val app = getApplication<Application>()
            val result = try {
                Pools.sync(app, draft)
                val torPort = if (draft.usesTor) {
                    TorController.start(app, PROBE_HOLDER, draft.hopsBeforeTor).also {
                        // A probe before Tor has a circuit only measures Tor's patience.
                        if (!TorController.awaitCircuit()) error("Tor did not build a circuit within two minutes")
                    }
                } else {
                    null
                }
                val raw = withContext(Dispatchers.IO) { Mobile.probe(draft.engineConfig(ChainVpnService.MTU, torPort)) }
                ProbeResult.parse(raw)
            } catch (e: Exception) {
                ProbeResult(error = e.message ?: e.toString())
            } finally {
                TorController.release(app, PROBE_HOLDER)
            }
            _probe.value = Probe.Done(result)
        }
    }

    // --- Scraper ---------------------------------------------------------------------

    private val sourceStore = SourceStore(app)
    val sources: StateFlow<List<ScrapeSource>> =
        sourceStore.sources.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun saveSources(list: List<ScrapeSource>) = viewModelScope.launch { sourceStore.save(list) }

    private val _scrape = MutableStateFlow(ScrapeState())
    val scrape: StateFlow<ScrapeState> = _scrape.asStateFlow()

    /**
     * Downloads the enabled lists and tests candidates until [want] pass, all through
     * [via]: directly, through Tor, or through one of the saved chains. Through anything
     * but directly, neither the list hosts nor the proxies, some of them run to collect
     * exactly this, see the phone.
     */
    fun startScrape(want: Int, via: String) {
        val app = getApplication<Application>()
        _scrape.value = ScrapeState(phase = ScrapePhase.Preparing, via = via)
        viewModelScope.launch {
            val hops = try {
                Via.resolve(app, via, SCRAPE_HOLDER)
            } catch (e: Exception) {
                Via.release(app, SCRAPE_HOLDER)
                _scrape.value = _scrape.value.copy(phase = ScrapePhase.Done, error = e.message)
                return@launch
            }
            _scrape.value = _scrape.value.copy(phase = ScrapePhase.Fetching)
            val cfg = scrapeConfig(sources.value, hops, want)
            Mobile.scrapeStart(SCRAPE_SESSION, cfg, object : ScrapeListener {
                override fun onFetched(candidates: Long, summaryJSON: String) {
                    _scrape.value = _scrape.value.copy(
                        phase = ScrapePhase.Testing, total = candidates.toInt(), summary = summaryJSON,
                    )
                }

                // On the engine's threads: nothing may escape, or the process aborts.
                override fun onResult(resultJSON: String) {
                    val r = runCatching { ScrapeResult.parse(resultJSON) }.getOrNull() ?: return
                    _scrape.value = _scrape.value.let { st -> st.copy(found = (st.found + r).sortedBy { it.ms }) }
                }

                override fun onProgress(tested: Long, total: Long, found: Long) {
                    _scrape.value = _scrape.value.copy(tested = tested.toInt(), total = total.toInt())
                }

                override fun onDone(error: String) {
                    _scrape.value = _scrape.value.copy(phase = ScrapePhase.Done, error = error.ifEmpty { null })
                    TorController.releaseAsync(app, SCRAPE_HOLDER)
                }
            })
        }
    }

    // --- Pools -------------------------------------------------------------------------

    private val poolStore = PoolStore(app)
    val pools: StateFlow<List<ProxyPool>> =
        poolStore.pools.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch { PoolStore.migrate(app) }
    }

    /** Adds [urls] to the pool [poolId], or to a new pool called [newName]; returns its id. */
    suspend fun addToPool(urls: List<String>, poolId: String?, newName: String = "Scraped proxies"): String {
        val id = poolId ?: poolStore.create(newName).id
        poolStore.update(id) { it.add(urls, System.currentTimeMillis()) }
        return id
    }

    /** Scraper results arrive checked; keep what the check found rather than re-checking. */
    fun addResultsToPool(results: List<ScrapeResult>, poolId: String?, newName: String = "Scraped proxies") =
        viewModelScope.launch {
            val id = poolId ?: poolStore.create(newName).id
            poolStore.update(id) { it.merge(results, System.currentTimeMillis()) }
        }

    suspend fun newPool(name: String): ProxyPool = poolStore.create(name)
    /**
     * Saves a pool's name and settings only: its proxies may have changed under the
     * screen, by an update running meanwhile, and must not be put back as they were.
     */
    fun savePoolSettings(p: ProxyPool) = viewModelScope.launch {
        poolStore.update(p.id) { it.copy(name = p.name, update = p.update) }
    }
    fun deletePool(id: String) = viewModelScope.launch { poolStore.delete(id) }
    fun updatePool(id: String, scrape: Boolean = true) = PoolUpdater.updateAsync(getApplication(), id, scrape)
    fun inspectPool(id: String, urls: List<String>? = null) = PoolUpdater.inspectAsync(getApplication(), id, urls)
    fun cancelPoolUpdate(id: String) = PoolUpdater.cancel(id)
    /** Pausing also stops an update in progress. */
    fun setPaused(id: String, paused: Boolean) = viewModelScope.launch {
        if (paused) PoolUpdater.cancel(id)
        poolStore.update(id) { it.copy(paused = paused) }
        PoolUpdater.resyncEngine(getApplication())
    }

    fun removeDead(id: String) = viewModelScope.launch { poolStore.update(id) { it.withoutDead() } }
    fun removeProxy(id: String, url: String) = viewModelScope.launch {
        poolStore.update(id) { p -> p.copy(proxies = p.proxies.filter { it.url != url }) }
    }

    fun cancelScrape() = Mobile.scrapeCancel(SCRAPE_SESSION)

    /** Appends [hop] to an existing chain, or starts a new one with it. */
    fun addHop(hop: Hop, toProfile: Profile?) = viewModelScope.launch {
        val p = toProfile?.let { it.copy(hops = it.hops + hop) }
            ?: newProfile().copy(name = "Free proxy ${hop.host}", hops = listOf(hop))
        store.save(p)
    }

    override fun onCleared() {
        Mobile.scrapeCancel(SCRAPE_SESSION)
    }

    // --- Network view ------------------------------------------------------------------

    private val blockStore = BlockStore(app)
    val blockRules: StateFlow<List<String>> =
        blockStore.rules.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Polls only while the network screen is on screen. */
    val network: StateFlow<NetView> = NetworkWatcher(app).watch()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), NetView())

    /** Returns the rule as stored, or null if [raw] is not a domain, address or range. */
    suspend fun block(raw: String): String? = blockStore.add(raw)

    fun unblock(rule: String) = viewModelScope.launch { blockStore.remove(rule) }

    fun clearProbe() {
        _probe.value = Probe.Idle
    }

    /** Apps that can use the network at all; nothing else is worth listing. */
    fun loadApps() {
        if (_apps.value != null) return
        viewModelScope.launch(Dispatchers.IO) {
            val pm = getApplication<Application>().packageManager
            val self = getApplication<Application>().packageName
            _apps.value = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                .filter { it.packageName != self }
                .filter { it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true }
                .mapNotNull { pi ->
                    val ai = pi.applicationInfo ?: return@mapNotNull null
                    InstalledApp(
                        pkg = pi.packageName,
                        label = ai.loadLabel(pm).toString(),
                        system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    )
                }
                .sortedWith(compareBy({ it.system }, { it.label.lowercase() }))
        }
    }
}
