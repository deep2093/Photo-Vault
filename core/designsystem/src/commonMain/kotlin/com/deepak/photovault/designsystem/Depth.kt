package com.deepak.photovault.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * Depth for toolbar text and icons in the lens-glass themes (24 Oct): a soft drop shadow under each glyph, so the
 * labels stand off the see-through glass instead of sitting flat on it. Only while lens glass is on.
 */
@Composable
fun glassDepthOn(): Boolean {
    val settings = LocalGlassSettings.current
    return settings.lensEnabled && settings.blurEnabled && LocalGlassTheme.current.lens
}

@Composable
private fun depthColor(): Color = Color.Black.copy(alpha = if (LocalGlassTheme.current.isDark) 0.6f else 0.32f)

/** An icon that, in lens glass, carries a soft shadow just below it. Same size and behaviour otherwise. */
@Composable
fun DepthIcon(icon: ImageVector, contentDescription: String?, tint: Color, modifier: Modifier = Modifier) {
    Box(modifier) {
        if (glassDepthOn()) {
            Icon(
                icon,
                contentDescription = null,
                tint = depthColor(),
                modifier = Modifier.matchParentSize().offset(y = 1.5.dp).blur(2.dp, BlurredEdgeTreatment.Unbounded),
            )
        }
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.matchParentSize())
    }
}

/** The same text style with a soft shadow under the letters, in lens glass. */
@Composable
fun TextStyle.withDepth(): TextStyle =
    if (glassDepthOn()) copy(shadow = Shadow(depthColor(), Offset(0f, 3f), 5f)) else this
