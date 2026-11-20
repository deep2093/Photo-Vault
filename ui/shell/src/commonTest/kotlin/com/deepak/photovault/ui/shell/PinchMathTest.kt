package com.deepak.photovault.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PinchMathTest {
    @Test
    fun pinchingInLaysOutFewerColumnsOnlyOnceThePhotosHaveGrown() {
        assertEquals(4, PinchMath.columnsWhilePinching(3.5f, shown = 4, min = 2, max = 8))
        assertEquals(3, PinchMath.columnsWhilePinching(3.0f, shown = 4, min = 2, max = 8))
        // A fast pinch: the layout that still covers the screen, not one that would leave gaps.
        assertEquals(3, PinchMath.columnsWhilePinching(2.4f, shown = 4, min = 2, max = 8))
    }

    @Test
    fun pinchingOutAddsColumnsJustPastAStep() {
        assertEquals(4, PinchMath.columnsWhilePinching(4.03f, shown = 4, min = 2, max = 8))
        assertEquals(5, PinchMath.columnsWhilePinching(4.1f, shown = 4, min = 2, max = 8))
        assertEquals(6, PinchMath.columnsWhilePinching(5.5f, shown = 4, min = 2, max = 8))
        // Coming back the other way it doesn't flip straight back.
        assertEquals(5, PinchMath.columnsWhilePinching(4.05f, shown = 5, min = 2, max = 8))
    }

    @Test
    fun neverPastTheBiggestOrSmallestPhotos() {
        assertEquals(2, PinchMath.columnsWhilePinching(1.5f, shown = 2, min = 2, max = 8))
        assertEquals(8, PinchMath.columnsWhilePinching(9.5f, shown = 8, min = 2, max = 8))
        val range = PinchMath.zoomRange(4, min = 2, max = 8)
        assertTrue(4 / range.endInclusive >= 2 / PinchMath.MAX_STRETCH - 0.001f)
        assertTrue(4 / range.start <= 8 * PinchMath.MAX_SQUEEZE + 0.001f)
    }

    @Test
    fun lettingGoSettlesOnTheNearerSize() {
        assertEquals(4, PinchMath.columnsWhenLetGo(3.6f, shown = 4, min = 2, max = 8))
        assertEquals(3, PinchMath.columnsWhenLetGo(3.4f, shown = 4, min = 2, max = 8))
        assertEquals(4, PinchMath.columnsWhenLetGo(4.04f, shown = 4, min = 2, max = 8))
        assertEquals(2, PinchMath.columnsWhenLetGo(1.8f, shown = 2, min = 2, max = 8))
    }

    @Test
    fun theGridIsNeverDrawnSmallerThanTheScreenExceptPastTheSmallestPhotos() {
        assertEquals(1f, PinchMath.drawScale(4.04f, shown = 4, max = 8))
        assertEquals(1.25f, PinchMath.drawScale(4f, shown = 5, max = 8), 0.0001f)
        assertTrue(PinchMath.drawScale(8.5f, shown = 8, max = 8) < 1f)
    }

    @Test
    fun aGrownGridAlwaysCoversTheScreen() {
        // Drawn at 1.5x on a 1000 px wide screen, it can move between -500 and 0.
        assertEquals(0f, PinchMath.clamp(40f, 1.5f, 1000f))
        assertEquals(-500f, PinchMath.clamp(-800f, 1.5f, 1000f))
        assertEquals(-200f, PinchMath.clamp(-200f, 1.5f, 1000f))
        // Drawn smaller (past the smallest photos), it stays inside the screen.
        assertEquals(100f, PinchMath.clamp(300f, 0.9f, 1000f))
    }
}
