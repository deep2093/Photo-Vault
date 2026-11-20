package com.deepak.photovault.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp

/**
 * Lens glass (24 Oct, experimental, Phase 5): instead of only blurring what is behind a floating bar, the bar acts
 * like a thick piece of glass: the picture behind it bends and magnifies toward its edges, with a light blur. It needs
 * the platform to hand the bar a copy of what is behind it, so it lives behind this small interface; Android
 * (13 and newer) implements it, everything else keeps the normal blur.
 */
interface LensGlass {
    /**
     * On the content that scrolls behind the bar: records it so the bar can look through it. The modifier stays in
     * place whether lens glass is on or off (only [active] changes), so switching themes never attaches it late.
     */
    fun source(active: Boolean, background: Color = Color.Unspecified): Modifier

    /** Composes the lens modifier against this screen's recording. */
    val drawLens: @Composable (Shape, Color, Boolean, LiquidGlassOptics?) -> Modifier
}

@Composable
internal fun LensGlass.lens(
    shape: Shape,
    tint: Color,
    active: Boolean,
    liquid: LiquidGlassOptics? = null,
): Modifier = drawLens(shape, tint, active, liquid)

/** Given only to floating bars and pills, never to anything inside the content they record (it would draw itself). */
val LocalLensGlass = staticCompositionLocalOf<LensGlass?> { null }

/** The Android lens uses this palette to composite transparent parts of the recorded layer. */
data class LiquidGlassOptics(
    val blur: Dp,
    val dark: Boolean,
    val backdrop: Color,
    val fallback: Color,
)

@Composable
fun lensGlassActive(): Boolean {
    val theme = LocalGlassTheme.current
    val settings = LocalGlassSettings.current
    return theme.lens && settings.blurEnabled && (theme.isLiquidGlass || settings.lensEnabled)
}
