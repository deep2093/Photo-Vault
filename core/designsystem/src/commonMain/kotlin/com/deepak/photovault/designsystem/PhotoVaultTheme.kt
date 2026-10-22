package com.deepak.photovault.designsystem

import androidx.compose.animation.core.FiniteAnimationSpec
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
        MaterialTheme(colorScheme = colors, typography = typographyFor(theme), content = content)
    }
}

/** Clear-glass themes give every text style a soft halo, so text reads over any photo. */
private fun typographyFor(theme: GlassTheme): Typography {
    val base = Typography()
    val halo = theme.textHalo ?: return base
    val shadow = Shadow(color = halo.copy(alpha = 0.95f), offset = Offset.Zero, blurRadius = 10f)
    fun TextStyle.haloed() = copy(shadow = shadow)
    return base.copy(
        displayLarge = base.displayLarge.haloed(), displayMedium = base.displayMedium.haloed(), displaySmall = base.displaySmall.haloed(),
        headlineLarge = base.headlineLarge.haloed(), headlineMedium = base.headlineMedium.haloed(), headlineSmall = base.headlineSmall.haloed(),
        titleLarge = base.titleLarge.haloed(), titleMedium = base.titleMedium.haloed(), titleSmall = base.titleSmall.haloed(),
        bodyLarge = base.bodyLarge.haloed(), bodyMedium = base.bodyMedium.haloed(), bodySmall = base.bodySmall.haloed(),
        labelLarge = base.labelLarge.haloed(), labelMedium = base.labelMedium.haloed(), labelSmall = base.labelSmall.haloed(),
    )
}

/**
 * An icon that stays readable on clear glass (Translucent): a soft glow in the icon's own shape sits behind
 * it, instead of a disc (feedback 22 Oct: the round backdrops looked odd). Other themes draw a plain icon.
 */
@Composable
fun GlassIcon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, glow: Boolean = true) {
    val halo = LocalGlassTheme.current.textHalo
    Box(modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (glow && halo != null) {
            // Slightly larger and blurred, so it reads as a glow around the strokes (Android 12+ blurs;
            // Android 11 shows a thin light outline instead).
            Icon(
                icon,
                contentDescription = null,
                tint = halo.copy(alpha = 0.9f),
                modifier = Modifier.matchParentSize().scale(1.14f).blur(3.dp, BlurredEdgeTreatment.Unbounded),
            )
        }
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.matchParentSize())
    }
}

/** The one animation spec for UI movement. Instant when reduce motion is on. */
@Composable
fun <T> motionSpec(durationMillis: Int = 220): FiniteAnimationSpec<T> =
    if (LocalGlassSettings.current.reduceMotion) snap() else tween(durationMillis, easing = FastOutSlowInEasing)
