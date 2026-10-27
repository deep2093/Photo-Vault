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
 * Scroll to top, sixth version (27 Oct). Deepak: it felt like hitting a wall instead of stopping before it.
 * The earlier slow-down was planned for a fixed distance, so when the real distance turned out longer it sped up
 * to finish on time. Now the speed depends only on how far is LEFT: speed = distance left / [TAU_MS], never above
 * the steady speed and never increasing. Far away it stays at the steady speed; as the top gets close it eases off
 * more and more, however far the top really is, and it can never arrive fast.
 *  1. From far down it jumps in one frame to about [JUMP_SCREENS] screens below the top.
 *  2. It scrolls up at the steady speed ([SPEED] screens a second).
 *  3. When the distance left is less than the steady speed times [TAU_MS], speed follows the distance left.
 * The distance is a guess until the first item is on screen, then it is measured exactly.
 */
private const val JUMP_SCREENS = 3.5f
private const val SPEED = 6f
private const val TAU_MS = 380f
/** Never slower than this (screens a second) until the last pixels, so it doesn't crawl at the end. */
private const val CRAWL = 0.35f

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
    val cruise = screenPx * SPEED / 1000f // px per ms
    val crawl = screenPx * CRAWL / 1000f
    var speed = cruise
    var last = withFrameNanos { it }
    while (!atTop()) {
        val now = withFrameNanos { it }
        val ms = ((now - last) / 1_000_000f).coerceIn(1f, 34f)
        last = now
        val left = exactLeft() ?: distanceLeft()
        if (left <= 0.5f) break
        // Speed follows the distance left, and only ever goes down.
        speed = minOf(speed, maxOf(left / TAU_MS, crawl), cruise)
        val step = minOf(speed * ms, left)
        if (scrollBy(-step) == 0f) break
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
