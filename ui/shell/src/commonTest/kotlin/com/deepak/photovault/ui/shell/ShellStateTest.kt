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
        state.updateAppearance { it.copy(themeId = GlassThemeId.ADAPTIVE_VIVID.name, themeChosen = true, blurOn = false, mode = ThemeMode.DARK.name) }
        assertEquals(GlassThemeId.ADAPTIVE_VIVID, ShellState(store).theme.id)
        assertFalse(store.load().blurOn)
    }

    @Test
    fun photoLightForEveryoneWhoNeverPickedATheme() {
        // An older version saved Aurora Frost (the old default) without the person choosing it; it no longer exists.
        val state = ShellState(MemoryAppearanceStore(AppearanceSettings(themeId = "AURORA_FROST")))
        assertEquals(GlassThemes.default, state.theme)
        // Someone who had picked a removed theme moves to the default too.
        val removed = ShellState(MemoryAppearanceStore(AppearanceSettings(themeId = "TRANSLUCENT", themeChosen = true)))
        assertEquals(GlassThemes.default, removed.theme)
        // Someone who picked a theme keeps it.
        val picked = ShellState(MemoryAppearanceStore(AppearanceSettings(themeId = GlassThemeId.AURORA_GLASS_LIGHT.name, themeChosen = true, mode = ThemeMode.LIGHT.name)))
        assertEquals(GlassThemeId.AURORA_GLASS_LIGHT, picked.theme.id)
        // Warm Light and Clear Light were removed (18 Nov): the default theme instead.
        val warm = ShellState(MemoryAppearanceStore(AppearanceSettings(themeId = "PEARL_MIST", themeChosen = true, mode = ThemeMode.LIGHT.name)))
        assertEquals(GlassThemes.default, warm.theme)
    }

    @Test
    fun eachThemeSwitchesBetweenDayAndNight() {
        // 18 Nov: one theme, its day or night version by the chosen mode, or by the phone's dark mode.
        val state = ShellState(MemoryAppearanceStore(AppearanceSettings(themeId = GlassThemeId.LIQUID_DAY.name, themeChosen = true)))
        assertEquals(ThemeMode.SYSTEM.name, state.appearance.mode)
        state.systemDark = false
        assertEquals(GlassThemeId.LIQUID_DAY, state.theme.id)
        state.systemDark = true
        assertEquals(GlassThemeId.LIQUID_DARK, state.theme.id)
        state.updateAppearance { it.copy(mode = ThemeMode.LIGHT.name) }
        assertEquals(GlassThemeId.LIQUID_DAY, state.theme.id)
        state.updateAppearance { it.copy(mode = ThemeMode.DARK.name) }
        state.systemDark = false
        assertEquals(GlassThemeId.LIQUID_DARK, state.theme.id)
        // Saved as the night version, it is still the same theme.
        state.updateAppearance { it.copy(themeId = GlassThemeId.ADAPTIVE_MIDNIGHT.name, mode = ThemeMode.LIGHT.name) }
        assertEquals(GlassThemeId.PHOTO_DAY, state.theme.id)
        // The viewer always uses the night version.
        assertEquals(GlassThemeId.ADAPTIVE_MIDNIGHT, state.viewerTheme.id)
        assertTrue(state.viewerTheme.isDark)
    }

    @Test
    fun messagesReplaceEachOtherAndOnlyTheirOwnDismissal() {
        val state = ShellState(MemoryAppearanceStore())
        state.say("Saved")
        val first = state.message!!
        state.say("Saved")
        val second = state.message!!
        assertTrue(first != second)
        state.dismissMessage(first)
        assertEquals(second, state.message)
        state.dismissMessage(second)
        assertEquals(null, state.message)
        state.say("  ")
        assertEquals(null, state.message)
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
    fun theBottomBarIsNotPinnedUntilAskedFor() {
        assertEquals(false, AppearanceSettings().pinBottomBar)
    }

    @Test
    fun lensGlassIsGoneAndTheOneGlassSwitchFollowsBlurAndBatterySaver() {
        val signals = PlatformSignals()
        // The thick-glass look follows the Blur slider: on only at 0 (29 Oct).
        val old = ShellState(MemoryAppearanceStore(AppearanceSettings(blurStep = 3)))
        assertFalse(old.glassSettings(signals).lensEnabled)
        assertTrue(ShellState(MemoryAppearanceStore(AppearanceSettings(blurStep = 0))).glassSettings(signals).lensEnabled)
        assertTrue(old.glassSettings(signals).blurEnabled)
        assertFalse(old.glassSettings(PlatformSignals(powerSaveMode = true)).blurEnabled)
        val off = ShellState(MemoryAppearanceStore(AppearanceSettings(blurOn = false)))
        assertFalse(off.glassSettings(signals).blurEnabled)
    }

    @Test
    fun blurSliderGoesFromNoBlurToStrongAndRemovedThemesFallBack() {
        val state = ShellState(MemoryAppearanceStore())
        // The usual blur is step 3.
        assertEquals(1f, state.glassSettings(PlatformSignals()).blurScale)
        state.updateAppearance { it.copy(blurStep = 0) }
        assertEquals(0f, state.glassSettings(PlatformSignals()).blurScale)
        state.updateAppearance { it.copy(blurStep = 4) }
        assertTrue(state.glassSettings(PlatformSignals()).blurScale > 1f)
        // Liquid Night was removed on 29 Oct: anyone who had it gets the default.
        state.updateAppearance { it.copy(themeId = "LIQUID_NIGHT", themeChosen = true) }
        assertEquals(GlassThemes.default, state.theme)
    }

    @Test
    fun eachPageRemembersItsOwnGridSize() {
        val store = MemoryAppearanceStore()
        val state = ShellState(store)
        state.zoomGrid(+1, currentColumns = 4, maxColumns = 8, page = "album")
        state.zoomGrid(-1, currentColumns = 4, maxColumns = 8, page = GRID_PHOTOS)
        assertEquals(3, state.gridColumnsFor("album"))
        assertEquals(5, state.gridColumnsFor(GRID_PHOTOS))
        assertEquals(null, state.gridColumnsFor("search"))
        state.albumSize = 4
        // Saved: a new start of the app gets the same sizes.
        val again = ShellState(store)
        assertEquals(3, again.gridColumnsFor("album"))
        assertEquals(5, again.gridColumns)
        assertEquals(4, again.albumSize)
    }

    @Test
    fun foldedAndOpenScreensKeepTheirOwnGridSize() {
        val store = MemoryAppearanceStore()
        val state = ShellState(store)
        // A size saved before (one per page) counts on both kinds of screen until each is pinched.
        state.zoomGrid(+1, currentColumns = 4, maxColumns = 8)
        assertEquals(3, state.gridColumnsFor(GRID_PHOTOS, large = false))
        assertEquals(3, state.gridColumnsFor(GRID_PHOTOS, large = true))
        state.setGridColumns(GRID_PHOTOS, large = true, columns = 6)
        state.setGridColumns(GRID_PHOTOS, large = false, columns = 4)
        assertEquals(6, state.gridColumnsFor(GRID_PHOTOS, large = true))
        assertEquals(4, state.gridColumnsFor(GRID_PHOTOS, large = false))
        val again = ShellState(store)
        assertEquals(6, again.gridColumnsFor(GRID_PHOTOS, large = true))
        assertEquals(4, again.gridColumnsFor(GRID_PHOTOS, large = false))
    }
}
