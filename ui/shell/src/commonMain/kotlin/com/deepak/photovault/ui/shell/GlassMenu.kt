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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.LayoutDirection
import com.deepak.photovault.designsystem.glassChrome
import com.deepak.photovault.designsystem.LocalGlassSettings
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.glassRim

/**
 * A small menu in the app's own glass (31 Oct), drawn inside the screen (not a pop-up window) so it is made exactly
 * like the top bar: the theme's glass, depth and rim, and the blur slider. A tap outside closes it. It opens just
 * under the top bar, at the right.
 */
@Composable
internal fun BoxScope.GlassMenuLayer(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (!expanded) return
    val insets = LocalScreenInsets.current
    Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClick = onDismiss))
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .align(Alignment.TopEnd)
            .padding(
                end = insets.calculateEndPadding(LayoutDirection.Ltr) + 12.dp,
                top = insets.calculateTopPadding() + 8.dp + TopBarHeight + 6.dp,
            )
            .widthIn(min = 200.dp, max = 280.dp)
            .glassChrome(shape, elevation = 12.dp)
            // A firmer tint under the words (31 Oct: the plain glass was hard to read over photos); still the theme's own colour.
            .background(LocalGlassTheme.current.glassFallback.copy(alpha = 0.82f), shape)
            .padding(vertical = 6.dp),
        content = content,
    )
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
