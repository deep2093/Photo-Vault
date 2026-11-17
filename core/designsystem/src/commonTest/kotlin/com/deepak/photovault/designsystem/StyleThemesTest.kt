package com.deepak.photovault.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.test.Test
import kotlin.test.assertTrue

/** The redesign's colours (18 Nov): every text colour readable on the surfaces it sits on, light and dark. */
class StyleThemesTest {
    private val themes = listOf(
        StyleThemes.ClearGlassLight, StyleThemes.ClearGlassDark,
        StyleThemes.DarkroomLight, StyleThemes.DarkroomDark,
        StyleThemes.material(StyleThemes.materialFallback(false), false),
        StyleThemes.material(StyleThemes.materialFallback(true), true),
    )

    private fun surfaces(t: GlassTheme): List<Pair<String, Color>> {
        val page = t.wallpaper.first()
        return listOf(
            "page" to page,
            "card" to (t.panelFill ?: t.glassFallback).compositeOver(page),
            "solid bar" to t.glassFallback.compositeOver(page),
        )
    }

    @Test
    fun textAndSecondaryTextReadOnPagesCardsAndBars() {
        val bad = themes.flatMap { t ->
            surfaces(t).flatMap { (where, bg) ->
                listOfNotNull(
                    Contrast.ratio(t.onGlass, bg).takeIf { it < Contrast.TEXT }?.let { "${t.id} text on $where: $it" },
                    Contrast.ratio(t.onGlassSecondary, bg).takeIf { it < Contrast.TEXT }?.let { "${t.id} secondary on $where: $it" },
                    Contrast.ratio(t.accent, bg).takeIf { it < Contrast.TEXT }?.let { "${t.id} accent on $where: $it" },
                )
            }
        }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test
    fun textOnTheAccentAndOnTheSoftChoiceReads() {
        val bad = themes.flatMap { t ->
            val card = (t.panelFill ?: t.glassFallback).compositeOver(t.wallpaper.first())
            listOfNotNull(
                Contrast.ratio(t.onAccent, t.accent).takeIf { it < Contrast.TEXT }?.let { "${t.id} on accent: $it" },
                t.accentSoft?.let { soft ->
                    Contrast.ratio(t.onAccentSoft ?: t.onGlass, soft.compositeOver(card)).takeIf { it < Contrast.TEXT }?.let { "${t.id} on soft: $it" }
                },
            )
        }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test
    fun clearGlassBarsStayReadableOverBrightAndDarkPhotos() {
        val bad = listOf(StyleThemes.ClearGlassLight, StyleThemes.ClearGlassDark).flatMap { t ->
            listOf(Color.Black, Contrast.BRIGHT_PHOTO).mapNotNull { photo ->
                val glass = t.glassTint.compositeOver(photo)
                Contrast.ratio(t.onGlass, glass).takeIf { it < Contrast.TEXT }?.let { "${t.id} over $photo: $it" }
            }
        }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }
}
