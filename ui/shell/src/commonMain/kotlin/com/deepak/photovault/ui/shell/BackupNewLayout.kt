package com.deepak.photovault.ui.shell

import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deepak.photovault.core.backup.BackupDestination
import com.deepak.photovault.core.backup.BackupPrivacy
import com.deepak.photovault.core.backup.BackupSelection
import com.deepak.photovault.core.backup.BackupSettings
import com.deepak.photovault.core.backup.isDriveOnly
import com.deepak.photovault.core.media.LibraryCounts
import com.deepak.photovault.core.media.MediaAccess
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.StorageSlice
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassCard
import com.deepak.photovault.designsystem.GlassIconButton
import com.deepak.photovault.designsystem.GlassSegmented
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.SectionHeader
import com.deepak.photovault.designsystem.WarningNote

/** The ring's colours: backed up, waiting, skipped. */
private val RING = listOf(Color(0xFF34A853), Color(0xFFF2A60C))

/**
 * The Backup layout (15 Nov; the only one since 16 Nov): Back up to as a slim switch; a ring
 * card like Storage's (backed up, waiting, skipped; swipe between the family drive and Google Drive when backing up to
 * both); one settings card (with a small switch for whose settings when both); warnings only when they need you; the
 * same last card (privacy, remove phone copies, restore).
 */
internal fun LazyListScope.newBackupItems(
    state: ShellState,
    drive: DriveState,
    backup: BackupState,
    gallery: GalleryState,
    actions: MediaActions,
    googleOn: Boolean,
    showDrive: Boolean,
    showGoogle: Boolean,
    googleView: BackupSettings,
    eligible: Int,
    done: Int,
    googleEligible: List<MediaItem>,
    privacyNeedsYou: Boolean,
    open: (BackupSheet, Boolean) -> Unit,
    openPrivacy: () -> Unit,
    openFreeUp: () -> Unit,
    openRestore: () -> Unit,
) {
    val settings = backup.settings
    if (googleOn) item(key = "n-where") { WhereSwitch(backup) }
    // Only when something needs the person (16 Nov: an empty one left a double gap under the switch).
    val warn = (showGoogle && state.google.accounts.any { it.needsSignIn }) || !backup.notificationsAllowed() || backup.batteryRestricted()
    if (warn) item(key = "n-warnings") { Warnings(state, backup, actions, showGoogle) { open(BackupSheet.ACCOUNTS, true) } }
    if (privacyNeedsYou) item(key = "n-privacy-needed") { PrivacyCard(state, drive, backup) }
    // A place that isn't ready yet (no family, no photo access, no Google account) keeps its old card: it says what to do.
    val driveReady = showDrive && drive.joined != null && gallery.access != MediaAccess.NONE
    val googleReady = showGoogle && state.google.accounts.isNotEmpty()
    if (showDrive && !driveReady) item(key = "n-drive-setup") {
        DriveCard(drive, backup, gallery, actions, eligible, done, titled = googleOn) { open(it, false) }
    }
    if (showGoogle && !googleReady) item(key = "n-google-setup") {
        GoogleCard(state.google, backup, googleView, gallery.albums, googleEligible) { open(it, true) }
    }
    if (driveReady || googleReady) item(key = "n-ring") {
        RingCard(state, backup, gallery, actions, driveReady, googleReady, eligible, done, googleEligible, googleView)
    }
    if (driveReady || googleReady) item(key = "n-settings") {
        SettingsCard(state, backup, gallery, settings, googleView, driveReady, googleReady, open)
    }
    item(key = "n-more") {
        GlassCard(Modifier.cardWidth()) {
            if (!privacyNeedsYou) {
                SettingRow(Icons.Rounded.Lock, "Backup privacy", if (settings.privacy == BackupPrivacy.PRIVATE) "Private" else "Normal", dense = true, onClick = openPrivacy)
            }
            // Private backups being made normal (16 Nov): where it is up to, so no computer is needed to check.
            if (settings.privacy == BackupPrivacy.NORMAL) {
                val line = backup.converting?.let { (done, total) -> if (total == 0) "Making private backups normal…" else "Making private backups normal: $done of $total" }
                    ?: backup.convertNote
                    ?: if (settings.convertPrivateToNormal) "Your private backups become normal pictures after the next backup to the family drive." else null
                line?.let {
                    Text(
                        it,
                        color = LocalGlassTheme.current.onGlassSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
                    )
                }
            }
            SettingRow(Icons.Rounded.PhoneAndroid, "Remove phone copies", if (settings.freeUpDays == 0) "Off" else "After ${settings.freeUpDays} days", dense = true, onClick = openFreeUp)
            if (drive.joined != null || showGoogle) SettingRow(Icons.Rounded.CloudDownload, "Restore to this phone", "", dense = true, onClick = openRestore)
        }
    }
}

/** Family drive, Google Drive or Both, as a slim switch with no card; a line under it only for Both. */
@Composable
private fun WhereSwitch(backup: BackupState) {
    val theme = LocalGlassTheme.current
    val options = listOf(BackupDestination.DRIVE to "Family drive", BackupDestination.GOOGLE to "Google Drive", BackupDestination.BOTH to "Both")
    val choice = backup.settings.destination
    Column(Modifier.cardWidth()) {
        GlassSegmented(options.map { it.second }, options.indexOfFirst { it.first == choice }.coerceAtLeast(0), { i ->
            backup.update { it.copy(destination = options[i].first) }
        }, Modifier.fillMaxWidth())
        if (choice == BackupDestination.BOTH) {
            Text(
                "A photo counts as backed up once it's in both.",
                color = theme.onWallpaper,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 6.dp, top = 6.dp),
            )
        }
    }
}

/** Only what needs the person: a short line each, tap to fix. Nothing when all is well. */
@Composable
private fun Warnings(state: ShellState, backup: BackupState, actions: MediaActions, showGoogle: Boolean, openAccounts: () -> Unit) {
    val needSignIn = showGoogle && state.google.accounts.any { it.needsSignIn }
    val notifications = !backup.notificationsAllowed()
    val battery = backup.batteryRestricted()
    if (!needSignIn && !notifications && !battery) return
    Column(Modifier.cardWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (needSignIn) Tappable(openAccounts) { WarningNote("Google wants you to sign in again. Tap to fix.") }
        if (notifications) Tappable(actions::requestNotifications) { WarningNote("Notifications are off, so you won't hear when backup needs you. Tap to allow.") }
        if (battery) Tappable(actions::openBatterySettings) { WarningNote("Battery limits can delay backups. Tap to let Pixallery run on time.") }
    }
}

@Composable
private fun Tappable(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)) { content() }
}

/**
 * The ring card: for each place in use, a ring of backed up, waiting and skipped (photos the settings leave out),
 * the share done in the middle, when it last ran and runs next, and Back up now (or the progress and Stop while it
 * runs). With both places, swipe the ring for the other one.
 */
@Composable
private fun RingCard(
    state: ShellState,
    backup: BackupState,
    gallery: GalleryState,
    actions: MediaActions,
    driveReady: Boolean,
    googleReady: Boolean,
    eligible: Int,
    done: Int,
    googleEligible: List<MediaItem>,
    googleView: BackupSettings,
) {
    val theme = LocalGlassTheme.current
    val google = state.google
    val status = google.platform.status?.collectAsState()?.value ?: GoogleStatus()
    LaunchedEffect(googleReady) { if (googleReady) google.refresh(readSpace = true, readRecords = false) }
    LaunchedEffect(status.running) { if (googleReady && !status.running) google.refresh(readRecords = false) }
    val phoneCount = remember(gallery.items) { gallery.items.count { !it.isDriveOnly } }
    val onGoogle = remember(googleEligible, google.keys) { googleEligible.count { BackupSelection.key(it) in google.keys } }
    val places = listOfNotNull(if (driveReady) false else null, if (googleReady) true else null)
    val pager = androidx.compose.foundation.pager.rememberPagerState { places.size }
    var note by remember { mutableStateOf<String?>(null) }
    GlassCard(Modifier.cardWidth()) {
        val isGoogle = places.getOrNull(pager.currentPage) == true
        // With both places (18 Nov): a tap on the heading shows the other one too, not only a swipe.
        val ringScope = androidx.compose.runtime.rememberCoroutineScope()
        val still = com.deepak.photovault.designsystem.LocalGlassSettings.current.reduceMotion
        Row(
            Modifier
                .then(
                    if (places.size > 1) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = if (isGoogle) "Show the family drive" else "Show Google Drive") {
                        ringScope.launch { val next = (pager.currentPage + 1) % places.size; if (still) pager.scrollToPage(next) else pager.animateScrollToPage(next) }
                    } else Modifier,
                )
                .heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader(if (isGoogle) "Google Drive" else "Family drive", Modifier.weight(1f))
            if (places.size > 1) PagerDots(places.size, pager.currentPage)
        }
        androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxWidth(), beyondViewportPageCount = 1, verticalAlignment = Alignment.Top) { page ->
            val g = places[page]
            val total = if (g) googleEligible.size else eligible
            val saved = if (g) onGoogle else done
            val waiting = (total - saved).coerceAtLeast(0)
            val skipped = (phoneCount - total).coerceAtLeast(0)
            val running = if (g) status.running else backup.running
            // Extra animations (21 Nov): after a tap the button shows a turning ring until the backup is under way;
            // when a run ends well it shows a tick for a moment; and the first time nothing is left waiting, confetti.
            val lively = com.deepak.photovault.designsystem.extraMotionOn()
            var starting by remember(g) { mutableStateOf(false) }
            var justDone by remember(g) { mutableStateOf(false) }
            var wasRunning by remember(g) { mutableStateOf(running) }
            val endedWell = if (g) status.message == null else backup.history.firstOrNull()?.succeeded != false
            val nothingWaiting = total > 0 && waiting == 0
            LaunchedEffect(running) {
                if (running) {
                    starting = false
                } else if (wasRunning && lively && endedWell) {
                    wasRunning = false
                    justDone = true
                    kotlinx.coroutines.delay(2200)
                    justDone = false
                }
                wasRunning = running
            }
            // If the backup can't start (no Wi-Fi yet, say), the ring stops after a few seconds.
            LaunchedEffect(starting) {
                if (starting) {
                    kotlinx.coroutines.delay(4000)
                    starting = false
                }
            }
            LaunchedEffect(justDone, nothingWaiting) {
                if (justDone && nothingWaiting && !state.appearance.celebratedBackup) {
                    state.updateAppearance { it.copy(celebratedBackup = true) }
                    state.celebrate()
                }
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                        Donut(
                            listOf(StorageSlice("Backed up", saved.toLong(), saved), StorageSlice("Waiting", waiting.toLong(), waiting), StorageSlice("Skipped", skipped.toLong(), skipped)),
                            Modifier.size(112.dp),
                            colors = RING + theme.onGlass.copy(alpha = 0.10f),
                        )
                        val pct = if (total == 0) 0 else (saved * 100L / total).toInt()
                        Text("$pct%", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        // No nudge (16 Nov): measured on Deepak's screenshot, the clear part of the hole (below the inner
                        // wall the ring's depth shows) is centred on the whole ring, so the number sits plain in the middle.
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        CompactLegend(RING[0], "Backed up", LibraryCounts.grouped(saved))
                        CompactLegend(RING[1], "Waiting", LibraryCounts.grouped(waiting))
                        CompactLegend(theme.onGlass.copy(alpha = 0.25f), "Skipped", LibraryCounts.grouped(skipped))
                    }
                }
                // When, then the button (or the progress while it runs).
                val last = if (g) status.lastAtMillis else backup.history.firstOrNull()?.finishedAtMillis
                val next = if (g) backup.nextGoogleRunMillis() else backup.nextRunMillis()
                val line = listOfNotNull(last?.let { "Last ${whenText(it, backup.now())}" }, next?.let { "next ${whenText(it, backup.now())}" }).joinToString(" · ")
                if (line.isNotEmpty() && !running) {
                    Text(line.replaceFirstChar { it.uppercase() }, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
                if (running) {
                    val (d, t) = if (g) status.done to status.total else (backup.progress?.done ?: 0) to (backup.progress?.total ?: 0)
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (!g) backup.waitingFor?.let { "Waiting for $it" } ?: (if (t == 0) "Getting ready…" else "Backing up $d of $t")
                                else if (t == 0) "Getting ready…" else "Backing up $d of $t",
                                color = theme.onGlass,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(6.dp))
                            com.deepak.photovault.designsystem.GlassProgressBar(if (t == 0) 0f else d / t.toFloat(), label = "Backup progress")
                        }
                        if (!g) GlassIconButton(Icons.Rounded.Stop, "Stop backup", onClick = backup::stop)
                    }
                } else {
                    GlassButton(
                        if (justDone) "Backed up" else if (waiting == 0 && total > 0) "Back up again" else "Back up ${LibraryCounts.grouped(waiting)} now",
                        primary = waiting > 0,
                        icon = if (justDone) Icons.Rounded.Check else if (g) Icons.Rounded.CloudUpload else Icons.Rounded.Upload,
                        busy = starting,
                        onClick = {
                            if (lively) starting = true
                            val mobile = if (g) googleView.mobileData else backup.settings.mobileData
                            note = if (backup.onMobileData() && !mobile) "On mobile data: it starts once you're on Wi-Fi." else null
                            if (!backup.notificationsAllowed()) actions.requestNotifications()
                            if (g) google.platform.backUpNow() else backup.backUpNow()
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                }
                val problem = if (g) status.message else backup.history.firstOrNull()?.takeIf { !it.succeeded }?.failureMessage
                problem?.let { WarningNote(it) }
            }
        }
        note?.let { WarningNote(it) }
    }
}

/** One settings card: Schedule, Folders, Options (and Accounts for Google); with both places, a switch for whose. */
@Composable
private fun SettingsCard(
    state: ShellState,
    backup: BackupState,
    gallery: GalleryState,
    settings: BackupSettings,
    googleView: BackupSettings,
    driveReady: Boolean,
    googleReady: Boolean,
    open: (BackupSheet, Boolean) -> Unit,
) {
    var showGoogle by remember { mutableStateOf(!driveReady) }
    val g = if (driveReady && googleReady) showGoogle else googleReady && !driveReady
    GlassCard(Modifier.cardWidth()) {
        SectionHeader("Settings")
        if (driveReady && googleReady) {
            GlassSegmented(listOf("Family drive", "Google Drive"), if (g) 1 else 0, { showGoogle = it == 1 }, Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp))
        }
        if (g) SettingRow(Icons.Rounded.Storage, "Accounts", accountsSummary(state.google.accounts), dense = true, onClick = { open(BackupSheet.ACCOUNTS, true) })
        PlaceRows(if (g) googleView else settings, gallery.albums, dense = true) { open(it, g) }
        if (!g) {
            // Kept from the old layout: the line in the family's queue, if this phone must wait.
            backup.queuePlace.takeIf { it >= 2 }?.let {
                Text(
                    "Number $it in line for the family drive; it starts by itself.",
                    color = LocalGlassTheme.current.onGlassSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
