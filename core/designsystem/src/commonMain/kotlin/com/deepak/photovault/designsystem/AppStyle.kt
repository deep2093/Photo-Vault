package com.deepak.photovault.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The redesign (18 Nov, Deepak: "all three, switchable"). A style is the whole look of the app: its colours (light and
 * dark), fonts, corners and how bars and cards are drawn. Every screen keeps working the same; only the drawing
 * changes, because the shared pieces (bars, cards, buttons, switches, the bottom bar) read the style.
 *
 * - Classic: the glass themes as they were (wallpapers, glass, the six theme choices).
 * - Clear Glass: glass only on floating bars, photos on a calm plain page, dark text in light mode. Inter.
 * - Material You: Android's own look, colours from the wallpaper on Android 12 and later. Roboto.
 * - Darkroom: black or paper, hairlines, one warm accent, magazine-style dates. Libre Bodoni with Public Sans.
 */

/** Saved in settings: never rename. */
enum class AppStyleId { CLASSIC, CLEAR_GLASS, MATERIAL_YOU, DARKROOM }

/** Light, dark, or as the phone is set. Classic ignores it (each classic theme is light or dark itself). */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** How the sections are reached at the bottom of a phone screen. */
enum class NavStyle {
    /** A floating capsule with a sliding highlight (Classic, Clear Glass). */
    CAPSULE,

    /** Android's navigation bar: full width, a pill behind the chosen icon. */
    MATERIAL,

    /** Plain tabs on the page with a thin line above the chosen one (Darkroom). */
    TABS,
}

/** How a day is titled in the photo grid. */
enum class DateStyle {
    /** "Saturday, 14 November" in one line. */
    PLAIN,

    /** A big serif day number with the month and weekday in small capitals beside it (Darkroom). */
    MAGAZINE,
}

@Immutable
data class AppStyle(
    val id: AppStyleId,
    val displayName: String,
    val summary: String,
    /** Floating bars blur what scrolls under them. */
    val glass: Boolean,
    /** Classic's bright specular edges and shading on glass. */
    val classicGlass: Boolean,
    /** A plain thin line around bars and cards instead of a glass edge. */
    val hairlines: Boolean,
    /** Corners of cards and panels. */
    val cardRadius: Dp,
    /** Every corner (cards, buttons, bars) is this, when set (Darkroom: square). */
    val squareCorners: Dp? = null,
    val nav: NavStyle,
    val dates: DateStyle,
    /** Space between photos in the grid. */
    val photoGap: Dp,
    /** The theme picker and the glass sliders apply (Classic only has theme choices). */
    val hasThemeChoices: Boolean = false,
)

object AppStyles {
    val Classic = AppStyle(
        AppStyleId.CLASSIC, "Classic glass", "The original glass and wallpapers",
        glass = true, classicGlass = true, hairlines = false, cardRadius = 24.dp,
        nav = NavStyle.CAPSULE, dates = DateStyle.PLAIN, photoGap = 2.dp, hasThemeChoices = true,
    )
    val ClearGlass = AppStyle(
        AppStyleId.CLEAR_GLASS, "Clear Glass", "Glass bars over a calm page",
        glass = true, classicGlass = false, hairlines = true, cardRadius = 20.dp,
        nav = NavStyle.CAPSULE, dates = DateStyle.PLAIN, photoGap = 2.dp,
    )
    val MaterialYou = AppStyle(
        AppStyleId.MATERIAL_YOU, "Material You", "Android's own look, your wallpaper's colours",
        glass = false, classicGlass = false, hairlines = false, cardRadius = 16.dp,
        nav = NavStyle.MATERIAL, dates = DateStyle.PLAIN, photoGap = 2.dp,
    )
    val Darkroom = AppStyle(
        AppStyleId.DARKROOM, "Darkroom", "A quiet gallery, magazine dates",
        glass = false, classicGlass = false, hairlines = true, cardRadius = 2.dp, squareCorners = 2.dp,
        nav = NavStyle.TABS, dates = DateStyle.MAGAZINE, photoGap = 1.dp,
    )

    /** In the order Settings offers them. */
    val all: List<AppStyle> = listOf(ClearGlass, MaterialYou, Darkroom, Classic)

    /** Everyone starts on Clear Glass (18 Nov); an unknown saved value does too. */
    val default: AppStyle = ClearGlass

    fun byId(id: String?): AppStyle = all.firstOrNull { it.id.name == id } ?: default
}

val LocalAppStyle = staticCompositionLocalOf { AppStyles.Classic }

/** A shape as the current style draws it: square in Darkroom, the style's card corners for cards. */
@Composable
fun styled(shape: Shape): Shape {
    val style = LocalAppStyle.current
    style.squareCorners?.let { return RoundedCornerShape(it) }
    return if (shape == GlassCardShape) RoundedCornerShape(style.cardRadius) else shape
}

/**
 * The fonts the styles use. The app puts the real ones in (bundled with the app); without them the system fonts are
 * used, so nothing breaks.
 */
@Immutable
data class StyleFonts(
    /** Clear Glass. */
    val inter: FontFamily = FontFamily.Default,
    /** Darkroom's titles and dates. */
    val serif: FontFamily = FontFamily.Serif,
    /** Darkroom's everything else. */
    val sans: FontFamily = FontFamily.Default,
)

val LocalStyleFonts = staticCompositionLocalOf { StyleFonts() }

/** Material's type scale, in the style's fonts. Sizes stay in sp, so they follow Android's font size. */
fun styleTypography(style: AppStyle, fonts: StyleFonts): Typography {
    val base = Typography()
    fun TextStyle.inFont(family: FontFamily) = copy(fontFamily = family)
    return when (style.id) {
        AppStyleId.CLASSIC, AppStyleId.MATERIAL_YOU -> base
        AppStyleId.CLEAR_GLASS -> Typography(
            displayLarge = base.displayLarge.inFont(fonts.inter),
            displayMedium = base.displayMedium.inFont(fonts.inter),
            displaySmall = base.displaySmall.inFont(fonts.inter),
            headlineLarge = base.headlineLarge.inFont(fonts.inter).copy(fontWeight = FontWeight.Bold),
            headlineMedium = base.headlineMedium.inFont(fonts.inter).copy(fontWeight = FontWeight.Bold),
            headlineSmall = base.headlineSmall.inFont(fonts.inter).copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
            titleLarge = base.titleLarge.inFont(fonts.inter).copy(fontWeight = FontWeight.Bold),
            titleMedium = base.titleMedium.inFont(fonts.inter).copy(fontWeight = FontWeight.SemiBold),
            titleSmall = base.titleSmall.inFont(fonts.inter).copy(fontWeight = FontWeight.SemiBold),
            bodyLarge = base.bodyLarge.inFont(fonts.inter),
            bodyMedium = base.bodyMedium.inFont(fonts.inter),
            bodySmall = base.bodySmall.inFont(fonts.inter),
            labelLarge = base.labelLarge.inFont(fonts.inter).copy(fontWeight = FontWeight.SemiBold),
            labelMedium = base.labelMedium.inFont(fonts.inter).copy(fontWeight = FontWeight.SemiBold),
            labelSmall = base.labelSmall.inFont(fonts.inter).copy(fontWeight = FontWeight.SemiBold),
        )
        AppStyleId.DARKROOM -> Typography(
            displayLarge = base.displayLarge.inFont(fonts.serif),
            displayMedium = base.displayMedium.inFont(fonts.serif),
            displaySmall = base.displaySmall.inFont(fonts.serif),
            headlineLarge = base.headlineLarge.inFont(fonts.serif).copy(fontWeight = FontWeight.Medium),
            headlineMedium = base.headlineMedium.inFont(fonts.serif).copy(fontWeight = FontWeight.Medium),
            headlineSmall = base.headlineSmall.inFont(fonts.serif).copy(fontWeight = FontWeight.Medium),
            titleLarge = base.titleLarge.inFont(fonts.serif).copy(fontWeight = FontWeight.Medium),
            // Smaller titles and everything read at length or tapped stay in the sans: the serif only from 22 sp.
            titleMedium = base.titleMedium.inFont(fonts.sans).copy(fontWeight = FontWeight.SemiBold),
            titleSmall = base.titleSmall.inFont(fonts.sans).copy(fontWeight = FontWeight.SemiBold),
            bodyLarge = base.bodyLarge.inFont(fonts.sans),
            bodyMedium = base.bodyMedium.inFont(fonts.sans),
            bodySmall = base.bodySmall.inFont(fonts.sans),
            labelLarge = base.labelLarge.inFont(fonts.sans).copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
            labelMedium = base.labelMedium.inFont(fonts.sans).copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
            labelSmall = base.labelSmall.inFont(fonts.sans).copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
        )
    }
}

/**
 * The colours of the new styles, light and dark, as [GlassTheme]s, so every screen that reads the theme gets them.
 * Text colours were checked against their page and panel colours (4.5:1 or better; see StyleThemesTest).
 */
object StyleThemes {
    private val BackedUp = Color(0xFF1B6E45)
    private val PhoneOnly = Color(0xFF8A4B00)
    private val Hues = listOf(Color(0xFF9AA5B1), Color(0xFFB8C2CC), Color(0xFF7D8894), Color(0xFFD0D6DC), Color(0xFFC4CCD4))

    private fun flat(
        id: GlassThemeId,
        name: String,
        dark: Boolean,
        page: Long,
        bar: Long,
        solidBar: Long,
        line: Long,
        text: Long,
        text2: Long,
        accent: Long,
        onAccent: Long,
        accentSoft: Long,
        onAccentSoft: Long,
        panel: Long,
        onDrive: Long,
        blur: Dp = 20.dp,
    ) = GlassTheme(
        id = id,
        displayName = name,
        bestFor = name,
        isDark = dark,
        wallpaper = listOf(Color(page), Color(page)),
        glows = emptyList(),
        glassTint = Color(bar),
        glassFallback = Color(solidBar),
        glassRim = Color(line),
        onGlass = Color(text),
        onGlassSecondary = Color(text2),
        accent = Color(accent),
        onAccent = Color(onAccent),
        badgeBackedUp = BackedUp,
        badgePhoneOnly = PhoneOnly,
        badgeOnDrive = Color(onDrive),
        onBadge = Color.White,
        sampleHues = Hues,
        blurRadius = blur,
        grain = 0f,
        lens = false,
        panelFill = Color(panel),
        onWallpaper = Color(text),
        sheen = false,
        accentSoft = Color(accentSoft),
        onAccentSoft = Color(onAccentSoft),
    )

    val ClearGlassLight = flat(
        GlassThemeId.CLEAR_GLASS_LIGHT, "Clear Glass", dark = false,
        page = 0xFFF5F6F8, bar = 0xB8FFFFFF, solidBar = 0xF5FFFFFF, line = 0x1A0F141E,
        text = 0xFF0F1217, text2 = 0xFF566070, accent = 0xFF1E5EEB, onAccent = 0xFFFFFFFF,
        accentSoft = 0x1F1E5EEB, onAccentSoft = 0xFF1A4FC4, panel = 0xFFFFFFFF, onDrive = 0xFF1E5EEB,
    )
    val ClearGlassDark = flat(
        GlassThemeId.CLEAR_GLASS_DARK, "Clear Glass", dark = true,
        page = 0xFF090A0C, bar = 0xB8202228, solidBar = 0xF5202228, line = 0x24FFFFFF,
        text = 0xFFF3F4F6, text2 = 0xFFA3ABB8, accent = 0xFF8AB0FF, onAccent = 0xFF0A1430,
        accentSoft = 0x2E8AB0FF, onAccentSoft = 0xFFB9CEFF, panel = 0xFF16181C, onDrive = 0xFF2F5FC9,
    )
    val DarkroomDark = flat(
        GlassThemeId.DARKROOM_DARK, "Darkroom", dark = true,
        page = 0xFF000000, bar = 0xFF000000, solidBar = 0xFF000000, line = 0xFF2A2A2A,
        text = 0xFFF4F1EA, text2 = 0xFFA9A49A, accent = 0xFFE9A23B, onAccent = 0xFF1A1206,
        accentSoft = 0x29E9A23B, onAccentSoft = 0xFFF3BD6B, panel = 0xFF0F0F0F, onDrive = 0xFF6B5A3A,
    )
    val DarkroomLight = flat(
        GlassThemeId.DARKROOM_LIGHT, "Darkroom", dark = false,
        page = 0xFFF6F3EC, bar = 0xFFF6F3EC, solidBar = 0xFFF6F3EC, line = 0xFFDDD7CB,
        text = 0xFF151412, text2 = 0xFF5F5A52, accent = 0xFFA3541A, onAccent = 0xFFFFFFFF,
        accentSoft = 0x1FA3541A, onAccentSoft = 0xFF7E3F12, panel = 0xFFFFFFFF, onDrive = 0xFF7E3F12,
    )

    /** Material You's colours when the phone can't give its own (before Android 12): Pixallery blue. */
    fun materialFallback(dark: Boolean): ColorScheme =
        if (dark) {
            darkColorScheme(
                primary = Color(0xFFB7C4FF), onPrimary = Color(0xFF0E2683),
                primaryContainer = Color(0xFF2B3E9F), onPrimaryContainer = Color(0xFFDCE1FF),
                secondaryContainer = Color(0xFF3E4357), onSecondaryContainer = Color(0xFFDEE1F9),
                surface = Color(0xFF121318), onSurface = Color(0xFFE3E1E9), onSurfaceVariant = Color(0xFFC5C5D4),
                surfaceContainerLow = Color(0xFF1A1B21), surfaceContainer = Color(0xFF1E1F25), surfaceContainerHigh = Color(0xFF292A2F),
                surfaceContainerHighest = Color(0xFF34343A), background = Color(0xFF121318), onBackground = Color(0xFFE3E1E9),
                outline = Color(0xFF8F909A), outlineVariant = Color(0xFF454652),
                inverseSurface = Color(0xFFE3E1E9), inverseOnSurface = Color(0xFF2F3036), inversePrimary = Color(0xFF3756C5),
                error = Color(0xFFFFB4AB),
            )
        } else {
            lightColorScheme(
                primary = Color(0xFF3756C5), onPrimary = Color(0xFFFFFFFF),
                primaryContainer = Color(0xFFDCE1FF), onPrimaryContainer = Color(0xFF0A1A5C),
                secondaryContainer = Color(0xFFDEE1F9), onSecondaryContainer = Color(0xFF161B2C),
                surface = Color(0xFFFAF8FF), onSurface = Color(0xFF1A1B21), onSurfaceVariant = Color(0xFF454652),
                surfaceContainerLow = Color(0xFFF4F2FA), surfaceContainer = Color(0xFFEEEDF6), surfaceContainerHigh = Color(0xFFE8E7F0),
                surfaceContainerHighest = Color(0xFFE3E1EA), background = Color(0xFFFAF8FF), onBackground = Color(0xFF1A1B21),
                outline = Color(0xFF767680), outlineVariant = Color(0xFFC5C5D4),
                inverseSurface = Color(0xFF2F3036), inverseOnSurface = Color(0xFFF1F0F7), inversePrimary = Color(0xFFB7C4FF),
                error = Color(0xFFBA1A1A),
            )
        }

    /** Material You as a [GlassTheme], from the phone's colour scheme (or [materialFallback]). */
    fun material(scheme: ColorScheme, dark: Boolean): GlassTheme = GlassTheme(
        id = if (dark) GlassThemeId.MATERIAL_DARK else GlassThemeId.MATERIAL_LIGHT,
        displayName = "Material You",
        bestFor = "Material You",
        isDark = dark,
        wallpaper = listOf(scheme.surface, scheme.surface),
        glows = emptyList(),
        glassTint = scheme.surfaceContainer,
        glassFallback = scheme.surfaceContainer,
        glassRim = scheme.outlineVariant,
        onGlass = scheme.onSurface,
        onGlassSecondary = scheme.onSurfaceVariant,
        accent = scheme.primary,
        onAccent = scheme.onPrimary,
        badgeBackedUp = BackedUp,
        badgePhoneOnly = PhoneOnly,
        badgeOnDrive = scheme.primaryContainer,
        onBadge = Color.White,
        sampleHues = Hues,
        blurRadius = 0.dp,
        grain = 0f,
        lens = false,
        panelFill = scheme.surfaceContainerLow,
        onWallpaper = scheme.onSurface,
        sheen = false,
        accentSoft = scheme.secondaryContainer,
        onAccentSoft = scheme.onSecondaryContainer,
    )

    /** The theme for [style] in light or dark. Classic is not handled here (it has its own theme choices). */
    fun theme(style: AppStyle, dark: Boolean, materialScheme: ColorScheme?): GlassTheme = when (style.id) {
        AppStyleId.CLEAR_GLASS -> if (dark) ClearGlassDark else ClearGlassLight
        AppStyleId.DARKROOM -> if (dark) DarkroomDark else DarkroomLight
        AppStyleId.MATERIAL_YOU -> material(materialScheme ?: materialFallback(dark), dark)
        AppStyleId.CLASSIC -> if (dark) GlassThemes.darkThemes.first() else GlassThemes.default
    }
}
