package io.github.ncwardell.drysocks.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ncwardell.drysocks.ChainVpnService
import io.github.ncwardell.drysocks.MainViewModel
import io.github.ncwardell.drysocks.Protection
import io.github.ncwardell.drysocks.R
import io.github.ncwardell.drysocks.VpnStatus
import io.github.ncwardell.drysocks.core.AppMode
import io.github.ncwardell.drysocks.core.Hop
import io.github.ncwardell.drysocks.core.HopKind
import io.github.ncwardell.drysocks.core.Profile
import io.github.ncwardell.drysocks.core.ProxyPool
import io.github.ncwardell.drysocks.core.RotationMode

enum class Tab(val label: Int) { Home(R.string.tab_home), Chains(R.string.tab_chains), Network(R.string.tab_network), Pools(R.string.tab_pools) }

/** One screen per job, behind a bottom bar. */
@Composable
fun DrySocksApp(vm: MainViewModel, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val toast = remember { io.github.ncwardell.drysocks.ui.ToastState() }

    CompositionLocalProvider(LocalToast provides toast) {
        Box(Modifier.fillMaxSize().background(Ds.Ink)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).statusBarsPadding()) {
                    if (tab != Tab.Home) BackHandler { tab = Tab.Home }
                    when (tab) {
                        Tab.Home -> HomeScreen(vm, onConnect, onDisconnect, onEditChain = { tab = Tab.Chains })
                        Tab.Chains -> ChainsScreen(vm)
                        Tab.Network -> NetworkScreen(vm)
                        Tab.Pools -> PoolsScreen(vm)
                    }
                }
                BottomBar(tab) { tab = it }
            }
            toast.Host(Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = 84.dp).windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Ds.Ink).windowInsetsPadding(WindowInsets.navigationBars)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ds.hairline(0.06f)))
        Row(Modifier.fillMaxWidth().height(68.dp), verticalAlignment = Alignment.CenterVertically) {
            Tab.entries.forEach { t ->
                val on = t == current
                Column(
                    Modifier.weight(1f).fillMaxSize().clickable { onSelect(t) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(if (on) Ds.Lime else Color.Transparent))
                    Spacer(Modifier.height(5.dp))
                    Text(stringResource(t.label), style = DsType.Label.copy(color = if (on) Ds.Bone else Ds.Dim))
                }
            }
        }
    }
}

// --- Home ------------------------------------------------------------------------------

private enum class OrbState { Off, Connecting, On, Blocked }

@Composable
private fun HomeScreen(vm: MainViewModel, onConnect: () -> Unit, onDisconnect: () -> Unit, onEditChain: () -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val pools by vm.pools.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val status by ChainVpnService.status.collectAsStateWithLifecycle()
    val traffic by ChainVpnService.traffic.collectAsStateWithLifecycle()
    val protection by ChainVpnService.protection.collectAsStateWithLifecycle()
    val exit by vm.exit.collectAsStateWithLifecycle()
    val toast = LocalToast.current
    val switchNote = stringResource(R.string.switch_next_connect)
    val selected = profiles.firstOrNull { it.id == (selectedId ?: profiles.firstOrNull()?.id) }

    val orb = when (status) {
        is VpnStatus.Running -> OrbState.On
        is VpnStatus.Connecting -> OrbState.Connecting
        is VpnStatus.Blocked -> OrbState.Blocked
        else -> OrbState.Off
    }
    val chainName = (status as? VpnStatus.Running)?.profile ?: selected?.name ?: ""

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { Header(orb == OrbState.On, protection) }
        item {
            Centered {
                Orb(orb, enabled = selected != null) {
                    when (orb) {
                        OrbState.Off -> onConnect()
                        else -> onDisconnect()
                    }
                }
            }
        }
        item { StatusText(status, orb, chainName, exit, onRetry = onConnect, onDisconnect = onDisconnect) }
        selected?.let { p ->
            item { RouteCard(p, pools, orb == OrbState.On, traffic, exit, onEdit = onEditChain) }
        }
        item {
            SectionLabel(stringResource(R.string.section_chains)) {
                DsLink(stringResource(R.string.new_chain_link), onClick = {
                    vm.createChain { onEditChain() }
                })
            }
        }
        if (profiles.isEmpty()) {
            item { DashedNote(stringResource(R.string.no_chains)) }
        }
        items(profiles, key = { it.id }) { p ->
            ChainRow(p, pools, selected = p.id == selected?.id) {
                if (p.id != selected?.id) {
                    vm.select(p.id)
                    if (orb == OrbState.On || orb == OrbState.Connecting) toast.show(switchNote)
                }
            }
        }
        item { LeakGuard(protection, selected) }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun Header(connected: Boolean, protection: Protection?) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Mark(height = 14.dp, exitLit = connected)
        Spacer(Modifier.width(10.dp))
        Text("drysocks", style = DsType.Wordmark, modifier = Modifier.weight(1f))
        when {
            protection?.lockdown == true -> StatusPill("lockdown", Ds.Lime)
            protection?.alwaysOn == true -> StatusPill("always-on", Ds.Amber)
        }
    }
}

@Composable
private fun Orb(state: OrbState, enabled: Boolean, onClick: () -> Unit) {
    val fill by animateColorAsState(if (state == OrbState.On) Ds.Lime else Ds.Surface, tween(350), label = "orb")
    val text by animateColorAsState(
        when (state) {
            OrbState.On -> Ds.Ink
            OrbState.Blocked -> Ds.Coral
            else -> Ds.Bone
        }, tween(350), label = "orbText",
    )
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "angle")

    Box(
        Modifier.size(220.dp).drawBehind {
            if (state == OrbState.On) {
                // The glow: a soft lime field, and a 14dp ring of light around the orb.
                drawCircle(Brush.radialGradient(listOf(Ds.Lime.copy(alpha = 0.25f), Color.Transparent), radius = size.minDimension / 2), radius = size.minDimension / 2)
                drawCircle(Ds.Lime.copy(alpha = 0.08f), radius = (86 + 14).dp.toPx())
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(172.dp).clip(CircleShape).background(fill)
                .border(
                    1.dp,
                    when (state) {
                        OrbState.Blocked -> Ds.Coral
                        OrbState.On -> Color.Transparent
                        else -> Ds.hairline(0.12f)
                    },
                    CircleShape,
                )
                .clickable(enabled = enabled || state != OrbState.Off, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (state == OrbState.Connecting) {
                Canvas(Modifier.size(172.dp).rotate(angle)) {
                    drawArc(Ds.Lime, 0f, 90f, false, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round), topLeft = Offset(1.dp.toPx(), 1.dp.toPx()), size = Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(
                        when (state) {
                            OrbState.Off -> R.string.orb_connect
                            OrbState.Connecting -> R.string.orb_connecting
                            OrbState.On -> R.string.orb_connected
                            OrbState.Blocked -> R.string.orb_blocking
                        },
                    ),
                    style = DsType.OrbLabel.copy(color = text),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(
                        when (state) {
                            OrbState.Off -> R.string.orb_hint_off
                            OrbState.Connecting -> R.string.orb_hint_connecting
                            OrbState.On -> R.string.orb_hint_on
                            OrbState.Blocked -> R.string.orb_hint_blocked
                        },
                    ),
                    style = DsType.Mono.copy(fontSize = 11.sp, color = text.copy(alpha = 0.7f)),
                )
            }
        }
    }
}

@Composable
private fun StatusText(status: VpnStatus, orb: OrbState, chain: String, exit: String?, onRetry: () -> Unit, onDisconnect: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val (title, sub) = when (status) {
            is VpnStatus.Running -> stringResource(R.string.status_through, chain) to
                (exit?.let { stringResource(R.string.status_sites_see, it) } ?: stringResource(R.string.status_finding_exit))
            is VpnStatus.Connecting -> stringResource(R.string.status_connecting) to stringResource(R.string.status_building, chain)
            is VpnStatus.Blocked -> stringResource(R.string.status_blocked_title) to status.reason
            is VpnStatus.Failed -> stringResource(R.string.status_stopped) to stringResource(R.string.status_failed, status.reason)
            VpnStatus.Stopped -> stringResource(R.string.status_stopped) to
                if (chain.isEmpty()) stringResource(R.string.status_no_chain) else stringResource(R.string.status_goes_direct, chain)
        }
        Text(title, style = DsType.Status.copy(color = if (orb == OrbState.Blocked) Ds.Coral else Ds.Bone), textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(sub, style = DsType.BodyMuted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 300.dp))
        if (orb == OrbState.Blocked) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BonePill(stringResource(R.string.retry), onRetry, height = 44.dp)
                OutlinePill(stringResource(R.string.disconnect), onDisconnect)
            }
        }
    }
}

/** Phone → hops → Internet; lit, with a pulse travelling along it, while connected. */
@Composable
private fun RouteCard(p: Profile, pools: List<ProxyPool>, live: Boolean, traffic: Pair<Long, Long>, exit: String?, onEdit: () -> Unit) {
    val nodes = buildList {
        add("Phone" to "you")
        p.hops.forEach { add(hopNode(it, pools)) }
        add("Internet" to "exit")
    }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val t by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val line by animateColorAsState(if (live) Ds.Lime else Ds.hairline(0.14f), tween(350), label = "line")

    DsCard {
        SectionLabel(stringResource(R.string.section_route)) { DsLink(stringResource(R.string.edit_link), onEdit) }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth()) {
            // The rail and the pulse, drawn behind the nodes: from the first ring's centre to the last's.
            Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                val n = nodes.size
                val step = size.width / n
                val x0 = step / 2
                val x1 = size.width - step / 2
                val y = size.height / 2
                drawLine(line, Offset(x0, y), Offset(x1, y), 2.dp.toPx())
                if (live) {
                    val x = x0 + (x1 - x0) * t
                    val a = when {
                        t < 0.12f -> t / 0.12f
                        t > 0.88f -> (1 - t) / 0.12f
                        else -> 1f
                    }
                    drawCircle(Ds.Lime.copy(alpha = 0.3f * a), 7.dp.toPx(), Offset(x, y))
                    drawCircle(Ds.Lime.copy(alpha = a), 3.dp.toPx(), Offset(x, y))
                }
            }
            Row(Modifier.fillMaxWidth()) {
                nodes.forEachIndexed { i, (label, sub) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        val last = i == nodes.lastIndex
                        Box(
                            Modifier.size(14.dp).clip(CircleShape)
                                .background(if (live && last) Ds.Lime else Ds.Surface)
                                .border(2.dp, if (live) Ds.Lime else Ds.Dim, CircleShape),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(label, style = DsType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(sub, style = DsType.MonoSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (live) {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ds.hairline(0.06f)))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                RouteStat("Up", bytes(traffic.first), Ds.Bone, Modifier.weight(1f))
                RouteStat("Down", bytes(traffic.second), Ds.Bone, Modifier.weight(1f))
                RouteStat("Exit", exit ?: "…", Ds.Lime, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RouteStat(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = DsType.Secondary)
        Text(value, style = DsType.MonoStat.copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A hop as the route shows it: a name, and what kind of thing it is. */
fun hopNode(h: Hop, pools: List<ProxyPool>): Pair<String, String> = when (h.kind) {
    HopKind.TOR -> "Tor" to "3 relays"
    HopKind.ROTATING -> (pools.firstOrNull { it.id == h.poolId }?.name ?: "Rotating") to when (h.rotation) {
        RotationMode.SCATTERED -> "scattered"
        RotationMode.AUTO -> "every ${h.intervalSec} s"
        RotationMode.SEQUENTIAL -> "failover"
    }
    else -> h.host.ifEmpty { "proxy" } to h.kind.scheme
}

/** "Tor → 185.220.101.4 → internet" */
fun chainSummary(p: Profile, pools: List<ProxyPool> = emptyList()): String =
    (p.hops.map { hopNode(it, pools).first } + "internet").joinToString(" → ")

@Composable
private fun ChainRow(p: Profile, pools: List<ProxyPool>, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) Ds.Graphite else Color.Transparent)
            .border(1.dp, Ds.hairline(if (selected) 0.1f else 0.06f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Radio(selected)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = DsType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(chainSummary(p, pools), style = DsType.Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            p.problem()?.let { Text(it, style = DsType.Secondary.copy(color = Ds.Coral), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.width(10.dp))
        Text(if (p.hops.size == 1) "1 hop" else "${p.hops.size} hops", style = DsType.Mono.copy(color = Ds.Dim, fontSize = 11.sp))
    }
}

@Composable
fun Radio(on: Boolean) {
    Box(
        Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (on) Ds.Lime else Ds.Dim, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Box(Modifier.size(10.dp).clip(CircleShape).background(Ds.Lime))
    }
}

@Composable
private fun LeakGuard(protection: Protection?, selected: Profile?) {
    val ctx = LocalContext.current
    DsCard {
        Text(stringResource(R.string.section_leak_guard).uppercase(), style = DsType.Section)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.protection_heading), style = DsType.CardTitle)
        Spacer(Modifier.height(12.dp))
        GuardRow(stringResource(R.string.guard_always_on), protection?.alwaysOn)
        GuardRow(stringResource(R.string.guard_lockdown), protection?.lockdown)
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                protection == null -> stringResource(R.string.protection_unknown)
                protection.lockdown -> stringResource(R.string.protection_lockdown)
                protection.alwaysOn -> stringResource(R.string.protection_always_on)
                else -> stringResource(R.string.protection_off)
            },
            style = DsType.Secondary,
        )
        if (selected != null && protection?.alwaysOn != false) {
            lockdownCosts(selected).forEach {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(it), style = DsType.Secondary.copy(color = Ds.Coral))
            }
        }
        if (protection?.lockdown != true) {
            Spacer(Modifier.height(14.dp))
            OutlinePill(
                stringResource(R.string.open_vpn_settings),
                onClick = { ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun GuardRow(label: String, on: Boolean?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, style = DsType.Body, modifier = Modifier.weight(1f))
        Text(
            when (on) {
                true -> "On"
                false -> "Off"
                null -> "—"
            },
            style = DsType.MonoBody.copy(color = when (on) {
                true -> Ds.Lime
                false -> Ds.Coral
                null -> Ds.Dim
            }),
        )
    }
}

private fun lockdownCosts(p: Profile): List<Int> = buildList {
    if (p.appMode == AppMode.ONLY || p.apps.isNotEmpty()) add(R.string.lockdown_cost_apps)
    if (p.bypassLan) add(R.string.lockdown_cost_lan)
}
