package com.deepak.photovault.core.media

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditPixelsTest {
    private fun px(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun red(c: Int) = (c shr 16) and 0xFF

    @Test
    fun anUntouchedEditLeavesPixelsAlone() {
        val src = IntArray(16) { px(it * 10, 100, 200 - it * 5) }
        assertContentEquals(src, EditPixels.all(PhotoEdit(), src, 4, 4))
        assertFalse(PhotoEdit().needsPixels)
    }

    @Test
    fun shadowsLiftDarkTonesAndLeaveBlackAndWhite() {
        assertEquals(0f, EditPixels.tone(0f, 1f, 0f))
        assertEquals(1f, EditPixels.tone(1f, 1f, 0f))
        assertTrue(EditPixels.tone(0.25f, 1f, 0f) > 0.3f)
        assertTrue(EditPixels.tone(0.75f, 0f, -1f) < 0.72f)
        // Shadows hardly touch bright tones, highlights hardly touch dark ones.
        assertTrue(EditPixels.tone(0.9f, 1f, 0f) - 0.9f < 0.02f)
        assertTrue(0.1f - EditPixels.tone(0.1f, 0f, -1f) < 0.02f)
    }

    @Test
    fun vignetteDarkensCornersNotTheMiddle() {
        val w = 21
        val src = IntArray(w * w) { px(200, 200, 200) }
        val out = EditPixels.all(PhotoEdit(vignette = 100), src, w, w)
        assertEquals(200, red(out[10 * w + 10]))
        assertTrue(red(out[0]) < 100)
    }

    @Test
    fun seeThroughIsKept() {
        val src = IntArray(4) { px(120, 120, 120, a = 40) }
        val out = EditPixels.all(PhotoEdit(shadows = 50, grain = 50), src, 2, 2)
        assertTrue(out.all { (it ushr 24) == 40 })
    }

    @Test
    fun bandsGiveTheSameAsAllAtOnce() {
        val w = 9
        val h = 13
        val src = IntArray(w * h) { i -> px((i * 37) % 256, (i * 11) % 256, (i * 5) % 256) }
        val edit = PhotoEdit(sharpness = 60, highlights = -30, vignette = 40, grain = 20)
        val whole = EditPixels.all(edit, src, w, h)
        val halo = EditPixels.halo(w, h)
        val joined = IntArray(w * h)
        var y = 0
        while (y < h) {
            val rows = minOf(4, h - y)
            val above = minOf(halo, y)
            val below = minOf(halo, h - y - rows)
            val band = src.copyOfRange((y - above) * w, (y + rows + below) * w)
            EditPixels.rows(edit, band, w, h, rows, above, below, y).copyInto(joined, y * w)
            y += rows
        }
        assertContentEquals(whole, joined)
    }

    @Test
    fun filterStrengthMixesTheLook() {
        val half = PhotoEdit(filter = EditFilter.MONO, filterStrength = 50)
        val (r, g, _) = EditMath.apply(EditMath.colourMatrix(half), 200f, 40f, 90f)
        val (mr, mg, _) = EditMath.apply(EditMath.colourMatrix(half.copy(filterStrength = 100)), 200f, 40f, 90f)
        assertTrue(r in mr..200f && g in 40f..mg)
        assertFalse(PhotoEdit(filter = EditFilter.FILM, filterStrength = 0).hasColour)
        // Film brings its own grain, at its strength.
        assertEquals(0.35f, PhotoEdit(filter = EditFilter.FILM).grainAmount, 0.001f)
        assertEquals(0.175f, PhotoEdit(filter = EditFilter.FILM, filterStrength = 50).grainAmount, 0.001f)
    }

    @Test
    fun oneWaySlidersStopAtZero() {
        assertEquals(0, PhotoEdit().with(EditAdjust.VIGNETTE, -40).vignette)
        assertEquals(-40, PhotoEdit().with(EditAdjust.TINT, -40).tint)
        assertTrue(PhotoEdit(tint = 10).hasColour)
        assertTrue(PhotoEdit(fade = 10).hasColour && !PhotoEdit(fade = 10).needsPixels)
    }
}
