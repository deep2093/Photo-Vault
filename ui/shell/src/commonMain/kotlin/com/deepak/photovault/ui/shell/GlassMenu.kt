package com.deepak.photovault.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.deepak.photovault.designsystem.LocalGlassSettings
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.glassRim

/**
 * A small menu in the app's own glass (31 Oct), opened from a pill button. It takes the theme's glass colour and
 * rim, and goes solid when the glass effect is off. It opens just under the button, at its right edge.
 * (A pop-up is a window of its own, so it can't blur what is behind it; it is tinted glass instead.)
 */
@Composable
internal fun GlassMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (!expanded) return
    val theme = LocalGlassTheme.current
    val glassOn = LocalGlassSettings.current.blurEnabled
    val gap = with(LocalDensity.current) { 44.dp.roundToPx() }
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, gap),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier
                .widthIn(min = 200.dp, max = 280.dp)
                .clip(shape)
                .background(theme.glassFallback.copy(alpha = if (glassOn) 0.94f else 1f).compositeOver(theme.wallpaper.first()))
                .glassRim(shape)
                .padding(vertical = 6.dp),
            content = content,
        )
    }
}

/** One row of a [GlassMenu]: icon, label and, if wanted, something at the end (a tick or an arrow). */
@Composable
internal fun GlassMenuItem(
    icon: ImageVector?,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    trailing: ImageVector? = null,
) {
    val theme = LocalGlassTheme.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (selected) theme.accent else theme.onGlass
        if (icon != null) {
            Icon(icon, null, tint = if (selected) theme.accent else theme.onGlassSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(12.dp))
        }
        Text(label, color = tint, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1)
        if (trailing != null) {
            Spacer(Modifier.size(8.dp))
            Icon(trailing, null, tint = tint, modifier = Modifier.size(18.dp))
        }
    }
}

/** A thin line between groups of menu rows. */
@Composable
internal fun GlassMenuDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).height(1.dp).background(LocalGlassTheme.current.onGlass.copy(alpha = 0.12f)))
}
