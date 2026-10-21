package com.deepak.photovault.ui.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
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

/**
 * All state the shell owns. Created by the platform app, so the PC app can drive it from keyboard
 * shortcuts and the Android app from the activity.
 */
@Stable
class ShellState(private val store: AppearanceStore) {
    var destination by mutableStateOf(Destination.PHOTOS)

    var appearance by mutableStateOf(store.load())
        private set

    /** Photo grid columns chosen by pinch, zoom buttons or Ctrl +/-; null means "fit the window". */
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

    /** Fewer, larger photos (positive) or more, smaller photos (negative). */
    fun zoomGrid(step: Int, currentColumns: Int) {
        gridColumns = (currentColumns - step).coerceIn(MIN_COLUMNS, MAX_COLUMNS)
    }

    companion object {
        const val MIN_COLUMNS = 2
        const val MAX_COLUMNS = 10
    }
}

/** In-memory store for previews and tests. */
class MemoryAppearanceStore(private var value: AppearanceSettings = AppearanceSettings()) : AppearanceStore {
    override fun load() = value
    override fun save(settings: AppearanceSettings) {
        value = settings
    }
}
