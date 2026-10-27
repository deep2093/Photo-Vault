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
 * Scroll to top, the Samsung Gallery way, fifth version (27 Oct):
 *  1. From far down it jumps in one frame to about [JUMP_SCREENS] screens below the top.
 *  2. It scrolls up at a steady speed ([SPEED] screens a second).
 *  3. It slows down in a straight line to a stop over [EASE_MS] ms, starting at exactly the steady speed.
 * The distance is a guess (rows are not all the same height) until the first item is on screen; from then on it is
 * measured exactly, so the slow-down is corrected smoothly and ends exactly at the top, with no last-moment snap.
 */
private const val JUMP_SCREENS = 3.5f
private const val SPEED = 6f
private const val EASE_MS = 700f

private suspend fun glideToTop(
    distanceLeft: () -> Float,
    exactLeft: () -> Float?,
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
    // Slowing from [speed] to nothing in EASE_MS covers speed * EASE_MS / 2.
    val handOver = speed * EASE_MS / 2f
    var last = withFrameNanos { it }
    while (!atTop() && (exactLeft() ?: distanceLeft()) > handOver) {
        val now = withFrameNanos { it }
        val ms = ((now - last) / 1_000_000f).coerceIn(1f, 34f)
        last = now
        val step = (speed * ms).coerceAtMost((exactLeft() ?: distanceLeft()) - handOver + 1f)
        if (scrollBy(-step) == 0f) return
    }
    if (atTop()) return
    // A short trip slows down in less time, but never starts faster than the steady speed.
    val start = exactLeft() ?: distanceLeft()
    val total = minOf(EASE_MS, 2f * start / speed).coerceAtLeast(200f)
    var v = 2f * start / total // px per ms at the start of the slow-down
    var begin = withFrameNanos { it }
    last = begin
    while (!atTop()) {
        val now = withFrameNanos { it }
        val ms = ((now - last) / 1_000_000f).coerceIn(1f, 34f)
        last = now
        val t = (now - begin) / 1_000_000f
        val timeLeft = total - t
        val step = run {
            val exact = exactLeft()
            if (exact != null) {
                // Measured: finish exactly in the time left, speed changing smoothly.
                if (timeLeft <= ms) exact else minOf(exact, 2f * exact / timeLeft * ms)
            } else {
                // Still a guess: follow the planned straight-line slow-down.
                v * (1f - t / total).coerceAtLeast(0f) * ms
            }
        }
        if (step <= 0.01f) { if (timeLeft <= 0f) break else continue }
        if (scrollBy(-step) == 0f) break
        if (timeLeft <= 0f && exactLeft() == null) break
    }
}

@Composable
fun ScrollToTopEffect(state: LazyGridState, extraTaps: Int = 0): Modifier {
    val signal = LocalScrollToTop.current
    LaunchedEffect(signal, extraTaps) {
        if (signal == 0 && extraTaps == 0) return@LaunchedEffect
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
            { info().visibleItemsInfo.firstOrNull { it.index == 0 }?.let { (-it.offset.y).toFloat().coerceAtLeast(0f) } },
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
            { state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }?.let { (-it.offset).toFloat().coerceAtLeast(0f) } },
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
