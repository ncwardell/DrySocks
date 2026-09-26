package com.sockschain.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sockschain.app.ChainVpnService
import com.sockschain.app.MainViewModel
import com.sockschain.app.R
import com.sockschain.app.Protection
import com.sockschain.app.VpnStatus
import com.sockschain.core.AppMode
import com.sockschain.core.HopKind
import com.sockschain.core.Profile

private sealed interface Screen {
    data object Home : Screen
    data class Edit(val profile: Profile) : Screen
    data object Scraper : Screen
    data object Network : Screen
    data object Pools : Screen
}

@Composable
fun SocksChainApp(vm: MainViewModel, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            vm = vm,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            onEdit = { vm.clearProbe(); screen = Screen.Edit(it) },
            onScraper = { screen = Screen.Scraper },
            onNetwork = { screen = Screen.Network },
            onPools = { screen = Screen.Pools },
        )
        Screen.Pools -> {
            BackHandler { screen = Screen.Home }
            PoolsScreen(vm, onDone = { screen = Screen.Home })
        }
        Screen.Network -> {
            BackHandler { screen = Screen.Home }
            NetworkScreen(vm, onDone = { screen = Screen.Home })
        }
        Screen.Scraper -> {
            BackHandler { screen = Screen.Home }
            ScraperScreen(vm, onDone = { screen = Screen.Home })
        }
        is Screen.Edit -> {
            BackHandler { screen = Screen.Home }
            EditorScreen(
                vm = vm,
                initial = s.profile,
                isNew = vm.profiles.value.none { it.id == s.profile.id },
                onDone = { screen = Screen.Home },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    vm: MainViewModel,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onEdit: (Profile) -> Unit,
    onScraper: () -> Unit,
    onNetwork: () -> Unit,
    onPools: () -> Unit,
) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val status by ChainVpnService.status.collectAsStateWithLifecycle()
    val traffic by ChainVpnService.traffic.collectAsStateWithLifecycle()
    val protection by ChainVpnService.protection.collectAsStateWithLifecycle()
    val effectiveSelected = selectedId ?: profiles.firstOrNull()?.id

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onPools) {
                        Icon(Icons.Default.Layers, stringResource(R.string.pools_title))
                    }
                    IconButton(onClick = onNetwork) {
                        Icon(Icons.Default.Hub, stringResource(R.string.network_title))
                    }
                    IconButton(onClick = onScraper) {
                        Icon(Icons.Default.TravelExplore, stringResource(R.string.scraper_title))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEdit(vm.newProfile()) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.new_chain)) },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StatusCard(status, traffic, canConnect = profiles.isNotEmpty(), onConnect, onDisconnect)
            }
            item { ProtectionCard(protection, selected = profiles.firstOrNull { it.id == effectiveSelected }) }
            if (profiles.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.no_chains),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
            items(profiles, key = { it.id }) { p ->
                ProfileRow(
                    profile = p,
                    selected = p.id == effectiveSelected,
                    onSelect = { vm.select(p.id) },
                    onEdit = { onEdit(p) },
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    status: VpnStatus,
    traffic: Pair<Long, Long>,
    canConnect: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val on = status is VpnStatus.Running || status is VpnStatus.Connecting || status is VpnStatus.Blocked
    Card(
        colors = when {
            status is VpnStatus.Blocked ->
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            on -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            else -> CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when (status) {
                    VpnStatus.Stopped -> stringResource(R.string.status_stopped)
                    is VpnStatus.Connecting -> stringResource(R.string.status_connecting)
                    is VpnStatus.Running -> stringResource(R.string.status_running, status.profile)
                    is VpnStatus.Failed -> stringResource(R.string.status_failed, status.reason)
                    is VpnStatus.Blocked -> stringResource(R.string.status_blocked, status.reason)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (status is VpnStatus.Running) {
                Text(
                    "↑ ${bytes(traffic.first)}   ↓ ${bytes(traffic.second)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (on) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Blocked holds traffic back until the chain comes up; after fixing it,
                    // retry from here without dropping the block in between.
                    if (status is VpnStatus.Blocked) {
                        Button(onClick = onConnect) { Text(stringResource(R.string.retry)) }
                    }
                    FilledTonalButton(onClick = onDisconnect) { Text(stringResource(R.string.disconnect)) }
                }
            } else {
                Button(onClick = onConnect, enabled = canConnect) { Text(stringResource(R.string.connect)) }
            }
        }
    }
}

@Composable
private fun ProfileRow(profile: Profile, selected: Boolean, onSelect: () -> Unit, onEdit: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onSelect)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(Modifier.weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    chainSummary(profile),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                profile.problem()?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, stringResource(R.string.edit)) }
        }
    }
}

/**
 * On-at-boot and the kill switch are Android's settings, not this app's: only a
 * device-owner app may set them for another. So this shows them and links to them.
 */
@Composable
private fun ProtectionCard(protection: Protection?, selected: Profile?) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.protection_heading), style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    protection == null -> stringResource(R.string.protection_unknown)
                    protection.lockdown -> stringResource(R.string.protection_lockdown)
                    protection.alwaysOn -> stringResource(R.string.protection_always_on)
                    else -> stringResource(R.string.protection_off)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (protection?.lockdown != true) {
                Text(stringResource(R.string.protection_how), style = MaterialTheme.typography.bodySmall)
            }
            // What lockdown will cost with this chain, said before it bites.
            if (selected != null && protection?.alwaysOn != false) {
                lockdownCosts(selected).forEach {
                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text(stringResource(R.string.open_vpn_settings)) }
        }
    }
}

private fun lockdownCosts(p: Profile): List<Int> = buildList {
    if (p.appMode == AppMode.ONLY || p.apps.isNotEmpty()) add(R.string.lockdown_cost_apps)
    if (p.bypassLan) add(R.string.lockdown_cost_lan)
}

fun chainSummary(p: Profile): String = p.hops.joinToString(" → ") {
    if (it.kind == HopKind.TOR) "Tor" else if (it.kind == HopKind.ROTATING) "Rotating" else "${it.kind.label} ${it.host}:${it.port}"
} + " → internet"

