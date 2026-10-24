package com.deepak.photovault.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Static highlights only: no shimmer loops, image filters or extra backdrop captures. */
@Immutable
data class PreviewGlassStyle(
    val selectedFill: Color,
    val onSelected: Color,
    val sheen: List<Color>,
)

/** Opt-in interpretations of the saved Aurora / Crystal concept, using the existing Haze renderer. */
object ExperimentalGlassThemes {
    val Aurora = GlassTheme(
        id = GlassThemeId.EXPERIMENTAL_AURORA_GLASS,
        displayName = "Aurora Glass",
        bestFor = "Experimental · luminous blue and violet glass",
        isDark = true,
        wallpaper = listOf(Color(0xFF08244F), Color(0xFF111C44), Color(0xFF090F28)),
        glows = listOf(
            Glow(0.80f, 0.03f, 0.70f, Color(0x80214CCC)),
            Glow(0.08f, 0.30f, 0.65f, Color(0x662089BC)),
            Glow(0.86f, 0.95f, 0.65f, Color(0x66684BE0)),
        ),
        // Protective tint keeps labels readable even when a white photo passes under the bar.
        glassTint = Color(0xCC101F48),
        glassFallback = Color(0x99132147),
        glassRim = Color(0xFFD3E8FF),
        onGlass = Color(0xFFF8FBFF),
        onGlassSecondary = Color(0xFFDFEAFB),
        accent = Color(0xFFA9DCFF),
        onAccent = Color(0xFF082D54),
        badgeBackedUp = Color(0xFF1B6E45),
        badgePhoneOnly = Color(0xFF8A4B00),
        badgeOnDrive = Color(0xFF1A50A6),
        onBadge = Color.White,
        sampleHues = listOf(Color(0xFF1982C4), Color(0xFFE07A5F), Color(0xFF3D9970), Color(0xFF6A4C93), Color(0xFFF2B134)),
        blurRadius = 26.dp,
        grain = 0.015f,
        previewGlass = PreviewGlassStyle(
            selectedFill = Color(0xFFF5FAFF),
            onSelected = Color(0xFF1255B0),
            sheen = listOf(Color(0x14D8F4FF), Color(0x04FFFFFF), Color(0x0C9A86FF)),
        ),
    )

    val Crystal = GlassTheme(
        id = GlassThemeId.EXPERIMENTAL_CRYSTAL_GLASS,
        displayName = "Crystal Glass",
        bestFor = "Experimental · neutral glass coloured by your photos",
        isDark = true,
        wallpaper = listOf(Color(0xFF24323B), Color(0xFF30352D), Color(0xFF152A2D)),
        glows = emptyList(),
        glassTint = Color(0xCC162023),
        glassFallback = Color(0x80131D20),
        glassRim = Color(0xFFE7F1EF),
        onGlass = Color(0xFFFAFCFC),
        onGlassSecondary = Color(0xFFE1ECEA),
        accent = Color(0xFFB8E7ED),
        onAccent = Color(0xFF10383D),
        badgeBackedUp = Color(0xFF1B6E45),
        badgePhoneOnly = Color(0xFF8A4B00),
        badgeOnDrive = Color(0xFF235F6A),
        onBadge = Color.White,
        sampleHues = listOf(Color(0xFFB29B70), Color(0xFF397B87), Color(0xFF718559), Color(0xFF9F7A6D), Color(0xFF4C677D)),
        adaptive = AdaptiveStyle(
            values = listOf(0.34f, 0.27f, 0.19f),
            minSaturation = 0.12f,
            maxSaturation = 0.38f,
            glow = 0.30f,
            scrim = 0.30f,
            fallback = listOf(Color(0xFF24323B), Color(0xFF30352D), Color(0xFF152A2D)),
        ),
        blurRadius = 18.dp,
        grain = 0f,
        previewGlass = PreviewGlassStyle(
            selectedFill = Color(0xFFF6FAF9),
            onSelected = Color(0xFF15536B),
            sheen = listOf(Color(0x10FFFFFF), Color(0x02FFFFFF), Color(0x06E4EFEB)),
        ),
    )
}
