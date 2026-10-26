package com.deepak.photovault.ui.shell

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier

/**
 * Counts taps on the section that is already open. Each new value asks that section to scroll to the top
 * (feedback 22 Oct). 0 means "never asked".
 */
val LocalScrollToTop = compositionLocalOf { 0 }

/**
 * Scroll to top, the Samsung Gallery way (26 Oct, measured frame by frame from Deepak's recording of Samsung Gallery):
 *  1. If the top is far away, the list jumps in one frame to about [JUMP_SCREENS] screens below the top. No fade.
 *  2. It then scrolls up at a steady, quick speed ([SPEED] screens a second), slow enough for the grid to keep up.
 *  3. For the last stretch it slows down smoothly into the top over [EASE_MS] ms, with no change of speed where
 *     the steady part ends.
 * The position is re-measured every frame, so it ends exactly at the top. Same speed on 60 Hz and 120 Hz screens.
 */
private const val JUMP_SCREENS = 3.5f
private const val SPEED = 6f
private const val EASE_MS = 500f

private suspend fun glideToTop(
    distanceLeft: () -> Float,
    scrollBy: suspend (Float) -> Float,
    jumpNear: suspend (Float) -> Unit,
    atTop: () -> Boolean,
    screenPx: Float,
) {
    if (atTop()) return
    if (distanceLeft() > screenPx * JUMP_SCREENS) {
        jumpNear(screenPx * JUMP_SCREENS)
        withFrameNanos { }
    }
    val speed = screenPx * SPEED / 1000f // px per ms
    // Where the steady part hands over: easing out from this speed over EASE_MS covers exactly this distance.
    val handOver = speed * EASE_MS / 2f
    var last = withFrameNanos { it }
    while (!atTop() && distanceLeft() > handOver) {
        val now = withFrameNanos { it }
        val ms = ((now - last) / 1_000_000f).coerceIn(1f, 34f)
        last = now
        val step = (speed * ms).coerceAtMost(distanceLeft() - handOver + 1f)
        if (scrollBy(-step) == 0f) return
    }
    val start = distanceLeft()
    if (start <= 0.5f) return
    // A short trip eases out in less time, so it never starts faster than the steady speed.
    val easeMs = minOf(EASE_MS, 2f * start / speed).coerceAtLeast(220f)
    val begin = withFrameNanos { it }
    while (!atTop()) {
        val now = withFrameNanos { it }
        val t = ((now - begin) / 1_000_000f / easeMs).coerceIn(0f, 1f)
        if (t >= 1f) break
        val rest = 1f - t
        val step = distanceLeft() - start * rest * rest
        if (step > 0.5f && scrollBy(-step) == 0f) break
    }
}

@Composable
fun ScrollToTopEffect(state: LazyGridState): Modifier {
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
    return Modifier
}

@Composable
fun ScrollToTopEffect(state: LazyListState): Modifier {
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
    return Modifier
}
