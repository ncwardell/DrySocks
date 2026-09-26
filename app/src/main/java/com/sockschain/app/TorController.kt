package com.sockschain.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.sockschain.core.Hop
import com.sockschain.core.json
import com.sockschain.engine.mobile.Mobile
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.torproject.jni.TorService

sealed interface TorStatus {
    data object Off : TorStatus

    /** Tor is up far enough to take SOCKS connections; it holds them until it has a circuit. */
    data class Starting(val socksPort: Int?, val percent: Int, val summary: String) : TorStatus
    data class On(val socksPort: Int) : TorStatus
    data class Failed(val reason: String) : TorStatus
}

/**
 * The Tor inside this app: Guardian Project's tor-android, run in this process.
 *
 * Being in this process is what makes it work with lockdown VPN: Android exempts the
 * VPN app from its own lockdown, and Tor's sockets are this app's sockets. It also
 * keeps Tor out of the tunnel, since the app is excluded from its own VPN.
 *
 * Used by the VPN and by the scraper; whoever needs it calls [start], and [release]
 * when done. Tor stops when nobody holds it.
 */
object TorController {
    private val _status = MutableStateFlow<TorStatus>(TorStatus.Off)
    val status: StateFlow<TorStatus> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var service: TorService? = null
    private var connection: ServiceConnection? = null
    private var watcher: Job? = null
    private val holders = mutableSetOf<String>()
    private var upstream: List<Hop>? = null

    /**
     * Makes sure Tor runs with [before] as its upstream (empty: Tor dials directly) and
     * returns its SOCKS port. [holder] names the caller, so one release does not stop
     * Tor under another.
     */
    /**
     * With [adopt], a Tor already running is used as it is, whatever its upstream: for
     * scrapes, which want *a* Tor and must not re-route the one the VPN is using.
     */
    suspend fun start(context: Context, holder: String, before: List<Hop> = emptyList(), adopt: Boolean = false): Int = lock.withLock {
        holders += holder
        val app = context.applicationContext

        if (adopt && service != null) {
            // Leave the upstream alone.
        } else if (service == null) {
            upstream = before
            writeTorrc(app, upstreamLine(before))
            bind(app)
        } else if (upstream != before) {
            // Change Tor's upstream in place; restarting Tor inside one process is the
            // part of tor-android least worth depending on.
            val ctl = service?.torControlConnection
                ?: error("Tor is still starting; try again in a moment")
            val line = upstreamLine(before)
            if (line == null) ctl.resetConf(listOf("Socks5Proxy")) else ctl.setConf("Socks5Proxy", line)
            upstream = before
        }

        withTimeoutOrNull(60_000) {
            status.first { it is TorStatus.On || (it is TorStatus.Starting && it.socksPort != null) || it is TorStatus.Failed }
        }.let {
            when (it) {
                is TorStatus.On -> it.socksPort
                is TorStatus.Starting -> it.socksPort!!
                is TorStatus.Failed -> error("Tor: ${it.reason}")
                else -> error("Tor did not open its SOCKS port within a minute")
            }
        }
    }

    /** Waits until Tor has built a circuit; false on timeout. For tests of the chain. */
    suspend fun awaitCircuit(timeoutMs: Long = 120_000): Boolean =
        withTimeoutOrNull(timeoutMs) { status.first { it is TorStatus.On || it is TorStatus.Failed } } is TorStatus.On

    /** For callers about to die, such as a service in teardown, whose own scope would go with them. */
    fun releaseAsync(context: Context, holder: String) {
        val app = context.applicationContext
        scope.launch { release(app, holder) }
    }

    suspend fun release(context: Context, holder: String) = lock.withLock {
        holders -= holder
        if (holders.isNotEmpty()) return@withLock
        watcher?.cancel()
        connection?.let { runCatching { context.applicationContext.unbindService(it) } }
        connection = null
        service = null
        upstream = null
        Mobile.stopUpstream()
        _status.value = TorStatus.Off
    }

    /** Null when Tor dials directly; otherwise its Socks5Proxy value, served by the engine. */
    private fun upstreamLine(before: List<Hop>): String? {
        if (before.isEmpty()) {
            Mobile.stopUpstream()
            return null
        }
        val urls = json.encodeToString(ListSerializer(String.serializer()), before.map { it.toUrl() })
        val port = Mobile.startUpstream(urls)
        return "127.0.0.1:$port"
    }

    private fun writeTorrc(context: Context, socks5Proxy: String?) {
        // TorService adds SOCKSPort auto, the control socket and the data directory itself.
        val lines = buildList {
            add("ClientOnly 1")
            add("AvoidDiskWrites 1")
            socks5Proxy?.let { add("Socks5Proxy $it") }
        }
        TorService.getTorrc(context).writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun bind(app: Context) {
        _status.value = TorStatus.Starting(null, 0, "Starting Tor")
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as TorService.LocalBinder).service
                watcher = scope.launch { watch() }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                _status.value = TorStatus.Failed("Tor stopped")
            }
        }
        connection = conn
        if (!app.bindService(Intent(app, TorService::class.java), conn, Context.BIND_AUTO_CREATE)) {
            _status.value = TorStatus.Failed("could not start Tor's service")
        }
    }

    /** Polls Tor's bootstrap progress; Tor's own events would need a control listener for no gain. */
    private suspend fun watch() {
        while (scope.isActive) {
            val svc = service ?: return
            val port = svc.socksPort.takeIf { it > 0 }
            val phase = runCatching { svc.getInfo("status/bootstrap-phase") }.getOrNull().orEmpty()
            val percent = Regex("""PROGRESS=(\d+)""").find(phase)?.groupValues?.get(1)?.toInt() ?: 0
            val summary = Regex("""SUMMARY="([^"]*)"""").find(phase)?.groupValues?.get(1) ?: "Starting Tor"

            _status.value = if (percent >= 100 && port != null) {
                TorStatus.On(port)
            } else {
                TorStatus.Starting(port, percent, summary)
            }
            delay(if (percent >= 100) 5_000 else 500)
        }
    }
}
