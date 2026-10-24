package com.deepak.photovault.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.deepak.photovault.core.media.FavouritesStore
import com.deepak.photovault.core.media.MediaAccess
import com.deepak.photovault.core.media.LibraryView
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.MediaDetailsSource
import com.deepak.photovault.core.media.MediaLibrary
import com.deepak.photovault.core.media.NoMediaDetails
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

    /** Ask for "access photo location", so the details panel can show where a photo was taken. */
    fun requestLocationAccess() = Unit

    /** Open the phone's maps app at a photo's location. Nothing is looked up by PhotoVault itself. */
    fun openMap(latitude: Double, longitude: Double, label: String) = Unit
    fun share(items: List<MediaItem>)
    /** Android asks first; [onDone] says whether the person agreed (the delete animation plays then). */
    fun moveToRecentlyDeleted(items: List<MediaItem>, onDone: (Boolean) -> Unit = {})
    fun restore(items: List<MediaItem>)
    fun deleteForever(items: List<MediaItem>, onDone: (Boolean) -> Unit = {})

    /** Android 13+: ask to show notifications (backup progress and failed backups). */
    fun requestNotifications() = Unit

    /** Opens the phone's battery settings so scheduled backups aren't held back. */
    fun openBatterySettings() = Unit

    /** Lets the person pick a photo for a profile picture; hands back a screen-sized copy to position and zoom. */
    fun pickProfilePhoto(onPicked: (PickedImage) -> Unit) = Unit

    /** Total and free bytes of the phone's own storage, or null if unknown. */
    fun phoneStorage(): Pair<Long, Long>? = null

    /** Send feedback (23 Oct): pick up to five photos or videos to go with a note. */
    fun pickFeedbackFiles(onPicked: (List<FeedbackFile>) -> Unit) = Unit

    /** Reads a picked file, or a copy kept while the note waits to be sent. */
    fun openFeedbackFile(file: FeedbackFile): com.deepak.photovault.core.drive.ByteSource? = null

    /** Notes that couldn't be sent yet (drive out of reach), kept on the phone with copies of their files. */
    val feedbackOutbox: FeedbackOutbox get() = NoFeedbackOutbox

    /** The app's version, sent with feedback. */
    val appVersion: String get() = ""

    /** What went wrong the last time the app closed unexpectedly, if it did (24 Oct). */
    fun lastCrash(): String? = null

    fun clearLastCrash() = Unit

    /** Moves every photo of an album to Recently deleted (Android asks first). */
    fun deleteAlbum(albumId: String, onDone: (Boolean) -> Unit = {}) = Unit

    /**
     * Renames a phone album (its folder). Android asks to allow changes to photos taken by other apps. [onDone] gets
     * null on success or a message to show.
     */
    fun renameAlbum(albumId: String, newName: String, onDone: (String?) -> Unit) = Unit

    /** Cuts the square [left], [top], [side] (pixels of [image]) out and shrinks it to a 256 px JPEG. */
    fun cropProfilePhoto(image: PickedImage, left: Int, top: Int, side: Int, onDone: (ByteArray) -> Unit) = Unit
}

/** Albums the person hid (Settings > Albums), by album ID. Android keeps them in SharedPreferences. */
interface HiddenAlbumsStore {
    fun load(): Set<String>
    fun save(ids: Set<String>)
}

class MemoryHiddenAlbumsStore(private var ids: Set<String> = emptySet()) : HiddenAlbumsStore {
    override fun load() = ids
    override fun save(ids: Set<String>) {
        this.ids = ids
    }
}

/** A picked photo ready to position: JPEG bytes and their size in pixels. */
class PickedImage(val jpeg: ByteArray, val width: Int, val height: Int)

/** Does nothing; for previews and tests. */
object NoMediaActions : MediaActions {
    override fun requestAccess() = Unit
    override fun share(items: List<MediaItem>) = Unit
    override fun moveToRecentlyDeleted(items: List<MediaItem>, onDone: (Boolean) -> Unit) = Unit
    override fun restore(items: List<MediaItem>) = Unit
    override fun deleteForever(items: List<MediaItem>, onDone: (Boolean) -> Unit) = Unit
}

/** A photo or video added to a feedback note. [uri] is where to read it (a picked file or a kept copy). */
data class FeedbackFile(val uri: String, val name: String, val sizeBytes: Long, val isVideo: Boolean)

/** Feedback waiting for the drive. Adding copies the files into the app's own storage, so they can't vanish. */
interface FeedbackOutbox {
    fun add(note: com.deepak.photovault.core.household.FeedbackNote, files: List<FeedbackFile>)
    fun all(): List<Pair<com.deepak.photovault.core.household.FeedbackNote, List<FeedbackFile>>>
    fun remove(id: String)
}

object NoFeedbackOutbox : FeedbackOutbox {
    override fun add(note: com.deepak.photovault.core.household.FeedbackNote, files: List<FeedbackFile>) = Unit
    override fun all(): List<Pair<com.deepak.photovault.core.household.FeedbackNote, List<FeedbackFile>>> = emptyList()
    override fun remove(id: String) = Unit
}

/** The phone's photos as the screens see them. Refreshes itself when the phone's library changes. */
@Stable
class GalleryState(
    private val library: MediaLibrary,
    private val favouritesStore: FavouritesStore,
    /** Camera, lens, exposure and location for the viewer's details panel. */
    val detailsSource: MediaDetailsSource = NoMediaDetails,
    private val hiddenStore: HiddenAlbumsStore = MemoryHiddenAlbumsStore(),
) {
    var access by mutableStateOf(MediaAccess.NONE)

    /** Years, Months or All on Photos (24 Oct; replaced Day / Month / Year headings). */
    var view by mutableStateOf(LibraryView.ALL)

    /** A day section for All to scroll to once it shows (after tapping a month card). */
    var jumpTo by mutableStateOf<String?>(null)

    var items by mutableStateOf<List<MediaItem>>(emptyList())
        private set

    var trashed by mutableStateOf<List<TrashedItem>>(emptyList())
        private set

    var albums by mutableStateOf<List<PhoneAlbum>>(emptyList())
        private set

    /** Every album, hidden ones included (for Settings > Albums). */
    var allAlbums by mutableStateOf<List<PhoneAlbum>>(emptyList())
        private set

    /**
     * Hidden albums (24 Oct): left out of Photos, Albums and search. The photos stay on the phone and still back up.
     */
    var hiddenAlbums by mutableStateOf(hiddenStore.load())
        private set

    private var everything: List<MediaItem> = emptyList()

    fun setHidden(albumId: String, hidden: Boolean) {
        hiddenAlbums = if (hidden) hiddenAlbums + albumId else hiddenAlbums - albumId
        hiddenStore.save(hiddenAlbums)
        applyVisible()
    }

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
        everything = all
        trashed = bin.sortedBy { it.expiresAtMillis }
        allAlbums = PhoneAlbums.from(all)
        applyVisible()
    }

    /** Is the Favourites or Videos card (or an album) hidden in Albums? */
    fun isHidden(id: String) = id in hiddenAlbums

    /**
     * Takes deleted photos off screen at once, as they fly into the bin (23 Oct); the next reload from the
     * phone's library confirms it.
     */
    fun dropNow(ids: Set<Long>) {
        if (ids.isEmpty()) return
        everything = everything.filterNot { it.id in ids }
        trashed = trashed.filterNot { it.item.id in ids }
        applyVisible()
    }

    private fun applyVisible() {
        val visible = if (hiddenAlbums.isEmpty()) everything else everything.filter { it.albumId !in hiddenAlbums }
        items = visible
        byId = visible.associateBy { it.id }
        albums = allAlbums.filter { it.id !in hiddenAlbums }
    }

    companion object {
        private const val CHANGE_SETTLE_MS = 400L

        /** Not real albums: hiding these hides only their card in Albums (24 Oct). */
        const val FAVOURITES_CARD = "@favourites"
        const val VIDEOS_CARD = "@videos"
    }
}
