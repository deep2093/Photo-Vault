package com.deepak.photovault.ui.shell

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
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

/**
 * Where each photo item of a grid sits, in rows of tiles (17 Nov). In the mixed-size grid a big-photo block is ONE
 * grid item two rows tall, so counting items (4 to a row) made the distance to the top far too short: the glide
 * slowed down screens too early and crawled, which looked janky. The Memories row above the photos was not counted
 * either. [headerItems] grid items come before the first photo item.
 */
class GridRows(val headerItems: Int, private val rowsAbove: IntArray) {
    val photoItems: Int get() = rowsAbove.size

    /** Rows of tiles above photo item [k] (0 is the first photo item). */
    fun before(k: Int): Int = if (rowsAbove.isEmpty()) 0 else rowsAbove[k.coerceIn(0, rowsAbove.size - 1)]

    /** The last photo item that starts no more than [rows] rows down. */
    fun itemAtRow(rows: Float): Int {
        if (rowsAbove.isEmpty() || rows <= 0f) return 0
        var lo = 0
        var hi = rowsAbove.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (rowsAbove[mid] <= rows) lo = mid else hi = mid - 1
        }
        return lo
    }

    companion object {
        /** One tile per item, [columns] to a row. */
        fun plain(headerItems: Int, count: Int, columns: Int): GridRows {
            val c = columns.coerceAtLeast(1)
            return GridRows(headerItems, IntArray(count.coerceAtLeast(0)) { it / c })
        }

        /** The mixed-size grid: a run of tiles fills whole rows from a new line; a block is one item, two rows. */
        fun mosaic(headerItems: Int, parts: List<Mosaic.Part>, columns: Int): GridRows {
            val c = columns.coerceAtLeast(1)
            val out = ArrayList<Int>()
            var rows = 0
            for (part in parts) when (part) {
                is Mosaic.Run -> {
                    val n = part.to - part.from
                    for (k in 0 until n) out += rows + k / c
                    rows += (n + c - 1) / c
                }
                is Mosaic.Block -> {
                    out += rows
                    rows += 2
                }
            }
            return GridRows(headerItems, out.toIntArray())
        }
    }
}

@Composable
fun ScrollToTopEffect(state: LazyGridState, extraTaps: Int = 0, rows: GridRows? = null): Modifier {
    val signal = LocalScrollToTop.current
    // A page built again (coming back from a photo) must not take an old tap as a new one and jump to the top (30 Oct).
    val firstSignal = remember { signal }
    val firstTaps = remember { extraTaps }
    val currentRows by rememberUpdatedState(rows)
    // 20 Nov: with reduce motion the page goes straight to the top instead of gliding there.
    val still by rememberUpdatedState(com.deepak.photovault.designsystem.LocalGlassSettings.current.reduceMotion)
    // The height of everything above the first photo item (the Memories row and the like), measured whenever the
    // page is at the top, since it can't be worked out from row counts (17 Nov).
    val headerSpan = remember { floatArrayOf(-1f) }
    LaunchedEffect(state) {
        snapshotFlow {
            val r = currentRows
            if (r == null || state.firstVisibleItemIndex != 0) return@snapshotFlow null
            if (r.headerItems == 0) return@snapshotFlow 0f
            val visible = state.layoutInfo.visibleItemsInfo
            val top = visible.firstOrNull { it.index == 0 } ?: return@snapshotFlow null
            val photo = visible.firstOrNull { it.index == r.headerItems } ?: return@snapshotFlow null
            (photo.offset.y - top.offset.y).toFloat()
        }.collect { span -> if (span != null) headerSpan[0] = span }
    }
    LaunchedEffect(signal, extraTaps) {
        if (signal == firstSignal && extraTaps == firstTaps) return@LaunchedEffect
        if (still) {
            state.scrollToItem(0)
            return@LaunchedEffect
        }
        val info = { state.layoutInfo }
        // Rows above, from the tiles on screen: the smallest width is a tile, so a row is one of its height.
        fun guess(): Float {
            val visible = info().visibleItemsInfo
            if (visible.isEmpty()) return 0f
            val tileW = visible.minOf { it.size.width }.coerceAtLeast(1)
            val tileH = visible.filter { it.size.width == tileW }.map { it.size.height }.average().toFloat()
            val columns = (info().viewportSize.width / tileW.toFloat()).toInt().coerceAtLeast(1)
            return state.firstVisibleItemIndex / columns * tileH + state.firstVisibleItemScrollOffset
        }
        // With the grid's own row layout: the distance between two photo items on screen, in pixels per row.
        fun pitch(r: GridRows): Float? {
            val photos = info().visibleItemsInfo.filter { it.index >= r.headerItems && it.index < r.headerItems + r.photoItems }
            val a = photos.firstOrNull() ?: return null
            val b = photos.lastOrNull() ?: return null
            val dr = r.before(b.index - r.headerItems) - r.before(a.index - r.headerItems)
            return if (dr > 0) (b.offset.y - a.offset.y).toFloat() / dr else null
        }
        fun header(): Float = headerSpan[0].coerceAtLeast(0f)
        fun distance(): Float {
            val r = currentRows ?: return guess()
            val first = state.firstVisibleItemIndex
            val offset = state.firstVisibleItemScrollOffset.toFloat()
            if (first < r.headerItems) return offset + header() * first / r.headerItems.coerceAtLeast(1)
            val p = pitch(r) ?: return guess()
            return header() + r.before(first - r.headerItems) * p + offset
        }
        val screen = info().viewportSize.height.toFloat().coerceAtLeast(1f)
        glideToTop(
            ::distance,
            { info().visibleItemsInfo.firstOrNull { it.index == 0 }?.let { (-it.offset.y).toFloat().coerceAtLeast(0f) } },
            { state.scrollBy(it) },
            { target ->
                val r = currentRows
                val p = r?.let(::pitch)
                if (r != null && p != null && p > 0f) {
                    state.scrollToItem(r.headerItems + r.itemAtRow((target - header()) / p))
                } else {
                    val visible = info().visibleItemsInfo
                    val tileW = visible.minOf { it.size.width }.coerceAtLeast(1)
                    val tileH = visible.filter { it.size.width == tileW }.map { it.size.height }.average().toFloat().coerceAtLeast(1f)
                    val columns = (info().viewportSize.width / tileW.toFloat()).toInt().coerceAtLeast(1)
                    state.scrollToItem((target / tileH).toInt() * columns)
                }
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
    val firstSignal = remember { signal }
    val still by rememberUpdatedState(com.deepak.photovault.designsystem.LocalGlassSettings.current.reduceMotion)
    LaunchedEffect(signal) {
        if (signal == firstSignal) return@LaunchedEffect
        if (still) {
            state.scrollToItem(0)
            return@LaunchedEffect
        }
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
