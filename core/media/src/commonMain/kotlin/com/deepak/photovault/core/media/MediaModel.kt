package com.deepak.photovault.core.media

import kotlinx.coroutines.flow.Flow

enum class MediaType { PHOTO, VIDEO }

/**
 * One photo or video on this device. [uri] is opaque to shared code: a content:// URI on Android,
 * a file path on a PC later.
 */
data class MediaItem(
    val id: Long,
    val uri: String,
    val type: MediaType,
    val displayName: String,
    /** When it was taken, or added if the file has no capture time. */
    val takenAtMillis: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    /** Videos only. */
    val durationMillis: Long = 0,
    val albumId: String,
    val albumName: String,
    val mimeType: String,
) {
    val isVideo: Boolean get() = type == MediaType.VIDEO
}

/** A photo in Recently deleted, removed for good at [expiresAtMillis] (30 days after deleting on Android). */
data class TrashedItem(val item: MediaItem, val expiresAtMillis: Long)

/** How much of the phone's library the app may see. Android 14+ lets people share only some photos. */
enum class MediaAccess { NONE, PARTIAL, FULL }

/** A photo for the thumbnail loader: the platform turns this into a fast, cached thumbnail. */
data class Thumb(val uri: String, val isVideo: Boolean, val sizePx: Int)

/**
 * Where photos come from. Android: MediaStore. A future PC app: chosen folders.
 * Implementations do their own threading; all calls are safe from the main thread.
 */
interface MediaLibrary {
    suspend fun loadAll(): List<MediaItem>

    suspend fun loadTrashed(): List<TrashedItem>

    /** Emits whenever photos are added, changed or deleted on the device. */
    fun changes(): Flow<Unit>
}

/** Favourites are kept by the app (Android's own favourite flag needs a system prompt for every tap). */
interface FavouritesStore {
    fun load(): Set<Long>

    fun save(ids: Set<Long>)
}

class MemoryFavouritesStore(private var ids: Set<Long> = emptySet()) : FavouritesStore {
    override fun load() = ids

    override fun save(ids: Set<Long>) {
        this.ids = ids
    }
}
