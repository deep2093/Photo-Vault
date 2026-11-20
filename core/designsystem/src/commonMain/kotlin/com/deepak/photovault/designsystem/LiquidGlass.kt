package com.deepak.photovault.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/*
 * Liquid Glass, made like iOS's (21 Nov, Deepak: "transparent glossy elements, like water droplets, with depth. No
 * frost or blur of any kind"). The floating bars look through to the photos with nothing blurred: the Android lens
 * (LiquidGlassAgsl.LENS, Android 13+) bends the picture at their rounded edges like a droplet and lights the edge.
 * Panels and buttons on the page get the same lit edge ([liquidGlassEdge]). The selected tab is a small droplet of
 * brighter glass ([liquidGlassDroplet]).
 */

/**
 * How Liquid Glass keeps its words readable without frost (21 Nov). Behind the middle of the glass, by day the photo
 * is brightened just enough for dark words (keeping its colour where it can); by night it is dimmed like smoked glass
 * for white words. The Android lens does exactly this to every pixel (toneForInk in LiquidGlassAgsl), and
 * ThemeContrastTest checks each theme's words against [throughGlass].
 */
object LiquidGlassInk {
    /** By day: the least light kept behind dark words (linear luminance). Black photos show as mid grey, not white. */
    const val DAY_FLOOR = 0.27f

    /** By night: the most light kept behind white words (linear luminance). White photos show as smoked grey. */
    const val NIGHT_CEILING = 0.15f

    /** The selected tab's droplet: a little brighter glass by day, a little lighter smoke by night. */
    const val SELECTED_DAY_FLOOR = 0.42f
    const val SELECTED_NIGHT_CEILING = 0.17f

    /** A photo's colour as seen through the middle of the glass. */
    fun throughGlass(photo: Color, dark: Boolean, floor: Float = DAY_FLOOR, ceiling: Float = NIGHT_CEILING): Color {
        val r = toLinear(photo.red)
        val g = toLinear(photo.green)
        val b = toLinear(photo.blue)
        val luma = max(0.2126 * r + 0.7152 * g + 0.0722 * b, 0.00001)
        val out = if (dark) {
            val target = ceiling * (1.0 - exp(-luma / ceiling))
            val k = target / luma
            doubleArrayOf(r * k, g * k, b * k)
        } else {
            val target = floor + (1.0 - floor) * luma
            val brightest = max(max(r, max(g, b)), 0.00001)
            val gain = min(min(target / luma, 1.0 / brightest), 3.0)
            val lifted = luma * gain
            val toWhite = max(0.0, (target - lifted) / max(1.0 - lifted, 0.00001))
            doubleArrayOf(r * gain, g * gain, b * gain).map { it + (1.0 - it) * toWhite }.toDoubleArray()
        }
        return Color(toSrgb(out[0]), toSrgb(out[1]), toSrgb(out[2]))
    }

    private fun toLinear(c: Float): Double {
        val v = c.toDouble().coerceIn(0.0, 1.0)
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun toSrgb(c: Double): Float {
        val v = c.coerceIn(0.0, 1.0)
        return (if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1.0 / 2.4) - 0.055).toFloat()
    }
}

/** Android 13+ draws the lit glass edge with a shader; older phones keep the gradient edge. */
internal expect fun liquidGlassEdgeSupported(): Boolean

/** Draws the glass edge over this area (its corners [radius] round), under whatever is drawn next. */
internal expect fun DrawScope.drawLiquidGlassEdge(radius: Float, dark: Boolean)

/**
 * The lit edge of Liquid Glass on a panel or button (21 Nov): a thin bright line where the rounded edge catches the
 * light, a soft gloss at the top left and a glint at the bottom right, and a faint shade inside the edge for its
 * thickness. Drawn under the words. The area must already be clipped to [shape].
 */
@Composable
internal fun Modifier.liquidGlassEdge(shape: Shape): Modifier {
    val theme = LocalGlassTheme.current
    if (!liquidGlassEdgeSupported()) return gradientRim(theme, shape)
    val dark = theme.isDark
    return drawWithCache {
        val radius = when (val outline = shape.createOutline(size, layoutDirection, this)) {
            is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
            is Outline.Rectangle -> 0f
            else -> size.minDimension / 2f
        }
        onDrawWithContent {
            drawLiquidGlassEdge(radius, dark)
            drawContent()
        }
    }
}

/**
 * The selected tab in Liquid Glass (21 Nov): a small droplet of brighter glass that magnifies what passes under it,
 * as iOS shows the selected tab. Without a lens (Android 12 and older, or the glass effect off) it is the frosted
 * accent film with the lit edge.
 */
@Composable
fun Modifier.liquidGlassDroplet(shape: Shape): Modifier {
    val theme = LocalGlassTheme.current
    val lens = LocalLensGlass.current
    if (lens != null && theme.isLiquidGlass && lensGlassActive()) {
        val film = theme.accentSurface.compositeOver(theme.glassFallback)
        return clip(shape).then(
            lens.lens(
                shape = shape,
                tint = film,
                active = true,
                liquid = LiquidGlassOptics(
                    dark = theme.isDark,
                    backdrop = theme.wallpaper.first(),
                    fallback = film,
                    inkFloor = LiquidGlassInk.SELECTED_DAY_FLOOR,
                    inkCeiling = LiquidGlassInk.SELECTED_NIGHT_CEILING,
                ),
            ),
        )
    }
    return clip(shape).background(theme.accentSurface).frostedEdge(shape)
}

/** Reuse the host button's interactions, so the highlight needs no competing gesture detector. */
@Composable
fun Modifier.liquidGlassTouch(source: InteractionSource): Modifier {
    val settings = LocalGlassSettings.current
    if (!LocalGlassTheme.current.isLiquidGlass || !settings.blurEnabled || settings.reduceMotion) return this

    var press by remember(source) { mutableStateOf<PressInteraction.Press?>(null) }
    var point by remember(source) { mutableStateOf(Offset.Unspecified) }
    LaunchedEffect(source) {
        source.interactions.collect { event ->
            when (event) {
                is PressInteraction.Press -> {
                    point = event.pressPosition
                    press = event
                }
                is PressInteraction.Release -> if (press == event.press) press = null
                is PressInteraction.Cancel -> if (press == event.press) press = null
                else -> Unit
            }
        }
    }
    val alpha = animateFloatAsState(
        targetValue = if (press == null) 0f else 0.30f,
        animationSpec = tween(if (press == null) 220 else 90),
        label = "glass reflection",
    )
    return drawWithContent {
        if (alpha.value > 0f && size.minDimension > 0f) {
            val center = if (point == Offset.Unspecified) this.center else point
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, Color.White.copy(alpha = 0f)),
                    center = center,
                    radius = size.minDimension,
                ),
                alpha = alpha.value,
            )
        }
        drawContent()
    }
}
