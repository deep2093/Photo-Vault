package com.deepak.photovault.ui.shell

import androidx.compose.foundation.Canvas
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
internal fun StorageContent(gallery: GalleryState, drive: DriveState, actions: MediaActions) {
    val theme = LocalGlassTheme.current
    var source by remember { mutableStateOf(StorageSource.PHONE) }
    var driveFiles by remember { mutableStateOf<List<StoredFile>?>(null) }
    var driveLoading by remember { mutableStateOf(false) }
    val zone = remember { TimeZone.currentSystemDefault() }
    val phoneFiles = remember(gallery.items) { StorageStats.fromPhone(gallery.items) }
    LaunchedEffect(source, drive.joined?.me?.userId) {
        if (source == StorageSource.DRIVE && driveFiles == null && drive.joined != null) {
            driveLoading = true
            // Album names come with each backup since 24 Oct; older ones are matched to the phone's photos.
            val albumByKey = gallery.items.associate { BackupSelection.key(it) to it.albumName }
            driveFiles = drive.myBackupRecords()?.map { r ->
                StoredFile(r.fileName, r.sizeBytes, r.takenAtMillis, r.album ?: albumByKey[r.key] ?: "Not on this phone")
            }
            driveLoading = false
        }
    }
    Column(Modifier.cardWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StorageSource.entries.forEach { s -> GlassChip(s.label, selected = source == s, onClick = { source = s }) }
        }
        when (source) {
            StorageSource.PHONE -> {
                val phone = remember { actions.phoneStorage() }
                GlassCard(Modifier.fillMaxWidth()) {
                    SectionHeader("Phone storage")
                    val media = phoneFiles.sumOf { it.bytes }
                    if (phone != null) {
                        val (total, free) = phone
                        val other = (total - free - media).coerceAtLeast(0)
                        UsageBar(
                            listOf(StorageSlice("Photos and videos", media, phoneFiles.size), StorageSlice("Everything else", other, 0)),
                            total,
                        )
                        Text("${DetailsFormat.size(free)} free of ${DetailsFormat.size(total)}", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("Photos and videos: ${DetailsFormat.size(media)}", color = theme.onGlass, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Breakdown(phoneFiles, zone)
            }
            StorageSource.DRIVE -> {
                val joined = drive.joined
                GlassCard(Modifier.fillMaxWidth()) {
                    SectionHeader("Your backup on the drive")
                    if (joined == null) {
                        Text("Connect and join your family drive to see this.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        val usage = drive.usage[joined.me.userId]
                        val limit = usage?.limitBytes ?: joined.me.limitGb * DriveState.GB
                        val used = usage?.usedBytes ?: 0L
                        UsageBar(listOf(StorageSlice("Your backup", used, 0)), limit)
                        Text(
                            "${DetailsFormat.size(used)} of your ${joined.me.limitGb} GB · ${DetailsFormat.size((limit - used).coerceAtLeast(0))} left",
                            color = theme.onGlassSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // The whole drive is the owner's business only.
                        if (drive.isOwner) {
                            drive.space?.let {
                                Text(
                                    "Whole drive: ${DetailsFormat.size(it.freeBytes)} free of ${DetailsFormat.size(it.totalBytes)}",
                                    color = theme.onGlassSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                val files = driveFiles
                when {
                    joined == null -> Unit
                    driveLoading -> Text("Reading the drive...", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
                    files == null -> Text("Couldn't read the drive. Check you're on the home Wi-Fi.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
                    files.isEmpty() -> Text("Nothing backed up yet.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
                    else -> Breakdown(files, zone)
                }
            }
        }
    }
}

/** File types as a ring, photos per year as bars, and the biggest albums. */
@Composable
private fun Breakdown(files: List<StoredFile>, zone: TimeZone) {
    val theme = LocalGlassTheme.current
    val types = remember(files) { StorageStats.byType(files) }
    val years = remember(files) { StorageStats.byYear(files, zone) }
    val albums = remember(files) { StorageStats.byAlbum(files) }
    GlassCard(Modifier.fillMaxWidth()) {
        SectionHeader("File types")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Donut(types, Modifier.size(132.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                types.forEachIndexed { i, s -> Legend(CHART[i % CHART.size], s.label, "${DetailsFormat.size(s.bytes)} · ${LibraryCounts.grouped(s.count)}") }
            }
        }
    }
    if (years.size > 1) {
        GlassCard(Modifier.fillMaxWidth()) {
            SectionHeader("By year")
            YearBars(years)
        }
    }
    GlassCard(Modifier.fillMaxWidth()) {
        SectionHeader("Albums")
        UsageBar(albums, albums.sumOf { it.bytes })
        Spacer(Modifier.height(8.dp))
        albums.forEachIndexed { i, s ->
            Legend(CHART[i % CHART.size], s.label, "${DetailsFormat.size(s.bytes)} · ${LibraryCounts.grouped(s.count)} items")
            Spacer(Modifier.height(6.dp))
        }
        if (albums.isEmpty()) Text("No photos yet.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A ring split by size. */
@Composable
private fun Donut(slices: List<StorageSlice>, modifier: Modifier) {
    val total = slices.sumOf { it.bytes }.coerceAtLeast(1L)
    val track = LocalGlassTheme.current.onGlass.copy(alpha = 0.08f)
    Canvas(modifier.semantics { contentDescription = slices.joinToString { "${it.label} ${DetailsFormat.size(it.bytes)}" } }) {
        val stroke = size.minDimension * 0.22f
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        var start = -90f
        slices.forEachIndexed { i, s ->
            val sweep = 360f * s.bytes / total
            if (sweep > 0.5f) drawArc(CHART[i % CHART.size], start, (sweep - 1.2f).coerceAtLeast(0.5f), false, Offset(inset, inset), arcSize, style = Stroke(stroke))
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
private fun YearBars(years: List<StorageSlice>) {
    val theme = LocalGlassTheme.current
    val max = years.maxOf { it.bytes }.coerceAtLeast(1L)
    val shown = years.takeLast(6)
    Row(Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
        shown.forEach { y ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
private fun Legend(color: Color, label: String, detail: String) {
    val theme = LocalGlassTheme.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
