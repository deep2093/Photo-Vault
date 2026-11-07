package com.deepak.photovault.ui.shell

import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.MediaType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TagsTest {
    private fun photo(id: Long, taken: Long = id * 1000) = MediaItem(
        id = id, uri = "u$id", type = MediaType.PHOTO, displayName = "IMG_$id.jpg", takenAtMillis = taken,
        sizeBytes = 10, width = 1, height = 1, albumId = "Camera", albumName = "Camera", mimeType = "image/jpeg",
    )

    private class Saved : TagsPlatform {
        var last: Map<String, List<String>> = emptyMap()
        override suspend fun save(tags: Map<String, List<String>>) { last = tags }
    }

    @Test
    fun addJoinsSameSpellingAndSaves() = runTest {
        val saved = Saved()
        val tags = TagsState(saved)
        val a = photo(1)
        val b = photo(2)
        tags.add(listOf(a), "  Birthday  ")
        tags.add(listOf(a, b), "birthday")
        assertEquals(listOf("Birthday"), tags.tagsOf(a))
        assertEquals(listOf("Birthday"), tags.tagsOf(b))
        assertEquals(2, saved.last.size)
        assertEquals(listOf("Birthday" to listOf(b, a)), tags.list(listOf(a, b)))
    }

    @Test
    fun searchRenameDelete() = runTest {
        val tags = TagsState()
        val a = photo(1)
        val b = photo(2)
        tags.add(listOf(a), "Family dinner")
        tags.add(listOf(b), "Receipts")
        assertEquals(setOf(1L), tags.photosMatching("din", listOf(a, b)))
        assertTrue(tags.photosMatching("x", listOf(a, b)).isEmpty())
        tags.rename("Family dinner", "Receipts")
        assertEquals(listOf("Receipts"), tags.tagsOf(a))
        tags.delete("receipts")
        assertTrue(tags.byKey.isEmpty())
        tags.add(listOf(a), "Diwali")
        tags.remove(a, "DIWALI")
        assertTrue(tags.tagsOf(a).isEmpty())
    }

    @Test
    fun cleanTidiesTags() {
        assertEquals("Holiday 2024", TagsState.clean(" Holiday\t  2024\n"))
        assertNull(TagsState.clean("   "))
        assertEquals(TagsState.MAX_LENGTH, TagsState.clean("x".repeat(50))!!.length)
    }
}
