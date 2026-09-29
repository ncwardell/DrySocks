package io.github.ncwardell.drysocks.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ncwardell.drysocks.Country
import io.github.ncwardell.drysocks.Geo
import io.github.ncwardell.drysocks.MapNode
import io.github.ncwardell.drysocks.R
import io.github.ncwardell.drysocks.RouteMap
import io.github.ncwardell.drysocks.core.flag
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A wireframe globe with the route drawn on it: phone → each hop → exit, in lime, and a
 * faint arc out to every country connections went to. Drag to turn it; left alone it
 * turns slowly on its own.
 */
@Composable
fun RouteGlobe(map: RouteMap, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val land = remember { runCatching { Geo.land(ctx) }.getOrDefault(emptyList()) }
    val measurer = rememberTextMeasurer()

    val placed = map.route.filter { it.country != null }
    val start = remember(placed.map { it.country?.code }) {
        // Face the route: its first located stop, else Europe-ish.
        placed.firstOrNull()?.country?.let { it.lon to it.lat.coerceIn(-50f, 50f) } ?: (10f to 30f)
    }
    var lon0 by remember { mutableFloatStateOf(start.first) }
    val lat0 = start.second
    var lastTouch by remember { mutableLongStateOf(0L) }

    // Idle spin, 4° a second, resuming three seconds after the last drag.
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L && System.currentTimeMillis() - lastTouch > 3_000) {
                    lon0 = (lon0 + 4f * (now - last) / 1e9f + 540f) % 360f - 180f
                }
                last = now
            }
        }
    }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val t by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "t")

    Canvas(
        // Horizontal drags spin it; vertical ones are left to the page, which scrolls.
        modifier.fillMaxWidth().aspectRatio(1f).pointerInput(Unit) {
            detectHorizontalDragGestures(onDragStart = { lastTouch = System.currentTimeMillis() }) { change, dx ->
                change.consume()
                lastTouch = System.currentTimeMillis()
                val r = size.width * 0.42f
                lon0 = (lon0 - dx / r * (180f / PI.toFloat()) + 540f) % 360f - 180f
            }
        },
    ) {
        val r = size.minDimension * 0.42f
        val c = Offset(size.width / 2, size.height / 2)
        val view = View(lon0, lat0, c, r)

        // The sphere: a faint fill and rim, so the back of the world reads as "behind".
        drawCircle(Brush.radialGradient(listOf(Ds.Surface, Ds.Ink), center = c, radius = r), r, c)
        drawCircle(Ds.hairline(0.14f), r, c, style = Stroke(1.dp.toPx()))

        // Graticule every 30°.
        val grid = Ds.hairline(0.06f)
        for (lat in -60..60 step 30) polyline(view, (0..72).map { (it * 5f - 180f) to lat.toFloat() }, grid, 1.dp.toPx())
        for (lon in -180 until 180 step 30) polyline(view, (0..36).map { lon.toFloat() to (it * 5f - 90f) }, grid, 1.dp.toPx())

        // Land.
        val coast = Ds.Bone.copy(alpha = 0.32f)
        for (ring in land) polyline(view, (0 until ring.size / 2).map { ring[it * 2] to ring[it * 2 + 1] }, coast, 1.dp.toPx())

        // Destinations: faint arcs from where traffic leaves, dots sized by traffic.
        val exitAt = map.exit ?: placed.lastOrNull()?.country
        val maxBytes = max(1L, map.destinations.maxOfOrNull { it.bytes } ?: 0L)
        for (d in map.destinations) {
            if (exitAt != null && exitAt.code != d.country.code) {
                arc(view, exitAt, d.country, Ds.Bone.copy(alpha = 0.22f), 1.dp.toPx(), lift = 0.08f)
            }
            view.point(d.country.lon, d.country.lat)?.let {
                val size = 3.dp.toPx() + 4.dp.toPx() * sqrt(d.bytes.toFloat() / maxBytes)
                drawCircle(Ds.Bone.copy(alpha = 0.85f), size, it)
            }
        }

        // The route, in lime: through the chain.
        val routeColor = if (map.live) Ds.Lime else Ds.Dim
        for (i in 0 until placed.size - 1) {
            val a = placed[i].country!!
            val b = placed[i + 1].country!!
            if (a.code == b.code) continue
            arc(view, a, b, routeColor, 2.dp.toPx(), lift = 0.16f, pulseAt = if (map.live) t else null)
        }
        placed.forEachIndexed { i, node ->
            val p = view.point(node.country!!.lon, node.country.lat) ?: return@forEachIndexed
            val isPhone = node.role == MapNode.Role.PHONE
            val last = i == placed.lastIndex
            if (last && map.live) {
                drawCircle(Ds.Lime.copy(alpha = 0.25f), 9.dp.toPx(), p)
                drawCircle(Ds.Lime, 5.dp.toPx(), p)
            } else {
                drawCircle(Ds.Ink, 5.dp.toPx(), p)
                drawCircle(if (isPhone) Ds.Muted else routeColor, 5.dp.toPx(), p, style = Stroke(2.dp.toPx()))
            }
            val label = measurer.measure(
                "${flag(node.country.code)} ${node.label}",
                TextStyle(color = Ds.Bone, fontSize = 11.sp, fontFamily = Geist),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            drawText(label, topLeft = Offset(p.x + 8.dp.toPx(), p.y - label.size.height - 2.dp.toPx()))
        }
    }
}

/** The textual route: a traceroute you can read, with each stop's flag and address. */
@Composable
fun RouteList(map: RouteMap) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        map.route.forEachIndexed { i, node ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Text("%02d".format(i), style = DsType.Mono.copy(color = Ds.Dim), modifier = Modifier.width(30.dp))
                Text(flag(node.country?.code), fontSize = 16.sp, modifier = Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(node.label, style = DsType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(node.detail, node.country?.name).joinToString("  ·  "),
                        style = DsType.MonoSmall.copy(fontSize = 11.sp, color = Ds.Muted),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        map.exit?.let {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.map_sites_see_you_in, flag(it.code), it.name), style = DsType.Body.copy(color = Ds.Lime))
        }
    }
}

@Composable
fun DestinationList(map: RouteMap) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        map.destinations.forEach { d ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Text(flag(d.country.code), fontSize = 16.sp, modifier = Modifier.width(30.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.country.name, style = DsType.Label)
                    Text(d.hosts.joinToString(", "), style = DsType.MonoSmall.copy(fontSize = 11.sp, color = Ds.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    Text(bytes(d.bytes), style = DsType.Mono.copy(color = Ds.Bone))
                    Text(if (d.connections == 1) "1 conn" else "${d.connections} conns", style = DsType.MonoSmall)
                }
            }
        }
    }
}

// --- Orthographic projection -----------------------------------------------------------

private class View(lon0: Float, lat0: Float, val c: Offset, val r: Float) {
    private val l0 = lon0.rad()
    private val sinP = sin(lat0.rad())
    private val cosP = cos(lat0.rad())

    /** Screen position of a point on the sphere lifted to [k] × radius, or null if behind. */
    fun point(lon: Float, lat: Float, k: Float = 1f): Offset? {
        val la = lat.rad()
        val dl = lon.rad() - l0
        val cosC = sinP * sin(la) + cosP * cos(la) * cos(dl)
        // A lifted point stays visible a little past the rim, where it clears the horizon.
        if (cosC < -(1f - 1f / k)) return null
        val x = cos(la) * sin(dl)
        val y = cosP * sin(la) - sinP * cos(la) * cos(dl)
        return Offset(c.x + r * k * x, c.y - r * k * y)
    }
}

private fun Float.rad() = this * PI.toFloat() / 180f

private fun DrawScope.polyline(view: View, pts: List<Pair<Float, Float>>, color: Color, width: Float) {
    val path = Path()
    var pen = false
    for ((lon, lat) in pts) {
        val p = view.point(lon, lat)
        if (p == null) {
            pen = false
            continue
        }
        if (pen) path.lineTo(p.x, p.y) else path.moveTo(p.x, p.y)
        pen = true
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round))
}

/** A great-circle arc from [a] to [b], lifted off the surface so it reads as a flight. */
private fun DrawScope.arc(view: View, a: Country, b: Country, color: Color, width: Float, lift: Float, pulseAt: Float? = null) {
    val va = unit(a.lon, a.lat)
    val vb = unit(b.lon, b.lat)
    val omega = acos((va[0] * vb[0] + va[1] * vb[1] + va[2] * vb[2]).coerceIn(-1f, 1f))
    if (omega < 1e-4f) return
    val steps = 48
    fun at(s: Float): Offset? {
        val w1 = sin((1 - s) * omega) / sin(omega)
        val w2 = sin(s * omega) / sin(omega)
        val x = w1 * va[0] + w2 * vb[0]
        val y = w1 * va[1] + w2 * vb[1]
        val z = w1 * va[2] + w2 * vb[2]
        val lat = asin(z.coerceIn(-1f, 1f)) * 180f / PI.toFloat()
        val lon = atan2(y, x) * 180f / PI.toFloat()
        // Higher for longer hops, so short ones do not balloon.
        val k = 1f + lift * (omega / PI.toFloat()).coerceAtLeast(0.2f) * sin(PI.toFloat() * s)
        return view.point(lon, lat, k)
    }
    val path = Path()
    var pen = false
    for (i in 0..steps) {
        val p = at(i / steps.toFloat())
        if (p == null) {
            pen = false
            continue
        }
        if (pen) path.lineTo(p.x, p.y) else path.moveTo(p.x, p.y)
        pen = true
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round))
    pulseAt?.let { s -> at(s)?.let { drawCircle(color, 3.5f * width / 2f, it) } }
}

private fun unit(lon: Float, lat: Float): FloatArray {
    val la = lat.rad()
    val lo = lon.rad()
    return floatArrayOf(cos(la) * cos(lo), cos(la) * sin(lo), sin(la))
}
