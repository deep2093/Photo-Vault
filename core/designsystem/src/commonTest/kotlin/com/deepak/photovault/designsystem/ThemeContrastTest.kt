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
        // Every theme and every pale version: onGlass on glass and frosted cards, onWallpaper straight on the page.
        val bad = GlassThemes.allVariants.mapNotNull { theme ->
            val worst = Contrast.worstPrimary(theme)
            val need = if (theme.isPaleVariant || theme.milky) Contrast.NON_TEXT else Contrast.TEXT
            if (worst >= need) null else "${theme.displayName} (pale=${theme.isPaleVariant}): primary $worst < $need"
        }
        assertTrue(bad.isEmpty(), bad.joinToString("; "))
    }

    @Test
    fun secondaryTextIsReadableOnEveryGlassBackground() {
        val bad = GlassThemes.allVariants.mapNotNull { theme ->
            val worst = Contrast.worstOnGlass(theme, theme.onGlassSecondary)
            val need = if (theme.isPaleVariant || theme.milky) Contrast.NON_TEXT else Contrast.TEXT
            if (worst >= need) null else "${theme.displayName} (pale=${theme.isPaleVariant}): secondary $worst < $need"
        }
        assertTrue(bad.isEmpty(), bad.joinToString("; "))
    }

    @Test
    fun accentIsVisibleOnGlassAndItsTextIsReadable() {
        val bad = GlassThemes.allVariants.flatMap { theme ->
            val out = mutableListOf<String>()
            val onGlass = Contrast.worstOnGlass(theme, theme.accent)
            if (onGlass < Contrast.NON_TEXT) out += "${theme.displayName} (pale=${theme.isPaleVariant}): accent on glass $onGlass"
            // Selected items are a frosted film, not a colour: the label is checked over each glass colour.
            val fill = theme.accentFill!!
            val worst = Contrast.glassBackgrounds(theme).minOf { Contrast.ratio(theme.onAccent, fill.compositeOver(it)) }
            val need = if (theme.isPaleVariant) 2.5 else if (theme.milky) 2.0 else Contrast.NON_TEXT
            if (worst < need) out += "${theme.displayName} (pale=${theme.isPaleVariant}): text on frosted accent $worst < $need"
            out
        }
        assertTrue(bad.isEmpty(), bad.joinToString("; "))
    }

    @Test
    fun badgeIconsAreReadable() {
        GlassThemes.allVariants.forEach { theme ->
            listOf(theme.badgeBackedUp, theme.badgePhoneOnly, theme.badgeOnDrive).forEach { fill ->
                val ratio = Contrast.ratio(theme.onBadge, fill)
                assertTrue(ratio >= Contrast.TEXT, "${theme.displayName}: badge contrast $ratio")
            }
        }
    }

    @Test
    fun blurTintIsNeverTooThin() {
        // The contrast tests above check text over black and a very bright photo, which is the real guard.
        // This floor stops a theme from relying on blur alone.
        GlassThemes.allVariants.forEach { theme ->
            assertTrue(theme.milky || theme.glassTint.alpha >= 0.4f, "${theme.displayName}: glass tint alpha ${theme.glassTint.alpha}")
        }
    }

    @Test
    fun everyThemeHasTheOneGlassLookWithWhiteTextAndNoLens() {
        GlassThemes.allVariants.forEach { theme ->
            assertEquals(Color.White, theme.onGlass, theme.displayName)
            assertEquals(Color.White, theme.accent, theme.displayName)
            assertTrue(theme.accentFill != null && theme.panelFill != null, theme.displayName)
            assertTrue(!theme.lens, theme.displayName)
        }
    }

    @Test
    fun adaptiveThemesAreDistinct() {
        // Feedback 22 and 23 Oct: they looked alike. Each needs its own wallpaper shape.
        val adaptive = GlassThemes.adaptiveThemes
        assertEquals(adaptive.size, adaptive.map { it.adaptive!!.layout }.toSet().size)
        assertTrue(GlassThemes.AdaptiveLight.adaptive!!.values.first() > GlassThemes.AdaptiveMidnight.adaptive!!.values.first() + 0.05f)
        assertTrue(GlassThemes.AdaptiveVivid.adaptive!!.minSaturation > GlassThemes.AdaptiveLight.adaptive!!.minSaturation)
    }
}

class ThemeCatalogTest {
    @Test
    fun themesAreSortedIntoLightAndDarkSectionsWithUniqueIds() {
        assertEquals(listOf("Warm Light", "Teal Light", "Luminous Light", "Clear Light", "Liquid Day", "Photo Light"), GlassThemes.lightThemes.map { it.displayName })
        assertEquals(listOf("Luminous Night", "Liquid Night", "Photo Night", "Vivid Night"), GlassThemes.darkThemes.map { it.displayName })
        assertEquals(GlassThemes.all.size, GlassThemes.all.map { it.id }.toSet().size)
        assertTrue(GlassThemes.adaptiveThemes.all { it.adaptive != null })
        assertTrue(GlassThemes.fixed.all { it.adaptive == null })
    }

    @Test
    fun everyLightThemeHasAPaleVersionAndDarkThemesDoNot() {
        GlassThemes.lightThemes.forEach { theme ->
            val pale = theme.pale
            assertTrue(pale != null, theme.displayName)
            assertEquals(theme.id, pale!!.id)
            assertTrue(!pale.isDark)
            assertTrue(pale.onWallpaper != pale.onGlass)
            assertEquals(pale, GlassThemes.shown(theme, palePreferred = true))
            assertEquals(theme, GlassThemes.shown(theme, palePreferred = false))
        }
        GlassThemes.darkThemes.forEach { theme ->
            assertTrue(theme.pale == null, theme.displayName)
            assertEquals(theme, GlassThemes.shown(theme, palePreferred = true))
        }
    }

    @Test
    fun photoLightIsTheDefault() {
        assertEquals(GlassThemes.AdaptiveLight, GlassThemes.default)
    }

    @Test
    fun removedThemesFallBackToTheDefault() {
        listOf(
            "AURORA_FROST", "TRANSLUCENT", "FROSTED", "CRYSTAL",
            // Removed 29 Oct: Violet Night, Glass Night, Clear Night, Glow Night (and Glass Light earlier).
            "MIDNIGHT_PRISM", "GLASS_NIGHT", "CRYSTAL_GLASS_DARK", "ADAPTIVE_GLOW", "GLASS_LIGHT",
        ).forEach { assertEquals(GlassThemes.default, GlassThemes.byId(it), it) }
    }

    @Test
    fun unknownSavedIdFallsBackToDefault() {
        assertEquals(GlassThemes.default, GlassThemes.byId("SOMETHING_FROM_A_NEWER_VERSION"))
        assertEquals(GlassThemes.default, GlassThemes.byId(null))
        assertEquals(GlassThemes.PearlMist, GlassThemes.byId("PEARL_MIST"))
        assertEquals(GlassThemes.LiquidNight, GlassThemes.byId("LIQUID_NIGHT"))
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
    fun photoLightHasADeepVersionAndAPaleOneWithAWhiteVeilAndDarkTextOnThePage() {
        val deep = GlassThemes.AdaptiveLight.adaptive!!
        assertTrue(!deep.light)
        val pale = GlassThemes.AdaptiveLight.pale!!
        assertTrue(pale.adaptive!!.light)
        assertEquals(Color.White, pale.adaptive!!.veil)
        assertTrue(Contrast.relativeLuminance(pale.onWallpaper) < 0.05)
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
        GlassThemes.allVariants.filter { it.adaptive != null }.forEach { theme ->
            val style = theme.adaptive!!
            val palette = adaptivePalette(listOf(Color.White, Color.Yellow, Color.Cyan, Color.Blue, Color.Black), style)
            // As drawn: under the wallpaper's veil, at its weakest (the top of the screen).
            val veil = style.veil.copy(alpha = style.scrim * 0.6f)
            palette.forEach { c ->
                val ratio = Contrast.ratio(theme.onWallpaper, veil.compositeOver(c))
                assertTrue(ratio >= Contrast.TEXT, "${theme.displayName}: text on wallpaper $ratio")
            }
        }
    }
}
