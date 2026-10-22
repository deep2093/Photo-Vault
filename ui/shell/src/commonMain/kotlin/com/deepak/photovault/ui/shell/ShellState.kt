package com.deepak.photovault.ui.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Stable
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

enum class Destination(val label: String, val icon: ImageVector) {
    PHOTOS("Photos", Icons.Rounded.PhotoLibrary),
    ALBUMS("Albums", Icons.Rounded.Collections),
    BACKUP("Backup", Icons.Rounded.Backup),
    FAMILY("Family", Icons.Rounded.Groups),
    SETTINGS("Settings", Icons.Rounded.Settings),
}

/** What the platform reports. Android: battery saver and "remove animations"; PC: both false for now. */
data class PlatformSignals(
    val powerSaveMode: Boolean = false,
    val systemReduceMotion: Boolean = false,
)

/** Appearance choices that are saved between launches. */
data class AppearanceSettings(
    val themeId: String = GlassThemes.default.id.name,
    val blurOn: Boolean = true,
    val reduceMotion: Boolean = false,
)

/** Saves [AppearanceSettings]. Android uses SharedPreferences; the PC app uses Java preferences. */
interface AppearanceStore {
    fun load(): AppearanceSettings
    fun save(settings: AppearanceSettings)
}

/** Full-screen pages opened over the main sections. Back closes the top one. */
sealed interface Overlay {
    /** The photo viewer, swiping through [ids] (the list it was opened from) starting at [startId]. */
    data class Viewer(val ids: List<Long>, val startId: Long) : Overlay

    data object Search : Overlay

    data class Album(val kind: AlbumKind) : Overlay

    data object RecentlyDeleted : Overlay
}

sealed interface AlbumKind {
    data class Phone(val albumId: String, val name: String) : AlbumKind

    data object Favourites : AlbumKind

    data object Videos : AlbumKind
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

    /** How many times each section was tapped while already open; each tap scrolls it to the top. */
    val reselects = mutableStateMapOf<Destination, Int>()

    /** A navigation tap: opens another section, or scrolls the open one back to the top. */
    fun tapSection(target: Destination) {
        selected = emptySet()
        if (target == destination) {
            reselects[target] = (reselects[target] ?: 0) + 1
        } else {
            destination = target
        }
    }

    /** Photos picked by long-press. Empty means not selecting. */
    var selected by mutableStateOf<Set<Long>>(emptySet())

    val selecting: Boolean get() = selected.isNotEmpty()

    /** The viewer's swipe-up details panel is open. Back closes it before closing the viewer. */
    var detailsOpen by mutableStateOf(false)

    /** Back has something to close: a page, a selection, or a section other than Photos. */
    val canGoBack: Boolean get() = overlays.isNotEmpty() || selecting || detailsOpen

    fun open(overlay: Overlay) {
        selected = emptySet()
        detailsOpen = false
        overlays.add(overlay)
    }

    /** Closes the selection first, then the top page. Returns false when there was nothing to close. */
    fun back(): Boolean = when {
        detailsOpen -> {
            detailsOpen = false
            true
        }
        selecting -> {
            selected = emptySet()
            true
        }
        overlays.isNotEmpty() -> {
            overlays.removeAt(overlays.lastIndex)
            true
        }
        else -> false
    }

    fun toggleSelected(id: Long) {
        selected = if (id in selected) selected - id else selected + id
    }

    var appearance by mutableStateOf(store.load())
        private set

    /** Photo grid columns chosen by pinching; null means "fit the screen". */
    var gridColumns by mutableStateOf<Int?>(null)

    val theme: GlassTheme get() = GlassThemes.byId(appearance.themeId)

    fun updateAppearance(change: (AppearanceSettings) -> AppearanceSettings) {
        appearance = change(appearance)
        store.save(appearance)
    }

    /** Blur turns off on battery saver; reduce motion follows the system as well as the user's switch. */
    fun glassSettings(signals: PlatformSignals): GlassSettings = GlassSettings(
        blurEnabled = appearance.blurOn && !signals.powerSaveMode,
        reduceMotion = appearance.reduceMotion || signals.systemReduceMotion,
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
