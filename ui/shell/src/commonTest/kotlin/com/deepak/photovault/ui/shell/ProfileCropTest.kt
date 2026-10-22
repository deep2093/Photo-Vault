package com.deepak.photovault.ui.shell

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileCropTest {
    @Test
    fun unzoomedLandscapeTakesTheMiddleSquare() {
        val (left, top, side) = ProfileCrop.square(2000f, 1000f, 500f, 1f, Offset.Zero)!!
        assertEquals(500f, left, 0.5f)
        assertEquals(0f, top, 0.5f)
        assertEquals(1000f, side, 0.5f)
    }

    @Test
    fun zoomingInTakesASmallerSquareAndDraggingMovesIt() {
        // Zoom 2x: the circle shows half the short side. Drag right by 100 px on screen = left in the photo.
        val (left, top, side) = ProfileCrop.square(1000f, 1000f, 500f, 2f, Offset(100f, 0f))!!
        assertEquals(500f, side, 0.5f)
        assertEquals(250f - 100f, left, 0.5f)
        assertEquals(250f, top, 0.5f)
    }

    @Test
    fun cannotDragAnEdgeIntoTheCircle() {
        val clamped = ProfileCrop.clamp(Offset(9999f, -9999f), 2000f, 1000f, 500f, 1f)
        // Landscape at zoom 1: 1000 px of room sideways, none up or down.
        assertEquals(250f, clamped.x, 0.5f)
        assertEquals(0f, clamped.y, 0.5f)
    }
}
