package com.sockschain.app.ui

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
import com.sockschain.app.MainViewModel
import com.sockschain.app.R
import com.sockschain.app.ScrapePhase
import com.sockschain.app.TorController
import com.sockschain.app.TorStatus
import com.sockschain.core.Profile
import com.sockschain.core.ScrapeResult
import com.sockschain.core.ScrapeSource
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScraperScreen(vm: MainViewModel, onDone: () -> Unit) {
    val state by vm.scrape.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val tor by TorController.status.collectAsStateWithLifecycle()

    var viaTor by rememberSaveable { mutableStateOf(true) }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.scraper_via_tor))
                            Text(stringResource(R.string.scraper_via_tor_explainer), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = viaTor, onCheckedChange = { viaTor = it }, enabled = !state.running)
                    }
                    Text(stringResource(R.string.scraper_want, want.roundToInt()))
                    Slider(value = want, onValueChange = { want = it }, valueRange = 5f..100f, steps = 18, enabled = !state.running)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.running) {
                            FilledTonalButton(onClick = vm::cancelScrape) { Text(stringResource(R.string.cancel)) }
                        } else {
                            Button(
                                onClick = { vm.startScrape(want.roundToInt(), viaTor) },
                                enabled = sources.any { it.enabled },
                            ) { Text(stringResource(R.string.scraper_start)) }
                        }
                        OutlinedButton(onClick = { editingSources = true }, enabled = !state.running) {
                            Text(stringResource(R.string.scraper_sources, sources.count { it.enabled }, sources.size))
                        }
                    }
                }
            }

            item { ScrapeProgress(state.phase, state.tested, state.total, state.found.size, tor, state.viaTor, state.error) }

            items(state.found, key = { it.url }) { r -> ResultRow(r, profiles, onAdd = { p -> vm.addHop(r.hop!!, p) }) }
        }
    }
}

@Composable
private fun ScrapeProgress(
    phase: ScrapePhase,
    tested: Int,
    total: Int,
    found: Int,
    tor: TorStatus,
    viaTor: Boolean,
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
