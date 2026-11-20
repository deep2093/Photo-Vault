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
    /** Pearl (20 Nov): Skywing's Liquid Day brought over and improved, with a night version. */
    PEARL_DAY, PEARL_NIGHT,
    /** Liquid Glass (20 Nov): Deepak's own iOS-style theme, Light and Dark. */
    LIQUID_GLASS_LIGHT, LIQUID_GLASS_DARK,
    /** Clear Glass (21 Nov): a copy of iOS 26's clear Liquid Glass, Light and Dark. */
    CLEAR_GLASS_LIGHT, CLEAR_GLASS_DARK,
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
    /**
     * A faint band of colour across the page, like the sheen on a pearl (Pearl, 20 Nov): three colours, from one side
     * of the band to the other. Null for the other themes, whose pages stay calm.
     */
    val lustre: List<Color>? = null,
    /** Liquid Glass and Clear Glass: how the lens keeps words readable without frost ([GlassInk]). Null elsewhere. */
    val glassInk: GlassInk? = null,
    /** Clear Glass: the blue of main buttons and chosen chips (iOS's prominent glass), with white words. Null elsewhere. */
    val prominent: Color? = null,
) {
    /** The pale-background version: white text on a pale page can only reach 3:1 on its glass cards, so tests allow that for it. */
    val isPaleVariant: Boolean get() = !isDark

    /** Clear lens glass that never blurs: Liquid Glass and Clear Glass (its iOS copy) share all of it. */
    val isLiquidGlass: Boolean
        get() = id == GlassThemeId.LIQUID_GLASS_LIGHT || id == GlassThemeId.LIQUID_GLASS_DARK || isClearGlass

    /**
     * Clear Glass (21 Nov): the copy of iOS. On top of Liquid Glass: bars that turn dark over dark photos and light over
     * light ones, light that moves as the phone tilts, Clear or Tinted, the scroll edge fade, iOS colours and blue.
     */
    val isClearGlass: Boolean
        get() = id == GlassThemeId.CLEAR_GLASS_LIGHT || id == GlassThemeId.CLEAR_GLASS_DARK
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
        glassTint = if (milky) Color(0x1F8A9098) else Color(0x73000000L or smoke),
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
        GlassThemeId.AURORA_GLASS_LIGHT, "Aurora Day", "Lilac with a mint light",
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
        GlassThemeId.LIQUID_DAY, "Ocean Day", "Clear glass over a sky-blue page",
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
        GlassThemeId.LUMINOUS_DARK, "Aurora Night", "Northern lights: deep violet with green and pink light",
        wallpaper = listOf(0xFF140B30, 0xFF1C1144, 0xFF0B1B34),
        glows = listOf(Glow(0.2f, 0.12f, 0.75f, Color(0x4D3EE6C1)), Glow(0.85f, 0.55f, 0.8f, Color(0x598B5CF6)), Glow(0.5f, 1.0f, 0.6f, Color(0x33F472B6))),
        rim = 0xFFA8CCFF, onDrive = 0xFF0A4FA8,
        hues = listOf(0xFF79B7D8, 0xFF9675B6, 0xFF5AA0E0, 0xFFB4C8F5, 0xFF7C78FF),
        milky = false,
        smoke = 0x120A26, solid = 0x1F1638,
    )

    val LiquidNight: GlassTheme = glass(
        GlassThemeId.LIQUID_DARK, "Ocean Night", "Clear glass over a deep sea, a moon above",
        wallpaper = listOf(0xFF03192B, 0xFF062A42, 0xFF0A3A57),
        glows = listOf(Glow(0.85f, 0.06f, 0.55f, Color(0x29FFFFFF)), Glow(0.1f, 0.65f, 0.8f, Color(0x4D2EC4D9)), Glow(0.75f, 0.98f, 0.7f, Color(0x332E8BD9))),
        rim = 0xFFFFFFFF, onDrive = 0xFF3F5A8A,
        hues = listOf(0xFF4F83BF, 0xFF6FA3D9, 0xFF3A6EA5, 0xFF8CB4E0, 0xFF5A8FCF),
        milky = false,
        smoke = 0x05161F, solid = 0x0F2433,
    )

    // ---- Pearl (20 Nov, Deepak: "Liquid Day from Skywing's latest build; improve on it, rename it, add a night version") ----

    /**
     * Pearl Day: Skywing's Liquid Day, improved. The same nearly white, cool page and clear glass, with a pearl's soft play
     * of colour (a warm light at the top, a hint of aqua and of rose) and a faint lustre across it. Its bars use the calm
     * frost, so dark words stay readable over dark photos too (Skywing's almost clear bars let them vanish there), and the
     * chosen item is a soft shade of the text colour instead of a white film that disappeared on the white page.
     */
    val PearlDay: GlassTheme = glass(
        GlassThemeId.PEARL_DAY, "Pearl Day", "Pearl white with clear glass",
        // The deep page under the pale one is never shown; the pale version below is the theme.
        wallpaper = listOf(0xFF2B3642, 0xFF394655, 0xFF465566),
        glows = emptyList(),
        rim = 0xFFD5DEE8, onDrive = 0xFF3F5A8A,
        hues = listOf(0xFFB9C4D6, 0xFFD8C8DA, 0xFFA9C9D4, 0xFFE3D6C8, 0xFF9DB0C8),
        milky = false,
    ).let { deep ->
        // 20 Nov (Deepak: "Pearl Day doesn't have any depth; give it some, like the night version"): light from above,
        // a deeper cool pearl lower down with a little shade in its lower corners, and stronger lights of colour, so
        // cards and bars stand off the page instead of melting into it.
        pale(
            deep,
            listOf(0xFFF6F7FB, 0xFFE9EBF3, 0xFFD9DEEA),
            listOf(
                Glow(0.85f, 0.06f, 0.75f, Color(0x4DFFD9A0)),
                Glow(0.05f, 0.55f, 0.75f, Color(0x3359C8FF)),
                Glow(0.85f, 1.0f, 0.7f, Color(0x40D9A6E0)),
                Glow(0.3f, 0.12f, 0.5f, Color(0xB3FFFFFF)),
                Glow(1.0f, 1.05f, 0.75f, Color(0x1F4A5878)),
                Glow(0.0f, 1.05f, 0.6f, Color(0x144A5878)),
            ),
            ink = 0xFF14171C,
            frost = 0xF8F9FB,
        )
    }.copy(lustre = listOf(Color(0x26FFD6E8), Color(0x40FFFFFF), Color(0x26D6F2FF)))

    /** Pearl Night: a black pearl. Cool charcoal with a moonlit light and soft teal and rose sheens, silver-edged glass. */
    val PearlNight: GlassTheme = glass(
        GlassThemeId.PEARL_NIGHT, "Pearl Night", "Black pearl: charcoal with a soft teal and rose sheen",
        wallpaper = listOf(0xFF1C1F27, 0xFF13151B, 0xFF0B0C10),
        glows = listOf(
            Glow(0.85f, 0.05f, 0.5f, Color(0x1FFFFFFF)),
            Glow(0.05f, 0.6f, 0.6f, Color(0x1F3CC8C0)),
            Glow(0.85f, 0.98f, 0.6f, Color(0x24D985C0)),
        ),
        rim = 0xFFD5DEE8, onDrive = 0xFF3F5A8A,
        hues = listOf(0xFF5B6475, 0xFF6E5A6E, 0xFF4F6E73, 0xFF7A6C5E, 0xFF55607A),
        milky = false,
        smoke = 0x0B0C10, solid = 0x1B1D24,
    ).copy(lustre = listOf(Color(0x0CE38AC4), Color(0x10FFFFFF), Color(0x0C6FD6CF)))

    // ---- Liquid Glass (20 Nov, Deepak's own iOS-style theme, made as a patch on main and merged here) ----
    // 21 Nov, made as close to iOS as it can be: clear glass like water droplets, never frosted or blurred. On Android
    // 13 and newer the bars look straight through to the photos and bend them at their rounded edges, which catch the
    // light (LiquidGlassAgsl); panels and buttons get the same lit edge. Android 12 and older: a clear tinted pane.
    // The words stay readable through LiquidGlassInk (checked by ThemeContrastTest).

    val LiquidGlassLight: GlassTheme = LiquidDay.pale!!.copy(
        id = GlassThemeId.LIQUID_GLASS_LIGHT,
        displayName = "Liquid Glass Light",
        bestFor = "Clear glass droplets over a neutral light gallery",
        wallpaper = listOf(Color(0xFFF8F9FB), Color(0xFFEEF1F5), Color(0xFFE6EAF0)),
        glows = emptyList(),
        // Only where there is no lens (Android 12 and older): a clear pane, tinted enough for the words, not blurred.
        glassTint = Color(0xA4F8FAFD),
        glassFallback = Color(0xFFEFF2F7),
        glassRim = White,
        onGlass = Color(0xFF171C24),
        // 21 Nov: a touch darker, so it reads at 4.5:1 through the clearer glass (LiquidGlassInk.DAY_FLOOR).
        onGlassSecondary = Color(0xFF20262F),
        onWallpaper = Color(0xFF171C24),
        accent = Color(0xFF171C24),
        onAccent = Color(0xFF171C24),
        accentFill = Color(0x76FFFFFF),
        panelFill = Color(0x54FFFFFF),
        // Never blurs (21 Nov).
        blurRadius = 0.dp,
        rimWidth = 0.75.dp,
        rimStrength = 0.75f,
        glassInk = GlassInk.LIQUID,
    )

    val LiquidGlassDark: GlassTheme = LiquidGlassLight.copy(
        id = GlassThemeId.LIQUID_GLASS_DARK,
        displayName = "Liquid Glass Dark",
        bestFor = "Smoked glass droplets over a neutral dark gallery",
        isDark = true,
        wallpaper = listOf(Color(0xFF101217), Color(0xFF171B22), Color(0xFF1D232C)),
        glassTint = Color(0xAB131720),
        glassFallback = Color(0xFF212730),
        onGlass = White,
        onGlassSecondary = Color(0xFFEFF2F7),
        onWallpaper = White,
        accent = White,
        onAccent = White,
        accentFill = Color(0x1FFFFFFF),
        panelFill = Color(0x15FFFFFF),
    )

    // ---- Clear Glass (21 Nov, Deepak: "make a new theme which is a total copy of iOS clear glass, including the
    // toggle and everything") ----
    // iOS 26's clear Liquid Glass: white and black pages like iOS, black and white words, iOS blue for the chosen tab
    // and main buttons. It is Liquid Glass (the lens, never blurred) plus what iOS adds: bars that turn dark over dark
    // photos and light over light ones, light that moves as the phone tilts, the Clear or Tinted choice (iOS 26.1), the
    // scroll edge fade, and Apple's motion with Settings > About > Test: iOS motion.

    val ClearGlassLight: GlassTheme = LiquidGlassLight.copy(
        id = GlassThemeId.CLEAR_GLASS_LIGHT,
        displayName = "Clear Glass Light",
        bestFor = "iOS clear glass on white, blue for what's chosen",
        wallpaper = listOf(Color(0xFFFFFFFF), Color(0xFFF9F9FB), Color(0xFFF2F2F7)),
        glows = emptyList(),
        glassTint = Color(0xA6FFFFFF),
        glassFallback = Color(0xFFF2F2F7),
        glassRim = White,
        onGlass = Color(0xFF000000),
        // iOS greys its secondary words with transparency; solid here, dark enough to read through the clear glass.
        onGlassSecondary = Color(0xFF242428),
        onWallpaper = Color(0xFF000000),
        // iOS blue (systemBlue): the chosen tab, ticks and progress.
        accent = Color(0xFF007AFF),
        onAccent = Color(0xFF007AFF),
        accentFill = Color(0xEBFFFFFF),
        panelFill = Color(0xB3FFFFFF),
        glassInk = GlassInk.CLEAR,
        // A shade deeper than systemBlue, so white words on it read at 4.5:1.
        prominent = Color(0xFF0068D6),
    )

    val ClearGlassDark: GlassTheme = ClearGlassLight.copy(
        id = GlassThemeId.CLEAR_GLASS_DARK,
        displayName = "Clear Glass Dark",
        bestFor = "iOS clear glass on black, blue for what's chosen",
        isDark = true,
        wallpaper = listOf(Color(0xFF000000), Color(0xFF0A0A0C), Color(0xFF1C1C1E)),
        glassTint = Color(0xB31C1C1E),
        glassFallback = Color(0xFF1C1C1E),
        onGlass = White,
        onGlassSecondary = Color(0xFFEBEBF5),
        onWallpaper = White,
        accent = White,
        // iOS blue for dark mode, on the chosen tab's darker glass.
        onAccent = Color(0xFF0A84FF),
        accentFill = Color(0xF72C2C2E),
        panelFill = Color(0x2EFFFFFF),
    )

    // ---- Dark themes ----

    /** Night sky: nearly black, with one soft light of your photos' colour at the top. */
    val AdaptiveMidnight: GlassTheme = glass(
        GlassThemeId.ADAPTIVE_MIDNIGHT, "Gallery Night", "Graphite, one soft light from your photos",
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
        GlassThemeId.ADAPTIVE_VIVID, "Prism Night", "Deep jewel colours from your photos",
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
    ).copy(id = GlassThemeId.PHOTO_DAY, displayName = "Gallery Day", bestFor = "Warm paper, one soft light from your photos")

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
    ).copy(id = GlassThemeId.VIVID_DAY, displayName = "Prism Day", bestFor = "Pastel colours from your photos")

    /** The seven themes, each with its day and night (18 Nov; Pearl and Liquid Glass added 20 Nov, Clear Glass 21 Nov). Liquid first: everyone starts on it. */
    val families: List<ThemeFamily> = listOf(
        // 18 Nov (Deepak: "name them after what they look like"): new names only; the saved keys stay, so everyone keeps
        // the theme they chose.
        ThemeFamily("LIQUID", "Ocean", "Sky blue by day, a deep sea at night", LiquidDay.pale!!, LiquidNight),
        ThemeFamily("PEARL", "Pearl", "Pearl white and clear glass by day, a black pearl's soft sheen at night", PearlDay, PearlNight),
        ThemeFamily("LUMINOUS", "Aurora", "Lilac and mint by day, northern lights at night", AuroraGlassLight.pale!!, LuminousNight),
        ThemeFamily("PHOTO", "Gallery", "Warm paper by day, graphite at night, one soft light from your photos", PhotoDay, AdaptiveMidnight),
        ThemeFamily("VIVID", "Prism", "Colours from your photos: pastels by day, deep jewels at night", VividDay, AdaptiveVivid),
        ThemeFamily("LIQUID_GLASS", "Liquid Glass", "Clear glass like iOS, never blurred: its edges bend the photos like water droplets. Light by day, smoked at night", LiquidGlassLight, LiquidGlassDark),
        ThemeFamily("CLEAR_GLASS", "Clear Glass", "A copy of iOS 26's clear glass: bars that turn dark over dark photos and light over light, light that moves as you tilt the phone, iOS blue. Clear or Tinted", ClearGlassLight, ClearGlassDark),
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
