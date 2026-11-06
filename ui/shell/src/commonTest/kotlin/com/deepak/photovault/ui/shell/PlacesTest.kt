package com.deepak.photovault.ui.shell

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlacesTest {
    private val melbourne = Place("Melbourne", "Victoria", "Australia")
    private val delhi = Place("New Delhi", "NCT", "India")

    private fun state(): PlacesState {
        val platform = object : PlacesPlatform {
            override suspend fun load() = PlacesSnapshot(
                mapOf(
                    1L to PhotoPlace(-37.8, 144.9, melbourne),
                    2L to PhotoPlace(-37.8, 144.9, melbourne),
                    3L to PhotoPlace(28.6, 77.2, delhi),
                    4L to PhotoPlace(0.5, 0.5, null),
                ),
            )
        }
        return PlacesState(platform)
    }

    @Test
    fun placesWithMostPhotosComeFirst() = runTest {
        val s = state()
        s.refresh()
        val list = s.places(setOf(1L, 2L, 3L, 4L))
        assertEquals(listOf(melbourne, delhi), list.map { it.first })
        assertEquals(setOf(1L, 2L), s.photosAt(melbourne.key))
        // Photos no longer shown don't count.
        assertEquals(listOf(delhi), s.places(setOf(3L)).map { it.first })
    }

    @Test
    fun searchFindsTownRegionAndCountry() = runTest {
        val s = state()
        s.refresh()
        assertEquals(setOf(1L, 2L), s.photosMatching("melb"))
        assertEquals(setOf(1L, 2L), s.photosMatching("Victoria"))
        assertEquals(setOf(3L), s.photosMatching("india"))
        assertTrue(s.photosMatching("me").isEmpty())
    }

    @Test
    fun labelsAndKeys() {
        assertEquals("Melbourne, Victoria", melbourne.label)
        assertEquals("Paris, France", Place("Paris", "", "France").label)
        assertEquals(melbourne, Place.fromKey(melbourne.key))
    }
}
