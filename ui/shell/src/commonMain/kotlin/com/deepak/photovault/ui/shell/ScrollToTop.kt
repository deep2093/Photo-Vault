package com.deepak.photovault.ui.shell

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.withFrameNanos
import kotlin.math.exp

/**
 * Counts taps on the section that is already open. Each new value asks that section to scroll to the top
 * (feedback 22 Oct). 0 means "never asked".
 */
val LocalScrollToTop = compositionLocalOf { 0 }

/**
 * One continuous glide to the top (26 Oct; the earlier jump-then-animate paused near the top, and so did Compose's own
 * animateScrollToItem). Each frame moves a share of what is left, so it starts fast and eases to a stop exactly at the
 * top, however far away it began. A finger on the list cancels it.
 */
private suspend fun glideToTop(distanceLeft: () -> Float, scrollBy: suspend (Float) -> Float, atTop: () -> Boolean, screenPx: Float) {
    var last = withFrameNanos { it }
    while (!atTop()) {
        val now = withFrameNanos { it }
        val dt = ((now - last) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        last = now
        val left = distanceLeft()
        // Closing speed: 9 per second of what is left, never slower than a screen a second and never more than 3 screens a frame.
        val share = left * (1f - exp(-9f * dt))
        val step = share.coerceAtLeast(screenPx * dt).coerceAtMost(screenPx * 3f).coerceAtMost(left)
        if (step <= 0.5f) break
        val moved = scrollBy(-step)
        if (moved == 0f) break
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
        glideToTop(::distance, { state.scrollBy(it) }, { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0 }, screen)
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
        glideToTop(::distance, { state.scrollBy(it) }, { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0 }, screen)
        if (state.firstVisibleItemIndex != 0 || state.firstVisibleItemScrollOffset != 0) state.scrollToItem(0)
    }
}
