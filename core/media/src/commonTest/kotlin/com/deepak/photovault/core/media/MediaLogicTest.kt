package com.deepak.photovault.core.media

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val zone = TimeZone.of("Australia/Melbourne")
private val today = LocalDate(2026, 10, 22)

private fun at(year: Int, month: Int, day: Int, hour: Int = 12) =
    LocalDateTime(year, month, day, hour, 0).toInstant(zone).toEpochMilliseconds()

private fun item(
    id: Long,
    millis: Long,
    name: String = "IMG_$id.jpg",
    album: String = "Camera",
    video: Boolean = false,
) = MediaItem(
    id = id, uri = "content://media/$id", type = if (video) MediaType.VIDEO else MediaType.PHOTO, displayName = name,
    takenAtMillis = millis, sizeBytes = 1000, width = 4000, height = 3000, durationMillis = if (video) 42_000 else 0,
    albumId = album.lowercase(), albumName = album, mimeType = if (video) "video/mp4" else "image/jpeg",
)

class TimelineTest {
    private val items = listOf(
        item(1, at(2026, 10, 22, 9)),
        item(2, at(2026, 10, 22, 18)),
        item(3, at(2026, 10, 21)),
        item(4, at(2026, 10, 19)),
        item(5, at(2025, 12, 25)),
    )

    @Test
    fun daysAreNewestFirstWithFriendlyLabels() {
        val sections = Timeline.group(items, TimelineGrouping.DAY, zone, today)
        assertEquals(listOf("Today", "Yesterday", "Monday 19 October", "25 December 2025"), sections.map { it.label })
        assertEquals(listOf(2L, 1L), sections.first().items.map { it.id })
    }

    @Test
    fun monthsAndYearsGroupTogether() {
        assertEquals(listOf("October 2026", "December 2025"), Timeline.group(items, TimelineGrouping.MONTH, zone, today).map { it.label })
        assertEquals(listOf("2026", "2025"), Timeline.group(items, TimelineGrouping.YEAR, zone, today).map { it.label })
    }

    @Test
    fun midnightUsesThePhonesTimeZone() {
        // 11:30 pm in Melbourne is still "today" even though it is already tomorrow in UTC terms... and vice versa.
        val late = LocalDateTime(2026, 10, 21, 23, 30).toInstant(zone).toEpochMilliseconds()
        assertEquals("Yesterday", Timeline.group(listOf(item(9, late)), TimelineGrouping.DAY, zone, today).single().label)
    }

    @Test
    fun durationsAndDaysLeft() {
        assertEquals("0:42", Timeline.duration(42_000))
        assertEquals("12:05", Timeline.duration(725_000))
        assertEquals("1:02:33", Timeline.duration(3_753_000))
        val day = 24L * 60 * 60 * 1000
        assertEquals(30, Timeline.daysLeft(nowMillis = 0, expiresAtMillis = 30 * day))
        assertEquals(1, Timeline.daysLeft(nowMillis = 0, expiresAtMillis = 1))
        assertEquals(0, Timeline.daysLeft(nowMillis = 10, expiresAtMillis = 5))
    }

    @Test
    fun handlesTwentyThousandPhotosQuickly() {
        val many = (0 until 20_000).map { item(it.toLong(), at(2026, 10, 22) - it * 3_600_000L) }
        val sections = Timeline.group(many, TimelineGrouping.DAY, zone, today)
        assertEquals(20_000, sections.sumOf { it.items.size })
        assertTrue(sections.size > 800)
    }
}

class SearchAndAlbumsTest {
    private val items = listOf(
        item(1, at(2026, 10, 1), name = "beach_sunset.jpg"),
        item(2, at(2026, 9, 15), album = "WhatsApp Images"),
        item(3, at(2025, 10, 3), name = "VID_birthday.mp4", video = true),
        item(4, at(2026, 10, 5), album = "Screenshots"),
    )

    @Test
    fun everyWordMustMatch() {
        assertEquals(listOf(1L), MediaSearch.filter(items, "beach", emptySet(), zone).map { it.id })
        assertEquals(listOf(3L), MediaSearch.filter(items, "videos 2025", emptySet(), zone).map { it.id })
        assertEquals(listOf(4L, 1L, 3L), MediaSearch.filter(items, "oct", emptySet(), zone).map { it.id })
        assertEquals(listOf(2L), MediaSearch.filter(items, "whatsapp", emptySet(), zone).map { it.id })
        assertEquals(listOf(4L), MediaSearch.filter(items, "favourite", setOf(4L), zone).map { it.id })
        assertTrue(MediaSearch.filter(items, "   ", emptySet(), zone).isEmpty())
    }

    @Test
    fun albumsPutCameraFirstThenNewest() {
        val albums = PhoneAlbums.from(items)
        assertEquals(listOf("Camera", "Screenshots", "WhatsApp Images"), albums.map { it.name })
        assertEquals(2, albums.first().count)
        assertEquals(1L, albums.first().cover.id)
    }

    @Test
    fun countsReadNaturally() {
        assertEquals("3 photos · 1 video", LibraryCounts.summary(items))
        assertEquals("No photos yet", LibraryCounts.summary(emptyList()))
        assertEquals("1,248", LibraryCounts.grouped(1248))
        assertEquals("20,000", LibraryCounts.grouped(20000))
    }
}

class MainColorTest {
    @Test
    fun picksTheMostColourfulBrightPixel() {
        val grey = 0xFF808080.toInt()
        val orange = 0xFFE07A20.toInt()
        assertEquals(orange, MainColor.pick(intArrayOf(grey, grey, orange, grey)))
    }

    @Test
    fun allGreyGivesTheAverage() {
        assertEquals(0xFF646464.toInt(), MainColor.pick(intArrayOf(0xFF505050.toInt(), 0xFF787878.toInt())))
    }

    @Test
    fun darkPixelsDoNotWin() {
        val nearBlackRed = 0xFF200000.toInt()
        val paleBlue = 0xFF90A8D0.toInt()
        assertEquals(paleBlue, MainColor.pick(intArrayOf(nearBlackRed, paleBlue)))
    }
}

class LibraryLevelsTest {
    private val items = listOf(
        item(1, at(2026, 10, 22, 9)),
        item(2, at(2026, 10, 3)),
        item(3, at(2026, 9, 1), video = true),
        item(4, at(2025, 10, 20)),
        item(5, at(2025, 2, 1)),
        item(6, at(2024, 6, 6), video = true),
    )

    @Test
    fun monthsNewestFirstWithCountsCoversAndTheirFirstDay() {
        val months = LibraryLevels.months(items, zone)
        assertEquals(listOf("October 2026", "September 2026", "October 2025", "February 2025", "June 2024"), months.map { it.label })
        assertEquals(2, months[0].count)
        assertEquals(listOf(1L, 2L), months[0].covers.map { it.id })
        assertEquals("2026-10-22", months[0].firstDayKey)
        // A month of only videos still gets a cover.
        assertEquals(listOf(3L), months[1].covers.map { it.id })
        assertEquals(2, LibraryLevels.firstMonthOf(2025, months))
    }

    @Test
    fun yearsShowThePhotoClosestToTodaysDate() {
        val years = LibraryLevels.years(items, zone, today)
        assertEquals(listOf(2026, 2025, 2024), years.map { it.year })
        assertEquals(1L, years[0].cover.id)
        assertEquals(4L, years[1].cover.id) // 20 Oct beats 1 Feb for "around 22 October"
        assertEquals(6L, years[2].cover.id) // only a video that year
        assertEquals(2, years[1].months)
    }

    @Test
    fun spreadPicksFromAcrossTheMonth() {
        val many = (1L..40L).map { item(it, at(2026, 10, 1) + it) }
        val picked = LibraryLevels.spread(many, 4)
        assertEquals(4, picked.size)
        assertEquals(many.first(), picked.first())
        assertEquals(many.last(), picked.last())
    }
}

class StorageStatsTest {
    private val files = listOf(
        StoredFile("a.jpg", 300, at(2026, 1, 1), "Camera"),
        StoredFile("b.JPEG", 200, at(2025, 5, 1), "Camera"),
        StoredFile("c.heic", 400, at(2026, 2, 1), "WhatsApp Images"),
        StoredFile("d.mp4", 1000, at(2024, 3, 1), "Camera"),
        StoredFile("e.png", 50, at(2026, 4, 1), "Screenshots"),
        StoredFile("f.gif", 10, at(2026, 4, 2), "Download"),
        StoredFile("g.webp", 5, at(2026, 4, 3), "Download"),
    )

    @Test
    fun typesMergeJpegAndFoldTheSmallOnes() {
        val types = StorageStats.byType(files, top = 3)
        assertEquals(listOf("MP4", "JPG", "HEIC", "Other"), types.map { it.label })
        assertEquals(500L, types[1].bytes)
        assertEquals(65L, types[3].bytes)
        assertEquals(3, types[3].count)
    }

    @Test
    fun albumsAndYears() {
        assertEquals(listOf("Camera", "WhatsApp Images", "Screenshots", "Download"), StorageStats.byAlbum(files).map { it.label })
        assertEquals(1500L, StorageStats.byAlbum(files).first().bytes)
        assertEquals(listOf("2024", "2025", "2026"), StorageStats.byYear(files, zone).map { it.label })
    }

    @Test
    fun eachSliceOpensExactlyItsFiles() {
        val types = StorageStats.byType(files, top = 3)
        assertEquals(listOf("a.jpg", "b.JPEG"), StorageStats.ofType(files, types, "JPG").map { it.name })
        // "Other" holds every type not shown on its own, and only those.
        assertEquals(listOf("e.png", "f.gif", "g.webp"), StorageStats.ofType(files, types, "Other").map { it.name })
        val albums = StorageStats.byAlbum(files, top = 2)
        assertEquals(listOf("e.png", "f.gif", "g.webp"), StorageStats.ofAlbum(files, albums, "Other").map { it.name })
        assertEquals(listOf("c.heic"), StorageStats.ofAlbum(files, albums, "WhatsApp Images").map { it.name })
        assertEquals(listOf("a.jpg", "c.heic", "e.png", "f.gif", "g.webp"), StorageStats.ofYear(files, "2026", zone).map { it.name })
    }

    @Test
    fun sortingByNameDateAndSizeBothWays() {
        val byName = FileOrder().tapped(FileSort.NAME)
        assertEquals(FileOrder(FileSort.NAME, ascending = true), byName)
        assertEquals("a.jpg", StorageStats.sorted(files, byName).first().name)
        assertEquals("g.webp", StorageStats.sorted(files, byName.tapped(FileSort.NAME)).first().name)
        // Date and size start newest / biggest first; a second tap turns them round.
        assertEquals("g.webp", StorageStats.sorted(files, FileOrder()).first().name)
        val bySize = byName.tapped(FileSort.SIZE)
        assertEquals("d.mp4", StorageStats.sorted(files, bySize).first().name)
        assertEquals("g.webp", StorageStats.sorted(files, bySize.tapped(FileSort.SIZE)).first().name)
    }
}
