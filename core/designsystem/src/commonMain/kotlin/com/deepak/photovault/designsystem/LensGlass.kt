package com.deepak.photovault.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

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

    /** Changes every time the recorded content is drawn again, so a bar knows when to look behind itself again. */
    val version: Long

    /**
     * How bright the recorded content is behind [rect] (root coordinates), as average linear luminance from 0 (black)
     * to 1 (white), with [backdrop] where it is see-through. Null when it can't tell (nothing recorded yet). Clear Glass
     * uses it to turn a bar dark over dark photos and light over light ones (21 Nov). [fresh]: measure from a new
     * copy even if the content doesn't seem to have been drawn again.
     */
    suspend fun brightnessBehind(rect: Rect, backdrop: Color, fresh: Boolean = false): Float?

    /**
     * Which [version] the last [brightnessBehind] answer was measured from. Small copies are made at most a few times a
     * second, so an answer can be a moment old: a bar looks again until this has caught up with [version] (21 Nov, a
     * pill that asked just after its neighbour kept the old answer for good and stayed the wrong colour).
     */
    val measuredVersion: Long
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

/**
 * Liquid Glass through the Android lens (21 Nov: clear, never blurred). [backdrop] shows where the recorded content is
 * see-through (the page itself is not recorded), [fallback] is drawn until the lens is ready, and [inkFloor] and
 * [inkCeiling] keep the words readable over any photo (see [LiquidGlassInk]). Clear Glass adds [frost] of [frostColor]
 * over the picture (Tinted, and its chosen tab), the light's direction ([light], from the phone's tilt) and how dark the
 * glass is right now ([darkness], while a bar changes over); both are read while drawing.
 */
data class LiquidGlassOptics(
    val dark: Boolean,
    val backdrop: Color,
    val fallback: Color,
    val inkFloor: Float = LiquidGlassInk.DAY_FLOOR,
    val inkCeiling: Float = LiquidGlassInk.NIGHT_CEILING,
    val frost: Float = 0f,
    val frostColor: Color = Color.White,
    val light: State<Offset>? = null,
    val darkness: State<Float>? = null,
)

@Composable
fun lensGlassActive(): Boolean {
    val theme = LocalGlassTheme.current
    val settings = LocalGlassSettings.current
    return theme.lens && settings.blurEnabled && (theme.isLiquidGlass || settings.lensEnabled)
}
