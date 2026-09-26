package com.sockschain.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
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

    private fun render(status: VpnStatus) {
        val tile = qsTile ?: return
        tile.state = when (status) {
            is VpnStatus.Running, is VpnStatus.Connecting, is VpnStatus.Blocked -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when (status) {
                is VpnStatus.Running -> status.profile
                is VpnStatus.Connecting -> getString(R.string.status_connecting)
                is VpnStatus.Blocked -> getString(R.string.status_blocked_short)
                is VpnStatus.Failed -> getString(R.string.status_failed_short)
                VpnStatus.Stopped -> null
            }
        }
        tile.updateTile()
    }
}
