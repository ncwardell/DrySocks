package io.github.ncwardell.drysocks.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// --- Surfaces --------------------------------------------------------------------------

@Composable
fun DsCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Ds.Surface).padding(padding),
        content = content,
    )
}

/** Mono, uppercase, tracked: "ROUTE", "CHAINS". */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = DsType.Section, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

/** A text action: "Edit →", "+ New chain". */
@Composable
fun DsLink(text: String, onClick: () -> Unit, color: Color = Ds.Bone) {
    Text(
        text, style = DsType.Label.copy(color = color),
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 10.dp),
    )
}

// --- Buttons ---------------------------------------------------------------------------

/** The primary action: a filled bone pill. */
@Composable
fun BonePill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = 52.dp) {
    Box(
        modifier
            .height(height)
            .clip(CircleShape)
            .background(if (enabled) Ds.Bone else Ds.Graphite)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = DsType.Button.copy(color = if (enabled) Ds.Ink else Ds.Disabled))
    }
}

/** A secondary action: an outline pill, bone by default, lime/purple/coral when it means something. */
@Composable
fun OutlinePill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Ds.Bone,
    enabled: Boolean = true,
    height: Dp = 44.dp,
) {
    val c = if (enabled) color else Ds.Disabled
    Box(
        modifier
            .height(height)
            .clip(CircleShape)
            .border(1.dp, if (color == Ds.Bone) Ds.hairline(0.14f) else c.copy(alpha = 0.5f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = DsType.Label.copy(color = c, fontSize = DsType.Label.fontSize * 1.1f))
    }
}

/** A choice among a few: chain chips, route chips. Selected is bone on ink. */
@Composable
fun DsChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(if (selected) Ds.Bone else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else Ds.hairline(0.14f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = DsType.Label.copy(color = if (selected) Ds.Ink else Ds.Bone), maxLines = 1)
    }
}

/** Pill-track tabs: Connections / DNS / Blocklist. */
@Composable
fun DsSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(CircleShape).background(Ds.Surface).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val bg by animateColorAsState(if (i == selected) Ds.SegmentActive else Color.Transparent, tween(200), label = "seg")
            Box(
                Modifier.weight(1f).height(36.dp).clip(CircleShape).background(bg).clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = DsType.Label.copy(color = if (i == selected) Ds.Bone else Ds.Muted), maxLines = 1)
            }
        }
    }
}

/** 48×28, lime when on, ink knob. */
@Composable
fun DsSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val knob by animateDpAsState(if (checked) 22.dp else 2.dp, tween(200), label = "knob")
    val track by animateColorAsState(if (checked) Ds.Lime else Ds.Track, tween(200), label = "track")
    Box(
        Modifier.size(48.dp, 28.dp).clip(CircleShape).background(track).clickable { onChange(!checked) },
    ) {
        Box(
            Modifier.offset(x = knob, y = 2.dp).size(24.dp).clip(CircleShape)
                .background(if (checked) Ds.Ink else Ds.Dim),
        )
    }
}

/** A hop's kind: Tor purple, Rotating lime, a proxy protocol bone. */
@Composable
fun HopTag(text: String, bg: Color, fg: Color, onClick: (() -> Unit)? = null) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(text, style = DsType.Label.copy(color = fg, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
    }
}

/** 52dp, radius 16, surface: the name field, the blocklist field. */
@Composable
fun DsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    style: TextStyle = DsType.Body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = DsType.Body.fontSize * 1.2f),
    height: Dp = 52.dp,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
) {
    BasicTextField(
        value, onValueChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(Ds.Lime),
        keyboardOptions = keyboard,
        modifier = modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(16.dp)).background(Ds.Surface)
            .border(1.dp, Ds.hairline(0.1f), RoundedCornerShape(16.dp)),
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = Ds.Dim, fontWeight = null))
                inner()
            }
        },
    )
}

// --- Motion ----------------------------------------------------------------------------

/** New list items: 250ms fade and a 6dp rise. */
@Composable
fun Modifier.enter(): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(250)) }
    return this.alpha(a.value).offset(y = (6 * (1 - a.value)).dp)
}

// --- Toast -----------------------------------------------------------------------------

@Stable
class ToastState {
    var message by mutableStateOf<String?>(null)
        private set
    private var serial by mutableStateOf(0)

    fun show(text: String) {
        message = text
        serial++
    }

    @Composable
    internal fun Host(modifier: Modifier) {
        LaunchedEffect(serial) {
            if (message != null) {
                delay(2200)
                message = null
            }
        }
        AnimatedVisibility(message != null, modifier, enter = fadeIn(tween(250)), exit = fadeOut(tween(250))) {
            Box(
                Modifier.fillMaxWidth()
                    .shadow(32.dp, RoundedCornerShape(16.dp), ambientColor = Color.Black.copy(alpha = 0.4f))
                    .clip(RoundedCornerShape(16.dp)).background(Ds.Bone)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Text(message.orEmpty(), style = DsType.Body.copy(color = Ds.Ink))
            }
        }
    }
}

val LocalToast = compositionLocalOf { ToastState() }

// --- Brand -----------------------------------------------------------------------------

/**
 * The mark: two hollow rings joined by bars, ending in the exit dot. The rings stand for a
 * chain of any length; the dot lights lime only when traffic is going through it.
 * Proportions from the brand board: ring Ø30 / stroke 4, bar 22×4, exit Ø42.
 */
@Composable
fun Mark(height: Dp, exitLit: Boolean, modifier: Modifier = Modifier, ink: Color = Ds.Bone) {
    val width = height * (30 + 22 + 30 + 22 + 42) / 42f
    val exit by animateColorAsState(if (exitLit) Ds.Lime else Ds.Disabled, tween(350), label = "exit")
    Canvas(modifier.size(width, height)) {
        val u = size.height / 42f
        val cy = size.height / 2
        val stroke = 4 * u
        var x = 0f
        repeat(2) {
            drawCircle(ink, radius = 15 * u - stroke / 2, center = Offset(x + 15 * u, cy), style = Stroke(stroke))
            x += 30 * u
            drawRoundRect(ink, topLeft = Offset(x - stroke / 2, cy - 2 * u), size = Size(22 * u + stroke, 4 * u), cornerRadius = CornerRadius(2 * u))
            x += 22 * u
        }
        drawCircle(exit, radius = 21 * u, center = Offset(x + 21 * u, cy))
    }
}

/** Small status dot with a label: header pills, leak-guard rows. */
@Composable
fun StatusPill(text: String, dot: Color) {
    Row(
        Modifier.clip(CircleShape).background(Ds.Surface).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Text("  $text", style = DsType.Mono.copy(color = Ds.Bone, fontSize = DsType.Mono.fontSize * 0.92f, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
    }
}

/** A dashed-border note card: "Connect to see traffic", "No hops yet". */
@Composable
fun DashedNote(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().defaultMinSize(minHeight = 72.dp)
            .drawBehind {
                drawRoundRect(
                    Ds.hairline(0.16f),
                    cornerRadius = CornerRadius(18.dp.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
                )
            }
            .padding(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = DsType.BodyMuted, textAlign = TextAlign.Center)
    }
}

@Composable
fun Centered(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) =
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center, content = content)
