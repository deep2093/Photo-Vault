package com.deepak.photovault.core.media

import kotlinx.datetime.TimeZone

/** One slice of a storage chart: a name, how many bytes, how many files. */
data class StorageSlice(val label: String, val bytes: Long, val count: Int)

/** A file for the Storage page: from the phone or from the drive's backup records. */
data class StoredFile(val name: String, val bytes: Long, val takenAtMillis: Long, val album: String)

/** Pure sums behind Settings > Storage (24 Oct), tested. */
object StorageStats {
    fun fromPhone(items: List<MediaItem>): List<StoredFile> =
        items.map { StoredFile(it.displayName, it.sizeBytes, it.takenAtMillis, it.albumName.ifBlank { "Other" }) }

    /** By file type (JPG, HEIC, MP4...), biggest first; the rest after [top] become "Other". */
    fun byType(files: List<StoredFile>, top: Int = 4): List<StorageSlice> {
        val groups = files.groupBy { typeOf(it.name) }
            .map { (type, list) -> StorageSlice(type, list.sumOf { it.bytes }, list.size) }
            .sortedByDescending { it.bytes }
        return folded(groups, top)
    }

    /** By album, biggest first; the rest after [top] become "Other". */
    fun byAlbum(files: List<StoredFile>, top: Int = 6): List<StorageSlice> {
        val groups = files.groupBy { it.album }
            .map { (album, list) -> StorageSlice(album, list.sumOf { it.bytes }, list.size) }
            .sortedByDescending { it.bytes }
        return folded(groups, top)
    }

    /** By year, oldest first (for the bar chart). */
    fun byYear(files: List<StoredFile>, zone: TimeZone): List<StorageSlice> =
        files.groupBy { Timeline.localDate(it.takenAtMillis, zone).year }
            .entries.sortedBy { it.key }
            .map { (year, list) -> StorageSlice(year.toString(), list.sumOf { it.bytes }, list.size) }

    fun typeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").uppercase()
        return when (ext) {
            "JPEG", "JPG" -> "JPG"
            "HEIF", "HEIC" -> "HEIC"
            "" -> "OTHER"
            else -> ext
        }
    }

    private fun folded(groups: List<StorageSlice>, top: Int): List<StorageSlice> {
        if (groups.size <= top + 1) return groups
        val rest = groups.drop(top)
        return groups.take(top) + StorageSlice("Other", rest.sumOf { it.bytes }, rest.sumOf { it.count })
    }
}
