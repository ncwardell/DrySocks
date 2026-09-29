package io.github.ncwardell.drysocks.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ncwardell.drysocks.MainViewModel
import io.github.ncwardell.drysocks.TorController
import io.github.ncwardell.drysocks.TorStatus
import io.github.ncwardell.drysocks.Probe
import io.github.ncwardell.drysocks.R
import io.github.ncwardell.drysocks.core.AppMode
import io.github.ncwardell.drysocks.core.Hop
import io.github.ncwardell.drysocks.core.HopKind
import io.github.ncwardell.drysocks.core.Profile
import io.github.ncwardell.drysocks.core.PoolState
import io.github.ncwardell.drysocks.core.RotationMode
import io.github.ncwardell.drysocks.Pools
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: MainViewModel, initial: Profile, isNew: Boolean, onDone: () -> Unit) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var picking by remember { mutableStateOf(false) }

    if (picking) {
        BackHandler { picking = false }
        AppPickerScreen(
            vm = vm,
            chosen = draft.apps,
            onChange = { draft = draft.copy(apps = it) },
            onDone = { picking = false },
        )
        return
    }

    val problem = draft.problem()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) stringResource(R.string.new_chain) else draft.name) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (!isNew) {
                        IconButton(onClick = { vm.delete(draft.id); onDone() }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.delete))
                        }
                    }
                    TextButton(onClick = { vm.save(draft); onDone() }) { Text(stringResource(R.string.save)) }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text(stringResource(R.string.name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(stringResource(R.string.hops_heading), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.hops_explainer),
                style = MaterialTheme.typography.bodySmall,
            )

            draft.hops.forEachIndexed { i, hop ->
                HopCard(
                    index = i,
                    hop = hop,
                    isFirst = i == 0,
                    isLast = i == draft.hops.lastIndex,
                    onChange = { h -> draft = draft.copy(hops = draft.hops.toMutableList().also { it[i] = h }) },
                    onMove = { d ->
                        draft = draft.copy(
                            hops = draft.hops.toMutableList().also { java.util.Collections.swap(it, i, i + d) },
                        )
                    },
                    onRemove = { draft = draft.copy(hops = draft.hops.filterIndexed { j, _ -> j != i }) },
                    vm = vm,
                    chainName = draft.name,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { draft = draft.copy(hops = draft.hops + Hop()) }) {
                    Text(stringResource(R.string.add_hop))
                }
                // First is where Tor usually goes (Tor → your proxies); the arrows move it.
                if (!draft.usesTor) {
                    OutlinedButton(onClick = { draft = draft.copy(hops = listOf(Hop.tor()) + draft.hops) }) {
                        Text(stringResource(R.string.add_tor))
                    }
                }
                OutlinedButton(onClick = { draft = draft.copy(hops = draft.hops + Hop.rotating()) }) {
                    Text(stringResource(R.string.add_rotating))
                }
            }

            HorizontalDivider()

            OutlinedTextField(
                value = draft.dns,
                onValueChange = { draft = draft.copy(dns = it.trim()) },
                label = { Text(stringResource(R.string.dns)) },
                supportingText = { Text(stringResource(R.string.dns_explainer)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.bypass_lan))
                    Text(stringResource(R.string.bypass_lan_explainer), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = draft.bypassLan, onCheckedChange = { draft = draft.copy(bypassLan = it) })
            }

            Text(stringResource(R.string.apps_heading), style = MaterialTheme.typography.titleSmall)
            AppMode.entries.forEach { mode ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = draft.appMode == mode, onClick = { draft = draft.copy(appMode = mode) })
                    Text(
                        stringResource(
                            if (mode == AppMode.ALL_EXCEPT) R.string.mode_all_except else R.string.mode_only,
                        ),
                    )
                }
            }
            OutlinedButton(onClick = { vm.loadApps(); picking = true }) {
                Text(stringResource(R.string.choose_apps, draft.apps.size))
            }

            HorizontalDivider()

            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            ProbePanel(vm, draft, enabled = problem == null)
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HopCard(
    index: Int,
    hop: Hop,
    isFirst: Boolean,
    isLast: Boolean,
    onChange: (Hop) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    vm: MainViewModel,
    chainName: String,
) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(28.dp))

                var open by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = hop.kind.label,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        HopKind.entries.forEach { k ->
                            DropdownMenuItem(
                                text = { Text(k.label) },
                                onClick = {
                                    open = false
                                    onChange(
                                        when {
                                            k == HopKind.TOR -> Hop.tor()
                                            k == HopKind.ROTATING -> Hop.rotating()
                                            hop.kind == HopKind.TOR || hop.kind == HopKind.ROTATING -> Hop(kind = k)
                                            else -> hop.copy(kind = k)
                                        },
                                    )
                                },
                            )
                        }
                    }
                }

                IconButton(onClick = { onMove(-1) }, enabled = !isFirst) {
                    Icon(Icons.Default.ArrowUpward, stringResource(R.string.move_up))
                }
                IconButton(onClick = { onMove(1) }, enabled = !isLast) {
                    Icon(Icons.Default.ArrowDownward, stringResource(R.string.move_down))
                }
                IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, stringResource(R.string.remove)) }
            }

            if (hop.kind == HopKind.TOR) {
                TorHopNote(isFirst = isFirst, isLast = isLast)
                return@Column
            }
            if (hop.kind == HopKind.ROTATING) {
                RotatingHopEditor(hop, onChange, vm, chainName)
                return@Column
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = hop.host,
                    onValueChange = { onChange(hop.copy(host = it.trim())) },
                    label = { Text(stringResource(R.string.host)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = if (hop.port == 0) "" else hop.port.toString(),
                    onValueChange = { s -> onChange(hop.copy(port = s.filter(Char::isDigit).take(5).toIntOrNull() ?: 0)) },
                    label = { Text(stringResource(R.string.port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(96.dp),
                )
            }

            run {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = hop.user,
                        onValueChange = { onChange(hop.copy(user = it)) },
                        label = { Text(stringResource(if (hop.kind == HopKind.SOCKS4A) R.string.user_id else R.string.user)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    if (hop.kind != HopKind.SOCKS4A) {
                        OutlinedTextField(
                            value = hop.pass,
                            onValueChange = { onChange(hop.copy(pass = it)) },
                            label = { Text(stringResource(R.string.password)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RotatingHopEditor(hop: Hop, onChange: (Hop) -> Unit, vm: MainViewModel, chainName: String) {
    val pools by vm.pools.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val pool = pools.firstOrNull { it.id == hop.poolId }
    var open by remember { mutableStateOf(false) }
    // The engine's copy, once a second: what the next connection gets, and when that changes.
    val state by produceState(PoolState(), hop.id) {
        while (true) {
            value = runCatching { Pools.state(hop.id) }.getOrDefault(PoolState())
            delay(1000)
        }
    }

    Text(stringResource(R.string.rotating_explainer), style = MaterialTheme.typography.bodySmall)
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = pool?.let { "${it.name}  (${it.working}/${it.proxies.size})" } ?: stringResource(R.string.choose_pool),
            onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text(stringResource(R.string.pool)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            pools.forEach { p ->
                DropdownMenuItem(
                    text = { Text("${p.name}  (${p.working}/${p.proxies.size})") },
                    onClick = { open = false; onChange(hop.copy(poolId = p.id)) },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.new_pool)) },
                onClick = {
                    open = false
                    scope.launch { onChange(hop.copy(poolId = vm.newPool("$chainName pool").id)) }
                },
            )
        }
    }
    RotationMode.entries.forEach { m ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            RadioButton(selected = hop.rotation == m, onClick = { onChange(hop.copy(rotation = m)) })
            Column(Modifier.weight(1f)) {
                Text(stringResource(when (m) {
                    RotationMode.SEQUENTIAL -> R.string.rotation_sequential
                    RotationMode.AUTO -> R.string.rotation_auto
                    RotationMode.SCATTERED -> R.string.rotation_scattered
                }))
                Text(
                    stringResource(when (m) {
                        RotationMode.SEQUENTIAL -> R.string.rotation_sequential_explainer
                        RotationMode.AUTO -> R.string.rotation_auto_explainer
                        RotationMode.SCATTERED -> R.string.rotation_scattered_explainer
                    }),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    if (hop.rotation == RotationMode.AUTO) {
        OutlinedTextField(
            value = if (hop.intervalSec == 0) "" else hop.intervalSec.toString(),
            onValueChange = { s -> onChange(hop.copy(intervalSec = s.filter(Char::isDigit).take(5).toIntOrNull() ?: 0)) },
            label = { Text(stringResource(R.string.rotate_every)) },
            suffix = { Text("s") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(160.dp),
        )
    }
    if (pool?.paused == true) {
        Text(stringResource(R.string.pool_paused_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (pool != null && pool.proxies.isEmpty()) {
        Text(stringResource(R.string.pool_empty_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (hop.rotation == RotationMode.SCATTERED && state.size > 0) {
        Text(
            stringResource(R.string.rotating_scattered_now) + "\n" + stringResource(R.string.rotating_health, state.healthy, state.size),
            style = MaterialTheme.typography.labelMedium,
        )
    }
    state.current?.let { cur ->
        Text(
            buildString {
                append(stringResource(R.string.rotating_now, cur))
                if (state.nextInMs > 0) append(" · ").append(stringResource(R.string.rotating_next, (state.nextInMs + 999) / 1000))
                append("\n").append(stringResource(R.string.rotating_health, state.healthy, state.size))
            },
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun TorHopNote(isFirst: Boolean, isLast: Boolean) {
    val tor by TorController.status.collectAsStateWithLifecycle()
    Text(
        stringResource(
            when {
                isFirst && isLast -> R.string.tor_alone
                isFirst -> R.string.tor_first
                isLast -> R.string.tor_last
                else -> R.string.tor_middle
            },
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    val state = when (val t = tor) {
        TorStatus.Off -> null
        is TorStatus.Starting -> stringResource(R.string.tor_starting, t.percent, t.summary)
        is TorStatus.On -> stringResource(R.string.tor_on)
        is TorStatus.Failed -> stringResource(R.string.tor_failed, t.reason)
    }
    state?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
}

@Composable
private fun ProbePanel(vm: MainViewModel, draft: Profile, enabled: Boolean) {
    val probe by vm.probe.collectAsStateWithLifecycle()

    Button(onClick = { vm.probe(draft) }, enabled = enabled && probe != Probe.Running) {
        Text(stringResource(R.string.test_chain))
    }
    when (val p = probe) {
        Probe.Idle -> Unit
        Probe.Running -> CircularProgressIndicator()
        is Probe.Done -> Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                p.result.steps.forEachIndexed { i, s ->
                    val what = if (i == 0) "connect" else "leg $i"
                    Text("$what → ${s.to}   ${s.ms} ms", style = MaterialTheme.typography.bodySmall)
                }
                p.result.exitIp?.let {
                    Text(stringResource(R.string.exit_ip, it), style = MaterialTheme.typography.titleSmall)
                }
                p.result.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
