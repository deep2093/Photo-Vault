package com.deepak.photovault.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
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
        // Clear glass (Translucent) is exempt from the accent-vs-glass check: the selected pill always carries
        // high-contrast text, and a solid accent would defeat the clear look the theme is for.
        GlassThemes.all.forEach { theme ->
            val onGlass = Contrast.worstOnGlass(theme, theme.accent)
            if (theme.textHalo == null) assertTrue(onGlass >= Contrast.NON_TEXT, "${theme.displayName}: accent on glass $onGlass")
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
    fun blurTintIsNeverTooThinUnlessTextHasAHalo() {
        // The contrast tests above check text over pure black and pure white photos, which is the real guard.
        // This floor stops a theme from relying on blur alone; only clear-glass themes with a halo may go lower.
        GlassThemes.all.forEach { theme ->
            val floor = if (theme.textHalo != null) 0.4f else 0.6f
            assertTrue(theme.glassTint.alpha >= floor, "${theme.displayName}: glass tint alpha ${theme.glassTint.alpha}")
        }
    }

    @Test
    fun adaptiveThemesAreDistinct() {
        // Feedback 22 Oct: the three looked the same. Each needs its own accent, and Glow must be lighter.
        val dark = listOf(GlassThemes.AdaptiveGlow, GlassThemes.AdaptiveMidnight, GlassThemes.AdaptiveVivid)
        assertEquals(dark.size, dark.map { it.accent }.toSet().size)
        assertTrue(GlassThemes.AdaptiveGlow.adaptive!!.values.first() > GlassThemes.AdaptiveMidnight.adaptive!!.values.first() + 0.2f)
        assertTrue(GlassThemes.AdaptiveVivid.adaptive!!.minSaturation > GlassThemes.AdaptiveGlow.adaptive!!.minSaturation)
    }

    @Test
    fun translucentIsClearGlass() {
        assertTrue(GlassThemes.Translucent.glassTint.alpha < 0.5f)
        assertTrue(GlassThemes.Translucent.textHalo != null)
    }
}

class ThemeCatalogTest {
    @Test
    fun hasTheAgreedGlassAndAdaptiveThemesWithUniqueIds() {
        assertEquals(
            listOf("Aurora Frost", "Midnight Prism", "Pearl Mist", "Ocean Glass", "Translucent", "Frosted"),
            GlassThemes.fixed.map { it.displayName },
        )
        assertEquals(
            listOf("Adaptive Glow", "Adaptive Midnight", "Adaptive Vivid", "Adaptive Light"),
            GlassThemes.adaptiveThemes.map { it.displayName },
        )
        assertEquals(GlassThemes.all.size, GlassThemes.all.map { it.id }.toSet().size)
        assertTrue(GlassThemes.adaptiveThemes.all { it.adaptive != null })
        assertTrue(GlassThemes.fixed.all { it.adaptive == null })
    }

    @Test
    fun auroraFrostIsTheDefaultAndDarkThemesAreMidnightAndAdaptive() {
        assertEquals(GlassThemeId.AURORA_FROST, GlassThemes.default.id)
        assertEquals(
            listOf(GlassThemeId.MIDNIGHT_PRISM, GlassThemeId.ADAPTIVE_GLOW, GlassThemeId.ADAPTIVE_MIDNIGHT, GlassThemeId.ADAPTIVE_VIVID),
            (GlassThemes.fixed + GlassThemes.adaptiveThemes).filter { it.isDark }.map { it.id },
        )
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
    fun frostedBlursMoreThanTranslucent() {
        assertTrue(GlassThemes.Frosted.blurRadius > GlassThemes.Translucent.blurRadius)
        assertTrue(GlassThemes.Frosted.grain > GlassThemes.Translucent.grain)
        assertTrue(GlassThemes.Translucent.glassTint.alpha < GlassThemes.Frosted.glassTint.alpha)
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

/** The experiment must be additive, persistable and legible, including its white selected capsules. */
class ExperimentalGlassThemeTest {
    @Test
    fun experimentsAreOptInAndHaveStableSavedIds() {
        assertEquals(listOf("EXPERIMENTAL_AURORA_GLASS", "EXPERIMENTAL_CRYSTAL_GLASS"), GlassThemes.experimental.map { it.id.name })
        assertEquals(10, (GlassThemes.fixed + GlassThemes.adaptiveThemes).size)
        assertTrue((GlassThemes.fixed + GlassThemes.adaptiveThemes).all { it.previewGlass == null })
        assertEquals(GlassThemes.AuroraFrost, GlassThemes.default)
        GlassThemes.experimental.forEach { assertEquals(it, GlassThemes.byId(it.id.name)) }
    }

    @Test
    fun whiteSelectionsKeepReadableLabels() {
        GlassThemes.experimental.forEach { theme ->
            val style = theme.previewGlass!!
            assertTrue(Contrast.ratio(style.onSelected, style.selectedFill) >= Contrast.TEXT, theme.displayName)
            assertTrue(Contrast.worstAnywhere(theme, theme.onGlassSecondary) >= Contrast.TEXT, theme.displayName)
        }
    }

    @Test
    fun crystalUsesExistingAdaptivePipelineAndAuroraRemainsBlueViolet() {
        assertEquals(null, ExperimentalGlassThemes.Aurora.adaptive)
        val crystal = ExperimentalGlassThemes.Crystal.adaptive!!
        assertTrue(crystal.maxSaturation < GlassThemes.AdaptiveGlow.adaptive!!.minSaturation)
        assertEquals(26.dp, ExperimentalGlassThemes.Aurora.blurRadius)
        assertEquals(18.dp, ExperimentalGlassThemes.Crystal.blurRadius)
        assertEquals(crystal.fallback, adaptivePalette(emptyList(), crystal))
        val veil = crystal.veil.copy(alpha = crystal.scrim * 0.6f)
        for (h in 0 until 360 step 5) {
            val photo = Hsv.toColor(h.toFloat(), 1f, 1f)
            adaptivePalette(listOf(photo, Color.White), crystal).forEach { color ->
                assertTrue(Contrast.ratio(ExperimentalGlassThemes.Crystal.onGlassSecondary, veil.compositeOver(color)) >= Contrast.TEXT)
            }
        }
    }
}
