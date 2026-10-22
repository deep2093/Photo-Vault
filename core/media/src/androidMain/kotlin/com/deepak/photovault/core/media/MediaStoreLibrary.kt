package com.deepak.photovault.core.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/**
 * The phone's photos and videos from Android's MediaStore. Read-only: changes (trash, restore, delete) go
 * through Android's own consent prompts in the app module, never around them.
 */
class MediaStoreLibrary(context: Context) : MediaLibrary {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val filesUri: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    override suspend fun loadAll(): List<MediaItem> = withContext(Dispatchers.IO) { query(trashed = false).map { it.item } }

    override suspend fun loadTrashed(): List<TrashedItem> = withContext(Dispatchers.IO) { query(trashed = true) }

    override fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(filesUri, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    private fun query(trashed: Boolean): List<TrashedItem> {
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)",
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                ),
            )
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
            // Recently deleted lists only trashed items; the gallery never shows them.
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, if (trashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE)
            // Files still being written (a photo being saved) are left out until they are complete.
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_EXCLUDE)
        }
        val out = ArrayList<TrashedItem>()
        resolver.query(filesUri, PROJECTION, args, null)?.use { c -> readAll(c, out) }
        return out
    }

    private fun readAll(c: Cursor, out: MutableList<TrashedItem>) {
        val id = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val type = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
        val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val taken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
        val added = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val width = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
        val height = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
        val duration = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
        val bucketId = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
        val bucketName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        val mime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        val expires = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_EXPIRES)
        while (c.moveToNext()) {
            val rowId = c.getLong(id)
            val isVideo = c.getInt(type) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val takenMillis = c.getLong(taken).takeIf { it > 0 } ?: (c.getLong(added) * 1000)
            out.add(
                TrashedItem(
                    item = MediaItem(
                        id = rowId,
                        uri = ContentUris.withAppendedId(base, rowId).toString(),
                        type = if (isVideo) MediaType.VIDEO else MediaType.PHOTO,
                        displayName = c.getString(name).orEmpty(),
                        takenAtMillis = takenMillis,
                        sizeBytes = c.getLong(size),
                        width = c.getInt(width),
                        height = c.getInt(height),
                        durationMillis = if (isVideo) c.getLong(duration) else 0,
                        albumId = c.getString(bucketId).orEmpty(),
                        albumName = c.getString(bucketName).orEmpty(),
                        mimeType = c.getString(mime).orEmpty(),
                    ),
                    // DATE_EXPIRES is in seconds; trashed items get it set by Android (30 days).
                    expiresAtMillis = c.getLong(expires) * 1000,
                ),
            )
        }
    }

    private companion object {
        val PROJECTION = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_EXPIRES,
        )
    }
}
