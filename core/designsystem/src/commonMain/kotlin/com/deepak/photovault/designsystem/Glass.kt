package com.deepak.photovault.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * The theme's wallpaper behind everything: gradient, soft glows and a faint diagonal sheen. Adaptive themes
 * paint a gradient made from the photos on screen instead, which [ContributeScreenColor] feeds.
 */
@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val theme = LocalGlassTheme.current
    val registry = remember { ScreenColors() }
    CompositionLocalProvider(LocalScreenColors provides registry) {
        val style = theme.adaptive
        if (style != null) {
            val colors = rememberAdaptiveColors(style)
            Box(modifier.drawBehind { drawAdaptiveWallpaper(colors.map { it.value }, style) }, content = content)
        } else {
            Box(modifier.drawBehind { drawWallpaper(theme) }, content = content)
        }
    }
}

internal fun DrawScope.drawWallpaper(theme: GlassTheme) {
    drawRect(Brush.linearGradient(theme.wallpaper, start = Offset.Zero, end = Offset(size.width, size.height)))
    theme.glows.forEach { glow ->
        drawRect(
            Brush.radialGradient(
                listOf(glow.color, Color.Transparent),
                center = Offset(size.width * glow.x, size.height * glow.y),
                radius = size.maxDimension * glow.radius,
            ),
        )
    }
    drawRect(
        Brush.linearGradient(
            0f to Color.Transparent,
            0.45f to Color(0x0FFFFFFF),
            0.55f to Color(0x14FFFFFF),
            0.65f to Color.Transparent,
            start = Offset(0f, size.height * 0.2f),
            end = Offset(size.width, size.height * 0.6f),
        ),
    )
}

/** A fine specular rim: bright at the top-left, fading, with a softer glint at the bottom-right. */
@Composable
fun Modifier.glassRim(shape: Shape): Modifier {
    val theme = LocalGlassTheme.current
    val rim = theme.glassRim
    val k = theme.rimStrength
    return border(
        theme.rimWidth,
        Brush.linearGradient(listOf(rim.copy(alpha = (0.7f * k).coerceAtMost(1f)), rim.copy(alpha = 0.12f * k), rim.copy(alpha = (0.4f * k).coerceAtMost(1f)))),
        shape,
    )
}

/**
 * Glass for floating chrome (navigation bar, top bar) that sits over scrolling photos. Blurs what passes
 * underneath when blur is on and the screen provides [LocalHazeState]; otherwise uses the solid fallback.
 * On Android 11 the blur library falls back to the same tint by itself.
 */
@Composable
fun Modifier.glassChrome(shape: Shape, elevation: Dp = 16.dp): Modifier {
    val noShadow = false
    val noRim = false
    val noBlur = false
    val theme = LocalGlassTheme.current
    val haze = LocalHazeState.current
    val settings = LocalGlassSettings.current
    val blur = settings.blurEnabled && haze != null && !noBlur
    // Lens glass (experimental): only for themes that offer it, and only where the platform gave the bar a lens.
    val lens = if (noBlur) null else LocalLensGlass.current
    val lensActive = lens != null && settings.lensEnabled && settings.blurEnabled && theme.lens
    val shadowColor = Color.Black.copy(alpha = if (theme.isDark) 0.6f else 0.18f)
    return this
        .then(if (noShadow) Modifier else Modifier.shadow(elevation, shape, ambientColor = shadowColor, spotColor = shadowColor))
        .clip(shape)
        // Always in place when the platform offers a lens (it only draws while lens glass is on), so turning it on
        // later never has to attach it to a bar that is already on screen.
        .then(if (lens != null) lens.lens(shape, theme.glassTint.copy(alpha = LENS_TINT_ALPHA), lensActive) else Modifier)
        .then(
            if (lensActive) {
                Modifier
            } else if (blur && haze != null) {
                Modifier.hazeEffect(
                    state = haze,
                    style = HazeStyle(
                        // Transparent, not the wallpaper colour: where nothing scrolls under a bar (empty parts of
                        // Albums, Backup, Family, Settings, the tablet menu) the real wallpaper shows through, so the
                        // bar looks like the same glass everywhere instead of turning white (feedback 24 Oct).
                        backgroundColor = Color.Transparent,
                        tints = listOf(HazeTint(theme.glassTint)),
                        blurRadius = theme.blurRadius,
                        noiseFactor = theme.grain,
                        fallbackTint = HazeTint(theme.glassFallback),
                    ),
                )
            } else {
                Modifier.background(theme.glassFallback)
            },
        )
        .then(if (blur) Modifier.glassDepth() else Modifier)
        .then(if (noRim) Modifier else Modifier.glassRim(shape))
}

/** Glass for panels and cards that sit on the wallpaper. No blur needed: the wallpaper is calm. */
@Composable
fun Modifier.glassPanel(shape: Shape): Modifier {
    val theme = LocalGlassTheme.current
    val film = theme.panelFill
    // A see-through frosted film with depth (29 Oct) while the glass effect is on; solid when "Turn off glass effect" is on.
    return if (film != null && LocalGlassSettings.current.blurEnabled) {
        this.clip(shape).background(film).glassDepth().glassRim(shape)
    } else {
        this.clip(shape).background(theme.glassFallback).glassRim(shape)
    }
}

/**
 * Depth for glass (29 Oct): a soft light along the top and a little shade along the bottom, so a bar or card reads as
 * a thick pane above the page. Off with "Turn off glass effect".
 */
@Composable
fun Modifier.glassDepth(): Modifier {
    if (!LocalGlassSettings.current.blurEnabled) return this
    return this.drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.16f),
                0.4f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.14f),
            ),
        )
    }
}

/** On a pill filled with [accentSurface]: in frosted themes a fine bright edge so the film reads as glass. */
@Composable
fun Modifier.frostedEdge(shape: Shape): Modifier {
    val theme = LocalGlassTheme.current
    val edge = theme.glassRim
    return if (theme.accentFill == null) this else this.border(
        1.dp,
        Brush.linearGradient(listOf(edge.copy(alpha = 0.55f), edge.copy(alpha = 0.12f), edge.copy(alpha = 0.3f))),
        shape,
    )
}

val GlassCardShape = RoundedCornerShape(24.dp)

/**
 * Lens glass is more see-through than the normal blur (24 Oct, asked for "a bit more transparent"): the tint is
 * lighter than the 0.6 the contrast tests demand of the normal blur, so labels lean on the soft shadow under them.
 */
private const val LENS_TINT_ALPHA = 0.42f

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.glassPanel(GlassCardShape).padding(16.dp), content = content)
}
