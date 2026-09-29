package io.github.ncwardell.drysocks.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ncwardell.drysocks.MainViewModel
import io.github.ncwardell.drysocks.Pools
import io.github.ncwardell.drysocks.Probe
import io.github.ncwardell.drysocks.R
import io.github.ncwardell.drysocks.TorController
import io.github.ncwardell.drysocks.TorStatus
import io.github.ncwardell.drysocks.core.AppMode
import io.github.ncwardell.drysocks.core.Hop
import io.github.ncwardell.drysocks.core.HopKind
import io.github.ncwardell.drysocks.core.PoolState
import io.github.ncwardell.drysocks.core.Profile
import io.github.ncwardell.drysocks.core.ProxyPool
import io.github.ncwardell.drysocks.core.RotationMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The editor for the selected chain. There is no Save: changes are written a moment
 * after the last keystroke, and take effect on the next connect.
 */
@Composable
fun ChainsScreen(vm: MainViewModel) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val pools by vm.pools.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val saved = profiles.firstOrNull { it.id == (selectedId ?: profiles.firstOrNull()?.id) }

    if (saved == null) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            TitleRow(onNew = { vm.createChain() })
            DashedNote(stringResource(R.string.no_chains))
        }
        return
    }

    // The draft follows the chain being edited; switching chains starts a new one.
    var draft by remember(saved.id) { mutableStateOf(saved) }
    LaunchedEffect(draft) {
        if (draft != saved) {
            delay(400)
            vm.save(draft)
        }
    }
    var picking by remember { mutableStateOf(false) }
    var editingDns by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (picking) {
        BackHandler { picking = false }
        AppPickerScreen(vm, chosen = draft.apps, onChange = { draft = draft.copy(apps = it) }, onDone = { picking = false })
        return
    }
    if (editingDns) {
        DnsDialog(draft.dns, onSave = { draft = draft.copy(dns = it); editingDns = false }, onDismiss = { editingDns = false })
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_chain_title, draft.name)) },
            confirmButton = { TextButton(onClick = { vm.delete(draft.id); confirmDelete = false }) { Text(stringResource(R.string.delete), color = Ds.Coral) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    fun setHops(h: List<Hop>) {
        draft = draft.copy(hops = h)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { TitleRow(onNew = { vm.createChain() }) }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                profiles.forEach { p -> DsChip(p.name, selected = p.id == saved.id, onClick = { vm.select(p.id) }) }
            }
        }
        item { DsTextField(draft.name, { draft = draft.copy(name = it) }, placeholder = stringResource(R.string.name)) }
        item { Text(stringResource(R.string.hops_explainer), style = DsType.Secondary) }
        item {
            HopTimeline(
                hops = draft.hops,
                pools = pools,
                vm = vm,
                chainName = draft.name,
                onChange = { i, h -> setHops(draft.hops.toMutableList().also { it[i] = h }) },
                onMove = { i, d -> setHops(draft.hops.toMutableList().also { java.util.Collections.swap(it, i, i + d) }) },
                onRemove = { i -> setHops(draft.hops.filterIndexed { j, _ -> j != i }) },
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinePill(stringResource(R.string.add_proxy), onClick = { setHops(draft.hops + Hop()) })
                if (!draft.usesTor) {
                    OutlinePill(stringResource(R.string.add_tor_pill), color = Ds.TorPurple, onClick = { setHops(listOf(Hop.tor()) + draft.hops) })
                }
                OutlinePill(stringResource(R.string.add_rotating_pill), color = Ds.Lime, onClick = {
                    scope.launch {
                        val pool = pools.firstOrNull() ?: vm.newPool("${draft.name} pool")
                        setHops(draft.hops + Hop.rotating(pool.id))
                    }
                })
            }
        }
        item {
            SettingsCard(
                draft,
                onDns = { editingDns = true },
                onLan = { draft = draft.copy(bypassLan = it) },
                onApps = { vm.loadApps(); picking = true },
            )
        }
        draft.problem()?.let { item { Text(it, style = DsType.Secondary.copy(color = Ds.Coral)) } }
        item { TestChain(vm, draft) }
        item {
            Centered { DsLink(stringResource(R.string.delete_chain), onClick = { confirmDelete = true }, color = Ds.Coral) }
        }
    }
}

@Composable
private fun TitleRow(onNew: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.tab_chains), style = DsType.ScreenTitle, modifier = Modifier.weight(1f))
        Box(
            Modifier.height(36.dp).clip(CircleShape).background(Ds.Surface).clickable(onClick = onNew).padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.new_pill), style = DsType.Label) }
    }
}

// --- The hop timeline ------------------------------------------------------------------

@Composable
private fun HopTimeline(
    hops: List<Hop>,
    pools: List<ProxyPool>,
    vm: MainViewModel,
    chainName: String,
    onChange: (Int, Hop) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    Column {
        EndNode(stringResource(R.string.node_phone), filled = false)
        if (hops.isEmpty()) {
            Rail(dashed = true) {
                Text(stringResource(R.string.no_hops), style = DsType.BodyMuted, modifier = Modifier.padding(vertical = 18.dp))
            }
        }
        hops.forEachIndexed { i, hop ->
            key(i) {
                Rail {
                    HopCard(
                        index = i, hop = hop, hops = hops, pools = pools, vm = vm, chainName = chainName,
                        onChange = { onChange(i, it) },
                        onUp = { onMove(i, -1) }.takeIf { i > 0 },
                        onDown = { onMove(i, 1) }.takeIf { i < hops.lastIndex },
                        onRemove = { onRemove(i) },
                    )
                }
            }
        }
        EndNode(stringResource(R.string.node_internet), filled = true)
    }
}

@Composable
private fun EndNode(label: String, filled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            if (filled) {
                Box(Modifier.size(16.dp).clip(CircleShape).background(Ds.Lime))
            } else {
                Box(Modifier.size(12.dp).clip(CircleShape).border(2.dp, Ds.Dim, CircleShape))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(label, style = DsType.Label.copy(color = if (filled) Ds.Bone else Ds.Muted))
    }
}

/** The 2dp rail down the left, with the content hanging off it. */
@Composable
private fun Rail(dashed: Boolean = false, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Canvas(Modifier.width(22.dp).fillMaxHeight()) {
            drawLine(
                Color.White.copy(alpha = 0.12f),
                Offset(size.width / 2, 0f), Offset(size.width / 2, size.height),
                strokeWidth = 2.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())) else null,
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).padding(vertical = 6.dp)) { content() }
    }
}

@Composable
private fun HopCard(
    index: Int,
    hop: Hop,
    hops: List<Hop>,
    pools: List<ProxyPool>,
    vm: MainViewModel,
    chainName: String,
    onChange: (Hop) -> Unit,
    onUp: (() -> Unit)?,
    onDown: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    var expanded by remember { mutableStateOf(hop.kind != HopKind.TOR && hop.kind != HopKind.ROTATING && hop.host.isEmpty()) }
    Column(
        Modifier.fillMaxWidth().enter().clip(RoundedCornerShape(18.dp)).background(Ds.Surface).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("%02d".format(index + 1), style = DsType.Mono.copy(color = Ds.Dim))
            Spacer(Modifier.width(10.dp))
            when (hop.kind) {
                HopKind.TOR -> HopTag("Tor", Ds.TorPurple.copy(alpha = 0.14f), Ds.TorPurple)
                HopKind.ROTATING -> HopTag("Rotating", Ds.Lime.copy(alpha = 0.12f), Ds.Lime)
                else -> HopTag(hop.kind.label.substringBefore(' '), Ds.hairline(0.08f), Ds.Bone) {
                    // SOCKS5 → SOCKS4a → HTTP → SOCKS5
                    val next = when (hop.kind) {
                        HopKind.SOCKS5 -> HopKind.SOCKS4A
                        HopKind.SOCKS4A -> HopKind.HTTP
                        else -> HopKind.SOCKS5
                    }
                    onChange(hop.copy(kind = next, pass = if (next == HopKind.SOCKS4A) "" else hop.pass))
                }
            }
            Spacer(Modifier.weight(1f))
            IconBtn(Icons.Default.ArrowUpward, stringResource(R.string.move_up), onUp)
            IconBtn(Icons.Default.ArrowDownward, stringResource(R.string.move_down), onDown)
            IconBtn(Icons.Default.Close, stringResource(R.string.remove), onRemove)
        }
        Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (hop.kind) {
                HopKind.TOR -> TorBody(index, hops)
                HopKind.ROTATING -> RotatingBody(hop, pools, vm, chainName, onChange)
                else -> ProxyBody(hop, expanded, onToggle = { expanded = !expanded }, onChange = onChange)
            }
        }
    }
}

@Composable
private fun IconBtn(icon: ImageVector, label: String, onClick: (() -> Unit)?) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(enabled = onClick != null) { onClick?.invoke() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (onClick != null) Ds.Bone else Ds.Disabled, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun TorBody(index: Int, hops: List<Hop>) {
    val tor by TorController.status.collectAsStateWithLifecycle()
    Text(stringResource(R.string.tor_detail), style = DsType.MonoBody)
    Text(
        stringResource(
            when {
                hops.size == 1 -> R.string.tor_alone
                index == 0 -> R.string.tor_first
                index == hops.lastIndex -> R.string.tor_last
                else -> R.string.tor_middle
            },
        ),
        style = DsType.Secondary,
    )
    when (val t = tor) {
        is TorStatus.Starting -> Text(stringResource(R.string.tor_starting, t.percent, t.summary), style = DsType.Mono.copy(color = Ds.TorPurple))
        is TorStatus.On -> Text(stringResource(R.string.tor_on), style = DsType.Mono.copy(color = Ds.Lime))
        is TorStatus.Failed -> Text(stringResource(R.string.tor_failed, t.reason), style = DsType.Mono.copy(color = Ds.Coral))
        TorStatus.Off -> Unit
    }
}

@Composable
private fun ProxyBody(hop: Hop, expanded: Boolean, onToggle: () -> Unit, onChange: (Hop) -> Unit) {
    val shown = if (hop.host.isEmpty()) stringResource(R.string.proxy_unset) else "${hop.kind.scheme}://${hop.host}:${hop.port}"
    Text(
        shown,
        style = DsType.MonoBody.copy(color = if (hop.host.isEmpty()) Ds.Dim else Ds.Bone),
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onToggle).padding(vertical = 2.dp),
    )
    if (expanded) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DsTextField(hop.host, { onChange(hop.copy(host = it.trim())) }, Modifier.weight(1f), placeholder = stringResource(R.string.host),
                style = DsType.MonoBody, height = 44.dp, keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri))
            DsTextField(if (hop.port == 0) "" else hop.port.toString(), { s -> onChange(hop.copy(port = s.filter(Char::isDigit).take(5).toIntOrNull() ?: 0)) },
                Modifier.width(92.dp), placeholder = stringResource(R.string.port), style = DsType.MonoBody, height = 44.dp,
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DsTextField(hop.user, { onChange(hop.copy(user = it)) }, Modifier.weight(1f),
                placeholder = stringResource(if (hop.kind == HopKind.SOCKS4A) R.string.user_id else R.string.user), style = DsType.Body, height = 44.dp)
            if (hop.kind != HopKind.SOCKS4A) {
                PasswordField(hop.pass, { onChange(hop.copy(pass = it)) }, Modifier.weight(1f))
            }
        }
    }
    Text(hop.problem() ?: stringResource(R.string.proxy_note), style = DsType.Secondary.copy(color = if (hop.problem() != null) Ds.Coral else Ds.Muted))
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, modifier: Modifier) {
    androidx.compose.foundation.text.BasicTextField(
        value, onChange, singleLine = true,
        textStyle = DsType.Body,
        visualTransformation = PasswordVisualTransformation(),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Ds.Lime),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = modifier.height(44.dp).clip(RoundedCornerShape(16.dp)).background(Ds.Surface).border(1.dp, Ds.hairline(0.1f), RoundedCornerShape(16.dp)),
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(stringResource(R.string.password), style = DsType.Body.copy(color = Ds.Dim))
                inner()
            }
        },
    )
}

@Composable
private fun RotatingBody(hop: Hop, pools: List<ProxyPool>, vm: MainViewModel, chainName: String, onChange: (Hop) -> Unit) {
    val scope = rememberCoroutineScope()
    val pool = pools.firstOrNull { it.id == hop.poolId }
    val state by produceState(PoolState(), hop.id) {
        while (true) {
            value = runCatching { Pools.state(hop.id) }.getOrDefault(PoolState())
            delay(1000)
        }
    }
    val modes = listOf(RotationMode.SCATTERED, RotationMode.AUTO, RotationMode.SEQUENTIAL)
    DsSegmented(
        listOf(stringResource(R.string.rotation_scattered), stringResource(R.string.rotation_every, hop.intervalSec), stringResource(R.string.rotation_failover)),
        selected = modes.indexOf(hop.rotation),
        onSelect = { onChange(hop.copy(rotation = modes[it])) },
    )
    if (hop.rotation == RotationMode.AUTO) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.rotate_every), style = DsType.Secondary)
            DsTextField(
                if (hop.intervalSec == 0) "" else hop.intervalSec.toString(),
                { s -> onChange(hop.copy(intervalSec = s.filter(Char::isDigit).take(5).toIntOrNull() ?: 0)) },
                Modifier.width(96.dp), style = DsType.MonoBody, height = 40.dp,
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Text("s", style = DsType.Secondary)
        }
    }
    // Which pool it draws from: chips, plus a new one.
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        pools.forEach { p ->
            DsChip("${p.name}  ${p.working}/${p.proxies.size}", selected = p.id == hop.poolId, onClick = { onChange(hop.copy(poolId = p.id)) })
        }
        DsChip(stringResource(R.string.new_pool), selected = false, onClick = {
            scope.launch { onChange(hop.copy(poolId = vm.newPool("$chainName pool").id)) }
        })
    }
    Text(
        stringResource(
            when (hop.rotation) {
                RotationMode.SCATTERED -> R.string.rotation_scattered_explainer
                RotationMode.AUTO -> R.string.rotation_auto_explainer
                RotationMode.SEQUENTIAL -> R.string.rotation_sequential_explainer
            },
        ),
        style = DsType.Secondary,
    )
    when {
        pool == null -> Text(stringResource(R.string.choose_pool), style = DsType.Secondary.copy(color = Ds.Coral))
        pool.proxies.isEmpty() -> Text(stringResource(R.string.pool_empty_hint), style = DsType.Secondary.copy(color = Ds.Coral))
        state.size > 0 -> Text(
            buildString {
                if (hop.rotation == RotationMode.SCATTERED) {
                    append(stringResource(R.string.rotating_scattered_now))
                } else {
                    state.current?.let { append(stringResource(R.string.rotating_now, it)) }
                    if (state.nextInMs > 0) append(" · ").append(stringResource(R.string.rotating_next, (state.nextInMs + 999) / 1000))
                }
                append("\n").append(stringResource(R.string.rotating_health, state.healthy, state.size))
            },
            style = DsType.Mono.copy(color = Ds.Lime),
        )
    }
}

// --- Settings and test -----------------------------------------------------------------

@Composable
private fun SettingsCard(p: Profile, onDns: () -> Unit, onLan: (Boolean) -> Unit, onApps: () -> Unit) {
    DsCard(padding = PaddingValues(0.dp)) {
        SettingRow(onClick = onDns) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (p.dns.startsWith("https://")) R.string.dns_over_https else R.string.dns_over_tcp), style = DsType.Secondary)
                Text(p.dns, style = DsType.MonoBody, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Divider()
        SettingRow {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.bypass_lan), style = DsType.Body)
                Text(stringResource(R.string.bypass_lan_short), style = DsType.Secondary)
            }
            DsSwitch(p.bypassLan, onLan)
        }
        Divider()
        SettingRow(onClick = onApps) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.apps_heading), style = DsType.Body)
                Text(
                    when {
                        p.appMode == AppMode.ONLY -> stringResource(R.string.apps_only_n, p.apps.size)
                        p.apps.isEmpty() -> stringResource(R.string.apps_all)
                        else -> stringResource(R.string.apps_all_except_n, p.apps.size)
                    },
                    style = DsType.Secondary,
                )
            }
            Icon(Icons.Default.ChevronRight, null, tint = Ds.Muted)
        }
    }
}

@Composable
private fun SettingRow(onClick: (() -> Unit)? = null, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Ds.hairline(0.06f)))

@Composable
private fun DnsDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dns)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DsTextField(v, { v = it.trim() }, style = DsType.MonoBody, height = 48.dp, keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri))
                Text(stringResource(R.string.dns_explainer), style = DsType.Secondary)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(v) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun TestChain(vm: MainViewModel, draft: Profile) {
    val probe by vm.probe.collectAsStateWithLifecycle()
    val running = probe == Probe.Running
    BonePill(
        stringResource(
            when {
                draft.hops.isEmpty() -> R.string.test_add_hop
                running -> R.string.testing
                else -> R.string.test_chain
            },
        ),
        onClick = { vm.probe(draft) },
        enabled = draft.hops.isNotEmpty() && !running && draft.problem() == null,
        modifier = Modifier.fillMaxWidth(),
    )
    (probe as? Probe.Done)?.result?.let { r ->
        // Legs appear one after another, as they were built.
        var shown by remember(r) { mutableIntStateOf(0) }
        LaunchedEffect(r) {
            while (shown < r.steps.size + 1) {
                delay(180)
                shown++
            }
        }
        Spacer(Modifier.height(12.dp))
        DsCard {
            r.steps.take(shown).forEachIndexed { i, s ->
                Row(Modifier.fillMaxWidth().enter().padding(vertical = 4.dp)) {
                    Text(if (i == 0) "connect" else "leg $i", style = DsType.Mono, modifier = Modifier.width(72.dp))
                    Text("→ ${s.to}", style = DsType.Mono.copy(color = Ds.Bone), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${s.ms} ms", style = DsType.Mono.copy(color = Ds.Lime))
                }
            }
            if (shown > r.steps.size) {
                Spacer(Modifier.height(8.dp))
                r.exitIp?.let { Text(stringResource(R.string.leaves_as, it), style = DsType.Body.copy(color = Ds.Lime), modifier = Modifier.enter()) }
                r.error?.let { Text(it, style = DsType.Secondary.copy(color = Ds.Coral), modifier = Modifier.enter()) }
            }
        }
    }
}
