package com.deepak.photovault.ui.shell

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HowToReg
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.deepak.photovault.core.household.HouseholdSettings
import com.deepak.photovault.core.household.OwnerPin
import com.deepak.photovault.designsystem.CollapsibleSection
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassChip
import com.deepak.photovault.designsystem.GlassDialog
import com.deepak.photovault.designsystem.GlassSwitchRow
import com.deepak.photovault.designsystem.GlassTextField
import com.deepak.photovault.designsystem.GlassTheme
import com.deepak.photovault.designsystem.GlassThemes
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.SectionHeader
import com.deepak.photovault.designsystem.ThemeSwatch
import com.deepak.photovault.designsystem.WarningNote
import kotlinx.coroutines.launch

/**
 * Settings in collapsible sections (feedback 22 Oct), in the order people reach for them:
 * Appearance, Drive, Family, About.
 */
@Composable
fun SettingsScreen(state: ShellState, drive: DriveState, iconSwitcher: AppIconSwitcher = NoAppIconSwitcher) {
    GlassListScreen(title = null, subtitle = null) {
        item { AppearanceSection(state, iconSwitcher) }
        item { DriveSection(state, drive) }
        item { FamilySection(drive) }
        item { AboutSection() }
    }
}

@Composable
private fun AppearanceSection(state: ShellState, iconSwitcher: AppIconSwitcher) {
    val theme = LocalGlassTheme.current
    CollapsibleSection("Appearance", Icons.Rounded.Palette, Modifier.cardWidth(), summary = "${theme.displayName} · themes, motion, app icon") {
        SectionHeader("Glass themes")
        ThemeRow(GlassThemes.fixed, state)
        SectionHeader("Adaptive themes")
        Text("The background takes its colours from the photos on screen.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
        ThemeRow(GlassThemes.adaptiveThemes, state)
        Text(theme.bestFor, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
        GlassSwitchRow(
            "Glass blur",
            state.appearance.blurOn,
            { on -> state.updateAppearance { it.copy(blurOn = on) } },
            icon = Icons.Rounded.BlurOn,
            description = "Turns off by itself on battery saver",
        )
        GlassSwitchRow(
            "Reduce motion",
            state.appearance.reduceMotion,
            { on -> state.updateAppearance { it.copy(reduceMotion = on) } },
            icon = Icons.Rounded.Animation,
            description = "Also follows the system setting",
        )
        AppIconPicker(iconSwitcher)
    }
}

@Composable
private fun ThemeRow(themes: List<GlassTheme>, state: ShellState) {
    Row(Modifier.horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        themes.forEach { option ->
            ThemeSwatch(option, selected = option.id == state.theme.id, onClick = { state.updateAppearance { it.copy(themeId = option.id.name) } })
        }
    }
}

/** Leaf (default) or Petals, on Light, Dark, Colourful or Clear. Android 13+ can also theme them. */
@Composable
private fun AppIconPicker(switcher: AppIconSwitcher) {
    val theme = LocalGlassTheme.current
    var current by remember { mutableStateOf(switcher.current()) }
    SectionHeader("App icon")
    IconGlyph.entries.forEach { glyph ->
        Text(glyph.label, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconBackground.entries.forEach { background ->
                val choice = AppIconChoice(glyph, background)
                val selected = choice == current
                Column(
                    Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .selectable(selected = selected, role = Role.RadioButton, onClick = {
                            if (!selected) {
                                switcher.set(choice)
                                current = choice
                            }
                        })
                        .padding(6.dp)
                        .semantics(mergeDescendants = true) { contentDescription = "${glyph.label} icon, ${background.label}" },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(56.dp).then(if (selected) Modifier.border(3.dp, theme.accent, CircleShape) else Modifier).padding(4.dp)) {
                        AppIconPreview(choice, Modifier.fillMaxSize())
                    }
                    Text(background.label, color = if (selected) theme.onGlass else theme.onGlassSecondary, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    Text(
        "Your home screen may take a moment to show a new icon. Turn on themed icons in your phone's wallpaper settings for one that matches your colours.",
        color = theme.onGlassSecondary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun DriveSection(state: ShellState, drive: DriveState) {
    val theme = LocalGlassTheme.current
    val c = drive.connection
    var confirmForget by remember { mutableStateOf(false) }
    CollapsibleSection(
        "Drive",
        Icons.Rounded.Dns,
        Modifier.cardWidth(),
        summary = if (c?.isComplete == true) "${c.host} / ${c.share}" else "Not connected",
    ) {
        if (c?.isComplete == true) {
            InfoRow(Icons.Rounded.Dns, "Address", c.host)
            InfoRow(Icons.Rounded.Storage, "Shared folder", "${c.share} / ${c.folder}")
            drive.problem?.let { WarningNote("Can't reach the drive: $it") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("Change", onClick = { state.open(Overlay.DriveSetup) })
                GlassButton("Forget on this phone", onClick = { confirmForget = true })
            }
        } else {
            Text("Connect the shared drive at home to back up and share with your family.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
            GlassButton("Connect drive", primary = true, onClick = { state.open(Overlay.DriveSetup) }, modifier = Modifier.padding(top = 8.dp))
        }
        InfoRow(Icons.Rounded.VpnKey, "Away from home (VPN)", "Phase 9")
    }
    if (confirmForget) {
        ConfirmDialog(
            title = "Forget this drive?",
            body = "This phone stops using the drive and forgets its password. Nothing on the drive is deleted, and you can connect again later.",
            confirm = "Forget",
            onDismiss = { confirmForget = false },
        ) {
            drive.forget()
            confirmForget = false
        }
    }
}

/** Owner controls (PIN, approving phones, default space). Everyone else sees how the family is set up. */
@Composable
private fun FamilySection(drive: DriveState) {
    val theme = LocalGlassTheme.current
    val scope = rememberCoroutineScope()
    val joined = drive.joined
    val settings = joined?.household?.settings ?: HouseholdSettings()
    var pinFor by remember { mutableStateOf<(() -> Unit)?>(null) }
    var changePin by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun asOwner(action: () -> Unit) {
        if (drive.ownerUnlocked) action() else pinFor = action
    }

    CollapsibleSection(
        "Family",
        Icons.Rounded.Groups,
        Modifier.cardWidth(),
        summary = when {
            joined == null -> "Not joined"
            drive.isOwner -> "You're the owner"
            else -> "Owner: ${joined.household.owner?.name}"
        },
    ) {
        if (joined == null) {
            Text("Connect the drive and join your family to see these.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
            return@CollapsibleSection
        }
        val owner = drive.isOwner
        if (!owner) {
            Text("Only the owner can change these.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        GlassSwitchRow(
            "Owner PIN",
            settings.requirePin,
            { on -> if (owner) asOwner { scope.launch { message = if (on && joined.household.ownerPin == null) "Set a PIN first" else drive.updateSettings { it.copy(requirePin = on) } } } },
            icon = Icons.Rounded.Lock,
            description = "Asked before changing space, removing people or approving phones",
        )
        GlassSwitchRow(
            "Approve new phones",
            settings.approveNewPhones,
            { on -> if (owner) asOwner { scope.launch { message = drive.updateSettings { it.copy(approveNewPhones = on) } } } },
            icon = Icons.Rounded.HowToReg,
            description = if (settings.approveNewPhones) "New phones wait for the owner" else "Anyone with the drive password joins straight away",
        )
        SectionHeader("Space for new people")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(25, 50, 100, 200, 500).forEach { gb ->
                GlassChip("$gb GB", selected = settings.defaultLimitGb == gb, onClick = {
                    if (owner) asOwner { scope.launch { message = drive.updateSettings { it.copy(defaultLimitGb = gb) } } }
                })
            }
        }
        if (owner) {
            GlassButton(
                if (joined.household.ownerPin == null) "Set owner PIN" else "Change owner PIN",
                icon = Icons.Rounded.Password,
                onClick = { asOwner { changePin = true } },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        message?.let { WarningNote(it) }
    }

    pinFor?.let { action ->
        PinDialog(drive, onDismiss = { pinFor = null }, onUnlocked = {
            pinFor = null
            action()
        })
    }
    if (changePin) {
        NewPinDialog(onDismiss = { changePin = false }) { pin ->
            scope.launch {
                message = drive.setPin(pin)
                changePin = false
            }
        }
    }
}

@Composable
private fun NewPinDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    GlassDialog(
        title = "New owner PIN",
        onDismiss = onDismiss,
        buttons = {
            GlassButton("Cancel", onClick = onDismiss)
            GlassButton("Save", primary = true, onClick = {
                error = when {
                    !OwnerPin.isValid(pin) -> "The PIN must be 4 to 8 digits."
                    pin != confirm -> "The PINs don't match."
                    else -> null
                }
                if (error == null) onSave(pin)
            })
        },
    ) {
        GlassTextField(pin, { if (it.length <= OwnerPin.MAX_DIGITS) pin = it.filter(Char::isDigit) }, "New PIN", password = true, keyboardType = KeyboardType.NumberPassword)
        GlassTextField(confirm, { if (it.length <= OwnerPin.MAX_DIGITS) confirm = it.filter(Char::isDigit) }, "Confirm", password = true, keyboardType = KeyboardType.NumberPassword)
        error?.let { WarningNote(it) }
    }
}

@Composable
private fun AboutSection() {
    CollapsibleSection("About and coming soon", Icons.Rounded.Info, Modifier.cardWidth(), summary = "What's next for PhotoVault") {
        InfoRow(Icons.Rounded.Schedule, "Backup to the drive", "Phase 4")
        InfoRow(Icons.Rounded.Storage, "Phone and drive in one timeline", "Phase 5")
        InfoRow(Icons.Rounded.Lock, "Secured folder", "Phase 8")
        InfoRow(Icons.Rounded.VpnKey, "Remote access (VPN)", "Phase 9")
    }
}
