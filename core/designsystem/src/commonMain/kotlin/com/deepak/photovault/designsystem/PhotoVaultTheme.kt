package com.deepak.photovault.designsystem

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.compositeOver
import dev.chrisbanes.haze.HazeState

/**
 * How glass and motion behave right now. The platform decides the inputs (Android: battery saver and the
 * system "remove animations" setting; PC: the window), the user can override them in Settings.
 */
@Immutable
data class GlassSettings(
    /** Real backdrop blur. Off on battery saver or when the user turns it off; Android 11 falls back automatically. */
    val blurEnabled: Boolean = true,
    /** Replace movement with instant changes. */
    val reduceMotion: Boolean = false,
)

val LocalGlassTheme = staticCompositionLocalOf { GlassThemes.default }
val LocalGlassSettings = staticCompositionLocalOf { GlassSettings() }

/** Set by the screen scaffold around scrolling content, so floating bars can blur what passes under them. */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

@Composable
fun PhotoVaultTheme(
    theme: GlassTheme,
    settings: GlassSettings = GlassSettings(),
    content: @Composable () -> Unit,
) {
    val surface = theme.glassFallback.compositeOver(theme.wallpaper.first())
    val base = if (theme.isDark) darkColorScheme() else lightColorScheme()
    val colors = base.copy(
        primary = theme.accent,
        onPrimary = theme.onAccent,
        secondary = theme.accent,
        onSecondary = theme.onAccent,
        background = theme.wallpaper.first(),
        onBackground = theme.onGlass,
        surface = surface,
        onSurface = theme.onGlass,
        surfaceVariant = surface,
        onSurfaceVariant = theme.onGlassSecondary,
        surfaceContainerHigh = surface,
        outline = theme.glassRim,
    )
    CompositionLocalProvider(LocalGlassTheme provides theme, LocalGlassSettings provides settings) {
        MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
    }
}

/**
 * A plain icon. Clear-glass themes used to draw a glow behind icons and text; it was removed after the Phase 3
 * review (23 Oct) because it smeared text on solid surfaces. Transparent now frosts its bars instead.
 */
@Composable
fun GlassIcon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier.size(24.dp))
}

/** The one animation spec for UI movement. Instant when reduce motion is on. */
@Composable
fun <T> motionSpec(durationMillis: Int = 220, easing: Easing = FastOutSlowInEasing): FiniteAnimationSpec<T> =
    if (LocalGlassSettings.current.reduceMotion) snap() else tween(durationMillis, easing = easing)

/**
 * Pages that slide in from the edge like a sliding door (24 Oct, after the Samsung comparison): leaves quickly, then
 * settles softly, instead of the even ease the rest of the app uses.
 */
val PageEasing: Easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
