package com.deepak.photovault.living

import org.junit.Assert.assertTrue
import org.junit.Test

class FillTest {
    @Test
    fun holeTakesTheColourAround() {
        val w = 20
        val h = 12
        val blue = 0xFF2040C0.toInt()
        val hole = BooleanArray(w * h) { i -> i % w in 6..13 && i / w in 3..8 }
        val px = IntArray(w * h) { if (hole[it]) 0xFFFF0000.toInt() else blue }
        val out = Fill.pushPull(px, hole, w, h)
        for (c in out) {
            assertTrue(kotlin.math.abs(((c shr 16) and 0xFF) - 0x20) <= 2)
            assertTrue(kotlin.math.abs(((c shr 8) and 0xFF) - 0x40) <= 2)
            assertTrue(kotlin.math.abs((c and 0xFF) - 0xC0) <= 2)
            assertTrue((c ushr 24) == 0xFF)
        }
    }

    @Test
    fun videoGoesThereAndBack() {
        assertTrue(LivingVideo.phase(0, 360) == 0f)
        assertTrue(LivingVideo.phase(180, 360) == 1f)
        assertTrue(LivingVideo.phase(90, 360) == LivingVideo.phase(270, 360))
    }
}
