package com.deepak.photovault.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals

class GridRowsTest {
    @Test
    fun plainGridCountsWholeRows() {
        val rows = GridRows.plain(headerItems = 1, count = 10, columns = 4)
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 1, 1, 2, 2), (0 until 10).map { rows.before(it).toInt() })
        assertEquals(7, rows.itemAtRow(1f))
        assertEquals(0, rows.itemAtRow(0f))
    }

    @Test
    fun aBlockIsOneItemTwoRowsTall() {
        // Run of 4 (one row), a block (two rows), run of 6 (two rows, the last one part full), a block.
        val parts = listOf(Mosaic.Run(0, 4), Mosaic.Block(4, right = false), Mosaic.Run(9, 15), Mosaic.Block(15, right = true))
        val rows = GridRows.mosaic(headerItems = 1, parts = parts, columns = 4)
        // Items: 4 tiles, 1 block, 6 tiles, 1 block = 12 grid items.
        assertEquals(12, rows.photoItems)
        assertEquals(listOf(0, 0, 0, 0, 1, 3, 3, 3, 3, 4, 4, 5), (0 until 12).map { rows.before(it).toInt() })
        // The item that starts at or above a row: the first block for row 1 or 2, the last block for row 5 and beyond.
        assertEquals(4, rows.itemAtRow(1f))
        assertEquals(4, rows.itemAtRow(2.5f))
        assertEquals(11, rows.itemAtRow(9f))
    }

    @Test
    fun everyPlannedPhotoAreaAddsUp() {
        val ids = (1L..500L).toList()
        for (columns in Mosaic.MIN_COLUMNS..Mosaic.MAX_COLUMNS) {
            val parts = Mosaic.plan(ids, columns)
            val rows = GridRows.mosaic(0, parts, columns)
            // Rows never go back, and each block adds two rows.
            val list = (0 until rows.photoItems).map(rows::before)
            assertEquals(list.sorted(), list, "columns=$columns")
        }
    }

    @Test
    fun dayHeadingsArePartOfARow() {
        // Two days: 5 photos then 3, four to a row, each heading half a row tall.
        val rows = GridRows.sectioned(headerItems = 1, sizes = listOf(5, 3), columns = 4, headingRows = 0.5f)
        // Items: heading, 5 tiles, heading, 3 tiles.
        assertEquals(10, rows.photoItems)
        assertEquals(listOf(0f, 0.5f, 0.5f, 0.5f, 0.5f, 1.5f, 2.5f, 3f, 3f, 3f), (0 until 10).map(rows::before))
        // Row 2.6 is the second heading's row.
        assertEquals(6, rows.itemAtRow(2.6f))
    }
}
