package com.deepak.photovault.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Stable IDs: saved in settings, so never rename them. */
enum class GlassThemeId {
    AURORA_FROST, MIDNIGHT_PRISM, PEARL_MIST, OCEAN_GLASS, TRANSLUCENT, FROSTED,
    ADAPTIVE_GLOW, ADAPTIVE_MIDNIGHT, ADAPTIVE_VIVID, ADAPTIVE_LIGHT,
}

/** A soft radial light on the wallpaper. Position and radius are fractions of the screen. */
@Immutable
data class Glow(val x: Float, val y: Float, val radius: Float, val color: Color)

/**
 * One Liquid Glass theme. Glass is for controls and panels only; photos are never tinted or blurred.
 *
 * Every text colour is checked by [ThemeContrastTest] against the worst background it can appear on:
 * [glassTint] over pure black and pure white (blurred photos can be anything), and [glassFallback]
 * over each wallpaper colour.
 */
@Immutable
data class GlassTheme(
    val id: GlassThemeId,
    val displayName: String,
    val bestFor: String,
    val isDark: Boolean,
    /** Wallpaper gradient, top-left to bottom-right. Adaptive themes use it until a photo is on screen. */
    val wallpaper: List<Color>,
    val glows: List<Glow>,
    /** Laid over the blurred content behind floating bars. */
    val glassTint: Color,
    /** Used when there is no blur: panels, battery saver, blur turned off, Android 11. */
    val glassFallback: Color,
    /** Fine specular edge on glass. */
    val glassRim: Color,
    val onGlass: Color,
    val onGlassSecondary: Color,
    /** Selected items, primary buttons, progress. */
    val accent: Color,
    val onAccent: Color,
    /** Status badge fills, each carrying a white icon. */
    val badgeBackedUp: Color,
    val badgePhoneOnly: Color,
    val badgeOnDrive: Color,
    val onBadge: Color,
    /** Placeholder tiles for sample photos (Phase 1 only). */
    val sampleHues: List<Color>,
    /** Adaptive themes: the wallpaper takes its colours from the photos on screen (as in Veyra). */
    val adaptive: AdaptiveStyle? = null,
    /** How strongly floating bars blur what is behind them. */
    val blurRadius: Dp = 24.dp,
    /** Fine grain in the glass; higher looks more frosted. */
    val grain: Float = 0.03f,
)

object GlassThemes {
    val AuroraFrost = GlassTheme(
        id = GlassThemeId.AURORA_FROST,
        displayName = "Aurora Frost",
        bestFor = "Default light mode",
        isDark = false,
        wallpaper = listOf(Color(0xFFEAF4FF), Color(0xFFF7FBFF), Color(0xFFD6E6FA)),
        glows = listOf(
            Glow(0.9f, 0.05f, 0.65f, Color(0x4D8EC5FF)),
            Glow(0.1f, 0.75f, 0.7f, Color(0x332A4E8C)),
            Glow(0.4f, 0.3f, 0.4f, Color(0x80FFFFFF)),
        ),
        glassTint = Color(0xCCEEF6FF),
        glassFallback = Color(0xF2F3F8FF),
        glassRim = Color(0xFF7A97C2),
        onGlass = Color(0xFF0B1F3A),
        onGlassSecondary = Color(0xFF34496A),
        accent = Color(0xFF1A62C2),
        onAccent = Color(0xFFFFFFFF),
        badgeBackedUp = Color(0xFF1B6E45),
        badgePhoneOnly = Color(0xFF8A4B00),
        badgeOnDrive = Color(0xFF1A62C2),
        onBadge = Color(0xFFFFFFFF),
        sampleHues = listOf(Color(0xFF6FA8DC), Color(0xFF9FC5E8), Color(0xFF3D5A80), Color(0xFFB4D2F0), Color(0xFF7F9CC9)),
    )

    val MidnightPrism = GlassTheme(
        id = GlassThemeId.MIDNIGHT_PRISM,
        displayName = "Midnight Prism",
        bestFor = "Dark mode on OLED screens",
        isDark = true,
        wallpaper = listOf(Color(0xFF05060F), Color(0xFF14113D), Color(0xFF22104A)),
        glows = listOf(
            Glow(0.85f, 0.1f, 0.7f, Color(0x407C4DFF)),
            Glow(0.1f, 0.85f, 0.8f, Color(0x333D5AFE)),
        ),
        glassTint = Color(0xCC0D0B24),
        glassFallback = Color(0xF214122E),
        glassRim = Color(0xFF8E80D9),
        onGlass = Color(0xFFF4F2FF),
        onGlassSecondary = Color(0xFFBCB6E6),
        accent = Color(0xFFB39BFF),
        onAccent = Color(0xFF160033),
        badgeBackedUp = Color(0xFF1B6E45),
        badgePhoneOnly = Color(0xFF8A4B00),
        badgeOnDrive = Color(0xFF4B3AA8),
        onBadge = Color(0xFFFFFFFF),
        sampleHues = listOf(Color(0xFF3B2F7A), Color(0xFF5E3FA3), Color(0xFF1F2A6B), Color(0xFF7A4FB5), Color(0xFF2C1F5E)),
    )

    val PearlMist = GlassTheme(
        id = GlassThemeId.PEARL_MIST,
        displayName = "Pearl Mist",
        bestFor = "Soft, warm look",
        isDark = false,
        wallpaper = listOf(Color(0xFFFFF8F1), Color(0xFFFBEDE6), Color(0xFFF4E0D6)),
        glows = listOf(
            Glow(0.9f, 0.05f, 0.7f, Color(0x40F4B9C1)),
            Glow(0.05f, 0.6f, 0.7f, Color(0x33E8B48A)),
            Glow(0.6f, 1.0f, 0.8f, Color(0x33F6C9C9)),
        ),
        glassTint = Color(0xCCFFF7F0),
        glassFallback = Color(0xF2FFF8F3),
        glassRim = Color(0xFFC79A86),
        onGlass = Color(0xFF3A2320),
        onGlassSecondary = Color(0xFF664640),
        accent = Color(0xFF9E4F4A),
        onAccent = Color(0xFFFFFFFF),
        badgeBackedUp = Color(0xFF1B6E45),
        badgePhoneOnly = Color(0xFF8A4B00),
        badgeOnDrive = Color(0xFF9E4F4A),
        onBadge = Color(0xFFFFFFFF),
        sampleHues = listOf(Color(0xFFE8B4A0), Color(0xFFD99A8E), Color(0xFFF2D0C2), Color(0xFFC47F72), Color(0xFFE9C3B0)),
    )

    val OceanGlass = GlassTheme(
        id = GlassThemeId.OCEAN_GLASS,
        displayName = "Ocean Glass",
        bestFor = "Backup and storage screens",
        isDark = false,
        wallpaper = listOf(Color(0xFFE6FBF6), Color(0xFFC9F1EC), Color(0xFFB3E8F0)),
        glows = listOf(
            Glow(0.85f, 0.1f, 0.7f, Color(0x404FD1C5)),
            Glow(0.1f, 0.8f, 0.8f, Color(0x330F9AA8)),
        ),
        glassTint = Color(0xCCEEFFFB),
        glassFallback = Color(0xF2F1FFFC),
        glassRim = Color(0xFF5FB3B0),
        onGlass = Color(0xFF05393A),
        onGlassSecondary = Color(0xFF285858),
        accent = Color(0xFF0A6E72),
        onAccent = Color(0xFFFFFFFF),
        badgeBackedUp = Color(0xFF1B6E45),
        badgePhoneOnly = Color(0xFF8A4B00),
        badgeOnDrive = Color(0xFF0A6E72),
        onBadge = Color(0xFFFFFFFF),
        sampleHues = listOf(Color(0xFF4FB7B3), Color(0xFF7FD3CF), Color(0xFF2A8C9A), Color(0xFFA5E4E0), Color(0xFF3F9E8F)),
    )

    /** Clear glass: photos show through the bars. Dark text keeps it readable over any photo (tested). */
    val Translucent = GlassTheme(
        id = GlassThemeId.TRANSLUCENT,
        displayName = "Translucent",
        bestFor = "See-through bars over your photos",
        isDark = false,
        wallpaper = listOf(Color(0xFFEEF3FA), Color(0xFFF8FAFD), Color(0xFFE2EAF4)),
        glows = listOf(Glow(0.85f, 0.1f, 0.6f, Color(0x338EC5FF)), Glow(0.1f, 0.8f, 0.7f, Color(0x26C8B6FF))),
        glassTint = Color(0xA6F4F7FC),
        glassFallback = Color(0xE6F6F8FC),
        glassRim = Color(0xFF9DB2CF),
        onGlass = Color(0xFF0D1726),
        onGlassSecondary = Color(0xFF2E3A4E),
        accent = Color(0xFF1F5FBF),
        onAccent = Color(0xFFFFFFFF),
        badgeBackedUp = Color(0xFF1B6E45),
        badgePhoneOnly = Color(0xFF8A4B00),
        badgeOnDrive = Color(0xFF1F5FBF),
        onBadge = Color(0xFFFFFFFF),
        sampleHues = listOf(Color(0xFF7FB3E0), Color(0xFFE0A57F), Color(0xFF8FCFA8), Color(0xFFC3A6E8), Color(0xFFE8D27F)),
        blurRadius = 14.dp,
        grain = 0f,
    )

    /** Milky frosted glass with a fine grain: the calmest, most private-looking bars. */
    val Frosted = GlassTheme(
        id = GlassThemeId.FROSTED,
        displayName = "Frosted",
        bestFor = "Calm, milky frosted glass",
        isDark = false,
        wallpaper = listOf(Color(0xFFF1F3F6), Color(0xFFFAFBFC), Color(0xFFE6EAEF)),
        glows = listOf(Glow(0.5f, 0.0f, 0.8f, Color(0x40FFFFFF)), Glow(0.9f, 0.9f, 0.6f, Color(0x1A8FA6C0))),
        glassTint = Color(0xE6F7F8FA),
        glassFallback = Color(0xF5F8F9FB),
        glassRim = Color(0xFFB4BCC8),
        onGlass = Color(0xFF15191F),
        onGlassSecondary = Color(0xFF3A414C),
        accent = Color(0xFF2D6A8E),
        onAccent = Color(0xFFFFFFFF),
        badgeBackedUp = Color(0xFF1B6E45),
        badgePhoneOnly = Color(0xFF8A4B00),
        badgeOnDrive = Color(0xFF2D6A8E),
        onBadge = Color(0xFFFFFFFF),
        sampleHues = listOf(Color(0xFF9FB4C7), Color(0xFFC7B59F), Color(0xFFA9C7B0), Color(0xFFB9A9C7), Color(0xFFC7C29F)),
        blurRadius = 40.dp,
        grain = 0.12f,
    )

    /**
     * Adaptive themes, ported from Veyra: smoky dark glass over a wallpaper made from the photos on screen,
     * easing between colours as you scroll. All keep the wallpaper dark enough for light text
     * ([ThemeContrastTest] checks every hue).
     */
    private fun adaptive(id: GlassThemeId, name: String, bestFor: String, style: AdaptiveStyle) = GlassTheme(
        id = id,
        displayName = name,
        bestFor = bestFor,
        isDark = true,
        wallpaper = style.fallback,
        glows = emptyList(),
        glassTint = Color(0xCC101016),
        glassFallback = Color(0xF21B1B24),
        glassRim = Color(0xFF8A8A9E),
        onGlass = Color(0xFFF5F5FA),
        onGlassSecondary = Color(0xFFD4D2E0),
        accent = Color(0xFFFFD27A),
        onAccent = Color(0xFF2A1D00),
        badgeBackedUp = Color(0xFF1B6E45),
        badgePhoneOnly = Color(0xFF8A4B00),
        badgeOnDrive = Color(0xFF3B5BA8),
        onBadge = Color(0xFFFFFFFF),
        // Colourful samples, so the adaptive wallpaper visibly follows the screen.
        sampleHues = listOf(Color(0xFFE07A5F), Color(0xFF3D9970), Color(0xFF6A4C93), Color(0xFFF2B134), Color(0xFF1982C4)),
        adaptive = style,
    )

    val AdaptiveGlow = adaptive(
        GlassThemeId.ADAPTIVE_GLOW, "Adaptive Glow", "Colours follow your photos",
        AdaptiveStyle(listOf(0.34f, 0.24f, 0.14f), 0.35f, 0.75f, glow = 0.35f, scrim = 0.25f,
            fallback = listOf(Color(0xFF3A2A5A), Color(0xFF1E3A4A), Color(0xFF241A30))),
    )

    val AdaptiveMidnight = adaptive(
        GlassThemeId.ADAPTIVE_MIDNIGHT, "Adaptive Midnight", "Darker, follows your photos",
        AdaptiveStyle(listOf(0.20f, 0.13f, 0.08f), 0.25f, 0.55f, glow = 0.22f, scrim = 0.35f,
            fallback = listOf(Color(0xFF1C2333), Color(0xFF14202A), Color(0xFF15131C))),
    )

    val AdaptiveVivid = adaptive(
        GlassThemeId.ADAPTIVE_VIVID, "Adaptive Vivid", "Bolder colours from your photos",
        AdaptiveStyle(listOf(0.46f, 0.32f, 0.18f), 0.5f, 0.9f, glow = 0.45f, scrim = 0.38f,
            fallback = listOf(Color(0xFF6A2C5C), Color(0xFF1F4F6E), Color(0xFF3A1E40))),
    )

    /** Light adaptive: soft pastel colours from the photos on screen under a white veil, with dark text. */
    val AdaptiveLight = GlassTheme(
        id = GlassThemeId.ADAPTIVE_LIGHT,
        displayName = "Adaptive Light",
        bestFor = "Light colours from your photos",
        isDark = false,
        wallpaper = listOf(Color(0xFFF6F1FA), Color(0xFFEFF5FA), Color(0xFFF7F3EC)),
        glows = emptyList(),
        glassTint = Color(0xCCFAF9FF),
        glassFallback = Color(0xF2FBFAFF),
        glassRim = Color(0xFFB0A8C8),
        onGlass = Color(0xFF1C1A28),
        onGlassSecondary = Color(0xFF36314A),
        accent = Color(0xFF4E33A3),
        onAccent = Color(0xFFFFFFFF),
        badgeBackedUp = Color(0xFF1B6E45),
        badgePhoneOnly = Color(0xFF8A4B00),
        badgeOnDrive = Color(0xFF4E33A3),
        onBadge = Color(0xFFFFFFFF),
        sampleHues = listOf(Color(0xFFE07A5F), Color(0xFF3D9970), Color(0xFF6A4C93), Color(0xFFF2B134), Color(0xFF1982C4)),
        adaptive = AdaptiveStyle(
            listOf(0.98f, 0.95f, 0.91f), 0.10f, 0.28f, glow = 0.35f, scrim = 0.25f,
            fallback = listOf(Color(0xFFF6F1FA), Color(0xFFEFF5FA), Color(0xFFF7F3EC)),
            light = true,
        ),
    )

    val fixed: List<GlassTheme> = listOf(AuroraFrost, MidnightPrism, PearlMist, OceanGlass, Translucent, Frosted)
    val adaptiveThemes: List<GlassTheme> = listOf(AdaptiveGlow, AdaptiveMidnight, AdaptiveVivid, AdaptiveLight)
    val all: List<GlassTheme> = fixed + adaptiveThemes
    val default: GlassTheme = AuroraFrost

    /** Unknown or missing IDs (an older or newer saved setting) fall back to the default. */
    fun byId(id: String?): GlassTheme = all.firstOrNull { it.id.name == id } ?: default
}
