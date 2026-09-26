package com.sockschain.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sockschain.core.AppMode
import com.sockschain.core.Profile
import com.sockschain.core.Routes
import com.sockschain.engine.mobile.Mobile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetAddress

sealed interface VpnStatus {
    data object Stopped : VpnStatus
    data class Connecting(val profile: String) : VpnStatus
    data class Running(val profile: String) : VpnStatus
    data class Failed(val reason: String) : VpnStatus

    /** The chain could not start, and all traffic is being dropped instead of leaking. */
    data class Blocked(val reason: String) : VpnStatus
}

/** Android's own settings for this VPN, as the running service last saw them. */
data class Protection(val alwaysOn: Boolean, val lockdown: Boolean)

class ChainVpnService : VpnService() {

    companion object {
        private const val ACTION_START = "com.sockschain.app.START"
        private const val ACTION_STOP = "com.sockschain.app.STOP"
        private const val CHANNEL = "vpn"
        private const val NOTIFICATION_ID = 1

        const val MTU = 1500
        private const val TOR_HOLDER = "vpn"

        // The TUN side of the link. 198.18.0.0/15 is reserved for benchmarking, so it
        // collides with nobody's LAN and survives Routes.LAN being bypassed.
        private const val TUN_V4 = "198.18.0.1"
        private const val DNS_V4 = "198.18.0.2"
        private const val TUN_V6 = "fdfe:dcba:9876::1"

        private val _status = MutableStateFlow<VpnStatus>(VpnStatus.Stopped)
        val status: StateFlow<VpnStatus> = _status.asStateFlow()

        private val _traffic = MutableStateFlow(0L to 0L)
        /** Bytes up and down since the engine last started. */
        val traffic: StateFlow<Pair<Long, Long>> = _traffic.asStateFlow()

        private val _protection = MutableStateFlow<Protection?>(null)
        /** Null until the service has run on Android 10+, which is when it can ask. */
        val protection: StateFlow<Protection?> = _protection.asStateFlow()

        /** The caller must have been through [VpnService.prepare] first. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, ChainVpnService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ChainVpnService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var engineUp = false
    private var ticker: Job? = null
    private var baseline = 0L to 0L

    /** A TUN nobody reads: every packet into it is dropped. Held while failing closed. */
    private var blackhole: ParcelFileDescriptor? = null

    /** Started by always-on VPN, at boot or after a crash, rather than by the user. */
    private var startedBySystem = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch { lock.withLock { teardown(VpnStatus.Stopped) } }
            return START_NOT_STICKY
        }

        // ACTION_START from the app or tile, or SERVICE_INTERFACE from always-on VPN.
        // Either way the promise made by startForegroundService is kept first.
        startedBySystem = intent?.action == SERVICE_INTERFACE
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(getString(R.string.status_connecting)),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        scope.launch { connect() }
        return START_NOT_STICKY
    }

    private suspend fun connect() = lock.withLock {
        if (engineUp) return@withLock
        if (Build.VERSION.SDK_INT >= 29) _protection.value = Protection(isAlwaysOn, isLockdownEnabled)

        val reason = bringUp()
        when {
            // The new interface has replaced the blackhole; its descriptor can go.
            reason == null -> closeBlackhole()
            failClosed() -> holdBlackhole(reason)
            else -> teardown(VpnStatus.Failed(reason))
        }
    }

    /**
     * Under always-on the user has said traffic goes through this VPN or nowhere, so a
     * chain that cannot start must not let it out. Android's lockdown blocks it anyway;
     * this also covers always-on without lockdown, where stopping would let traffic
     * straight out. A user-started connection that fails just reports and stops.
     */
    private fun failClosed(): Boolean =
        startedBySystem || (Build.VERSION.SDK_INT >= 29 && isAlwaysOn)

    private fun holdBlackhole(reason: String) {
        // Always a fresh one: a failed bringUp may already have established, and so
        // replaced, the interface an older blackhole belonged to.
        val fresh = try {
            Builder()
                .setSession(getString(R.string.app_name))
                .addAddress(TUN_V4, 30)
                .addAddress(TUN_V6, 126)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDisallowedApplication(packageName)
                .establish()
        } catch (_: Exception) {
            null
        }
        closeBlackhole()
        blackhole = fresh
        if (fresh == null) {
            // No permission any more; nothing this app can hold open. Lockdown, if on,
            // still blocks.
            teardown(VpnStatus.Failed(reason))
            return
        }
        _status.value = VpnStatus.Blocked(reason)
        notify(getString(R.string.status_blocked, reason))
    }

    private fun closeBlackhole() {
        blackhole?.close()
        blackhole = null
    }

    /** Starts the engine for the selected profile; returns why not, or null. */
    private suspend fun bringUp(): String? {
        val profile = ProfileStore(this).selected() ?: return "No chain configured"
        // Before the engine starts, so nothing slips through while the app's copy of
        // the blocklist is still on its way.
        runCatching { BlockStore(this).push() }
        // Rotating hops' pools, before anything dials through them (Tor's upstream too).
        runCatching { PoolStore.migrate(this) }
        runCatching { Pools.sync(this, profile) }
        profile.problem()?.let { return it }
        _status.value = VpnStatus.Connecting(profile.name)

        // Tor runs in this process, so like the app it is outside the tunnel and exempt
        // from lockdown. The engine starts as soon as Tor's SOCKS port is open; Tor holds
        // connections until it has a circuit, and the engine waits up to 30 s for them.
        val torPort = if (profile.usesTor) {
            try {
                TorController.start(this, TOR_HOLDER, profile.hopsBeforeTor)
            } catch (e: Exception) {
                return e.message ?: e.toString()
            }
        } else {
            TorController.release(this, TOR_HOLDER)
            null
        }

        val tun = try {
            builderFor(profile).establish()
        } catch (e: Exception) {
            return e.message ?: e.toString()
        } ?: return "VPN permission was withdrawn; open the app to grant it"

        try {
            // detachFd hands the descriptor to Go, which closes it on stop. It is
            // detached before the call so a failure inside Go cannot double-close it.
            Mobile.start(tun.detachFd().toLong(), profile.engineConfig(MTU, torPort, tunDns = DNS_V4))
        } catch (e: Exception) {
            return e.message ?: e.toString()
        }
        engineUp = true
        PoolUpdater.startAuto(this)
        _traffic.value = 0L to 0L
        baseline = Mobile.bytesUp() to Mobile.bytesDown()
        _status.value = VpnStatus.Running(profile.name)
        notify(getString(R.string.status_running, profile.name))
        ticker = scope.launch {
            while (isActive) {
                _traffic.value = (Mobile.bytesUp() - baseline.first) to (Mobile.bytesDown() - baseline.second)
                delay(1000)
            }
        }
        return null
    }

    private fun builderFor(profile: Profile): Builder {
        val b = Builder()
            .setSession(profile.name)
            .setMtu(MTU)
            .addAddress(TUN_V4, 30)
            .addAddress(TUN_V6, 126)
            .addDnsServer(DNS_V4)
            // All IPv6 goes in too; left out, it would go around the chain.
            .addRoute("::", 0)

        when {
            !profile.bypassLan -> b.addRoute("0.0.0.0", 0)
            Build.VERSION.SDK_INT >= 33 -> {
                b.addRoute("0.0.0.0", 0)
                Routes.LAN.forEach { b.excludeRoute(IpPrefix(InetAddress.getByName(it.address), it.prefix)) }
            }
            else -> Routes.complement(Routes.LAN).forEach { b.addRoute(it.address, it.prefix) }
        }

        // This app's own sockets, including the one to the first hop and Tor's, must not
        // loop back into the VPN. In ALL_EXCEPT mode it is excluded explicitly; in ONLY
        // mode it is simply never on the list.
        val outside = setOf(packageName)
        when (profile.appMode) {
            AppMode.ALL_EXCEPT -> (profile.apps + outside).forEach { pkg ->
                try {
                    b.addDisallowedApplication(pkg)
                } catch (_: PackageManager.NameNotFoundException) {
                    // Uninstalled since it was picked; nothing to exclude.
                }
            }
            AppMode.ONLY -> {
                val added = (profile.apps - outside).count { pkg ->
                    try {
                        b.addAllowedApplication(pkg); true
                    } catch (_: PackageManager.NameNotFoundException) {
                        false
                    }
                }
                // With an empty allow-list Android routes every app, the opposite of intent.
                check(added > 0) { "None of the chosen apps are installed any more" }
            }
        }
        return b
    }

    /** Call with [lock] held. */
    private fun teardown(final: VpnStatus) {
        ticker?.cancel()
        PoolUpdater.stopAuto()
        if (engineUp) {
            Mobile.stop()
            engineUp = false
        }
        closeBlackhole()
        TorController.releaseAsync(this, TOR_HOLDER)
        _status.value = final
        _protection.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        // Another VPN took over, or the user revoked us in Settings.
        scope.launch { lock.withLock { teardown(VpnStatus.Stopped) } }
    }

    override fun onDestroy() {
        if (engineUp) {
            Mobile.stop()
            engineUp = false
            _status.value = VpnStatus.Stopped
        }
        closeBlackhole()
        TorController.releaseAsync(this, TOR_HOLDER)
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_vpn), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ChainVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }
}
