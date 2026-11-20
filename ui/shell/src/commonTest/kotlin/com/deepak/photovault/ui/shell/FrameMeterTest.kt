package com.deepak.photovault.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals

class FrameMeterTest {
    @Test
    fun smoothIsTheShareOfFramesOnTime() {
        assertEquals(97, smoothPercent(FrameCounts(frames = 100, slow = 3)))
        assertEquals(100, smoothPercent(FrameCounts()))
    }

    @Test
    fun theWorstScreensComeFirstAndEmptyOnesAreLeftOut() {
        val list = worstPlaces(
            mapOf(
                "Photo viewer" to FrameCounts(frames = 300, slow = 3),
                "Photos · All" to FrameCounts(frames = 600, slow = 30, frozen = 1),
                "Albums" to FrameCounts(),
            ),
        )
        assertEquals(listOf("Photos · All", "Photo viewer"), list.map { it.first })
    }
}
