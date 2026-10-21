package com.deepak.photovault.core.media

import kotlinx.datetime.TimeZone

/** A folder of photos on the phone (Camera, Screenshots, WhatsApp Images...). */
data class PhoneAlbum(val id: String, val name: String, val count: Int, val cover: MediaItem, val latestMillis: Long)

object PhoneAlbums {
    /** Albums newest first; Camera always first when present, as people expect. */
    fun from(items: List<MediaItem>): List<PhoneAlbum> =
        items.groupBy { it.albumId }
            .map { (id, list) ->
                val newest = list.maxBy { it.takenAtMillis }
                PhoneAlbum(id, list.first().albumName.ifBlank { "Other" }, list.size, newest, newest.takenAtMillis)
            }
            .sortedWith(compareByDescending<PhoneAlbum> { it.name.equals("Camera", ignoreCase = true) }.thenByDescending { it.latestMillis })
}

/**
 * Search across the phone's photos. Every word must match something: the file name, the album, the type
 * ("photo", "video"), "favourite", a month name or a year. Runs on the phone; nothing is sent anywhere.
 */
object MediaSearch {
    fun filter(items: List<MediaItem>, query: String, favourites: Set<Long>, zone: TimeZone): List<MediaItem> {
        val words = query.lowercase().split(' ', ',', '.').map { it.trim() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return items.filter { item -> words.all { word -> matches(item, word, favourites, zone) } }
            .sortedByDescending { it.takenAtMillis }
    }

    private fun matches(item: MediaItem, word: String, favourites: Set<Long>, zone: TimeZone): Boolean {
        if (item.displayName.lowercase().contains(word)) return true
        if (item.albumName.lowercase().contains(word)) return true
        if (word.startsWith("video") && item.isVideo) return true
        if ((word.startsWith("photo") || word.startsWith("picture") || word.startsWith("image")) && !item.isVideo) return true
        if ((word.startsWith("fav") || word == "loved") && item.id in favourites) return true
        val date = Timeline.localDate(item.takenAtMillis, zone)
        if (word.length >= 3 && Timeline.monthName(date.month).lowercase().startsWith(word)) return true
        if (word.length == 4 && word.all(Char::isDigit) && date.year.toString() == word) return true
        return false
    }
}

/** Counts for a screen subtitle, e.g. "1,248 photos · 210 videos". */
object LibraryCounts {
    fun summary(items: List<MediaItem>): String {
        val videos = items.count { it.isVideo }
        val photos = items.size - videos
        fun n(v: Int, one: String, many: String) = "${grouped(v)} ${if (v == 1) one else many}"
        return when {
            items.isEmpty() -> "No photos yet"
            videos == 0 -> n(photos, "photo", "photos")
            photos == 0 -> n(videos, "video", "videos")
            else -> "${n(photos, "photo", "photos")} · ${n(videos, "video", "videos")}"
        }
    }

    /** 1248 -> "1,248". */
    fun grouped(value: Int): String = value.toString().reversed().chunked(3).joinToString(",").reversed()
}
