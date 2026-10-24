package com.deepak.photovault.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.foundation.clickable
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

private enum class StorageSource(val label: String) { PHONE("Phone"), DRIVE("Drive") }

/**
 * Settings > Storage (24 Oct, after the screenshot Deepak shared): phone or drive, each with how full it is, a ring
 * by file type, photos per year, and the albums taking the most space.
 */
@Composable
internal fun StorageContent(state: ShellState, gallery: GalleryState, drive: DriveState, actions: MediaActions, startOnDrive: Boolean = false) {
    val theme = LocalGlassTheme.current
    var source by remember { mutableStateOf(if (startOnDrive) StorageSource.DRIVE else StorageSource.PHONE) }
    val zone = remember { TimeZone.currentSystemDefault() }
    val phoneFiles = remember(gallery.items) { StorageStats.fromPhone(gallery.items) }
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
    fun open(onDrive: Boolean) = { title: String, files: List<StoredFile> ->
        if (files.isNotEmpty()) state.open(Overlay.StorageFiles(title, onDrive, files.map { it.ref }))
    }
    Column(Modifier.cardWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StorageSource.entries.forEach { s -> GlassChip(s.label, selected = source == s, onClick = { source = s }) }
        }
        when (source) {
            StorageSource.PHONE -> {
                val phone = remember { actions.phoneStorage() }
                val media = phoneFiles.sumOf { it.bytes }
                if (phone != null) {
                    val (total, free) = phone
                    val other = (total - free - media).coerceAtLeast(0)
                    SpaceCard(
                        "Phone storage",
                        listOf(
                            StorageSlice("Photos and videos", media, phoneFiles.size),
                            StorageSlice("Everything else", other, 0),
                            StorageSlice("Free", free, 0),
                        ),
                        "${DetailsFormat.size(free)} free of ${DetailsFormat.size(total)}",
                        onOpen = { i -> if (i == 0) open(false)("Photos and videos", phoneFiles) },
                    )
                } else {
                    GlassCard(Modifier.fillMaxWidth()) {
                        SectionHeader("Phone storage")
                        Text("Photos and videos: ${DetailsFormat.size(media)}", color = theme.onGlass, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Breakdown(phoneFiles, zone, open(false))
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
                    SpaceCard(
                        "Your backup on the drive",
                        slices,
                        "${DetailsFormat.size(used)} of your ${joined.me.limitGb} GB" + (space?.takeIf { drive.isOwner }?.let { " · drive ${DetailsFormat.size(it.freeBytes)} free of ${DetailsFormat.size(it.totalBytes)}" } ?: ""),
                        onOpen = { i -> if (i == 0) driveFiles?.let { open(true)("Your backup", it) } },
                    )
                }
                val files = driveFiles
                when {
                    joined == null -> Unit
                    driveLoading -> Text("Reading the drive...", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
                    files == null -> Text("Couldn't read the drive. Check you're on the home Wi-Fi.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
                    files.isEmpty() -> Text("Nothing backed up yet.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
                    else -> Breakdown(files, zone, open(true))
                }
            }
        }
    }
}

/** File types as a ring, photos per year as bars, and the biggest albums. */
@Composable
private fun Breakdown(files: List<StoredFile>, zone: TimeZone, onOpen: (String, List<StoredFile>) -> Unit) {
    val theme = LocalGlassTheme.current
    val types = remember(files) { StorageStats.byType(files) }
    val years = remember(files) { StorageStats.byYear(files, zone) }
    val albums = remember(files) { StorageStats.byAlbum(files) }
    fun openType(i: Int) {
        types.getOrNull(i)?.let { onOpen(it.label, StorageStats.ofType(files, types, it.label)) }
    }
    fun openAlbum(i: Int) {
        albums.getOrNull(i)?.let { onOpen(it.label, StorageStats.ofAlbum(files, albums, it.label)) }
    }
    GlassCard(Modifier.fillMaxWidth()) {
        SectionHeader("File types")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Donut(types, Modifier.size(132.dp), onSlice = ::openType)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                types.forEachIndexed { i, s -> Legend(CHART[i % CHART.size], s.label, "${DetailsFormat.size(s.bytes)} · ${LibraryCounts.grouped(s.count)}", onClick = { openType(i) }) }
            }
        }
    }
    if (years.size > 1) {
        GlassCard(Modifier.fillMaxWidth()) {
            SectionHeader("By year")
            YearBars(years) { year -> onOpen(year, StorageStats.ofYear(files, year, zone)) }
        }
    }
    GlassCard(Modifier.fillMaxWidth()) {
        SectionHeader("Albums")
        UsageBar(albums, albums.sumOf { it.bytes })
        Spacer(Modifier.height(8.dp))
        albums.forEachIndexed { i, s ->
            Legend(CHART[i % CHART.size], s.label, "${DetailsFormat.size(s.bytes)} · ${LibraryCounts.grouped(s.count)} items", onClick = { openAlbum(i) })
        }
        if (albums.isEmpty()) Text("No photos yet.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A ring split by size. */
@Composable
private fun Donut(slices: List<StorageSlice>, modifier: Modifier, colors: List<Color> = CHART, onSlice: ((Int) -> Unit)? = null) {
    val total = slices.sumOf { it.bytes }.coerceAtLeast(1L)
    val track = LocalGlassTheme.current.onGlass.copy(alpha = 0.08f)
    val haptics = com.deepak.photovault.designsystem.rememberHaptics()
    // The ring sweeps round clockwise when the screen opens (25 Oct); instant with reduce motion.
    val reduceMotion = com.deepak.photovault.designsystem.LocalGlassSettings.current.reduceMotion
    val grow = remember { androidx.compose.animation.core.Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduceMotion) grow.animateTo(1f, androidx.compose.animation.core.tween(900, easing = com.deepak.photovault.designsystem.PageEasing))
    }
    val tap = if (onSlice == null) Modifier else Modifier.pointerInput(slices) {
        detectTapGestures { p ->
            val index = DonutMath.sliceAt(p.x - size.width / 2f, p.y - size.height / 2f, slices.map { it.bytes })
            if (index != null) {
                haptics.tick()
                onSlice(index)
            }
        }
    }
    Canvas(modifier.then(tap).semantics { contentDescription = slices.joinToString { "${it.label} ${DetailsFormat.size(it.bytes)}" } }) {
        val stroke = size.minDimension * 0.22f
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        var start = -90f
        val limit = -90f + 360f * grow.value
        slices.forEachIndexed { i, s ->
            val sweep = 360f * s.bytes / total
            val shown = minOf(sweep - 1.2f, limit - start)
            if (sweep > 0.5f && shown > 0.5f) drawArc(colors[i % colors.size], start, shown.coerceAtLeast(0.5f), false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            start += sweep
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
    val shown = years.takeLast(6)
    Row(Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
        shown.forEach { y ->
            Column(
                Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Show ${y.label}") { onYear(y.label) }.padding(horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(DetailsFormat.size(y.bytes), color = theme.onGlassSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                Box(
                    Modifier
                        .padding(vertical = 4.dp)
                        .width(18.dp)
                        .height((90f * y.bytes / max).coerceAtLeast(3f).dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(theme.accent),
                )
                Text(y.label, color = theme.onGlass, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String, detail: String, onClick: (() -> Unit)? = null) {
    val theme = LocalGlassTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "Show $label", onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
        }
        if (onClick != null) {
            androidx.compose.material3.Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = theme.onGlassSecondary)
        }
    }
}

/** A card with a ring and its legend, for how full the phone or your drive space is. Tap a part to see it. */
@Composable
private fun SpaceCard(title: String, slices: List<StorageSlice>, summary: String, onOpen: (Int) -> Unit) {
    val theme = LocalGlassTheme.current
    val colors = SPACE_COLORS.take(slices.size - 1) + theme.onGlass.copy(alpha = 0.10f)
    GlassCard(Modifier.fillMaxWidth()) {
        SectionHeader(title)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Donut(slices, Modifier.size(132.dp), colors = colors, onSlice = onOpen)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                slices.forEachIndexed { i, s ->
                    val opens = i == 0 && s.count > 0
                    Legend(colors[i], s.label, if (s.count > 0) "${DetailsFormat.size(s.bytes)} · ${LibraryCounts.grouped(s.count)}" else DetailsFormat.size(s.bytes), onClick = if (opens) ({ onOpen(i) }) else null)
                }
            }
        }
        Text(summary, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

/** First part in the accent-like blue, then soft greys; the last ("Free", "Left") is drawn as empty track. */
private val SPACE_COLORS = listOf(Color(0xFF1E88E5), Color(0xFF8E24AA), Color(0xFF9E9EA7))

/** Which slice of a ring a tap landed on, tested. */
object DonutMath {
    /** [dx], [dy] from the centre. Slices start at the top and go clockwise; null when there is nothing to show. */
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
