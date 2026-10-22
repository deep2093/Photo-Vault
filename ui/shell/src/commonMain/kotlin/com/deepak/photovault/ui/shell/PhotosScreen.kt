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
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
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
 * Gallery, with search and a menu; Day / Month / Year sit in their own pill at the top left (24 Oct). The
 * backup cloud was removed (Backup is in the navigation bar). Pinch still changes the size.
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
        floatingStartActions = { GroupingButtons(gallery) },
        floatingActions = {
            GlassIconButton(Icons.Rounded.Search, "Search", onClick = { state.open(Overlay.Search) })
            PhotosMenu(gallery)
        },
    ) { padding ->
        when {
            gallery.access == MediaAccess.NONE -> EmptyState(
                title = "See your photos here",
                body = "Pixallery needs access to your photos and videos to show them and, later, back them up. " +
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
                                WarningNote("You've shared only some photos with Pixallery. Allow all to see and back up everything.")
                                GlassButton("Allow all photos", onClick = actions::requestAccess, modifier = Modifier.padding(bottom = 8.dp))
                            }
                        }
                    }
                },
            )
        }
    }
}

/**
 * Day / Month / Year as three small buttons in their own pill at the top left (24 Oct), opposite search and the
 * menu. The chosen one is filled.
 */
@Composable
private fun GroupingButtons(gallery: GalleryState) {
    val theme = LocalGlassTheme.current
    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(TimelineGrouping.DAY to "Day", TimelineGrouping.MONTH to "Month", TimelineGrouping.YEAR to "Year").forEach { (option, label) ->
            val selected = gallery.grouping == option
            val background by androidx.compose.animation.animateColorAsState(
                if (selected) theme.accent else androidx.compose.ui.graphics.Color.Transparent,
                com.deepak.photovault.designsystem.motionSpec(),
                label = "grouping",
            )
            Box(
                Modifier
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(background)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { gallery.grouping = option })
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (selected) theme.onAccent else theme.onGlass,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** The ⋮ menu: how many photos and videos. */
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
    floatingStartActions: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
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
        floatingStartActions = floatingStartActions,
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
