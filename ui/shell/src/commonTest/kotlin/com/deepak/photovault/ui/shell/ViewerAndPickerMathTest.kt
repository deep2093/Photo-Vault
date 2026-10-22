package com.deepak.photovault.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals

class ViewerAndPickerMathTest {
    @Test
    fun scrubberMapsTheStripToTheVideoBothWays() {
        assertEquals(11, ScrubberMath.frameCount(11_000))
        assertEquals(8, ScrubberMath.frameCount(2_000))
        assertEquals(60, ScrubberMath.frameCount(3_600_000))
        assertEquals(5_500L, ScrubberMath.timeAt(500f, 1000f, 11_000))
        assertEquals(500f, ScrubberMath.offsetAt(5_500, 1000f, 11_000), 0.01f)
        // Never past either end.
        assertEquals(11_000L, ScrubberMath.timeAt(5000f, 1000f, 11_000))
        assertEquals(0L, ScrubberMath.timeAt(-20f, 1000f, 11_000))
    }

    @Test
    fun wheelTimeTurnsTwelveHourIntoTwentyFour() {
        assertEquals(0, WheelTime.to24(12, pm = false))
        assertEquals(2, WheelTime.to24(2, pm = false))
        assertEquals(12, WheelTime.to24(12, pm = true))
        assertEquals(18, WheelTime.to24(6, pm = true))
    }
}
