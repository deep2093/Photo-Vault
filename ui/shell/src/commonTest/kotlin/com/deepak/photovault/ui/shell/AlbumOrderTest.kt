package com.deepak.photovault.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals

class AlbumOrderTest {
    private val defaults = listOf(GalleryState.FAVOURITES_CARD, GalleryState.VIDEOS_CARD, "camera", "whatsapp", "screens")

    @Test
    fun yourOrderFirstNewAlbumsAfterGoneOnesDropped() {
        assertEquals(defaults, AlbumOrder.apply(defaults, emptyList()))
        val saved = listOf("whatsapp", "gone", GalleryState.VIDEOS_CARD)
        assertEquals(listOf("whatsapp", GalleryState.VIDEOS_CARD, GalleryState.FAVOURITES_CARD, "camera", "screens"), AlbumOrder.apply(defaults, saved))
    }

    @Test
    fun dragMovesOneCard() {
        assertEquals(listOf("b", "c", "a", "d"), AlbumOrder.move(listOf("a", "b", "c", "d"), 0, 2))
        assertEquals(listOf("d", "a", "b", "c"), AlbumOrder.move(listOf("a", "b", "c", "d"), 3, 0))
        assertEquals(listOf("a", "b"), AlbumOrder.move(listOf("a", "b"), 0, 5))
    }

    @Test
    fun coverKeepsUpToFourPhotosThatStillExist() {
        val exists = setOf(1L, 2L, 3L, 5L, 6L)
        assertEquals(listOf(1L, 2L, 3L, 5L), AlbumOrder.cover(listOf(1L, 2L, 4L, 3L, 5L, 6L), exists::contains))
        assertEquals(emptyList(), AlbumOrder.cover(null, exists::contains))
    }

    private val keys = listOf(
        AlbumSortKey("a", "Zoo", 5, 300L),
        AlbumSortKey("b", "apple", 50, 100L),
        AlbumSortKey("c", "Mango", 5, 200L),
    )

    @Test
    fun sortsByNameItemsAndDateInBothDirections() {
        assertEquals(listOf("a", "b", "c"), AlbumSorting.apply(keys, AlbumSort.MINE, false))
        assertEquals(listOf("b", "c", "a"), AlbumSorting.apply(keys, AlbumSort.NAME, false))
        assertEquals(listOf("a", "c", "b"), AlbumSorting.apply(keys, AlbumSort.NAME, true))
        assertEquals(listOf("b", "c", "a"), AlbumSorting.apply(keys, AlbumSort.ITEMS, true))
        assertEquals(listOf("a", "c", "b"), AlbumSorting.apply(keys, AlbumSort.RECENT, true))
        assertEquals(listOf("b", "c", "a"), AlbumSorting.apply(keys, AlbumSort.RECENT, false))
    }

    @Test
    fun sortChoiceIsSavedAndReadBack() {
        assertEquals(AlbumSort.ITEMS to true, AlbumSorting.decode(AlbumSorting.encode(AlbumSort.ITEMS, true)))
        assertEquals(AlbumSort.NAME to false, AlbumSorting.decode("NAME:asc"))
        assertEquals(AlbumSort.MINE to false, AlbumSorting.decode(""))
        assertEquals(AlbumSort.MINE to false, AlbumSorting.decode("rubbish"))
    }

    @Test
    fun coverCropOfTheMiddleSquareIsNoChange() {
        // A 400 x 300 photo: the middle square is 300 wide, starting at 50.
        val v = CoverCrop.toCover(50f, 0f, 300f, 400f, 300f)
        assertEquals(1f, v[0], 0.001f)
        assertEquals(0f, v[1], 0.001f)
        assertEquals(0f, v[2], 0.001f)
    }

    @Test
    fun coverCropZoomsAndShifts() {
        // Half-size frame in the top-left corner of a 400 x 400 photo: twice the zoom, pushed right and down.
        val v = CoverCrop.toCover(0f, 0f, 200f, 400f, 400f)
        assertEquals(2f, v[0], 0.001f)
        assertEquals(0.5f, v[1], 0.001f)
        assertEquals(0.5f, v[2], 0.001f)
    }

    @Test
    fun coverFrameStaysInsideThePhoto() {
        val (l, t, s) = CoverCrop.clamp(-20f, 500f, 900f, 400f, 300f)
        assertEquals(300f, s, 0.001f)
        assertEquals(0f, l, 0.001f)
        assertEquals(0f, t, 0.001f)
        assertEquals(45f, CoverCrop.clamp(0f, 0f, 1f, 400f, 300f).third, 0.001f)
    }
}
