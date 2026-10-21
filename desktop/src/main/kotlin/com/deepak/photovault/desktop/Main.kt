package com.deepak.photovault.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.deepak.photovault.ui.shell.AppearanceSettings
import com.deepak.photovault.ui.shell.AppearanceStore
import com.deepak.photovault.ui.shell.Destination
import com.deepak.photovault.ui.shell.PhotoVaultApp
import com.deepak.photovault.ui.shell.ShellState
import java.util.prefs.Preferences

/**
 * PC host for the shared PhotoVault shell. Phase 1: the same screens and sample data as the Android app.
 *
 * Keyboard: Ctrl+1..5 switch sections, Ctrl+Plus / Ctrl+Minus change photo size.
 */
fun main() = application {
    val state = remember { ShellState(JavaPrefsAppearanceStore()) }
    val windowState = rememberWindowState(size = DpSize(1280.dp, 820.dp))
    Window(
        onCloseRequest = ::exitApplication,
        title = "PhotoVault",
        state = windowState,
        onPreviewKeyEvent = shortcuts@{ event ->
            if (event.type != KeyEventType.KeyDown || !(event.isCtrlPressed || event.isMetaPressed)) return@shortcuts false
            val destination = when (event.key) {
                Key.One -> Destination.PHOTOS
                Key.Two -> Destination.ALBUMS
                Key.Three -> Destination.BACKUP
                Key.Four -> Destination.FAMILY
                Key.Five -> Destination.SETTINGS
                else -> null
            }
            when {
                destination != null -> {
                    state.destination = destination
                    true
                }
                event.key == Key.Equals || event.key == Key.Plus || event.key == Key.NumPadAdd -> {
                    state.zoomGrid(+1, state.gridColumns ?: DEFAULT_PC_COLUMNS)
                    true
                }
                event.key == Key.Minus || event.key == Key.NumPadSubtract -> {
                    state.zoomGrid(-1, state.gridColumns ?: DEFAULT_PC_COLUMNS)
                    true
                }
                else -> false
            }
        },
    ) {
        window.minimumSize = java.awt.Dimension(420, 600)
        PhotoVaultApp(state)
    }
}

/** Columns the grid starts from when zooming before the user has chosen a size. */
private const val DEFAULT_PC_COLUMNS = 8

/** Appearance choices in the per-user Java preferences (the Windows registry on a PC). Not secret. */
private class JavaPrefsAppearanceStore : AppearanceStore {
    private val prefs = Preferences.userRoot().node("com/deepak/photovault/appearance")

    override fun load() = AppearanceSettings(
        themeId = prefs.get("theme", AppearanceSettings().themeId),
        blurOn = prefs.getBoolean("blur", true),
        reduceMotion = prefs.getBoolean("reduce_motion", false),
    )

    override fun save(settings: AppearanceSettings) {
        prefs.put("theme", settings.themeId)
        prefs.putBoolean("blur", settings.blurOn)
        prefs.putBoolean("reduce_motion", settings.reduceMotion)
    }
}
