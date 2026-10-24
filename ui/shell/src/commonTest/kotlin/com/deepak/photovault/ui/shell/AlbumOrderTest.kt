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
}
