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

    @Test
    fun slideGoesBothWaysFarMore() {
        val m = com.deepak.photovault.ui.shell.LivingMotion
        assertTrue(m.backgroundSlide(0f, 0.8f, 0f, false) < 0f)
        assertTrue(m.backgroundSlide(0f, 0.8f, 1f, false) > 0f)
        assertTrue(m.backgroundSlide(0.5f, 0.8f, 0.5f, false) == 0f)
        assertTrue(kotlin.math.abs(m.backgroundSlide(0f, 0.8f, 1f, false)) > kotlin.math.abs(m.backgroundSlide(0.9f, 0.8f, 1f, false)))
        // Never more than the extra width around the photo, so no edge shows.
        assertTrue(kotlin.math.abs(m.backgroundSlide(0f, 0.8f, 1f, false)) < (m.OVERSCAN - 1f) / 2f)
    }

    @Test
    fun mirrorCopiesTheBackgroundBeside() {
        val w = 10
        val h = 1
        // Left half red, right half blue, a hole in the middle.
        val px = IntArray(w) { if (it < 5) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        val hole = BooleanArray(w) { it in 4..5 }
        val out = Fill.mirror(px, hole, w, h)
        assertTrue(((out[4] shr 16) and 0xFF) > (out[4] and 0xFF))
        assertTrue((out[5] and 0xFF) > ((out[5] shr 16) and 0xFF))
    }
}
