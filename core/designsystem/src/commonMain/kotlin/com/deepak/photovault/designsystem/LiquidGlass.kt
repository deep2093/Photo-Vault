package com.deepak.photovault.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** A thin, opposing reflection on the non-refractive material. Draw it underneath the labels. */
internal fun Modifier.liquidGlassSheen(dark: Boolean): Modifier = drawWithCache {
    val light = Brush.linearGradient(
        0f to Color.White.copy(alpha = if (dark) 0.17f else 0.26f),
        0.45f to Color.Transparent,
        1f to Color.Black.copy(alpha = if (dark) 0.18f else 0.09f),
        start = Offset(size.width * 0.12f, 0f),
        end = Offset(size.width * 0.84f, size.height),
    )
    onDrawWithContent {
        drawRect(light)
        drawContent()
    }
}

/** Reuse the host button's interactions, so the highlight needs no competing gesture detector. */
@Composable
fun Modifier.liquidGlassTouch(source: InteractionSource): Modifier {
    val settings = LocalGlassSettings.current
    if (!LocalGlassTheme.current.isLiquidGlass || !settings.blurEnabled || settings.reduceMotion) return this

    var press by remember(source) { mutableStateOf<PressInteraction.Press?>(null) }
    var point by remember(source) { mutableStateOf(Offset.Unspecified) }
    LaunchedEffect(source) {
        source.interactions.collect { event ->
            when (event) {
                is PressInteraction.Press -> {
                    point = event.pressPosition
                    press = event
                }
                is PressInteraction.Release -> if (press == event.press) press = null
                is PressInteraction.Cancel -> if (press == event.press) press = null
                else -> Unit
            }
        }
    }
    val alpha = animateFloatAsState(
        targetValue = if (press == null) 0f else 0.30f,
        animationSpec = tween(if (press == null) 220 else 90),
        label = "glass reflection",
    )
    return drawWithContent {
        if (alpha.value > 0f && size.minDimension > 0f) {
            val center = if (point == Offset.Unspecified) this.center else point
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, Color.White.copy(alpha = 0f)),
                    center = center,
                    radius = size.minDimension,
                ),
                alpha = alpha.value,
            )
        }
        drawContent()
    }
}
