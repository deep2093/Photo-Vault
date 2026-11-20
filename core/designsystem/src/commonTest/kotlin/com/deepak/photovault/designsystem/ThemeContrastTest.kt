package com.deepak.photovault.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        val bad = GlassThemes.all.mapNotNull { theme ->
            val worst = Contrast.worstPrimary(theme)
            val need = if (theme.milky) Contrast.NON_TEXT else Contrast.TEXT
            if (worst >= need) null else "${theme.displayName} (pale=${theme.isPaleVariant}): primary $worst < $need"
        }
        assertTrue(bad.isEmpty(), bad.joinToString("; "))
    }

    @Test
    fun secondaryTextIsReadableOnEveryGlassBackground() {
        val bad = GlassThemes.all.mapNotNull { theme ->
            val worst = Contrast.worstOnGlass(theme, theme.onGlassSecondary)
            val need = if (theme.milky) Contrast.NON_TEXT else Contrast.TEXT
            if (worst >= need) null else "${theme.displayName} (pale=${theme.isPaleVariant}): secondary $worst < $need"
        }
        assertTrue(bad.isEmpty(), bad.joinToString("; "))
    }

    @Test
    fun accentIsVisibleOnGlassAndItsTextIsReadable() {
        val bad = GlassThemes.all.flatMap { theme ->
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
        GlassThemes.all.forEach { theme ->
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
        GlassThemes.all.forEach { theme ->
            assertTrue(theme.milky || theme.isPaleVariant || theme.glassTint.alpha >= 0.4f, "${theme.displayName}: glass tint alpha ${theme.glassTint.alpha}")
        }
    }

    @Test
    fun everyThemeHasTheOneGlassLook() {
        GlassThemes.all.forEach { theme ->
            if (theme.isPaleVariant) assertEquals(theme.onWallpaper, theme.onGlass, theme.displayName)
            else { assertEquals(Color.White, theme.onGlass, theme.displayName); assertEquals(Color.White, theme.accent, theme.displayName) }
            assertTrue(theme.accentFill != null && theme.panelFill != null, theme.displayName)
        }
    }

    @Test
    fun liquidGlassFallbackInkStaysReadableOverBlackAndWhitePhotos() {
        // Liquid Glass and Clear Glass (21 Nov).
        for (theme in GlassThemes.all.filter { it.isLiquidGlass }) {
            for (photo in listOf(Color.Black, Color.White)) {
                val glass = theme.glassTint.compositeOver(photo)
                for (ink in listOf(theme.onGlass, theme.onGlassSecondary)) {
                    assertTrue(Contrast.ratio(ink, glass) >= Contrast.TEXT, theme.displayName)
                    assertTrue(Contrast.ratio(ink, theme.glassFallback) >= Contrast.TEXT, theme.displayName)
                }
            }
        }
    }

    @Test
    fun liquidGlassLensKeepsWordsReadableOverAnyPhoto() {
        // 21 Nov: clear glass, never blurred. Behind the middle of the glass the lens brightens the photo (day) or smokes
        // it (night) just enough for the words; checked over the photos hardest for each, including the selected tab.
        val photos = listOf(
            Color.Black, Color.White, Contrast.BRIGHT_PHOTO, Color(0xFF808080), Color(0xFF404040),
            Color.Red, Color.Green, Color.Blue, Color.Yellow, Color.Cyan, Color.Magenta,
            Color(0xFF0B1E3F), Color(0xFFFFE9A8), Color(0xFF1B4D2B),
        )
        val bad = mutableListOf<String>()
        // Liquid Glass and Clear Glass (21 Nov), each with its own ink rule; Clear Glass also Tinted.
        for (theme in GlassThemes.all.filter { it.isLiquidGlass }) {
            val ink = theme.glassInk!!
            for (photo in photos) {
                // Clear Glass's Clear has its light film; Liquid Glass none.
                val film = if (theme.isClearGlass) LiquidGlassInk.CLEAR_FILM else 0f
                val looks = mutableListOf(
                    "clear" to LiquidGlassInk.throughGlass(
                        photo, theme.isDark, ink.dayFloor, ink.nightCeiling, film, LiquidGlassInk.tintedFrostColor(theme.isDark),
                    ),
                )
                if (theme.isClearGlass) {
                    looks += "tinted" to LiquidGlassInk.throughGlass(
                        photo, theme.isDark, ink.dayFloor, ink.nightCeiling,
                        LiquidGlassInk.TINTED_FROST, LiquidGlassInk.tintedFrostColor(theme.isDark),
                    )
                }
                for ((look, glass) in looks) {
                    for (words in listOf(theme.onGlass, theme.onGlassSecondary)) {
                        val ratio = Contrast.ratio(words, glass)
                        if (ratio < Contrast.TEXT) bad += "${theme.displayName} ($look): words over $photo $ratio"
                    }
                }
                val frost = when {
                    !theme.isClearGlass -> 0f
                    theme.isDark -> LiquidGlassInk.SELECTED_FROST_NIGHT
                    else -> LiquidGlassInk.SELECTED_FROST_DAY
                }
                val selected = LiquidGlassInk.throughGlass(
                    photo, theme.isDark, ink.selectedDayFloor, ink.selectedNightCeiling, frost, LiquidGlassInk.selectedFrostColor(theme.isDark),
                )
                // Clear Glass's chosen tab is iOS blue on its droplet, at 3:1 like iOS's; Liquid Glass's words at 4.5:1.
                val need = if (theme.isClearGlass) Contrast.NON_TEXT else Contrast.TEXT
                val ratio = Contrast.ratio(theme.onAccent, selected)
                if (ratio < need) bad += "${theme.displayName}: selected tab over $photo $ratio"
            }
        }
        assertTrue(bad.isEmpty(), bad.joinToString("; "))
    }

    @Test
    fun theChosenOptionShowsInEveryTheme() {
        // 21 Nov (Deepak: "cannot see which option is selected"): Clear Glass's white marker sat on a white strip. The
        // marker must stand out from its strip in every theme, and the chosen option's words must read on it.
        val bad = mutableListOf<String>()
        for (theme in GlassThemes.all) {
            val cards = Contrast.panelBackgrounds(theme).ifEmpty { listOf(theme.glassFallback) }
            for (card in cards) {
                val strip = theme.segmentTrack?.compositeOver(card) ?: card
                val marker = theme.segmentThumb.compositeOver(strip)
                val stands = Contrast.ratio(marker, strip)
                if (stands < 1.3) bad += "${theme.displayName} (pale=${theme.isPaleVariant}): marker on its strip $stands"
                // Clear Glass keeps the usual words on its marker; they must read there. The quiet highlight behind a
                // chosen row (the side bar) must show on the card too.
                if (theme.isClearGlass) {
                    val reads = Contrast.ratio(theme.onGlass, marker)
                    if (reads < Contrast.TEXT) bad += "${theme.displayName}: words on the marker $reads"
                    val row = Contrast.ratio(theme.chosenRowFill.compositeOver(card), card)
                    if (row < 1.15) bad += "${theme.displayName}: chosen row on its card $row"
                }
            }
        }
        assertTrue(bad.isEmpty(), bad.joinToString("; "))
    }

    @Test
    fun clearGlassFloatingGlassIsTheNightLookByDayToo() {
        // 21 Nov (Deepak: "make this the default glass in day, don't change colour when scrolling"): by day the bars
        // take the night theme's glass and words. Those words must read on that glass even where the day's white page
        // shows through it (the lens puts the page's colour behind see-through content).
        val night = GlassThemes.familyOf(GlassThemeId.CLEAR_GLASS_LIGHT.name).night
        val ink = night.glassInk!!
        for (behind in GlassThemes.ClearGlassLight.wallpaper + listOf(Color.White, Color.Black)) {
            for (film in listOf(LiquidGlassInk.CLEAR_FILM, LiquidGlassInk.TOP_FILM, LiquidGlassInk.TINTED_FROST)) {
                val glass = LiquidGlassInk.throughGlass(behind, true, ink.dayFloor, ink.nightCeiling, film, LiquidGlassInk.tintedFrostColor(true))
                assertTrue(Contrast.ratio(night.onGlass, glass) >= Contrast.TEXT, "words over $behind with film $film")
                assertTrue(Contrast.ratio(night.onGlassSecondary, glass) >= Contrast.TEXT, "small words over $behind with film $film")
            }
        }
    }

    @Test
    fun tintedIsClearlyMoreSolidThanClear() {
        // 21 Nov (Deepak: "Tinted and Clear differences hard to find, specially in day"). A black and a white photo
        // behind the glass: Clear keeps them well apart, Tinted brings them close together.
        assertTrue(LiquidGlassInk.TINTED_FROST - LiquidGlassInk.CLEAR_FILM >= 0.35f)
        // The pills at the top share a film between the two: even, but still glass.
        assertTrue(LiquidGlassInk.TOP_FILM > LiquidGlassInk.CLEAR_FILM && LiquidGlassInk.TOP_FILM < LiquidGlassInk.TINTED_FROST)
        for (theme in listOf(GlassThemes.ClearGlassLight, GlassThemes.ClearGlassDark)) {
            val ink = theme.glassInk!!
            fun spread(frost: Float): Double {
                val film = LiquidGlassInk.tintedFrostColor(theme.isDark)
                val black = LiquidGlassInk.throughGlass(Color.Black, theme.isDark, ink.dayFloor, ink.nightCeiling, frost, film)
                val white = LiquidGlassInk.throughGlass(Color.White, theme.isDark, ink.dayFloor, ink.nightCeiling, frost, film)
                return Contrast.ratio(black, white)
            }
            val clear = spread(LiquidGlassInk.CLEAR_FILM)
            val tinted = spread(LiquidGlassInk.TINTED_FROST)
            assertTrue(clear >= 1.5, "${theme.displayName}: Clear still shows the photo ($clear)")
            assertTrue(tinted <= 1.35, "${theme.displayName}: Tinted is nearly solid ($tinted)")
        }
    }

    @Test
    fun clearGlassNightCardsAreBlackGlass() {
        // 21 Nov (Deepak picked "black glass" over the dark grey): cards are nearly black on every part of the page, and
        // the words on them read.
        val theme = GlassThemes.ClearGlassDark
        for (card in Contrast.panelBackgrounds(theme)) {
            // (panelBackgrounds adds the glass's own faint sheen, so "nearly black" is a little above the bare fill.)
            assertTrue(Contrast.relativeLuminance(card) < 0.03, "card $card is not nearly black")
            assertTrue(Contrast.ratio(theme.onGlass, card) >= 12.0)
            assertTrue(Contrast.ratio(theme.onGlassSecondary, card) >= Contrast.TEXT)
        }
    }

    @Test
    fun extraAnimationsAreOnUnlessTurnedOff() {
        // 21 Nov: small animations in every theme, with a switch in Settings > About.
        assertTrue(GlassSettings().extraMotion)
        assertFalse(GlassSettings().iosMotion)
    }

    @Test
    fun clearGlassIsLiquidGlassWithIosColours() {
        // 21 Nov: the copy of iOS shares Liquid Glass's lens and adds its own. iOS's white and black pages, blue.
        val family = GlassThemes.familyOf(GlassThemeId.CLEAR_GLASS_LIGHT.name)
        assertEquals("Clear Glass", family.name)
        assertEquals(GlassThemes.ClearGlassLight, family.day)
        assertEquals(GlassThemes.ClearGlassDark, family.night)
        for (theme in listOf(family.day, family.night)) {
            assertTrue(theme.isLiquidGlass && theme.isClearGlass, theme.displayName)
            assertTrue(theme.prominent != null && theme.glassInk == GlassInk.CLEAR, theme.displayName)
            // White words on the blue main buttons.
            assertTrue(Contrast.ratio(Color.White, theme.prominent!!) >= Contrast.TEXT, theme.displayName)
        }
        assertEquals(Color.White, family.day.wallpaper.first())
        assertEquals(Color.Black, family.night.wallpaper.first())
        assertFalse(GlassThemes.LiquidGlassLight.isClearGlass)
    }

    @Test
    fun liquidGlassIsClearNotFrosted() {
        // Never blurs (21 Nov), Clear Glass included.
        GlassThemes.all.filter { it.isLiquidGlass }.forEach { assertEquals(0f, it.blurRadius.value, it.displayName) }
        assertEquals(4, GlassThemes.all.count { it.isLiquidGlass })
        // Bright photos pass the day glass untouched and dark ones the night glass: nothing is laid over them.
        assertEquals(Color.White, LiquidGlassInk.throughGlass(Color.White, dark = false))
        assertEquals(Color.Black, LiquidGlassInk.throughGlass(Color.Black, dark = true))
        // A dark photo brightened by day keeps its colour instead of turning a milky grey.
        val navy = LiquidGlassInk.throughGlass(Color(0xFF0B1E3F), dark = false)
        assertTrue(navy.blue - navy.red > 0.05f, "navy through the day glass: $navy")
        val forest = LiquidGlassInk.throughGlass(Color(0xFF1B4D2B), dark = false)
        assertTrue(forest.green - forest.red > 0.1f, "forest through the day glass: $forest")
    }

    @Test
    fun adaptiveThemesAreDistinct() {
        // Feedback 22 and 23 Oct: they looked alike. Each needs its own wallpaper shape among the day themes and among
        // the night ones (18 Nov: a theme's day and night versions share theirs).
        listOf(GlassThemes.lightThemes, GlassThemes.darkThemes).forEach { group ->
            val adaptive = group.filter { it.adaptive != null }
            assertEquals(adaptive.size, adaptive.map { it.adaptive!!.layout }.toSet().size)
        }
        assertTrue(GlassThemes.AdaptiveVivid.adaptive!!.minSaturation > GlassThemes.AdaptiveMidnight.adaptive!!.minSaturation)
    }
}

class ThemeCatalogTest {
    @Test
    fun themesAreSortedIntoLightAndDarkSectionsWithUniqueIds() {
        assertEquals(listOf("Ocean Day", "Pearl Day", "Aurora Day", "Gallery Day", "Prism Day", "Liquid Glass Light", "Clear Glass Light"), GlassThemes.lightThemes.map { it.displayName })
        assertEquals(listOf("Ocean Night", "Pearl Night", "Aurora Night", "Gallery Night", "Prism Night", "Liquid Glass Dark", "Clear Glass Dark"), GlassThemes.darkThemes.map { it.displayName })
        assertEquals(GlassThemes.all.size, GlassThemes.all.map { it.id }.toSet().size)
        assertTrue(GlassThemes.adaptiveThemes.all { it.adaptive != null })
        assertTrue(GlassThemes.fixed.all { it.adaptive == null })
    }

    @Test
    fun everyThemeHasALightDayAndADarkNight() {
        // 18 Nov: each theme has a day version (light page, dark text) and a night one (deep page, white text). Six
        // since Pearl came over from Skywing and Deepak's Liquid Glass was added (20 Nov); seven with Clear Glass (21 Nov).
        assertEquals(7, GlassThemes.families.size)
        GlassThemes.families.forEach { family ->
            assertFalse(family.day.isDark, family.name)
            assertTrue(family.night.isDark, family.name)
            assertEquals(family.day.onWallpaper, family.day.onGlass, family.name)
            assertEquals(family.day, family.pick(dark = false))
            assertEquals(family.night, family.pick(dark = true))
            // Found again from either version's saved id.
            assertEquals(family, GlassThemes.familyOf(family.day.id.name))
            assertEquals(family, GlassThemes.familyOf(family.night.id.name))
        }
        assertEquals(GlassThemes.families.first(), GlassThemes.familyOf("PEARL_MIST"))
        assertEquals(GlassThemes.default, GlassThemes.families.first().day)
    }

    @Test
    fun calmDayGlassKeepsDarkTextReadableOverDarkPhotos() {
        // 18 Nov, calm glass: the day themes' bars are white enough for their dark words over a black photo too.
        if (!GlassFinish.CALM) return
        GlassThemes.lightThemes.forEach { theme ->
            val ratio = Contrast.ratio(theme.onGlass, theme.glassTint.compositeOver(Color.Black))
            assertTrue(ratio >= Contrast.TEXT, "${theme.displayName}: dark words over a black photo $ratio")
        }
    }

    @Test
    fun pearlIsSkywingsLiquidDayWithANight() {
        // 20 Nov: the nearly white page of Skywing's Liquid Day, with its lustre, and a black pearl at night.
        val pearl = GlassThemes.familyOf(GlassThemeId.PEARL_NIGHT.name)
        assertEquals("Pearl", pearl.name)
        assertEquals(GlassThemes.PearlDay, pearl.day)
        assertEquals(GlassThemes.PearlNight, pearl.night)
        // A light page (deeper at the bottom since 20 Nov, for depth) under dark words.
        assertTrue(pearl.day.wallpaper.all { Contrast.relativeLuminance(it) > 0.7 }, "Pearl Day's page is light")
        assertTrue(pearl.night.wallpaper.all { Contrast.relativeLuminance(it) < 0.02 }, "Pearl Night's page is nearly black")
        assertEquals(3, pearl.day.lustre?.size)
        assertEquals(3, pearl.night.lustre?.size)
        // The other themes keep their calm pages.
        assertTrue(GlassThemes.all.filter { it.id != GlassThemeId.PEARL_DAY && it.id != GlassThemeId.PEARL_NIGHT }.all { it.lustre == null })
    }

    @Test
    fun liquidDayIsTheDefault() {
        assertEquals(GlassThemes.LiquidDay.pale, GlassThemes.default)
    }

    @Test
    fun removedThemesFallBackToTheDefault() {
        listOf(
            "AURORA_FROST", "TRANSLUCENT", "FROSTED", "CRYSTAL",
            // Removed 29 Oct: Violet Night, Glass Night, Clear Night, Glow Night (and Glass Light earlier).
            "MIDNIGHT_PRISM", "GLASS_NIGHT", "CRYSTAL_GLASS_DARK", "ADAPTIVE_GLOW", "GLASS_LIGHT",
            // Removed 29 Oct (later): Teal Light, Luminous Night, Liquid Night.
            "OCEAN_GLASS", "AURORA_GLASS_DARK", "LIQUID_NIGHT", "ADAPTIVE_LIGHT",
            // Removed 18 Nov: Warm Light, Clear Light.
            "PEARL_MIST", "CRYSTAL_GLASS_LIGHT",
        ).forEach { assertEquals(GlassThemes.default, GlassThemes.byId(it), it) }
    }

    @Test
    fun unknownSavedIdFallsBackToDefault() {
        assertEquals(GlassThemes.default, GlassThemes.byId("SOMETHING_FROM_A_NEWER_VERSION"))
        assertEquals(GlassThemes.default, GlassThemes.byId(null))
        assertEquals(GlassThemes.AuroraGlassLight.pale, GlassThemes.byId("AURORA_GLASS_LIGHT"))
        assertEquals(GlassThemes.default, GlassThemes.byId("LIQUID_NIGHT"))
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
        GlassThemes.all.filter { it.adaptive != null }.forEach { theme ->
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
