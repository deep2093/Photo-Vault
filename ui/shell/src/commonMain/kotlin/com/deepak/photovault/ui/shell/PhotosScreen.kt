package com.deepak.photovault.ui.shell

import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudQueue
import com.deepak.photovault.core.backup.isDriveOnly
import androidx.compose.material.icons.rounded.Download
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.rounded.Groups
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
import com.deepak.photovault.core.media.LibraryLevels
import com.deepak.photovault.core.media.LibraryView
import com.deepak.photovault.core.media.MonthSummary
import com.deepak.photovault.designsystem.motionSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassChip
import com.deepak.photovault.designsystem.GlassIconButton
import com.deepak.photovault.designsystem.WarningNote
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * The photo timeline. No top bar (feedback 23 Oct): small glass pills float at the top, as in Samsung Gallery.
 *
 * Years / Months / All (24 Oct, after the Apple Photos video): Years shows a card per year, Months a card per
 * month, All every photo by day. Tapping a year opens its months; tapping a month opens All at that month.
 * Pinching moves between them too (spread to go in, pinch to come out), and the change animates as a zoom.
 */
@Composable
fun PhotosScreen(state: ShellState, gallery: GalleryState, actions: MediaActions, drive: DriveState) {
    val zone = remember { TimeZone.currentSystemDefault() }
    val today = Timeline.localDate(Clock.System.now().toEpochMilliseconds(), zone)
    val source = PhotoSource.entries.firstOrNull { it.name == state.appearance.photoSource } ?: PhotoSource.ALL
    // Only on the phone / only on the drive (cloud) / both in one timeline (25 Oct).
    val shown = remember(gallery.items, source) {
        when (source) {
            PhotoSource.ALL -> gallery.items
            PhotoSource.PHONE -> gallery.items.filterNot { it.isDriveOnly }
            PhotoSource.DRIVE -> gallery.items.filter { it.isDriveOnly }
        }
    }
    val sections = remember(shown, today) { Timeline.group(shown, TimelineGrouping.DAY, zone, today) }
    val months = remember(shown) { LibraryLevels.months(shown, zone) }
    val years = remember(shown, today) { LibraryLevels.years(shown, zone, today) }
    // Kept here, so going Years -> Months -> back to Years returns to the same place.
    val yearsGrid = rememberLazyGridState()
    val monthsGrid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // Back climbs from a month to its months, then the years, then the day view; only then does the app close.
    val canStep = gallery.view != LibraryView.ALL || gallery.viewHistory.isNotEmpty()
    androidx.compose.runtime.SideEffect {
        state.canStepBack = canStep
        state.stepBack = {
            gallery.view = if (gallery.viewHistory.isNotEmpty()) gallery.viewHistory.removeAt(gallery.viewHistory.lastIndex) else LibraryView.ALL
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { state.canStepBack = false; state.stepBack = null } }
    fun openYear(year: Int) {
        gallery.viewHistory.add(gallery.view)
        gallery.view = LibraryView.MONTHS
        scope.launch { monthsGrid.scrollToItem(LibraryLevels.firstMonthOf(year, months)) }
    }
    fun openMonth(month: MonthSummary) {
        gallery.jumpTo = month.firstDayKey
        gallery.viewHistory.add(gallery.view)
        gallery.view = LibraryView.ALL
    }
    SelectableScreen(
        title = null,
        subtitle = null,
        state = state,
        gallery = gallery,
        actions = actions,
        floatingStartActions = { ViewButtons(gallery) },
        floatingActions = {
            if (!state.appearance.filterChips) SourceButton(source) { next -> state.updateAppearance { it.copy(photoSource = next.name) } }
            SmallPillButton(Icons.Rounded.Search, "Search") { state.open(Overlay.Search) }
        },
    ) { padding ->
        when {
            gallery.access == MediaAccess.NONE -> EmptyState(
                title = "See your photos here",
                body = "Pixallery needs access to your photos and videos to show them and back them up. " +
                    "They stay on your phone and your own drive.",
                action = { GlassButton("Allow access", onClick = actions::requestAccess, primary = true) },
            )
            gallery.loaded && gallery.items.isEmpty() -> EmptyState(
                title = "No photos yet",
                body = "Photos and videos you take will appear here.",
            )
            else -> {
                val spec = motionSpec<Float>(LEVEL_MS)
                androidx.compose.animation.AnimatedContent(
                    targetState = gallery.view,
                    transitionSpec = {
                        // Going in (Years -> Months -> All) zooms in; coming out zooms out.
                        val deeper = targetState.ordinal > initialState.ordinal
                        (fadeIn(spec) + scaleIn(spec, initialScale = if (deeper) 0.92f else 1.08f)) togetherWith
                            (fadeOut(spec) + scaleOut(spec, targetScale = if (deeper) 1.08f else 0.92f))
                    },
                    label = "library level",
                ) { view ->
                    when (view) {
                        LibraryView.YEARS -> YearsGrid(years, yearsGrid, padding, onOpen = { openYear(it.year) }) { zoomIn ->
                            if (zoomIn) gallery.view = LibraryView.MONTHS
                        }
                        LibraryView.MONTHS -> MonthsGrid(months, monthsGrid, padding, onOpen = ::openMonth) { zoomIn ->
                            if (zoomIn) {
                                months.getOrNull(monthsGrid.firstVisibleItemIndex)?.let(::openMonth) ?: run { gallery.view = LibraryView.ALL }
                            } else {
                                gallery.view = LibraryView.YEARS
                            }
                        }
                        LibraryView.ALL -> PullToRefresh(padding, onRefresh = {
                            // Pull down at the top (24 Oct): reload the phone's photos and check the drive.
                            gallery.refresh()
                            drive.refresh()
                            // And fetch what the drive holds (Phase 5A): new backups and the drive's Recently deleted.
                            if (drive.joined != null) {
                                drive.loadBackupRecords(force = true)
                                drive.loadDriveTrash()
                            }
                        }) { MediaGrid(
                            sections = sections,
                            state = state,
                            gallery = gallery,
                            screenPadding = padding,
                            onOpen = { item -> state.open(Overlay.Viewer(sections.flatMap { s -> s.items.map { it.id } }, item.id)) },
                            jumpTo = gallery.jumpTo,
                            onJumped = { gallery.jumpTo = null },
                            onZoomOutPastMax = { gallery.view = LibraryView.MONTHS },
                            header = {
                                item(key = "controls", span = { GridItemSpan(maxLineSpan) }, contentType = "controls") {
                                    androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = SCREEN_MARGIN)) {
                                        if (state.appearance.filterChips) {
                                            androidx.compose.foundation.layout.Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                PhotoSource.entries.forEach { s ->
                                                    com.deepak.photovault.designsystem.GlassChip(
                                                        when (s) { PhotoSource.ALL -> "Both"; PhotoSource.PHONE -> "Phone"; PhotoSource.DRIVE -> "Cloud" },
                                                        selected = s == source,
                                                        onClick = { state.updateAppearance { it.copy(photoSource = s.name) } },
                                                    )
                                                }
                                            }
                                        }
                                        if (gallery.access == MediaAccess.PARTIAL) {
                                            WarningNote("You've shared only some photos with Pixallery. Allow all to see and back up everything.")
                                            GlassButton("Allow all photos", onClick = actions::requestAccess, modifier = Modifier.padding(bottom = 8.dp))
                                        }
                                    }
                                }
                            },
                        ) }
                    }
                }
            }
        }
    }
}

/** The Photos filter as one small button: its icon shows the current choice; tapping lists the three. */
@Composable
private fun SourceButton(current: PhotoSource, onPick: (PhotoSource) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SmallPillButton(
            when (current) { PhotoSource.ALL -> Icons.Rounded.CloudQueue; PhotoSource.PHONE -> Icons.Rounded.PhoneAndroid; PhotoSource.DRIVE -> Icons.Rounded.CloudDone },
            "Show: ${current.label}",
        ) { open = true }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PhotoSource.entries.forEach { s ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(if (s == current) "✓ ${s.label}" else s.label) },
                    onClick = { open = false; onPick(s) },
                )
            }
        }
    }
}

/** Pull down at the top to refresh; the spinner appears just under the top pills. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PullToRefresh(padding: androidx.compose.foundation.layout.PaddingValues, onRefresh: suspend () -> Unit, content: @Composable () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var refreshing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val pull = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    val theme = LocalGlassTheme.current
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            scope.launch {
                try {
                    onRefresh()
                } finally {
                    refreshing = false
                }
            }
        },
        state = pull,
        indicator = {
            androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
                state = pull,
                isRefreshing = refreshing,
                containerColor = theme.glassFallback,
                color = theme.accent,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding()),
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) { content() }
}

private const val LEVEL_MS = 300

/** Years / Months / All as three small buttons in their own pill at the top left. The chosen one is filled. */
@Composable
private fun ViewButtons(gallery: GalleryState) {
    val theme = LocalGlassTheme.current
    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        LibraryView.entries.forEach { option ->
            val selected = gallery.view == option
            val background by androidx.compose.animation.animateColorAsState(
                if (selected) theme.accent else androidx.compose.ui.graphics.Color.Transparent,
                motionSpec(),
                label = "view",
            )
            Box(
                Modifier
                    .heightIn(min = 30.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(background)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { gallery.viewHistory.clear(); gallery.view = option })
                    .padding(horizontal = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                com.deepak.photovault.designsystem.DepthText(
                    option.label,
                    color = if (selected) theme.onAccent else theme.onGlass,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** A small round button for the compact pills (24 Oct). */
@Composable
internal fun SmallPillButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val theme = LocalGlassTheme.current
    Box(
        Modifier
            .size(32.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        com.deepak.photovault.designsystem.DepthIcon(icon, label, theme.onGlass, Modifier.size(18.dp))
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
    /** Where the grid draws each photo, for the delete animation; the screen's [MediaGrid] fills it in. */
    tileSpots: TileSpots = remember { TileSpots() },
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    val selectedItems = state.selected.mapNotNull(gallery::find)
    val flight = LocalDeleteFlight.current
    val prompt = LocalDeletePrompt.current
    val bin = remember { ScreenSpot() }
    androidx.compose.runtime.CompositionLocalProvider(LocalTileSpots provides tileSpots) {
    // One GlassScreen for both modes, so the grid keeps its scroll position when selection starts.
    // Selecting (24 Oct): the top shows only the count and Cancel; share, favourite and delete rise in at the
    // bottom while the navigation bar rolls down.
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
                GlassIconButton(Icons.Rounded.Close, "Cancel selection", onClick = { state.selected = emptySet() })
            }
        },
        bottomActions = if (!state.selecting) null else ({
            if (selectionActions != null) {
                selectionActions(selectedItems)
            } else {
                GlassIconButton(Icons.Rounded.Share, "Share", onClick = { actions.share(selectedItems) })
                if (selectedItems.any { it.isDriveOnly }) GlassIconButton(Icons.Rounded.Download, "Save to this phone", onClick = {
                    actions.saveToPhone(selectedItems)
                    state.selected = emptySet()
                })
                if (LocalFamilyAlbum.current) GlassIconButton(Icons.Rounded.Groups, "Share to Family Album", onClick = {
                    actions.shareToFamilyAlbum(selectedItems)
                    state.selected = emptySet()
                })
                GlassIconButton(Icons.Rounded.FavoriteBorder, "Favourite", onClick = {
                    gallery.toggleFavourite(selectedItems)
                    state.selected = emptySet()
                })
                GlassIconButton(Icons.Rounded.Delete, "Move to Recently deleted", modifier = Modifier.trackSpot(bin), onClick = {
                    // Where the photos are now: by the time Android has asked, the grid may have changed.
                    val flying = tileSpots.flying(selectedItems)
                    prompt.trash(actions, selectedItems) { agreed ->
                        if (agreed) {
                            flight.launch(flying, bin.bounds)
                            gallery.dropNow(selectedItems.map { it.id }.toSet())
                            state.selected = emptySet()
                        }
                    }
                })
            }
        }),
        content = content,
    )
    }
}
