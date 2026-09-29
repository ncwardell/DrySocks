package io.github.ncwardell.drysocks.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ncwardell.drysocks.AppRef
import io.github.ncwardell.drysocks.MainViewModel
import io.github.ncwardell.drysocks.NetView
import io.github.ncwardell.drysocks.R
import io.github.ncwardell.drysocks.core.BlockRules
import io.github.ncwardell.drysocks.core.NetConn
import io.github.ncwardell.drysocks.core.NetLookup
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Series colours, from the validated reference palette (categorical slots 1 and 2):
 * both pass lightness, chroma, CVD separation (ΔE 24.7 light / 26.8 dark) and 3:1
 * contrast against their surface. Identity never rests on colour alone: the legend
 * names both, with arrows.
 */
private data class SeriesColors(val up: Color, val down: Color)

@Composable
private fun seriesColors() = if (isSystemInDarkTheme()) {
    SeriesColors(up = Color(0xFF3987E5), down = Color(0xFFD95926))
} else {
    SeriesColors(up = Color(0xFF2A78D6), down = Color(0xFFEB6834))
}

/** Something the user may want to block: a name or an address. */
private data class BlockTarget(val title: String, val detail: String, val host: String?, val ip: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(vm: MainViewModel, onDone: () -> Unit) {
    val view by vm.network.collectAsStateWithLifecycle()
    val rules by vm.blockRules.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    var target by remember { mutableStateOf<BlockTarget?>(null) }

    target?.let { t -> BlockDialog(vm, t, rules, onDismiss = { target = null }) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.network_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!view.running) {
                Text(
                    stringResource(R.string.network_not_running),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                Throughput(view, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                Counters(view, Modifier.padding(horizontal = 16.dp))
            }
            PrimaryTabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 8.dp)) {
                listOf(R.string.tab_connections, R.string.tab_graph, R.string.tab_dns, R.string.tab_blocklist)
                    .forEachIndexed { i, label -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(label)) }) }
            }
            when (tab) {
                0 -> ConnectionList(view) { c -> target = targetFor(c, view.owners[c.id]) }
                1 -> FlowGraph(view) { host, ip -> target = BlockTarget(host ?: ip ?: "?", "", host, ip) }
                2 -> LookupList(view.snapshot.dns) { l ->
                    target = BlockTarget(l.name, l.answers.joinToString(", "), l.name, null)
                }
                3 -> Blocklist(vm, rules)
            }
        }
    }
}

private fun targetFor(c: NetConn, owner: AppRef?): BlockTarget = BlockTarget(
    title = c.label,
    detail = buildString {
        append(owner?.label ?: "Unknown app")
        append(" → ").append(c.dst)
        append("\n↑ ").append(bytes(c.up)).append("   ↓ ").append(bytes(c.down))
        c.error?.let { append("\n").append(it) }
    },
    host = c.host,
    ip = c.ip,
)

// --- Throughput --------------------------------------------------------------------

@Composable
private fun Throughput(view: NetView, modifier: Modifier = Modifier) {
    val colors = seriesColors()
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val measurer = rememberTextMeasurer()
    var scrub by remember { mutableStateOf<Int?>(null) }

    val up = view.upRate
    val down = view.downRate
    val n = max(up.size, down.size)
    val peak = max(1L, max(up.maxOrNull() ?: 0, down.maxOrNull() ?: 0))

    Column(modifier) {
        // Legend, with the live values; text stays in text colours, the swatch carries identity.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val i = scrub
            Legend(colors.up, "↑ " + stringResource(R.string.upload), rate(if (i != null) up.getOrNull(i) else up.lastOrNull()))
            Legend(colors.down, "↓ " + stringResource(R.string.download), rate(if (i != null) down.getOrNull(i) else down.lastOrNull()))
            Spacer(Modifier.weight(1f))
            Text(
                if (i != null) "−${n - 1 - i}s" else stringResource(R.string.last_minute),
                style = MaterialTheme.typography.labelSmall, color = muted,
            )
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .padding(top = 6.dp)
                .pointerInput(n) {
                    fun at(x: Float) = if (n < 2) null else ((x / size.width) * (NW - 1)).toInt()
                        .let { it - (NW - n) }.coerceIn(0, n - 1)
                    detectDragGestures(
                        onDragStart = { scrub = at(it.x) },
                        onDragEnd = { scrub = null },
                        onDragCancel = { scrub = null },
                    ) { change, _ -> scrub = at(change.position.x) }
                }
                .pointerInput(n) {
                    detectTapGestures(onPress = {
                        scrub = if (n < 2) null else ((it.x / size.width) * (NW - 1)).toInt().let { v -> v - (NW - n) }.coerceIn(0, n - 1)
                        tryAwaitRelease()
                        scrub = null
                    })
                },
        ) {
            val w = size.width
            val h = size.height
            val step = w / (NW - 1)
            fun x(i: Int) = (NW - n + i) * step
            fun y(v: Long) = h - (v.toFloat() / peak) * (h - 4.dp.toPx())

            // Recessive frame: a baseline and the peak, labelled once.
            drawLine(grid, Offset(0f, h), Offset(w, h), 1.dp.toPx())
            drawLine(grid, Offset(0f, y(peak)), Offset(w, y(peak)), 1.dp.toPx(), pathEffect = null, alpha = 0.5f)
            drawText(measurer, rate(peak), Offset(0f, y(peak) + 2.dp.toPx()), TextStyle(color = muted, fontSize = 10.sp))

            for ((series, color) in listOf(up to colors.up, down to colors.down)) {
                if (series.size < 2) continue
                val path = Path().apply {
                    moveTo(x(0), y(series[0]))
                    for (i in 1 until series.size) lineTo(x(i), y(series[i]))
                }
                drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }

            scrub?.let { i ->
                drawLine(ink.copy(alpha = 0.4f), Offset(x(i), 0f), Offset(x(i), h), 1.dp.toPx())
                for ((series, color) in listOf(up to colors.up, down to colors.down)) {
                    series.getOrNull(i)?.let { v -> drawCircle(color, 4.dp.toPx(), Offset(x(i), y(v))) }
                }
            }
        }
    }
}

private const val NW = io.github.ncwardell.drysocks.NetworkWatcher.WINDOW

@Composable
private fun Legend(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 12.dp, height = 3.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(6.dp))
        Text("$label  ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Counters(view: NetView, modifier: Modifier = Modifier) {
    val s = view.snapshot
    Row(modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Stat(s.active.size.toString(), stringResource(R.string.stat_open))
        Stat(s.blocked.toString(), stringResource(R.string.stat_blocked))
        Stat(s.udpDropped.toString(), stringResource(R.string.stat_udp))
        Stat("${bytes(s.up)} / ${bytes(s.down)}", stringResource(R.string.stat_total))
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleSmall)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- Connections -------------------------------------------------------------------

@Composable
private fun ConnectionList(view: NetView, onTap: (NetConn) -> Unit) {
    val rows = view.snapshot.active + view.snapshot.recent
    if (rows.isEmpty()) {
        Empty(R.string.no_connections)
        return
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
        items(rows, key = { it.id }) { c ->
            Row(
                Modifier.fillMaxWidth().clickable { onTap(c) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(c.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (view.owners[c.id]?.label ?: "—") + "  ·  " + c.dst.substringAfterLast(':').let { "port $it" },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    when {
                        c.blocked -> BlockedBadge()
                        c.error != null -> Text(stringResource(R.string.conn_failed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        c.active -> Text(stringResource(R.string.conn_open), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        else -> Text(stringResource(R.string.conn_closed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!c.blocked) {
                        Text("↑${bytes(c.up)} ↓${bytes(c.down)}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun BlockedBadge() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Block, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.conn_blocked), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    }
}

// --- Graph -------------------------------------------------------------------------

private data class Edge(val app: String, val host: String, val ip: String?, val hostName: String?, val bytes: Long, val blocked: Boolean)

/**
 * Apps on the left, where they connect on the right, one line per pair, its weight the
 * traffic between them. Tapping a destination offers to block it.
 */
@Composable
private fun FlowGraph(view: NetView, onHost: (host: String?, ip: String?) -> Unit) {
    val all = (view.snapshot.active + view.snapshot.recent)
    if (all.isEmpty()) {
        Empty(R.string.no_connections)
        return
    }
    val other = stringResource(R.string.graph_other)
    val unknownApp = stringResource(R.string.graph_unknown_app)

    val raw = all.groupBy { (view.owners[it.id]?.label ?: unknownApp) to it.label }.map { (k, cs) ->
        Edge(k.first, k.second, cs.first().ip, cs.first().host, cs.sumOf { it.up + it.down }, cs.all { it.blocked })
    }
    // At most 6 apps and 10 destinations drawn; the rest fold into "Other", never new colours.
    val topApps = raw.groupBy { it.app }.mapValues { e -> e.value.sumOf { it.bytes } }.entries
        .sortedByDescending { it.value }.take(6).map { it.key }.toSet()
    val topHosts = raw.groupBy { it.host }.mapValues { e -> e.value.sumOf { it.bytes } + e.value.size }.entries
        .sortedByDescending { it.value }.take(10).map { it.key }.toSet()
    val edges = raw.map {
        it.copy(app = if (it.app in topApps) it.app else other, host = if (it.host in topHosts) it.host else other)
    }.groupBy { it.app to it.host }.map { (k, es) ->
        Edge(k.first, k.second, es.first().ip, es.first().hostName, es.sumOf { it.bytes }, es.all { it.blocked })
    }
    val apps = edges.map { it.app }.distinct().sortedByDescending { a -> edges.filter { it.app == a }.sumOf { it.bytes } }
    val hosts = edges.map { it.host }.distinct().sortedByDescending { h -> edges.filter { it.host == h }.sumOf { it.bytes } }
    val maxBytes = max(1L, edges.maxOf { it.bytes })

    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val line = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    val measurer = rememberTextMeasurer()
    val rowH = 36.dp

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.graph_explainer), style = MaterialTheme.typography.bodySmall, color = muted)
        Spacer(Modifier.height(8.dp))
        val rows = max(apps.size, hosts.size)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(rowH * rows)
                .pointerInput(hosts) {
                    detectTapGestures { p ->
                        if (p.x < size.width * 0.55f) return@detectTapGestures
                        val i = (p.y / (rowH.toPx() * rows / hosts.size)).toInt()
                        hosts.getOrNull(i)?.takeIf { it != other }?.let { h ->
                            val e = edges.first { it.host == h }
                            onHost(e.hostName, e.ip)
                        }
                    }
                },
        ) {
            val leftX = size.width * 0.38f
            val rightX = size.width * 0.58f
            fun appY(i: Int) = (i + 0.5f) * size.height / apps.size
            fun hostY(i: Int) = (i + 0.5f) * size.height / hosts.size
            val style = TextStyle(color = ink, fontSize = 12.sp)

            for (e in edges) {
                val a = appY(apps.indexOf(e.app))
                val b = hostY(hosts.indexOf(e.host))
                val w = 1.dp.toPx() + 5.dp.toPx() * sqrt(e.bytes.toFloat() / maxBytes)
                val path = Path().apply {
                    moveTo(leftX, a)
                    cubicTo((leftX + rightX) / 2, a, (leftX + rightX) / 2, b, rightX, b)
                }
                drawPath(path, if (e.blocked) error else line.copy(alpha = 0.6f), style = Stroke(w, cap = StrokeCap.Round))
            }
            apps.forEachIndexed { i, a ->
                drawCircle(ink, 4.dp.toPx(), Offset(leftX, appY(i)))
                val t = measurer.measure(a, style, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    constraints = androidx.compose.ui.unit.Constraints(maxWidth = (leftX - 8.dp.toPx()).toInt()))
                drawText(t, topLeft = Offset(leftX - 8.dp.toPx() - t.size.width, appY(i) - t.size.height / 2))
            }
            hosts.forEachIndexed { i, h ->
                val blocked = edges.filter { it.host == h }.all { it.blocked }
                drawCircle(if (blocked) error else ink, 4.dp.toPx(), Offset(rightX, hostY(i)))
                val label = if (blocked) "⛔ $h" else h
                val t = measurer.measure(label, style.copy(color = if (blocked) error else ink), maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    constraints = androidx.compose.ui.unit.Constraints(maxWidth = (size.width - rightX - 8.dp.toPx()).toInt()))
                drawText(t, topLeft = Offset(rightX + 8.dp.toPx(), hostY(i) - t.size.height / 2))
            }
        }
    }
}

// --- DNS ---------------------------------------------------------------------------

@Composable
private fun LookupList(lookups: List<NetLookup>, onTap: (NetLookup) -> Unit) {
    if (lookups.isEmpty()) {
        Empty(R.string.no_lookups)
        return
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
        items(lookups) { l ->
            Row(
                Modifier.fillMaxWidth().clickable { onTap(l) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(l.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        l.error ?: l.answers.take(3).joinToString("  ").ifEmpty { "—" },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace,
                    )
                }
                if (l.blocked) BlockedBadge() else Text(l.type, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// --- Blocklist ---------------------------------------------------------------------

@Composable
private fun Blocklist(vm: MainViewModel, rules: List<String>) {
    val scope = rememberCoroutineScope()
    var entry by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(stringResource(R.string.blocklist_explainer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    entry, { entry = it; invalid = false },
                    label = { Text(stringResource(R.string.blocklist_entry)) },
                    isError = invalid,
                    supportingText = if (invalid) { { Text(stringResource(R.string.blocklist_invalid)) } } else null,
                    singleLine = true, modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = {
                    scope.launch { if (vm.block(entry) == null) invalid = true else entry = "" }
                }, enabled = entry.isNotBlank()) { Text(stringResource(R.string.block)) }
            }
        }
        if (rules.isEmpty()) item { Text(stringResource(R.string.blocklist_empty), style = MaterialTheme.typography.bodyMedium) }
        items(rules, key = { it }) { r ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(when (BlockRules.kind(r)) {
                                BlockRules.Kind.DOMAIN -> R.string.rule_domain
                                BlockRules.Kind.ADDRESS -> R.string.rule_address
                                BlockRules.Kind.RANGE -> R.string.rule_range
                            }),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { vm.unblock(r) }) { Icon(Icons.Default.Delete, stringResource(R.string.remove)) }
                }
            }
        }
    }
}

@Composable
private fun BlockDialog(vm: MainViewModel, t: BlockTarget, rules: List<String>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    fun block(rule: String) = scope.launch { vm.block(rule); onDismiss() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (t.detail.isNotEmpty()) Text(t.detail, style = MaterialTheme.typography.bodySmall)
                t.host?.let { h ->
                    // Blocking the registrable parent is usually what a person means; offer both.
                    val parent = h.split('.').takeLast(2).joinToString(".")
                    listOfNotNull(h, parent.takeIf { it != h }).forEach { d ->
                        OutlinedButton(onClick = { block(d) }, enabled = d !in rules, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(if (d == h) R.string.block_name else R.string.block_domain, d))
                        }
                    }
                }
                t.ip?.let { ip ->
                    OutlinedButton(onClick = { block(ip) }, enabled = ip !in rules, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.block_ip, ip))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Empty(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
}

private fun rate(v: Long?): String = if (v == null) "—" else bytes(v) + "/s"

fun bytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
    n < 1024L * 1024 * 1024 -> "%.1f MB".format(n / 1024.0 / 1024)
    else -> "%.2f GB".format(n / 1024.0 / 1024 / 1024)
}
