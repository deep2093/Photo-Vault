package com.deepak.photovault.ui.shell

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.PhotoSizeSelectLarge
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deepak.photovault.core.backup.BackupSchedule
import com.deepak.photovault.core.backup.BackupSelection
import com.deepak.photovault.core.backup.BackupRun
import com.deepak.photovault.core.backup.Frequency
import com.deepak.photovault.core.media.DetailsFormat
import com.deepak.photovault.core.media.LibraryCounts
import com.deepak.photovault.core.media.MediaAccess
import com.deepak.photovault.core.media.PhoneAlbum
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassCard
import com.deepak.photovault.designsystem.GlassChip
import com.deepak.photovault.designsystem.GlassDialog
import com.deepak.photovault.designsystem.GlassIconButton
import com.deepak.photovault.designsystem.GlassProgressBar
import com.deepak.photovault.designsystem.GlassSwitchRow
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.SectionHeader
import com.deepak.photovault.designsystem.WarningNote
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime

/** Shown whenever mobile-data backup is switched on (agreed wording, PhotoVault Plan v2). */
const val MOBILE_DATA_WARNING =
    "Backing up on mobile data uses about twice the size of each photo or video, because the app checks " +
        "every file after uploading. Large videos can use a lot of data."

/**
 * Backup (Phase 4, made compact 24 Oct): status at the top, then one card of short rows (schedule and folders open
 * their own small windows), your space and phones, a short history and restore. Details live one tap away.
 */
@Composable
fun BackupScreen(drive: DriveState, backup: BackupState, gallery: GalleryState, actions: MediaActions, state: ShellState, onBack: (() -> Unit)? = null) {
    val settings = backup.settings
    var confirmMobile by remember { mutableStateOf(false) }
    var pickFolders by remember { mutableStateOf(false) }
    var scheduleOpen by remember { mutableStateOf(false) }
    // What is backed up changes after every run; refresh when the screen opens and when a run ends.
    LaunchedEffect(backup.running, backup.history.size) { backup.refreshKeys() }
    // Let the family see this phone's backup time, so nobody picks the same hour (24 Oct).
    LaunchedEffect(drive.joined?.me?.userId) {
        if (drive.joined != null) drive.publishSchedule(settings.schedule, kotlinx.datetime.TimeZone.currentSystemDefault().id)
    }
    val eligible = remember(gallery.items, settings) { BackupSelection.eligible(gallery.items, settings) }
    val done = remember(eligible, backup.backedUpKeys) { eligible.count { BackupSelection.key(it) in backup.backedUpKeys } }

    // Opened from Settings since 24 Oct, so it has a title and a back button.
    GlassListScreen(title = if (onBack != null) "Backup" else null, subtitle = null, onBack = onBack) {
        item { StatusCard(drive, backup, gallery, actions, eligible.size, done) }
        item { PrivacyCard(state, drive, backup) }
        item {
            GlassCard(Modifier.cardWidth()) {
                SettingRow(Icons.Rounded.Schedule, "Schedule", scheduleSummary(settings), onClick = { scheduleOpen = true })
                SettingRow(
                    Icons.Rounded.Folder,
                    "Folders",
                    chosenFolders(gallery.albums, settings.folders).joinToString(", ").ifEmpty { "None" },
                    onClick = { pickFolders = true },
                )
                GlassSwitchRow("Photos", settings.photos, { on -> backup.update { it.copy(photos = on) } }, icon = Icons.Rounded.Photo)
                GlassSwitchRow(
                    "Videos",
                    settings.videos,
                    { on -> backup.update { it.copy(videos = on, videosOnMobile = if (on) it.videosOnMobile else false) } },
                    icon = Icons.Rounded.Movie,
                )
                GlassSwitchRow(
                    "Storage saver",
                    settings.storageSaver,
                    { on -> backup.update { it.copy(storageSaver = on) } },
                    icon = Icons.Rounded.PhotoSizeSelectLarge,
                    description = "Photos saved at 16 MP: still sharp for prints up to 24 × 16 in.",
                )
                GlassSwitchRow(
                    "Mobile data",
                    settings.mobileData,
                    { on -> if (on) confirmMobile = true else backup.update { it.copy(mobileData = false, videosOnMobile = false) } },
                    icon = Icons.Rounded.SignalCellularAlt,
                )
                if (settings.mobileData && settings.videos) {
                    GlassSwitchRow("Videos on mobile data", settings.videosOnMobile, { on -> backup.update { it.copy(videosOnMobile = on) } }, icon = Icons.Rounded.Movie)
                }
                if (!backup.notificationsAllowed()) {
                    SettingRow(Icons.Rounded.Notifications, "Notifications are off", "Allow", onClick = actions::requestNotifications)
                }
                if (backup.batteryRestricted()) {
                    SettingRow(Icons.Rounded.BatteryChargingFull, "Run on time in background", "Allow", onClick = actions::openBatterySettings)
                }
            }
        }
        // Your phones moved to your own page in Family (24 Oct).
        if (drive.joined != null) item { RestoreCard(drive, backup, gallery) }
    }

    if (confirmMobile) {
        ConfirmDialog(
            title = "Back up on mobile data?",
            body = MOBILE_DATA_WARNING,
            confirm = "Use mobile data",
            onDismiss = { confirmMobile = false },
        ) {
            backup.update { it.copy(mobileData = true) }
            confirmMobile = false
        }
    }
    if (pickFolders) {
        FolderPicker(gallery.albums, settings.folders, onDismiss = { pickFolders = false }) { ids ->
            backup.update { it.copy(folders = ids) }
            pickFolders = false
        }
    }
    if (scheduleOpen) {
        ScheduleDialog(backup, actions, drive, onDismiss = { scheduleOpen = false })
    }
}

/** "Sun · 2:00 am · charging", or "Off". */
private fun scheduleSummary(settings: com.deepak.photovault.core.backup.BackupSettings): String {
    val s = settings.schedule
    if (!s.enabled) return "Off"
    val day = when (s.frequency) {
        Frequency.DAILY -> "Daily"
        Frequency.WEEKLY -> s.daysLabel()
        Frequency.MONTHLY -> "Day ${s.dayOfMonth}"
    }
    return listOfNotNull(day, BackupSchedule.timeLabel(s.hour, s.minute), if (settings.chargingOnly) "charging" else null).joinToString(" · ")
}

/** A short row: icon, title, current value and an arrow; opens its own window. */
@Composable
private fun SettingRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String, onClick: () -> Unit) {
    val theme = LocalGlassTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .clickable(onClickLabel = title, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(icon, contentDescription = null, tint = theme.onGlassSecondary)
        Spacer(Modifier.width(16.dp))
        Text(title, color = theme.onGlass, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            color = theme.onGlassSecondary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp),
        )
        androidx.compose.material3.Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = theme.onGlassSecondary)
    }
}

/** Everything about when backups run, in one small window. */
@Composable
private fun ScheduleDialog(backup: BackupState, actions: MediaActions, drive: DriveState, onDismiss: () -> Unit) {
    val theme = LocalGlassTheme.current
    val scope = rememberCoroutineScope()
    // Changes are a draft until Save, so a time too close to another phone's is never scheduled (24 Oct).
    var draft by remember { mutableStateOf(backup.settings) }
    var others by remember { mutableStateOf<List<com.deepak.photovault.core.backup.PublishedSchedule>?>(null) }
    LaunchedEffect(Unit) { others = drive.otherSchedules() }
    val zone = remember { kotlinx.datetime.TimeZone.currentSystemDefault() }
    val now = remember { backup.now() }
    val clashes = remember(draft.schedule, others) {
        others?.let { com.deepak.photovault.core.backup.SlotRules.clashes(draft.schedule, zone, it, now) }.orEmpty()
    }
    val suggestion = remember(draft.schedule, others, clashes) {
        if (clashes.isEmpty()) null else others?.let { com.deepak.photovault.core.backup.SlotRules.nearestFree(draft.schedule, zone, it, now) }
    }
    GlassDialog(
        title = "Schedule",
        onDismiss = onDismiss,
        buttons = {
            GlassButton("Cancel", onClick = onDismiss)
            GlassButton("Save", primary = true, onClick = {
                if (clashes.isNotEmpty()) return@GlassButton
                backup.update { draft }
                scope.launch { drive.publishSchedule(draft.schedule, zone.id) }
                onDismiss()
            })
        },
    ) {
        GlassSwitchRow(
            "Back up automatically",
            draft.schedule.enabled,
            { on ->
                draft = draft.copy(schedule = draft.schedule.copy(enabled = on))
                if (on && !backup.notificationsAllowed()) actions.requestNotifications()
            },
        )
        if (draft.schedule.enabled) {
            ScheduleControls(draft.schedule) { f -> draft = draft.copy(schedule = f(draft.schedule)) }
            if (clashes.isNotEmpty()) {
                val who = clashes.joinToString { "${it.userName}'s ${it.deviceName} (${it.schedule.describe().replaceFirstChar { c -> c.lowercase() }}${if (it.zoneId != zone.id) ", their time" else ""})" }
                WarningNote("Too close to $who. Only one phone backs up at a time, so pick a time at least an hour away.")
                suggestion?.let { free ->
                    GlassButton("Use ${BackupSchedule.timeLabel(free.hour, free.minute)}", onClick = { draft = draft.copy(schedule = free) })
                }
            } else if (!others.isNullOrEmpty()) {
                Text(
                    "Other family phones: " + others.orEmpty().joinToString("; ") { "${it.userName}'s ${it.deviceName} ${it.schedule.describe().replaceFirstChar { c -> c.lowercase() }}" },
                    color = theme.onGlassSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            GlassSwitchRow("Only while charging", draft.chargingOnly, { on -> draft = draft.copy(chargingOnly = on) })
            Text(
                if (draft.chargingOnly) "Not charging then? It starts once the phone is charging on Wi-Fi." else "Starts at that time once on Wi-Fi.",
                color = theme.onGlassSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun chosenFolders(albums: List<PhoneAlbum>, folders: Set<String>?): List<String> =
    albums.filter { if (folders == null) BackupSelection.isCamera(it.name) else it.id in folders }.map { it.name }

@Composable
private fun StatusCard(drive: DriveState, backup: BackupState, gallery: GalleryState, actions: MediaActions, eligible: Int, done: Int) {
    val soloScope = rememberCoroutineScope()
    val theme = LocalGlassTheme.current
    var note by remember { mutableStateOf<String?>(null) }
    var taps by remember { mutableStateOf(0) }
    // Tapping Back up now must never look like nothing happened (25 Oct): if it hasn't started in a few seconds, say why.
    LaunchedEffect(taps) {
        if (taps == 0) return@LaunchedEffect
        kotlinx.coroutines.delay(4000)
        if (!backup.running) {
            note = if (backup.onMobileData() && !backup.settings.mobileData) {
                "Waiting for Wi-Fi. You're on mobile data, which backup doesn't use unless you allow it in Backup settings."
            } else {
                "Waiting to start. Backup needs a Wi-Fi connection and the drive on; it starts by itself as soon as they are."
            }
        }
    }
    // Your place in line (28 Oct): shown once when this phone has to wait behind another family phone.
    var hideLine by remember { mutableStateOf(false) }
    LaunchedEffect(backup.queuePlace) { if (backup.queuePlace == 0) hideLine = false }
    if (backup.queuePlace >= 2 && !hideLine) {
        GlassDialog(
            title = "You're number ${backup.queuePlace} in line",
            onDismiss = { hideLine = true },
            buttons = {
                GlassButton("Leave the line", onClick = { backup.stop(); hideLine = true })
                GlassButton("Keep my place", primary = true, onClick = { hideLine = true })
            },
        ) {
            Text(
                (backup.waitingFor?.let { "$it is backing up now. " } ?: "") +
                    "Only one phone backs up at a time so the drive and your internet stay quick. Yours starts by itself when it's your turn; you can close the app.",
                color = theme.onGlassSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    GlassCard(Modifier.cardWidth()) {
        val p = backup.progress
        when {
            backup.running -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Backing up", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            backup.waitingFor?.let { "Number ${backup.queuePlace.coerceAtLeast(1)} in line · waiting for $it" }
                                ?: if (p == null || p.total == 0) "Getting ready..." else "${p.done} of ${p.total}" + (p.current?.let { " · $it" } ?: ""),
                            color = theme.onGlassSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    GlassIconButton(Icons.Rounded.Stop, "Stop backup", onClick = backup::stop)
                }
                Spacer(Modifier.height(8.dp))
                GlassProgressBar(p?.fraction ?: 0f, label = "Backup progress")
            }
            drive.joined == null -> {
                Text("Backup", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        !drive.isSetUp -> "Connect your family drive to start backing up."
                        drive.membership is com.deepak.photovault.core.household.Membership.NoHousehold ->
                            "This drive has no family yet. You can back up just for yourself, and add family later."
                        else -> "Join your family on the drive to start backing up."
                    },
                    color = theme.onGlassSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Backing up alone (25 Oct): a family of one, in one tap, no PIN. Family can be added later.
                if (drive.isSetUp && drive.membership is com.deepak.photovault.core.household.Membership.NoHousehold) {
                    var startError by remember { mutableStateOf<String?>(null) }
                    GlassButton(
                        "Back up just me",
                        primary = true,
                        icon = Icons.Rounded.Upload,
                        onClick = { soloScope.launch { startError = drive.startJustMe() } },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    startError?.let { WarningNote(it) }
                }
            }
            gallery.access == MediaAccess.NONE -> {
                Text("Backup", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Allow access to your photos so they can be backed up.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
                GlassButton("Allow access", primary = true, onClick = actions::requestAccess, modifier = Modifier.padding(top = 8.dp))
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (eligible > 0 && done == eligible) "All backed up" else "${LibraryCounts.grouped(eligible - done)} to back up",
                            color = theme.onGlass,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${LibraryCounts.grouped(done)} of ${LibraryCounts.grouped(eligible)} saved and checked",
                            color = theme.onGlassSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    GlassButton("Back up now", primary = true, icon = Icons.Rounded.Upload, onClick = {
                        note = if (backup.onMobileData() && !backup.settings.mobileData) "On mobile data: it starts once you're on Wi-Fi." else null
                        if (!backup.notificationsAllowed()) actions.requestNotifications()
                        backup.backUpNow()
                        taps++
                    })
                }
                Spacer(Modifier.height(10.dp))
                GlassProgressBar(if (eligible == 0) 0f else done / eligible.toFloat(), label = "$done of $eligible backed up")
                val last = backup.history.firstOrNull()
                val next = backup.nextRunMillis()
                val line = listOfNotNull(
                    last?.let { "Last ${whenText(it.finishedAtMillis, backup.now())}" },
                    next?.let { "next ${whenText(it, backup.now())}" },
                ).joinToString(" · ")
                if (line.isNotEmpty()) {
                    Text(line.replaceFirstChar { it.uppercase() }, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
                if (last != null && !last.succeeded) last.failureMessage?.let { WarningNote(it) }
                note?.let { WarningNote(it) }
            }
        }
    }
}

/** Frequency, day and time. The time moves in 15-minute steps. */
@Composable
private fun ScheduleControls(schedule: BackupSchedule, change: ((BackupSchedule) -> BackupSchedule) -> Unit) {
    val theme = LocalGlassTheme.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Frequency.entries.forEach { option -> GlassChip(option.label, selected = schedule.frequency == option, onClick = { change { it.copy(frequency = option) } }) }
    }
    when (schedule.frequency) {
        Frequency.WEEKLY -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Several days can be chosen (24 Oct); at least one always stays on.
            BackupSchedule.DAY_NAMES.forEachIndexed { i, day ->
                val d = i + 1
                GlassChip(day.take(3), selected = d in schedule.weekDays, onClick = {
                    change {
                        val days = it.weekDays
                        val next = if (d in days) days - d else days + d
                        if (next.isEmpty()) it else it.copy(daysOfWeek = next)
                    }
                })
            }
        }
        Frequency.MONTHLY -> Stepper("Day of the month", schedule.dayOfMonth.toString(),
            onMinus = { change { it.copy(dayOfMonth = if (it.dayOfMonth <= 1) 28 else it.dayOfMonth - 1) } },
            onPlus = { change { it.copy(dayOfMonth = if (it.dayOfMonth >= 28) 1 else it.dayOfMonth + 1) } })
        Frequency.DAILY -> Unit
    }
    // Rolling wheels for the time, with a tick on each step (24 Oct).
    WheelTimePicker(schedule.hour, schedule.minute, onChange = { h, m -> change { it.copy(hour = h, minute = m) } })
    Text(
        "Android may start a little late to save battery.",
        color = theme.onGlassSecondary,
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun BackupSchedule.shiftMinutes(delta: Int): BackupSchedule {
    val total = ((hour * 60 + minute + delta) % (24 * 60) + 24 * 60) % (24 * 60)
    return copy(hour = total / 60, minute = total % 60)
}

@Composable
private fun Stepper(title: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    val theme = LocalGlassTheme.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(title, color = theme.onGlass, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        GlassIconButton(Icons.Rounded.Remove, "Earlier", onClick = onMinus)
        Text(
            value,
            color = theme.onGlass,
            style = MaterialTheme.typography.titleMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.width(88.dp),
        )
        GlassIconButton(Icons.Rounded.Add, "Later", onClick = onPlus)
    }
}

@Composable
private fun FolderPicker(albums: List<PhoneAlbum>, current: Set<String>?, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    val theme = LocalGlassTheme.current
    var chosen by remember { mutableStateOf(current ?: albums.filter { BackupSelection.isCamera(it.name) }.map { it.id }.toSet()) }
    GlassDialog(
        title = "Folders to back up",
        onDismiss = onDismiss,
        buttons = {
            GlassButton("Cancel", onClick = onDismiss)
            GlassButton("Save", primary = true, onClick = { onSave(chosen) })
        },
    ) {
        Text("Camera is backed up by default. Add any other folder on this phone.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            albums.forEach { album ->
                GlassSwitchRow(
                    album.name,
                    album.id in chosen,
                    { on -> chosen = if (on) chosen + album.id else chosen - album.id },
                    description = "${LibraryCounts.grouped(album.count)} items",
                )
            }
        }
    }
}

@Composable
private fun SpaceAndPhonesCard(drive: DriveState, backup: BackupState, eligible: Int, done: Int) {
    val theme = LocalGlassTheme.current
    val me = drive.joined?.me ?: return
    var last by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(me.userId, backup.history.size) { last = drive.lastBackups() }
    GlassCard(Modifier.cardWidth()) {
        SectionHeader("Your phones")
        me.devices.forEach { device ->
            val isThis = device.deviceId == drive.deviceId
            Column(Modifier.padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(Icons.Rounded.PhoneAndroid, null, tint = theme.onGlassSecondary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(device.name + if (isThis) " (this phone)" else "", color = theme.onGlass, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            last[device.deviceId]?.let { "Last backup ${whenText(it, backup.now())}" } ?: "No backups yet",
                            color = theme.onGlassSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(history: List<BackupRun>) {
    val theme = LocalGlassTheme.current
    GlassCard(Modifier.cardWidth()) {
        SectionHeader("History")
        history.take(3).forEach { run ->
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                androidx.compose.material3.Icon(Icons.Rounded.History, null, tint = if (run.succeeded) theme.onGlassSecondary else theme.accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("${run.trigger.label} · ${dateText(run.finishedAtMillis)}", color = theme.onGlass, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        buildString {
                            append(if (run.uploaded == 0) "Nothing new" else "${LibraryCounts.grouped(run.uploaded)} saved (${DetailsFormat.size(run.bytes)})")
                            run.failureMessage?.let { append(". ").append(it) }
                        },
                        color = theme.onGlassSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun RestoreCard(drive: DriveState, backup: BackupState, gallery: GalleryState) {
    val theme = LocalGlassTheme.current
    val scope = rememberCoroutineScope()
    var missing by remember { mutableStateOf<Int?>(null) }
    var checking by remember { mutableStateOf(false) }
    GlassCard(Modifier.cardWidth()) {
        SectionHeader("Restore")
        Text(
            "Bring back backed-up photos that aren't on this phone.",
            color = theme.onGlassSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        val rp = backup.restoreProgress
        when {
            backup.restoring -> {
                Text(if (rp == null) "Restoring..." else "Restoring ${rp.first} of ${rp.second}", color = theme.onGlass, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                if (rp != null && rp.second > 0) GlassProgressBar(rp.first / rp.second.toFloat(), label = "Restore progress")
            }
            missing == null -> GlassButton(if (checking) "Checking..." else "Check the drive", icon = Icons.Rounded.CloudDownload, modifier = Modifier.padding(top = 8.dp), onClick = {
                if (!checking) {
                    checking = true
                    scope.launch {
                        missing = drive.missingOnPhone(gallery.items) ?: -1
                        checking = false
                    }
                }
            })
            missing == -1 -> WarningNote("Couldn't read the drive. Check you're on the home Wi-Fi and try again.")
            missing == 0 -> Text("Everything on the drive is already on this phone.", color = theme.onGlass, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            else -> GlassButton("Restore ${LibraryCounts.grouped(missing!!)} to this phone", primary = true, icon = Icons.Rounded.CloudDownload, modifier = Modifier.padding(top = 8.dp), onClick = {
                backup.restoreMissing()
                missing = null
            })
        }
        backup.restoreMessage?.let { Text(it, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
    }
}

@Composable
internal fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String) {
    val theme = LocalGlassTheme.current
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(icon, contentDescription = null, tint = theme.onGlassSecondary)
        Spacer(Modifier.width(16.dp))
        if (value.length > INFO_ROW_INLINE_CHARS) {
            // A long value goes under the title, so the title never gets squeezed into a narrow column
            // (feedback 22 Oct: "Folders" wrapped letter by letter beside the folder list).
            Column(Modifier.weight(1f)) {
                Text(title, color = theme.onGlass, style = MaterialTheme.typography.bodyLarge)
                Text(value, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Text(title, color = theme.onGlass, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(value, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private const val INFO_ROW_INLINE_CHARS = 20

/** "today at 2:05 am", "yesterday at ...", "on Sun 25 Oct at ...". */
internal fun whenText(millis: Long, now: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val at = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
    val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date
    val time = BackupSchedule.timeLabel(at.hour, at.minute)
    val days = at.date.toEpochDays() - today.toEpochDays()
    return when (days) {
        0 -> "today at $time"
        -1 -> "yesterday at $time"
        1 -> "tomorrow at $time"
        else -> "on ${dateText(millis, zone)} at $time"
    }
}

internal fun dateText(millis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val d = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date
    return "${BackupSchedule.DAY_NAMES[d.dayOfWeek.isoDayNumber - 1].take(3)} ${d.dayOfMonth} ${MONTHS[d.monthNumber - 1]}"
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** Real figures once this phone has joined the family. */
@Composable
private fun YourSpace(drive: DriveState) {
    val theme = LocalGlassTheme.current
    val joined = drive.joined
    if (joined == null) {
        Text(
            if (drive.isSetUp) "Join your family on the drive to see your space." else "Connect your drive to see your space.",
            color = theme.onGlassSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    val mine = drive.usage[joined.me.userId]
    val limit = mine?.limitBytes ?: joined.me.limitGb * DriveState.GB
    val used = mine?.usedBytes
    // One line: the bar and "used of total" beside it (24 Oct: compact).
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassProgressBar(
            ((used ?: 0L) / limit.toFloat()).coerceIn(0f, 1f),
            modifier = Modifier.weight(1f),
            label = "Your space, ${used?.let(DetailsFormat::size) ?: "counting"} of ${joined.me.limitGb} gigabytes used",
        )
        Spacer(Modifier.width(12.dp))
        Text(
            if (used == null) "Counting..." else "${DetailsFormat.size(used)} of ${joined.me.limitGb} GB",
            color = theme.onGlass,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    // The whole drive is the owner's business only (24 Oct).
    if (drive.isOwner) {
        drive.space?.let {
            Text(
                "Drive: ${DetailsFormat.size(it.freeBytes)} free of ${DetailsFormat.size(it.totalBytes)}",
                color = theme.onGlassSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
