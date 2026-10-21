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
     * Every opaque background that text can end up on: glass over the busiest photos (black and white),
     * glass over the wallpaper, and the bare wallpaper (photo date headers sit straight on it).
     * For adaptive themes the wallpaper is every hue the palette can produce, at its brightest.
     */
    fun glassBackgrounds(theme: GlassTheme): List<Color> {
        val walls = wallpaperColors(theme)
        return listOf(theme.glassTint.compositeOver(Color.Black), theme.glassTint.compositeOver(Color.White)) +
            walls.map { theme.glassFallback.compositeOver(it) } + walls
    }

    /** Wallpaper colours to test against. Adaptive: all hues at both saturation limits, under the lightest veil. */
    fun wallpaperColors(theme: GlassTheme): List<Color> {
        val style = theme.adaptive ?: return theme.wallpaper
        val veil = Color.Black.copy(alpha = style.scrim * 0.6f)
        val generated = (0 until 360 step 5).flatMap { hue ->
            listOf(style.minSaturation, style.maxSaturation).flatMap { s ->
                style.values.map { v -> veil.compositeOver(Hsv.toColor(hue.toFloat(), s, v)) }
            }
        }
        return theme.wallpaper + generated
    }

    /** The lowest contrast [foreground] reaches on any background in [glassBackgrounds]. */
    fun worstOnGlass(theme: GlassTheme, foreground: Color): Double =
        glassBackgrounds(theme).minOf { ratio(foreground, it) }
}
