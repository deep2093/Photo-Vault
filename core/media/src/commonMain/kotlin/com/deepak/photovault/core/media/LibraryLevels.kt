package com.deepak.photovault.core.media

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** The three ways to look at the library (24 Oct, after Apple Photos): years, months, or every photo. */
enum class LibraryView(val label: String) { YEARS("Years"), MONTHS("Months"), ALL("All") }

/** One month as a card: a few photos spread across it, how many, and the key of its first day in "All". */
data class MonthSummary(
    val year: Int,
    val month: Int,
    val label: String,
    val count: Int,
    /** Up to four photos from across the month, newest first; videos only when there aren't enough photos. */
    val covers: List<MediaItem>,
    /** The newest day's section key in the All view, to jump to. */
    val firstDayKey: String,
) {
    val key: String get() = "$year-$month"
}

/** One year as a card: its cover photo, how many, and how many months have photos. */
data class YearSummary(val year: Int, val count: Int, val cover: MediaItem, val months: Int)

/** Pure, so it is tested without a device. Libraries of tens of thousands of photos take one pass each. */
object LibraryLevels {
    fun months(items: List<MediaItem>, zone: TimeZone): List<MonthSummary> {
        val byMonth = LinkedHashMap<Pair<Int, Int>, MutableList<MediaItem>>()
        val firstDay = HashMap<Pair<Int, Int>, LocalDate>()
        for (item in items.sortedByDescending { it.takenAtMillis }) {
            val date = Timeline.localDate(item.takenAtMillis, zone)
            val key = date.year to date.monthNumber
            byMonth.getOrPut(key) { ArrayList() }.add(item)
            if (key !in firstDay) firstDay[key] = date
        }
        return byMonth.map { (key, list) ->
            val date = firstDay.getValue(key)
            MonthSummary(
                year = key.first,
                month = key.second,
                label = "${Timeline.monthName(date.month)} ${key.first}",
                count = list.size,
                covers = spread(preferPhotos(list), 4),
                firstDayKey = date.toString(),
            )
        }
    }

    /**
     * Years newest first. The cover is the photo taken closest to today's date in that year, so the cards show
     * "this time, each year" (as Apple's Years view does).
     */
    fun years(items: List<MediaItem>, zone: TimeZone, today: LocalDate): List<YearSummary> {
        val byYear = items.groupBy { Timeline.localDate(it.takenAtMillis, zone).year }
        return byYear.keys.sortedDescending().map { year ->
            val list = byYear.getValue(year)
            val months = list.map { Timeline.localDate(it.takenAtMillis, zone).monthNumber }.toSet().size
            val target = today.dayOfYear
            val cover = preferPhotos(list).minBy { item ->
                val d = Timeline.localDate(item.takenAtMillis, zone).dayOfYear
                val gap = kotlin.math.abs(d - target)
                minOf(gap, 366 - gap)
            }
            YearSummary(year, list.size, cover, months)
        }
    }

    /** The month whose card should be in view when going from a year to Months. */
    fun firstMonthOf(year: Int, months: List<MonthSummary>): Int = months.indexOfFirst { it.year == year }.coerceAtLeast(0)

    private fun preferPhotos(list: List<MediaItem>): List<MediaItem> = list.filter { !it.isVideo }.ifEmpty { list }

    /** [n] items evenly spread through [list] (keeps its order). */
    fun spread(list: List<MediaItem>, n: Int): List<MediaItem> {
        if (list.size <= n) return list
        return List(n) { i -> list[(i * (list.size - 1)) / (n - 1).coerceAtLeast(1)] }.distinct()
    }
}
