package com.deepak.photovault.ui.shell

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Counts taps on the section that is already open. Each new value asks that section to scroll to the top
 * (feedback 22 Oct). 0 means "never asked".
 */
val LocalScrollToTop = compositionLocalOf { 0 }

/**
 * Scroll to top, fourth version (26 Oct). Measured on Deepak's video: the glide stuttered at the start because it moved
 * ~500 px a frame and the phone had to build whole rows of new photos that fast. Now it never moves faster than the
 * grid can keep up with:
 *  - Within [NEAR] screens of the top: one gentle glide all the way.
 *  - Further down: the list fades out quickly, moves to one screen below the top while faded (so the jump is never
 *    seen), then fades back in while gliding into the top. One quick, continuous movement.
 * [fade] is the list's opacity; the screen applies it. Position is re-measured every frame so it ends exactly at the top.
 */
private const val NEAR = 3f
private const val GLIDE_MS = 420f
private const val FADE_MS = 110

private suspend fun glideToTop(
    distanceLeft: () -> Float,
    scrollBy: suspend (Float) -> Float,
    jumpNear: suspend (Float) -> Unit,
    atTop: () -> Boolean,
    screenPx: Float,
    fade: Animatable<Float, *>,
) {
    if (atTop()) return
    var far = false
    if (distanceLeft() > screenPx * NEAR) {
        far = true
        fade.animateTo(0f, tween(FADE_MS, easing = LinearEasing))
        jumpNear(screenPx)
        withFrameNanos { }
    }
    val start = distanceLeft()
    if (start <= 0.5f) { fade.snapTo(1f); return }
    val glideMs = if (far) GLIDE_MS else GLIDE_MS + 140f
    coroutineScope {
        if (far) launch { fade.animateTo(1f, tween(FADE_MS * 2, easing = LinearEasing)) }
        val begin = withFrameNanos { it }
        while (!atTop()) {
            val now = withFrameNanos { it }
            val t = ((now - begin) / 1_000_000f / glideMs).coerceIn(0f, 1f)
            if (t >= 1f) break
            val rest = 1f - t
            val wanted = start * rest * rest * rest
            val step = distanceLeft() - wanted
            if (step > 0.5f && scrollBy(-step) == 0f) break
        }
    }
    fade.snapTo(1f)
}

@Composable
fun ScrollToTopEffect(state: LazyGridState): Modifier {
    val signal = LocalScrollToTop.current
    val fade = remember { Animatable(1f) }
    LaunchedEffect(signal) {
        if (signal == 0) return@LaunchedEffect
        val info = { state.layoutInfo }
        // Rows above, from the tiles on screen: the smallest width is a tile, so a row is one of its height.
        fun distance(): Float {
            val visible = info().visibleItemsInfo
            if (visible.isEmpty()) return 0f
            val tileW = visible.minOf { it.size.width }.coerceAtLeast(1)
            val tileH = visible.filter { it.size.width == tileW }.map { it.size.height }.average().toFloat()
            val columns = (info().viewportSize.width / tileW.toFloat()).toInt().coerceAtLeast(1)
            return state.firstVisibleItemIndex / columns * tileH + state.firstVisibleItemScrollOffset
        }
        val screen = info().viewportSize.height.toFloat().coerceAtLeast(1f)
        glideToTop(
            ::distance,
            { state.scrollBy(it) },
            { target ->
                val visible = info().visibleItemsInfo
                val tileW = visible.minOf { it.size.width }.coerceAtLeast(1)
                val tileH = visible.filter { it.size.width == tileW }.map { it.size.height }.average().toFloat().coerceAtLeast(1f)
                val columns = (info().viewportSize.width / tileW.toFloat()).toInt().coerceAtLeast(1)
                state.scrollToItem((target / tileH).toInt() * columns)
            },
            { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0 },
            screen,
            fade,
        )
        // Whatever the estimate left over (headers are not tile-sized) is a few pixels.
        if (state.firstVisibleItemIndex != 0 || state.firstVisibleItemScrollOffset != 0) state.scrollToItem(0)
    }
    return Modifier.graphicsLayer { alpha = fade.value }
}

@Composable
fun ScrollToTopEffect(state: LazyListState): Modifier {
    val signal = LocalScrollToTop.current
    val fade = remember { Animatable(1f) }
    LaunchedEffect(signal) {
        if (signal == 0) return@LaunchedEffect
        fun distance(): Float {
            val visible = state.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) return 0f
            return state.firstVisibleItemIndex * visible.map { it.size }.average().toFloat() + state.firstVisibleItemScrollOffset
        }
        val screen = state.layoutInfo.viewportSize.height.toFloat().coerceAtLeast(1f)
        glideToTop(
            ::distance,
            { state.scrollBy(it) },
            { target ->
                val visible = state.layoutInfo.visibleItemsInfo
                state.scrollToItem((target / visible.map { it.size }.average().toFloat().coerceAtLeast(1f)).toInt())
            },
            { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0 },
            screen,
            fade,
        )
        if (state.firstVisibleItemIndex != 0 || state.firstVisibleItemScrollOffset != 0) state.scrollToItem(0)
    }
    return Modifier.graphicsLayer { alpha = fade.value }
}
