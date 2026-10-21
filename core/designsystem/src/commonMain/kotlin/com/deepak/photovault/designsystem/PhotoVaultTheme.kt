package com.deepak.photovault.designsystem

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
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
        MaterialTheme(colorScheme = colors, content = content)
    }
}

/** The one animation spec for UI movement. Instant when reduce motion is on. */
@Composable
fun <T> motionSpec(durationMillis: Int = 220): FiniteAnimationSpec<T> =
    if (LocalGlassSettings.current.reduceMotion) snap() else tween(durationMillis, easing = FastOutSlowInEasing)
