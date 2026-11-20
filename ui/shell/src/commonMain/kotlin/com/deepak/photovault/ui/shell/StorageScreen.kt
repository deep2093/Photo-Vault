package com.deepak.photovault.ui.shell

import androidx.compose.material.icons.rounded.CloudUpload
import com.deepak.photovault.core.backup.isDriveOnly
import com.deepak.photovault.designsystem.GlassButton
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepak.photovault.core.backup.BackupSelection
import com.deepak.photovault.core.media.DetailsFormat
import com.deepak.photovault.core.media.LibraryCounts
import com.deepak.photovault.core.media.StorageSlice
import com.deepak.photovault.core.media.StorageStats
import com.deepak.photovault.core.media.StoredFile
import com.deepak.photovault.designsystem.GlassCard
import com.deepak.photovault.designsystem.GlassChip
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.SectionHeader
import kotlinx.datetime.TimeZone

/** Chart colours, chosen to stay apart from each other on light and dark themes. */
private val CHART = listOf(
    Color(0xFFE5483C), Color(0xFFD81B60), Color(0xFF8E24AA), Color(0xFF5E35B1),
    Color(0xFF3949AB), Color(0xFF1E88E5), Color(0xFF00897B), Color(0xFF7CB342),
)

private enum class StorageSource(val label: String) { PHONE("Phone"), DRIVE("Drive"), GOOGLE("Google Drive") }

/**
 * Settings > Storage (24 Oct, after the screenshot Deepak shared): phone or drive, each with how full it is, a ring
 * by file type, photos per year, and the albums taking the most space.
 */
@Composable
internal fun StorageContent(
    state: ShellState,
    gallery: GalleryState,
    drive: DriveState,
    actions: MediaActions,
    startOnDrive: Boolean = false,
    backup: BackupState? = null,
) {
    val theme = LocalGlassTheme.current
    // Remembered across the pages opened from here, so Back returns to the tab you were on.
    // Only places that are set up get a button (14 Nov): the family drive once this phone is in a family, Google once
    // an account is added. With just the phone there are no buttons at all.
    val googleOn = state.google.platform.supported && state.google.accounts.isNotEmpty()
    val driveOn = drive.joined != null
    val source = when {
        state.storageOnGoogle && googleOn -> StorageSource.GOOGLE
        (state.storageOnDrive ?: startOnDrive) && driveOn -> StorageSource.DRIVE
        else -> StorageSource.PHONE
    }
    val sources = StorageSource.entries.filter {
        when (it) {
            StorageSource.PHONE -> true
            StorageSource.DRIVE -> driveOn
            StorageSource.GOOGLE -> googleOn
        }
    }
    val zone = remember { TimeZone.currentSystemDefault() }
    // Only what is on this phone: photos that are only on the drive don't take phone space.
    val phoneFiles = remember(gallery.items) { StorageStats.fromPhone(gallery.items.filterNot { it.isDriveOnly }) }
    LaunchedEffect(startOnDrive) { if (startOnDrive) state.storageOnDrive = true }
    // Read again each time the drive tab opens, so files deleted or backed up since are right.
    LaunchedEffect(source, drive.joined?.me?.userId) {
        if (source == StorageSource.DRIVE && drive.joined != null) {
            drive.loadBackupRecords(force = true)
            drive.refreshUsage()
        }
    }
    val records = drive.backupRecords
    val driveFiles = remember(records, gallery.items) {
        // Album names come with each backup since 24 Oct; older ones are matched to the phone's photos.
        val albumByKey = gallery.items.associate { BackupSelection.key(it) to it.albumName }
        records?.map { r ->
            StoredFile(r.fileName, r.sizeBytes, r.takenAtMillis, r.album ?: albumByKey[r.key] ?: "Not on this phone", r.drivePath, r.isVideo)
        }
    }
    val driveLoading = drive.backupRecordsLoading && records == null
    // Every part of a chart opens the files behind it (23 Oct).
    fun open(onDrive: Boolean, google: Boolean = false) = { title: String, files: List<StoredFile> ->
        if (files.isNotEmpty()) state.open(Overlay.StorageFiles(title, onDrive, files.map { it.ref }, google))
    }
    var cacheVersion by remember { mutableStateOf(0) }
    var showCache by remember { mutableStateOf(false) }
    var showFreeUp by remember { mutableStateOf(false) }
    fun typeOpener(files: List<StoredFile>, types: List<StorageSlice>, onDrive: Boolean, google: Boolean = false) = { i: Int ->
        types.getOrNull(i)?.let { open(onDrive, google)(it.label, StorageStats.ofType(files, types, it.label)) }
        Unit
    }
    Column(Modifier.pageWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 15 Nov: a slim switch, like Back up to.
        if (sources.size > 1) com.deepak.photovault.designsystem.GlassSegmented(
            sources.map { it.label },
            sources.indexOf(source).coerceAtLeast(0),
            { i ->
                val s = sources[i]
                state.storageOnDrive = s == StorageSource.DRIVE
                state.storageOnGoogle = s == StorageSource.GOOGLE
            },
            Modifier.fillMaxWidth(),
        )
        when (source) {
            StorageSource.GOOGLE -> GoogleStorage(state, zone, open(true, google = true)) { files, types -> typeOpener(files, types, true, google = true) }
            StorageSource.PHONE -> {
                val phone = remember { actions.phoneStorage() }
                val media = phoneFiles.sumOf { it.bytes }
                if (phone != null) {
                    val (total, free) = phone
                    val cacheBytes = remember(cacheVersion) { actions.appCacheBytes() }
                    val photoFiles = phoneFiles.filterNot { StorageStats.typeOf(it) == StorageStats.VIDEOS }
                    val videoFiles = phoneFiles.filter { StorageStats.typeOf(it) == StorageStats.VIDEOS }
                    val types = remember(phoneFiles) { StorageStats.byType(phoneFiles) }
                    SpaceCard(
                        "Phone storage",
                        listOf(
                            StorageSlice("Photos", photoFiles.sumOf { it.bytes }, photoFiles.size),
                            StorageSlice("Videos", videoFiles.sumOf { it.bytes }, videoFiles.size),
                            StorageSlice("App cache", cacheBytes, 0),
                            StorageSlice("Free", free, 0),
                        ),
                        "${DetailsFormat.size(free)} free of ${DetailsFormat.size(total)}",
                        canOpen = { i, s -> (i <= 1 && s.count > 0) || i == 2 },
                        onOpen = { i ->
                            when (i) {
                                0 -> open(false)("Photos", photoFiles)
                                1 -> open(false)("Videos", videoFiles)
                                2 -> showCache = true
                            }
                        },
                        types = types,
                        onType = typeOpener(phoneFiles, types, false),
                        typesSummary = "${LibraryCounts.grouped(phoneFiles.size)} files · ${DetailsFormat.size(media)}",
                    )
                } else {
                    GlassCard(Modifier.fillMaxWidth()) {
                        SectionHeader("Phone storage")
                        Text("Photos and videos: ${DetailsFormat.size(media)}", color = theme.onGlass, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Breakdown(phoneFiles, zone, open(false))
                // 15 Nov: the ways to make room, one line each, at the end.
                GlassCard(Modifier.fillMaxWidth()) {
                    SectionHeader("Free up space")
                    if (state.duplicates.platform.supported) {
                        ToolRow(Icons.Rounded.ContentCopy, "Find duplicates", "Copies and near-identical shots") { state.open(Overlay.Duplicates) }
                    }
                    if (backup != null) {
                        ToolRow(Icons.Rounded.PhoneAndroid, "Remove phone copies", "Photos already backed up") { showFreeUp = true }
                    }
                    ToolRow(Icons.Rounded.CleaningServices, "Clear app cache", DetailsFormat.size(remember(cacheVersion) { actions.appCacheBytes() })) { showCache = true }
                }
            }
            StorageSource.DRIVE -> {
                val joined = drive.joined
                if (joined == null) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        SectionHeader("Your backup on the drive")
                        Text("Connect and join your family drive to see this.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    // A ring like the phone's (23 Oct): your backup against your space; the owner also sees the
                    // rest of the family and the drive's free space.
                    val usage = drive.usage[joined.me.userId]
                    val limit = usage?.limitBytes ?: joined.me.limitGb * DriveState.GB
                    val used = usage?.usedBytes ?: driveFiles?.sumOf { it.bytes } ?: 0L
                    val space = drive.space
                    val family = drive.usage.filterKeys { it != joined.me.userId }.values.sumOf { it.usedBytes ?: 0L }
                    // 24 Oct: the owner sees the whole drive (yours, the rest of the family, free); everyone else sees
                    // their own backup, the rest of the family as one total, and what's left of their own space.
                    val slices = if (drive.isOwner && space != null) {
                        listOf(
                            StorageSlice("Your backup", used, driveFiles?.size ?: 0),
                            StorageSlice("Rest of the family", family, 0),
                            StorageSlice("Free on the drive", space.freeBytes, 0),
                        )
                    } else {
                        listOf(
                            StorageSlice("Your backup", used, driveFiles?.size ?: 0),
                            StorageSlice("Rest of the family", family, 0),
                            StorageSlice("Left of your space", (limit - used).coerceAtLeast(0), 0),
                        )
                    }
                    val df = driveFiles.orEmpty()
                    val types = remember(df) { StorageStats.byType(df) }
                    SpaceCard(
                        "Your backup on the drive",
                        slices,
                        "${DetailsFormat.size(used)} of your ${joined.me.limitGb} GB" + (space?.takeIf { drive.isOwner }?.let { " · drive ${DetailsFormat.size(it.freeBytes)} free of ${DetailsFormat.size(it.totalBytes)}" } ?: ""),
                        onOpen = { i -> if (i == 0) driveFiles?.let { open(true)("Your backup", it) } },
                        types = types,
                        onType = typeOpener(df, types, true),
                        typesSummary = "${LibraryCounts.grouped(df.size)} files · ${DetailsFormat.size(df.sumOf { it.bytes })}",
                    )
                }
                val files = driveFiles
                // 10 Nov: the list last read stays on screen while the drive is read again in the background.
                if (joined != null && drive.backupRecordsLoading && files != null) {
                    Text("Updating from the drive…", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
                }
                drive.backupListProblem?.let { com.deepak.photovault.designsystem.WarningNote(it) }
                val usedOnDrive = joined?.let { drive.usage[it.me.userId]?.usedBytes } ?: 0L
                when {
                    joined == null -> Unit
                    // 13 Nov: placeholder rows while the drive is read the first time.
                    driveLoading -> GlassCard(Modifier.fillMaxWidth()) { com.deepak.photovault.designsystem.SkeletonRows(4, label = "Reading the drive") }
                    files == null -> Text("Couldn't read the drive. Check you're on the home Wi-Fi.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
                    // Space is used but the list came back empty: say so plainly instead of "nothing backed up".
                    files.isEmpty() && usedOnDrive > 0 -> Text(
                        "Your backup uses ${DetailsFormat.size(usedOnDrive)}, but its list of photos couldn't be read. Pull down on Photos on the home Wi-Fi, then come back.",
                        color = theme.onGlassSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    files.isEmpty() -> com.deepak.photovault.designsystem.EmptyCard(
                        Icons.Rounded.CloudUpload,
                        "Nothing backed up yet",
                        "Photos you back up to the family drive show here, by type, year and album.",
                        Modifier.fillMaxWidth(),
                    ) { GlassButton("Open Backup", primary = true, onClick = { state.open(Overlay.Backup) }) }
                    // 10 Nov: the drive's albums, below By year like the phone's.
                    else -> Breakdown(files, zone, open(true), albumsTitle = "Backed-up albums")
                }
                if (joined != null) {
                    // 18 Nov: a full-width row like Free up space, so it lines up with the cards above it.
                    val binned = gallery.trashed.count { it.item.isDriveOnly }
                    GlassCard(Modifier.fillMaxWidth()) {
                        ToolRow(Icons.Rounded.Delete, "Recently deleted on the drive", if (binned == 0) "Empty" else if (binned == 1) "1 item" else "${LibraryCounts.grouped(binned)} items") {
                            state.open(Overlay.DriveRecentlyDeleted)
                        }
                    }
                }
            }
        }
    }
    if (showFreeUp && backup != null) FreeUpDialog(backup, actions, onDismiss = { showFreeUp = false })
    if (showCache) {
        val bytes = remember(cacheVersion, showCache) { actions.appCacheBytes() }
        com.deepak.photovault.designsystem.GlassDialog(
            title = "App cache",
            onDismiss = { showCache = false },
            buttons = {
                GlassButton("Close", onClick = { showCache = false })
                GlassButton("Clear cache", primary = true, onClick = {
                    actions.clearAppCache()
                    cacheVersion++
                    showCache = false
                    actions.toast("Cache cleared.")
                })
            },
        ) {
            Text(
                "${DetailsFormat.size(bytes)} of temporary files: previews and photos fetched from the drive. Clearing never deletes your photos or backups; anything needed again is made again.",
                color = theme.onGlass,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}


/**
 * Settings > Storage > Google Drive (14 Nov, option A): All or one account at the top; a ring of Pixallery's photos,
 * its videos, everything else in Google (Gmail, Google Photos, other files: Google only says the total) and free;
 * with more than one account, each account's bar (tap one to see just it); then types, years and albums like the drive.
 */
@Composable
private fun GoogleStorage(
    state: ShellState,
    zone: TimeZone,
    onOpen: (String, List<StoredFile>) -> Unit,
    typeOpener: (List<StoredFile>, List<StorageSlice>) -> (Int) -> Unit,
) {
    val theme = LocalGlassTheme.current
    val google = state.google
    // Last time's numbers at once (kept on the phone), then a fresh check with Google each time the tab opens.
    LaunchedEffect(google.accounts.size) {
        google.loadCached()
        google.loadUsage()
    }
    val usage = google.usage
    if (usage == null) {
        GlassCard(Modifier.fillMaxWidth()) {
            SectionHeader("Google Drive")
            com.deepak.photovault.designsystem.SkeletonRows(3, label = "Reading Google Drive")
        }
        return
    }
    if (usage.isEmpty()) {
        com.deepak.photovault.designsystem.EmptyCard(
            Icons.Rounded.CloudUpload,
            "No Google account yet",
            "Add one in Backup to keep a copy of your photos in Google Drive.",
            Modifier.fillMaxWidth(),
        ) { GlassButton("Open Backup", primary = true, onClick = { state.open(Overlay.Backup) }) }
        return
    }
    val chosenEmail = state.storageGoogleAccount?.takeIf { e -> usage.any { it.email == e } }
    val view = chosenEmail?.let { e -> usage.first { it.email == e } } ?: GoogleUsage.all(usage)
    val files = remember(view) {
        view.records.map { r -> StoredFile(r.fileName, r.sizeBytes, r.takenAtMillis, r.album ?: "Not on this phone", r.drivePath, r.isVideo) }
    }
    if (usage.size > 1) {
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip("All", selected = chosenEmail == null, onClick = { state.storageGoogleAccount = null })
            usage.forEach { u -> GlassChip(u.email.substringBefore('@'), selected = chosenEmail == u.email, onClick = { state.storageGoogleAccount = u.email }) }
        }
    }
    // Tells the person it is checking (15 Nov), while the last numbers stay on screen.
    androidx.compose.animation.AnimatedVisibility(
        visible = google.usageLoading,
        enter = androidx.compose.animation.fadeIn(com.deepak.photovault.designsystem.motionSpec()) + androidx.compose.animation.expandVertically(com.deepak.photovault.designsystem.motionSpec()),
        exit = androidx.compose.animation.fadeOut(com.deepak.photovault.designsystem.motionSpec()) + androidx.compose.animation.shrinkVertically(com.deepak.photovault.designsystem.motionSpec()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), color = theme.onGlassSecondary, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Checking Google Drive for new photos…", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
    val photos = files.filterNot { it.isVideo }
    val videos = files.filter { it.isVideo }
    val types = remember(files) { StorageStats.byType(files) }
    SpaceCard(
        if (chosenEmail != null) chosenEmail else if (usage.size > 1) "All Google accounts" else usage.first().email,
        listOf(
            StorageSlice("Pixallery photos", view.photoBytes, view.photos),
            StorageSlice("Pixallery videos", view.videoBytes, view.videos),
            StorageSlice("Gmail, Google Photos and other files", view.otherBytes, 0),
            StorageSlice("Free", view.freeBytes ?: 0L, 0),
        ),
        when {
            view.limitBytes == null -> "Google hasn't said how big this account is yet."
            chosenEmail == null && usage.size > 1 -> "${DetailsFormat.size(view.freeBytes ?: 0L)} free of ${DetailsFormat.size(view.limitBytes)} across ${usage.size} accounts"
            else -> "${DetailsFormat.size(view.freeBytes ?: 0L)} free of ${DetailsFormat.size(view.limitBytes)}"
        },
        canOpen = { i, sl -> i <= 1 && sl.count > 0 },
        onOpen = { i ->
            when (i) {
                0 -> onOpen("Photos on Google", photos)
                1 -> onOpen("Videos on Google", videos)
            }
        },
        types = types,
        onType = typeOpener(files, types),
        typesSummary = "${LibraryCounts.grouped(files.size)} files · ${DetailsFormat.size(files.sumOf { it.bytes })}",
    )
    // Each account's bar under All, filled in the order they were added.
    if (chosenEmail == null && usage.size > 1) {
        GlassCard(Modifier.fillMaxWidth()) {
            SectionHeader("Accounts")
            Text("Filled in this order: when one is full, the next is used.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
            usage.forEach { u ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClickLabel = "Show ${u.email}", onClick = com.deepak.photovault.designsystem.hapticClick { state.storageGoogleAccount = u.email })
                        .padding(vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(u.email, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        androidx.compose.material3.Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = theme.onGlassSecondary)
                    }
                    val limit = u.limitBytes ?: 0L
                    SpaceBar(
                        listOf(u.photoBytes, u.videoBytes, u.otherBytes),
                        limit.coerceAtLeast(u.pixalleryBytes + u.otherBytes),
                    )
                    Text(
                        listOfNotNull(
                            "${LibraryCounts.grouped(u.photos + u.videos)} items · ${DetailsFormat.size(u.pixalleryBytes)}",
                            u.freeBytes?.let { "${DetailsFormat.size(it)} free" },
                        ).joinToString(" · "),
                        color = theme.onGlassSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    val unread = if (chosenEmail != null) !view.listRead else usage.any { !it.listRead }
    if (unread && !google.usageLoading) {
        com.deepak.photovault.designsystem.WarningNote("Couldn't read the list of photos from Google just now. Check the internet; it tries again each time you open this.")
    }
    if (files.isEmpty() && unread) {
        Unit
    } else if (files.isEmpty()) {
        com.deepak.photovault.designsystem.EmptyCard(
            Icons.Rounded.CloudUpload,
            "Nothing backed up yet",
            "Photos you back up to Google Drive show here, by type, year and album.",
            Modifier.fillMaxWidth(),
        ) { GlassButton("Open Backup", primary = true, onClick = { state.open(Overlay.Backup) }) }
    } else {
        Breakdown(files, zone, onOpen, albumsTitle = "Backed-up albums")
    }
}

/** One account's bar, in the same colours as the ring: photos, videos, the rest of Google; the empty part is free. */
@Composable
private fun SpaceBar(parts: List<Long>, total: Long) {
    val theme = LocalGlassTheme.current
    val sum = total.coerceAtLeast(1L)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(theme.onGlass.copy(alpha = 0.10f)),
    ) {
        parts.forEachIndexed { i, b ->
            val f = (b.toFloat() / sum).coerceIn(0f, 1f)
            if (f > 0.002f) Box(Modifier.weight(f).height(10.dp).background(SPACE_COLORS[i % SPACE_COLORS.size]))
        }
        val rest = 1f - parts.sum().toFloat() / sum
        if (rest > 0.002f) Spacer(Modifier.weight(rest))
    }
}

/**
 * Photos per year as bars and the biggest albums (15 Nov: compact; file types moved into the ring card, one swipe
 * from the space ring). The last 5 years and the 5 biggest albums show, with Show all for the rest.
 */
@Composable
private fun Breakdown(
    files: List<StoredFile>,
    zone: TimeZone,
    onOpen: (String, List<StoredFile>) -> Unit,
    albumsTitle: String = "Albums",
) {
    val theme = LocalGlassTheme.current
    val years = remember(files) { StorageStats.byYear(files, zone) }
    val albums = remember(files) { StorageStats.byAlbum(files) }
    var allYears by remember { mutableStateOf(false) }
    var allAlbums by remember { mutableStateOf(false) }
    fun openAlbum(i: Int) {
        albums.getOrNull(i)?.let { onOpen(it.label, StorageStats.ofAlbum(files, albums, it.label)) }
    }
    @Composable
    fun albumsCard() {
        GlassCard(Modifier.fillMaxWidth()) {
            SectionHeader(albumsTitle)
            UsageBar(albums, albums.sumOf { it.bytes })
            val shown = if (allAlbums) albums else albums.take(5)
            shown.forEachIndexed { i, s ->
                CompactLegend(CHART[i % CHART.size], s.label, "${DetailsFormat.size(s.bytes)} · ${LibraryCounts.grouped(s.count)}", onClick = { openAlbum(i) })
            }
            if (albums.size > 5) ShowAll(allAlbums, albums.size) { allAlbums = !allAlbums }
            if (albums.isEmpty()) Text("No photos yet.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
        }
    }
    @Composable
    fun yearsCard() {
        if (years.size > 1) {
            GlassCard(Modifier.fillMaxWidth()) {
                SectionHeader("By year")
                YearBars(if (allYears) years else years.takeLast(5)) { year -> onOpen(year, StorageStats.ofYear(files, year, zone)) }
                if (years.size > 5) ShowAll(allYears, years.size) { allYears = !allYears }
            }
        }
    }
    // 13 Nov: on a tablet, years on the left and the albums on the right. 18 Nov (Deepak's tablet: the albums card sat
    // alone on the right half): with only one year there is no years card, so the albums take the whole width.
    if (LocalTablet.current && years.size > 1) {
        TabletColumns(left = { yearsCard() }, right = { albumsCard() })
    } else {
        yearsCard()
        albumsCard()
    }
}

/** "Show all (12)" / "Show less" under a shortened list. */
@Composable
private fun ShowAll(open: Boolean, count: Int, onClick: () -> Unit) {
    val theme = LocalGlassTheme.current
    Text(
        if (open) "Show less" else "Show all ($count)",
        color = theme.accent,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(percent = 50))
            .clickable(onClick = com.deepak.photovault.designsystem.hapticClick(onClick))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** One line in the Free up space card: icon, name, a short note, an arrow. */
@Composable
private fun ToolRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, note: String, onClick: () -> Unit) {
    val theme = LocalGlassTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = title, onClick = com.deepak.photovault.designsystem.hapticClick(onClick))
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(icon, null, tint = theme.onGlass, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, color = theme.onGlass, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1)
        Text(note, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp).widthIn(max = 170.dp))
        androidx.compose.material3.Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = theme.onGlassSecondary)
    }
}

private fun shade(c: Color, factor: Float) = Color(c.red * factor, c.green * factor, c.blue * factor, c.alpha)

/** A ring split by size. */
@Composable
internal fun Donut(slices: List<StorageSlice>, modifier: Modifier, colors: List<Color> = CHART, onSlice: ((Int) -> Unit)? = null) {
    // Rings with depth are drawn from solid colours: see-through ones (the Free part) smudged when layered (26 Oct).
    val onGlass = LocalGlassTheme.current.onGlass
    val base = if (onGlass.red + onGlass.green + onGlass.blue > 1.5f) Color(0xFF2B2E36) else Color.White
    val solid = remember(colors, base) {
        colors.map { c -> if (c.alpha >= 0.99f) c else Color(c.red * c.alpha + base.red * (1 - c.alpha), c.green * c.alpha + base.green * (1 - c.alpha), c.blue * c.alpha + base.blue * (1 - c.alpha)) }
    }
    // What the ring draws: every part that has something gets at least a small, visible wedge (26 Oct: PNG and GIF were
    // hair-thin cracks). The list beside the ring keeps the true sizes.
    val weights = remember(slices) { DonutMath.visibleShares(slices.map { it.bytes }).map { (it * 1_000_000).toLong() } }
    val total = weights.sum().coerceAtLeast(1L)
    // Parts touch, split by a thin dark line (28 Oct: the flat ring style was removed).
    val gap = 0f
    val haptics = com.deepak.photovault.designsystem.rememberHaptics()
    // The ring sweeps round clockwise when the screen opens (25 Oct); instant with reduce motion.
    val reduceMotion = com.deepak.photovault.designsystem.LocalGlassSettings.current.reduceMotion
    val grow = remember { androidx.compose.animation.core.Animatable(if (reduceMotion) 1f else 0f) }
    // Starts empty and waits for the page to slide in and for the sizes to arrive, so the whole ring fills, not just the last slice.
    // Only the first time (14 Nov): new sizes arriving a moment later (read again from the drive or Google) no longer
    // empty the ring and sweep it again, which looked like the numbers went away and came back.
    var swept by remember { mutableStateOf(reduceMotion) }
    val hasData = slices.any { it.bytes > 0 }
    LaunchedEffect(hasData) {
        if (!hasData || swept) return@LaunchedEffect
        grow.snapTo(0f)
        kotlinx.coroutines.delay(350)
        grow.animateTo(1f, androidx.compose.animation.core.tween(2000, easing = com.deepak.photovault.designsystem.PageEasing))
        swept = true
    }
    val tap = if (onSlice == null) Modifier else Modifier.pointerInput(slices) {
        detectTapGestures { p ->
            val index = DonutMath.sliceAt(p.x - size.width / 2f, p.y - (size.height - size.height * 0.09f) / 2f, weights)
            if (index != null) {
                haptics.tick()
                onSlice(index)
            }
        }
    }
    Canvas(modifier.then(tap).semantics { contentDescription = slices.joinToString { "${it.label} ${DetailsFormat.size(it.bytes)}" } }) {
        val depthPx = size.minDimension * 0.09f
        val face = size.minDimension - depthPx
        val stroke = face * 0.26f
        val inset = stroke / 2
        val left = (size.width - face) / 2f
        val arcSize = Size(face - stroke, face - stroke)
        val limit = -90f + 360f * grow.value
        run {
            // The ring's thickness: stacked darker copies of each part, hard-edged so nothing bleeds.
            val layers = 12
            for (k in layers downTo 1) {
                val dy = depthPx * k / layers
                var st = -90f
                weights.forEachIndexed { i, w ->
                    val sweep = 360f * w / total
                    val shown = minOf(sweep - gap, limit - st)
                    if (sweep > 0.5f && shown > 0.5f) drawArc(shade(solid[i % solid.size], 0.62f), st, shown.coerceAtLeast(0.5f), false, Offset(left + inset, inset + dy), arcSize, style = Stroke(stroke))
                    st += sweep
                }
            }
        }
        var start = -90f
        val light = androidx.compose.ui.graphics.Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.30f), Color.Transparent, Color.Black.copy(alpha = 0.16f)),
            start = Offset(left, 0f), end = Offset(left + face, face),
        )
        weights.forEachIndexed { i, w ->
            val sweep = 360f * w / total
            val shown = minOf(sweep - gap, limit - start)
            if (sweep > 0.5f && shown > 0.5f) {
                val sw = shown.coerceAtLeast(0.5f)
                drawArc(solid[i % solid.size], start, sw, false, Offset(left + inset, inset), arcSize, style = Stroke(stroke))
                run {
                    drawArc(light, start, sw, false, Offset(left + inset, inset), arcSize, style = Stroke(stroke))
                    // Thin bright rim on the outer edge and a darker line on the inner edge give the rounded look.
                    drawArc(Color.White.copy(alpha = 0.40f), start, sw, false, Offset(left + 1.5f, 1.5f), Size(face - 3f, face - 3f), style = Stroke(3f))
                    drawArc(Color.Black.copy(alpha = 0.18f), start, sw, false, Offset(left + stroke - 1f, stroke - 1f), Size(face - 2 * stroke + 2f, face - 2 * stroke + 2f), style = Stroke(2f))
                }
            }
            start += sweep
        }
        run {
            // Thin dividers where parts meet, on the top face only, once the sweep has passed them.
            val c = Offset(left + face / 2f, face / 2f)
            val outer = face / 2f
            val inner = outer - stroke
            var at = -90f
            val count = weights.count { it > 0 }
            if (count > 1) weights.forEach { w ->
                if (w > 0 && at <= limit) {
                    val r = at * kotlin.math.PI / 180.0
                    val dx = kotlin.math.cos(r).toFloat()
                    val dy = kotlin.math.sin(r).toFloat()
                    drawLine(Color.Black.copy(alpha = 0.30f), Offset(c.x + dx * inner, c.y + dy * inner), Offset(c.x + dx * outer, c.y + dy * outer), strokeWidth = 2f)
                }
                at += 360f * w / total
            }
        }
    }
}

/** One long bar split into coloured parts, out of [total]. */
@Composable
private fun UsageBar(parts: List<StorageSlice>, total: Long) {
    val theme = LocalGlassTheme.current
    val sum = total.coerceAtLeast(1L)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(theme.onGlass.copy(alpha = 0.08f)),
    ) {
        parts.forEachIndexed { i, p ->
            val f = (p.bytes.toFloat() / sum).coerceIn(0f, 1f)
            if (f > 0.002f) Box(Modifier.weight(f).height(12.dp).background(CHART[i % CHART.size]))
        }
        val rest = 1f - parts.sumOf { it.bytes }.toFloat() / sum
        if (rest > 0.002f) Spacer(Modifier.weight(rest))
    }
}

@Composable
private fun YearBars(years: List<StorageSlice>, onYear: (String) -> Unit) {
    val theme = LocalGlassTheme.current
    val max = years.maxOf { it.bytes }.coerceAtLeast(1L)
    val shown = years
    val many = shown.size > 6
    Row(
        Modifier.fillMaxWidth().height(120.dp).then(if (many) Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState(Int.MAX_VALUE)) else Modifier),
        horizontalArrangement = if (many) Arrangement.spacedBy(4.dp) else Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom,
    ) {
        shown.forEach { y ->
            Column(
                Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Show ${y.label}", onClick = com.deepak.photovault.designsystem.hapticClick { onYear(y.label) }).padding(horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(DetailsFormat.size(y.bytes), color = theme.onGlassSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                Box(
                    Modifier
                        .padding(vertical = 4.dp)
                        .width(18.dp)
                        .height((74f * y.bytes / max).coerceAtLeast(3f).dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(theme.accent),
                )
                Text(y.label, color = theme.onGlass, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * A card with a ring and its legend, for how full the phone or your drive space is. Tap a part to see it.
 * 15 Nov: with [types], swipe the ring sideways to see the file types (a second ring); dots show which is showing.
 */
@Composable
private fun SpaceCard(
    title: String,
    slices: List<StorageSlice>,
    summary: String,
    canOpen: (Int, StorageSlice) -> Boolean = { i, s -> i == 0 && s.count > 0 },
    onOpen: (Int) -> Unit,
    types: List<StorageSlice> = emptyList(),
    onType: (Int) -> Unit = {},
    typesSummary: String = "",
) {
    val theme = LocalGlassTheme.current
    val colors = SPACE_COLORS.take(slices.size - 1) + theme.onGlass.copy(alpha = 0.10f)
    val pages = if (types.isEmpty()) 1 else 2
    val pager = androidx.compose.foundation.pager.rememberPagerState { pages }
    val haptics = com.deepak.photovault.designsystem.rememberHaptics()
    LaunchedEffect(pager.settledPage) { if (pages > 1) haptics.tick() }
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(if (pager.currentPage == 0) title else "File types", Modifier.weight(1f))
            if (pages > 1) PagerDots(pages, pager.currentPage)
        }
        androidx.compose.foundation.pager.HorizontalPager(
            pager,
            Modifier.fillMaxWidth(),
            beyondViewportPageCount = 1,
            verticalAlignment = Alignment.Top,
        ) { page ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (page == 0) {
                        Donut(slices, Modifier.size(112.dp), colors = colors, onSlice = onOpen)
                    } else {
                        Donut(types, Modifier.size(112.dp), onSlice = onType)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        if (page == 0) {
                            slices.forEachIndexed { i, s ->
                                val opens = canOpen(i, s)
                                CompactLegend(colors[i], s.label, if (s.count > 0) "${DetailsFormat.size(s.bytes)} · ${LibraryCounts.grouped(s.count)}" else DetailsFormat.size(s.bytes), onClick = if (opens) ({ onOpen(i) }) else null)
                            }
                        } else {
                            types.take(6).forEachIndexed { i, s ->
                                CompactLegend(CHART[i % CHART.size], s.label, "${DetailsFormat.size(s.bytes)} · ${LibraryCounts.grouped(s.count)}", onClick = { onType(i) })
                            }
                        }
                    }
                }
                Text(
                    if (page == 0) summary else typesSummary,
                    color = theme.onGlassSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** Small dots for the ring card's pages; the current one is wider. */
@Composable
internal fun PagerDots(count: Int, current: Int) {
    val theme = LocalGlassTheme.current
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val on = i == current
            Box(
                Modifier
                    .height(6.dp)
                    .width(if (on) 16.dp else 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (on) theme.onGlass else theme.onGlass.copy(alpha = 0.3f)),
            )
        }
    }
}

/** One line of a legend (15 Nov): dot, name, size and count on the right, an arrow when it opens a list. */
@Composable
internal fun CompactLegend(color: Color, label: String, detail: String, onClick: (() -> Unit)? = null) {
    val theme = LocalGlassTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "Show $label", onClick = com.deepak.photovault.designsystem.hapticClick(onClick)) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(detail, color = theme.onGlassSecondary, style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
        if (onClick != null) {
            androidx.compose.material3.Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = theme.onGlassSecondary, modifier = Modifier.size(18.dp))
        } else {
            Spacer(Modifier.width(18.dp))
        }
    }
}

/** First part in the accent-like blue, then soft greys; the last ("Free", "Left") is drawn as empty track. */
private val SPACE_COLORS = listOf(Color(0xFF1E88E5), Color(0xFF8E24AA), Color(0xFF9E9EA7))

/** Which slice of a ring a tap landed on, tested. */
object DonutMath {
    /** [dx], [dy] from the centre. Slices start at the top and go clockwise; null when there is nothing to show. */
    /**
     * Each part's share of the ring as drawn: a part with anything in it gets at least [min] (3%), and the larger parts
     * give up a little room for it. Empty parts stay empty. Shares add up to 1.
     */
    fun visibleShares(sizes: List<Long>, min: Double = 0.03): List<Double> {
        val total = sizes.sum()
        if (total <= 0L) return sizes.map { 0.0 }
        val raw = sizes.map { it.toDouble() / total }
        val small = raw.map { it > 0.0 && it < min }
        val reserved = min * small.count { it }
        val bigSum = raw.filterIndexed { i, _ -> !small[i] }.sum()
        if (reserved >= 1.0 || bigSum <= 0.0) {
            val n = raw.count { it > 0.0 }
            return raw.map { if (it > 0.0) 1.0 / n else 0.0 }
        }
        return raw.mapIndexed { i, r -> if (small[i]) min else r / bigSum * (1.0 - reserved) }
    }

    fun sliceAt(dx: Float, dy: Float, sizes: List<Long>): Int? {
        val total = sizes.sum()
        if (total <= 0L) return null
        // 0 degrees at the top, growing clockwise.
        var angle = kotlin.math.atan2(dx.toDouble(), -dy.toDouble()) * 180.0 / kotlin.math.PI
        if (angle < 0) angle += 360.0
        var start = 0.0
        sizes.forEachIndexed { i, s ->
            val sweep = 360.0 * s / total
            if (angle < start + sweep) return i
            start += sweep
        }
        return sizes.lastIndex
    }
}
