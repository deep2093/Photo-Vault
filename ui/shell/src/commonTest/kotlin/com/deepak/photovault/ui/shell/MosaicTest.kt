package com.deepak.photovault.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MosaicTest {
    private val ids = (1L..500L).toList()

    @Test
    fun everyPhotoIsPlacedExactlyOnceAndInOrder() {
        for (columns in 2..7) {
            val seen = ArrayList<Int>()
            for (part in Mosaic.plan(ids, columns)) when (part) {
                is Mosaic.Run -> seen += part.from until part.to
                is Mosaic.Block -> seen += part.from until part.from + Mosaic.photosIn(columns)
            }
            assertEquals(ids.indices.toList(), seen, "columns=$columns")
        }
    }

    @Test
    fun blocksStartOnARowAndNeverTouch() {
        for (columns in Mosaic.MIN_COLUMNS..Mosaic.MAX_COLUMNS) {
            val parts = Mosaic.plan(ids, columns)
            assertTrue(parts.any { it is Mosaic.Block }, "some blocks with $columns columns")
            parts.forEachIndexed { i, part ->
                if (part is Mosaic.Block) {
                    // Always a plain run of whole rows between two blocks.
                    val before = parts.getOrNull(i - 1)
                    assertTrue(before == null || before is Mosaic.Run)
                    if (before is Mosaic.Run) assertEquals(0, (before.to - before.from) % columns)
                    // The big photo is one that may be big.
                    val big = part.from + Mosaic.slots(columns, part.right).indexOfFirst { it.span == 2 }
                    assertTrue(Mosaic.mayBeBig(ids[big]))
                }
            }
        }
    }

    @Test
    fun aBlockFillsTwoRowsWithNoGapsOrOverlaps() {
        for (columns in Mosaic.MIN_COLUMNS..Mosaic.MAX_COLUMNS) for (right in listOf(false, true)) {
            val slots = Mosaic.slots(columns, right)
            assertEquals(Mosaic.photosIn(columns), slots.size)
            val cells = slots.flatMap { s -> (0 until s.span).flatMap { dx -> (0 until s.span).map { dy -> (s.column + dx) to (s.row + dy) } } }
            assertEquals((0 until columns).flatMap { c -> (0..1).map { r -> c to r } }.toSet(), cells.toSet())
            assertEquals(2 * columns, cells.size)
            assertEquals(1, slots.count { it.span == 2 })
            assertEquals(if (right) columns - 2 else 0, slots.first { it.span == 2 }.column)
        }
    }

    @Test
    fun theSamePhotosAreBigEveryTimeAndOtherSizesStayPlain() {
        assertEquals(Mosaic.plan(ids, 3), Mosaic.plan(ids, 3))
        assertEquals(listOf(Mosaic.Run(0, 500)), Mosaic.plan(ids, 2))
        assertEquals(listOf(Mosaic.Run(0, 500)), Mosaic.plan(ids, 6))
        assertEquals(listOf(Mosaic.Run(0, 0)), Mosaic.plan(emptyList(), 3))
        // Roughly one in six may be big.
        val share = ids.count { Mosaic.mayBeBig(it) }
        assertTrue(share in 50..120, "share=$share")
    }
}
