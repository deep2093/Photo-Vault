package com.deepak.photovault.ui.shell

import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Notifications
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
import androidx.compose.material.icons.rounded.BlurOff
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
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.HowToReg
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.ViewSidebar
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt
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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
    APPEARANCE("Appearance"),
    BACKUP("Backup"),
    DRIVE("Drive"),
    NOTIFICATIONS("Notifications"),
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
    actions: MediaActions? = null,
) {
    val theme = LocalGlassTheme.current
    // 13 Nov: on a tablet the list stays on the left and the chosen page opens beside it.
    if (LocalTablet.current && backup != null && gallery != null && actions != null) {
        SettingsTwoPane(state, drive, iconSwitcher, backup, gallery, actions)
        return
    }
    GlassListScreen(title = null, subtitle = null) {
        item {
            // 13 Nov: two columns of rows on a tablet, so Settings uses the width.
            val tablet = LocalTablet.current
            Column(Modifier.pageWidth().glassPanel(com.deepak.photovault.designsystem.GlassCardShape).padding(vertical = 6.dp)) {
                SettingsPage.entries.chunked(if (tablet) 2 else 1).forEach { pair ->
                Row(Modifier.fillMaxWidth()) {
                pair.forEach { page ->
                    val (icon, summary) = when (page) {
                        SettingsPage.ABOUT -> Icons.Rounded.Info to "What's coming next"
                        SettingsPage.APPEARANCE -> Icons.Rounded.Palette to appearanceSummary(state)
                        SettingsPage.NOTIFICATIONS -> Icons.Rounded.Notifications to notificationsSummary(state, backup)
                        SettingsPage.BACKUP -> Icons.Rounded.Backup to backupSummary(backup)
                        SettingsPage.DRIVE -> Icons.Rounded.Dns to (drive.connection?.takeIf { it.isComplete }?.let { "${it.host} / ${it.share}" } ?: "Not connected")
                        SettingsPage.OPEN_WITH -> Icons.AutoMirrored.Rounded.OpenInNew to "Photos and videos from other apps"
                        SettingsPage.REMOTE -> Icons.Rounded.VpnKey to state.remote.summary()
                        SettingsPage.STORAGE -> Icons.Rounded.PieChart to "Phone and drive"
                        SettingsPage.FEEDBACK -> Icons.Rounded.Feedback to (if (drive.feedbackWaiting > 0) "${drive.feedbackWaiting} waiting to send" else "Ideas and bugs, with photos or videos")
                    }
                    SettingsRow(icon, page.title, summary, Modifier.weight(1f)) {
                        if (page == SettingsPage.BACKUP) state.open(Overlay.Backup) else state.open(Overlay.Settings(page))
                    }
                }
                repeat((if (tablet) 2 else 1) - pair.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
                }
                }
            }
        }
    }
}

/** What each Settings row shows: its picture and the short line under its name. */
@Composable
private fun settingsRowInfo(page: SettingsPage, state: ShellState, drive: DriveState, backup: BackupState?): Pair<androidx.compose.ui.graphics.vector.ImageVector, String> {
    return when (page) {
        SettingsPage.ABOUT -> Icons.Rounded.Info to "What's coming next"
        SettingsPage.APPEARANCE -> Icons.Rounded.Palette to appearanceSummary(state)
        SettingsPage.NOTIFICATIONS -> Icons.Rounded.Notifications to notificationsSummary(state, backup)
        SettingsPage.BACKUP -> Icons.Rounded.Backup to backupSummary(backup)
        SettingsPage.DRIVE -> Icons.Rounded.Dns to (drive.connection?.takeIf { it.isComplete }?.let { "${it.host} / ${it.share}" } ?: "Not connected")
        SettingsPage.OPEN_WITH -> Icons.AutoMirrored.Rounded.OpenInNew to "Photos and videos from other apps"
        SettingsPage.REMOTE -> Icons.Rounded.VpnKey to state.remote.summary()
        SettingsPage.STORAGE -> Icons.Rounded.PieChart to "Phone and drive"
        SettingsPage.FEEDBACK -> Icons.Rounded.Feedback to (if (drive.feedbackWaiting > 0) "${drive.feedbackWaiting} waiting to send" else "Ideas and bugs, with photos or videos")
    }
}

/**
 * Settings on a tablet (13 Nov): the list on the left, the chosen page beside it, each scrolling on its own. Backup
 * still opens as its own full page (it is a big screen with its own bar).
 */
@Composable
private fun SettingsTwoPane(state: ShellState, drive: DriveState, iconSwitcher: AppIconSwitcher, backup: BackupState, gallery: GalleryState, actions: MediaActions) {
    val theme = LocalGlassTheme.current
    var chosen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(SettingsPage.APPEARANCE.name) }
    val page = SettingsPage.entries.firstOrNull { it.name == chosen } ?: SettingsPage.APPEARANCE
    GlassScreen(title = null, subtitle = null) { padding ->
        // 13 Nov (Deepak's tablet photos): the list and the page sit together in the middle of the screen, instead of
        // the list hard left and the page far to the right.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Row(Modifier.widthIn(max = 1140.dp).fillMaxSize()) {
                androidx.compose.foundation.lazy.LazyColumn(
                    Modifier.width(340.dp).fillMaxHeight(),
                    contentPadding = padding,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            "Settings",
                            color = theme.onWallpaper,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 8.dp, top = 4.dp).semantics { heading() },
                        )
                    }
                    item {
                        Column(
                            Modifier.fillMaxWidth().glassPanel(com.deepak.photovault.designsystem.GlassCardShape).padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            SettingsPage.entries.forEach { p ->
                                val (icon, summary) = settingsRowInfo(p, state, drive, backup)
                                SettingsPaneRow(icon, p.title, summary, selected = p == page) { chosen = p.name }
                            }
                        }
                    }
                }
                // 18 Nov (Deepak: "the menu and its pages aren't even"): the page's cards fill their column, so the gap
                // between the list and the page matches the margins, and every page has the same width.
                androidx.compose.animation.Crossfade(page, Modifier.weight(1f).fillMaxHeight(), animationSpec = com.deepak.photovault.designsystem.motionSpec(), label = "settingsPane") { shown ->
                  androidx.compose.runtime.CompositionLocalProvider(LocalCardMaxWidth provides androidx.compose.ui.unit.Dp.Infinity) {
                    if (shown == SettingsPage.BACKUP) {
                        // Backup opens beside the list too (13 Nov), not as a page of its own.
                        BackupScreen(drive, backup, gallery, actions, state, onBack = null, heading = "Backup")
                    } else {
                        androidx.compose.foundation.lazy.LazyColumn(
                            Modifier.fillMaxSize().imePadding(),
                            contentPadding = padding,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            item {
                                Text(
                                    shown.title,
                                    color = theme.onWallpaper,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                    modifier = Modifier.cardWidth().padding(start = 4.dp, top = 4.dp).semantics { heading() },
                                )
                            }
                            settingsPageItems(shown, state, drive, iconSwitcher, gallery, actions, backup, startOnDrive = false)
                        }
                    }
                  }
                }
            }
        }
    }
}

/**
 * One row of the tablet's Settings list (13 Nov): its picture in a soft round, and the chosen one filled with the
 * theme's own colour (with words that stay readable on it), so it is clear which page is open beside the list.
 */
@Composable
private fun SettingsPaneRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, summary: String, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalGlassTheme.current
    val haptics = com.deepak.photovault.designsystem.rememberHaptics()
    val fill by androidx.compose.animation.animateColorAsState(if (selected) theme.accent else androidx.compose.ui.graphics.Color.Transparent, com.deepak.photovault.designsystem.motionSpec(), label = "paneRow")
    val ink = if (selected) com.deepak.photovault.designsystem.readableOn(theme.accent) else theme.onGlass
    val soft = if (selected) ink.copy(alpha = 0.8f) else theme.onGlassSecondary
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(fill)
            .semantics { this.selected = selected }
            .heightIn(min = 60.dp)
            .clickable(role = androidx.compose.ui.semantics.Role.Tab, onClickLabel = "Open $title") {
                haptics.tap()
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (selected) ink.copy(alpha = 0.16f) else theme.accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(icon, contentDescription = null, tint = if (selected) ink else theme.accent, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, color = ink, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold else null)
            if (summary.isNotEmpty()) {
                Text(summary, color = soft, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.settingsPageItems(
    page: SettingsPage,
    state: ShellState,
    drive: DriveState,
    iconSwitcher: AppIconSwitcher,
    gallery: GalleryState,
    actions: MediaActions,
    backup: BackupState,
    startOnDrive: Boolean,
) {
    when (page) {
        SettingsPage.ABOUT -> item { AboutSection(state) }
        SettingsPage.APPEARANCE -> item { AppearanceSection(state, iconSwitcher) }
        SettingsPage.NOTIFICATIONS -> item { NotificationsSection(state, drive, backup, actions) }
        SettingsPage.BACKUP -> Unit
        SettingsPage.DRIVE -> item { DriveSection(state, drive) }
        SettingsPage.REMOTE -> item { RemoteAccessContent(state.remote, backup) }
        SettingsPage.OPEN_WITH -> item { OpenWithContent(actions) }
        SettingsPage.STORAGE -> item { StorageContent(state, gallery, drive, actions, startOnDrive, backup) }
        SettingsPage.FEEDBACK -> item { FeedbackContent(drive, actions) }
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
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    /** The page showing beside the list on a tablet: softly lit in the theme's colour. */
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val theme = LocalGlassTheme.current
    val haptics = com.deepak.photovault.designsystem.rememberHaptics()
    Row(
        modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .then(if (selected) Modifier.background(theme.accent.copy(alpha = 0.16f)) else Modifier)
            .semantics { this.selected = selected }
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
    // Pull down on the drive pages to read the drive again (30 Oct).
    val refresh: (suspend () -> Unit)? = if (page == SettingsPage.STORAGE || page == SettingsPage.DRIVE) ({
        if (page == SettingsPage.STORAGE) gallery.refresh()
        drive.refresh()
        if (drive.joined != null) {
            drive.loadBackupRecords(force = true)
            drive.refreshUsage(force = true)
        }
    }) else null
    GlassListScreen(title = page.title, subtitle = null, onBack = { state.back() }, onRefresh = refresh) {
        settingsPageItems(page, state, drive, iconSwitcher, gallery, actions, backup, startOnDrive)
    }
}

private fun notificationsSummary(state: ShellState, backup: BackupState?): String {
    if (backup?.notificationsAllowed() == false) return "Off for Pixallery"
    val a = state.appearance
    val on = listOf(a.notifyBackupProblems, a.memoriesNotice, a.notifyPeople, a.notifyFamily, a.notifyFeedback).count { it }
    return if (on == 5) "All on" else "$on of 5 on"
}

/**
 * Settings > Notifications (8 Nov): every kind Pixallery can show, each with its own switch. The ones Android needs
 * while work runs (backing up, restoring, a face scan you started) can't be turned off, so they are only listed.
 */
@Composable
private fun NotificationsSection(state: ShellState, drive: DriveState, backup: BackupState, actions: MediaActions) {
    val theme = LocalGlassTheme.current
    val a = state.appearance
    // Checked again every moment, so coming back from Android's settings updates it.
    var allowed by remember { androidx.compose.runtime.mutableStateOf(backup.notificationsAllowed()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            allowed = backup.notificationsAllowed()
            kotlinx.coroutines.delay(1500)
        }
    }
    Column(Modifier.cardWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!allowed) {
            GlassCard(Modifier.fillMaxWidth()) {
                com.deepak.photovault.designsystem.WarningNote("Notifications are off for Pixallery in Android's settings, so none of these can show.")
                GlassButton("Allow notifications", primary = true, onClick = actions::requestNotifications, modifier = Modifier.padding(top = 4.dp))
            }
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Text("Choose what Pixallery tells you about.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
            GlassSwitchRow(
                "Backup problems",
                a.notifyBackupProblems,
                { on -> state.updateAppearance { it.copy(notifyBackupProblems = on) } },
                icon = Icons.Rounded.Backup,
                description = "When a scheduled backup doesn't finish after two tries.",
            )
            GlassSwitchRow(
                "On this day",
                a.memoriesNotice,
                { on -> state.updateAppearance { it.copy(memoriesNotice = on) } },
                icon = Icons.Rounded.History,
                description = "A short note in the morning when you have photos from this day in earlier years.",
            )
            GlassSwitchRow(
                "People: same person?",
                a.notifyPeople,
                { on -> state.updateAppearance { it.copy(notifyPeople = on) } },
                icon = Icons.Rounded.Groups,
                description = "When Pixallery isn't sure if some faces are the same person. Once a day at most.",
            )
            if (drive.isOwner) {
                GlassSwitchRow(
                    "Family requests",
                    a.notifyFamily,
                    { on -> state.updateAppearance { it.copy(notifyFamily = on) } },
                    icon = Icons.Rounded.PersonAdd,
                    description = "When a phone wants to join the family or someone asks for more space.",
                )
                GlassSwitchRow(
                    "Feedback from the family",
                    a.notifyFeedback,
                    { on -> state.updateAppearance { it.copy(notifyFeedback = on) } },
                    icon = Icons.Rounded.Feedback,
                    description = "When someone in the family sends an idea or a bug report.",
                )
            }
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Text("Always shown while working", color = theme.onGlass, style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Text(
                "Android needs these so the work isn't stopped: backing up, bringing photos back from the drive, and looking for faces after you tap Scan. They go away when the work is done.",
                color = theme.onGlassSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
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
    PageCard("Appearance", Icons.Rounded.Palette, Modifier.cardWidth(), summary = appearanceSummary(state)) {
        ThemeGrid(state)
        GlassSwitchRow(
            // 21 Nov: "Glass effect", on when the glass is on. It read "Turn off glass effect" while on, backwards.
            "Glass effect",
            state.appearance.blurOn,
            { on -> state.updateAppearance { it.copy(blurOn = on) } },
            icon = Icons.Rounded.BlurOn,
        )
        // Liquid Glass never blurs (21 Nov): it is clear glass that bends the light, so it has no Blur slider.
        if (state.appearance.blurOn && !LocalGlassTheme.current.isLiquidGlass) BlurSlider(state)
        // Tablets only (18 Nov): the side bar, on by default; off brings back the floating bar at the bottom.
        val tabletDevice = LocalTabletDevice.current
        if (tabletDevice) {
            GlassSwitchRow(
                "Side bar",
                state.appearance.tabletSideBar,
                { on -> state.updateAppearance { it.copy(tabletSideBar = on) } },
                description = "The sections down the left side of this tablet. Off: the floating bar at the bottom, as on phones.",
                icon = Icons.Rounded.ViewSidebar,
            )
        }
        if (!tabletDevice || !state.appearance.tabletSideBar) {
            GlassSwitchRow(
                "Keep the bottom bar in place",
                state.appearance.pinBottomBar,
                { on -> state.updateAppearance { it.copy(pinBottomBar = on) } },
                icon = Icons.Rounded.PushPin,
            )
        }
        GlassSwitchRow(
            "Mixed photo sizes",
            state.appearance.mixedGrid,
            { on -> state.updateAppearance { it.copy(mixedGrid = on) } },
            description = "In Photos > All, some photos are shown bigger than the rest. Off: every photo the same size.",
            icon = Icons.Rounded.PhotoLibrary,
        )
        GlassSwitchRow(
            "Reduce motion",
            state.appearance.reduceMotion,
            { on -> state.updateAppearance { it.copy(reduceMotion = on) } },
            icon = Icons.Rounded.Animation,
        )
        AppIconPicker(iconSwitcher)
    }
}

/** Every theme as a round swatch, named underneath; the chosen one has a ring and a tick. */
@Composable
private fun ThemeGrid(state: ShellState) {
    val theme = LocalGlassTheme.current
    val night = state.nightNow
    val chosen = state.themeFamily
    // 18 Nov: each theme shown in the version you'd see now (day or night), named underneath.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 20 Nov (five themes with Pearl): as many as fit side by side, then the rows evened out and the shorter one
        // centred, so a phone shows three and two instead of four and one left alone.
        val families = GlassThemes.families
        val fit = (maxWidth / THEME_CELL).toInt().coerceIn(2, families.size)
        val rows = (families.size + fit - 1) / fit
        val perRow = (families.size + rows - 1) / rows
        val cell = maxWidth / perRow
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            families.chunked(perRow).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    row.forEach { family ->
                        Column(Modifier.width(cell), horizontalAlignment = Alignment.CenterHorizontally) {
                            ThemeSwatch(
                                family.pick(night),
                                selected = family.key == chosen.key,
                                onClick = { state.updateAppearance { it.copy(themeId = family.day.id.name, themeChosen = true) } },
                            )
                            Text(
                                family.name,
                                color = if (family.key == chosen.key) theme.onGlass else theme.onGlassSecondary,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(top = 4.dp).clearAndSetSemantics { },
                            )
                        }
                    }
                }
            }
        }
    }
    // What the chosen theme is like, day and night, so they are easy to tell apart.
    Text(
        "${chosen.name}: ${chosen.about}",
        color = theme.onGlassSecondary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
    // Day or night (18 Nov, Deepak: "one theme can be switched to night and day"): as the phone, or always one.
    val modes = ThemeMode.entries
    com.deepak.photovault.designsystem.GlassSegmented(
        modes.map { it.label },
        modes.indexOfFirst { it.name == state.appearance.mode }.coerceAtLeast(0),
        { i -> state.updateAppearance { it.copy(mode = modes[i].name) } },
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
    )
    // Clear Glass (21 Nov): iOS 26.1's choice, Clear or Tinted.
    if (chosen.day.isClearGlass) {
        com.deepak.photovault.designsystem.GlassSegmented(
            listOf("Clear", "Tinted"),
            if (state.appearance.glassTinted) 1 else 0,
            { i -> state.updateAppearance { it.copy(glassTinted = i == 1) } },
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        )
        Text(
            "Clear lets the photos show through the bars and menus. Tinted makes them almost solid, as on iOS 26.1.",
            color = theme.onGlassSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Settings' one-line summary of the look: the theme, then As the phone, Day or Night. */
internal fun appearanceSummary(state: ShellState): String {
    val mode = ThemeMode.entries.firstOrNull { it.name == state.appearance.mode } ?: ThemeMode.SYSTEM
    return "${state.themeFamily.name} · ${mode.label}"
}

/** How much the glass blurs what is behind it (not shown for Liquid Glass, which never blurs). */
@Composable
private fun BlurSlider(state: ShellState) {
    val theme = LocalGlassTheme.current
    val step = state.appearance.blurStep.coerceIn(0, 4)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.BlurOn, contentDescription = null, tint = theme.onGlassSecondary)
        Spacer(Modifier.width(16.dp))
        Text("Blur", color = theme.onGlass, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        val strength = if (step > 0) "$step of 4" else "None"
        Text(strength, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
    }
    Slider(
        value = step.toFloat(),
        onValueChange = { v ->
            val next = v.roundToInt().coerceIn(0, 4)
            if (next != step) state.updateAppearance { it.copy(blurStep = next) }
        },
        valueRange = 0f..4f,
        steps = 3,
        colors = SliderDefaults.colors(
            thumbColor = theme.onGlass,
            activeTrackColor = theme.onGlass,
            inactiveTrackColor = theme.onGlass.copy(alpha = 0.25f),
            activeTickColor = theme.glassFallback,
            inactiveTickColor = theme.onGlass.copy(alpha = 0.5f),
        ),
        modifier = Modifier.semantics { contentDescription = "Blur strength, 0 to 4" },
    )
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
        GlassSwitchRow(
            "Check the drive automatically",
            state.appearance.autoDriveCheck,
            { on -> state.updateAppearance { it.copy(autoDriveCheck = on) } },
            icon = Icons.Rounded.Dns,
            description = "On: when the app opens and every hour. Off: only when you open Family or Backup, or pull down on Photos, so the drive can sleep.",
        )
        GlassSwitchRow(
            FIND_MOVED_TITLE,
            drive.findMoved,
            { drive.updateFindMoved(it) },
            icon = Icons.Rounded.Explore,
            description = FIND_MOVED_NOTE,
        )
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
        // 18 Nov (Deepak): a test, off by default.
        GlassSwitchRow(
            "Test: all my backups on every device",
            state.appearance.mirrorTest,
            { on -> state.updateAppearance { it.copy(mirrorTest = on) } },
            description = "Photos and Albums get a switch: what is on this device, or your backups that aren't on it (from " +
                "your other devices, on the family drive and Google Drive). Only your own backups show, never anyone else's. " +
                "Your other phone or tablet must be in the family as you.",
            icon = Icons.Rounded.Devices,
        )
        // 20 Nov (Deepak): side bar tries on tablets, off by default; today's side bar is untouched until one is kept.
        if (LocalTabletDevice.current) {
            val theme = LocalGlassTheme.current
            Text(
                "Test: side bar motion",
                color = theme.onGlass,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 12.dp, start = 4.dp),
            )
            Text(
                "How the page changes when you pick a section. Sideways is how it works now.",
                color = theme.onGlassSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
            com.deepak.photovault.designsystem.GlassSegmented(
                RailMotion.entries.map { it.label },
                selectedIndex = RailMotion.of(state.appearance.railMotion).ordinal,
                onSelect = { i -> state.updateAppearance { it.copy(railMotion = RailMotion.entries[i].name) } },
            )
            GlassSwitchRow(
                "Test: side bar that folds out",
                state.appearance.railFoldTest,
                { on -> state.updateAppearance { it.copy(railFoldTest = on) } },
                description = "A menu button at the top of the side bar opens it wide, with each section's name beside its icon.",
                icon = Icons.Rounded.ViewSidebar,
            )
        }
        // 20 Nov (Phase 12): checks smoothness on a real phone.
        GlassSwitchRow(
            "Test: smoothness meter",
            state.appearance.frameMeterTest,
            { on -> state.updateAppearance { it.copy(frameMeterTest = on) } },
            description = "A small counter at the top of the screen: how many frames came late while you scroll, pinch " +
                "and open photos. Tap it to count again. Turn it off when you're done.",
            icon = androidx.compose.material.icons.Icons.Rounded.Speed,
        )
        // 21 Nov (Deepak: "look for Apple-style animations with that theme and put in About a toggle for experiment").
        GlassSwitchRow(
            "Test: iOS motion (Clear Glass)",
            state.appearance.iosMotionTest,
            { on -> state.updateAppearance { it.copy(iosMotionTest = on) } },
            description = "With the Clear Glass theme: the bottom bar shrinks to a small pill while you scroll down, with " +
                "Search beside it (also with Keep the bottom bar in place on); drag along the bar to move between " +
                "sections; buttons swell and bounce when pressed; menus grow out of their button; switch knobs turn to " +
                "glass while held. Off: it moves like the other themes.",
            icon = Icons.Rounded.AutoAwesome,
        )
        InfoRow(Icons.Rounded.Storage, "Phone and drive in one timeline", "Done")
        InfoRow(Icons.Rounded.Groups, "Family Album", "Done")
        InfoRow(Icons.Rounded.VpnKey, "Away from home (VPN)", "Done")
        InfoRow(Icons.Rounded.PersonAdd, "Invites", "Done")
        InfoRow(Icons.AutoMirrored.Rounded.OpenInNew, "Open with Pixallery", "Done")
        InfoRow(Icons.Rounded.Schedule, "Free up space", "Done")
        InfoRow(Icons.Rounded.Lock, "Secured folder", "Done")
        InfoRow(Icons.Rounded.Groups, "People", "Done")
        // What comes next (7 Nov).
        InfoRow(Icons.Rounded.Place, "Places", "Done")
        InfoRow(Icons.Rounded.History, "Memories and tags", "Done")
        InfoRow(Icons.Rounded.PhotoLibrary, "Living photos", "Done")
        // 10 Nov: Google Drive backup comes before Family roles.
        InfoRow(Icons.Rounded.Storage, "Google Drive backup", "Done")
        // 15 Nov: Phase 11 started before Family roles (10A comes after it).
        InfoRow(Icons.Rounded.Folder, "Photo editor", "Built, being tested")
        InfoRow(Icons.Rounded.Folder, "Duplicate finder", "Built, being tested")
        InfoRow(Icons.Rounded.Folder, "Straighten, undo, HDR, video trim", "Built, being tested")
        InfoRow(Icons.Rounded.Groups, "Family roles", "Built, being tested")
        InfoRow(Icons.Rounded.Schedule, "Smoothness and foldables", "Phase 12")
        InfoRow(Icons.AutoMirrored.Rounded.OpenInNew, "Release", "Phase 13")
        // Credits for the face models (6 Nov). AuraFace's licence asks for the notice and credit.
        Text(
            "Credits",
            color = LocalGlassTheme.current.onGlass,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            "People uses YuNet and SFace from OpenCV (Apache License 2.0). The AuraFace test model is AuraFace v1 by fal.ai, " +
                "used under the Apache License 2.0. Place names come from GeoNames (geonames.org), under the Creative Commons " +
                "Attribution 4.0 licence.",
            color = LocalGlassTheme.current.onGlassSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
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

/** Room for one theme swatch (72 dp) and a little space beside it. */
private val THEME_CELL = 80.dp
