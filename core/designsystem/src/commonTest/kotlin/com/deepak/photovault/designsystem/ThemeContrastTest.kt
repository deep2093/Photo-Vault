package com.deepak.photovault.designsystem

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Glass must never cost readability: every theme is checked against its worst possible background. */
class ThemeContrastTest {
    @Test
    fun contrastMathMatchesKnownValues() {
        assertEquals(21.0, Contrast.ratio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, Contrast.ratio(Color.Red, Color.Red), 0.0001)
    }

    @Test
    fun primaryTextIsReadableOnEveryGlassBackground() {
        GlassThemes.all.forEach { theme ->
            val worst = Contrast.worstOnGlass(theme, theme.onGlass)
            assertTrue(worst >= Contrast.TEXT, "${theme.displayName}: primary text contrast $worst")
        }
    }

    @Test
    fun secondaryTextIsReadableOnEveryGlassBackground() {
        GlassThemes.all.forEach { theme ->
            val worst = Contrast.worstOnGlass(theme, theme.onGlassSecondary)
            assertTrue(worst >= Contrast.TEXT, "${theme.displayName}: secondary text contrast $worst")
        }
    }

    @Test
    fun accentIsVisibleOnGlassAndItsTextIsReadable() {
        GlassThemes.all.forEach { theme ->
            val onGlass = Contrast.worstOnGlass(theme, theme.accent)
            assertTrue(onGlass >= Contrast.NON_TEXT, "${theme.displayName}: accent on glass $onGlass")
            val onAccent = Contrast.ratio(theme.onAccent, theme.accent)
            assertTrue(onAccent >= Contrast.TEXT, "${theme.displayName}: text on accent $onAccent")
        }
    }

    @Test
    fun badgeIconsAreReadable() {
        GlassThemes.all.forEach { theme ->
            listOf(theme.badgeBackedUp, theme.badgePhoneOnly, theme.badgeOnDrive).forEach { fill ->
                val ratio = Contrast.ratio(theme.onBadge, fill)
                assertTrue(ratio >= Contrast.TEXT, "${theme.displayName}: badge contrast $ratio")
            }
        }
    }

    @Test
    fun blurTintIsStrongEnoughToHideBusyPhotos() {
        // Below about 80% opacity, text over a busy photo stops being reliably readable.
        GlassThemes.all.forEach { theme ->
            assertTrue(theme.glassTint.alpha >= 0.79f, "${theme.displayName}: glass tint alpha ${theme.glassTint.alpha}")
        }
    }
}

class ThemeCatalogTest {
    @Test
    fun hasTheFourAgreedThemesWithUniqueIds() {
        assertEquals(
            listOf("Aurora Frost", "Midnight Prism", "Pearl Mist", "Ocean Glass"),
            GlassThemes.all.map { it.displayName },
        )
        assertEquals(GlassThemes.all.size, GlassThemes.all.map { it.id }.toSet().size)
    }

    @Test
    fun auroraFrostIsTheDefaultAndOnlyMidnightPrismIsDark() {
        assertEquals(GlassThemeId.AURORA_FROST, GlassThemes.default.id)
        assertEquals(listOf(GlassThemeId.MIDNIGHT_PRISM), GlassThemes.all.filter { it.isDark }.map { it.id })
    }

    @Test
    fun unknownSavedIdFallsBackToDefault() {
        assertEquals(GlassThemes.default, GlassThemes.byId("SOMETHING_FROM_A_NEWER_VERSION"))
        assertEquals(GlassThemes.default, GlassThemes.byId(null))
        assertEquals(GlassThemes.PearlMist, GlassThemes.byId("PEARL_MIST"))
    }
}

class AdaptiveLayoutTest {
    @Test
    fun phonesGetBottomBarTabletsAndPcGetSideRail() {
        assertEquals(NavigationLayout.BOTTOM_BAR, AdaptiveLayout.navigationFor(360))
        assertEquals(NavigationLayout.BOTTOM_BAR, AdaptiveLayout.navigationFor(599))
        assertEquals(NavigationLayout.SIDE_RAIL, AdaptiveLayout.navigationFor(600))
        assertEquals(NavigationLayout.SIDE_RAIL, AdaptiveLayout.navigationFor(1280))
    }

    @Test
    fun gridColumnsStayInRange() {
        assertEquals(3, AdaptiveLayout.defaultColumns(320))
        assertEquals(10, AdaptiveLayout.defaultColumns(4000))
    }
}
