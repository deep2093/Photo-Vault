package com.deepak.photovault.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Smallest touch target, per Android accessibility guidance. */
val MinTouchTarget = 48.dp

private val Capsule = RoundedCornerShape(percent = 50)

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, onWallpaper: Boolean = false) {
    Text(
        text,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        // Primary colour: headers also sit straight on the wallpaper (photo dates), including adaptive wallpapers.
        color = if (onWallpaper) LocalGlassTheme.current.onWallpaper else LocalGlassTheme.current.onGlass,
    )
}

/** Capsule button. [primary] fills with the accent; otherwise it is glass. */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    icon: ImageVector? = null,
    centered: Boolean = false,
) {
    val theme = LocalGlassTheme.current
    val haptics = rememberHaptics()
    val shape = Capsule
    Row(
        modifier
            .heightIn(min = MinTouchTarget)
            .then(if (primary) Modifier.clip(shape).background(theme.accentSurface).glassDepth().glassRim(shape) else Modifier.glassPanel(shape))
            .clickable(role = Role.Button, onClick = {
                haptics.tap()
                onClick()
            })
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (centered) Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally) else Arrangement.spacedBy(8.dp),
    ) {
        val fg = if (primary) theme.onAccent else theme.onGlass
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun GlassIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalGlassTheme.current
    val haptics = rememberHaptics()
    // No disc behind the icon: it sits on the bar's own glass (feedback 22 Oct). The ripple shows the touch.
    Box(
        modifier
            .size(MinTouchTarget)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = {
                haptics.tap()
                onClick()
            })
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        DepthIcon(icon, null, theme.onGlass, Modifier.size(24.dp))
    }
}

/** Single-choice chip (for example Day / Month / Year). */
@Composable
fun GlassChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalGlassTheme.current
    val haptics = rememberHaptics()
    val background by animateColorAsState(if (selected) theme.accentSurface else Color.Transparent, motionSpec())
    Box(
        modifier
            .heightIn(min = MinTouchTarget)
            .padding(vertical = 4.dp)
            .glassPanel(Capsule)
            .background(background)
            .selectable(selected = selected, role = Role.RadioButton, onClick = {
                haptics.tick()
                onClick()
            })
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) theme.onAccent else theme.onGlass, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * A setting with a switch. When [warning] is given it is shown while the switch is on, so the cost of the
 * choice is visible at the moment it is made (for example backing up on mobile data).
 */
@Composable
fun GlassSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    warning: String? = null,
    icon: ImageVector? = null,
) {
    val theme = LocalGlassTheme.current
    val haptics = rememberHaptics()
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .toggleable(value = checked, role = Role.Switch, onValueChange = { on ->
                    haptics.toggle(on)
                    onCheckedChange(on)
                })
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                // With a description under the title, the icon lines up with the title, not the middle (24 Oct).
                Icon(
                    icon,
                    contentDescription = null,
                    tint = theme.onGlassSecondary,
                    modifier = if (description != null) Modifier.align(Alignment.Top).padding(top = 2.dp) else Modifier,
                )
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = theme.onGlass, style = MaterialTheme.typography.bodyLarge)
                if (description != null) {
                    Text(description, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.width(12.dp))
            // The row handles the click, so the switch itself is not separately focusable.
            GlassToggle(checked)
        }
        if (warning != null && checked) WarningNote(warning)
    }
}

/**
 * A switch made of glass (29 Oct): a frosted track with a fine edge, brighter when on, and a glass knob. No accent
 * colour. The row around it handles the touch.
 */
@Composable
private fun GlassToggle(checked: Boolean) {
    val theme = LocalGlassTheme.current
    val shape = RoundedCornerShape(percent = 50)
    val knobX by androidx.compose.animation.core.animateDpAsState(if (checked) 22.dp else 0.dp, label = "knob")
    // Easier to see (29 Oct): off is a darker track with a clear edge, on is a bright track. The knob is solid: a
    // see-through knob let its shadow show through.
    val dark = theme.isDark
    val ink = theme.onGlass
    val track = when {
        checked -> if (dark) Color.White.copy(alpha = 0.55f) else ink.copy(alpha = 0.75f)
        else -> if (dark) Color.Black.copy(alpha = 0.30f) else ink.copy(alpha = 0.14f)
    }
    val edge = if (dark) Color.White else ink
    Box(
        Modifier
            .size(width = 52.dp, height = 30.dp)
            .clip(shape)
            .background(track)
            .border(1.5.dp, edge.copy(alpha = if (checked) 0.9f else 0.6f), shape)
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .offset(x = knobX)
                .size(24.dp)
                .clip(CircleShape)
                .background(if (checked || !dark) Color.White else Color(0xFFE6E8EE))
                .then(if (dark) Modifier else Modifier.border(1.dp, ink.copy(alpha = 0.25f), CircleShape)),
        )
    }
}

@Composable
fun WarningNote(text: String, modifier: Modifier = Modifier) {
    val theme = LocalGlassTheme.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(theme.badgePhoneOnly.copy(alpha = 0.12f))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.Warning, contentDescription = "Warning", tint = theme.onGlass, modifier = Modifier.size(20.dp))
        Text(text, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Where a photo is. Shown on gallery tiles; always has a spoken description. */
enum class BackupStatus(val description: String) {
    BACKED_UP("Backed up"),
    PHONE_ONLY("Only on this phone"),
    ON_DRIVE("Only on the drive"),
}

@Composable
fun StatusBadge(status: BackupStatus, modifier: Modifier = Modifier) {
    val theme = LocalGlassTheme.current
    val (fill, icon) = when (status) {
        BackupStatus.BACKED_UP -> theme.badgeBackedUp to Icons.Rounded.CloudDone
        BackupStatus.PHONE_ONLY -> theme.badgePhoneOnly to Icons.Rounded.PhoneAndroid
        BackupStatus.ON_DRIVE -> theme.badgeOnDrive to Icons.Rounded.CloudQueue
    }
    Box(
        modifier.size(22.dp).clip(CircleShape).background(fill).semantics { contentDescription = status.description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = theme.onBadge, modifier = Modifier.size(14.dp))
    }
}

/** Linear progress on glass. Animates unless reduce motion is on. */
@Composable
fun GlassProgressBar(fraction: Float, modifier: Modifier = Modifier, label: String) {
    val theme = LocalGlassTheme.current
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), motionSpec(400))
    Box(
        modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(Capsule)
            .background(theme.onGlass.copy(alpha = 0.12f))
            .semantics { contentDescription = label },
    ) {
        Box(Modifier.fillMaxWidth(shown).height(10.dp).clip(Capsule).background(theme.accent))
    }
}

/** Round swatch for the theme picker: just the colours, with a white ring and a tick when chosen. [theme] is spoken to screen readers. */
@Composable
fun ThemeSwatch(theme: GlassTheme, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(72.dp)
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = theme.displayName },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(72.dp).border(3.dp, LocalGlassTheme.current.onGlass, CircleShape))
        Box(
            Modifier
                .size(if (selected) 58.dp else 64.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(theme.wallpaper + theme.glassRim))
                .border(1.dp, LocalGlassTheme.current.onGlass.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = theme.onGlass, modifier = Modifier.size(26.dp))
        }
    }
}
