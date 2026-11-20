package com.deepak.photovault.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.collectAsState
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
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
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

/** Which small window is open on the Backup page, and for which place (family drive or Google). */
internal enum class BackupSheet { SCHEDULE, FOLDERS, OPTIONS, ACCOUNTS }

/**
 * Backup (Phase 4; tidied 14 Nov): where to back up, then one card for each place in use (the family drive, Google
 * Drive) with its own count, Back up now and three short rows (schedule, folders, options) that open small windows,
 * so each place has its own days, time and choices. Privacy, removing phone copies and restore sit in one last card.
 */
@Composable
fun BackupScreen(
    drive: DriveState,
    backup: BackupState,
    gallery: GalleryState,
    actions: MediaActions,
    state: ShellState,
    onBack: (() -> Unit)? = null,
    /** Shown above the cards when the page sits beside the tablet's Settings list (13 Nov), which has no top bar. */
    heading: String? = null,
) {
    val settings = backup.settings
    val googleOn = state.google.platform.supported
    val showDrive = !googleOn || settings.destination.usesDrive
    val showGoogle = googleOn && settings.destination.usesGoogle
    val googleView = remember(settings) { settings.forGoogle() }
    var sheet by remember { mutableStateOf<Pair<BackupSheet, Boolean>?>(null) }
    var privacyOpen by remember { mutableStateOf(false) }
    var freeUpOpen by remember { mutableStateOf(false) }
    var restoreOpen by remember { mutableStateOf(false) }
    // What is backed up changes after every run; refresh when the screen opens and when a run ends.
    LaunchedEffect(backup.running, backup.history.size) { backup.refreshKeys() }
    // Let the family see this phone's backup time, so nobody picks the same hour (24 Oct).
    LaunchedEffect(drive.joined?.me?.userId) {
        if (drive.joined != null) drive.publishSchedule(settings.schedule, kotlinx.datetime.TimeZone.currentSystemDefault().id)
    }
    val eligible = remember(gallery.items, settings) { BackupSelection.eligible(gallery.items, settings) }
    val done = remember(eligible, backup.driveKeys) { eligible.count { BackupSelection.key(it) in backup.driveKeys } }
    val googleEligible = remember(gallery.items, googleView) { BackupSelection.eligible(gallery.items, googleView) }
    // Backup waits for this choice, and a Private backup needs its key: shown in full until sorted.
    val privacyNeedsYou = settings.privacy == com.deepak.photovault.core.backup.BackupPrivacy.UNSET ||
        (settings.privacy == com.deepak.photovault.core.backup.BackupPrivacy.PRIVATE && !state.encryption.keyOnPhone)
    // 18 Nov (A2): an older, short Google PIN gets a nudge to choose a stronger password.
    val weakGooglePassword = rememberWeakGooglePassword(state)

    // Opened from Settings since 24 Oct, so it has a title and a back button.
    GlassListScreen(title = if (onBack != null) "Backup" else null, subtitle = null, onBack = onBack) {
        if (heading != null) item(key = "heading") {
            Text(
                heading,
                color = LocalGlassTheme.current.onWallpaper,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                modifier = Modifier.cardWidth().padding(start = 4.dp, top = 4.dp),
            )
        }
        // The new layout is the only one since 16 Nov (Deepak chose it after trying both): a ring card like Storage's,
        // one settings card and warnings only when they need you.
        if (weakGooglePassword) item(key = "weak-google-password") { WeakGooglePasswordCard(state) }
        newBackupItems(
            state, drive, backup, gallery, actions, googleOn, showDrive, showGoogle, googleView,
            eligible.size, done, googleEligible, privacyNeedsYou,
            open = { what, google -> sheet = what to google },
            openPrivacy = { privacyOpen = true },
            openFreeUp = { freeUpOpen = true },
            openRestore = { restoreOpen = true },
        )
    }
    if (privacyOpen) PrivacyDialog(state, drive, backup, onDismiss = { privacyOpen = false })
    if (freeUpOpen) FreeUpDialog(backup, actions, onDismiss = { freeUpOpen = false })
    if (restoreOpen) RestoreDialog(drive, backup, gallery, state.google, fromDrive = drive.joined != null && showDrive, fromGoogle = showGoogle, onDismiss = { restoreOpen = false })

    sheet?.let { (what, forGoogle) ->
        val view = if (forGoogle) googleView else settings
        val change: ((com.deepak.photovault.core.backup.BackupSettings) -> com.deepak.photovault.core.backup.BackupSettings) -> Unit =
            { f -> if (forGoogle) backup.updateGoogle(f) else backup.update(f) }
        val close = { sheet = null }
        when (what) {
            BackupSheet.SCHEDULE -> ScheduleDialog(backup, actions, drive, forGoogle, onDismiss = close)
            BackupSheet.FOLDERS -> FolderPicker(gallery.albums, view.folders, onDismiss = close) { ids ->
                change { it.copy(folders = ids) }
                close()
            }
            BackupSheet.OPTIONS -> OptionsDialog(if (forGoogle) "Google Drive options" else "Family drive options", view, change, close)
            BackupSheet.ACCOUNTS -> AccountsDialog(state.google, close)
        }
    }
}

/** "Sun · 2:00 am · charging", or "Off". */
internal fun scheduleSummary(settings: com.deepak.photovault.core.backup.BackupSettings): String {
    val s = settings.schedule
    if (!s.enabled) return "Off"
    val day = when (s.frequency) {
        Frequency.DAILY -> "Daily"
        Frequency.WEEKLY -> s.daysLabel()
        Frequency.MONTHLY -> "Day ${s.dayOfMonth}"
    }
    return listOfNotNull(day, BackupSchedule.timeLabel(s.hour, s.minute), if (settings.chargingOnly) "charging" else null).joinToString(" · ")
}

/** "Photos · Wi-Fi", "Photos, videos · mobile data · saver". */
internal fun optionsSummary(s: com.deepak.photovault.core.backup.BackupSettings): String = listOfNotNull(
    when {
        s.photos && s.videos -> "Photos, videos"
        s.photos -> "Photos"
        s.videos -> "Videos"
        else -> "Nothing"
    },
    if (s.mobileData) "mobile data" else "Wi-Fi",
    if (s.storageSaver) "saver" else null,
).joinToString(" · ")

/** The three rows every place has: when, which folders, and what and how. */
@Composable
internal fun PlaceRows(view: com.deepak.photovault.core.backup.BackupSettings, albums: List<PhoneAlbum>, dense: Boolean = false, open: (BackupSheet) -> Unit) {
    SettingRow(Icons.Rounded.Schedule, "Schedule", scheduleSummary(view), dense = dense, onClick = { open(BackupSheet.SCHEDULE) })
    SettingRow(
        Icons.Rounded.Folder,
        "Folders",
        chosenFolders(albums, view.folders).joinToString(", ").ifEmpty { "None" },
        dense = dense,
        onClick = { open(BackupSheet.FOLDERS) },
    )
    SettingRow(Icons.Rounded.Photo, "Options", optionsSummary(view), dense = dense, onClick = { open(BackupSheet.OPTIONS) })
}

/** Photos, videos, quality and mobile data for one place, in a small window. Changes apply straight away. */
@Composable
private fun OptionsDialog(
    title: String,
    view: com.deepak.photovault.core.backup.BackupSettings,
    change: ((com.deepak.photovault.core.backup.BackupSettings) -> com.deepak.photovault.core.backup.BackupSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassDialog(title = title, onDismiss = onDismiss, buttons = { GlassButton("Done", primary = true, onClick = onDismiss) }) {
        GlassSwitchRow("Photos", view.photos, { on -> change { it.copy(photos = on) } }, icon = Icons.Rounded.Photo)
        GlassSwitchRow(
            "Videos",
            view.videos,
            { on -> change { it.copy(videos = on, videosOnMobile = if (on) it.videosOnMobile else false) } },
            icon = Icons.Rounded.Movie,
        )
        GlassSwitchRow(
            "Storage saver",
            view.storageSaver,
            { on -> change { it.copy(storageSaver = on) } },
            icon = Icons.Rounded.PhotoSizeSelectLarge,
            description = "Photos saved at 16 MP: still sharp for prints up to 24 × 16 in.",
        )
        GlassSwitchRow(
            "Mobile data",
            view.mobileData,
            { on -> change { it.copy(mobileData = on, videosOnMobile = if (on) it.videosOnMobile else false) } },
            icon = Icons.Rounded.SignalCellularAlt,
            description = if (view.mobileData) null else "Off: backup waits for Wi-Fi.",
            warning = MOBILE_DATA_WARNING,
        )
        if (view.mobileData && view.videos) {
            GlassSwitchRow("Videos on mobile data", view.videosOnMobile, { on -> change { it.copy(videosOnMobile = on) } }, icon = Icons.Rounded.Movie)
        }
    }
}

/** A short row: icon, title, current value and an arrow; opens its own window. */
@Composable
internal fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    /** For a row that opens its card further down the page: an arrow down (closed) or up (open) instead of right. */
    open: Boolean? = null,
    /** A shorter row, for the new Backup layout (16 Nov). */
    dense: Boolean = false,
    onClick: () -> Unit,
) {
    val theme = LocalGlassTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (dense) 42.dp else 52.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .clickable(onClickLabel = title, onClick = com.deepak.photovault.designsystem.hapticClick(onClick))
            .padding(vertical = if (dense) 2.dp else 6.dp),
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
        androidx.compose.material3.Icon(
            when (open) {
                null -> Icons.AutoMirrored.Rounded.KeyboardArrowRight
                true -> Icons.Rounded.KeyboardArrowUp
                false -> Icons.Rounded.KeyboardArrowDown
            },
            contentDescription = null,
            tint = theme.onGlassSecondary,
        )
    }
}

/** Everything about when backups run, in one small window: the family drive's, or Google's own (14 Nov). */
@Composable
private fun ScheduleDialog(backup: BackupState, actions: MediaActions, drive: DriveState, forGoogle: Boolean, onDismiss: () -> Unit) {
    val theme = LocalGlassTheme.current
    val scope = rememberCoroutineScope()
    // Changes are a draft until Save, so a time too close to another phone's is never scheduled (24 Oct).
    var draft by remember { mutableStateOf(if (forGoogle) backup.settings.forGoogle() else backup.settings) }
    // Only the family drive takes turns; Google can be used by every phone at once.
    var others by remember { mutableStateOf<List<com.deepak.photovault.core.backup.PublishedSchedule>?>(null) }
    LaunchedEffect(Unit) { if (!forGoogle) others = drive.otherSchedules() }
    val zone = remember { kotlinx.datetime.TimeZone.currentSystemDefault() }
    val now = remember { backup.now() }
    val clashes = remember(draft.schedule, others) {
        others?.let { com.deepak.photovault.core.backup.SlotRules.clashes(draft.schedule, zone, it, now) }.orEmpty()
    }
    val suggestion = remember(draft.schedule, others, clashes) {
        if (clashes.isEmpty()) null else others?.let { com.deepak.photovault.core.backup.SlotRules.nearestFree(draft.schedule, zone, it, now) }
    }
    GlassDialog(
        title = if (forGoogle) "Google Drive schedule" else "Schedule",
        onDismiss = onDismiss,
        buttons = {
            GlassButton("Cancel", onClick = onDismiss)
            GlassButton("Save", primary = true, onClick = {
                if (clashes.isNotEmpty()) return@GlassButton
                if (forGoogle) {
                    backup.updateGoogle { draft }
                } else {
                    backup.update { draft }
                    scope.launch { drive.publishSchedule(draft.schedule, zone.id) }
                }
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
            val net = if (draft.mobileData) "online" else "on Wi-Fi"
            Text(
                if (draft.chargingOnly) "Not charging then? It starts once the phone is charging and $net." else "Starts at that time once $net.",
                color = theme.onGlassSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun chosenFolders(albums: List<PhoneAlbum>, folders: Set<String>?): List<String> =
    albums.filter { if (folders == null) BackupSelection.isCamera(it.name) else it.id in folders }.map { it.name }

/** The family drive: how much is backed up, Back up now, and its schedule, folders and options. */
@Composable
internal fun DriveCard(
    drive: DriveState,
    backup: BackupState,
    gallery: GalleryState,
    actions: MediaActions,
    eligible: Int,
    done: Int,
    /** "Family drive" on top, when Google Drive can be chosen too. */
    titled: Boolean,
    open: (BackupSheet) -> Unit,
) {
    val soloScope = rememberCoroutineScope()
    val theme = LocalGlassTheme.current
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
        if (titled) SectionHeader("Family drive")
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
            // Ready (joined, photos allowed): the ring card shows that, not this card (16 Nov clean-up).
            else -> Unit
        }
        Spacer(Modifier.height(6.dp))
        PlaceRows(backup.settings, gallery.albums, open = open)
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

/**
 * Restore to this phone in a popup (14 Nov): bring back backed-up photos that aren't on this phone, from the family
 * drive, Google Drive (15 Nov) or both, following where this phone backs up to.
 */
@Composable
private fun RestoreDialog(
    drive: DriveState,
    backup: BackupState,
    gallery: GalleryState,
    google: GoogleState,
    fromDrive: Boolean,
    fromGoogle: Boolean,
    onDismiss: () -> Unit,
) {
    val theme = LocalGlassTheme.current
    val scope = rememberCoroutineScope()
    // null while looking; -1 when it couldn't be read.
    var driveMissing by remember { mutableStateOf<Int?>(null) }
    var googleMissing by remember { mutableStateOf<List<String>?>(null) }
    var googleFailed by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    suspend fun look() {
        checking = true
        driveMissing = if (fromDrive) (drive.missingOnPhone(gallery.items) ?: -1) else 0
        if (fromGoogle) {
            val list = google.platform.missingOnPhone()
            googleFailed = list == null
            googleMissing = list.orEmpty()
        } else {
            googleMissing = emptyList()
        }
        checking = false
    }
    // Look straight away: the popup was opened to restore.
    LaunchedEffect(Unit) { if (!backup.restoring) look() }
    val rp = backup.restoreProgress
    val d = driveMissing
    val g = googleMissing
    val total = (d?.coerceAtLeast(0) ?: 0) + (g?.size ?: 0)
    val failed = (fromDrive && d == -1) || (fromGoogle && googleFailed)
    GlassDialog(
        title = "Restore to this phone",
        onDismiss = onDismiss,
        buttons = {
            GlassButton(if (backup.restoring) "Close" else "Cancel", onClick = onDismiss)
            if (!backup.restoring && !checking && total > 0) {
                GlassButton("Restore ${LibraryCounts.grouped(total)}", primary = true, icon = Icons.Rounded.CloudDownload, onClick = {
                    val googlePaths = g.orEmpty()
                    val driveToo = (d ?: 0) > 0
                    when {
                        googlePaths.isNotEmpty() -> backup.restoreSelected(googlePaths + (if (driveToo) listOf(RESTORE_ALL_FROM_DRIVE) else emptyList()))
                        driveToo -> backup.restoreMissing()
                    }
                    driveMissing = null
                    googleMissing = null
                })
            }
            if (!backup.restoring && !checking && failed) {
                GlassButton("Try again", onClick = { scope.launch { look() } })
            }
        },
    ) {
        Text(
            "Brings back backed-up photos that aren't on this phone, each to the folder it came from.",
            color = theme.onGlassSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        when {
            backup.restoring -> {
                Text(if (rp == null) "Restoring..." else "Restoring ${rp.first} of ${rp.second}", color = theme.onGlass, style = MaterialTheme.typography.bodyMedium)
                if (rp != null && rp.second > 0) GlassProgressBar(rp.first / rp.second.toFloat(), label = "Restore progress")
                Text("You can close this; it carries on.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
            }
            checking || d == null || g == null -> com.deepak.photovault.designsystem.SkeletonLine(180.dp, 16.dp, label = "Looking for backups")
            else -> {
                if (fromDrive) Text(
                    when (d) {
                        -1 -> "Family drive: couldn't read it. Check you're on the home Wi-Fi."
                        0 -> "Family drive: everything is already on this phone."
                        else -> "Family drive: ${LibraryCounts.grouped(d)} not on this phone."
                    },
                    color = if (d == -1) com.deepak.photovault.designsystem.DangerColor else theme.onGlass,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (fromGoogle) Text(
                    when {
                        googleFailed -> "Google Drive: couldn't read it. Check the internet."
                        g.isEmpty() -> "Google Drive: everything is already on this phone."
                        else -> "Google Drive: ${LibraryCounts.grouped(g.size)} not on this phone."
                    },
                    color = if (googleFailed) com.deepak.photovault.designsystem.DangerColor else theme.onGlass,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        backup.restoreMessage?.let { Text(it, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall) }
    }
}

/** In a chosen restore list: "and everything missing from the family drive too" (15 Nov). */
const val RESTORE_ALL_FROM_DRIVE = "*family-drive-all*"

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


/**
 * Google Drive (Phase 10; tidied 14 Nov): how much is on Google, Back up now, then short rows: the accounts (in their
 * own window), and Google's own schedule, folders and options.
 */
@Composable
internal fun GoogleCard(
    google: GoogleState,
    backup: BackupState,
    view: com.deepak.photovault.core.backup.BackupSettings,
    albums: List<PhoneAlbum>,
    eligible: List<com.deepak.photovault.core.media.MediaItem>,
    open: (BackupSheet) -> Unit,
) {
    val theme = LocalGlassTheme.current
    LaunchedEffect(Unit) { google.refresh(readSpace = true, readRecords = false) }
    GlassCard(Modifier.cardWidth()) {
        SectionHeader("Google Drive")
        // Only shown before an account is added (16 Nov clean-up): with one, the ring card takes over.
        Text(
            "Add a Google account to keep a copy of your photos away from home. Pixallery can only see the files it saves there, nothing else in the account.",
            color = theme.onGlassSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        GlassButton("Add Google account", icon = Icons.Rounded.Add, primary = true, onClick = { google.platform.addAccount() }, modifier = Modifier.padding(top = 12.dp))
    }
}

/** "1 account · 13.6 GB free"; what needs the person comes first. */
internal fun accountsSummary(accounts: List<GoogleAccount>): String {
    val count = if (accounts.size == 1) "1 account" else "${accounts.size} accounts"
    if (accounts.any { it.needsSignIn }) return "Sign in again"
    if (accounts.all { it.full }) return "Full"
    val free = accounts.filter { !it.full }.sumOf { a ->
        val used = a.usedBytes
        val limit = a.limitBytes
        if (used != null && limit != null) (limit - used).coerceAtLeast(0L) else 0L
    }
    return if (free > 0) "$count · ${DetailsFormat.size(free)} free" else count
}

/**
 * The Google accounts, in their own window: each with its space bar (Google's 15 GB is shared with Gmail and Google
 * Photos), and Add another account. Accounts fill in order: when one is full, the next one is used.
 */
@Composable
private fun AccountsDialog(google: GoogleState, onDismiss: () -> Unit) {
    val theme = LocalGlassTheme.current
    val scope = rememberCoroutineScope()
    var removing by remember { mutableStateOf<String?>(null) }
    GlassDialog(title = "Google accounts", onDismiss = onDismiss, buttons = { GlassButton("Done", primary = true, onClick = onDismiss) }) {
        Text(
            if (google.accounts.size > 1) "Filled in this order: when one is full, the next is used." else "Add more accounts for more space: when one is full, the next is used.",
            color = theme.onGlassSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            google.accounts.forEach { a ->
                Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    val used = a.usedBytes
                    val limit = a.limitBytes
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.email, color = theme.onGlass, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(
                                when {
                                    a.needsSignIn -> "Needs you to sign in again"
                                    a.full -> "Full"
                                    used != null && limit != null -> "${DetailsFormat.size(used)} of ${DetailsFormat.size(limit)} used"
                                    else -> "Space not read yet"
                                },
                                color = if (a.needsSignIn || a.full) com.deepak.photovault.designsystem.DangerColor else theme.onGlassSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (a.needsSignIn) GlassButton("Sign in", onClick = { google.platform.signInAgain(a.email) })
                        else GlassIconButton(Icons.Rounded.Close, "Stop backing up to ${a.email}", onClick = { removing = a.email })
                    }
                    if (used != null && limit != null && limit > 0) {
                        val share = (used.toFloat() / limit).coerceIn(0f, 1f)
                        Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(theme.onGlass.copy(alpha = 0.12f))) {
                            Box(Modifier.fillMaxWidth(share).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (share > 0.95f) com.deepak.photovault.designsystem.DangerColor else theme.accent))
                        }
                    }
                }
            }
        }
        GlassButton("Add another account", icon = Icons.Rounded.Add, onClick = { google.platform.addAccount() }, modifier = Modifier.padding(top = 12.dp))
    }
    removing?.let { email ->
        ConfirmDialog(
            title = "Stop backing up to $email?",
            body = "New photos won't go to this account. What is already there stays in its Pixallery folder.",
            confirm = "Stop",
            onDismiss = { removing = null },
        ) {
            removing = null
            scope.launch { google.remove(email) }
        }
    }
}
