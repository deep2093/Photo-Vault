package com.deepak.photovault.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * How an adaptive theme turns the colours of the photos on screen into its wallpaper. Ported from Veyra's
 * adaptive themes (same values), rewritten without Android colour classes so it stays shared code.
 *
 * [values] are the brightness of the three gradient colours (kept low so light text stays readable),
 * [minSaturation]..[maxSaturation] how colourful they may be, [glow] the strength of the soft lights and
 * [scrim] a dark veil over everything.
 */
@Immutable
data class AdaptiveStyle(
    val values: List<Float>,
    val minSaturation: Float,
    val maxSaturation: Float,
    val glow: Float,
    val scrim: Float,
    /** Used before any photo is on screen. */
    val fallback: List<Color>,
    /** Light adaptive themes: pastel colours under a white veil, with dark text. */
    val light: Boolean = false,
) {
    /** The readability veil: dark for light text, white for dark text. */
    val veil: Color get() = if (light) Color.White else Color.Black
}

/** Colours of the photos currently on screen. Each shown photo adds one colour and removes it when it leaves. */
class ScreenColors {
    private val shown = mutableStateMapOf<Long, Color>()
    private var next = 0L

    fun add(color: Color): Long {
        val token = next++
        shown[token] = color
        return token
    }

    fun remove(token: Long) {
        shown.remove(token)
    }

    fun current(): List<Color> = shown.values.toList()
}

val LocalScreenColors = staticCompositionLocalOf { ScreenColors() }

/**
 * A photo on screen lends [color] to an adaptive wallpaper while it is shown. Does nothing on other themes.
 * Phase 1 tiles pass their sample colour; Phase 2 passes each real photo's main colour.
 */
@Composable
fun ContributeScreenColor(color: Color?) {
    if (LocalGlassTheme.current.adaptive == null || color == null) return
    val registry = LocalScreenColors.current
    DisposableEffect(registry, color) {
        val token = registry.add(color)
        onDispose { registry.remove(token) }
    }
}

/** Pure HSV helpers (hue 0..360, saturation and value 0..1). */
object Hsv {
    fun of(color: Color): FloatArray {
        val r = color.red
        val g = color.green
        val b = color.blue
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val hue = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }
        val saturation = if (max == 0f) 0f else delta / max
        return floatArrayOf(hue, saturation, max)
    }

    fun toColor(hue: Float, saturation: Float, value: Float): Color {
        val h = ((hue % 360f) + 360f) % 360f
        val c = value * saturation
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = value - c
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color(r + m, g + m, b + m)
    }
}

/** Three gradient colours for [style] from the colours on screen. Same rules as Veyra. */
fun adaptivePalette(colors: List<Color>, style: AdaptiveStyle): List<Color> {
    if (colors.isEmpty()) return style.fallback
    val hsv = colors.map(Hsv::of)
    val first = hsv.maxBy { it[1] * it[2] }
    fun hueGap(a: Float, b: Float) = minOf(abs(a - b), 360f - abs(a - b))
    val second = hsv.filter { it[1] > 0.15f }.maxByOrNull { hueGap(it[0], first[0]) }
        ?.takeIf { hueGap(it[0], first[0]) > 25f } ?: floatArrayOf((first[0] + 40f) % 360f, first[1], first[2])
    val third = run {
        // The average hue as a direction, so reds near 0 and 360 average correctly.
        var x = 0.0
        var y = 0.0
        hsv.forEach {
            val radians = it[0].toDouble() * kotlin.math.PI / 180.0
            x += cos(radians) * it[1]
            y += sin(radians) * it[1]
        }
        val hue = ((atan2(y, x) * 180.0 / kotlin.math.PI + 360.0) % 360.0).toFloat()
        floatArrayOf(hue, hsv.map { it[1] }.average().toFloat(), 0.5f)
    }
    return listOf(first, second, third).mapIndexed { i, c ->
        Hsv.toColor(c[0], c[1].coerceIn(style.minSaturation, style.maxSaturation), style.values[i])
    }
}

/** The adaptive gradient, easing to new colours as the screen changes (instantly with reduce motion). */
@Composable
internal fun rememberAdaptiveColors(style: AdaptiveStyle): List<State<Color>> {
    val registry = LocalScreenColors.current
    val target by remember(style, registry) { derivedStateOf { adaptivePalette(registry.current(), style) } }
    val reduce = LocalGlassSettings.current.reduceMotion
    // Reacts within about half a second of new photos coming on screen (was 1.4 s; feedback 22 Oct).
    return List(3) { i -> animateColorAsState(target[i], if (reduce) snap() else tween(550), label = "adaptive_$i") }
}

/** Paints an adaptive wallpaper: a diagonal gradient, two soft lights and a readability veil. */
internal fun DrawScope.drawAdaptiveWallpaper(colors: List<Color>, style: AdaptiveStyle) {
    drawRect(Brush.linearGradient(listOf(colors[0], colors[2], colors[1]), start = Offset.Zero, end = Offset(size.width, size.height)))
    drawRect(
        Brush.radialGradient(
            listOf(colors[0].copy(alpha = style.glow), Color.Transparent),
            center = Offset(size.width * 0.85f, size.height * 0.08f),
            radius = size.maxDimension * 0.7f,
        ),
    )
    drawRect(
        Brush.radialGradient(
            listOf(colors[1].copy(alpha = style.glow * 0.8f), Color.Transparent),
            center = Offset(size.width * 0.1f, size.height * 0.9f),
            radius = size.maxDimension * 0.75f,
        ),
    )
    // Stronger towards the bottom, where the navigation bar sits.
    drawRect(Brush.verticalGradient(listOf(style.veil.copy(alpha = style.scrim * 0.6f), style.veil.copy(alpha = style.scrim))))
}
