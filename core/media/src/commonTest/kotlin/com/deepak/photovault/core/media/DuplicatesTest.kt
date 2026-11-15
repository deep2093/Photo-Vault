package com.deepak.photovault.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DuplicatesTest {
    private fun item(id: Long, at: Long, size: Long = 1000, w: Int = 4000, h: Int = 3000, album: String = "Camera", video: Boolean = false) = MediaItem(
        id = id, uri = "u$id", type = if (video) MediaType.VIDEO else MediaType.PHOTO, displayName = "p$id.jpg",
        takenAtMillis = at, sizeBytes = size, width = w, height = h, albumId = album, albumName = album, mimeType = "image/jpeg",
    )

    @Test
    fun onlyFilesSharingASizeAreHashed() {
        val list = listOf(item(1, 0, size = 10), item(2, 0, size = 10), item(3, 0, size = 11))
        assertEquals(setOf(1L, 2L), Duplicates.sameSize(list).map { it.id }.toSet())
    }

    @Test
    fun exactCopiesKeepTheCameraOne() {
        val list = listOf(item(1, 5000, album = "WhatsApp Images"), item(2, 9000, album = "Camera"), item(3, 1000, album = "Download"))
        val prints = list.associate { it.id to Duplicates.Print(content = "same") }
        val g = Duplicates.find(list, prints).single()
        assertEquals(Duplicates.Kind.EXACT, g.kind)
        assertEquals(2L, g.keep)
        assertEquals(listOf(2L, 3L, 1L), g.ids)
    }

    @Test
    fun similarPhotosAreClosePicturesCloseInTime() {
        val base = 0b1011_0110_1110_0001L
        val list = listOf(item(1, 0), item(2, 60_000), item(3, 120_000), item(4, 60 * 60_000))
        val prints = mapOf(
            1L to Duplicates.Print(picture = base, sharpness = 50f),
            2L to Duplicates.Print(picture = base xor 0b111, sharpness = 120f),
            3L to Duplicates.Print(picture = base.inv(), sharpness = 300f),
            // The same picture an hour later: not part of the burst.
            4L to Duplicates.Print(picture = base, sharpness = 300f),
        )
        val g = Duplicates.find(list, prints).single()
        assertEquals(Duplicates.Kind.SIMILAR, g.kind)
        assertEquals(setOf(1L, 2L), g.ids.toSet())
        assertEquals(2L, g.keep)
    }

    @Test
    fun anExactCopyIsntAlsoListedAsSimilar() {
        val list = listOf(item(1, 0), item(2, 1000), item(3, 2000))
        val prints = mapOf(
            1L to Duplicates.Print(content = "a", picture = 7L),
            2L to Duplicates.Print(content = "a", picture = 7L),
            3L to Duplicates.Print(picture = 7L),
        )
        val groups = Duplicates.find(list, prints)
        assertEquals(2, groups.size)
        val exact = groups.first { it.kind == Duplicates.Kind.EXACT }
        val similar = groups.first { it.kind == Duplicates.Kind.SIMILAR }
        assertTrue(exact.others.none { it in similar.ids })
    }

    @Test
    fun bestPrefersSharpThenBig() {
        val small = item(1, 0, w = 1000, h = 1000)
        val big = item(2, 0, w = 4000, h = 3000)
        val blurry = item(3, 0, w = 8000, h = 6000)
        val prints = mapOf(1L to Duplicates.Print(sharpness = 100f), 2L to Duplicates.Print(sharpness = 95f), 3L to Duplicates.Print(sharpness = 10f))
        assertEquals(2L, Duplicates.best(listOf(small, big, blurry), prints).id)
    }

    @Test
    fun fingerprintAndSharpnessBehave() {
        val ramp = IntArray(72) { i -> 255 - (i % 9) * 28 }
        assertEquals(-1L, Duplicates.differenceHash(ramp))
        assertEquals(0, Duplicates.distance(Duplicates.differenceHash(ramp), Duplicates.differenceHash(IntArray(72) { ramp[it] - 3 })))
        val flat = IntArray(100) { 128 }
        val checks = IntArray(100) { i -> if ((i / 10 + i % 10) % 2 == 0) 0 else 255 }
        assertEquals(0f, Duplicates.sharpness(flat, 10, 10))
        assertTrue(Duplicates.sharpness(checks, 10, 10) > 1000f)
    }
}
