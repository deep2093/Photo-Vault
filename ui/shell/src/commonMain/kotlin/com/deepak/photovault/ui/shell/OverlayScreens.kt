package com.deepak.photovault.ui.shell

import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.deepak.photovault.core.media.LibraryCounts
import com.deepak.photovault.core.media.MediaSearch
import com.deepak.photovault.core.media.Timeline
import com.deepak.photovault.core.media.TimelineGrouping
import com.deepak.photovault.core.media.TimelineSection
import com.deepak.photovault.designsystem.GlassIconButton
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.glassPanel
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

private fun today(zone: TimeZone) = Timeline.localDate(Clock.System.now().toEpochMilliseconds(), zone)

/** An album page: a phone folder, Favourites or Videos, with the same grid and selection as Photos. */
@Composable
fun AlbumScreen(kind: AlbumKind, state: ShellState, gallery: GalleryState, actions: MediaActions) {
    val zone = remember { TimeZone.currentSystemDefault() }
    val (title, items) = remember(kind, gallery.items, gallery.favourites) {
        when (kind) {
            is AlbumKind.Phone -> kind.name to gallery.items.filter { it.albumId == kind.albumId }
            AlbumKind.Favourites -> "Favourites" to gallery.items.filter { it.id in gallery.favourites }
            AlbumKind.Videos -> "Videos" to gallery.items.filter { it.isVideo }
        }
    }
    val sections = remember(items) { Timeline.group(items, TimelineGrouping.MONTH, zone, today(zone)) }
    val spots = remember { TileSpots() }
    // No top bar inside an album (24 Oct): the name is a big heading at the top of the photos, a small round back
    // button floats at the top left, and the count sits in small text under the last photo. Rename, cover and
    // delete live in Albums (press and hold an album).
    SelectableScreen(
        null, null, state, gallery, actions, onBack = { state.back() },
        tileSpots = spots,
        floatingStartActions = { SmallPillButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { state.back() } },
    ) { padding ->
        if (items.isEmpty()) {
            EmptyState(
                title = "Nothing here yet",
                body = if (kind == AlbumKind.Favourites) "Tap the heart on a photo to add it here." else "This album is empty.",
            )
        } else {
            MediaGrid(
                sections, state, gallery, padding,
                onOpen = { item -> state.open(Overlay.Viewer(sections.flatMap { s -> s.items.map { it.id } }, item.id)) },
                header = {
                    item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            title,
                            color = LocalGlassTheme.current.onGlass,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            maxLines = 2,
                            modifier = Modifier.padding(horizontal = SCREEN_MARGIN + 4.dp).padding(top = 4.dp, bottom = 4.dp),
                        )
                    }
                },
                footer = {
                    item(key = "count", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            LibraryCounts.summary(items),
                            color = LocalGlassTheme.current.onGlassSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        )
                    }
                },
            )
        }
    }
}

/** Album names that belong to the camera and system apps; renaming them would break those apps. */
object AlbumRules {
    private val system = setOf("camera", "screenshots", "screen recordings", "screenrecordings", "download", "downloads", "dcim")

    fun isSystem(name: String) = name.trim().lowercase() in system

    /** A folder name Android accepts: no slashes or odd characters, not empty, not too long. */
    fun cleanName(name: String): String? {
        val n = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "").take(60)
        return n.takeIf { it.isNotEmpty() && it != "." && it != ".." && !isSystem(it) }
    }
}

@Composable
internal fun RenameAlbumDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    val clean = AlbumRules.cleanName(name)
    com.deepak.photovault.designsystem.GlassDialog(
        title = "Rename album",
        onDismiss = onDismiss,
        buttons = {
            com.deepak.photovault.designsystem.GlassButton("Cancel", onClick = onDismiss)
            com.deepak.photovault.designsystem.GlassButton("Rename", primary = true, onClick = { if (clean != null && clean != current) onSave(clean) })
        },
    ) {
        com.deepak.photovault.designsystem.GlassTextField(name, { name = it }, "Album name")
        if (clean == null) com.deepak.photovault.designsystem.WarningNote("Choose another name.")
    }
}

/**
 * Recently deleted: photos moved here stay for 30 days, then Android removes them for good. Restore or
 * delete now; both go through Android's own confirmation.
 */
@Composable
fun RecentlyDeletedScreen(state: ShellState, gallery: GalleryState, actions: MediaActions) {
    val now = Clock.System.now().toEpochMilliseconds()
    val trashed = gallery.trashed
    val byId = remember(trashed) { trashed.associateBy { it.item.id } }
    val section = remember(trashed) { listOf(TimelineSection("bin", "Deleted for good after 30 days", trashed.map { it.item })) }
    val selected = state.selected.mapNotNull { byId[it]?.item }
    val spots = remember { TileSpots() }
    val flight = LocalDeleteFlight.current
    val bin = remember { ScreenSpot() }
    SelectableScreen(
        tileSpots = spots,
        title = "Recently deleted",
        subtitle = if (trashed.isEmpty()) "Empty" else "${trashed.size} items",
        state = state,
        gallery = gallery,
        actions = actions,
        onBack = { state.back() },
        selectionActions = {
            GlassIconButton(Icons.Rounded.Restore, "Restore", onClick = {
                actions.restore(selected)
                state.selected = emptySet()
            })
            GlassIconButton(Icons.Rounded.DeleteForever, "Delete for good", modifier = Modifier.trackSpot(bin), onClick = {
                val flying = spots.flying(selected)
                actions.deleteForever(selected) { agreed ->
                    if (agreed) {
                        flight.launch(flying, bin.bounds)
                        gallery.dropNow(selected.map { it.id }.toSet())
                        state.selected = emptySet()
                    }
                }
            })
        },
    ) { padding ->
        if (trashed.isEmpty()) {
            EmptyState("Nothing deleted", "Photos and videos you delete stay here for 30 days, so you can change your mind.")
        } else {
            // Opens in the viewer with Restore and Delete for good (23 Oct); long-press still selects.
            MediaGrid(
                section, state, gallery, padding,
                onOpen = { item -> state.open(Overlay.Viewer(trashed.map { it.item.id }, item.id, trash = true)) },
                tileLabel = { item ->
                    val days = byId[item.id]?.let { Timeline.daysLeft(it.expiresAtMillis, now) } ?: 0
                    if (days <= 1) "1 day" else "$days days"
                },
            )
        }
    }
}

/** Search the phone's photos by name, album, type, month, year or "favourite". Runs on the phone only. */
@Composable
fun SearchScreen(state: ShellState, gallery: GalleryState, actions: MediaActions) {
    val theme = LocalGlassTheme.current
    val zone = remember { TimeZone.currentSystemDefault() }
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query, gallery.items, gallery.favourites) { MediaSearch.filter(gallery.items, query, gallery.favourites, zone) }
    val sections = remember(results) { Timeline.group(results, TimelineGrouping.MONTH, zone, today(zone)) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    SelectableScreen(
        title = "Search",
        subtitle = if (query.isBlank()) "Try \"videos\", \"october\", \"2025\" or an album" else "${results.size} found",
        state = state,
        gallery = gallery,
        actions = actions,
        onBack = { state.back() },
    ) { padding ->
        MediaGrid(
            sections, state, gallery, padding,
            onOpen = { item -> state.open(Overlay.Viewer(results.map { it.id }, item.id)) },
            header = {
                item(key = "query", span = { GridItemSpan(maxLineSpan) }) {
                    androidx.compose.foundation.layout.Row(
                        Modifier
                            .padding(horizontal = SCREEN_MARGIN)
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .glassPanel(RoundedCornerShape(26.dp))
                            .padding(start = 20.dp, end = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = theme.onGlass),
                            cursorBrush = SolidColor(theme.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = "Search photos" },
                            decorationBox = { inner ->
                                androidx.compose.foundation.layout.Box {
                                    if (query.isEmpty()) {
                                        Text("Search your photos", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyLarge)
                                    }
                                    inner()
                                }
                            },
                        )
                        if (query.isNotEmpty()) GlassIconButton(Icons.Rounded.Close, "Clear search", onClick = { query = "" })
                    }
                }
            },
        )
    }
}

