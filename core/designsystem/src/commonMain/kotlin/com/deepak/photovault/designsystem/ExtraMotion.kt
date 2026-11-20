package com.deepak.photovault.designsystem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/*
 * Small extra animations in every theme (21 Nov, Deepak: "make all animations for all themes but give a toggle in
 * About"; the ideas are from two lists of iPhone animations he sent, rebuilt here as none of that code runs on Android).
 * Each is short, never makes anyone wait, and is off with Reduce motion or the switch in Settings > About.
 */

/** True when the extra animations should play: the About switch is on and Reduce motion is off. */
@Composable
fun extraMotionOn(): Boolean {
    val settings = LocalGlassSettings.current
    return settings.extraMotion && !settings.reduceMotion
}

/** Shakes side to side, like a head saying no, each time [count] goes up (a wrong PIN). */
@Composable
fun Modifier.shakeOn(count: Int): Modifier {
    if (!extraMotionOn()) return this
    val x = remember { Animatable(0f) }
    LaunchedEffect(count) {
        if (count > 0) {
            x.snapTo(0f)
            for (to in SHAKE) x.animateTo(to, tween(55, easing = LinearEasing))
        }
    }
    return graphicsLayer { translationX = x.value * 12.dp.toPx() }
}

private val SHAKE = floatArrayOf(-1f, 1f, -0.75f, 0.75f, -0.4f, 0.4f, 0f)

/**
 * The favourite heart: when [on] turns true it springs up from small with a ring and a few sparks around it. [key] is
 * what the heart belongs to (the photo): moving to another photo that is already a favourite plays nothing.
 */
@Composable
fun Modifier.heartPop(on: Boolean, key: Any?, color: Color): Modifier {
    if (!extraMotionOn()) return this
    val scale = remember { Animatable(1f) }
    val burst = remember { Animatable(1f) }
    var was by remember(key) { mutableStateOf(on) }
    LaunchedEffect(key, on) {
        if (on && !was) {
            launch {
                burst.snapTo(0f)
                burst.animateTo(1f, tween(440, easing = LinearOutSlowInEasing))
            }
            scale.snapTo(0.55f)
            scale.animateTo(1f, spring(dampingRatio = 0.32f, stiffness = 520f))
        }
        was = on
    }
    return drawBehind {
        val t = burst.value
        if (t < 1f) {
            val reach = size.minDimension * (0.22f + 0.30f * t)
            drawCircle(color, radius = reach, alpha = (1f - t) * 0.55f, style = Stroke(width = (1f - t) * 3.dp.toPx() + 0.5f))
            val dot = 1.8.dp.toPx() * (1f - t)
            for (i in 0 until 8) {
                val a = (i / 8f) * 2f * PI.toFloat() + 0.4f
                drawCircle(color, radius = dot, center = center + Offset(cos(a), sin(a)) * (reach * 1.22f), alpha = 1f - t)
            }
        }
    }.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/** A small bounce when [chosen] turns true (the bottom bar's icon). Nothing the first time it is drawn. */
@Composable
fun Modifier.bounceWhenChosen(chosen: Boolean): Modifier {
    if (!extraMotionOn()) return this
    val scale = remember { Animatable(1f) }
    var was by remember { mutableStateOf(chosen) }
    LaunchedEffect(chosen) {
        if (chosen && !was) {
            scale.snapTo(0.78f)
            scale.animateTo(1f, spring(dampingRatio = 0.38f, stiffness = 620f))
        }
        was = chosen
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/**
 * A soft shine that moves across something still loading, in [ink] (a tile waiting for its picture from the drive).
 * It only runs while [active], so nothing is left animating once the picture is there.
 */
@Composable
fun Modifier.shimmer(active: Boolean, ink: Color): Modifier {
    if (!active || !extraMotionOn()) return this
    val shine = rememberInfiniteTransition(label = "shimmer")
        .animateFloat(-0.6f, 1.6f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "shine")
    return drawBehind {
        val band = maxOf(size.width * 0.5f, 40.dp.toPx())
        val x = shine.value * size.width
        drawRect(
            Brush.horizontalGradient(
                0f to ink.copy(alpha = 0f),
                0.5f to ink.copy(alpha = 0.13f),
                1f to ink.copy(alpha = 0f),
                startX = x - band / 2f,
                endX = x + band / 2f,
            ),
        )
    }
}

/**
 * Words whose parts roll to their new value (the floating date: only the day rolls while the month stays, and so on).
 * The parts are the words between spaces. With the extra animations off it is a plain line of text.
 */
@Composable
fun RollingText(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
) {
    if (!extraMotionOn()) {
        Text(text, color = color, style = style, fontWeight = fontWeight, maxLines = 1, modifier = modifier)
        return
    }
    val gap = with(LocalDensity.current) { (style.fontSize * 0.27f).toDp() }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
        text.split(' ').forEach { part ->
            AnimatedContent(
                targetState = part,
                transitionSpec = {
                    (slideInVertically(tween(190)) { it / 2 } + fadeIn(tween(190))) togetherWith
                        (slideOutVertically(tween(190)) { -it / 2 } + fadeOut(tween(120))) using SizeTransform(clip = false)
                },
                label = "roll",
            ) { shown ->
                Text(shown, color = color, style = style, fontWeight = fontWeight, maxLines = 1, softWrap = false)
            }
        }
    }
}

/**
 * A number that runs up to [target] when it first shows and whenever it changes (the Backup page's share and counts).
 * With the extra animations off it is just [target].
 */
@Composable
fun countingUp(target: Int): Int {
    if (!extraMotionOn()) return target
    val shown = remember { Animatable(0f) }
    LaunchedEffect(target) { shown.animateTo(target.toFloat(), tween(700, easing = LinearOutSlowInEasing)) }
    return shown.value.roundToInt()
}

/**
 * The photos arriving in a wave from the top of the screen down, once, as the app opens (after the logo reveal).
 * [play] runs it; while [playing], tiles with [openingWave] fade and rise into place, the lower ones a little later.
 */
object OpeningWave {
    internal val progress = Animatable(1f)

    var playing by mutableStateOf(false)
        private set

    suspend fun play() {
        playing = true
        try {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(620, easing = LinearEasing))
        } finally {
            playing = false
        }
    }
}

/** One tile of the opening wave (see [OpeningWave]). Nothing is attached except while the wave is playing. */
@Composable
fun Modifier.openingWave(): Modifier {
    if (!OpeningWave.playing) return this
    // Where this tile is down the screen: [0] its top, [1] the screen's height.
    val place = remember { floatArrayOf(0f, 1f) }
    return onGloballyPositioned {
        place[0] = it.positionInRoot().y
        place[1] = it.findRootCoordinates().size.height.toFloat().coerceAtLeast(1f)
    }.graphicsLayer {
        val start = (place[0] / place[1]).coerceIn(0f, 1f) * 0.55f
        val k = ((OpeningWave.progress.value - start) / 0.45f).coerceIn(0f, 1f)
        alpha = k
        translationY = (1f - k) * 26.dp.toPx()
    }
}

/** A small ring that turns, for a button that is starting something. */
@Composable
fun Spinner(color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val turn = rememberInfiniteTransition(label = "spinner")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "turn")
    Canvas(modifier.size(size)) {
        val stroke = 2.4.dp.toPx()
        rotate(turn.value) {
            drawArc(
                color,
                startAngle = 0f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** Counts the rows of one open menu, so each can come in a moment after the one above it. */
class MenuStagger {
    private var next = 0

    fun take(): Int = next++
}

/** Given to the rows of an open menu; null: rows appear at once. */
val LocalMenuStagger = staticCompositionLocalOf<MenuStagger?> { null }

/** A menu row sliding in a moment after the row above it (very quick: 26 ms apart). */
@Composable
fun Modifier.staggeredIn(): Modifier {
    val stagger = LocalMenuStagger.current
    if (stagger == null || !extraMotionOn()) return this
    val index = remember { stagger.take() }
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(10) * 26L)
        shown.animateTo(1f, tween(170, easing = LinearOutSlowInEasing))
    }
    return graphicsLayer {
        alpha = shown.value
        translationY = (1f - shown.value) * 10.dp.toPx()
    }
}

/**
 * A short burst of confetti falling from the top each time [bursts] goes up (the first time every photo is backed
 * up). Drawn over the whole area it is given; touches pass straight through it.
 */
@Composable
fun Confetti(bursts: Int, modifier: Modifier = Modifier) {
    if (bursts <= 0 || !extraMotionOn()) return
    val progress = remember { Animatable(1f) }
    val pieces = remember(bursts) {
        val random = Random(bursts * 7919)
        List(90) {
            ConfettiPiece(
                x = random.nextFloat(),
                delay = random.nextFloat() * 0.25f,
                speed = 0.75f + random.nextFloat() * 0.6f,
                sway = (random.nextFloat() - 0.5f) * 0.25f,
                spin = (random.nextFloat() - 0.5f) * 1400f,
                size = 6f + random.nextFloat() * 6f,
                color = CONFETTI_COLOURS[random.nextInt(CONFETTI_COLOURS.size)],
            )
        }
    }
    LaunchedEffect(bursts) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(2400, easing = LinearEasing))
    }
    val t = progress.value
    if (t >= 1f) return
    Canvas(modifier) {
        for (piece in pieces) {
            val local = ((t - piece.delay) / (1f - piece.delay)).coerceIn(0f, 1f)
            if (local <= 0f) continue
            // Falling faster as it goes, drifting a little sideways, turning, and fading out near the end.
            val y = -0.05f + (local * local * 0.7f + local * 0.5f) * piece.speed
            val x = piece.x + piece.sway * sin(local * 6f)
            val w = piece.size.dp.toPx()
            val centre = Offset(x * size.width, y * size.height)
            rotate(piece.spin * local, centre) {
                drawRect(
                    piece.color,
                    topLeft = centre - Offset(w / 2f, w / 4f),
                    size = Size(w, w / 2f),
                    alpha = (1f - ((local - 0.75f) / 0.25f).coerceIn(0f, 1f)),
                )
            }
        }
    }
}

private class ConfettiPiece(val x: Float, val delay: Float, val speed: Float, val sway: Float, val spin: Float, val size: Float, val color: Color)

private val CONFETTI_COLOURS = listOf(
    Color(0xFFFF453A), Color(0xFFFF9F0A), Color(0xFFFFD60A), Color(0xFF30D158), Color(0xFF64D2FF), Color(0xFF0A84FF), Color(0xFFBF5AF2),
)
