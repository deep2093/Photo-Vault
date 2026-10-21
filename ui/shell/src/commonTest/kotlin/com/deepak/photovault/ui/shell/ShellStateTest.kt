package com.deepak.photovault.ui.shell

import com.deepak.photovault.designsystem.GlassThemeId
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
        state.updateAppearance { it.copy(themeId = GlassThemeId.MIDNIGHT_PRISM.name, blurOn = false) }
        assertEquals(GlassThemeId.MIDNIGHT_PRISM, ShellState(store).theme.id)
        assertFalse(store.load().blurOn)
    }

    @Test
    fun gridZoomStaysWithinLimits() {
        val state = ShellState(MemoryAppearanceStore())
        state.zoomGrid(+1, currentColumns = 3)
        assertEquals(2, state.gridColumns)
        state.zoomGrid(+1, currentColumns = 2)
        assertEquals(ShellState.MIN_COLUMNS, state.gridColumns)
        state.zoomGrid(-1, currentColumns = ShellState.MAX_COLUMNS)
        assertEquals(ShellState.MAX_COLUMNS, state.gridColumns)
    }

    @Test
    fun mobileDataWarningSaysWhyItCostsMore() {
        assertTrue(MOBILE_DATA_WARNING.contains("twice"))
        assertTrue(FOLDER_PRIVACY_NOTE.contains("drive password"))
    }
}
