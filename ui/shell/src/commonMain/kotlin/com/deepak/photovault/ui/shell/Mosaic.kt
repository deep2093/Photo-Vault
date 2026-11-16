package com.deepak.photovault.ui.shell

/**
 * The mixed-size photo grid (16 Nov, asked for after Google Photos): here and there one photo is shown double size
 * with normal ones beside it, on the left or the right.
 *
 * A block is two rows tall: the big photo takes 2 x 2 cells and normal ones fill the other columns, so with
 * [columns] columns a block holds 2 x columns - 3 photos. Photos keep their order: reading a block row by row gives
 * them in the order they were taken.
 *
 * Which photos are big is a fixed property of the photo (from its ID), not of where it happens to fall, so the same
 * photos stay big every time the app opens. A photo is only shown big when it lands where a block can start with it
 * (first in a row for a left block, or last-but-one cell of the first row for a right block), and two blocks never
 * touch: there is always a plain row between them.
 */
object Mosaic {
    const val MIN_COLUMNS = 3
    const val MAX_COLUMNS = 5

    /** One in this many photos may be shown big. */
    private const val ONE_IN = 6L

    fun worksWith(columns: Int): Boolean = columns in MIN_COLUMNS..MAX_COLUMNS

    /** Photos in one block. */
    fun photosIn(columns: Int): Int = 2 * columns - 3

    /** Whether this photo may be shown big: the same answer every time for the same photo. */
    fun mayBeBig(id: Long): Boolean = (((id * 2654435761L) ushr 11) and 0x7FFFFFFFL) % ONE_IN == 0L

    /** Where one photo of a block sits: column and row of its top-left cell, and how many cells wide and tall. */
    data class Slot(val column: Int, val row: Int, val span: Int)

    /**
     * The places of a block's photos, in the photos' own order. Left: the big one first, then the small ones row by
     * row. Right: the first row's small ones, the big one, then the second row's small ones.
     */
    fun slots(columns: Int, right: Boolean): List<Slot> {
        val small = columns - 2
        return if (!right) {
            listOf(Slot(0, 0, 2)) + (0..1).flatMap { row -> (0 until small).map { Slot(2 + it, row, 1) } }
        } else {
            (0 until small).map { Slot(it, 0, 1) } + Slot(small, 0, 2) + (0 until small).map { Slot(it, 1, 1) }
        }
    }

    sealed interface Part

    /** Plain photos, positions [from] until [to] in the list, laid out as the usual grid. */
    data class Run(val from: Int, val to: Int) : Part

    /** A block of [photosIn] photos starting at [from]; the big one on the right when [right]. */
    data class Block(val from: Int, val right: Boolean) : Part

    /** The whole list cut into plain runs and blocks, in order, covering every photo exactly once. */
    fun plan(ids: List<Long>, columns: Int): List<Part> {
        if (!worksWith(columns) || ids.isEmpty()) return listOf(Run(0, ids.size))
        val out = ArrayList<Part>()
        val size = photosIn(columns)
        var i = 0
        var runFrom = 0
        var afterBlock = false
        while (i < ids.size) {
            if (!afterBlock && i + size <= ids.size) {
                val left = mayBeBig(ids[i])
                val right = !left && mayBeBig(ids[i + columns - 2])
                if (left || right) {
                    if (i > runFrom) out += Run(runFrom, i)
                    out += Block(i, right)
                    i += size
                    runFrom = i
                    afterBlock = true
                    continue
                }
            }
            i = minOf(i + columns, ids.size)
            afterBlock = false
        }
        if (ids.size > runFrom) out += Run(runFrom, ids.size)
        return out
    }
}
