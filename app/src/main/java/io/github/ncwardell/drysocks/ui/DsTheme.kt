package io.github.ncwardell.drysocks.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.ncwardell.drysocks.R

/**
 * The DrySocks palette. Two colours carry meaning and are never decoration: lime means
 * traffic is going through the chain (live, healthy, protected); coral means exposed,
 * blocked or failed. Everything else is neutral.
 */
object Ds {
    val Ink = Color(0xFF0D0E10)
    val Surface = Color(0xFF16181B)
    val Graphite = Color(0xFF1E2125)
    val SegmentActive = Color(0xFF2A2D32)
    val Track = Color(0xFF26292E)
    val Bone = Color(0xFFF3F2EE)
    val Muted = Color(0xFF9A9DA3)
    val Dim = Color(0xFF6E7177)
    val Disabled = Color(0xFF4A4D52)

    val Lime = Color(0xFFC6F24E)
    val Coral = Color(0xFFFF7A59)
    val Amber = Color(0xFFE8C547)
    val TorPurple = Color(0xFFC9B8FF)

    fun hairline(alpha: Float = 0.06f) = Color.White.copy(alpha = alpha)
}

val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
)

/** For anything a machine reads back: addresses, bytes, latency, indices, section labels. */
val GeistMono = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
)

/** The type scale from the brand board. */
object DsType {
    val ScreenTitle = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, letterSpacing = (-0.04).em, color = Ds.Bone)
    val Status = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, color = Ds.Bone)
    val OrbLabel = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
    val Wordmark = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = (-0.04).em, color = Ds.Bone)
    val CardTitle = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Ds.Bone)
    val RowTitle = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Ds.Bone)
    val Body = TextStyle(fontFamily = Geist, fontSize = 14.sp, lineHeight = 20.sp, color = Ds.Bone)
    val BodyMuted = Body.copy(color = Ds.Muted)
    val Secondary = TextStyle(fontFamily = Geist, fontSize = 12.sp, lineHeight = 17.sp, color = Ds.Muted)
    val Label = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = Ds.Bone)
    val Button = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    val Section = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.08.em, color = Ds.Muted)
    val Mono = TextStyle(fontFamily = GeistMono, fontSize = 12.sp, color = Ds.Muted)
    val MonoBody = TextStyle(fontFamily = GeistMono, fontSize = 14.sp, color = Ds.Bone)
    val MonoSmall = TextStyle(fontFamily = GeistMono, fontSize = 10.sp, color = Ds.Dim)
    val MonoStat = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = Ds.Bone)
}

/**
 * Dark-first, and only dark: a privacy tool whose one signal colour reads best on ink.
 * Material's roles are mapped onto the palette so stock components (dialogs, fields,
 * menus) already look like DrySocks.
 */
@Composable
fun DrySocksTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Ds.Lime,
        onPrimary = Ds.Ink,
        primaryContainer = Ds.Graphite,
        onPrimaryContainer = Ds.Bone,
        secondary = Ds.Bone,
        onSecondary = Ds.Ink,
        secondaryContainer = Ds.SegmentActive,
        onSecondaryContainer = Ds.Bone,
        tertiary = Ds.TorPurple,
        tertiaryContainer = Ds.Graphite,
        onTertiaryContainer = Ds.Bone,
        background = Ds.Ink,
        onBackground = Ds.Bone,
        surface = Ds.Ink,
        onSurface = Ds.Bone,
        surfaceVariant = Ds.Surface,
        onSurfaceVariant = Ds.Muted,
        surfaceContainerLowest = Ds.Ink,
        surfaceContainerLow = Ds.Surface,
        surfaceContainer = Ds.Surface,
        surfaceContainerHigh = Ds.Graphite,
        surfaceContainerHighest = Ds.SegmentActive,
        outline = Color.White.copy(alpha = 0.14f),
        outlineVariant = Color.White.copy(alpha = 0.08f),
        error = Ds.Coral,
        onError = Ds.Ink,
        errorContainer = Color(0xFF3A1F18),
        onErrorContainer = Ds.Coral,
    )
    val base = Typography()
    fun TextStyle.g() = copy(fontFamily = Geist)
    val type = Typography(
        displayLarge = base.displayLarge.g(), displayMedium = base.displayMedium.g(), displaySmall = base.displaySmall.g(),
        headlineLarge = base.headlineLarge.g(), headlineMedium = base.headlineMedium.g(), headlineSmall = base.headlineSmall.g(),
        titleLarge = base.titleLarge.g().copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.g().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.g().copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.g(), bodyMedium = base.bodyMedium.g(), bodySmall = base.bodySmall.g(),
        labelLarge = base.labelLarge.g(), labelMedium = base.labelMedium.g(), labelSmall = base.labelSmall.g(),
    )
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
