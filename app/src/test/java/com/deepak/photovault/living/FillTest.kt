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
    fun backgroundNeverGrowsPastTheSubject() {
        for (y in listOf(0f, 0.3f, 0.7f, 1f)) for (t in listOf(0f, 0.5f, 1f)) {
            val s = com.deepak.photovault.ui.shell.LivingMotion.backgroundScale(y, 0.8f, t, false)
            assertTrue(s <= 1f && s > 0.9f)
        }
        // Far (top) changes more than near (where the person stands).
        val far = com.deepak.photovault.ui.shell.LivingMotion.backgroundScale(0f, 0.8f, 0f, false)
        val near = com.deepak.photovault.ui.shell.LivingMotion.backgroundScale(0.9f, 0.8f, 0f, false)
        assertTrue(far < near)
    }
}
