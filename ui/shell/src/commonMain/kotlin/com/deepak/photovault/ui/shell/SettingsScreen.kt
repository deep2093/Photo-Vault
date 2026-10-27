package com.deepak.photovault.ui.shell

import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
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
import androidx.compose.material.icons.rounded.Feedback
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import com.deepak.photovault.designsystem.GlassCard
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.ZoomIn
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
 * The Settings pages, listed in this order: alphabetical, then Send feedback, then About always last (23 Oct).
 * Each opens on its own, like Backup.
 */
enum class SettingsPage(val title: String) {
    ALBUMS("Albums"),
    APPEARANCE("Appearance"),
    BACKUP("Backup"),
    DRIVE("Drive"),
    REMOTE("Remote access"),
    STORAGE("Storage"),
    // The last three stay at the end, in this order, in every build (25 Oct); the rest are alphabetical.
    OPEN_WITH("Open with Pixallery"),
    FEEDBACK("Send feedback"),
    ABOUT("About"),
}

/** Settings: one short row per page, alphabetical (24 Oct). Family settings live in Family (owner only). */
@Composable
fun SettingsScreen(
    state: ShellState,
    drive: DriveState,
    iconSwitcher: AppIconSwitcher = NoAppIconSwitcher,
    backup: BackupState? = null,
    gallery: GalleryState? = null,
) {
    val theme = LocalGlassTheme.current
    GlassListScreen(title = null, subtitle = null) {
        item {
            Column(Modifier.cardWidth().glassPanel(com.deepak.photovault.designsystem.GlassCardShape).padding(vertical = 6.dp)) {
                SettingsPage.entries.forEach { page ->
                    val (icon, summary) = when (page) {
                        SettingsPage.ABOUT -> Icons.Rounded.Info to "What's coming next"
                        SettingsPage.ALBUMS -> Icons.Rounded.Collections to (gallery?.hiddenAlbums?.size?.takeIf { it > 0 }?.let { "$it hidden" } ?: "Show or hide albums")
                        SettingsPage.APPEARANCE -> Icons.Rounded.Palette to theme.displayName
                        SettingsPage.BACKUP -> Icons.Rounded.Backup to backupSummary(backup)
                        SettingsPage.DRIVE -> Icons.Rounded.Dns to (drive.connection?.takeIf { it.isComplete }?.let { "${it.host} / ${it.share}" } ?: "Not connected")
                        SettingsPage.OPEN_WITH -> Icons.AutoMirrored.Rounded.OpenInNew to "Photos and videos from other apps"
                        SettingsPage.REMOTE -> Icons.Rounded.VpnKey to state.remote.summary()
                        SettingsPage.STORAGE -> Icons.Rounded.PieChart to "Phone and drive"
                        SettingsPage.FEEDBACK -> Icons.Rounded.Feedback to (if (drive.feedbackWaiting > 0) "${drive.feedbackWaiting} waiting to send" else "Ideas and bugs, with photos or videos")
                    }
                    SettingsRow(icon, page.title, summary) {
                        if (page == SettingsPage.BACKUP) state.open(Overlay.Backup) else state.open(Overlay.Settings(page))
                    }
                }
            }
        }
    }
}

private fun backupSummary(backup: BackupState?): String {
    if (backup == null) return ""
    val last = backup.history.firstOrNull()
    return when {
        backup.running -> backup.progress?.let { "Backing up ${it.done} of ${it.total}" } ?: "Backing up"
        last == null -> "Not backed up yet"
        last.succeeded -> "Last ${whenText(last.finishedAtMillis, backup.now())}"
        else -> "Last backup didn't finish"
    }
}

@Composable
private fun SettingsRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, summary: String, onClick: () -> Unit) {
    val theme = LocalGlassTheme.current
    val haptics = com.deepak.photovault.designsystem.rememberHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(onClickLabel = "Open $title") {
                haptics.tap()
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(icon, contentDescription = null, tint = theme.accent)
        androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = theme.onGlass, style = MaterialTheme.typography.bodyLarge)
            if (summary.isNotEmpty()) {
                Text(summary, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        androidx.compose.material3.Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = theme.onGlassSecondary)
    }
}

/** One Settings page on its own, with a back button. */
@Composable
fun SettingsPageScreen(page: SettingsPage, state: ShellState, drive: DriveState, iconSwitcher: AppIconSwitcher, gallery: GalleryState, actions: MediaActions, backup: BackupState, startOnDrive: Boolean = false) {
    GlassListScreen(title = page.title, subtitle = null, onBack = { state.back() }) {
        when (page) {
            SettingsPage.ABOUT -> item { AboutSection(state) }
            SettingsPage.ALBUMS -> albumsPage(gallery)
            SettingsPage.APPEARANCE -> item { AppearanceSection(state, iconSwitcher) }
            SettingsPage.BACKUP -> Unit
            SettingsPage.DRIVE -> item { DriveSection(state, drive) }
            SettingsPage.REMOTE -> item { RemoteAccessContent(state.remote, backup) }
            SettingsPage.OPEN_WITH -> item { OpenWithContent(actions) }
            SettingsPage.STORAGE -> item { StorageContent(state, gallery, drive, actions, startOnDrive) }
            SettingsPage.FEEDBACK -> item { FeedbackContent(drive, actions) }
        }
    }
}

/** On a page of its own the section is a plain card (no opening and closing). */
@Composable
private fun PageCard(
    @Suppress("UNUSED_PARAMETER") title: String,
    @Suppress("UNUSED_PARAMETER") icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier,
    @Suppress("UNUSED_PARAMETER") summary: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) = GlassCard(modifier, content = content)

@Composable
private fun AppearanceSection(state: ShellState, iconSwitcher: AppIconSwitcher) {
    val theme = LocalGlassTheme.current
    PageCard("Appearance", Icons.Rounded.Palette, Modifier.cardWidth(), summary = theme.displayName) {
        SectionHeader("Light themes")
        ThemeRow(GlassThemes.lightThemes, state)
        SectionHeader("Dark themes")
        ThemeRow(GlassThemes.darkThemes, state)
        Text(theme.bestFor, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
        GlassSwitchRow(
            "Glass blur",
            state.appearance.blurOn,
            { on -> state.updateAppearance { it.copy(blurOn = on) } },
            icon = Icons.Rounded.BlurOn,
            description = "Turns off by itself on battery saver",
        )
        if (theme.lens && lensGlassSupported()) {
            GlassSwitchRow(
                "Lens glass (experimental)",
                state.appearance.lensGlass,
                { on -> state.updateAppearance { it.copy(lensGlass = on) } },
                icon = Icons.Rounded.ZoomIn,
                description = "The bars and pills bend and magnify the photos behind them, like thick glass",
            )
        }
        GlassSwitchRow(
            "Keep the bottom bar in place",
            state.appearance.pinBottomBar,
            { on -> state.updateAppearance { it.copy(pinBottomBar = on) } },
            icon = Icons.Rounded.PushPin,
            description = "The bar stays put instead of tucking away while you scroll",
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
    PageCard(
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
        InfoRow(Icons.Rounded.VpnKey, "Away from home (VPN)", "See Remote access")
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
private fun AboutSection(state: ShellState) {
    PageCard("About", Icons.Rounded.Info, Modifier.cardWidth()) {
        SettingsRow(Icons.Rounded.AutoAwesome, "What's new", "See what changed in this update") { state.intro.open(IntroKind.WHATS_NEW) }
        SettingsRow(Icons.Rounded.Explore, "Welcome tour", "A quick look around the app") { state.intro.open(IntroKind.TOUR) }
        InfoRow(Icons.Rounded.Storage, "Phone and drive in one timeline", "Done")
        InfoRow(Icons.Rounded.Groups, "Family Album", "Done")
        InfoRow(Icons.Rounded.VpnKey, "Away from home (VPN)", "Done")
        InfoRow(Icons.Rounded.PersonAdd, "Invites", "Done")
        InfoRow(Icons.AutoMirrored.Rounded.OpenInNew, "Open with Pixallery", "Done")
        InfoRow(Icons.Rounded.Schedule, "Free up space", "Phase 6")
        InfoRow(Icons.Rounded.Place, "Places and people", "Phase 7")
        InfoRow(Icons.Rounded.Lock, "Secured folder", "Phase 8")
    }
}

/** Settings > Open with Pixallery: Android lets only the person choose "Always", so this explains and goes there. */
@Composable
private fun OpenWithContent(actions: MediaActions) {
    val theme = LocalGlassTheme.current
    PageCard("Open photos and videos with Pixallery", Icons.AutoMirrored.Rounded.OpenInNew, Modifier.cardWidth()) {
        Text(
            "Pixallery is offered whenever another app opens a photo or video: Files, My Files, WhatsApp, Chrome downloads, or Share > Open with. " +
                "It opens in Pixallery's own viewer. If the file is in your gallery you can swipe to the others in its folder.",
            color = theme.onGlassSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("To make it the one that always opens:", color = theme.onGlass, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        listOf(
            "Tap the button below to open Android's default apps.",
            "Choose Gallery (or Photos), then Pixallery.",
            "Or open any photo from Files once and pick Pixallery, then tap Always.",
        ).forEachIndexed { i, step ->
            Text("${i + 1}. $step", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        }
        Text(
            "Android lets only you choose \"Always\"; an app can't set itself. Some camera apps (Samsung Camera's preview button) always open their own gallery, which Pixallery can't change.",
            color = theme.onGlassSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 10.dp),
        )
        GlassButton("Open Android settings", primary = true, onClick = actions::openDefaultAppSettings, modifier = Modifier.padding(top = 10.dp))
    }
}
