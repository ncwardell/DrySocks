package io.github.ncwardell.drysocks

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Quick Settings toggle for the selected chain. */
class ChainTileService : TileService() {
    private var watching: Job? = null

    override fun onStartListening() {
        watching = CoroutineScope(Dispatchers.Main).launch {
            ChainVpnService.status.collect { render(it) }
        }
    }

    override fun onStopListening() {
        watching?.cancel()
    }

    override fun onClick() {
        when (ChainVpnService.status.value) {
            is VpnStatus.Running, is VpnStatus.Connecting, is VpnStatus.Blocked -> ChainVpnService.stop(this)
            else -> if (VpnService.prepare(this) == null) {
                ChainVpnService.start(this)
            } else {
                // Consent has to be given from an activity; the app asks on launch.
                openApp()
            }
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    /** On: the filled mark and the chain's name. Off: the hollow mark, "Off". */
    private fun render(status: VpnStatus) {
        val tile = qsTile ?: return
        val on = status is VpnStatus.Running || status is VpnStatus.Connecting || status is VpnStatus.Blocked
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, if (on) R.drawable.ic_tile else R.drawable.ic_tile_off)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when (status) {
                is VpnStatus.Running -> status.profile
                is VpnStatus.Connecting -> getString(R.string.status_connecting)
                is VpnStatus.Blocked -> getString(R.string.status_blocked_short)
                is VpnStatus.Failed -> getString(R.string.status_failed_short)
                VpnStatus.Stopped -> getString(R.string.tile_off)
            }
        }
        tile.updateTile()
    }
}
