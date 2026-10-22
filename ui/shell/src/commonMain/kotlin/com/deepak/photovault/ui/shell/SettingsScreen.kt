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
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.ViewDay
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
import com.deepak.photovault.designsystem.glassPanel
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
 * Appearance, Drive, About. Family settings moved to the Family section (owner only, 24 Oct).
 */
@Composable
fun SettingsScreen(
    state: ShellState,
    drive: DriveState,
    iconSwitcher: AppIconSwitcher = NoAppIconSwitcher,
    backup: BackupState? = null,
    gallery: GalleryState? = null,
) {
    GlassListScreen(title = null, subtitle = null) {
        // Backup moved here from the navigation bar (24 Oct); it opens its own page.
        if (backup != null) item { BackupEntry(state, backup) }
        item { AppearanceSection(state, iconSwitcher) }
        if (gallery != null) item { HiddenAlbumsSection(gallery) }
        item { DriveSection(state, drive) }
        item { AboutSection() }
    }
}

/** One row that opens Backup, showing how it is going. */
@Composable
private fun BackupEntry(state: ShellState, backup: BackupState) {
    val theme = LocalGlassTheme.current
    val last = backup.history.firstOrNull()
    androidx.compose.foundation.layout.Row(
        Modifier
            .cardWidth()
            .glassPanel(com.deepak.photovault.designsystem.GlassCardShape)
            .clickable(onClickLabel = "Open Backup") { state.open(Overlay.Backup) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(Icons.Rounded.Backup, contentDescription = null, tint = theme.accent)
        androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text("Backup", color = theme.onGlass, style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    backup.running -> backup.progress?.let { "Backing up ${it.done} of ${it.total}" } ?: "Backing up"
                    last == null -> "Not backed up yet"
                    last.succeeded -> "Last backup ${whenText(last.finishedAtMillis, backup.now())}"
                    else -> "Last backup didn't finish"
                },
                color = theme.onGlassSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        androidx.compose.material3.Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = theme.onGlassSecondary)
    }
}

/**
 * Hide albums (24 Oct). A hidden album's photos leave Photos, Albums and search, after a pop-up says so. They stay on
 * the phone and still back up.
 */
@Composable
private fun HiddenAlbumsSection(gallery: GalleryState) {
    val theme = LocalGlassTheme.current
    var confirm by remember { mutableStateOf<com.deepak.photovault.core.media.PhoneAlbum?>(null) }
    val hidden = gallery.hiddenAlbums.size
    CollapsibleSection(
        "Albums",
        Icons.Rounded.VisibilityOff,
        Modifier.cardWidth(),
        summary = if (hidden == 0) "Hide albums" else "$hidden hidden",
    ) {
        Text("Hidden albums don't show in Photos or Albums.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
        gallery.allAlbums.forEach { album ->
            val isHidden = album.id in gallery.hiddenAlbums
            GlassSwitchRow(
                album.name,
                !isHidden,
                { show -> if (show) gallery.setHidden(album.id, false) else confirm = album },
                description = if (isHidden) "Hidden" else "${com.deepak.photovault.core.media.LibraryCounts.grouped(album.count)} items",
            )
        }
    }
    confirm?.let { album ->
        ConfirmDialog(
            title = "Hide ${album.name}?",
            body = "Its photos will be hidden from both Photos and Albums. They stay on your phone and still back up. You can show it again here.",
            confirm = "Hide",
            onDismiss = { confirm = null },
        ) {
            gallery.setHidden(album.id, true)
            confirm = null
        }
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
            ThemeSwatch(option, selected = option.id == state.theme.id, onClick = { state.updateAppearance { it.copy(themeId = option.id.name, themeChosen = true) } })
        }
    }
}

/** Four Seasons (default), Leaf or Petals, on Light, Dark, Colourful or Clear. Android 13+ can also theme them. */
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
            InfoRow(Icons.Rounded.Storage, "Shared folder", c.share)
            InfoRow(Icons.Rounded.Folder, "Pixallery folder", c.folder)
            Text(
                "Every phone in the family must use the same shared folder and Pixallery folder. Compare these on each phone if one can't find the family.",
                color = theme.onGlassSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            drive.problem?.let { WarningNote("Can't reach the drive: $it") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("Change", onClick = { state.open(Overlay.DriveSetup) })
                GlassButton("Forget on this phone", onClick = { confirmForget = true })
            }
        } else {
            Text("Connect the shared drive at home to back up and share with your family.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
            GlassButton("Connect drive", primary = true, onClick = { state.open(Overlay.DriveSetup) }, modifier = Modifier.padding(top = 8.dp))
        }
        InfoRow(Icons.Rounded.VpnKey, "Away from home (VPN)", "Phase 5")
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

@Composable
private fun AboutSection() {
    CollapsibleSection("About and coming soon", Icons.Rounded.Info, Modifier.cardWidth(), summary = "What's next for $APP_NAME") {
        InfoRow(Icons.Rounded.Storage, "Phone and drive in one timeline", "Phase 5")
        InfoRow(Icons.Rounded.VpnKey, "Away from home (VPN) and invites", "Phase 5")
        InfoRow(Icons.Rounded.Schedule, "Free up space", "Phase 6")
        InfoRow(Icons.Rounded.Lock, "Secured folder", "Phase 8")
    }
}
