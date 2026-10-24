package com.deepak.photovault.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.math.pow

/** WCAG 2.x contrast maths. Pure functions, used by tests to keep glass readable. */
object Contrast {
    /** Normal text needs at least this ratio. */
    const val TEXT = 4.5

    /** Icons, borders and large text need at least this ratio. */
    const val NON_TEXT = 3.0

    fun relativeLuminance(color: Color): Double {
        fun channel(v: Float): Double {
            val c = v.toDouble()
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    fun ratio(a: Color, b: Color): Double {
        val la = relativeLuminance(a.compositeOver(Color.Black))
        val lb = relativeLuminance(b.compositeOver(Color.Black))
        val (hi, lo) = if (la >= lb) la to lb else lb to la
        return (hi + 0.05) / (lo + 0.05)
    }

    /**
     * Opaque backgrounds that glass can end up as: glass over the busiest photos (black and white) and glass
     * over the wallpaper. For adaptive themes the wallpaper is every hue the palette can produce.
     * Clear-glass themes draw a white halo behind text; it is modelled as 75% white behind the letters.
     */
    fun glassBackgrounds(theme: GlassTheme): List<Color> {
        val glass = listOf(theme.glassTint.compositeOver(Color.Black), theme.glassTint.compositeOver(Color.White)) +
            wallpaperColors(theme).map { theme.glassFallback.compositeOver(it) }
        // Bound the experimental sheen conservatively by its strongest alpha in white.
        val sheen = theme.previewGlass?.sheen?.maxOf { it.alpha } ?: 0f
        val surfaces = if (sheen > 0f) glass + glass.map { Color.White.copy(alpha = sheen).compositeOver(it) } else glass
        val halo = theme.textHalo ?: return surfaces
        return surfaces.map { halo.copy(alpha = HALO_STRENGTH).compositeOver(it) }
    }

    /** Glass plus the bare wallpaper, which primary text also sits on (photo date headers, empty screens). */
    fun allTextBackgrounds(theme: GlassTheme): List<Color> = glassBackgrounds(theme) + wallpaperColors(theme)

    private const val HALO_STRENGTH = 0.75f

    /** Wallpaper colours to test against. Adaptive: all hues at both saturation limits, under the lightest veil. */
    fun wallpaperColors(theme: GlassTheme): List<Color> {
        val style = theme.adaptive ?: return theme.wallpaper
        val veil = style.veil.copy(alpha = style.scrim * 0.6f)
        val generated = (0 until 360 step 5).flatMap { hue ->
            listOf(style.minSaturation, style.maxSaturation).flatMap { s ->
                style.values.map { v -> veil.compositeOver(Hsv.toColor(hue.toFloat(), s, v)) }
            }
        }
        return theme.wallpaper + generated
    }

    /** The lowest contrast [foreground] reaches on glass. */
    fun worstOnGlass(theme: GlassTheme, foreground: Color): Double =
        glassBackgrounds(theme).minOf { ratio(foreground, it) }

    /** The lowest contrast [foreground] reaches anywhere text is drawn, glass or bare wallpaper. */
    fun worstAnywhere(theme: GlassTheme, foreground: Color): Double =
        allTextBackgrounds(theme).minOf { ratio(foreground, it) }
}
