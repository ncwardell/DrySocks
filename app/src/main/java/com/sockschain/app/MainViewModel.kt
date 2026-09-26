package com.sockschain.app

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.Manifest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sockschain.core.Hop
import com.sockschain.core.ScrapeResult
import com.sockschain.core.ScrapeSource
import com.sockschain.core.scrapeConfig
import com.sockschain.engine.mobile.ScrapeListener
import com.sockschain.core.Profile
import com.sockschain.core.ProbeResult
import com.sockschain.engine.mobile.Mobile
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
    val viaTor: Boolean = false,
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
     * Downloads the enabled lists and tests candidates until [want] pass. With [viaTor],
     * lists are fetched and every candidate is dialled through Tor, so neither the list
     * hosts nor the proxies, some of them run to collect exactly this, see the phone.
     */
    fun startScrape(want: Int, viaTor: Boolean) {
        val app = getApplication<Application>()
        _scrape.value = ScrapeState(phase = ScrapePhase.Preparing, viaTor = viaTor)
        viewModelScope.launch {
            val via = if (viaTor) {
                try {
                    val port = TorController.start(app, SCRAPE_HOLDER)
                    if (!TorController.awaitCircuit()) error("Tor did not build a circuit within two minutes")
                    listOf("socks5://127.0.0.1:$port")
                } catch (e: Exception) {
                    TorController.release(app, SCRAPE_HOLDER)
                    _scrape.value = _scrape.value.copy(phase = ScrapePhase.Done, error = e.message)
                    return@launch
                }
            } else {
                emptyList()
            }
            _scrape.value = _scrape.value.copy(phase = ScrapePhase.Fetching)
            val cfg = scrapeConfig(sources.value, via, want)
            Mobile.scrapeStart(cfg, object : ScrapeListener {
                override fun onFetched(candidates: Long, summaryJSON: String) {
                    _scrape.value = _scrape.value.copy(
                        phase = ScrapePhase.Testing, total = candidates.toInt(), summary = summaryJSON,
                    )
                }

                override fun onResult(resultJSON: String) {
                    val r = ScrapeResult.parse(resultJSON)
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

    fun cancelScrape() = Mobile.scrapeCancel()

    /** Appends [hop] to an existing chain, or starts a new one with it. */
    fun addHop(hop: Hop, toProfile: Profile?) = viewModelScope.launch {
        val p = toProfile?.let { it.copy(hops = it.hops + hop) }
            ?: newProfile().copy(name = "Free proxy ${hop.host}", hops = listOf(hop))
        store.save(p)
    }

    override fun onCleared() {
        Mobile.scrapeCancel()
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
