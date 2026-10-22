package com.deepak.photovault.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.deepak.photovault.core.media.LibraryCounts
import com.deepak.photovault.core.media.MediaAccess
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.Timeline
import com.deepak.photovault.core.media.TimelineGrouping
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassChip
import com.deepak.photovault.designsystem.GlassIconButton
import com.deepak.photovault.designsystem.WarningNote
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

@Composable
fun PhotosScreen(state: ShellState, gallery: GalleryState, actions: MediaActions) {
    val zone = remember { TimeZone.currentSystemDefault() }
    val today = Timeline.localDate(Clock.System.now().toEpochMilliseconds(), zone)
    val sections = remember(gallery.items, gallery.grouping, today) { Timeline.group(gallery.items, gallery.grouping, zone, today) }
    SelectableScreen(
        title = "Photos",
        subtitle = if (gallery.access == MediaAccess.NONE) null else LibraryCounts.summary(gallery.items),
        state = state,
        gallery = gallery,
        actions = actions,
        normalActions = { GlassIconButton(Icons.Rounded.Search, "Search", onClick = { state.open(Overlay.Search) }) },
    ) { padding ->
        when {
            gallery.access == MediaAccess.NONE -> EmptyState(
                title = "See your photos here",
                body = "PhotoVault needs access to your photos and videos to show them and, later, back them up. " +
                    "They stay on your phone and your own drive.",
                action = { GlassButton("Allow access", onClick = actions::requestAccess, primary = true) },
            )
            gallery.loaded && gallery.items.isEmpty() -> EmptyState(
                title = "No photos yet",
                body = "Photos and videos you take will appear here.",
            )
            else -> MediaGrid(
                sections = sections,
                state = state,
                gallery = gallery,
                contentPadding = padding,
                onOpen = { item -> state.open(Overlay.Viewer(sections.flatMap { s -> s.items.map { it.id } }, item.id)) },
                header = {
                    item(key = "controls", span = { GridItemSpan(maxLineSpan) }, contentType = "controls") {
                        androidx.compose.foundation.layout.Column {
                            if (gallery.access == MediaAccess.PARTIAL) {
                                WarningNote("You've shared only some photos with PhotoVault. Allow all to see and back up everything.")
                                GlassButton("Allow all photos", onClick = actions::requestAccess, modifier = Modifier.padding(bottom = 8.dp))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                                listOf(TimelineGrouping.DAY to "Day", TimelineGrouping.MONTH to "Month", TimelineGrouping.YEAR to "Year")
                                    .forEach { (option, label) ->
                                        GlassChip(label, selected = gallery.grouping == option, onClick = { gallery.grouping = option })
                                    }
                            }
                        }
                    }
                },
            )
        }
    }
}

/**
 * A screen whose grid supports selection: while photos are selected, the top bar shows how many with
 * Share, Favourite and Recently deleted actions, and back clears the selection.
 */
@Composable
fun SelectableScreen(
    title: String,
    subtitle: String?,
    state: ShellState,
    gallery: GalleryState,
    actions: MediaActions,
    onBack: (() -> Unit)? = null,
    normalActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    selectionActions: (@Composable androidx.compose.foundation.layout.RowScope.(List<MediaItem>) -> Unit)? = null,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    val selectedItems = state.selected.mapNotNull(gallery::find)
    // One GlassScreen for both modes, so the grid keeps its scroll position when selection starts.
    GlassScreen(
        title = if (state.selecting) "${state.selected.size} selected" else title,
        subtitle = if (state.selecting) null else subtitle,
        onBack = if (state.selecting) null else onBack,
        pinned = state.selecting,
        actions = {
            if (!state.selecting) {
                normalActions()
            } else {
                if (selectionActions != null) {
                    selectionActions(selectedItems)
                } else {
                    GlassIconButton(Icons.Rounded.Share, "Share", onClick = { actions.share(selectedItems) })
                    GlassIconButton(Icons.Rounded.FavoriteBorder, "Favourite", onClick = {
                        gallery.toggleFavourite(selectedItems)
                        state.selected = emptySet()
                    })
                    GlassIconButton(Icons.Rounded.Delete, "Move to Recently deleted", onClick = {
                        actions.moveToRecentlyDeleted(selectedItems)
                        state.selected = emptySet()
                    })
                }
                GlassIconButton(Icons.Rounded.Close, "Cancel selection", onClick = { state.selected = emptySet() })
            }
        },
        content = content,
    )
}
