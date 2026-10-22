package com.deepak.photovault.ui.shell

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
    SelectableScreen(title, LibraryCounts.summary(items), state, gallery, actions, onBack = { state.back() }) { padding ->
        if (items.isEmpty()) {
            EmptyState(
                title = "Nothing here yet",
                body = if (kind == AlbumKind.Favourites) "Tap the heart on a photo to add it here." else "This album is empty.",
            )
        } else {
            MediaGrid(sections, state, gallery, padding, onOpen = { item ->
                state.open(Overlay.Viewer(sections.flatMap { s -> s.items.map { it.id } }, item.id))
            })
        }
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
    SelectableScreen(
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
            GlassIconButton(Icons.Rounded.DeleteForever, "Delete for good", onClick = {
                actions.deleteForever(selected)
                state.selected = emptySet()
            })
        },
    ) { padding ->
        if (trashed.isEmpty()) {
            EmptyState("Nothing deleted", "Photos and videos you delete stay here for 30 days, so you can change your mind.")
        } else {
            // Deleted photos can't be opened in the viewer: tapping selects them, so Restore is one tap away.
            MediaGrid(
                section, state, gallery, padding,
                onOpen = { state.toggleSelected(it.id) },
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

