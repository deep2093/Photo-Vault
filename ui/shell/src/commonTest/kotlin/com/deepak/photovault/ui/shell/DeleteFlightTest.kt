package com.deepak.photovault.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeleteFlightTest {
    @Test
    fun everyPhotoStartsWholeAndEndsInTheBin() {
        for (count in listOf(1, 3, 24)) {
            for (i in 0 until count) {
                assertEquals(0f, FlightMath.progress(0f, i, count))
                assertEquals(1f, FlightMath.progress(1f, i, count))
            }
        }
        assertEquals(1f, FlightMath.scaleX(0f))
        assertEquals(FlightMath.END_SCALE, FlightMath.scaleY(1f), 1e-4f)
        assertEquals(0f, FlightMath.alpha(1f))
        assertEquals(1f, FlightMath.travel(1f))
    }

    @Test
    fun laterPhotosFollowJustBehindAndItNarrowsBeforeItShortens() {
        assertTrue(FlightMath.progress(0.4f, 0, 5) > FlightMath.progress(0.4f, 4, 5))
        assertTrue(FlightMath.scaleX(0.3f) < FlightMath.scaleY(0.3f))
    }

    @Test
    fun aDeletedPhotoOffScreenSimplyDoesNotFly() {
        val spots = TileSpots()
        assertTrue(spots.boundsOf(listOf(1L)).isEmpty())
        assertTrue((null as TileSpots?).flying(emptyList()).isEmpty())
    }

    @Test
    fun settingsPagesSlideAndAboutComesLast() {
        assertTrue(Overlay.Settings(SettingsPage.ABOUT).slidesSideways())
        assertTrue(Overlay.Backup.slidesSideways())
        assertTrue(!Overlay.Search.slidesSideways())
        assertEquals(SettingsPage.ABOUT, SettingsPage.entries.last())
    }

    @Test
    fun aTapOnTheRingPicksTheSliceUnderTheFinger() {
        val halves = listOf(1L, 1L)
        assertEquals(0, DonutMath.sliceAt(0f, -10f, halves)) // top
        assertEquals(0, DonutMath.sliceAt(10f, 0f, halves)) // right: first half (clockwise)
        assertEquals(1, DonutMath.sliceAt(-10f, 0f, halves)) // left
        assertEquals(null, DonutMath.sliceAt(1f, 1f, listOf(0L)))
    }

    private fun item(id: Long, video: Boolean = false) = com.deepak.photovault.core.media.MediaItem(
        id = id, uri = "content://$id", type = if (video) com.deepak.photovault.core.media.MediaType.VIDEO else com.deepak.photovault.core.media.MediaType.PHOTO,
        displayName = "f$id", takenAtMillis = 0, sizeBytes = 1, width = 1, height = 1, albumId = "a", albumName = "A",
        mimeType = if (video) "video/mp4" else "image/jpeg",
    )

    @Test
    fun deletePopUpWords() {
        assertEquals("Move this photo to the bin?", DeleteText.trash(listOf(item(1))))
        assertEquals("Move this video to the bin?", DeleteText.trash(listOf(item(1, video = true))))
        assertEquals("Move these 5 photos to the bin?", DeleteText.trash((1L..5L).map { item(it) }))
        assertEquals("Move these 2 items to the bin?", DeleteText.trash(listOf(item(1), item(2, video = true))))
        assertEquals("Delete this photo for good?", DeleteText.forever(listOf(item(1))))
    }

    @Test
    fun genieStartsWholePinchesFromTheBottomAndEndsInTheBin() {
        val area = androidx.compose.ui.geometry.Rect(0f, 0f, 280f, 280f)
        val bin = androidx.compose.ui.geometry.Offset(300f, 900f)
        val first = GenieMath.strip(area, 0, 0f, bin, 12f)!!
        assertEquals(280f, first.width, 0.01f)
        // Half way, the bottom strip is further along (narrower and lower) than the top one.
        val top = GenieMath.strip(area, 0, 0.4f, bin, 12f)!!
        val bottom = GenieMath.strip(area, GenieMath.STRIPS - 1, 0.4f, bin, 12f)!!
        assertTrue(bottom.width < top.width)
        assertEquals(null, GenieMath.strip(area, GenieMath.STRIPS - 1, 1f, bin, 12f))
        // One smooth piece: each strip starts where the one above ends (no gaps).
        for (i in 0 until GenieMath.STRIPS - 1) {
            val a1 = GenieMath.strip(area, i, 0.3f, bin, 12f) ?: continue
            val b1 = GenieMath.strip(area, i + 1, 0.3f, bin, 12f) ?: continue
            assertEquals(a1.bottom - 0.75f, b1.top, 0.01f)
        }
        // A wide photo in the viewer is fitted, a grid tile is filled.
        val fitted = GenieMath.imageRect(androidx.compose.ui.geometry.Rect(0f, 0f, 100f, 200f), androidx.compose.ui.geometry.Size(400f, 200f), fit = true)
        assertEquals(100f, fitted.width, 0.01f)
        assertEquals(50f, fitted.height, 0.01f)
    }
}
