package com.deepak.photovault.core.media

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

enum class TimelineGrouping { DAY, MONTH, YEAR }

/** A heading and the photos under it, newest first. [key] is stable for list keys. */
data class TimelineSection(val key: String, val label: String, val items: List<MediaItem>)

object Timeline {
    /** Groups [items] (any order) into newest-first sections. Pure, so it is tested without a device. */
    fun group(
        items: List<MediaItem>,
        grouping: TimelineGrouping,
        zone: TimeZone,
        today: LocalDate,
    ): List<TimelineSection> {
        val sorted = items.sortedWith(compareByDescending<MediaItem> { it.takenAtMillis }.thenByDescending { it.id })
        val sections = LinkedHashMap<String, MutableList<MediaItem>>()
        val labels = HashMap<String, String>()
        for (item in sorted) {
            val date = localDate(item.takenAtMillis, zone)
            val key = when (grouping) {
                TimelineGrouping.DAY -> date.toString()
                TimelineGrouping.MONTH -> "${date.year}-${date.monthNumber}"
                TimelineGrouping.YEAR -> date.year.toString()
            }
            sections.getOrPut(key) {
                labels[key] = when (grouping) {
                    TimelineGrouping.DAY -> dayLabel(date, today)
                    TimelineGrouping.MONTH -> "${monthName(date.month)} ${date.year}"
                    TimelineGrouping.YEAR -> date.year.toString()
                }
                ArrayList()
            }.add(item)
        }
        return sections.map { (key, list) -> TimelineSection(key, labels.getValue(key), list) }
    }

    fun localDate(millis: Long, zone: TimeZone): LocalDate =
        Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date

    /** "Today", "Yesterday", "Monday 19 October", or "19 October 2025" for other years. */
    fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.minus(DatePeriod(days = 1)) -> "Yesterday"
        else -> if (date.year == today.year) {
            "${dayName(date.dayOfWeek)} ${date.dayOfMonth} ${monthName(date.month)}"
        } else {
            "${date.dayOfMonth} ${monthName(date.month)} ${date.year}"
        }
    }

    fun monthName(month: Month): String = month.name.lowercase().replaceFirstChar { it.uppercase() }

    fun dayName(day: DayOfWeek): String = day.name.lowercase().replaceFirstChar { it.uppercase() }

    /** "0:42", "12:05", "1:02:33". */
    fun duration(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val ss = s.toString().padStart(2, '0')
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$ss" else "$m:$ss"
    }

    /** Whole days left before a deleted item is removed for good; never negative. */
    fun daysLeft(expiresAtMillis: Long, nowMillis: Long): Int {
        val remaining = expiresAtMillis - nowMillis
        if (remaining <= 0) return 0
        return ((remaining + DAY_MILLIS - 1) / DAY_MILLIS).toInt()
    }

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
}
