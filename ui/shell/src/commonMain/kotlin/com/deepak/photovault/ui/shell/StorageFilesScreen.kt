package com.deepak.photovault.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.deepak.photovault.core.media.DetailsFormat
import com.deepak.photovault.core.media.FileOrder
import com.deepak.photovault.core.media.FileSort
import com.deepak.photovault.core.media.LibraryCounts
import com.deepak.photovault.core.media.StorageStats
import com.deepak.photovault.core.media.StoredFile
import com.deepak.photovault.core.media.Thumb
import com.deepak.photovault.core.backup.BackupSelection
import com.deepak.photovault.core.backup.isDriveOnly
import com.deepak.photovault.core.backup.DriveTimeline
import com.deepak.photovault.core.media.Timeline
import com.deepak.photovault.designsystem.accentSurface
import com.deepak.photovault.designsystem.GlassIconButton
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.WarningNote
import com.deepak.photovault.designsystem.glassPanel
import com.deepak.photovault.designsystem.motionSpec
import com.deepak.photovault.designsystem.rememberHaptics
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Settings > Storage > a slice of a chart (23 Oct): the files behind it, sorted by name, date or size (tap the
 * same button again to turn the order round; the arrow shows which way). Long-press and slide to pick many, or
 * Select all. Phone files: open, share, move to Recently deleted. Drive backups: save back to the phone, or
 * delete from the drive after a warning.
 */
@Composable
fun StorageFilesScreen(
    page: Overlay.StorageFiles,
    state: ShellState,
    gallery: GalleryState,
    drive: DriveState,
    backup: BackupState,
    actions: MediaActions,
) {
    val theme = LocalGlassTheme.current
    val zone = remember { TimeZone.currentSystemDefault() }
    val scope = rememberCoroutineScope()
    val flight = LocalDeleteFlight.current
    val prompt = LocalDeletePrompt.current
    val files: List<StoredFile> = remember(page, gallery.items, drive.backupRecords) {
        if (page.onDrive) {
            val byPath = drive.backupRecords.orEmpty().associateBy { it.drivePath }
            page.refs.mapNotNull { path -> byPath[path]?.let { r -> StoredFile(r.fileName, r.sizeBytes, r.takenAtMillis, r.album.orEmpty(), r.drivePath, r.isVideo) } }
        } else {
            StorageStats.fromPhone(page.refs.mapNotNull { it.toLongOrNull()?.let(gallery::find) })
        }
    }
    // Backups open full screen: the photo on this phone if it is still here, else the drive's copy (one timeline).
    val openable: Map<String, Long> = remember(page, gallery.items, drive.backupRecords) {
        if (!page.onDrive) emptyMap() else {
            val byKey = gallery.items.associateBy { BackupSelection.key(it) }
            val byNameSize = gallery.items.associateBy { "${it.displayName}|${it.sizeBytes}" }
            drive.backupRecords.orEmpty().mapNotNull { r ->
                val id = byKey[r.key]?.id ?: byNameSize["${r.fileName}|${r.sizeBytes}"]?.id
                    ?: DriveTimeline.idOf(r.drivePath).takeIf { gallery.find(it) != null }
                id?.let { r.drivePath to it }
            }.toMap()
        }
    }
    var order by remember { mutableStateOf(FileOrder()) }
    val sorted = remember(files, order) { StorageStats.sorted(files, order) }
    val selected = state.filesSelected
    val selecting = selected.isNotEmpty()
    var confirmDelete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val bin = remember { ScreenSpot() }
    val thumbs = remember { HashMap<String, Rect>() }
    val chosen = sorted.filter { it.ref in selected }

    GlassScreen(
        title = if (selecting) "${selected.size} selected" else page.title,
        subtitle = if (selecting) null else "${LibraryCounts.grouped(files.size)} · ${DetailsFormat.size(files.sumOf { it.bytes })}",
        onBack = if (selecting) null else ({ state.back() }),
        pinned = selecting,
        actions = {
            if (selecting) {
                GlassIconButton(Icons.Rounded.SelectAll, "Select all", onClick = { state.filesSelected = sorted.map { it.ref }.toSet() })
                GlassIconButton(Icons.Rounded.Deselect, "Deselect all", onClick = { state.filesSelected = emptySet() })
                GlassIconButton(Icons.Rounded.Close, "Cancel selection", onClick = { state.filesSelected = emptySet() })
            }
        },
        bottomActions = if (!selecting) null else ({
            if (page.onDrive) {
                GlassIconButton(Icons.Rounded.Download, "Save to phone", onClick = {
                    backup.restoreSelected(chosen.map { it.ref })
                    message = "Saving ${LibraryCounts.grouped(chosen.size)} to the phone's \"Restored\" album. Ones already on the phone are skipped."
                    state.filesSelected = emptySet()
                })
                GlassIconButton(Icons.Rounded.DeleteForever, "Delete from drive", modifier = Modifier.trackSpot(bin), onClick = { confirmDelete = true })
            } else {
                GlassIconButton(Icons.Rounded.Share, "Share", onClick = { actions.share(chosen.mapNotNull { it.ref.toLongOrNull()?.let(gallery::find) }) })
                GlassIconButton(Icons.Rounded.Delete, "Move to Recently deleted", modifier = Modifier.trackSpot(bin), onClick = {
                    val items = chosen.mapNotNull { it.ref.toLongOrNull()?.let(gallery::find) }
                    val flying = items.mapNotNull { item -> thumbs[item.id.toString()]?.let { FlyingPhoto(item.uri, item.isVideo, it) } }
                    prompt.trash(actions, items) { agreed ->
                        if (agreed) {
                            flight.launch(flying, bin.bounds)
                            gallery.dropNow(items.map { it.id }.toSet())
                            state.filesSelected = emptySet()
                        }
                    }
                })
            }
        }),
    ) { padding ->
        val list = rememberSavedListState()
        ScrollToTopEffect(list)
        val density = LocalDensity.current
        val haptics = LocalHapticFeedback.current
        val currentSorted by rememberUpdatedState(sorted)
        var heightPx by remember { mutableStateOf(0f) }
        val topPx = with(density) { padding.calculateTopPadding().toPx() }
        val bottomPx = with(density) { padding.calculateBottomPadding().toPx() }
        val handler = remember(list, page.onDrive, openable) {
            object : PhotoGridGestureHandler {
                private var anchor = -1
                private var adding = true
                private var initial: Set<Long> = emptySet()
                private var finger = NOWHERE
                private var autoScroll: Job? = null

                private fun indexOf(ref: String) = currentSorted.indexOfFirst { it.ref == ref }

                override fun photoAt(position: Offset): Long? {
                    val info = list.layoutInfo
                    val row = info.visibleItemsInfo.firstOrNull { item ->
                        val top = item.offset - info.viewportStartOffset
                        item.key is String && position.y >= top && position.y < top + item.size
                    } ?: return null
                    return indexOf(row.key as String).takeIf { it >= 0 }?.toLong()
                }

                override fun onTap(id: Long) {
                    val file = currentSorted.getOrNull(id.toInt()) ?: return
                    when {
                        state.filesSelected.isNotEmpty() -> state.filesSelected = state.filesSelected.toggled(file.ref)
                        page.onDrive -> {
                            val ids = currentSorted.mapNotNull { openable[it.ref] }
                            val id = openable[file.ref]
                            if (id != null) state.open(Overlay.Viewer(ids, id)) else actions.toast("This one isn't in your timeline yet. Pull down on Photos to refresh.")
                        }
                        else -> state.open(Overlay.Viewer(currentSorted.mapNotNull { it.ref.toLongOrNull() }, file.ref.toLong()))
                    }
                }

                override fun onDragSelectStart(id: Long) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val all = currentSorted
                    initial = all.indices.filter { all[it].ref in state.filesSelected }.map { it.toLong() }.toSet()
                    anchor = id.toInt()
                    adding = id !in initial
                    apply(anchor)
                    autoScroll = scope.launch {
                        val zone = with(density) { 96.dp.toPx() }
                        val maxSpeed = with(density) { 22.dp.toPx() }
                        while (isActive) {
                            val speed = DragSelect.edgeSpeed(finger.y, topPx, heightPx - bottomPx, zone, maxSpeed)
                            if (speed != 0f) {
                                list.scrollBy(speed)
                                onDragSelectMove(finger)
                            }
                            withFrameNanos { }
                        }
                    }
                }

                private fun apply(current: Int) {
                    val all = currentSorted
                    val order = all.indices.map { it.toLong() }
                    state.filesSelected = DragSelect.selection(initial, order, anchor, current, adding).mapNotNull { all.getOrNull(it.toInt())?.ref }.toSet()
                }

                override fun onDragSelectMove(position: Offset) {
                    finger = position
                    val index = photoAt(position) ?: return
                    apply(index.toInt())
                }

                override fun onDragSelectEnd() {
                    autoScroll?.cancel()
                    autoScroll = null
                    finger = NOWHERE
                }
            }
        }
        val refreshList: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = list,
                contentPadding = padding,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { heightPx = it.height.toFloat() }
                    .selectGestures({ list.isScrollInProgress }, handler),
            ) {
                item(key = "sort", contentType = "sort") {
                    Column(Modifier.cardWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SortBar(order) { order = order.tapped(it) }
                        message?.let { WarningNote(it) }
                        if (page.onDrive) {
                            Text(
                                "Tap to open. Press and hold to pick backups.",
                                color = theme.onGlassSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (files.isEmpty()) Text("Nothing here any more.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                items(sorted, key = { it.ref }, contentType = { "file" }) { file ->
                    val item = if (page.onDrive) null else file.ref.toLongOrNull()?.let(gallery::find)
                    FileRow(
                        file = file,
                        // The copy still on this phone is shown when there is one, so the drive is not read for it (30 Oct).
                    thumbUri = if (page.onDrive) (openable[file.ref]?.let(gallery::find)?.takeUnless { it.isDriveOnly }?.uri ?: DriveTimeline.uriOf(file.ref)) else item?.uri,
                        when_ = takenText(file.takenAtMillis, zone),
                        selected = file.ref in selected,
                        selecting = selecting,
                        onThumbPlaced = { thumbs[file.ref] = it },
                        modifier = Modifier.cardWidth().animateItem(),
                    )
                }
            }
            ListFastScroller(
                list = list,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding(),
                labelAt = { index -> fileLabel(sorted.getOrNull((index - 1).coerceAtLeast(0)), order, zone) },
                modifier = Modifier.align(Alignment.TopEnd),
            )
            }
        }
        if (page.onDrive) PullToRefresh(padding, onRefresh = {
            drive.refresh()
            if (drive.joined != null) {
                drive.loadBackupRecords(force = true)
                drive.refreshUsage(force = true)
            }
        }, content = refreshList) else refreshList()
    }

    if (confirmDelete) {
        val bytes = chosen.sumOf { it.bytes }
        ConfirmDialog(
            title = "Delete ${LibraryCounts.grouped(chosen.size)} from the drive?",
            body = "These backups (${DetailsFormat.size(bytes)}) are removed from the family drive for good. Nothing on this phone is deleted. " +
                "Any that are still on this phone will be backed up again next time, unless you delete them from the phone too.",
            confirm = "Delete",
            onDismiss = { confirmDelete = false },
        ) {
            confirmDelete = false
            val paths = chosen.map { it.ref }.toSet()
            val keys = drive.backupRecords.orEmpty().filter { it.drivePath in paths }.map { it.key }.toSet()
            scope.launch {
                val error = drive.deleteBackups(paths)
                if (error == null) {
                    runCatching { backup.forgetBackedUp(keys) }
                    state.filesSelected = emptySet()
                    message = "Deleted ${LibraryCounts.grouped(paths.size)} from the drive."
                    drive.refreshUsage(force = true)
                } else {
                    message = error
                }
            }
        }
    }
}

private fun Set<String>.toggled(ref: String) = if (ref in this) this - ref else this + ref

private fun takenText(millis: Long, zone: TimeZone): String {
    val t = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
    val month = t.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
    val hour = if (t.hour % 12 == 0) 12 else t.hour % 12
    return "${t.dayOfMonth} $month ${t.year}, $hour:${t.minute.toString().padStart(2, '0')} ${if (t.hour < 12) "am" else "pm"}"
}

/** Name, Date, Size: tap to sort by it, tap again to turn the order round. The arrow shows the direction. */
@Composable
private fun SortBar(order: FileOrder, onTap: (FileSort) -> Unit) {
    val theme = LocalGlassTheme.current
    val haptics = rememberHaptics()
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Sort", color = theme.onGlassSecondary, style = MaterialTheme.typography.labelLarge)
        FileSort.entries.forEach { key ->
            val active = order.by == key
            Row(
                Modifier
                    .heightIn(min = 40.dp)
                    .glassPanel(RoundedCornerShape(percent = 50))
                    .background(if (active) theme.accentSurface else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected = active, role = Role.Button, onClick = {
                        haptics.tick()
                        onTap(key)
                    })
                    .semantics {
                        contentDescription = if (active) "${key.label}, ${if (order.ascending) "ascending" else "descending"}. Tap to reverse." else "Sort by ${key.label.lowercase()}"
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val fg = if (active) theme.onAccent else theme.onGlass
                Text(key.label, color = fg, style = MaterialTheme.typography.labelLarge)
                if (active) {
                    Spacer(Modifier.width(4.dp))
                    Icon(if (order.ascending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward, null, tint = fg, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    file: StoredFile,
    thumbUri: String?,
    when_: String,
    selected: Boolean,
    selecting: Boolean,
    onThumbPlaced: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalGlassTheme.current
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) theme.accent.copy(alpha = 0.18f) else theme.glassFallback)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${file.name}, $when_, ${DetailsFormat.size(file.bytes)}${if (selected) ", selected" else ""}"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(theme.onGlass.copy(alpha = 0.08f))
                .onGloballyPositioned { onThumbPlaced(it.boundsInRoot()) },
            contentAlignment = Alignment.Center,
        ) {
            if (thumbUri != null) {
                AsyncImage(Thumb(thumbUri, file.isVideo, 128), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(if (file.isVideo) Icons.Rounded.Movie else Icons.Rounded.Image, null, tint = theme.onGlassSecondary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$when_ · ${DetailsFormat.size(file.bytes)}", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        AnimatedVisibility(visible = selecting, enter = fadeIn(motionSpec()) + scaleIn(motionSpec()), exit = fadeOut(motionSpec()) + scaleOut(motionSpec())) {
            Box(
                Modifier
                    .padding(start = 8.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (selected) theme.accentSurface else theme.onGlass.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = theme.onAccent, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** What the side slider says for a file: the first letter, the month, the size or the type, following the sort. */
private fun fileLabel(file: StoredFile?, order: FileOrder, zone: TimeZone): String {
    file ?: return ""
    return when (order.by) {
        FileSort.NAME -> file.name.trim().firstOrNull()?.uppercaseChar()?.toString().orEmpty()
        FileSort.DATE -> {
            val t = Instant.fromEpochMilliseconds(file.takenAtMillis).toLocalDateTime(zone)
            "${t.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)} ${t.year}"
        }
        FileSort.SIZE -> DetailsFormat.size(file.bytes)
        FileSort.TYPE -> StorageStats.typeOf(file)
    }
}
