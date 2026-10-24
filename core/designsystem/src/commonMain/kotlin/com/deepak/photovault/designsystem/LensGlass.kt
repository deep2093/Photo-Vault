package com.deepak.photovault.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

/**
 * Lens glass (24 Oct, experimental, Phase 5): instead of only blurring what is behind a floating bar, the bar acts
 * like a thick piece of glass: the picture behind it bends and magnifies toward its edges, with a light blur. It needs
 * the platform to hand the bar a copy of what is behind it, so it lives behind this small interface; Android
 * (13 and newer) implements it, everything else keeps the normal blur.
 */
interface LensGlass {
    /** On the content that scrolls behind the bar: records it so the bar can look through it. */
    fun source(): Modifier

    /** On the bar: draws the recorded content bent like glass, then [tint] over it. */
    @Composable
    fun lens(shape: Shape, tint: Color): Modifier
}

/** Given only to the floating navigation bar, never to anything inside the recorded content (it would draw itself). */
val LocalLensGlass = staticCompositionLocalOf<LensGlass?> { null }
