package com.deepak.photovault.core.backup

import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.MediaType

/**
 * The one timeline (Phase 5, 25 Oct): photos that are backed up on the drive but no longer on this phone are shown
 * next to the phone's own, so deleting a backed-up photo from the phone doesn't make it disappear from Photos.
 * A drive-only photo is a [MediaItem] whose uri is `drive:<path on the drive>`; the platform fetches and caches it.
 */
object DriveTimeline {
    const val SCHEME = "drive:"

    fun uriOf(drivePath: String) = SCHEME + drivePath

    fun pathOf(uri: String): String? = if (uri.startsWith(SCHEME)) uri.removePrefix(SCHEME) else null

    /** Albums that exist only on the drive get this ID prefix, so they can't clash with a phone album. */
    const val ALBUM_PREFIX = "drive-album:"

    /** A stable ID that can't clash with a phone ID (MediaStore IDs are positive): negative, from the drive path. */
    fun idOf(drivePath: String): Long {
        var h = 1125899906842597L
        for (c in drivePath) h = 31 * h + c.code
        return -(h and Long.MAX_VALUE).coerceAtLeast(1L)
    }

    /**
     * The backed-up files that aren't on [onPhone], as items. Matching follows the restore rule: the same
     * name, size and capture time, or (for a restored copy, whose date Android may reset) the same name and size.
     */
    fun driveOnly(records: List<BackupRecord>, onPhone: List<MediaItem>): List<MediaItem> {
        if (records.isEmpty()) return emptyList()
        val keys = HashSet<String>(onPhone.size * 2)
        val nameAndSize = HashSet<String>(onPhone.size * 2)
        val albumIdByName = HashMap<String, String>()
        for (p in onPhone) {
            keys += BackupSelection.key(p)
            nameAndSize += "${p.displayName}|${p.sizeBytes}"
            albumIdByName.putIfAbsent(p.albumName.lowercase(), p.albumId)
        }
        return records
            .filter { it.key !in keys && "${it.fileName}|${it.sizeBytes}" !in nameAndSize }
            .map { r ->
                val album = r.album?.takeIf { it.isNotBlank() } ?: "Backed up"
                MediaItem(
                    id = idOf(r.drivePath),
                    uri = uriOf(r.drivePath),
                    type = if (r.isVideo) MediaType.VIDEO else MediaType.PHOTO,
                    displayName = r.fileName,
                    takenAtMillis = r.takenAtMillis,
                    sizeBytes = r.sizeBytes,
                    width = 0,
                    height = 0,
                    albumId = albumIdByName[album.lowercase()] ?: (ALBUM_PREFIX + album),
                    albumName = album,
                    mimeType = r.mimeType,
                )
            }
    }

    /** A trashed backup as an item (same ID as before it was deleted), so Recently deleted can show and restore it. */
    fun trashedItem(t: TrashedBackup): MediaItem = driveOnly(listOf(t.record), emptyList()).first().copy(uri = uriOf(t.trashPath))
}

/** True for a photo or video that is only on the family drive (not on this phone). */
val MediaItem.isDriveOnly: Boolean get() = uri.startsWith(DriveTimeline.SCHEME)

/** The backed-up records last read from the drive, kept on the phone so Photos still shows them offline. */
interface DriveRecordCache {
    fun load(): List<BackupRecord>
    fun save(records: List<BackupRecord>)
}

class MemoryDriveRecordCache(private var records: List<BackupRecord> = emptyList()) : DriveRecordCache {
    override fun load() = records
    override fun save(records: List<BackupRecord>) {
        this.records = records
    }
}
