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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import com.deepak.photovault.designsystem.LocalGlassTheme
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

/**
 * The photo timeline. No top bar (feedback 23 Oct): a small glass pill floats at the top right, as in Samsung
 * Gallery, with backup status, search and a menu holding Day / Month / Year. Pinch still changes the size.
 */
@Composable
fun PhotosScreen(state: ShellState, gallery: GalleryState, actions: MediaActions, drive: DriveState) {
    val zone = remember { TimeZone.currentSystemDefault() }
    val today = Timeline.localDate(Clock.System.now().toEpochMilliseconds(), zone)
    val sections = remember(gallery.items, gallery.grouping, today) { Timeline.group(gallery.items, gallery.grouping, zone, today) }
    SelectableScreen(
        title = null,
        subtitle = null,
        state = state,
        gallery = gallery,
        actions = actions,
        floatingActions = {
            val (cloud, cloudLabel) = when {
                !drive.isSetUp -> Icons.Rounded.CloudOff to "Backup: no drive connected"
                drive.problem != null -> Icons.Rounded.CloudOff to "Backup: can't reach the drive"
                drive.joined != null -> Icons.Rounded.CloudDone to "Backup: connected to your drive"
                else -> Icons.Rounded.Cloud to "Backup: finish joining your family"
            }
            GlassIconButton(cloud, cloudLabel, onClick = { state.tapSection(Destination.BACKUP) })
            GlassIconButton(Icons.Rounded.Search, "Search", onClick = { state.open(Overlay.Search) })
            PhotosMenu(gallery)
        },
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
                        }
                    }
                },
            )
        }
    }
}

/** The ⋮ menu: how many photos and videos, and Day / Month / Year. */
@Composable
private fun PhotosMenu(gallery: GalleryState) {
    val theme = LocalGlassTheme.current
    var open by remember { mutableStateOf(false) }
    Box {
        GlassIconButton(Icons.Rounded.MoreVert, "More options", onClick = { open = true })
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = theme.glassFallback.compositeOver(theme.wallpaper.first()),
        ) {
            if (gallery.access != MediaAccess.NONE) {
                Text(
                    LibraryCounts.summary(gallery.items),
                    color = theme.onGlassSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            listOf(TimelineGrouping.DAY to "Day", TimelineGrouping.MONTH to "Month", TimelineGrouping.YEAR to "Year").forEach { (option, label) ->
                val selected = gallery.grouping == option
                DropdownMenuItem(
                    text = { Text(label, color = theme.onGlass, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
                    trailingIcon = { if (selected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = theme.accent) },
                    onClick = {
                        gallery.grouping = option
                        open = false
                    },
                )
            }
        }
    }
}

/**
 * A screen whose grid supports selection: while photos are selected, the top bar shows how many with
 * Share, Favourite and Recently deleted actions, and back clears the selection.
 */
@Composable
fun SelectableScreen(
    title: String?,
    subtitle: String?,
    state: ShellState,
    gallery: GalleryState,
    actions: MediaActions,
    onBack: (() -> Unit)? = null,
    normalActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    floatingActions: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
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
        floatingActions = floatingActions,
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
