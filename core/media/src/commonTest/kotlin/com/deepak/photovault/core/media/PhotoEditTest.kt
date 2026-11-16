package com.deepak.photovault.core.media

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The photo editor's sums (Phase 11): turning, mirroring, the crop frame and colour. */
class PhotoEditTest {
    private fun near(a: Float, b: Float, by: Float = 0.001f) = assertTrue(abs(a - b) <= by, "$a is not $b")

    private fun near(a: CropBox, b: CropBox) {
        near(a.left, b.left); near(a.top, b.top); near(a.right, b.right); near(a.bottom, b.bottom)
    }

    @Test
    fun aNewEditChangesNothing() {
        val edit = PhotoEdit()
        assertTrue(edit.isUnchanged)
        assertTrue(EditMath.colourMatrix(edit).contentEquals(EditMath.identity()))
        assertFalse(edit.copy(brightness = 1).isUnchanged)
        assertFalse(edit.copy(filter = EditFilter.MONO).isUnchanged)
        assertFalse(edit.turnedRight().isUnchanged)
        assertFalse(edit.copy(crop = CropBox(0.1f, 0f, 1f, 1f)).isUnchanged)
    }

    @Test
    fun fourQuarterTurnsComeBackToTheStart() {
        val start = PhotoEdit(crop = CropBox(0.1f, 0.2f, 0.6f, 0.9f))
        var e = start
        repeat(4) { e = e.turnedRight() }
        assertEquals(0, e.turns)
        near(e.crop, start.crop)
        near(start.turnedRight().turnedLeft().crop, start.crop)
        assertEquals(0, start.turnedRight().turnedLeft().turns)
    }

    @Test
    fun theCropFrameTurnsWithThePicture() {
        // A frame hugging the top-left corner ends up top-right after a clockwise turn.
        val e = PhotoEdit(crop = CropBox(0f, 0f, 0.5f, 0.25f)).turnedRight()
        near(e.crop, CropBox(0.75f, 0f, 1f, 0.5f))
        assertTrue(e.sideways)
    }

    @Test
    fun mirroringTwiceIsNoMirror() {
        val start = PhotoEdit(crop = CropBox(0.1f, 0.2f, 0.6f, 0.9f))
        val once = start.mirrored()
        assertTrue(once.flipped)
        near(once.crop, CropBox(0.4f, 0.2f, 0.9f, 0.9f))
        val twice = once.mirrored()
        assertFalse(twice.flipped)
        near(twice.crop, start.crop)
    }

    @Test
    fun aMirroredPictureTurnsTheOtherWayUnderneath() {
        // Seen turning clockwise, a mirrored picture's own turn count goes backwards.
        assertEquals(3, PhotoEdit(flipped = true).turnedRight().turns)
        assertEquals(1, PhotoEdit(flipped = true).turnedLeft().turns)
        assertEquals(1, PhotoEdit().turnedRight().turns)
    }

    @Test
    fun monoMakesEveryColourGrey() {
        val (r, g, b) = EditMath.apply(EditMath.filterMatrix(EditFilter.MONO), 200f, 40f, 90f)
        near(r, g, 0.01f)
        near(g, b, 0.01f)
    }

    @Test
    fun slidersMoveColourTheRightWay() {
        val (r0, g0, b0) = Triple(120f, 100f, 80f)
        val brighter = EditMath.apply(EditMath.colourMatrix(PhotoEdit(brightness = 50)), r0, g0, b0)
        assertTrue(brighter.first > r0 && brighter.second > g0 && brighter.third > b0)
        val warmer = EditMath.apply(EditMath.colourMatrix(PhotoEdit(warmth = 100)), r0, g0, b0)
        assertTrue(warmer.first > r0 && warmer.third < b0)
        val grey = EditMath.apply(EditMath.colourMatrix(PhotoEdit(saturation = -100)), r0, g0, b0)
        near(grey.first, grey.third, 0.01f)
        // More contrast pushes a dark colour darker and a light one lighter; mid grey stays.
        val punchy = EditMath.colourMatrix(PhotoEdit(contrast = 100))
        assertTrue(EditMath.apply(punchy, 40f, 40f, 40f).first < 40f)
        assertTrue(EditMath.apply(punchy, 220f, 220f, 220f).first > 220f)
        near(EditMath.apply(punchy, 127.5f, 127.5f, 127.5f).first, 127.5f, 0.01f)
    }

    @Test
    fun matricesJoinInOrder() {
        // Grey first, then warmer: the answer is tinted. Warmer first, then grey: it is plain grey.
        val grey = EditMath.saturationMatrix(0f)
        val warm = EditMath.scaleMatrix(1.2f, 1f, 0.8f)
        val tinted = EditMath.apply(EditMath.concat(warm, grey), 100f, 100f, 100f)
        assertTrue(tinted.first > tinted.third)
        val plain = EditMath.apply(EditMath.concat(grey, warm), 100f, 100f, 100f)
        near(plain.first, plain.third, 0.01f)
    }

    @Test
    fun sliderValuesStayInRange() {
        assertEquals(100, PhotoEdit().with(EditAdjust.CONTRAST, 400).contrast)
        assertEquals(-100, PhotoEdit().with(EditAdjust.WARMTH, -400).warmth)
        assertEquals(25, PhotoEdit().with(EditAdjust.SATURATION, 25).value(EditAdjust.SATURATION))
    }

    @Test
    fun movingStopsAtTheEdges() {
        val box = CropBox(0.2f, 0.2f, 0.6f, 0.7f)
        near(EditMath.move(box, 0.1f, 0.1f), CropBox(0.3f, 0.3f, 0.7f, 0.8f))
        near(EditMath.move(box, 5f, -5f), CropBox(0.6f, 0f, 1f, 0.5f))
    }

    @Test
    fun draggingACornerKeepsTheOppositeOne() {
        val box = CropBox(0.2f, 0.2f, 0.8f, 0.8f)
        near(EditMath.resize(box, CropCorner.BOTTOM_RIGHT, 0.9f, 0.6f), CropBox(0.2f, 0.2f, 0.9f, 0.6f))
        near(EditMath.resize(box, CropCorner.TOP_LEFT, 0.4f, 0.1f), CropBox(0.4f, 0.1f, 0.8f, 0.8f))
        // Dragged past the picture: it stops at the edge. Dragged through itself: it stops at the smallest size.
        near(EditMath.resize(box, CropCorner.BOTTOM_RIGHT, 3f, 3f), CropBox(0.2f, 0.2f, 1f, 1f))
        val tiny = EditMath.resize(box, CropCorner.BOTTOM_RIGHT, 0f, 0f)
        near(tiny.width, EditMath.MIN_SIDE)
        near(tiny.height, EditMath.MIN_SIDE)
    }

    @Test
    fun aLockedShapeIsKeptWhileDragging() {
        val box = CropBox(0.1f, 0.1f, 0.5f, 0.5f)
        val wide = EditMath.resize(box, CropCorner.BOTTOM_RIGHT, 0.9f, 0.2f, ratio = 2f)
        near(wide.width / wide.height, 2f)
        near(wide.left, 0.1f)
        near(wide.top, 0.1f)
        // No room below for the full height: the frame shrinks to what fits and keeps its shape.
        val low = EditMath.resize(CropBox(0.1f, 0.7f, 0.3f, 0.8f), CropCorner.BOTTOM_RIGHT, 1f, 1f, ratio = 1f)
        near(low.width / low.height, 1f)
        assertTrue(low.bottom <= 1.0001f && low.right <= 1.0001f)
    }

    @Test
    fun aSquareOnAWidePictureIsNarrowInFractions() {
        // 4000 x 2000 picture: a square crop covers half the width and all the height.
        val square = EditMath.fit(CropBox(), 1f, 4000, 2000)
        near(square, CropBox(0.25f, 0f, 0.75f, 1f))
        val px = EditMath.pixels(square, 4000, 2000)
        assertEquals(listOf(1000, 0, 2000, 2000), px.toList())
        near(EditMath.boxRatio(1f, 4000, 2000), 0.5f)
    }

    @Test
    fun pixelsAreNeverEmptyOrOutside() {
        val px = EditMath.pixels(CropBox(0.9999f, 0.9999f, 1f, 1f), 10, 10)
        assertTrue(px[2] >= 1 && px[3] >= 1)
        assertTrue(px[0] + px[2] <= 10 && px[1] + px[3] <= 10)
        assertEquals(listOf(0, 0, 10, 10), EditMath.pixels(CropBox(), 10, 10).toList())
    }

    @Test
    fun cornersAreFoundByTouch() {
        val box = CropBox(0.2f, 0.2f, 0.8f, 0.8f)
        assertEquals(CropCorner.TOP_LEFT, EditMath.cornerNear(box, 0.22f, 0.18f, 0.05f, 0.05f))
        assertEquals(CropCorner.BOTTOM_RIGHT, EditMath.cornerNear(box, 0.83f, 0.79f, 0.05f, 0.05f))
        assertNull(EditMath.cornerNear(box, 0.5f, 0.5f, 0.05f, 0.05f))
    }

    @Test
    fun clampKeepsAFrameUsable() {
        val box = EditMath.clamp(CropBox(-0.2f, 0.95f, 0.01f, 1.4f))
        assertTrue(box.left >= 0f && box.bottom <= 1.0001f)
        assertTrue(box.width >= EditMath.MIN_SIDE - 0.0001f && box.height >= EditMath.MIN_SIDE - 0.0001f)
    }

    @Test
    fun theCopyGetsItsOwnName() {
        assertEquals("IMG_1234_edited.jpg", EditMath.copyName("IMG_1234.heic"))
        assertEquals("IMG_1234_edited.jpg", EditMath.copyName("IMG_1234_edited.jpg"))
        assertEquals("Photo_edited.jpg", EditMath.copyName(""))
        assertEquals("holiday_edited.jpg", EditMath.copyName("holiday"))
    }

    // ---- Straighten (16 Nov) ----

    @Test
    fun straightenScaleIsOneFlatAndCoversWhenTilted() {
        near(1f, EditMath.straightenScale(0f, 4000, 3000))
        // 10 degrees on a 4:3 picture: cos 10 + sin 10 x 4/3.
        near(0.98481f + 0.17365f * 4f / 3f, EditMath.straightenScale(10f, 4000, 3000))
        // Either way and either shape give the same enlargement.
        near(EditMath.straightenScale(10f, 4000, 3000), EditMath.straightenScale(-10f, 3000, 4000))
        near(1f, EditMath.straightenScale(5f, 0, 100))
    }

    @Test
    fun straightenIsKeptInRangeAndCountsAsAChange() {
        assertEquals(45f, PhotoEdit().straightened(80f).straighten)
        assertEquals(-2.5f, PhotoEdit().straightened(-2.54f).straighten)
        assertFalse(PhotoEdit().straightened(1.5f).isUnchanged)
        assertTrue(PhotoEdit().straightened(0.02f).isUnchanged)
    }

    @Test
    fun mirroringTurnsTheTiltTheOtherWayAndTurningKeepsIt() {
        val tilted = PhotoEdit().straightened(3f)
        assertEquals(-3f, tilted.mirrored().straighten)
        assertEquals(3f, tilted.turnedRight().straighten)
        assertEquals(3f, tilted.turnedLeft().straighten)
        assertEquals(0f, tilted.colourOnly().straighten)
    }
}
