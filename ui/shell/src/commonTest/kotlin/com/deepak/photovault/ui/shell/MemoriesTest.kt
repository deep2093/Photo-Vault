package com.deepak.photovault.ui.shell

import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.MediaType
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoriesTest {
    private val zone = TimeZone.UTC
    private val today = LocalDate(2026, 11, 7)

    private fun photo(id: Long, y: Int, m: Int, d: Int, album: String = "Camera", name: String = "IMG_$id.jpg") = MediaItem(
        id = id,
        uri = "u$id",
        type = MediaType.PHOTO,
        displayName = name,
        takenAtMillis = LocalDateTime(y, m, d, 10, 0).toInstant(zone).toEpochMilliseconds() + id,
        sizeBytes = 1,
        width = 1,
        height = 1,
        albumId = album,
        albumName = album,
        mimeType = "image/jpeg",
    )

    @Test
    fun sameDayWinsOverTheWeek() {
        val items = listOf(photo(1, 2025, 11, 7), photo(2, 2025, 11, 5), photo(3, 2023, 11, 9), photo(4, 2026, 11, 7))
        val found = Memories.find(items, today, zone)
        assertEquals(listOf(2025, 2023), found.map { it.year })
        assertEquals(listOf(1L), found[0].ids)
        assertEquals("1 year ago today", found[0].title)
        assertEquals(listOf(3L), found[1].ids)
        assertEquals("3 years ago this week", found[1].title)
    }

    @Test
    fun screenshotsAndFarDaysAreLeftOut() {
        val items = listOf(
            photo(1, 2024, 11, 7, album = "Screenshots"),
            photo(2, 2024, 11, 7, name = "Screenshot_2024.png"),
            photo(3, 2024, 11, 20),
        )
        assertTrue(Memories.find(items, today, zone).isEmpty())
    }

    @Test
    fun leapDayFallsBackToThe28th() {
        val found = Memories.find(listOf(photo(1, 2023, 2, 28)), LocalDate(2028, 2, 29), zone)
        assertEquals(1, found.size)
        assertTrue(found[0].sameDay)
    }

    @Test
    fun spreadKeepsEnds() {
        val list = (0..99).toList()
        val picked = Memories.spread(list, 5)
        assertEquals(5, picked.size)
        assertEquals(0, picked.first())
        assertEquals(99, picked.last())
    }
}
