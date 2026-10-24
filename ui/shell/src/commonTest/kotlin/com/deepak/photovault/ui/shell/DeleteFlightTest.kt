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
}
