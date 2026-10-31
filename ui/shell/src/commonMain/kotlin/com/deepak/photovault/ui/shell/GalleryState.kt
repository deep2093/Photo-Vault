package com.deepak.photovault.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.deepak.photovault.core.backup.BackupRecord
import com.deepak.photovault.core.backup.DriveRecordCache
import com.deepak.photovault.core.backup.DriveTimeline
import com.deepak.photovault.core.backup.MemoryDriveRecordCache
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
/** Where a wallpaper goes (25 Oct). */
enum class WallpaperTarget(val label: String) { HOME("Home screen"), LOCK("Lock screen"), BOTH("Home and lock screens") }

interface MediaActions {
    /** Sets a photo as the phone's wallpaper. [onDone] gets null on success or a message to show. */
    fun setWallpaper(item: MediaItem, target: WallpaperTarget, onDone: (String?) -> Unit) = onDone("Not supported here.")

    /**
     * Moves phone photos to another album (folder). Either [albumId] (an existing album) or [newAlbumName] is given.
     * Android asks first. [onDone] gets null on success or a message to show.
     */
    fun moveToAlbum(items: List<MediaItem>, albumId: String?, newAlbumName: String?, onDone: (String?) -> Unit) = onDone("Not supported here.")

    /** Size of Pixallery's temporary files (previews and downloads that can always be made again). */
    fun appCacheBytes(): Long = 0L

    /** Deletes those temporary files. Photos and backups are never touched. */
    fun clearAppCache() = Unit

    fun requestAccess()

    /** Ask for "access photo location", so the details panel can show where a photo was taken. */
    fun requestLocationAccess() = Unit

    /** Open the phone's maps app at a photo's location. Nothing is looked up by PhotoVault itself. */
    fun openMap(latitude: Double, longitude: Double, label: String) = Unit
    fun share(items: List<MediaItem>)

    /** Adds backed-up photos to the Family Album (Phase 5A). */
    fun shareToFamilyAlbum(items: List<MediaItem>) = Unit

    /** Takes photos you added out of the Family Album. They stay in your backup. */
    fun removeFromFamilyAlbum(items: List<MediaItem>) = Unit

    /** A short message at the bottom of the screen. */
    fun toast(message: String) = Unit

    /** Shares photos that are only on the drive: they are fetched to the phone first. */
    fun shareDrive(items: List<MediaItem>) = Unit

    /** Android's own default-apps settings, to choose Pixallery for photos and videos. */
    fun openDefaultAppSettings() = Unit

    /** Brings photos that are only on the family drive back onto this phone (checked copy, "Restored" album). */
    fun saveToPhone(items: List<MediaItem>) = Unit

    /** Copies a photo or video another app opened in Pixallery into this phone's gallery (Pictures/Pixallery). */
    fun saveOpenedFile(item: MediaItem) = Unit
    /** Android 12+: Pixallery may delete without Android's own pop-up ("manage media" allowed), so it asks itself. */
    fun canDeleteWithoutAsking(): Boolean = false

    /** Android 12+ has the "manage media" setting; older versions always ask. */
    fun automaticCleanupAvailable(): Boolean = false

    /** Opens Android's setting to allow "manage media". */
    fun askDeletePermission() = Unit

    /** Whether Pixallery has already offered the "manage media" setting once. */
    fun deletePermissionOffered(): Boolean = true

    fun markDeletePermissionOffered() = Unit

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

/**
 * How Albums is laid out (24 Oct): the order of the cards (dragged in Settings > Albums) and each album's chosen
 * cover (1 to 4 photos). Kept on this phone.
 */
interface AlbumLayoutStore {
    fun loadOrder(): List<String>
    fun saveOrder(ids: List<String>)
    fun loadCovers(): Map<String, List<Long>>
    fun saveCovers(covers: Map<String, List<Long>>)

    /** How Albums is sorted: "" for the person's own order, else "NAME:asc", "ITEMS:desc" and so on. */
    fun loadSort(): String = ""
    fun saveSort(value: String) = Unit

    /** A cropped single-photo cover: card ID to [zoom, shiftX, shiftY] (see [CoverCrop]). */
    fun loadCrops(): Map<String, List<Float>> = emptyMap()
    fun saveCrops(crops: Map<String, List<Float>>) = Unit
}

class MemoryAlbumLayoutStore : AlbumLayoutStore {
    private var order: List<String> = emptyList()
    private var covers: Map<String, List<Long>> = emptyMap()
    override fun loadOrder() = order
    override fun saveOrder(ids: List<String>) {
        order = ids
    }
    override fun loadCovers() = covers
    override fun saveCovers(covers: Map<String, List<Long>>) {
        this.covers = covers
    }
}

/** How Albums can be sorted (31 Oct). [MINE] is the person's own order, set by holding and sliding a card. */
enum class AlbumSort(val label: String) {
    MINE("My order"),
    NAME("Name"),
    ITEMS("Items"),
    RECENT("Recently updated"),
    ;

    /** The direction a first tap gives: names A to Z, the rest biggest and newest first. */
    val firstDescending: Boolean get() = this == ITEMS || this == RECENT
}

/** What sorting needs to know about one card. */
data class AlbumSortKey(val id: String, val name: String, val count: Int, val newestMillis: Long)

/**
 * The maths for cropping an album cover (31 Oct), pure and tested. A crop is kept as [zoom, shiftX, shiftY]: the
 * photo is first filled into the square card (the middle square), then zoomed about the centre by zoom and shifted by
 * the fractions of the card's side. The same three numbers work at any card size.
 */
object CoverCrop {
    const val MIN_FRACTION = 0.15f

    /** Keeps the frame ([left], [top], [side] in photo pixels) inside a [w] x [h] photo and not smaller than a small part of it. */
    fun clamp(left: Float, top: Float, side: Float, w: Float, h: Float): Triple<Float, Float, Float> {
        val most = minOf(w, h)
        val s = side.coerceIn(most * MIN_FRACTION, most)
        return Triple(left.coerceIn(0f, w - s), top.coerceIn(0f, h - s), s)
    }

    /** The three numbers to keep for a frame. A frame that is the middle square gives (1, 0, 0). */
    fun toCover(left: Float, top: Float, side: Float, w: Float, h: Float): List<Float> {
        val zoom = minOf(w, h) / side
        val cx = left + side / 2f
        val cy = top + side / 2f
        return listOf(zoom, -(cx - w / 2f) / side, -(cy - h / 2f) / side)
    }
}

/** Pure sorting rules for Albums, tested. */
object AlbumSorting {
    /** The IDs of [keys] in [sort] order; [MINE] keeps them as given. Ties fall back to the name, then the ID. */
    fun apply(keys: List<AlbumSortKey>, sort: AlbumSort, descending: Boolean): List<String> {
        if (sort == AlbumSort.MINE) return keys.map { it.id }
        val byName = compareBy<AlbumSortKey>({ it.name.lowercase() }, { it.id })
        val primary: Comparator<AlbumSortKey> = when (sort) {
            AlbumSort.NAME, AlbumSort.MINE -> byName
            AlbumSort.ITEMS -> compareBy { it.count }
            AlbumSort.RECENT -> compareBy { it.newestMillis }
        }
        // Ties always go A to Z, whichever way the sort runs.
        val order = (if (descending) primary.reversed() else primary).then(byName)
        return keys.sortedWith(order).map { it.id }
    }

    /** "NAME:asc" and back; anything unreadable means the person's own order. */
    fun encode(sort: AlbumSort, descending: Boolean): String = if (sort == AlbumSort.MINE) "" else "${sort.name}:${if (descending) "desc" else "asc"}"

    fun decode(value: String): Pair<AlbumSort, Boolean> {
        val sort = AlbumSort.entries.firstOrNull { it.name == value.substringBefore(':') } ?: return AlbumSort.MINE to false
        if (sort == AlbumSort.MINE) return AlbumSort.MINE to false
        return sort to (value.substringAfter(':', "") == "desc")
    }
}

/** Pure rules for the order of album cards, tested. */
object AlbumOrder {
    /**
     * [defaults] in the order they'd normally show; [saved] is the person's own order. Cards in [saved] come first
     * in that order, new ones follow in their normal order; cards that no longer exist are dropped.
     */
    fun apply(defaults: List<String>, saved: List<String>): List<String> {
        val present = defaults.toSet()
        val kept = saved.filter { it in present }.distinct()
        return kept + defaults.filter { it !in kept }
    }

    /** Moves the card at [from] to [to] (both positions in [ids]). */
    fun move(ids: List<String>, from: Int, to: Int): List<String> {
        if (from !in ids.indices || to !in ids.indices || from == to) return ids
        val list = ids.toMutableList()
        list.add(to, list.removeAt(from))
        return list
    }

    /** A cover of 1 to 4 photos that still exist; empty means "use the newest". */
    fun cover(chosen: List<Long>?, exists: (Long) -> Boolean): List<Long> = chosen.orEmpty().filter(exists).distinct().take(4)
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
    private val layoutStore: AlbumLayoutStore = MemoryAlbumLayoutStore(),
    /** What the drive held when last read, so backed-up photos still show offline (Phase 5, 25 Oct). */
    private val driveCache: DriveRecordCache = MemoryDriveRecordCache(),
    /** The Family Album as last read, kept for offline. */
    private val familyCache: DriveRecordCache = MemoryDriveRecordCache(),
) {
    /** The person's own order of album cards (album IDs plus [FAVOURITES_CARD] and [VIDEOS_CARD]). */
    var albumOrder by mutableStateOf(layoutStore.loadOrder())
        private set

    /** Chosen covers: album card ID to 1-4 photo IDs. */
    var albumCovers by mutableStateOf(layoutStore.loadCovers())
        private set

    /** Every album card ID in display order, hidden ones included (Settings > Albums). */
    fun orderedCards(): List<String> =
        AlbumOrder.apply(listOf(FAVOURITES_CARD, VIDEOS_CARD) + allAlbums.map { it.id }, albumOrder)

    fun moveAlbum(from: Int, to: Int) {
        albumOrder = AlbumOrder.move(orderedCards(), from, to)
        layoutStore.saveOrder(albumOrder)
    }

    /** How Albums is sorted now; moving a card by hand switches back to [AlbumSort.MINE]. */
    var albumSort by mutableStateOf(AlbumSorting.decode(layoutStore.loadSort()).first)
        private set
    var albumSortDescending by mutableStateOf(AlbumSorting.decode(layoutStore.loadSort()).second)
        private set

    /** Choosing a sort; choosing the one already on turns it round (31 Oct). */
    fun chooseSort(sort: AlbumSort) {
        if (sort == albumSort && sort != AlbumSort.MINE) {
            albumSortDescending = !albumSortDescending
        } else {
            albumSort = sort
            albumSortDescending = sort.firstDescending
        }
        layoutStore.saveSort(AlbumSorting.encode(albumSort, albumSortDescending))
    }

    /**
     * The person moved a card by hand. [shown] is the order on screen now (the sorted order if a sort was on), so
     * the cards stay where they are and Albums goes back to "My order". Hidden cards keep their old places at the end.
     */
    fun placeAlbums(shown: List<String>) {
        val hiddenTail = orderedCards().filter { it !in shown }
        albumOrder = shown + hiddenTail
        layoutStore.saveOrder(albumOrder)
        if (albumSort != AlbumSort.MINE) {
            albumSort = AlbumSort.MINE
            albumSortDescending = false
            layoutStore.saveSort("")
        }
    }

    /** Crops of one-photo covers: card ID to [zoom, shiftX, shiftY]. */
    var albumCrops by mutableStateOf(layoutStore.loadCrops())
        private set

    /** [crop] only counts for a one-photo cover; any other change of cover forgets an old crop. */
    fun setCover(cardId: String, photoIds: List<Long>, crop: List<Float>? = null) {
        albumCovers = if (photoIds.isEmpty()) albumCovers - cardId else albumCovers + (cardId to photoIds.take(4))
        layoutStore.saveCovers(albumCovers)
        val keep = crop?.takeIf { photoIds.size == 1 && it.size == 3 }
        albumCrops = if (keep != null) albumCrops + (cardId to keep) else albumCrops - cardId
        layoutStore.saveCrops(albumCrops)
    }

    /** The crop of a card's one-photo cover, if the person made one. */
    fun cropOf(cardId: String): List<Float>? =
        albumCrops[cardId]?.takeIf { albumCovers[cardId]?.size == 1 }

    /** The photos to show on a card: the chosen cover if any are still here, otherwise the newest. */
    fun coverOf(cardId: String, newest: MediaItem?): List<MediaItem> {
        val chosen = AlbumOrder.cover(albumCovers[cardId]) { find(it) != null }.mapNotNull(::find)
        return chosen.ifEmpty { listOfNotNull(newest) }
    }

    var access by mutableStateOf(MediaAccess.NONE)

    /** Years, Months or All on Photos (24 Oct; replaced Day / Month / Year headings). */
    var view by mutableStateOf(LibraryView.ALL)

    /** Levels left behind by opening a year or a month, so Back climbs the same steps (25 Oct). */
    val viewHistory = androidx.compose.runtime.mutableStateListOf<LibraryView>()

    /** A day section for All to scroll to once it shows (after tapping a month card). */
    var jumpTo by mutableStateOf<String?>(null)

    var items by mutableStateOf<List<MediaItem>>(emptyList())
        private set

    /** Recently deleted: the phone's, plus the drive's (photos deleted from the family drive keep 30 days too). */
    var trashed by mutableStateOf<List<TrashedItem>>(emptyList())
        private set

    private var phoneTrash: List<TrashedItem> = emptyList()
    private var driveTrash: List<TrashedItem> = emptyList()

    /** The drive's Recently deleted was read. */
    fun setDriveTrash(items: List<com.deepak.photovault.core.backup.TrashedBackup>) {
        driveTrash = items.map {
            TrashedItem(DriveTimeline.trashedItem(it), it.deletedAtMillis + com.deepak.photovault.core.backup.BackupCatalog.TRASH_DAYS * 24L * 60 * 60 * 1000)
        }
        trashed = (phoneTrash + driveTrash).sortedByDescending { it.expiresAtMillis }
    }

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

    /** Backed-up files from the drive; the ones no longer on the phone join the timeline. */
    private var driveRecords: List<BackupRecord> = driveCache.load()

    /** The Family Album (Phase 5A): photos from everyone's backups that the family chose to share, newest first. */
    var familyAlbum by mutableStateOf(DriveTimeline.driveOnly(familyCache.load(), emptyList()).sortedByDescending { it.takenAtMillis })
        private set

    private var familyById: Map<Long, MediaItem> = familyAlbum.associateBy { it.id }

    fun updateFamilyAlbum(records: List<BackupRecord>) {
        familyCache.save(records)
        familyAlbum = DriveTimeline.driveOnly(records, emptyList()).sortedByDescending { it.takenAtMillis }
        familyById = familyAlbum.associateBy { it.id }
    }

    /** Photos only on the drive right now (not on the phone). A photo in the phone's Recently deleted counts as gone from the phone. */
    private var driveOnly: List<MediaItem> = emptyList()

    /** The drive was read (or a backup finished): show what it holds that the phone doesn't. */
    fun setDriveRecords(records: List<BackupRecord>) {
        driveRecords = records
        driveCache.save(records)
        recomputeDriveOnly()
        allAlbums = PhoneAlbums.from(everything + driveOnly)
        applyVisible()
    }

    private fun recomputeDriveOnly() {
        // 25 Oct: a photo deleted from the phone (even one still in its Recently deleted) shows from the drive at once;
        // before, it stayed hidden for 30 days, which looked like the backup had lost it.
        driveOnly = DriveTimeline.driveOnly(driveRecords, everything)
    }

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

    /** A photo or video another app asked Pixallery to open, which isn't in this phone's gallery (Phase 5B-2). */
    private val externalById = HashMap<Long, MediaItem>()

    fun openExternal(item: MediaItem) {
        externalById[item.id] = item
    }

    /** True for a file another app opened here that is not part of this phone's gallery: only Share and Save are safe for it. */
    fun isExternal(item: MediaItem): Boolean = item.id in externalById && item.id !in byId

    fun find(id: Long): MediaItem? = byId[id] ?: familyById[id] ?: externalById[id]

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
        phoneTrash = bin
        trashed = (phoneTrash + driveTrash).sortedByDescending { it.expiresAtMillis }
        recomputeDriveOnly()
        allAlbums = PhoneAlbums.from(all + driveOnly)
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
        phoneTrash = phoneTrash.filterNot { it.item.id in ids }
        driveTrash = driveTrash.filterNot { it.item.id in ids }
        trashed = trashed.filterNot { it.item.id in ids }
        driveOnly = driveOnly.filterNot { it.id in ids }
        applyVisible()
    }

    private fun applyVisible() {
        val merged = if (driveOnly.isEmpty()) everything else everything + driveOnly
        val visible = if (hiddenAlbums.isEmpty()) merged else merged.filter { it.albumId !in hiddenAlbums }
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
