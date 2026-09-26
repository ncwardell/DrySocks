package com.sockschain.app

import android.Manifest
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.sockschain.app.ui.SocksChainApp
import com.sockschain.app.ui.SocksChainTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(this) == null) ChainVpnService.start(this)
    }

    // Without it the VPN still runs; its notification, with the Stop button, just stays hidden.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            SocksChainTheme {
                SocksChainApp(vm, onConnect = ::connect, onDisconnect = { ChainVpnService.stop(this) })
            }
        }
    }

    private fun connect() {
        val consent = VpnService.prepare(this)
        if (consent == null) ChainVpnService.start(this) else vpnConsent.launch(consent)
    }
}
