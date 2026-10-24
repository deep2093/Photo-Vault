package com.deepak.photovault.ui.shell

import com.deepak.photovault.designsystem.AdaptiveLayout
import com.deepak.photovault.designsystem.GlassThemeId
import com.deepak.photovault.designsystem.GlassThemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellStateTest {
    @Test
    fun batterySaverTurnsBlurOffEvenWhenTheUserWantsIt() {
        val state = ShellState(MemoryAppearanceStore(AppearanceSettings(blurOn = true)))
        assertTrue(state.glassSettings(PlatformSignals(powerSaveMode = false)).blurEnabled)
        assertFalse(state.glassSettings(PlatformSignals(powerSaveMode = true)).blurEnabled)
    }

    @Test
    fun reduceMotionFollowsTheSystemOrTheUser() {
        val state = ShellState(MemoryAppearanceStore(AppearanceSettings(reduceMotion = false)))
        assertFalse(state.glassSettings(PlatformSignals()).reduceMotion)
        assertTrue(state.glassSettings(PlatformSignals(systemReduceMotion = true)).reduceMotion)
        state.updateAppearance { it.copy(reduceMotion = true) }
        assertTrue(state.glassSettings(PlatformSignals()).reduceMotion)
    }

    @Test
    fun appearanceChangesAreSaved() {
        val store = MemoryAppearanceStore()
        val state = ShellState(store)
        state.updateAppearance { it.copy(themeId = GlassThemeId.MIDNIGHT_PRISM.name, themeChosen = true, blurOn = false) }
        assertEquals(GlassThemeId.MIDNIGHT_PRISM, ShellState(store).theme.id)
        assertFalse(store.load().blurOn)
    }

    @Test
    fun photoLightForEveryoneWhoNeverPickedATheme() {
        // An older version saved Aurora Frost (the old default) without the person choosing it; it no longer exists.
        val state = ShellState(MemoryAppearanceStore(AppearanceSettings(themeId = "AURORA_FROST")))
        assertEquals(GlassThemes.AdaptiveLight, state.theme)
        // Someone who had picked a removed theme moves to the default too.
        val removed = ShellState(MemoryAppearanceStore(AppearanceSettings(themeId = "TRANSLUCENT", themeChosen = true)))
        assertEquals(GlassThemes.AdaptiveLight, removed.theme)
        // Someone who picked a theme keeps it.
        val picked = ShellState(MemoryAppearanceStore(AppearanceSettings(themeId = GlassThemeId.PEARL_MIST.name, themeChosen = true)))
        assertEquals(GlassThemeId.PEARL_MIST, picked.theme.id)
    }

    @Test
    fun tappingASectionOrSelectingBringsTheBarsBack() {
        val state = ShellState(MemoryAppearanceStore())
        state.chrome.hidden = true
        state.tapSection(Destination.PHOTOS)
        assertFalse(state.chrome.hidden)
        assertEquals(setOf(Destination.PHOTOS, Destination.ALBUMS), Destination.hidesChrome)
    }

    @Test
    fun gridZoomStaysWithinLimits() {
        val state = ShellState(MemoryAppearanceStore())
        state.zoomGrid(+1, currentColumns = 3, maxColumns = 7)
        assertEquals(2, state.gridColumns)
        state.zoomGrid(+1, currentColumns = 2, maxColumns = 7)
        assertEquals(AdaptiveLayout.MIN_COLUMNS, state.gridColumns)
        state.zoomGrid(-1, currentColumns = 22, maxColumns = 22)
        assertEquals(22, state.gridColumns)
    }

    @Test
    fun mobileDataWarningSaysWhyItCostsMore() {
        assertTrue(MOBILE_DATA_WARNING.contains("twice"))
        assertTrue(FOLDER_PRIVACY_NOTE.contains("drive password"))
    }

    @Test
    fun lensGlassIsOffByDefaultAndFollowsBlurAndBatterySaver() {
        val signals = PlatformSignals()
        assertFalse(ShellState(MemoryAppearanceStore()).glassSettings(signals).lensEnabled)
        val on = ShellState(MemoryAppearanceStore(AppearanceSettings(lensGlass = true)))
        assertTrue(on.glassSettings(signals).lensEnabled)
        assertFalse(on.glassSettings(PlatformSignals(powerSaveMode = true)).lensEnabled)
        val blurOff = ShellState(MemoryAppearanceStore(AppearanceSettings(lensGlass = true, blurOn = false)))
        assertFalse(blurOff.glassSettings(signals).lensEnabled)
    }
}
