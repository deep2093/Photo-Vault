package com.deepak.photovault.ui.shell

import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.Timeline
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** Photos from one earlier year, taken on today's date (or within 3 days of it) (7C-1 On this day, 7 Nov). */
data class Memory(val year: Int, val yearsAgo: Int, val sameDay: Boolean, val ids: List<Long>) {
    /** "1 year ago today", "3 years ago this week". */
    val title: String get() = (if (yearsAgo == 1) "1 year ago" else "$yearsAgo years ago") + if (sameDay) " today" else " this week"
}

object Memories {
    /** Days either side of today that still count as "this week". */
    const val WEEK_DAYS = 3

    /** Most photos shown for one year; picked evenly through the day so it isn't one burst. */
    const val PER_YEAR = 30

    private val skipAlbums = listOf("screenshot", "screen recording", "screenrecord", "whatsapp", "download")

    /** Screenshots, screen recordings, downloads and chat-app pictures aren't memories. */
    fun counts(item: MediaItem): Boolean {
        val album = item.albumName.lowercase()
        val name = item.displayName.lowercase()
        if (skipAlbums.any { it in album }) return false
        if (name.startsWith("screenshot") || name.startsWith("screen_recording") || name.startsWith("screenrecord")) return false
        return true
    }

    /** Today's date in an earlier year (29 February becomes 28 February in other years). */
    private fun sameDate(today: LocalDate, year: Int): LocalDate =
        runCatching { LocalDate(year, today.monthNumber, today.dayOfMonth) }.getOrElse { LocalDate(year, today.monthNumber, 28) }

    /**
     * One memory per earlier year, newest year first. A year with photos on today's date uses just those; otherwise
     * photos within [WEEK_DAYS] days. Photos are oldest first inside a memory, like the day went.
     */
    fun find(items: List<MediaItem>, today: LocalDate, zone: TimeZone, perYear: Int = PER_YEAR): List<Memory> {
        val byYear = HashMap<Int, MutableList<Pair<MediaItem, Boolean>>>()
        for (item in items) {
            if (item.takenAtMillis <= 0) continue
            val date = Timeline.localDate(item.takenAtMillis, zone)
            if (date.year >= today.year) continue
            val gap = date.toEpochDays().toLong() - sameDate(today, date.year).toEpochDays().toLong()
            if (gap < -WEEK_DAYS || gap > WEEK_DAYS) continue
            if (!counts(item)) continue
            byYear.getOrPut(date.year) { mutableListOf() } += item to (gap == 0L)
        }
        return byYear.entries.sortedByDescending { it.key }.map { (year, list) ->
            val day = list.filter { it.second }
            val chosen = (day.ifEmpty { list }).map { it.first }.sortedBy { it.takenAtMillis }
            Memory(year, today.year - year, sameDay = day.isNotEmpty(), ids = spread(chosen, perYear).map { it.id })
        }
    }

    /** Up to [max] items picked evenly from the list, first and last kept. */
    internal fun <T> spread(list: List<T>, max: Int): List<T> {
        if (list.size <= max || max <= 0) return if (max <= 0) emptyList() else list
        if (max == 1) return listOf(list.first())
        return (0 until max).map { i -> list[(i.toLong() * (list.size - 1) / (max - 1)).toInt()] }
    }

    /** The memory to put in today's notification: the one with today's date, newest year first. */
    fun forNotice(memories: List<Memory>): Memory? = memories.firstOrNull { it.sameDay && it.ids.size >= 3 }
}
