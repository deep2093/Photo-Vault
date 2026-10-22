package com.deepak.photovault.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DragSelectTest {
    private val order = (1L..10L).toList()

    @Test
    fun slidingSelectsEverythingBetweenStartAndFinger() {
        assertEquals((3L..7L).toSet(), DragSelect.selection(emptySet(), order, anchor = 2, current = 6, adding = true))
    }

    @Test
    fun slidingBackwardsWorksToo() {
        assertEquals((3L..6L).toSet(), DragSelect.selection(emptySet(), order, anchor = 5, current = 2, adding = true))
    }

    @Test
    fun slidingBackShrinksTheRangeAndKeepsEarlierPicks() {
        val before = setOf(10L)
        val wide = DragSelect.selection(before, order, anchor = 0, current = 5, adding = true)
        val narrow = DragSelect.selection(before, order, anchor = 0, current = 1, adding = true)
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 6L, 10L), wide)
        assertEquals(setOf(1L, 2L, 10L), narrow)
    }

    @Test
    fun startingOnASelectedPhotoUnselects() {
        val all = order.toSet()
        assertEquals(all - setOf(4L, 5L, 6L), DragSelect.selection(all, order, anchor = 3, current = 5, adding = false))
    }

    @Test
    fun autoScrollOnlyNearTheEdges() {
        assertEquals(0f, DragSelect.edgeSpeed(500f, top = 0f, bottom = 1000f, zone = 100f, maxSpeed = 20f))
        assertTrue(DragSelect.edgeSpeed(10f, 0f, 1000f, 100f, 20f) < 0f)
        assertTrue(DragSelect.edgeSpeed(990f, 0f, 1000f, 100f, 20f) > 0f)
        assertEquals(20f, DragSelect.edgeSpeed(1000f, 0f, 1000f, 100f, 20f))
    }
}

class AppIconTest {
    @Test
    fun sixteenIconsWithFourSeasonsOnLightAsDefault() {
        assertEquals(16, AppIconChoice.all.size)
        assertEquals("IconFourSeasonsLight", AppIconChoice().aliasName)
        assertEquals("ic_four_seasons_light", AppIconChoice().resourceName)
        assertEquals("IconPetalsColourful", AppIconChoice(IconGlyph.PETALS, IconBackground.COLOURFUL).aliasName)
        assertEquals("ic_leaf_clear", AppIconChoice(IconGlyph.LEAF, IconBackground.CLEAR).resourceName)
        // Bloom was removed; its old launcher entries hand over to Four Seasons on the same background.
        assertEquals(AppIconChoice(IconGlyph.FOUR_SEASONS, IconBackground.DARK), AppIconChoice.retired.getValue("IconBloomDark"))
    }
}

class SectionTapTest {
    @Test
    fun tappingTheOpenSectionAsksItToScrollToTop() {
        val state = ShellState(MemoryAppearanceStore())
        state.tapSection(Destination.PHOTOS)
        state.tapSection(Destination.PHOTOS)
        assertEquals(2, state.reselects[Destination.PHOTOS])
        state.tapSection(Destination.ALBUMS)
        assertEquals(Destination.ALBUMS, state.destination)
        assertEquals(null, state.reselects[Destination.ALBUMS])
    }
}
