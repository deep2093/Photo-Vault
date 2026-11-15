package com.deepak.photovault.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deepak.photovault.core.backup.isDriveOnly
import com.deepak.photovault.core.media.DetailsFormat
import com.deepak.photovault.core.media.Duplicates
import com.deepak.photovault.core.media.LibraryCounts
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.Thumb
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassCard
import com.deepak.photovault.designsystem.GlassSegmented
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.SectionHeader
import com.deepak.photovault.designsystem.rememberHaptics
import kotlinx.datetime.TimeZone

/** The phone's part of the duplicate finder (Phase 11 part 3, 15 Nov). */
interface DuplicatePlatform {
    val supported: Boolean get() = false

    /** Fingerprints of [items] (worked out once and kept), reporting how far it has got. */
    suspend fun prints(items: List<MediaItem>, onProgress: (Int, Int) -> Unit): Map<Long, Duplicates.Print> = emptyMap()
}

@Stable
class DuplicateState(val platform: DuplicatePlatform = object : DuplicatePlatform {}) {
    /** null until looked; then the groups found. */
    var groups by mutableStateOf<List<Duplicates.Group>?>(null)
        private set

    /** Done and total while looking. */
    var progress by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    /** What will be moved to Recently deleted: every group's suggestions to start with. */
    var remove by mutableStateOf<Set<Long>>(emptySet())

    private var lookedAt: List<MediaItem>? = null

    suspend fun look(items: List<MediaItem>) {
        val phone = items.filterNot { it.isDriveOnly }
        if (lookedAt == phone && groups != null) return
        progress = 0 to 0
        try {
            val prints = platform.prints(phone) { done, total -> progress = done to total }
            val found = Duplicates.find(phone, prints)
            // Ticks someone already changed stay; new groups start with the suggestions ticked.
            val before = groups?.flatMap { it.ids }?.toHashSet().orEmpty()
            remove = remove.filter { id -> found.any { id in it.others } } .toSet() +
                found.filter { g -> g.ids.none { it in before } }.flatMap { it.others }
            groups = found
            lookedAt = phone
        } finally {
            progress = null
        }
    }

    /** Ticks or unticks [id]; at least one of its group always stays. */
    fun toggle(group: Duplicates.Group, id: Long): Boolean {
        if (id in remove) {
            remove = remove - id
            return true
        }
        val left = group.ids.count { it != id && it !in remove }
        if (left == 0) return false
        remove = remove + id
        return true
    }

    /** After moving to Recently deleted: those photos and the groups that are done go. */
    fun removed(ids: Set<Long>) {
        groups = groups?.mapNotNull { g ->
            val left = g.ids.filter { it !in ids }
            if (left.size < 2) null else g.copy(ids = left, keep = if (g.keep in ids) left.first() else g.keep)
        }
        remove = remove - ids
        lookedAt = null
    }
}

/**
 * Duplicates (Phase 11 part 3, 15 Nov): exact copies and very similar photos on this phone, in groups. The one to
 * keep is marked; the rest are ticked to go. Untick any, tick the keeper of a group only if another stays. Moving
 * sends them to Recently deleted (30 days), so nothing is lost straight away.
 */
@Composable
fun DuplicatesScreen(state: ShellState, gallery: GalleryState, actions: MediaActions) {
    val theme = LocalGlassTheme.current
    val dup = state.duplicates
    val toast = LocalToast.current
    val haptics = rememberHaptics()
    var exact by remember { mutableStateOf(true) }
    val byId = remember(gallery.items) { gallery.items.associateBy { it.id } }
    LaunchedEffect(gallery.items) { dup.look(gallery.items) }
    val groups = dup.groups.orEmpty().filter { g -> g.ids.count { it in byId } >= 2 }
    val exactGroups = groups.filter { it.kind == Duplicates.Kind.EXACT }
    val similarGroups = groups.filter { it.kind == Duplicates.Kind.SIMILAR }
    // Nothing exact but some similar: open on Similar.
    LaunchedEffect(exactGroups.isEmpty(), similarGroups.isEmpty()) { if (exactGroups.isEmpty() && similarGroups.isNotEmpty()) exact = false }
    val shown = if (exact) exactGroups else similarGroups
    val chosen = dup.remove.mapNotNull { byId[it] }
    val bytes = chosen.sumOf { it.sizeBytes }
    val zone = remember { TimeZone.currentSystemDefault() }

    GlassListScreen(title = "Duplicates", subtitle = null, onBack = { state.back() }) {
        val p = dup.progress
        item(key = "summary") {
            GlassCard(Modifier.cardWidth()) {
                when {
                    p != null -> {
                        SectionHeader("Looking for duplicates")
                        Text(
                            if (p.second == 0) "Getting ready…" else "Checking ${LibraryCounts.grouped(p.first)} of ${LibraryCounts.grouped(p.second)} on this phone…",
                            color = theme.onGlassSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (p.second > 0) com.deepak.photovault.designsystem.GlassProgressBar(p.first / p.second.toFloat(), label = "Checking", modifier = Modifier.padding(top = 10.dp))
                        Text(
                            "Done once; after that it's quick. Nothing leaves your phone.",
                            color = theme.onGlassSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    groups.isEmpty() -> {
                        SectionHeader("No duplicates")
                        Text("No copies or near-identical photos found on this phone.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                    else -> {
                        Text(
                            if (chosen.isEmpty()) "Nothing picked" else "${LibraryCounts.grouped(chosen.size)} picked · frees ${DetailsFormat.size(bytes)}",
                            color = theme.onGlass,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "The best of each group is kept. Tap a photo to pick or unpick it.",
                            color = theme.onGlassSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                        )
                        GlassSegmented(
                            listOf("Exact copies (${exactGroups.size})", "Similar (${similarGroups.size})"),
                            if (exact) 0 else 1,
                            { exact = it == 0 },
                            Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassButton("Pick suggested", onClick = {
                                dup.remove = dup.remove + shown.flatMap { it.others }
                            })
                            GlassButton("Unpick all", onClick = {
                                val here = shown.flatMap { it.ids }.toSet()
                                dup.remove = dup.remove - here
                            })
                        }
                    }
                }
            }
        }
        if (p == null) {
            shown.forEach { g ->
                item(key = "g-${g.kind}-${g.keep}") { GroupCard(g, byId, dup, zone, onRefused = { toast("Keep at least one photo of each group.") }) }
            }
            if (shown.isEmpty() && groups.isNotEmpty()) {
                item(key = "none") {
                    Text(
                        if (exact) "No exact copies." else "No similar photos.",
                        color = theme.onWallpaper,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.cardWidth().padding(8.dp),
                    )
                }
            }
            if (chosen.isNotEmpty()) item(key = "go") {
                GlassButton(
                    "Move ${LibraryCounts.grouped(chosen.size)} to Recently deleted",
                    icon = Icons.Rounded.Delete,
                    primary = true,
                    danger = true,
                    onClick = {
                        haptics.tap()
                        val ids = chosen.map { it.id }.toSet()
                        actions.moveToRecentlyDeleted(chosen) { ok ->
                            if (ok) {
                                dup.removed(ids)
                                toast("Moved to Recently deleted: ${DetailsFormat.size(bytes)} freed.")
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun GroupCard(
    g: Duplicates.Group,
    byId: Map<Long, MediaItem>,
    dup: DuplicateState,
    zone: TimeZone,
    onRefused: () -> Unit,
) {
    val theme = LocalGlassTheme.current
    val haptics = rememberHaptics()
    val items = g.ids.mapNotNull { byId[it] }
    GlassCard(Modifier.cardWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (g.kind == Duplicates.Kind.EXACT) "${items.size} copies" else "${items.size} similar photos",
                    color = theme.onGlass,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    dateText(items.first().takenAtMillis, zone) + " · " + items.map { it.albumName }.distinct().joinToString(", "),
                    color = theme.onGlassSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
            if (g.kind == Duplicates.Kind.EXACT) {
                androidx.compose.material3.Icon(Icons.Rounded.ContentCopy, null, tint = theme.onGlassSecondary, modifier = Modifier.size(18.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEach { item ->
                val picked = item.id in dup.remove
                val shape = RoundedCornerShape(14.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(104.dp)
                            .clip(shape)
                            .border(2.dp, if (item.id == g.keep && !picked) theme.accent else Color.Transparent, shape)
                            .clickable(role = Role.Checkbox, onClickLabel = if (picked) "Keep" else "Remove") {
                                if (dup.toggle(g, item.id)) haptics.tick() else onRefused()
                            },
                    ) {
                        coil3.compose.AsyncImage(
                            model = Thumb(item.uri, item.isVideo, 256),
                            contentDescription = item.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(104.dp),
                        )
                        // Picked to go: a little dimmer.
                        if (picked) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.28f)))
                        SelectionMark(picked, 24.dp, Modifier.align(Alignment.TopEnd).padding(6.dp))
                        if (item.id == g.keep) {
                            Text(
                                "Best",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(6.dp)
                                    .clip(RoundedCornerShape(percent = 50))
                                    .background(Color.Black.copy(alpha = 0.6f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    Text(
                        "${item.width} × ${item.height} · ${DetailsFormat.size(item.sizeBytes)}",
                        color = theme.onGlassSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
