package io.github.ncwardell.drysocks.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ncwardell.drysocks.MainViewModel
import io.github.ncwardell.drysocks.PoolUpdater
import io.github.ncwardell.drysocks.R
import io.github.ncwardell.drysocks.UpdateProgress
import io.github.ncwardell.drysocks.core.PoolProxy
import io.github.ncwardell.drysocks.core.PoolUpdate
import io.github.ncwardell.drysocks.core.Profile
import io.github.ncwardell.drysocks.core.ProxyPool
import io.github.ncwardell.drysocks.core.ScrapeVia
import io.github.ncwardell.drysocks.core.UpdateMode
import io.github.ncwardell.drysocks.core.parseProxyList
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoolsScreen(vm: MainViewModel, onDone: () -> Unit) {
    val pools by vm.pools.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val progress by PoolUpdater.progress.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<String?>(null) }

    open?.let { id ->
        BackHandler { open = null }
        PoolDetailScreen(vm, id, onDone = { open = null })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pools_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { scope.launch { open = vm.newPool("New pool").id } },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.new_pool_button)) },
            )
        },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(stringResource(R.string.pools_explainer), style = MaterialTheme.typography.bodySmall) }
            if (pools.isEmpty()) item { Text(stringResource(R.string.pools_empty), style = MaterialTheme.typography.bodyMedium) }
            items(pools, key = { it.id }) { p ->
                Card(Modifier.fillMaxWidth().clickable { open = p.id }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.pool_working, p.working, p.proxies.size), style = MaterialTheme.typography.labelLarge)
                            }
                            IconButton(onClick = { vm.setPaused(p.id, !p.paused) }) {
                                Icon(
                                    if (p.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    stringResource(if (p.paused) R.string.pool_resume else R.string.pool_pause),
                                )
                            }
                        }
                        Text(
                            if (p.paused) stringResource(R.string.pool_paused) else modeLine(p) + "  ·  " + viaLabel(p.update.via, profiles),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (p.paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                        val prog = progress[p.id]
                        when {
                            prog != null -> ProgressLine(prog)
                            p.lastError != null -> Text(p.lastError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
                            p.lastUpdated > 0 -> Text(stringResource(R.string.pool_updated, ago(p.lastUpdated)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun modeLine(p: ProxyPool): String = when {
    p.update.mode == UpdateMode.MANUAL -> stringResource(R.string.mode_manual)
    p.update.background -> stringResource(R.string.mode_auto_background, p.update.everyMinutes)
    else -> stringResource(R.string.mode_auto, p.update.everyMinutes)
}

@Composable
private fun ProgressLine(p: UpdateProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (p.of > 0) "${p.step} — ${p.done} of ${p.of}" else p.step + "…", style = MaterialTheme.typography.bodySmall)
        if (p.of > 0) {
            LinearProgressIndicator(progress = { p.done / p.of.toFloat() }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PoolDetailScreen(vm: MainViewModel, id: String, onDone: () -> Unit) {
    val pools by vm.pools.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val progress by PoolUpdater.progress.collectAsStateWithLifecycle()
    val saved = pools.firstOrNull { it.id == id } ?: run { onDone(); return }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // Settings are edited as a draft and saved together; the proxy list below is live.
    var name by remember(id) { mutableStateOf(saved.name) }
    var u by remember(id) { mutableStateOf(saved.update) }
    var countries by remember(id) { mutableStateOf(saved.update.countries.joinToString(", ")) }
    var pasting by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<PoolProxy?>(null) }
    details?.let { px ->
        ReportDialog(saved.proxies.firstOrNull { it.url == px.url } ?: px, onInspect = { vm.inspectPool(id, listOf(px.url)) }, onDismiss = { details = null })
    }

    val draftUpdate = u.copy(countries = countries.split(',', ' ').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet())
    val draft = saved.copy(name = name, update = draftUpdate)
    val dirty = draft.name != saved.name || draft.update != saved.update
    val problem = draft.problem()
    val usedBy = profiles.filter { id in it.poolIds }
    val selfRoute = ScrapeVia.chainId(u.via)?.let { cid -> profiles.firstOrNull { it.id == cid } }?.takeIf { id in it.poolIds }
    val prog = progress[id]

    if (pasting) {
        PasteDialog(onAdd = { urls -> scope.launch { vm.addToPool(urls, id) }; pasting = false }, onDismiss = { pasting = false })
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.pool_delete_title, saved.name)) },
            text = { if (usedBy.isNotEmpty()) Text(stringResource(R.string.pool_delete_used, usedBy.joinToString { it.name })) },
            confirmButton = { TextButton(onClick = { vm.deletePool(id); onDone() }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(saved.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                    TextButton(onClick = { vm.savePoolSettings(draft) }, enabled = dirty && problem == null) { Text(stringResource(R.string.save)) }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            item {
                Text(stringResource(R.string.pool_updates), style = MaterialTheme.typography.titleSmall)
                listOf(UpdateMode.MANUAL, UpdateMode.AUTO).forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = u.mode == m, onClick = { u = u.copy(mode = m) })
                        Column {
                            Text(stringResource(if (m == UpdateMode.MANUAL) R.string.mode_manual else R.string.mode_auto_label))
                            Text(
                                stringResource(if (m == UpdateMode.MANUAL) R.string.mode_manual_explainer else R.string.mode_auto_explainer),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            if (u.mode == UpdateMode.AUTO) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NumberField(u.everyMinutes, stringResource(R.string.pool_every), "min") { u = u.copy(everyMinutes = it) }
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.pool_background))
                            Text(stringResource(R.string.pool_background_explainer), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = u.background, onCheckedChange = { u = u.copy(background = it) })
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(u.target, stringResource(R.string.pool_target), null) { u = u.copy(target = it) }
                    NumberField(u.pruneAfter, stringResource(R.string.pool_prune), null) { u = u.copy(pruneAfter = it) }
                }
            }
            item {
                ViaPicker(u.via, profiles, enabled = true, onPick = { u = u.copy(via = it) })
                if (selfRoute != null) {
                    Text(stringResource(R.string.pool_self_route, selfRoute.name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            item {
                Text(stringResource(R.string.pool_protocols), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("socks5", "socks4a", "http").forEach { proto ->
                        FilterChip(
                            selected = proto in u.protocols,
                            onClick = { u = u.copy(protocols = if (proto in u.protocols) u.protocols - proto else u.protocols + proto) },
                            label = { Text(proto) },
                        )
                    }
                }
                OutlinedTextField(
                    countries, { countries = it },
                    label = { Text(stringResource(R.string.pool_countries)) },
                    placeholder = { Text("US, DE, NL") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            item { problem?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
            item {
                if (dirty) Text(stringResource(R.string.pool_unsaved), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (prog != null) {
                        FilledTonalButton(onClick = { vm.cancelPoolUpdate(id) }) { Text(stringResource(R.string.cancel)) }
                    } else {
                        Button(onClick = { vm.updatePool(id) }, enabled = !dirty) { Text(stringResource(R.string.pool_update_now)) }
                        OutlinedButton(onClick = { vm.updatePool(id, scrape = false) }, enabled = !dirty && saved.proxies.isNotEmpty()) {
                            Text(stringResource(R.string.pool_check_only))
                        }
                        OutlinedButton(onClick = { vm.inspectPool(id) }, enabled = !dirty && saved.proxies.isNotEmpty()) {
                            Text(stringResource(R.string.pool_inspect))
                        }
                    }
                    OutlinedButton(onClick = { pasting = true }) { Text(stringResource(R.string.pool_paste)) }
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(saved.proxies.joinToString("\n") { it.url })) },
                        enabled = saved.proxies.isNotEmpty(),
                    ) { Text(stringResource(R.string.pool_copy)) }
                    OutlinedButton(onClick = { vm.removeDead(id) }, enabled = saved.proxies.any { !it.working && !it.unchecked }) {
                        Text(stringResource(R.string.pool_remove_dead))
                    }
                }
            }
            item {
                prog?.let { ProgressLine(it) }
                saved.lastError?.takeIf { prog == null }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (usedBy.isNotEmpty()) {
                    Text(stringResource(R.string.pool_used_by, usedBy.joinToString { it.name }), style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                Text(
                    stringResource(R.string.pool_list_heading, saved.working, saved.proxies.size),
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(saved.proxies.sortedWith(compareByDescending<PoolProxy> { it.working }.thenBy { it.unchecked }.thenBy { it.ms }), key = { it.url }) { px ->
                ProxyRow(px, u, onRemove = { vm.removeProxy(id, px.url) }, onOpen = { details = px })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun ProxyRow(px: PoolProxy, u: PoolUpdate, onRemove: () -> Unit, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(px.url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    px.working -> buildString {
                        append("${px.ms} ms")
                        px.country?.let { append("  ·  $it") }
                        px.exitIp?.takeIf { it != px.url.substringAfter("://").substringBeforeLast(':') }?.let { append("  ·  exits as $it") }
                        append("  ·  ").append(ago(px.lastOk))
                    }
                    px.unchecked -> stringResource(R.string.proxy_unchecked)
                    else -> stringResource(R.string.proxy_failing, px.fails, u.pruneAfter) +
                        (if (px.lastOk > 0) "  ·  " + stringResource(R.string.proxy_last_worked, ago(px.lastOk)) else "")
                },
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    px.working -> MaterialTheme.colorScheme.onSurface
                    px.unchecked -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.error
                },
            )
            px.report?.let { r ->
                val warn = listOfNotNull(
                    "tampers".takeIf { r.tampers },
                    "needs login".takeIf { r.needsAuth },
                    "flagged".takeIf { r.flagged },
                )
                Text(
                    buildList {
                        if (r.protocols.isNotEmpty()) add(r.protocols.joinToString("·"))
                        add(if (r.anyPort) "any port" else if (r.web443 || r.web80) "web ports only" else "no tunnels")
                        if (r.tor) add("Tor ✓")
                        if (r.network != "unknown") add(r.network)
                        if (r.kbps > 0) add("${r.kbps} KB/s")
                    }.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (warn.isNotEmpty()) {
                    Text("⚠ " + warn.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        IconButton(onClick = onRemove) { Icon(Icons.Default.Close, stringResource(R.string.remove)) }
    }
}

@Composable
private fun ReportDialog(px: PoolProxy, onInspect: () -> Unit, onDismiss: () -> Unit) {
    val r = px.report
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(px.url, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (r == null) {
                    Text(stringResource(R.string.report_none))
                } else {
                    @Composable
                    fun line(label: String, value: String, bad: Boolean = false) = Row {
                        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(120.dp))
                        Text(value, style = MaterialTheme.typography.bodySmall, color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                    fun yes(b: Boolean) = if (b) "yes" else "no"
                    line("Speaks", r.protocols.joinToString(", ").ifEmpty { "nothing it would admit to" })
                    line("Needs a login", yes(r.needsAuth), r.needsAuth)
                    line("Port 443", yes(r.web443), !r.web443)
                    line("Port 80", yes(r.web80))
                    line("Any port", yes(r.anyPort))
                    line("Tor", if (r.tor) "gets through" else "blocked or failed")
                    line("Tampering", if (r.tampers) "adds " + r.addedHeaders.joinToString() else if (r.web80) "none seen" else "not tested", r.tampers)
                    line("Exits as", listOfNotNull(r.exitIp, r.country).joinToString("  ").ifEmpty { "—" })
                    line("Network", listOfNotNull(r.network, r.isp).joinToString(" — "))
                    line("Listed as proxy", yes(r.flagged), r.flagged)
                    line("Speed", if (r.kbps > 0) "${r.kbps} KB/s" else "—")
                    line("Inspected", ago(px.inspectedAt))
                    if (r.errors.isNotEmpty()) {
                        Text(stringResource(R.string.report_errors), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                        r.errors.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onInspect(); onDismiss() }) { Text(stringResource(R.string.pool_inspect_one)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun NumberField(value: Int, label: String, suffix: String?, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = if (value == 0) "" else value.toString(),
        onValueChange = { s -> onChange(s.filter(Char::isDigit).take(4).toIntOrNull() ?: 0) },
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(150.dp),
    )
}

@Composable
private fun PasteDialog(onAdd: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val parsed = remember(text) { parseProxyList(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pool_paste)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pool_paste_help), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    text, { text = it },
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Text(stringResource(R.string.pool_paste_read, parsed.size), style = MaterialTheme.typography.labelMedium)
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(parsed) }, enabled = parsed.isNotEmpty()) { Text(stringResource(R.string.add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun ago(t: Long): String {
    val s = (System.currentTimeMillis() - t) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> "${s / 86_400} d ago"
    }
}
