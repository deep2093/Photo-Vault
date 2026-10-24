package com.deepak.photovault.ui.shell

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Samsung-style drag selection (feedback 22 Oct): long-press a photo, then keep the finger down and slide.
 * Every photo between the first one and the finger is selected; sliding back un-selects again. If the first
 * photo was already selected, the slide un-selects instead.
 *
 * Pure function so it is unit-tested: [initial] is the selection before the long-press, [order] the photos in
 * screen order, and [anchor]/[current] are positions in [order].
 */
object DragSelect {
    fun selection(initial: Set<Long>, order: List<Long>, anchor: Int, current: Int, adding: Boolean): Set<Long> {
        if (anchor !in order.indices) return initial
        val end = current.coerceIn(order.indices)
        val range = order.subList(minOf(anchor, end), maxOf(anchor, end) + 1)
        return if (adding) initial + range else initial - range.toSet()
    }

    /** Auto-scroll speed in pixels per frame when the finger is near the top or bottom edge; 0 elsewhere. */
    fun edgeSpeed(y: Float, top: Float, bottom: Float, zone: Float, maxSpeed: Float): Float = when {
        y < top + zone -> -maxSpeed * ((top + zone - y) / zone).coerceIn(0f, 1f)
        y > bottom - zone -> maxSpeed * ((y - (bottom - zone)) / zone).coerceIn(0f, 1f)
        else -> 0f
    }
}

/** Callbacks from [photoGridGestures]. Photos are identified by MediaStore ID. */
@Stable
interface PhotoGridGestureHandler {
    /** The photo under [position], or null for headers, gaps and controls. */
    fun photoAt(position: Offset): Long?

    fun onTap(id: Long)

    /** Long-press landed on [id]: start a drag selection there. */
    fun onDragSelectStart(id: Long)

    /** The finger is now at [position] (also called while auto-scrolling). */
    fun onDragSelectMove(position: Offset)

    fun onDragSelectEnd()
}

private enum class PressResult { TAP, CANCEL }

/**
 * Tap to open, long-press to select, long-press-and-slide to select many. Runs before the grid's own
 * scrolling (Initial pass) so a drag selection can take over the finger; ordinary scrolling, flings and
 * two-finger pinches are left alone.
 */
fun Modifier.photoGridGestures(grid: LazyGridState, handler: PhotoGridGestureHandler): Modifier =
    selectGestures({ grid.isScrollInProgress }, handler)

/** The same gestures for any scrolling list (the Storage file pages, 23 Oct). */
fun Modifier.selectGestures(isScrolling: () -> Boolean, handler: PhotoGridGestureHandler): Modifier = pointerInput(handler) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // A tap that stops a fling should only stop it, never open a photo.
        val wasScrolling = isScrolling()
        val result = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } > 1) return@withTimeoutOrNull PressResult.CANCEL
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull PressResult.CANCEL
                // A release that arrives already consumed is Android cancelling the touch, e.g. when a swipe
                // from the screen edge turns into the system back gesture. It must not open the photo under the
                // finger (bug 22 Oct: back on a full album opened a photo, then closed it).
                if (!change.pressed) {
                    return@withTimeoutOrNull if (change.isConsumed) PressResult.CANCEL else PressResult.TAP
                }
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    return@withTimeoutOrNull PressResult.CANCEL
                }
            }
            @Suppress("UNREACHABLE_CODE")
            PressResult.CANCEL
        }
        when (result) {
            PressResult.TAP -> if (!wasScrolling) handler.photoAt(down.position)?.let(handler::onTap)
            PressResult.CANCEL -> Unit
            null -> {
                val anchor = handler.photoAt(down.position) ?: return@awaitEachGesture
                handler.onDragSelectStart(anchor)
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        // Take the finger from the grid, so it selects instead of scrolling.
                        change.consume()
                        handler.onDragSelectMove(change.position)
                    }
                } finally {
                    handler.onDragSelectEnd()
                }
            }
        }
    }
}
