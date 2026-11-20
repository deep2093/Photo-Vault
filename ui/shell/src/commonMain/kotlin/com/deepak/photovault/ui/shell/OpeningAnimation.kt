package com.deepak.photovault.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * How an icon splits into pieces for the opening animation: a centre point (fractions of the icon image) and the
 * angles, in degrees clockwise from "right", that separate one piece from the next. Cutting along these lines
 * gives back exactly the icon when the pieces meet, whichever icon is chosen.
 */
data class IconPieces(val centerX: Float, val centerY: Float, val cuts: List<Float>) {
    val count: Int get() = cuts.size

    /** The piece between cut [i] and the next one, as (start angle, sweep). */
    fun wedge(i: Int): Pair<Float, Float> {
        val start = cuts[i]
        val end = cuts[(i + 1) % cuts.size].let { if (it <= start) it + 360f else it }
        return start to (end - start)
    }

    companion object {
        fun of(glyph: IconGlyph): IconPieces = when (glyph) {
            // Four leaves (or tiles), one in each quarter.
            IconGlyph.FOUR_SEASONS -> IconPieces(0.5f, 0.5f, listOf(0f, 90f, 180f, 270f))
            // A flower: two leaves, three petals and the stem, fanning out from where they meet.
            IconGlyph.LEAF -> IconPieces(0.5f, 0.57f, listOf(-10f, 60f, 120f, 190f, 245f, 295f))
        }
    }
}

/**
 * The opening animation (24 Oct): the pieces of the chosen app icon fly in from all sides in a spiral and join
 * into the icon, then the app fades in. About a second; only on a fresh start; never with reduce motion (the
 * caller skips it then). It can't be skipped by tapping, by request.
 */
@Composable
fun OpeningAnimation(icon: ImageBitmap, glyph: IconGlyph, background: Color, onDone: () -> Unit, reveal: Boolean = false) {
    val pieces = remember(glyph) { IconPieces.of(glyph) }
    val flight = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    // Extra animations (21 Nov, Deepak: "do logo reveal"): once the pieces have joined, the icon dips a little and then
    // grows past the edges of the screen as a window, the app showing through its shape; the photos then arrive in a
    // wave. Without it the icon fades away as before.
    val grow = remember { Animatable(0f) }
    val haptics = com.deepak.photovault.designsystem.rememberHaptics()
    LaunchedEffect(Unit) {
        flight.animateTo(1f, tween(FLIGHT_MS, easing = FastOutSlowInEasing))
        delay(HOLD_MS)
        if (reveal) {
            haptics.tick()
            launch {
                delay((REVEAL_MS * REVEAL_DIP).toLong())
                com.deepak.photovault.designsystem.OpeningWave.play()
            }
            grow.animateTo(1f, tween(REVEAL_MS, easing = LinearEasing))
        } else {
            fade.animateTo(0f, tween(FADE_MS, easing = LinearEasing))
        }
        onDone()
    }
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val g = grow.value
                alpha = fade.value * (1f - ((g - 0.72f) / 0.28f).coerceIn(0f, 1f))
                // The window is cut out of this layer only, so the app shows through it.
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
            },
    ) {
        drawRect(background)
        val t = flight.value
        val iconPx = ICON_SIZE.toPx()
        val center = Offset(size.width / 2f, size.height / 2f)
        val g = grow.value
        if (g > 0f) {
            // First a small dip (the icon itself), then the icon's shape as a growing window.
            val window = g >= REVEAL_DIP
            val zoom = if (!window) 1f - 0.12f * (g / REVEAL_DIP) else 0.88f + (REVEAL_ZOOM - 0.88f) * ((g - REVEAL_DIP) / (1f - REVEAL_DIP)).pow(2.4f)
            val topLeft = Offset(center.x - iconPx * pieces.centerX, center.y - iconPx * pieces.centerY)
            scale(zoom, zoom, pivot = center) {
                drawImage(
                    icon,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(icon.width, icon.height),
                    dstOffset = IntOffset(topLeft.x.toInt(), topLeft.y.toInt()),
                    dstSize = IntSize(iconPx.toInt(), iconPx.toInt()),
                    blendMode = if (window) androidx.compose.ui.graphics.BlendMode.DstOut else androidx.compose.ui.graphics.BlendMode.SrcOver,
                )
            }
            return@Canvas
        }
        // Where the icon's own centre point lands on screen.
        val iconTopLeft = Offset(center.x - iconPx * pieces.centerX, center.y - iconPx * pieces.centerY)
        val pivot = Offset(iconTopLeft.x + iconPx * pieces.centerX, iconTopLeft.y + iconPx * pieces.centerY)
        val far = hypot(size.width, size.height) * 0.7f
        val left = 1f - t
        for (i in 0 until pieces.count) {
            val (start, sweep) = pieces.wedge(i)
            val mid = start + sweep / 2f
            // Each piece starts off screen on its own side and spirals inwards.
            val orbit = (mid + left * SPIRAL_DEGREES) * PI.toFloat() / 180f
            val distance = far * left.pow(1.4f)
            val shift = Offset(cos(orbit) * distance, sin(orbit) * distance)
            val spin = -left * SPIN_DEGREES
            val alpha = (t * 3f).coerceIn(0f, 1f)
            val wedge = wedgePath(pivot, start, sweep, iconPx * 2f)
            // The piece's own middle, so it turns about itself while flying.
            val rad = mid * PI.toFloat() / 180f
            val own = Offset(pivot.x + cos(rad) * iconPx * 0.2f, pivot.y + sin(rad) * iconPx * 0.2f)
            translate(shift.x, shift.y) {
                rotate(spin, own) {
                    clipPath(wedge) {
                        drawImage(
                            icon,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(icon.width, icon.height),
                            dstOffset = IntOffset(iconTopLeft.x.toInt(), iconTopLeft.y.toInt()),
                            dstSize = IntSize(iconPx.toInt(), iconPx.toInt()),
                            alpha = alpha,
                        )
                    }
                }
            }
        }
    }
}

private fun wedgePath(center: Offset, startDeg: Float, sweepDeg: Float, radius: Float): Path = Path().apply {
    moveTo(center.x, center.y)
    val steps = 12
    for (s in 0..steps) {
        val a = (startDeg + sweepDeg * s / steps) * PI.toFloat() / 180f
        lineTo(center.x + cos(a) * radius, center.y + sin(a) * radius)
    }
    close()
}

/** The icon artwork has the launcher's safe margin around it, so it is drawn larger than the flower itself. */
private val ICON_SIZE = 240.dp
private const val FLIGHT_MS = 850
private const val HOLD_MS = 120L
private const val FADE_MS = 230

/** The logo reveal: how long, the share of it spent on the dip, and how many times its size the window grows to. */
private const val REVEAL_MS = 520
private const val REVEAL_DIP = 0.22f
private const val REVEAL_ZOOM = 30f
private const val SPIRAL_DEGREES = 220f
private const val SPIN_DEGREES = 180f
