package com.deepak.photovault.ui.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.deepak.photovault.designsystem.AdaptiveLayout
import com.deepak.photovault.designsystem.GlassSettings
import com.deepak.photovault.designsystem.GlassTheme
import com.deepak.photovault.designsystem.GlassThemes
import com.deepak.photovault.designsystem.PageEasing

enum class Destination(val label: String, val icon: ImageVector) {
    PHOTOS("Photos", Icons.Rounded.PhotoLibrary),
    ALBUMS("Albums", Icons.Rounded.Collections),
    FAMILY("Family", Icons.Rounded.Groups),
    SETTINGS("Settings", Icons.Rounded.Settings),
    ;

    companion object {
        /** Sections whose top pills and navigation bar slide away while scrolling down (24 Oct). */
        val hidesChrome = setOf(PHOTOS, ALBUMS)
    }
}

/** Whether the floating pills and navigation bar are tucked away while scrolling. */
@Stable
class ChromeState {
    var hidden by mutableStateOf(false)
}

/** Given only to sections that hide their chrome on scroll; null elsewhere. */
val LocalChrome = androidx.compose.runtime.staticCompositionLocalOf<ChromeState?> { null }

/** What the platform reports. Android: battery saver and "remove animations"; PC: both false for now. */
data class PlatformSignals(
    val powerSaveMode: Boolean = false,
    val systemReduceMotion: Boolean = false,
    /** The app is on screen. Drive checks pause while it is in the background. */
    val foreground: Boolean = true,
)

/** Appearance choices that are saved between launches. */
data class AppearanceSettings(
    val themeId: String = GlassThemes.default.id.name,
    val blurOn: Boolean = true,
    val reduceMotion: Boolean = false,
    /** Tablets only: the floating bottom bar instead of the side menu (24 Oct). Saved per device. */
    val tabletBottomBar: Boolean = false,
    /** The person picked a theme themselves. Until then they follow the default (Photo Light since 24 Oct). */
    val themeChosen: Boolean = false,
    /** Lens glass on the bottom bar of the Clear themes (experimental, Android 13+; off by default, 24 Oct). */
    val lensGlass: Boolean = false,
    /** Keep the floating bottom bar in place instead of tucking it away while scrolling (24 Oct). */
    val pinBottomBar: Boolean = false,
    /** Check the family drive by itself when the app opens and once an hour while it is on screen (28 Oct). */
    val autoDriveCheck: Boolean = true,
)

/** Saves [AppearanceSettings]. Android uses SharedPreferences; the PC app uses Java preferences. */
interface AppearanceStore {
    fun load(): AppearanceSettings
    fun save(settings: AppearanceSettings)
}

/** Full-screen pages opened over the main sections. Back closes the top one. */
sealed interface Overlay {
    /** The photo viewer, swiping through [ids] (the list it was opened from) starting at [startId]. */
    /**
     * Settings > Storage: the files behind a slice of a chart (23 Oct). [refs] are phone media IDs, or drive paths
     * when [onDrive].
     */
    /** Choosing 1 to 4 photos as an album's cover (24 Oct). [cardId] is the album, or Favourites / Videos. */
    data class CoverPicker(val cardId: String, val title: String) : Overlay

    data class StorageFiles(val title: String, val onDrive: Boolean, val refs: List<String>) : Overlay

    /** [trash]: photos in Recently deleted, shown with Restore and Delete for good (23 Oct). */
    data class Viewer(val ids: List<Long>, val startId: Long, val trash: Boolean = false) : Overlay

    data object Search : Overlay

    data class Album(val kind: AlbumKind) : Overlay

    data object RecentlyDeleted : Overlay

    /** Settings > Storage: just the backups deleted from the drive (25 Oct). */
    data object DriveRecentlyDeleted : Overlay

    /** Connecting the family drive, creating the family or joining it. */
    data object DriveSetup : Overlay

    /** Backup, opened from Settings (24 Oct: moved out of the navigation bar). */
    data object Backup : Overlay

    /** One Settings page (24 Oct: each opens on its own). */
    /** [startOnDrive]: Storage opens on its Drive tab (from "Manage my backup" in Family). */
    data class Settings(val page: SettingsPage, val startOnDrive: Boolean = false) : Overlay

    /** One family member's page: picture, space, phones (24 Oct). */
    data class Member(val userId: String) : Overlay

    /** Normal or Private backup: the two big cards (Phase 4A). */
    data object PrivacyChoice : Overlay

    /** Setting up Private backup: understand, 24 words, check, helper (Phase 4A). */
    data object PrivateSetup : Overlay

    /** Getting the private key back on a new phone with the 24 words. */
    data object RestoreKeys : Overlay
}

sealed interface AlbumKind {
    data class Phone(val albumId: String, val name: String) : AlbumKind

    data object Favourites : AlbumKind

    data object Videos : AlbumKind

    /** The album shared with the whole family (Phase 5A). */
    data object Family : AlbumKind
}

/**
 * All state the shell owns. Created by the platform app, so the PC app can drive it from keyboard
 * shortcuts and the Android app from the activity.
 */
@Stable
class ShellState(private val store: AppearanceStore) {
    var destination by mutableStateOf(Destination.PHOTOS)

    /** Open pages, newest last. */
    val overlays = mutableStateListOf<Overlay>()

    /**
     * Where the person was, as text, so a recreated app (rotation, theme or font change, Android reclaiming memory
     * while another app was open) opens on the same section and pages instead of Photos (25 Oct).
     * Pages that depend on a photo list (the viewer, storage files, cover picker) are not restored.
     */
    fun saveNavigation(): String {
        val pages = overlays.mapNotNull { o ->
            when (o) {
                is Overlay.Settings -> listOf("settings", o.page.name, o.startOnDrive.toString())
                is Overlay.Album -> when (val k = o.kind) {
                    is AlbumKind.Phone -> listOf("album", "phone", k.albumId, k.name)
                    AlbumKind.Favourites -> listOf("album", "favourites")
                    AlbumKind.Videos -> listOf("album", "videos")
                    AlbumKind.Family -> listOf("album", "family")
                }
                is Overlay.Member -> listOf("member", o.userId)
                Overlay.Backup -> listOf("backup")
                Overlay.PrivacyChoice -> listOf("privacy")
                Overlay.DriveSetup -> listOf("setup")
                Overlay.RecentlyDeleted -> listOf("trash")
                Overlay.DriveRecentlyDeleted -> listOf("drivetrash")
                Overlay.Search -> listOf("search")
                else -> null
            }?.joinToString("\u001F")
        }
        return (listOf(destination.name) + pages).joinToString("\u001E")
    }

    fun restoreNavigation(saved: String?) {
        if (saved.isNullOrEmpty()) return
        val parts = saved.split("\u001E")
        Destination.entries.firstOrNull { it.name == parts.first() }?.let { destination = it }
        overlays.clear()
        parts.drop(1).forEach { raw ->
            val f = raw.split("\u001F")
            val overlay: Overlay? = when (f.firstOrNull()) {
                "settings" -> SettingsPage.entries.firstOrNull { it.name == f.getOrNull(1) }?.let { Overlay.Settings(it, f.getOrNull(2) == "true") }
                "album" -> when (f.getOrNull(1)) {
                    "phone" -> if (f.size >= 4) Overlay.Album(AlbumKind.Phone(f[2], f[3])) else null
                    "favourites" -> Overlay.Album(AlbumKind.Favourites)
                    "videos" -> Overlay.Album(AlbumKind.Videos)
                    "family" -> Overlay.Album(AlbumKind.Family)
                    else -> null
                }
                "member" -> f.getOrNull(1)?.let { Overlay.Member(it) }
                "backup" -> Overlay.Backup
                "privacy" -> Overlay.PrivacyChoice
                "setup" -> Overlay.DriveSetup
                "trash" -> Overlay.RecentlyDeleted
                "drivetrash" -> Overlay.DriveRecentlyDeleted
                "search" -> Overlay.Search
                else -> null
            }
            if (overlay != null) overlays.add(overlay)
        }
    }

    /** How many times each section was tapped while already open; each tap scrolls it to the top. */
    val reselects = mutableStateMapOf<Destination, Int>()

    /** A navigation tap: opens another section, or scrolls the open one back to the top. */
    fun tapSection(target: Destination) {
        selected = emptySet()
        albumsSelected = emptySet()
        chrome.hidden = false
        if (target == destination) {
            reselects[target] = (reselects[target] ?: 0) + 1
        } else {
            destination = target
        }
    }

    val chrome = ChromeState()

    /** Photos picked by long-press. Empty means not selecting. */
    var selected by mutableStateOf<Set<Long>>(emptySet())

    val selecting: Boolean get() = selected.isNotEmpty()

    /** The viewer's swipe-up details panel is open. Back closes it before closing the viewer. */
    var detailsOpen by mutableStateOf(false)

    /** Back has something to close: a page, a selection, or a section other than Photos. */
    /** Album cards picked in Albums by press-and-hold (24 Oct). Back clears them first. */
    var albumsSelected by mutableStateOf<Set<String>>(emptySet())

    /** Files picked on a Storage file page (phone media IDs or drive paths, 23 Oct). Back clears them first. */
    var filesSelected by mutableStateOf<Set<String>>(emptySet())

    /**
     * How far a page has been pulled away by the back gesture (24 Oct): 0 = in place, 1 = gone. The page follows the
     * finger exactly and stays wherever the finger stops; letting go finishes or cancels it from there.
     */
    val pageBack = Animatable(0f)

    /** Which way the finger pulls it: 1 = to the right (swipe from the left edge), -1 = to the left. */
    var pageBackDir: Int = -1
        private set

    /** Set for the one moment when the page that was just pulled away is removed, so it is not slid out again. */
    var pageBackSkip: Boolean = false

    /** How far the first open page covers the section underneath (0 to 1). Set by the page host, read while drawing. */
    var sectionCover: () -> Float = { 0f }

    /** A back gesture moves the top page itself only when nothing smaller (a selection, the details) would close first. */
    val canDragBack: Boolean
        get() = !detailsOpen && !selecting && filesSelected.isEmpty() && albumsSelected.isEmpty() && overlays.lastOrNull().slidesSideways()

    suspend fun dragBack(progress: Float, toRight: Boolean) {
        pageBackDir = if (toRight) 1 else -1
        pageBack.snapTo(progress.coerceIn(0f, 1f))
    }

    /** Lets go past the point of no return: slides the rest of the way, then removes the page. */
    suspend fun finishBack() {
        if (pageBack.value <= 0f) {
            back() // no finger-following (old Android, three-button navigation): the normal animation
            return
        }
        pageBack.animateTo(1f, tween((BACK_SETTLE_MS * (1f - pageBack.value)).toInt().coerceAtLeast(60), easing = PageEasing))
        pageBackSkip = true
        back() // the page host removes the page and puts the pull back to 0 in the same frame
        repeat(4) { withFrameNanos { } }
        if (pageBack.value > 0f) pageBack.snapTo(0f)
        pageBackSkip = false
    }

    /** The gesture was cancelled: the page slides back where it was. */
    suspend fun cancelBack() {
        pageBack.animateTo(0f, tween((BACK_SETTLE_MS * pageBack.value).toInt().coerceAtLeast(60), easing = PageEasing))
    }

    /** Photos is showing Months or Years (or came there from one): back steps up a level before the app closes. */
    var canStepBack by mutableStateOf(false)
    var stepBack: (() -> Unit)? = null

    /** Settings > Storage remembers Phone or Drive while pages are opened on top (25 Oct). */
    var storageOnDrive: Boolean? by mutableStateOf(null)

    val canGoBack: Boolean get() = canStepBack || overlays.isNotEmpty() || selecting || detailsOpen || filesSelected.isNotEmpty() || albumsSelected.isNotEmpty()

    /** Where each page was scrolled to when a photo was opened from it, so coming back lands in the same place (25 Oct). */
    val savedScroll = HashMap<String, Pair<Int, Int>>()

    fun scrollKey(overlay: Overlay? = overlays.lastOrNull()) = "${destination.name}|$overlay"

    fun open(overlay: Overlay) {
        // A page opened fresh starts at the top; only a page returned to from a photo keeps its place.
        if (overlay !is Overlay.Viewer) savedScroll.remove(scrollKey(overlay))
        selected = emptySet()
        filesSelected = emptySet()
        albumsSelected = emptySet()
        detailsOpen = false
        overlays.add(overlay)
    }

    /** Closes the selection first, then the top page. Returns false when there was nothing to close. */
    fun back(): Boolean = when {
        detailsOpen -> {
            detailsOpen = false
            true
        }
        // Choosing a cover uses the photo picks; back leaves the page in one go.
        overlays.lastOrNull() is Overlay.CoverPicker -> {
            selected = emptySet()
            overlays.removeAt(overlays.lastIndex)
            true
        }
        selecting -> {
            selected = emptySet()
            true
        }
        filesSelected.isNotEmpty() -> {
            filesSelected = emptySet()
            true
        }
        albumsSelected.isNotEmpty() -> {
            albumsSelected = emptySet()
            true
        }
        overlays.isNotEmpty() -> {
            overlays.removeAt(overlays.lastIndex)
            true
        }
        canStepBack && stepBack != null -> {
            stepBack?.invoke()
            true
        }
        else -> false
    }

    fun toggleSelected(id: Long) {
        selected = if (id in selected) selected - id else selected + id
    }

    var appearance by mutableStateOf(store.load())
        private set

    /** Album card size chosen by pinching on Albums (index into its sizes; 2 = the usual). */
    var albumSize by mutableStateOf(2)

    /** Photo grid columns chosen by pinching; null means "fit the screen". */
    var gridColumns by mutableStateOf<Int?>(null)

    val theme: GlassTheme get() = if (appearance.themeChosen) GlassThemes.byId(appearance.themeId) else GlassThemes.default

    /** The Welcome tour and What's new (Phase 5, 25 Oct). The platform swaps in one that remembers. */
    var intro: IntroState = IntroState()

    /** Remote access (the VPN) for when the family drive is out of reach at home (Phase 5B-1). */
    var remote: RemoteAccessState = RemoteAccessState()

    /** Invites: files, QR drawing and scanning (Phase 5B-2). */
    var invites: InvitePlatform = NoInvitePlatform

    /** Private backup keys (Phase 4A). */
    var encryption: EncryptionState = EncryptionState()

    /** An invite another app opened in Pixallery, waiting for the join screen to take it. */
    var pendingInvite by mutableStateOf<String?>(null)

    fun updateAppearance(change: (AppearanceSettings) -> AppearanceSettings) {
        appearance = change(appearance)
        store.save(appearance)
    }

    /** Blur turns off on battery saver; reduce motion follows the system as well as the user's switch. */
    fun glassSettings(signals: PlatformSignals): GlassSettings = GlassSettings(
        blurEnabled = appearance.blurOn && !signals.powerSaveMode,
        reduceMotion = appearance.reduceMotion || signals.systemReduceMotion,
        lensEnabled = (appearance.lensGlass || theme.lensAlways) && appearance.blurOn && !signals.powerSaveMode,
    )

    /** Fewer, larger photos (positive [step]) or more, smaller photos (negative), within the screen's range. */
    fun zoomGrid(step: Int, currentColumns: Int, maxColumns: Int) {
        gridColumns = (currentColumns - step).coerceIn(AdaptiveLayout.MIN_COLUMNS, maxColumns)
    }
}

/** In-memory store for previews and tests. */
class MemoryAppearanceStore(private var value: AppearanceSettings = AppearanceSettings()) : AppearanceStore {
    override fun load() = value
    override fun save(settings: AppearanceSettings) {
        value = settings
    }
}

// The same time as a page takes to slide in (25 Oct: leaving felt faster than arriving).
private const val BACK_SETTLE_MS = 640
