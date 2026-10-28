package com.deepak.photovault.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Stable IDs: saved in settings, so never rename them. */
enum class GlassThemeId {
    PEARL_MIST, OCEAN_GLASS,
    ADAPTIVE_MIDNIGHT, ADAPTIVE_VIVID, ADAPTIVE_LIGHT,
    AURORA_GLASS_LIGHT, AURORA_GLASS_DARK, CRYSTAL_GLASS_LIGHT,
    /** Liquid Night and Liquid Day: the clear iOS 26 look (28 Oct). */
    LIQUID_NIGHT, LIQUID_DAY,
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
    /** Lens glass: no theme uses it since 29 Oct (every theme has the one glass look). The code stays for now. */
    val lens: Boolean = false,
    /** The bright edge of glass: thickness and strength (1 = the usual). Glassmorphism themes use a fuller one. */
    val rimWidth: Dp = 1.dp,
    val rimStrength: Float = 1f,
    /**
     * Frosted accent (iOS style): where other themes fill a selected pill, button or tick with the solid accent colour,
     * this theme lays a see-through white film instead (and [accent] itself is white for icons and highlights).
     */
    val accentFill: Color? = null,
    /** Frosted panels, cards and album folders: a see-through film instead of the near-solid [glassFallback]. */
    val panelFill: Color? = null,
    /** Text and icons that sit straight on the wallpaper, not on glass. White, except in the pale versions of the light themes. */
    val onWallpaper: Color = onGlass,
    /** The pale-background version of a light theme (experiment, 29 Oct): same id, pale wallpaper, dark text on it, white text on glass. */
    val pale: GlassTheme? = null,
) {
    /** The pale-background version: white text on a pale page can only reach 3:1 on its glass cards, so tests allow that for it. */
    val isPaleVariant: Boolean get() = onWallpaper != onGlass
}

/** What a selected pill, button or tick is filled with: the frosted film where the theme has one, else the accent. */
val GlassTheme.accentSurface: Color get() = accentFill ?: accent

object GlassThemes {
    private val White = Color(0xFFFFFFFF)

    /**
     * The one glass look (29 Oct), shared by every theme: clear see-through bars with a little frost and depth, frosted
     * panels, frosted selection (the accent is white, so selected items are a frosted film, not a colour), white text
     * and icons. Only the colours differ. The tint is a neutral smoke at 0.45 over blurred photos; "Turn off glass effect" makes glass solid, and the frosted layer on cards and album folders (all neutral, so every theme has the same see-through glass and only the wallpaper differs).
     * ThemeContrastTest checks white text on all of it, over black and over a very bright photo.
     */
    private fun glass(
        id: GlassThemeId,
        name: String,
        bestFor: String,
        wallpaper: List<Long>,
        glows: List<Glow>,
        rim: Long,
        onDrive: Long,
        hues: List<Long>,
        secondary: Long = 0xFFFFFFFF,
        adaptive: AdaptiveStyle? = null,
        blur: Dp = 12.dp,
    ) = GlassTheme(
        id = id,
        displayName = name,
        bestFor = bestFor,
        isDark = true,
        wallpaper = wallpaper.map { Color(it) },
        glows = glows,
        glassTint = Color(0x730C0D10),
        glassFallback = Color(0xE61A1C22),
        glassRim = Color(rim),
        onGlass = White,
        onGlassSecondary = Color(secondary),
        accent = White,
        onAccent = White,
        accentFill = Color(0x33FFFFFF),
        panelFill = Color(0x1AFFFFFF),
        badgeBackedUp = Color(0xFF1B6E45),
        badgePhoneOnly = Color(0xFF8A4B00),
        badgeOnDrive = Color(onDrive),
        onBadge = White,
        sampleHues = hues.map { Color(it) },
        adaptive = adaptive,
        blurRadius = blur,
        grain = 0f,
        rimStrength = 1.1f,
    )

    /**
     * The pale-background version of a light theme (experiment, 29 Oct, shown with a switch in About): the old pale
     * wallpaper, dark text where text sits straight on it, and the same white text on smoky glass.
     */
    private fun pale(deep: GlassTheme, wallpaper: List<Long>, glows: List<Glow>, ink: Long, adaptive: AdaptiveStyle? = null): GlassTheme =
        deep.copy(
            isDark = false,
            wallpaper = wallpaper.map { Color(it) },
            glows = glows,
            onWallpaper = Color(ink),
            glassTint = deep.glassTint.copy(alpha = 0.55f),
            glassFallback = deep.glassFallback.copy(alpha = 0.92f),
            panelFill = Color(0x99101216),
            adaptive = adaptive,
            pale = null,
        )

    // ---- Light themes (deep backgrounds by default; pale ones with the experiment switch) ----

    val PearlMist: GlassTheme = glass(
        GlassThemeId.PEARL_MIST, "Warm Light", "Warm peach glass, white text",
        wallpaper = listOf(0xFF4E2824, 0xFF6A3830, 0xFF7C4A3C),
        glows = listOf(Glow(0.9f, 0.05f, 0.7f, Color(0x59FF9A7A)), Glow(0.05f, 0.6f, 0.7f, Color(0x40FFC48A)), Glow(0.6f, 1.0f, 0.8f, Color(0x40F4B9C1))),
        rim = 0xFFFFD9C8, onDrive = 0xFF9E4F4A,
        hues = listOf(0xFFE8B4A0, 0xFFD99A8E, 0xFFF2D0C2, 0xFFC47F72, 0xFFE9C3B0),
    ).let { deep ->
        deep.copy(pale = pale(deep, listOf(0xFFFFF8F1, 0xFFFBEDE6, 0xFFF4E0D6),
            listOf(Glow(0.9f, 0.05f, 0.7f, Color(0x40F4B9C1)), Glow(0.05f, 0.6f, 0.7f, Color(0x33E8B48A)), Glow(0.6f, 1.0f, 0.8f, Color(0x33F6C9C9))),
            ink = 0xFF3A2320))
    }

    val OceanGlass: GlassTheme = glass(
        GlassThemeId.OCEAN_GLASS, "Teal Light", "Cool teal glass, white text",
        wallpaper = listOf(0xFF083338, 0xFF0E4C53, 0xFF126068),
        glows = listOf(Glow(0.85f, 0.1f, 0.7f, Color(0x594FD1C5)), Glow(0.1f, 0.8f, 0.8f, Color(0x4D0F9AA8))),
        rim = 0xFF9AE8E4, onDrive = 0xFF0A6E72,
        hues = listOf(0xFF4FB7B3, 0xFF7FD3CF, 0xFF2A8C9A, 0xFFA5E4E0, 0xFF3F9E8F),
    ).let { deep ->
        deep.copy(pale = pale(deep, listOf(0xFFE6FBF6, 0xFFC9F1EC, 0xFFB3E8F0),
            listOf(Glow(0.85f, 0.1f, 0.7f, Color(0x404FD1C5)), Glow(0.1f, 0.8f, 0.8f, Color(0x330F9AA8))),
            ink = 0xFF05393A))
    }

    val AuroraGlassLight: GlassTheme = glass(
        GlassThemeId.AURORA_GLASS_LIGHT, "Luminous Light", "Bright blue, cyan and violet glow",
        wallpaper = listOf(0xFF182F66, 0xFF243F7C, 0xFF2E4A90),
        glows = listOf(Glow(0.9f, 0.05f, 0.7f, Color(0x805AC8FA)), Glow(0.05f, 0.7f, 0.75f, Color(0x667C78FF)), Glow(0.5f, 0.35f, 0.4f, Color(0x33FFFFFF))),
        rim = 0xFFA8CCFF, onDrive = 0xFF0A4FA8,
        hues = listOf(0xFF79B7D8, 0xFF9675B6, 0xFF5AA0E0, 0xFFB4C8F5, 0xFF7C78FF),
        blur = 14.dp,
    ).let { deep ->
        deep.copy(pale = pale(deep, listOf(0xFFE3F1FF, 0xFFF2F7FF, 0xFFE4E0FF),
            listOf(Glow(0.9f, 0.05f, 0.7f, Color(0x4D5AC8FA)), Glow(0.05f, 0.7f, 0.75f, Color(0x407C78FF)), Glow(0.5f, 0.35f, 0.4f, Color(0x80FFFFFF))),
            ink = 0xFF061F40))
    }

    val CrystalGlassLight: GlassTheme = glass(
        GlassThemeId.CRYSTAL_GLASS_LIGHT, "Clear Light", "Neutral grey-blue glass, white text",
        wallpaper = listOf(0xFF2B3642, 0xFF394655, 0xFF465566),
        glows = listOf(Glow(0.5f, 0.2f, 0.7f, Color(0x33FFFFFF))),
        rim = 0xFFD5DEE8, onDrive = 0xFF4A5568,
        hues = listOf(0xFFB8C2CC, 0xFF9AA5B1, 0xFFD0D6DC, 0xFF7D8894, 0xFFC4CCD4),
    ).let { deep ->
        deep.copy(pale = pale(deep, listOf(0xFFF2F4F6, 0xFFE9EDF1, 0xFFF8F9FB),
            listOf(Glow(0.5f, 0.2f, 0.7f, Color(0x80FFFFFF))),
            ink = 0xFF1C2733))
    }

    val LiquidDay: GlassTheme = glass(
        GlassThemeId.LIQUID_DAY, "Liquid Day", "Clear glass over a bright blue sky, white text",
        wallpaper = listOf(0xFF102F58, 0xFF1A4476, 0xFF225384),
        glows = listOf(Glow(0.85f, 0.08f, 0.8f, Color(0x80FFD9A0)), Glow(0.1f, 0.6f, 0.8f, Color(0x6659C8FF)), Glow(0.7f, 0.95f, 0.7f, Color(0x55A98CFF))),
        rim = 0xFFFFFFFF, onDrive = 0xFF3F5A8A,
        hues = listOf(0xFF4F83BF, 0xFF6FA3D9, 0xFF3A6EA5, 0xFF8CB4E0, 0xFF5A8FCF),
    ).let { deep ->
        deep.copy(pale = pale(deep, listOf(0xFFF7F8FB, 0xFFEEF1F7, 0xFFE6EBF4),
            listOf(Glow(0.85f, 0.08f, 0.8f, Color(0x33FFD9A0)), Glow(0.1f, 0.6f, 0.8f, Color(0x2659C8FF))),
            ink = 0xFF14171C))
    }

    val AdaptiveLight: GlassTheme = glass(
        GlassThemeId.ADAPTIVE_LIGHT, "Photo Light", "Soft bands of colour from your photos, white text",
        wallpaper = listOf(0xFF3B4A6E, 0xFF2E5A74, 0xFF4A3E6A),
        glows = emptyList(),
        rim = 0xFFD8D0F0, onDrive = 0xFF4E33A3,
        hues = listOf(0xFFE07A5F, 0xFF3D9970, 0xFF6A4C93, 0xFFF2B134, 0xFF1982C4),
        adaptive = AdaptiveStyle(
            listOf(0.40f, 0.34f, 0.28f), 0.30f, 0.70f, glow = 0.5f, scrim = 0.25f,
            fallback = listOf(Color(0xFF3B4A6E), Color(0xFF2E5A74), Color(0xFF4A3E6A)),
            layout = WallpaperLayout.HORIZON,
        ),
    ).let { deep ->
        deep.copy(pale = pale(deep, listOf(0xFFF6F1FA, 0xFFEFF5FA, 0xFFF7F3EC), emptyList(),
            ink = 0xFF1C1A28,
            adaptive = AdaptiveStyle(
                listOf(0.98f, 0.95f, 0.91f), 0.10f, 0.28f, glow = 0.35f, scrim = 0.25f,
                fallback = listOf(Color(0xFFF6F1FA), Color(0xFFEFF5FA), Color(0xFFF7F3EC)),
                light = true,
                layout = WallpaperLayout.HORIZON,
            )))
    }

    // ---- Dark themes ----

    val AuroraGlassDark: GlassTheme = glass(
        GlassThemeId.AURORA_GLASS_DARK, "Luminous Night", "Glowing glass on a deep blue night",
        wallpaper = listOf(0xFF060C24, 0xFF0C1D4A, 0xFF1B1250),
        glows = listOf(Glow(0.9f, 0.05f, 0.75f, Color(0x5970E7FF)), Glow(0.05f, 0.8f, 0.8f, Color(0x597C78FF))),
        rim = 0xFF70E7FF, onDrive = 0xFF3A4FC4,
        hues = listOf(0xFF193A7B, 0xFF3B2F9A, 0xFF1268D9, 0xFF2A6FB5, 0xFF5A4FD0),
        blur = 14.dp,
    )

    val LiquidNight: GlassTheme = glass(
        GlassThemeId.LIQUID_NIGHT, "Liquid Night", "Clear glass with a little frost, like iOS",
        wallpaper = listOf(0xFF0B0C10, 0xFF14161C, 0xFF1A1C24),
        glows = listOf(Glow(0.5f, 0.15f, 0.8f, Color(0x2640608F)), Glow(0.15f, 0.9f, 0.7f, Color(0x1F8F5A40))),
        rim = 0xFFFFFFFF, onDrive = 0xFF3F5A8A,
        hues = listOf(0xFF3A3F45, 0xFF4F565D, 0xFF2A2E33, 0xFF62696F, 0xFF454B52),
    )

    /** Night sky: nearly black, with one soft light of your photos' colour at the top. */
    val AdaptiveMidnight: GlassTheme = glass(
        GlassThemeId.ADAPTIVE_MIDNIGHT, "Photo Night", "Nearly black, one soft light from your photos",
        wallpaper = listOf(0xFF2A3550, 0xFF141A26, 0xFF0A0A10),
        glows = emptyList(),
        rim = 0xFF6E8AA0, onDrive = 0xFF1F5F86,
        hues = listOf(0xFFE07A5F, 0xFF3D9970, 0xFF6A4C93, 0xFFF2B134, 0xFF1982C4),
        adaptive = AdaptiveStyle(
            listOf(0.30f, 0.12f, 0.04f), 0.25f, 0.55f, glow = 0.7f, scrim = 0.30f,
            fallback = listOf(Color(0xFF2A3550), Color(0xFF141A26), Color(0xFF0A0A10)),
            layout = WallpaperLayout.NIGHT,
        ),
    )

    /** Bold sweep: fully saturated colours from your photos corner to corner. */
    val AdaptiveVivid: GlassTheme = glass(
        GlassThemeId.ADAPTIVE_VIVID, "Vivid Night", "Bold, saturated colours from your photos",
        wallpaper = listOf(0xFF7A2C6C, 0xFF1F5F86, 0xFF4A1E50),
        glows = emptyList(),
        rim = 0xFFB08AA6, onDrive = 0xFF8A2A6E,
        hues = listOf(0xFFE07A5F, 0xFF3D9970, 0xFF6A4C93, 0xFFF2B134, 0xFF1982C4),
        adaptive = AdaptiveStyle(
            listOf(0.46f, 0.38f, 0.30f), 0.85f, 1.0f, glow = 0.5f, scrim = 0.48f,
            fallback = listOf(Color(0xFF7A2C6C), Color(0xFF1F5F86), Color(0xFF4A1E50)),
            layout = WallpaperLayout.DIAGONAL,
        ),
    )

    /** Settings sections. Light themes have deep backgrounds by default (white text), pale ones with the About experiment. */
    val lightThemes: List<GlassTheme> = listOf(PearlMist, OceanGlass, AuroraGlassLight, CrystalGlassLight, LiquidDay, AdaptiveLight)
    val darkThemes: List<GlassTheme> = listOf(AuroraGlassDark, LiquidNight, AdaptiveMidnight, AdaptiveVivid)
    val fixed: List<GlassTheme> = lightThemes.filter { it.adaptive == null } + darkThemes.filter { it.adaptive == null }
    val adaptiveThemes: List<GlassTheme> = lightThemes.filter { it.adaptive != null } + darkThemes.filter { it.adaptive != null }
    val all: List<GlassTheme> = lightThemes + darkThemes

    /** Every theme and every pale version, for tests. */
    val allVariants: List<GlassTheme> = all + all.mapNotNull { it.pale }

    /** Photo Light for everyone who hasn't picked a theme. */
    val default: GlassTheme = AdaptiveLight

    /** Unknown or missing IDs (an older or newer saved setting, or a removed theme) fall back to the default. */
    fun byId(id: String?): GlassTheme = all.firstOrNull { it.id.name == id } ?: default

    /** The theme as shown: its pale version when the experiment switch is on and the theme has one. */
    fun shown(theme: GlassTheme, palePreferred: Boolean): GlassTheme = if (palePreferred) theme.pale ?: theme else theme
}
