package com.deepak.photovault.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        // Primary colour: headers also sit straight on the wallpaper (photo dates), including adaptive wallpapers.
        color = LocalGlassTheme.current.onGlass,
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
) {
    val theme = LocalGlassTheme.current
    val shape = Capsule
    Row(
        modifier
            .heightIn(min = MinTouchTarget)
            .then(if (primary) Modifier.clip(shape).background(theme.accent) else Modifier.glassPanel(shape))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val fg = if (primary) theme.onAccent else theme.onGlass
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun GlassIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalGlassTheme.current
    Box(
        modifier
            .size(MinTouchTarget)
            .glassPanel(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = theme.onGlass, modifier = Modifier.iconHalo())
    }
}

/** Single-choice chip (for example Day / Month / Year). */
@Composable
fun GlassChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalGlassTheme.current
    val background by animateColorAsState(if (selected) theme.accent else Color.Transparent, motionSpec())
    Box(
        modifier
            .heightIn(min = MinTouchTarget)
            .padding(vertical = 4.dp)
            .glassPanel(Capsule)
            .background(background)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
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
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = theme.onGlassSecondary)
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = theme.onGlass, style = MaterialTheme.typography.bodyLarge)
                if (description != null) {
                    Text(description, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.width(12.dp))
            // The row handles the click, so the switch itself is not separately focusable.
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = theme.onAccent,
                    checkedTrackColor = theme.accent,
                    uncheckedThumbColor = theme.onGlassSecondary,
                    uncheckedTrackColor = theme.glassFallback,
                    uncheckedBorderColor = theme.onGlassSecondary,
                ),
            )
        }
        if (warning != null && checked) WarningNote(warning)
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

/** Small round swatch used in the theme picker. */
@Composable
fun ThemeSwatch(theme: GlassTheme, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val current = LocalGlassTheme.current
    Column(
        modifier
            .sizeIn(minWidth = 88.dp)
            .clip(RoundedCornerShape(20.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(theme.wallpaper + theme.accent))
                .then(if (selected) Modifier.glassRim(CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(theme.accent))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            theme.displayName,
            color = if (selected) current.onGlass else current.onGlassSecondary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
