package com.deepak.photovault.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Stable IDs: saved in settings, so never rename them. PEARL_MIST (Warm Light) and CRYSTAL_GLASS_LIGHT (Clear Light)
 * were removed on 18 Nov; a phone that had one saved falls back to the default.
 */
enum class GlassThemeId {
    ADAPTIVE_MIDNIGHT, ADAPTIVE_VIVID,
    AURORA_GLASS_LIGHT,
    LIQUID_DAY,
    /** The night versions of Luminous and Liquid, and the day versions of Photo and Vivid (18 Nov). */
    LUMINOUS_DARK, LIQUID_DARK, PHOTO_DAY, VIVID_DAY,
}

/**
 * The calmer glass (18 Nov, Deepak: "might ask you to revert"): clean thin edges instead of bright glass rims, softer
 * shadows and depth, no sheen on the wallpaper, and bars in the day themes that are white enough for dark text to read
 * over any photo. Set to false to bring back the earlier glass exactly.
 */
object GlassFinish {
    const val CALM: Boolean = true
}

/**
 * One theme in its day and night versions (18 Nov, Deepak: "one theme can be switched to night and day"). Settings
 * picks the theme, then As the phone, Day or Night.
 */
@Immutable
data class ThemeFamily(val key: String, val name: String, val about: String, val day: GlassTheme, val night: GlassTheme) {
    fun pick(dark: Boolean): GlassTheme = if (dark) night else day
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
    /** Lens glass (looks like thick real glass, Android 13+): used with the Blur slider at 0 (29 Oct). */
    val lens: Boolean = true,
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
    /** iOS-style milky glass (light themes): almost no tint, just the blurred picture behind with a light edge, like iOS. Over very bright photos white text can be hard to read, so contrast is checked on the wallpaper only. */
    val milky: Boolean = false,
) {
    /** The pale-background version: white text on a pale page can only reach 3:1 on its glass cards, so tests allow that for it. */
    val isPaleVariant: Boolean get() = !isDark
}

/** What a selected pill, button or tick is filled with: the frosted film where the theme has one, else the accent. */
val GlassTheme.accentSurface: Color get() = accentFill ?: accent

object GlassThemes {
    private val White = Color(0xFFFFFFFF)
    private val lightIds = setOf(GlassThemeId.AURORA_GLASS_LIGHT, GlassThemeId.LIQUID_DAY)

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
        milky: Boolean = id in lightIds,
        /** The night glass's smoke and its solid version (18 Nov): a hint of the theme's own colour, so each looks its own. */
        smoke: Long = 0x0C0D10,
        solid: Long = 0x1A1C22,
    ) = GlassTheme(
        id = id,
        displayName = name,
        bestFor = bestFor,
        isDark = true,
        wallpaper = wallpaper.map { Color(it) },
        glows = glows,
        glassTint = if (milky) Color(0x1F8A9098) else Color(0x73000000 or smoke),
        glassFallback = if (milky) Color(0xE6505660) else Color(0xE6000000 or solid),
        glassRim = Color(rim),
        onGlass = White,
        onGlassSecondary = Color(secondary),
        accent = White,
        onAccent = White,
        accentFill = Color(if (milky) 0x38FFFFFF else 0x33FFFFFF),
        panelFill = Color(if (milky) 0x1FFFFFFF else 0x1AFFFFFF),
        badgeBackedUp = Color(0xFF1B6E45),
        badgePhoneOnly = Color(0xFF8A4B00),
        badgeOnDrive = Color(onDrive),
        onBadge = White,
        sampleHues = hues.map { Color(it) },
        adaptive = adaptive,
        blurRadius = blur,
        milky = milky,
        grain = 0f,
        rimStrength = 1.1f,
    )

    /**
     * The pale-background version of a light theme (experiment, 29 Oct, shown with a switch in About): the old pale
     * wallpaper with dark text, and clear glass with no colour behind it.
     */
    private fun pale(
        deep: GlassTheme,
        wallpaper: List<Long>,
        glows: List<Glow>,
        ink: Long,
        adaptive: AdaptiveStyle? = null,
        /** Calm glass (18 Nov): each day theme's bars carry a hint of its own colour, so the four don't look alike. */
        frost: Long = 0xF7F8FA,
    ): GlassTheme =
        deep.copy(
            isDark = false,
            wallpaper = wallpaper.map { Color(it) },
            glows = glows,
            onWallpaper = Color(ink),
            // Pale look: clear, almost colourless glass over the light page, with dark text and icons, like iOS on a light wallpaper.
            onGlass = Color(ink),
            onGlassSecondary = Color(ink),
            accent = Color(ink),
            onAccent = Color(ink),
            // Calm glass (18 Nov): a whiter frost, so the dark text reads over dark photos too; the chosen item a soft
            // shade of the text colour, since a white film would vanish on it.
            glassTint = if (GlassFinish.CALM) Color(0xB8000000 or frost) else Color(0x24FFFFFF),
            glassFallback = if (GlassFinish.CALM) Color(0xDB000000 or frost) else Color(0xDBF4F6FA),
            panelFill = Color(0x61FFFFFF),
            accentFill = if (GlassFinish.CALM) Color(ink).copy(alpha = 0.10f) else Color(0x80FFFFFF),
            milky = false,
            adaptive = adaptive,
            pale = null,
        )

    // ---- Light themes (deep backgrounds by default; pale ones with the experiment switch) ----

    val AuroraGlassLight: GlassTheme = glass(
        GlassThemeId.AURORA_GLASS_LIGHT, "Luminous Day", "Lilac with a mint light",
        wallpaper = listOf(0xFF182F66, 0xFF243F7C, 0xFF2E4A90),
        glows = listOf(Glow(0.9f, 0.05f, 0.7f, Color(0x805AC8FA)), Glow(0.05f, 0.7f, 0.75f, Color(0x667C78FF)), Glow(0.5f, 0.35f, 0.4f, Color(0x33FFFFFF))),
        rim = 0xFFA8CCFF, onDrive = 0xFF0A4FA8,
        hues = listOf(0xFF79B7D8, 0xFF9675B6, 0xFF5AA0E0, 0xFFB4C8F5, 0xFF7C78FF),
        blur = 14.dp,
    ).let { deep ->
        // 18 Nov (Deepak: the four looked alike): Luminous is the aurora, lilac with a mint light by day.
        deep.copy(pale = pale(deep, listOf(0xFFEDE6FF, 0xFFF6F1FF, 0xFFE3F5F1),
            listOf(Glow(0.9f, 0.05f, 0.7f, Color(0x598B6CFF)), Glow(0.05f, 0.75f, 0.75f, Color(0x4D3FD6B0)), Glow(0.5f, 0.35f, 0.4f, Color(0x80FFFFFF))),
            ink = 0xFF000000, frost = 0xF7F3FF))
    }

    val LiquidDay: GlassTheme = glass(
        GlassThemeId.LIQUID_DAY, "Liquid Day", "Clear glass over a sky-blue page",
        wallpaper = listOf(0xFF102F58, 0xFF1A4476, 0xFF225384),
        glows = listOf(Glow(0.85f, 0.08f, 0.8f, Color(0x80FFD9A0)), Glow(0.1f, 0.6f, 0.8f, Color(0x6659C8FF)), Glow(0.7f, 0.95f, 0.7f, Color(0x55A98CFF))),
        rim = 0xFFFFFFFF, onDrive = 0xFF3F5A8A,
        hues = listOf(0xFF4F83BF, 0xFF6FA3D9, 0xFF3A6EA5, 0xFF8CB4E0, 0xFF5A8FCF),
    ).let { deep ->
        // 18 Nov: Liquid is water and sky, a clear sky blue by day with a warm sun in the corner.
        deep.copy(pale = pale(deep, listOf(0xFFD9ECFA, 0xFFE9F4FD, 0xFFCFE4F6),
            listOf(Glow(0.85f, 0.08f, 0.8f, Color(0x66FFD9A0)), Glow(0.1f, 0.75f, 0.8f, Color(0x4D59C8FF))),
            ink = 0xFF14171C, frost = 0xF1F7FD))
    }

    // ---- Night versions of Luminous and Liquid (18 Nov): the same colours after dark, white text ----

    val LuminousNight: GlassTheme = glass(
        GlassThemeId.LUMINOUS_DARK, "Luminous Night", "An aurora: deep violet with green and pink light",
        wallpaper = listOf(0xFF140B30, 0xFF1C1144, 0xFF0B1B34),
        glows = listOf(Glow(0.2f, 0.12f, 0.75f, Color(0x4D3EE6C1)), Glow(0.85f, 0.55f, 0.8f, Color(0x598B5CF6)), Glow(0.5f, 1.0f, 0.6f, Color(0x33F472B6))),
        rim = 0xFFA8CCFF, onDrive = 0xFF0A4FA8,
        hues = listOf(0xFF79B7D8, 0xFF9675B6, 0xFF5AA0E0, 0xFFB4C8F5, 0xFF7C78FF),
        milky = false,
        smoke = 0x120A26, solid = 0x1F1638,
    )

    val LiquidNight: GlassTheme = glass(
        GlassThemeId.LIQUID_DARK, "Liquid Night", "Clear glass over a deep sea, a moon above",
        wallpaper = listOf(0xFF03192B, 0xFF062A42, 0xFF0A3A57),
        glows = listOf(Glow(0.85f, 0.06f, 0.55f, Color(0x29FFFFFF)), Glow(0.1f, 0.65f, 0.8f, Color(0x4D2EC4D9)), Glow(0.75f, 0.98f, 0.7f, Color(0x332E8BD9))),
        rim = 0xFFFFFFFF, onDrive = 0xFF3F5A8A,
        hues = listOf(0xFF4F83BF, 0xFF6FA3D9, 0xFF3A6EA5, 0xFF8CB4E0, 0xFF5A8FCF),
        milky = false,
        smoke = 0x05161F, solid = 0x0F2433,
    )

    // ---- Dark themes ----

    /** Night sky: nearly black, with one soft light of your photos' colour at the top. */
    val AdaptiveMidnight: GlassTheme = glass(
        GlassThemeId.ADAPTIVE_MIDNIGHT, "Photo Night", "Nearly black, one soft light from your photos",
        // 18 Nov: warm graphite rather than blue-black, so it isn't mistaken for Liquid or Luminous at night.
        wallpaper = listOf(0xFF2E2A26, 0xFF171512, 0xFF0A0A09),
        glows = emptyList(),
        rim = 0xFF6E8AA0, onDrive = 0xFF1F5F86,
        hues = listOf(0xFFE07A5F, 0xFF3D9970, 0xFF6A4C93, 0xFFF2B134, 0xFF1982C4),
        smoke = 0x0E0D0B, solid = 0x1E1C19,
        adaptive = AdaptiveStyle(
            listOf(0.30f, 0.12f, 0.04f), 0.25f, 0.55f, glow = 0.7f, scrim = 0.30f,
            fallback = listOf(Color(0xFF2E2A26), Color(0xFF171512), Color(0xFF0A0A09)),
            layout = WallpaperLayout.NIGHT,
        ),
    )

    /** Bold sweep: fully saturated colours from your photos corner to corner. */
    val AdaptiveVivid: GlassTheme = glass(
        GlassThemeId.ADAPTIVE_VIVID, "Vivid Night", "Bold, saturated colours from your photos",
        // 18 Nov: magenta to violet to teal until your photos colour it, unlike the others' blues.
        wallpaper = listOf(0xFF5E1250, 0xFF3A1F78, 0xFF0E4A63),
        glows = emptyList(),
        rim = 0xFF8AB6C8, onDrive = 0xFF1D5F86,
        hues = listOf(0xFFE07A5F, 0xFF3D9970, 0xFF6A4C93, 0xFFF2B134, 0xFF1982C4),
        smoke = 0x140A1C, solid = 0x22172B,
        adaptive = AdaptiveStyle(
            listOf(0.46f, 0.38f, 0.30f), 0.85f, 1.0f, glow = 0.5f, scrim = 0.48f,
            fallback = listOf(Color(0xFF5E1250), Color(0xFF3A1F78), Color(0xFF0E4A63)),
            layout = WallpaperLayout.DIAGONAL,
        ),
    )

    // ---- Day versions of Photo and Vivid (18 Nov): the same photo colours on a light page, dark text ----

    /** Nearly white, with one soft light of your photos' colour at the top. */
    val PhotoDay: GlassTheme = pale(
        AdaptiveMidnight,
        listOf(0xFFFAF7F2, 0xFFF5F0E8, 0xFFEFE8DD),
        emptyList(),
        ink = 0xFF14171C,
        // Warm paper with one soft light from your photos (18 Nov).
        frost = 0xFBF8F3,
        adaptive = AdaptiveStyle(
            listOf(0.93f, 0.95f, 0.97f), 0.10f, 0.32f, glow = 0.75f, scrim = 0.25f,
            fallback = listOf(Color(0xFFF1E6D6), Color(0xFFF5F0E8), Color(0xFFFAF7F2)),
            light = true,
            layout = WallpaperLayout.NIGHT,
        ),
    ).copy(id = GlassThemeId.PHOTO_DAY, displayName = "Photo Day", bestFor = "Nearly white, one soft light from your photos")

    /** Pastel colours from your photos corner to corner. */
    val VividDay: GlassTheme = pale(
        AdaptiveVivid,
        listOf(0xFFF7DCEA, 0xFFE6DDF8, 0xFFD8F2EC),
        emptyList(),
        ink = 0xFF14171C,
        // Bolder pastels, pink to lilac to mint before your photos colour it (18 Nov); plain white frost.
        frost = 0xFFFFFF,
        adaptive = AdaptiveStyle(
            listOf(0.91f, 0.88f, 0.93f), 0.36f, 0.55f, glow = 0.5f, scrim = 0.22f,
            fallback = listOf(Color(0xFFF7DCEA), Color(0xFFE6DDF8), Color(0xFFD8F2EC)),
            light = true,
            layout = WallpaperLayout.DIAGONAL,
        ),
    ).copy(id = GlassThemeId.VIVID_DAY, displayName = "Vivid Day", bestFor = "Pastel colours from your photos")

    /** The four themes, each with its day and night (18 Nov). Liquid first: it is the one everyone starts on. */
    val families: List<ThemeFamily> = listOf(
        ThemeFamily("LIQUID", "Liquid", "Sky blue by day, a deep sea at night", LiquidDay.pale!!, LiquidNight),
        ThemeFamily("LUMINOUS", "Luminous", "An aurora: lilac and mint, violet and green", AuroraGlassLight.pale!!, LuminousNight),
        ThemeFamily("PHOTO", "Photo", "Warm paper or graphite, one soft light from your photos", PhotoDay, AdaptiveMidnight),
        ThemeFamily("VIVID", "Vivid", "Bold colours from your photos", VividDay, AdaptiveVivid),
    )

    /** The theme a saved id belongs to, whether it was saved as the day or the night version; else Liquid. */
    fun familyOf(id: String?): ThemeFamily = families.firstOrNull { it.day.id.name == id || it.night.id.name == id } ?: families.first()

    /**
     * Every day version (light page, dark text) and every night version (deep page, white text), in [families] order.
     */
    val lightThemes: List<GlassTheme> = families.map { it.day }
    val darkThemes: List<GlassTheme> = families.map { it.night }
    val fixed: List<GlassTheme> = lightThemes.filter { it.adaptive == null } + darkThemes.filter { it.adaptive == null }
    val adaptiveThemes: List<GlassTheme> = lightThemes.filter { it.adaptive != null } + darkThemes.filter { it.adaptive != null }
    val all: List<GlassTheme> = lightThemes + darkThemes

    /** Liquid Day (light page) for everyone who hasn't picked a theme. */
    val default: GlassTheme = LiquidDay.pale!!

    /** Unknown or missing IDs (an older or newer saved setting, or a removed theme) fall back to the default. */
    fun byId(id: String?): GlassTheme = all.firstOrNull { it.id.name == id } ?: default
}
