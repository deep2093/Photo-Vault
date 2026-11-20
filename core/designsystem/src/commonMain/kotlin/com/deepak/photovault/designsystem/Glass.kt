package com.deepak.photovault.designsystem

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
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
    // 13 Nov: picking another theme melts the old wallpaper into the new one instead of snapping. The old one is
    // painted on top and fades away; the fade is read only while drawing, so nothing is laid out or built again.
    var shown by remember { mutableStateOf(theme) }
    var leaving by remember { mutableStateOf<GlassTheme?>(null) }
    val fade = remember { Animatable(0f) }
    val still = LocalGlassSettings.current.reduceMotion
    LaunchedEffect(theme.id, theme.isDark) {
        if (shown.id != theme.id || shown.isDark != theme.isDark) {
            leaving = shown
            shown = theme
            if (!still) {
                fade.snapTo(1f)
                fade.animateTo(0f, tween(THEME_FADE_MS, easing = FastOutSlowInEasing))
            }
            leaving = null
        }
    }
    CompositionLocalProvider(LocalScreenColors provides registry) {
        val style = theme.adaptive
        if (style != null) {
            val colors = rememberAdaptiveColors(style)
            Box(
                modifier.drawBehind {
                    drawAdaptiveWallpaper(colors.map { it.value }, style)
                    leaving?.let { drawWallpaper(it, fade.value) }
                },
                content = content,
            )
        } else {
            Box(
                modifier.drawBehind {
                    drawWallpaper(theme)
                    leaving?.let { drawWallpaper(it, fade.value) }
                },
                content = content,
            )
        }
    }
}

/** How long the old wallpaper takes to melt into the new theme's. */
private const val THEME_FADE_MS = 420

internal fun DrawScope.drawWallpaper(theme: GlassTheme, alpha: Float = 1f) {
    if (alpha <= 0f) return
    drawRect(Brush.linearGradient(theme.wallpaper, start = Offset.Zero, end = Offset(size.width, size.height)), alpha = alpha)
    theme.glows.forEach { glow ->
        drawRect(
            Brush.radialGradient(
                listOf(glow.color, Color.Transparent),
                center = Offset(size.width * glow.x, size.height * glow.y),
                radius = size.maxDimension * glow.radius,
            ),
            alpha = alpha,
        )
    }
    // Pearl's lustre (20 Nov): a faint band of colour across the page, like the sheen on a pearl.
    theme.lustre?.takeIf { it.size == 3 }?.let { (from, middle, to) ->
        drawRect(
            Brush.linearGradient(
                0f to Color.Transparent,
                0.36f to from,
                0.5f to middle,
                0.64f to to,
                1f to Color.Transparent,
                start = Offset(0f, size.height * 0.15f),
                end = Offset(size.width, size.height * 0.65f),
            ),
            alpha = alpha,
        )
    }
    // Calm glass (18 Nov): no diagonal sheen across the page.
    if (GlassFinish.CALM) return
    drawRect(
        Brush.linearGradient(
            0f to Color.Transparent,
            0.45f to Color(0x0FFFFFFF),
            0.55f to Color(0x14FFFFFF),
            0.65f to Color.Transparent,
            start = Offset(0f, size.height * 0.2f),
            end = Offset(size.width, size.height * 0.6f),
        ),
        alpha = alpha,
    )
}

/**
 * A soft press (13 Nov): the control dips a little under the finger and springs back. Read while drawing only, so
 * pressing never lays anything out again. Off with Reduce motion.
 */
@Composable
fun Modifier.pressDip(source: androidx.compose.foundation.interaction.InteractionSource, to: Float = 0.96f): Modifier {
    val pressed by source.collectIsPressedAsState()
    val still = LocalGlassSettings.current.reduceMotion
    val scale = androidx.compose.animation.core.animateFloatAsState(
        if (pressed && !still) to else 1f,
        // Growing instead (Clear Glass's Apple motion, 21 Nov): a softer spring, so it settles with a little bounce.
        if (to > 1f) androidx.compose.animation.core.spring(dampingRatio = 0.42f, stiffness = 420f)
        else androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "pressDip",
    )
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

/** A fine specular rim: bright at the top-left, fading, with a softer glint at the bottom-right. */
@Composable
fun Modifier.glassRim(shape: Shape): Modifier {
    val theme = LocalGlassTheme.current
    // Liquid Glass (21 Nov): the lit edge of a droplet, drawn under the words; a plain line while the glass effect is off.
    if (theme.isLiquidGlass) {
        return if (LocalGlassSettings.current.blurEnabled) liquidGlassEdge(shape) else border(1.dp, calmEdge(theme), shape)
    }
    // Calm glass (18 Nov): one clean, even line instead of the bright glint.
    if (GlassFinish.CALM) return border(1.dp, calmEdge(theme), shape)
    return gradientRim(theme, shape)
}

/** The bright edge as a gradient line: the look before calm glass, and Liquid Glass's edge on Android 12 and older. */
internal fun Modifier.gradientRim(theme: GlassTheme, shape: Shape): Modifier {
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
    if (theme.isLiquidGlass) return liquidGlassChrome(shape, elevation, theme, settings)
    val blur = settings.blurEnabled && haze != null && !noBlur
    // Lens glass (experimental): only for themes that offer it, and only where the platform gave the bar a lens.
    val lens = if (noBlur) null else LocalLensGlass.current
    val lensActive = lens != null && settings.lensEnabled && settings.blurEnabled && theme.lens
    // Calm glass (18 Nov): a softer, shorter shadow.
    val shadowColor = Color.Black.copy(alpha = if (GlassFinish.CALM) (if (theme.isDark) 0.38f else 0.10f) else if (theme.isDark) 0.6f else 0.18f)
    val lift = if (GlassFinish.CALM) elevation * 0.6f else elevation
    return this
        .then(if (noShadow) Modifier else Modifier.shadow(lift, shape, ambientColor = shadowColor, spotColor = shadowColor))
        .clip(shape)
        // Always in place when the platform offers a lens (it only draws while lens glass is on), so turning it on
        // later never has to attach it to a bar that is already on screen.
        .then(if (lens != null) lens.lens(shape, theme.glassTint.copy(alpha = LENS_TINT_ALPHA), lensActive) else Modifier)
        .then(
            if (lensActive) {
                Modifier
            } else if (blur && haze != null && settings.blurScale <= 0f) {
                // No blur (experiment): a clear film only, a little stronger so text still reads over sharp photos.
                Modifier.background(theme.glassTint.copy(alpha = maxOf(theme.glassTint.alpha, 0.30f)))
            } else if (blur && haze != null) {
                Modifier.hazeEffect(
                    state = haze,
                    style = HazeStyle(
                        // Transparent, not the wallpaper colour: where nothing scrolls under a bar (empty parts of
                        // Albums, Backup, Family, Settings, the tablet menu) the real wallpaper shows through, so the
                        // bar looks like the same glass everywhere instead of turning white (feedback 24 Oct).
                        backgroundColor = Color.Transparent,
                        tints = listOf(HazeTint(theme.glassTint)),
                        blurRadius = theme.blurRadius * settings.blurScale,
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

/**
 * Glass for a control that floats inside a scrolling page (Photos' date, 18 Nov). The page itself is what the blur
 * reads, so blur can't be drawn inside it (it drew nothing at all, leaving the words straight on the photos): this is
 * the solid frosted fill instead, always opaque enough for the words on it, with the glass edge.
 */
@Composable
fun Modifier.solidGlass(shape: Shape): Modifier {
    val theme = LocalGlassTheme.current
    return this.clip(shape).background(theme.glassFallback).glassRim(shape)
}

/**
 * Liquid Glass on a floating bar (21 Nov, made like iOS's): clear, never blurred. On Android 13+ the lens looks through
 * to the photos, bends them at the rounded edge like a droplet and lights the edge itself. Where there is no lens
 * (Android 12 and older, or a screen that records nothing) it is a clear tinted pane with the same lit edge.
 */
@Composable
private fun Modifier.liquidGlassChrome(
    shape: Shape,
    elevation: Dp,
    theme: GlassTheme,
    settings: GlassSettings,
): Modifier {
    val lens = LocalLensGlass.current
    val ink = theme.glassInk ?: GlassInk.LIQUID
    // Clear Glass's Tinted (iOS 26.1): a firmer film, less see-through and with more contrast. Still no blur.
    val tinted = theme.isClearGlass && settings.tinted
    // A soft, lifted shadow, so the glass floats above the photos. Clear Glass's adapts like iOS's: fainter over bright,
    // plain photos and stronger over darker, busier ones (once the bar has looked behind itself).
    val luma = LocalBackdropLuma.current?.value
    val shadowAlpha = when {
        theme.isDark -> 0.42f
        theme.isClearGlass && luma != null -> 0.24f - 0.14f * luma.coerceIn(0f, 1f)
        else -> 0.17f
    }
    val shadowColor = Color.Black.copy(alpha = shadowAlpha)
    val surface = when {
        // "Turn off glass effect": plain and solid.
        !settings.blurEnabled -> Modifier.background(theme.glassFallback).border(1.dp, calmEdge(theme), shape)
        lens != null -> lens.lens(
            shape = shape,
            tint = theme.glassTint,
            active = true,
            liquid = LiquidGlassOptics(
                dark = theme.isDark,
                backdrop = LocalGlassBackdrop.current ?: theme.wallpaper.first(),
                fallback = theme.glassFallback,
                inkFloor = ink.dayFloor,
                inkCeiling = ink.nightCeiling,
                frost = when {
                    tinted -> LiquidGlassInk.TINTED_FROST
                    theme.isClearGlass -> maxOf(LiquidGlassInk.clearFilm(theme.isDark), LocalGlassFilm.current ?: 0f)
                    else -> 0f
                },
                frostColor = LocalGlassDarkness.current?.let { LiquidGlassInk.tintedFrostColor(it.value) } ?: LiquidGlassInk.tintedFrostColor(theme.isDark),
                light = LocalGlassLight.current,
                darkness = LocalGlassDarkness.current,
            ),
        )
        else -> Modifier.background(if (tinted) theme.glassFallback.copy(alpha = 0.9f) else theme.glassTint).liquidGlassEdge(shape)
    }
    return shadow(elevation * 0.7f, shape, ambientColor = shadowColor, spotColor = shadowColor)
        .clip(shape)
        .then(surface)
}

/** Glass for panels and cards that sit on the wallpaper. No blur needed: the wallpaper is calm. */
@Composable
fun Modifier.glassPanel(shape: Shape): Modifier {
    val theme = LocalGlassTheme.current
    val film = theme.panelFill
    // Liquid Glass (21 Nov): a clear film with the lit droplet edge, no sheen across it. Clear Glass's Tinted leaves
    // cards as they are (it made them lighter grey at night and changed nothing by day); it is for the floating glass.
    if (theme.isLiquidGlass && film != null && LocalGlassSettings.current.blurEnabled) {
        return clip(shape).background(film).liquidGlassEdge(shape)
    }
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
    val theme = LocalGlassTheme.current
    // Liquid Glass (21 Nov): clear; its depth is in the lit edge (glassRim), not a sheen across it.
    if (theme.isLiquidGlass) return this
    // Calm glass (18 Nov): only a faint light along the top, no shade along the bottom.
    val top = if (GlassFinish.CALM) 0.07f else 0.16f
    val bottom = if (GlassFinish.CALM) 0f else 0.14f
    return this.drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = top),
                0.4f to Color.Transparent,
                1f to Color.Black.copy(alpha = bottom),
            ),
        )
    }
}

/** On a pill filled with [accentSurface]: in frosted themes a fine bright edge so the film reads as glass. */
@Composable
fun Modifier.frostedEdge(shape: Shape): Modifier {
    val theme = LocalGlassTheme.current
    val edge = theme.glassRim
    // Liquid Glass (21 Nov): the lit droplet edge, like every Liquid Glass surface.
    if (theme.isLiquidGlass && theme.accentFill != null) return glassRim(shape)
    // Calm glass (18 Nov): an even line on night themes; day themes' soft shade needs none.
    if (GlassFinish.CALM && !theme.isLiquidGlass) return if (theme.accentFill == null || !theme.isDark) this else this.border(1.dp, Color.White.copy(alpha = 0.18f), shape)
    return if (theme.accentFill == null) this else this.border(
        1.dp,
        Brush.linearGradient(listOf(edge.copy(alpha = 0.55f), edge.copy(alpha = 0.12f), edge.copy(alpha = 0.3f))),
        shape,
    )
}

/**
 * Liquid Glass: a solid main button ("Move to bin") as a glossy droplet (21 Nov, Deepak: "doesn't have the glass effect
 * in both day and night"). The colour stays solid so the words read; over it a soft light along the top, a little
 * shade along the bottom and the lit glass edge every other Liquid Glass button has. The middle, where the words sit,
 * is untouched. The area must already be clipped to [shape].
 */
@Composable
internal fun Modifier.solidButtonEdge(shape: Shape): Modifier {
    if (!LocalGlassSettings.current.blurEnabled) return this
    return drawWithContent {
        drawRect(SolidGlassSheen)
        drawContent()
    }.liquidGlassEdge(shape)
}

/** The light along the top and the shade along the bottom of a solid piece of Liquid Glass; its middle is untouched. */
internal val SolidGlassSheen: Brush = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.20f),
    0.42f to Color.Transparent,
    0.62f to Color.Transparent,
    1f to Color.Black.copy(alpha = 0.16f),
)

/** The calm glass's edge: a faint white line on night themes, a faint dark one on day themes. */
private fun calmEdge(theme: GlassTheme): Color = if (theme.isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)

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
