package com.deepak.photovault.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
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
            val worst = Contrast.worstAnywhere(theme, theme.onGlass)
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
    fun blurTintIsNeverTooThin() {
        // The contrast tests above check text over pure black and pure white photos, which is the real guard.
        // This floor stops a theme from relying on blur alone. No theme draws a glow behind text any more (23 Oct).
        GlassThemes.all.forEach { theme ->
            assertTrue(theme.glassTint.alpha >= 0.6f, "${theme.displayName}: glass tint alpha ${theme.glassTint.alpha}")
        }
    }

    @Test
    fun adaptiveThemesAreDistinct() {
        // Feedback 22 and 23 Oct: they looked alike. Each needs its own accent and its own wallpaper shape.
        val adaptive = GlassThemes.adaptiveThemes
        assertEquals(adaptive.size, adaptive.map { it.accent }.toSet().size)
        assertEquals(adaptive.size, adaptive.map { it.adaptive!!.layout }.toSet().size)
        assertTrue(GlassThemes.AdaptiveGlow.adaptive!!.values.first() > GlassThemes.AdaptiveMidnight.adaptive!!.values.first() + 0.2f)
        assertTrue(GlassThemes.AdaptiveVivid.adaptive!!.minSaturation > GlassThemes.AdaptiveGlow.adaptive!!.minSaturation)
    }
}

class ThemeCatalogTest {
    @Test
    fun themesAreSortedIntoLightDarkAndPhotoSectionsWithUniqueIds() {
        assertEquals(listOf("Warm Light", "Teal Light", "Luminous Light", "Clear Light"), GlassThemes.lightThemes.map { it.displayName })
        assertEquals(listOf("Violet Night", "Luminous Night", "Clear Night"), GlassThemes.darkThemes.map { it.displayName })
        assertEquals(listOf("Photo Glow", "Photo Night", "Photo Vivid", "Photo Light"), GlassThemes.photoThemes.map { it.displayName })
        assertEquals(GlassThemes.all.size, GlassThemes.all.map { it.id }.toSet().size)
        assertTrue(GlassThemes.lightThemes.none { it.isDark })
        assertTrue(GlassThemes.darkThemes.all { it.isDark })
        assertTrue(GlassThemes.photoThemes.all { it.adaptive != null })
        assertTrue(GlassThemes.fixed.all { it.adaptive == null })
    }

    @Test
    fun photoLightIsTheDefaultAndDarkThemesAreTheDarkAndPhotoNightOnes() {
        assertEquals(GlassThemes.AdaptiveLight, GlassThemes.default)
        assertEquals(
            listOf(
                GlassThemeId.MIDNIGHT_PRISM, GlassThemeId.AURORA_GLASS_DARK, GlassThemeId.CRYSTAL_GLASS_DARK,
                GlassThemeId.ADAPTIVE_GLOW, GlassThemeId.ADAPTIVE_MIDNIGHT, GlassThemeId.ADAPTIVE_VIVID,
            ),
            GlassThemes.all.filter { it.isDark }.map { it.id },
        )
    }

    @Test
    fun luminousAndClearAreRealPairsThatLookDifferentAndNeverTheDefault() {
        val e = listOf(GlassThemes.AuroraGlassLight, GlassThemes.AuroraGlassDark, GlassThemes.CrystalGlassLight, GlassThemes.CrystalGlassDark)
        assertEquals(listOf(false, true, false, true), e.map { it.isDark })
        assertTrue(e.none { it == GlassThemes.default })
        assertEquals(26f, GlassThemes.AuroraGlassLight.blurRadius.value)
        assertEquals(18f, GlassThemes.CrystalGlassLight.blurRadius.value)
        assertTrue(GlassThemes.AuroraGlassDark.grain > GlassThemes.CrystalGlassDark.grain)
        assertEquals(4, e.map { it.accent }.toSet().size)
        assertEquals(GlassThemes.CrystalGlassDark, GlassThemes.byId("CRYSTAL_GLASS_DARK"))
    }

    @Test
    fun removedThemesFallBackToTheDefault() {
        listOf("AURORA_FROST", "TRANSLUCENT", "FROSTED", "CRYSTAL").forEach { assertEquals(GlassThemes.default, GlassThemes.byId(it)) }
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
    fun defaultGridFitsTheWidth() {
        assertEquals(3, AdaptiveLayout.defaultColumns(320))
        assertEquals(10, AdaptiveLayout.defaultColumns(1100))
    }

    @Test
    fun tabletsZoomOutToSmallPhotosToo() {
        // Feedback 22 Oct: on a tablet the smallest photos were still too big. Tiles now shrink to about 48 dp.
        assertEquals(7, AdaptiveLayout.maxColumns(360))
        assertEquals(22, AdaptiveLayout.maxColumns(1100))
        assertTrue(1100 / AdaptiveLayout.maxColumns(1100) <= 50)
    }

    @Test
    fun savedColumnsAreClampedAfterRotation() {
        assertEquals(7, AdaptiveLayout.clampColumns(22, 360))
        assertEquals(AdaptiveLayout.MIN_COLUMNS, AdaptiveLayout.clampColumns(1, 1100))
    }
}

class AdaptivePaletteTest {
    @Test
    fun hsvRoundTrips() {
        listOf(Color(0xFFE07A5F), Color(0xFF3D9970), Color(0xFF1982C4), Color(0xFF808080)).forEach { c ->
            val (h, s, v) = Hsv.of(c).let { Triple(it[0], it[1], it[2]) }
            val back = Hsv.toColor(h, s, v)
            assertEquals(c.red, back.red, 0.01f)
            assertEquals(c.green, back.green, 0.01f)
            assertEquals(c.blue, back.blue, 0.01f)
        }
    }

    @Test
    fun removedThemesFallBackToTheDefault() {
        assertEquals(GlassThemes.default, GlassThemes.byId("FROSTED"))
        assertEquals(GlassThemes.default, GlassThemes.byId("CRYSTAL"))
    }

    @Test
    fun adaptiveLightUsesAWhiteVeilAndDarkText() {
        val style = GlassThemes.AdaptiveLight.adaptive!!
        assertTrue(style.light)
        assertEquals(Color.White, style.veil)
        assertTrue(Contrast.relativeLuminance(GlassThemes.AdaptiveLight.onGlass) < 0.05)
    }

    @Test
    fun noPhotosOnScreenUsesTheFallback() {
        GlassThemes.adaptiveThemes.forEach { theme ->
            val style = theme.adaptive!!
            assertEquals(style.fallback, adaptivePalette(emptyList(), style))
        }
    }

    @Test
    fun paletteKeepsTheStylesBrightnessWhateverThePhotos() {
        val photos = listOf(Color.White, Color.Yellow, Color(0xFFFF00FF), Color(0xFF00FFFF))
        GlassThemes.adaptiveThemes.forEach { theme ->
            val style = theme.adaptive!!
            val palette = adaptivePalette(photos, style)
            assertEquals(3, palette.size)
            palette.forEachIndexed { i, c -> assertEquals(style.values[i], Hsv.of(c)[2], 0.01f) }
        }
    }

    @Test
    fun extremePhotosNeverMakeTheWallpaperUnreadable() {
        GlassThemes.adaptiveThemes.forEach { theme ->
            val style = theme.adaptive!!
            val palette = adaptivePalette(listOf(Color.White, Color.Yellow, Color.Cyan, Color.Blue, Color.Black), style)
            // As drawn: under the wallpaper's veil, at its weakest (the top of the screen).
            val veil = style.veil.copy(alpha = style.scrim * 0.6f)
            palette.forEach { c ->
                val ratio = Contrast.ratio(theme.onGlass, veil.compositeOver(c))
                assertTrue(ratio >= Contrast.TEXT, "${theme.displayName}: text on wallpaper $ratio")
            }
        }
    }
}
