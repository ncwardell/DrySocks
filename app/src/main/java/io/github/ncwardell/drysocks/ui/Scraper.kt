package io.github.ncwardell.drysocks.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ncwardell.drysocks.MainViewModel
import io.github.ncwardell.drysocks.R
import io.github.ncwardell.drysocks.ScrapePhase
import io.github.ncwardell.drysocks.TorController
import io.github.ncwardell.drysocks.TorStatus
import io.github.ncwardell.drysocks.core.Profile
import io.github.ncwardell.drysocks.core.ScrapeResult
import io.github.ncwardell.drysocks.core.ScrapeSource
import io.github.ncwardell.drysocks.core.ScrapeVia
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScraperScreen(vm: MainViewModel, onDone: () -> Unit) {
    val state by vm.scrape.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val tor by TorController.status.collectAsStateWithLifecycle()

    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    // The chain in use is the one known to reach the internet here; failing that, Tor.
    var via by rememberSaveable { mutableStateOf<String?>(null) }
    val viaNow = via ?: (selectedId ?: profiles.firstOrNull()?.id)?.let { ScrapeVia.chain(it) } ?: ScrapeVia.TOR
    var addAllMenu by remember { mutableStateOf(false) }
    var want by rememberSaveable { mutableFloatStateOf(20f) }
    var editingSources by remember { mutableStateOf(false) }

    if (editingSources) {
        SourcesDialog(sources, onSave = { vm.saveSources(it); editingSources = false }, onDismiss = { editingSources = false })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scraper_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Text(
                        stringResource(R.string.scraper_warning),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ViaPicker(viaNow, profiles, enabled = !state.running, onPick = { via = it })
                    Text(stringResource(R.string.scraper_want, want.roundToInt()))
                    Slider(value = want, onValueChange = { want = it }, valueRange = 5f..100f, steps = 18, enabled = !state.running)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.running) {
                            FilledTonalButton(onClick = vm::cancelScrape) { Text(stringResource(R.string.cancel)) }
                        } else {
                            Button(
                                onClick = { vm.startScrape(want.roundToInt(), viaNow) },
                                enabled = sources.any { it.enabled },
                            ) { Text(stringResource(R.string.scraper_start)) }
                        }
                        OutlinedButton(onClick = { editingSources = true }, enabled = !state.running) {
                            Text(stringResource(R.string.scraper_sources, sources.count { it.enabled }, sources.size))
                        }
                    }
                }
            }

            item { ScrapeProgress(state.phase, state.tested, state.total, state.found.size, tor, state.via != ScrapeVia.DIRECT, state.error) }

            if (state.found.isNotEmpty()) {
                item {
                    Box {
                        OutlinedButton(onClick = { addAllMenu = true }) {
                            Text(stringResource(R.string.scraper_add_all_pool, state.found.size))
                        }
                        DropdownMenu(expanded = addAllMenu, onDismissRequest = { addAllMenu = false }) {
                            val pools by vm.pools.collectAsStateWithLifecycle()
                            pools.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.scraper_to_pool, p.name)) },
                                    onClick = { addAllMenu = false; vm.addResultsToPool(state.found, p.id) },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.scraper_new_pool)) },
                                onClick = { addAllMenu = false; vm.addResultsToPool(state.found, null) },
                            )
                        }
                    }
                }
            }

            items(state.found, key = { it.url }) { r -> ResultRow(r, profiles, onAdd = { p -> vm.addHop(r.hop!!, p) }) }
        }
    }
}

/** Direct, Tor, or any saved chain. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ViaPicker(via: String, profiles: List<Profile>, enabled: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = viaLabel(via, profiles)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (enabled) open = it }) {
            OutlinedTextField(
                value = label, onValueChange = {}, readOnly = true, singleLine = true, enabled = enabled,
                label = { Text(stringResource(R.string.scraper_via)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                val options = listOf(ScrapeVia.DIRECT, ScrapeVia.TOR) + profiles.map { ScrapeVia.chain(it.id) }
                options.forEach { o ->
                    DropdownMenuItem(text = { Text(viaLabel(o, profiles)) }, onClick = { open = false; onPick(o) })
                }
            }
        }
        Text(
            stringResource(if (via == ScrapeVia.DIRECT) R.string.scraper_via_direct_explainer else R.string.scraper_via_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = if (via == ScrapeVia.DIRECT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun viaLabel(via: String, profiles: List<Profile>): String = when (via) {
    ScrapeVia.DIRECT -> stringResource(R.string.via_direct)
    ScrapeVia.TOR -> stringResource(R.string.via_tor)
    else -> profiles.firstOrNull { it.id == ScrapeVia.chainId(via) }?.let { stringResource(R.string.via_chain, it.name) }
        ?: stringResource(R.string.via_missing)
}

@Composable
private fun ScrapeProgress(
    phase: ScrapePhase,
    tested: Int,
    total: Int,
    found: Int,
    tor: TorStatus,
    viaTor: Boolean, // any Tor involvement shows its progress while preparing
    error: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (phase) {
            ScrapePhase.Idle -> Unit
            ScrapePhase.Preparing -> {
                val t = tor as? TorStatus.Starting
                Text(
                    if (viaTor && t != null) stringResource(R.string.tor_starting, t.percent, t.summary)
                    else stringResource(R.string.scraper_preparing),
                )
                LinearProgressIndicator(progress = { (t?.percent ?: 0) / 100f }, modifier = Modifier.fillMaxWidth())
            }
            ScrapePhase.Fetching -> {
                Text(stringResource(R.string.scraper_fetching))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            ScrapePhase.Testing, ScrapePhase.Done -> {
                Text(stringResource(R.string.scraper_progress, tested, total, found))
                if (phase == ScrapePhase.Testing) {
                    LinearProgressIndicator(
                        progress = { if (total == 0) 0f else tested / total.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ResultRow(r: ScrapeResult, profiles: List<Profile>, onAdd: (Profile?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append("${r.ms} ms")
                        r.country?.let { append("  ·  ${flag(it)} $it") }
                        if (r.exitsElsewhere) append("  ·  exits as ${r.exitIp}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Box {
                IconButton(onClick = { menu = true }, enabled = !added) {
                    Icon(Icons.Default.Add, stringResource(R.string.scraper_add))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.scraper_new_chain)) },
                        onClick = { menu = false; added = true; onAdd(null) },
                    )
                    profiles.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.scraper_append_to, p.name)) },
                            onClick = { menu = false; added = true; onAdd(p) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcesDialog(initial: List<ScrapeSource>, onSave: (List<ScrapeSource>) -> Unit, onDismiss: () -> Unit) {
    var list by remember { mutableStateOf(initial) }
    var url by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("socks5") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scraper_sources_title)) },
        text = {
            LazyColumn(Modifier.fillMaxSize(0.9f)) {
                items(list, key = { it.url }) { src ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = src.enabled, onCheckedChange = { on ->
                            list = list.map { if (it.url == src.url) it.copy(enabled = on) else it }
                        })
                        Column(Modifier.weight(1f)) {
                            Text(src.url.removePrefix("https://").removePrefix("raw.githubusercontent.com/"),
                                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(src.kind, style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { list = list.filter { it.url != src.url } }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.remove))
                        }
                    }
                }
                item {
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(url, { url = it.trim() }, label = { Text(stringResource(R.string.scraper_source_url)) }, singleLine = true)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            listOf("socks5", "socks4a", "http").forEach { k ->
                                TextButton(onClick = { kind = k }) {
                                    Text(if (k == kind) "[$k]" else k)
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = { list = list + ScrapeSource(url, kind); url = "" },
                            enabled = url.startsWith("http") && list.none { it.url == url },
                        ) { Text(stringResource(R.string.scraper_add_source)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(list) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Regional-indicator flag for a two-letter country code. */
private fun flag(cc: String): String =
    if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) "" else cc.map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }.joinToString("")
