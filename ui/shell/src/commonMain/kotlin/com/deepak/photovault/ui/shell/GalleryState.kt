package com.deepak.photovault.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.deepak.photovault.core.media.FavouritesStore
import com.deepak.photovault.core.media.MediaAccess
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.MediaLibrary
import com.deepak.photovault.core.media.PhoneAlbum
import com.deepak.photovault.core.media.PhoneAlbums
import com.deepak.photovault.core.media.TimelineGrouping
import com.deepak.photovault.core.media.TrashedItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * Things only the platform can do with the phone's photos. Android shows its own consent prompt for
 * Recently deleted, restore and delete forever; the app never goes around it.
 */
interface MediaActions {
    fun requestAccess()
    fun share(items: List<MediaItem>)
    fun moveToRecentlyDeleted(items: List<MediaItem>)
    fun restore(items: List<MediaItem>)
    fun deleteForever(items: List<MediaItem>)
}

/** Does nothing; for previews and tests. */
object NoMediaActions : MediaActions {
    override fun requestAccess() = Unit
    override fun share(items: List<MediaItem>) = Unit
    override fun moveToRecentlyDeleted(items: List<MediaItem>) = Unit
    override fun restore(items: List<MediaItem>) = Unit
    override fun deleteForever(items: List<MediaItem>) = Unit
}

/** The phone's photos as the screens see them. Refreshes itself when the phone's library changes. */
@Stable
class GalleryState(private val library: MediaLibrary, private val favouritesStore: FavouritesStore) {
    var access by mutableStateOf(MediaAccess.NONE)

    var grouping by mutableStateOf(TimelineGrouping.DAY)

    var items by mutableStateOf<List<MediaItem>>(emptyList())
        private set

    var trashed by mutableStateOf<List<TrashedItem>>(emptyList())
        private set

    var albums by mutableStateOf<List<PhoneAlbum>>(emptyList())
        private set

    /** True after the first load, so the screen can tell "empty" from "still loading". */
    var loaded by mutableStateOf(false)
        private set

    var loadFailed by mutableStateOf(false)
        private set

    var favourites by mutableStateOf(favouritesStore.load())
        private set

    private var byId: Map<Long, MediaItem> = emptyMap()

    fun find(id: Long): MediaItem? = byId[id]

    fun isFavourite(item: MediaItem) = item.id in favourites

    suspend fun refresh() {
        if (access == MediaAccess.NONE) {
            setItems(emptyList(), emptyList())
            return
        }
        try {
            setItems(library.loadAll(), library.loadTrashed())
            loadFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A failed read keeps what is on screen; the next change notification retries.
            loadFailed = true
        }
        loaded = true
    }

    /** Loads now, then again after each burst of changes (a camera burst produces many notifications). */
    suspend fun watch() {
        refresh()
        library.changes().collectLatest {
            delay(CHANGE_SETTLE_MS)
            refresh()
        }
    }

    /** Favourite all when any is not a favourite yet; otherwise remove them all. */
    fun toggleFavourite(selected: Collection<MediaItem>) {
        val ids = selected.map { it.id }.toSet()
        favourites = if (ids.all { it in favourites }) favourites - ids else favourites + ids
        favouritesStore.save(favourites)
    }

    private fun setItems(all: List<MediaItem>, bin: List<TrashedItem>) {
        items = all
        byId = all.associateBy { it.id }
        trashed = bin.sortedBy { it.expiresAtMillis }
        albums = PhoneAlbums.from(all)
    }

    private companion object {
        const val CHANGE_SETTLE_MS = 400L
    }
}
