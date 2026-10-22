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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
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
    val rim = LocalGlassTheme.current.glassRim
    return border(
        1.dp,
        Brush.linearGradient(listOf(rim.copy(alpha = 0.7f), rim.copy(alpha = 0.12f), rim.copy(alpha = 0.4f))),
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
    val theme = LocalGlassTheme.current
    val haze = LocalHazeState.current
    val blur = LocalGlassSettings.current.blurEnabled && haze != null
    val shadowColor = Color.Black.copy(alpha = if (theme.isDark) 0.6f else 0.18f)
    return this
        .shadow(elevation, shape, ambientColor = shadowColor, spotColor = shadowColor)
        .clip(shape)
        .then(
            if (blur && haze != null) {
                Modifier.hazeEffect(
                    state = haze,
                    style = HazeStyle(
                        backgroundColor = theme.wallpaper.first(),
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
        .glassRim(shape)
}

/** Glass for panels and cards that sit on the wallpaper. No blur needed: the wallpaper is calm. */
@Composable
fun Modifier.glassPanel(shape: Shape): Modifier {
    val theme = LocalGlassTheme.current
    return this.clip(shape).background(theme.glassFallback).glassRim(shape)
}

val GlassCardShape = RoundedCornerShape(24.dp)

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.glassPanel(GlassCardShape).padding(16.dp), content = content)
}
