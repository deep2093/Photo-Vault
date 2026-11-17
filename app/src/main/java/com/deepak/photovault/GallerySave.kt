package com.deepak.photovault

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import java.io.OutputStream

/**
 * Putting a new photo or video into the phone's gallery (shared 16 Nov; it was written six times: edited copies,
 * trimmed videos, living-photo videos, restores, "save opened file" and Secured folder restores).
 *
 * Android's way is always the same: make a hidden ("pending") entry, write the file into it, then show it; if
 * anything fails the entry is deleted so no half-written file is left. Each caller keeps its own choice of folders,
 * names and messages.
 */
internal object GallerySave {
    val photos: Uri get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val videos: Uri get() = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /**
     * A hidden entry in the first of [folders] Android accepts (it refuses some, like Download or another app's
     * folder); null when none does. [takenAtMillis] null leaves the date to Android.
     */
    fun begin(resolver: ContentResolver, collection: Uri, name: String, mime: String, folders: List<String>, takenAtMillis: Long?): Uri? =
        folders.firstNotNullOfOrNull { folder ->
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                if (takenAtMillis != null) put(MediaStore.MediaColumns.DATE_TAKEN, takenAtMillis)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            runCatching { resolver.insert(collection, values) }.getOrNull()
        }

    /** Shows the finished entry in the gallery. False when Android didn't take it. */
    fun finish(resolver: ContentResolver, uri: Uri): Boolean =
        runCatching { resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) }.getOrDefault(0) > 0

    /** Deletes an entry that wasn't finished. */
    fun discard(resolver: ContentResolver, uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
    }

    /**
     * The whole thing for a file that is ready to be copied in: tries each of [folders] in turn (also when the
     * writing itself fails in one) and returns true once one holds the finished file.
     */
    fun save(
        resolver: ContentResolver,
        collection: Uri,
        name: String,
        mime: String,
        folders: List<String>,
        takenAtMillis: Long?,
        write: (OutputStream) -> Unit,
    ): Boolean = saveUri(resolver, collection, name, mime, folders, takenAtMillis, write = write) != null

    /** What [saveUri] made: the new entry, and whether it went straight into Recently deleted. */
    class Saved(val uri: Uri, val trashed: Boolean)

    /**
     * As [save], but returns the new entry (17 Nov: the viewer shows a saved copy at once). With [trashed] the entry
     * goes straight into Recently deleted, where Android keeps it for 30 days (the original kept when an edit is
     * saved over a photo); if Android won't put it there, it is shown normally and [Saved.trashed] is false.
     */
    fun saveUri(
        resolver: ContentResolver,
        collection: Uri,
        name: String,
        mime: String,
        folders: List<String>,
        takenAtMillis: Long?,
        trashed: Boolean = false,
        write: (OutputStream) -> Unit,
    ): Saved? {
        for (folder in folders) {
            val uri = begin(resolver, collection, name, mime, listOf(folder), takenAtMillis) ?: continue
            val written = runCatching { resolver.openOutputStream(uri)?.use(write) ?: error("no stream") }.isSuccess
            if (written) {
                if (trashed) {
                    val inBin = runCatching {
                        resolver.update(uri, ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                            put(MediaStore.MediaColumns.IS_TRASHED, 1)
                        }, null, null)
                    }.getOrDefault(0) > 0
                    if (inBin) return Saved(uri, trashed = true)
                }
                val shown = runCatching { resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) }.isSuccess
                if (shown) return Saved(uri, trashed = false)
            }
            discard(resolver, uri)
        }
        return null
    }
}
