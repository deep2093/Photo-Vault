package com.deepak.photovault.core.media

import kotlinx.datetime.TimeZone

/** One slice of a storage chart: a name, how many bytes, how many files. */
data class StorageSlice(val label: String, val bytes: Long, val count: Int)

/**
 * A file for the Storage page: from the phone or from the drive's backup records. [ref] finds it again: the
 * phone's media ID, or the file's path on the drive.
 */
data class StoredFile(
    val name: String,
    val bytes: Long,
    val takenAtMillis: Long,
    val album: String,
    val ref: String = "",
    val isVideo: Boolean = false,
)

/** What a file list is sorted by (23 Oct): name, date taken (date and time together), size or type. */
enum class FileSort(val label: String) { NAME("Name"), DATE("Date"), SIZE("Size"), TYPE("Type") }

/** Tapping a sort button picks it; tapping it again turns the order round. */
data class FileOrder(val by: FileSort = FileSort.DATE, val ascending: Boolean = false) {
    fun tapped(key: FileSort): FileOrder =
        if (key == by) copy(ascending = !ascending) else FileOrder(key, ascending = key == FileSort.NAME || key == FileSort.TYPE)
}

/** Pure sums behind Settings > Storage (24 Oct), tested. */
object StorageStats {
    /** The label of the slice that gathers everything after the biggest few. */
    const val OTHER = "Other"

    fun fromPhone(items: List<MediaItem>): List<StoredFile> =
        items.map { StoredFile(it.displayName, it.sizeBytes, it.takenAtMillis, it.albumName.ifBlank { "No album" }, it.id.toString(), it.isVideo) }

    /** The files behind one slice of [byType] (the "Other" slice: every type not named on its own). */
    fun ofType(files: List<StoredFile>, slices: List<StorageSlice>, label: String): List<StoredFile> {
        if (label != OTHER) return files.filter { typeOf(it) == label }
        val named = slices.map { it.label }.filter { it != OTHER }.toSet()
        return files.filter { typeOf(it) !in named }
    }

    /** The files behind one slice of [byAlbum]. */
    fun ofAlbum(files: List<StoredFile>, slices: List<StorageSlice>, label: String): List<StoredFile> {
        val named = slices.map { it.label }.filter { it != OTHER }.toSet()
        return if (label == OTHER && OTHER !in files.map { it.album }.toSet()) files.filter { it.album !in named } else files.filter { it.album == label }
    }

    fun ofYear(files: List<StoredFile>, year: String, zone: TimeZone): List<StoredFile> =
        files.filter { Timeline.localDate(it.takenAtMillis, zone).year.toString() == year }

    fun sorted(files: List<StoredFile>, order: FileOrder): List<StoredFile> {
        val byKey: Comparator<StoredFile> = when (order.by) {
            FileSort.NAME -> compareBy<StoredFile, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
            FileSort.DATE -> compareBy { it.takenAtMillis }
            FileSort.SIZE -> compareBy { it.bytes }
            FileSort.TYPE -> compareBy<StoredFile> { typeOf(it) }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        // Ties keep a steady order (by name), so the list doesn't shuffle between sorts.
        val steady = byKey.thenBy { it.name }.thenBy { it.ref }
        return files.sortedWith(if (order.ascending) steady else steady.reversed())
    }

    /** By kind (Photos, Videos, PNG, GIF), biggest first. */
    fun byType(files: List<StoredFile>, top: Int = 4): List<StorageSlice> {
        val groups = files.groupBy { typeOf(it) }
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

    const val PHOTOS = "Photos"
    const val VIDEOS = "Videos"
    const val PNG = "PNG"
    const val GIF = "GIF"

    private val videoTypes = setOf("MP4", "M4V", "MOV", "MKV", "WEBM", "AVI", "3GP", "3G2", "WMV", "FLV", "MPG", "MPEG", "TS", "MTS", "M2TS", "VOB", "OGV", "MXF")

    /** The four kinds Storage counts (25 Oct): JPEG and HEIC are Photos, every video format is Videos. Other pictures count as Photos. */
    fun typeOf(file: StoredFile): String = typeOf(file.name, file.isVideo)

    fun typeOf(name: String, isVideo: Boolean = false): String {
        val ext = name.substringAfterLast('.', "").uppercase()
        return when {
            isVideo || ext in videoTypes -> VIDEOS
            ext == "PNG" -> PNG
            ext == "GIF" -> GIF
            else -> PHOTOS
        }
    }

    private fun folded(groups: List<StorageSlice>, top: Int): List<StorageSlice> {
        if (groups.size <= top + 1) return groups
        val rest = groups.drop(top)
        return groups.take(top) + StorageSlice("Other", rest.sumOf { it.bytes }, rest.sumOf { it.count })
    }
}
