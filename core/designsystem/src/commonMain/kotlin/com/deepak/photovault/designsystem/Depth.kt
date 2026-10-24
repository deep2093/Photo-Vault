package com.deepak.photovault.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
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

/**
 * Text with a soft shadow in lens glass (25 Oct). The shadow is a blurred copy under the letters, like [DepthIcon]:
 * a text-style shadow drew a pale band across the whole title bar in Clear Light.
 */
@Composable
fun DepthText(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    fontWeight: androidx.compose.ui.text.font.FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: androidx.compose.ui.text.style.TextOverflow = androidx.compose.ui.text.style.TextOverflow.Clip,
) {
    Box(modifier) {
        if (glassDepthOn()) {
            androidx.compose.material3.Text(
                text, color = depthColor(), style = style, fontWeight = fontWeight, maxLines = maxLines, overflow = overflow,
                modifier = Modifier.offset(y = 1.5.dp).blur(2.dp, BlurredEdgeTreatment.Unbounded),
            )
        }
        androidx.compose.material3.Text(text, color = color, style = style, fontWeight = fontWeight, maxLines = maxLines, overflow = overflow)
    }
}
