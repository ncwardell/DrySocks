package io.github.ncwardell.drysocks.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
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

/** Something the user may want to block: a name or an address. */
private data class BlockTarget(val title: String, val detail: String, val host: String?, val ip: String?)

@Composable
fun NetworkScreen(vm: MainViewModel) {
    val view by vm.network.collectAsStateWithLifecycle()
    val rules by vm.blockRules.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    var target by remember { mutableStateOf<BlockTarget?>(null) }
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val blockedMsg = stringResource(R.string.toast_blocked)
    val unblockedMsg = stringResource(R.string.toast_unblocked)

    target?.let { t -> BlockDialog(vm, t, rules, onDismiss = { target = null }) }
    val copy = ListCopy(
        notRunning = stringResource(R.string.network_not_running),
        tapHint = stringResource(R.string.connections_tap_hint),
        none = stringResource(R.string.no_connections),
        noLookups = stringResource(R.string.no_lookups),
    )

    // Tap toggles a block on the connection's host (its address if DNS never named it).
    fun toggle(c: NetConn) {
        val rule = c.host ?: c.ip
        scope.launch {
            if (rule in rules) {
                vm.unblock(rule)
                toast.show(unblockedMsg.format(rule))
            } else if (vm.block(rule) != null) {
                toast.show(blockedMsg.format(rule))
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text(stringResource(R.string.network_title), style = DsType.ScreenTitle) }
        item { ThroughputCard(view) }
        item {
            DsSegmented(
                listOf(
                    stringResource(R.string.tab_connections), stringResource(R.string.tab_dns),
                    stringResource(R.string.tab_blocklist), stringResource(R.string.tab_graph),
                ),
                selected = tab, onSelect = { tab = it },
            )
        }
        when (tab) {
            0 -> connections(view, rules, copy, onTap = ::toggle, onLong = { c -> target = targetFor(c, view.owners[c.id]) })
            1 -> lookups(view, copy, onTap = { l -> target = BlockTarget(l.name, l.answers.joinToString(", "), l.name, null) })
            2 -> item { Blocklist(vm, rules) }
            3 -> item {
                if (!view.running) {
                    DashedNote(stringResource(R.string.network_not_running))
                } else {
                    FlowGraph(view) { host, ip -> target = BlockTarget(host ?: ip ?: "?", "", host, ip) }
                }
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

/**
 * Download over the last minute as bars, each lime at an opacity scaled to its value;
 * a single series, so the card's heading names it and no legend is needed.
 */
@Composable
private fun ThroughputCard(view: NetView) {
    val s = view.snapshot
    val bars = 28
    val down = view.downRate.takeLast(bars)
    val peak = max(1L, down.maxOrNull() ?: 0L)
    DsCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.down_last_60), style = DsType.Secondary)
                Text(
                    if (view.running) rate(down.lastOrNull()) else "—",
                    style = DsType.MonoStat.copy(fontSize = 26.sp),
                )
            }
            if (view.running) Text("↑ ${bytes(s.up)}  ↓ ${bytes(s.down)}", style = DsType.Mono)
        }
        Spacer(Modifier.height(12.dp))
        Canvas(Modifier.fillMaxWidth().height(64.dp)) {
            val gap = 3.dp.toPx()
            val w = (size.width - gap * (bars - 1)) / bars
            for (i in 0 until bars) {
                // Right-aligned: the newest second is the last bar.
                val v = down.getOrNull(i - (bars - down.size)) ?: 0L
                val frac = if (view.running) v.toFloat() / peak else 0f
                val h = max(3.dp.toPx(), size.height * (0.12f + 0.88f * frac))
                val color = if (view.running) Ds.Lime.copy(alpha = 0.35f + 0.65f * frac) else Ds.Track
                drawRoundRect(color, Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(2.dp.toPx()))
            }
        }
        Spacer(Modifier.height(12.dp))
        Row {
            NetStat(s.active.size.toString(), stringResource(R.string.stat_open), Ds.Bone, Modifier.weight(1f))
            NetStat(s.blocked.toString(), stringResource(R.string.stat_blocked), Ds.Coral, Modifier.weight(1f))
            NetStat(s.udpDropped.toString(), stringResource(R.string.stat_udp), Ds.Bone, Modifier.weight(1f))
        }
    }
}

@Composable
private fun NetStat(value: String, label: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = DsType.MonoStat.copy(color = color))
        Text(label, style = DsType.Secondary)
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.connections(view: NetView, rules: List<String>, copy: ListCopy, onTap: (NetConn) -> Unit, onLong: (NetConn) -> Unit) {
    if (!view.running) {
        item { DashedNote(copy.notRunning) }
        return
    }
    val rows = view.snapshot.active + view.snapshot.recent
    item { Text(copy.tapHint, style = DsType.Secondary) }
    if (rows.isEmpty()) item { DashedNote(copy.none) }
    items(rows, key = { it.id }) { c ->
        val app = view.owners[c.id]?.label
        val struck = (c.host ?: c.ip) in rules
        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp))
                .combinedClickable(onClick = { onTap(c) }, onLongClick = { onLong(c) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(Ds.Graphite), contentAlignment = Alignment.Center) {
                Text((app ?: c.label).take(1).uppercase(), style = DsType.Label.copy(fontSize = 14.sp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = DsType.Body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, textDecoration = if (struck) TextDecoration.LineThrough else null),
                )
                Text("${app ?: "—"} · ${bytes(c.up + c.down)}", style = DsType.Mono, maxLines = 1)
            }
            val (state, color) = when {
                c.blocked -> "blocked" to Ds.Coral
                c.error != null -> "failed" to Ds.Coral
                c.active -> "open" to Ds.Lime
                else -> "closed" to Ds.Dim
            }
            Text(state, style = DsType.Mono.copy(color = color))
        }
    }
}

private fun LazyListScope.lookups(view: NetView, copy: ListCopy, onTap: (NetLookup) -> Unit) {
    if (!view.running) {
        item { DashedNote(copy.notRunning) }
        return
    }
    if (view.snapshot.dns.isEmpty()) item { DashedNote(copy.noLookups) }
    items(view.snapshot.dns) { l ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onTap(l) }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(l.name, style = DsType.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        l.blocked -> "NXDOMAIN · blocked"
                        l.error != null -> l.error!!
                        else -> l.answers.firstOrNull { !it.startsWith("→") } ?: l.answers.firstOrNull() ?: "—"
                    },
                    style = DsType.Mono.copy(color = if (l.blocked || l.error != null) Ds.Coral else Ds.Muted),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(l.type, style = DsType.Mono.copy(color = Ds.Dim))
        }
    }
}

/** Copy for the list builders below, which are not composable and cannot read resources. */
private data class ListCopy(val notRunning: String, val tapHint: String, val none: String, val noLookups: String)

@Composable
private fun Blocklist(vm: MainViewModel, rules: List<String>) {
    val scope = rememberCoroutineScope()
    var entry by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DsTextField(entry, { entry = it; invalid = false }, Modifier.weight(1f), placeholder = stringResource(R.string.blocklist_entry), style = DsType.MonoBody, height = 48.dp)
            Box(
                Modifier.height(48.dp).clip(CircleShape).background(if (entry.isBlank()) Ds.Graphite else Ds.Coral)
                    .clickable(enabled = entry.isNotBlank()) { scope.launch { if (vm.block(entry) == null) invalid = true else entry = "" } }
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.block), style = DsType.Button.copy(color = if (entry.isBlank()) Ds.Disabled else Ds.Ink)) }
        }
        if (invalid) Text(stringResource(R.string.blocklist_invalid), style = DsType.Secondary.copy(color = Ds.Coral))
        Text(stringResource(R.string.blocklist_explainer), style = DsType.Secondary)
        if (rules.isEmpty()) Text(stringResource(R.string.blocklist_empty), style = DsType.BodyMuted)
        rules.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r, style = DsType.MonoBody)
                    Text(
                        stringResource(when (BlockRules.kind(r)) {
                            BlockRules.Kind.DOMAIN -> R.string.rule_domain
                            BlockRules.Kind.ADDRESS -> R.string.rule_address
                            BlockRules.Kind.RANGE -> R.string.rule_range
                        }),
                        style = DsType.Secondary,
                    )
                }
                Box(Modifier.size(40.dp).clip(CircleShape).clickable { vm.unblock(r) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, stringResource(R.string.remove), tint = Ds.Muted, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

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
