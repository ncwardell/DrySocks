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
import io.github.ncwardell.drysocks.core.flag
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip

/**
 * The scraper, as a card on the Pools tab: pick a route, scrape and test, add what passed
 * to a pool.
 */
@Composable
fun FindFreeProxies(vm: MainViewModel) {
    val state by vm.scrape.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val pools by vm.pools.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val tor by TorController.status.collectAsStateWithLifecycle()
    // The chain in use is the one known to reach the internet here; failing that, Tor.
    var via by rememberSaveable { mutableStateOf<String?>(null) }
    val viaNow = via ?: (selectedId ?: profiles.firstOrNull()?.id)?.let { ScrapeVia.chain(it) } ?: ScrapeVia.TOR
    var editingSources by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }

    if (editingSources) {
        SourcesDialog(sources, onSave = { vm.saveSources(it); editingSources = false }, onDismiss = { editingSources = false })
    }

    DsCard {
        SectionLabel(stringResource(R.string.scraper_title)) {
            DsLink(stringResource(R.string.scraper_sources, sources.count { it.enabled }, sources.size), onClick = { editingSources = true }, color = Ds.Muted)
        }
        Text(stringResource(R.string.scraper_warning_short), style = DsType.Secondary)
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.scraper_via), style = DsType.Label)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf(ScrapeVia.DIRECT, ScrapeVia.TOR) + profiles.map { ScrapeVia.chain(it.id) }).forEach { o ->
                DsChip(viaLabel(o, profiles), selected = o == viaNow, onClick = { if (!state.running) via = o })
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(if (viaNow == ScrapeVia.DIRECT) R.string.scraper_via_direct_explainer else R.string.scraper_via_explainer),
            style = DsType.Secondary.copy(color = if (viaNow == ScrapeVia.DIRECT) Ds.Coral else Ds.Dim),
        )
        Spacer(Modifier.height(14.dp))
        BonePill(
            stringResource(
                when {
                    state.running -> R.string.stop
                    state.phase == ScrapePhase.Done -> R.string.scrape_again
                    else -> R.string.scraper_start
                },
            ),
            onClick = { if (state.running) vm.cancelScrape() else vm.startScrape(20, viaNow) },
            enabled = sources.any { it.enabled },
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.phase != ScrapePhase.Idle) {
            Spacer(Modifier.height(14.dp))
            when (state.phase) {
                ScrapePhase.Preparing -> Text(
                    (tor as? TorStatus.Starting)?.let { stringResource(R.string.tor_starting, it.percent, it.summary) } ?: stringResource(R.string.scraper_preparing),
                    style = DsType.Secondary,
                )
                ScrapePhase.Fetching -> Text(stringResource(R.string.scraper_fetching), style = DsType.Secondary)
                else -> {
                    Row {
                        Text(stringResource(R.string.tested_x_of_y, state.tested, "%,d".format(state.total)), style = DsType.Mono, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.n_working, state.found.size), style = DsType.Mono.copy(color = Ds.Lime))
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Ds.Track)) {
                        val f = if (state.total == 0) 0f else state.tested / state.total.toFloat()
                        Box(Modifier.fillMaxWidth(if (state.phase == ScrapePhase.Done) 1f else f).height(4.dp).clip(CircleShape).background(Ds.Lime))
                    }
                }
            }
            state.error?.let { Text(it, style = DsType.Secondary.copy(color = Ds.Coral)) }
        }
        state.found.forEach { r ->
            Row(Modifier.fillMaxWidth().enter().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(flag(r.country), fontSize = 18.sp, modifier = Modifier.width(30.dp))
                Spacer(Modifier.width(8.dp))
                Text(r.url.substringAfter("://"), style = DsType.Mono.copy(color = Ds.Bone), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(r.url.substringBefore("://"), style = DsType.Mono.copy(color = Ds.Dim))
                Spacer(Modifier.width(10.dp))
                Text("${r.ms} ms", style = DsType.Mono.copy(color = Ds.Lime))
            }
        }
        if (state.found.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            val target = pools.firstOrNull()
            Box {
                OutlinePill(
                    if (target != null) stringResource(R.string.add_all_to, state.found.size, target.name) else stringResource(R.string.scraper_add_all_pool, state.found.size),
                    color = Ds.Lime, modifier = Modifier.fillMaxWidth(),
                    onClick = { if (pools.size <= 1) vm.addResultsToPool(state.found, target?.id) else addMenu = true },
                )
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    pools.forEach { p ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.scraper_to_pool, p.name)) }, onClick = { addMenu = false; vm.addResultsToPool(state.found, p.id) })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.scraper_new_pool)) }, onClick = { addMenu = false; vm.addResultsToPool(state.found, null) })
                }
            }
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

