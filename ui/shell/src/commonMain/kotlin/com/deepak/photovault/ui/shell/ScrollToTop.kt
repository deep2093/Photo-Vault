package com.deepak.photovault.ui.shell

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.withFrameNanos

/**
 * Counts taps on the section that is already open. Each new value asks that section to scroll to the top
 * (feedback 22 Oct). 0 means "never asked".
 */
val LocalScrollToTop = compositionLocalOf { 0 }

/**
 * One smooth glide to the top (26 Oct, third version). The earlier ones scrolled thousands of pixels a frame, so every
 * frame had to build and load whole screens of new rows, and that was the jank. Now: if the top is far away, the list
 * first jumps to about [NEAR] screens below the top in a single frame (it is moving too fast to read anyway), then
 * glides the rest over a fixed time, fastest at the start and slowing into the top. The position is re-measured each
 * frame, so it still ends exactly at the top.
 */
private const val NEAR = 4f
private const val GLIDE_MS = 520f

private suspend fun glideToTop(
    distanceLeft: () -> Float,
    scrollBy: suspend (Float) -> Float,
    jumpNear: suspend (Float) -> Unit,
    atTop: () -> Boolean,
    screenPx: Float,
) {
    if (atTop()) return
    if (distanceLeft() > screenPx * NEAR) {
        jumpNear(screenPx * NEAR)
        withFrameNanos { }
    }
    val start = distanceLeft()
    if (start <= 0.5f) return
    val begin = withFrameNanos { it }
    while (!atTop()) {
        val now = withFrameNanos { it }
        val t = ((now - begin) / 1_000_000f / GLIDE_MS).coerceIn(0f, 1f)
        if (t >= 1f) break
        val rest = 1f - t
        val wanted = start * rest * rest * rest
        val step = distanceLeft() - wanted
        if (step > 0.5f && scrollBy(-step) == 0f) break
    }
}

@Composable
fun ScrollToTopEffect(state: LazyGridState) {
    val signal = LocalScrollToTop.current
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
        )
        // Whatever the estimate left over (headers are not tile-sized) is a few pixels.
        if (state.firstVisibleItemIndex != 0 || state.firstVisibleItemScrollOffset != 0) state.scrollToItem(0)
    }
}

@Composable
fun ScrollToTopEffect(state: LazyListState) {
    val signal = LocalScrollToTop.current
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
        )
        if (state.firstVisibleItemIndex != 0 || state.firstVisibleItemScrollOffset != 0) state.scrollToItem(0)
    }
}
