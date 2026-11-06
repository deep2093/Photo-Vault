package com.deepak.photovault.ui.shell

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlacesMapTest {
    private val melbourne = Place("Melbourne", "Victoria", "Australia")
    private val sydney = Place("Sydney", "New South Wales", "Australia")
    private val delhi = Place("New Delhi", "NCT", "India")
    private val day = 86_400_000L

    /** The whole world on a 1000 x 1000 screen, straight lines of latitude and longitude. */
    private fun world(zoom: Double) = MapViewport(zoom, 90.0, -90.0, -180.0, 180.0, 1000f, 1000f) { lat, lon ->
        Offset(((lon + 180) / 360 * 1000).toFloat(), ((90 - lat) / 180 * 1000).toFloat())
    }

    private val points = listOf(
        MapPoint(1, -37.81, 144.96, 1 * day, melbourne),
        MapPoint(2, -37.82, 144.97, 2 * day, melbourne),
        MapPoint(3, -33.87, 151.21, 3 * day, sydney),
        MapPoint(4, 28.61, 77.21, 4 * day, delhi),
    )

    @Test
    fun farOutOneBubblePerCountry() {
        val b = MapClusters.bubbles(points, world(2.0), 72f)
        assertEquals(setOf("Australia", "India"), b.mapNotNull { it.label }.toSet())
        assertEquals(3, b.first { it.label == "Australia" }.ids.size)
        // The newest photo is the cover.
        assertEquals(3L, b.first { it.label == "Australia" }.cover)
    }

    @Test
    fun thenOneBubblePerTown() {
        val b = MapClusters.bubbles(points, world(6.0), 72f)
        assertEquals(setOf("Melbourne", "Sydney", "New Delhi"), b.mapNotNull { it.label }.toSet())
    }

    @Test
    fun closeInPhotosNearEachOtherShareABubble() {
        val b = MapClusters.bubbles(points, world(12.0), 72f)
        // Melbourne's two photos are a few pixels apart on this screen: one bubble, no label.
        assertTrue(b.any { it.ids.toSet() == setOf(1L, 2L) && it.label == null })
    }

    @Test
    fun aTripIsSeveralPhotosAwayFromHome() {
        val home = (0 until 20).map { MapPoint(100L + it, -37.81, 144.96, it * day, melbourne) }
        val trip = (0 until 6).map { MapPoint(200L + it, -33.87, 151.21, (30 + it / 3) * day, sydney) }
        val short = (0 until 2).map { MapPoint(300L + it, 28.61, 77.21, 60 * day, delhi) }
        val found = Trips.find(home + trip + short)
        assertEquals(1, found.size)
        assertEquals("Sydney", found.single().title)
        assertEquals(6, found.single().ids.size)
    }
}
