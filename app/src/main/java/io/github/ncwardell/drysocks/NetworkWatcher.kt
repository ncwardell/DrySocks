package io.github.ncwardell.drysocks

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import io.github.ncwardell.drysocks.core.NetConn
import io.github.ncwardell.drysocks.core.NetSnapshot
import io.github.ncwardell.drysocks.engine.mobile.Mobile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.InetSocketAddress

/** One app, as the network view shows it. */
data class AppRef(val uid: Int, val label: String, val pkg: String?)

/** The view's state: the engine's snapshot, who owns what, and recent throughput. */
data class NetView(
    val snapshot: NetSnapshot = NetSnapshot(),
    val owners: Map<Long, AppRef> = emptyMap(),
    /** Bytes per second, oldest first, one sample a second. */
    val upRate: List<Long> = emptyList(),
    val downRate: List<Long> = emptyList(),
    val running: Boolean = false,
)

/**
 * Polls the engine once a second while collected. The log lives in the engine and
 * dies with it; this only adds which app owns each connection, which Android tells
 * the active VPN app (API 29+) while the connection is open, so it is asked once,
 * for each connection, as soon as it is seen.
 */
class NetworkWatcher(context: Context) {
    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val pm = app.packageManager
    private val owners = mutableMapOf<Long, AppRef>()
    private val apps = mutableMapOf<Int, AppRef>()

    fun watch(): Flow<NetView> = flow {
        var last: NetSnapshot? = null
        val ups = ArrayDeque<Long>()
        val downs = ArrayDeque<Long>()
        while (true) {
            val raw = Mobile.snapshot()
            val running = raw != "{}"
            val snap = if (running) NetSnapshot.parse(raw) else NetSnapshot()
            if (!running) owners.clear()

            snap.active.forEach { c -> if (c.id !in owners) owner(c)?.let { owners[c.id] = it } }
            // Forget owners of connections the engine has forgotten.
            val known = (snap.active.asSequence() + snap.recent.asSequence()).map { it.id }.toSet()
            owners.keys.retainAll(known)

            val prev = last
            if (prev != null && running) {
                push(ups, (snap.up - prev.up).coerceAtLeast(0))
                push(downs, (snap.down - prev.down).coerceAtLeast(0))
            }
            last = snap.takeIf { running }
            emit(NetView(snap, owners.toMap(), ups.toList(), downs.toList(), running))
            delay(1000)
        }
    }.flowOn(Dispatchers.IO)

    private fun push(q: ArrayDeque<Long>, v: Long) {
        q.addLast(v)
        while (q.size > WINDOW) q.removeFirst()
    }

    private fun owner(c: NetConn): AppRef? {
        if (Build.VERSION.SDK_INT < 29) return null
        val uid = try {
            cm.getConnectionOwnerUid(OsConstants.IPPROTO_TCP, address(c.src), address(c.dst))
        } catch (_: Exception) {
            return null // closed already, or this app is no longer the active VPN
        }
        if (uid < 0) return null
        return apps.getOrPut(uid) { describe(uid) }
    }

    private fun describe(uid: Int): AppRef {
        val pkg = pm.getPackagesForUid(uid)?.firstOrNull()
        val label = pkg?.let {
            try {
                pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        } ?: when (uid) {
            0 -> "root"
            1000 -> "Android system"
            2000 -> "shell (adb)"
            else -> "uid $uid"
        }
        return AppRef(uid, label, pkg)
    }

    private fun address(s: String): InetSocketAddress {
        val host = s.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        // An address literal, so no lookup happens here.
        return InetSocketAddress(java.net.InetAddress.getByName(host), s.substringAfterLast(':').toInt())
    }

    companion object {
        const val WINDOW = 60
    }
}
