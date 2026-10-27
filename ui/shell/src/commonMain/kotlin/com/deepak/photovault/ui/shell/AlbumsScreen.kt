package com.deepak.photovault.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.selected
import com.deepak.photovault.designsystem.GlassIconButton
import com.deepak.photovault.designsystem.motionSpec
import coil3.compose.AsyncImage
import com.deepak.photovault.core.media.LibraryCounts
import com.deepak.photovault.core.media.MediaAccess
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.Thumb
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassCardShape
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.glassPanel

/** One card in Albums: a phone album, or the Favourites / Videos collections. */
internal const val FAMILY_CARD = "@family"

private class AlbumCardData(val id: String, val name: String, val items: () -> List<MediaItem>, val count: Int, val newest: MediaItem?, val icon: ImageVector?, val kind: AlbumKind)

@Composable
fun AlbumsScreen(state: ShellState, gallery: GalleryState, actions: MediaActions, drive: DriveState) {
    val selected = state.albumsSelected
    val selecting = selected.isNotEmpty()
    val flight = LocalDeleteFlight.current
    val prompt = LocalDeletePrompt.current
    val bin = remember { ScreenSpot() }
    var renaming by remember { mutableStateOf<AlbumCardData?>(null) }
    // Worked out once per library change, not on every frame (libraries can hold tens of thousands of items).
    val favourites = remember(gallery.items, gallery.favourites) { gallery.items.filter { it.id in gallery.favourites } }
    val videos = remember(gallery.items) { gallery.items.filter { it.isVideo } }
    val inFamily = drive.joined != null
    val cards: List<AlbumCardData> = remember(gallery.items, gallery.albums, gallery.albumOrder, gallery.hiddenAlbums, favourites, videos, gallery.familyAlbum, inFamily) {
        val byId = buildMap {
            put(GalleryState.FAVOURITES_CARD, AlbumCardData(GalleryState.FAVOURITES_CARD, "Favourites", { favourites }, favourites.size, favourites.maxByOrNull { it.takenAtMillis }, Icons.Rounded.Favorite, AlbumKind.Favourites))
            put(GalleryState.VIDEOS_CARD, AlbumCardData(GalleryState.VIDEOS_CARD, "Videos", { videos }, videos.size, videos.maxByOrNull { it.takenAtMillis }, Icons.Rounded.VideoLibrary, AlbumKind.Videos))
            gallery.albums.forEach { a ->
                put(a.id, AlbumCardData(a.id, a.name, { gallery.items.filter { it.albumId == a.id } }, a.count, a.cover, null, AlbumKind.Phone(a.id, a.name)))
            }
        }
        // The order chosen in Settings > Albums (24 Oct); hidden ones left out.
        val family = if (inFamily) listOf(AlbumCardData(FAMILY_CARD, "Family Album", { gallery.familyAlbum }, gallery.familyAlbum.size, gallery.familyAlbum.firstOrNull(), Icons.Rounded.Groups, AlbumKind.Family)) else emptyList()
        family + gallery.orderedCards().filter { it !in gallery.hiddenAlbums }.mapNotNull { byId[it] }
    }
    val chosen = cards.filter { it.id in selected }
    val gridState = rememberSavedGridState()
    val gridOrigin = remember { floatArrayOf(0f, 0f) }
    fun cardBounds(ids: Set<String>): Map<String, androidx.compose.ui.geometry.Rect> {
        val info = gridState.layoutInfo
        return info.visibleItemsInfo.mapNotNull { item ->
            val id = (item.key as? String)?.removePrefix("card:")?.takeIf { (item.key as String).startsWith("card:") && it in ids } ?: return@mapNotNull null
            val left = gridOrigin[0] + item.offset.x
            val top = gridOrigin[1] + item.offset.y - info.viewportStartOffset
            id to androidx.compose.ui.geometry.Rect(left, top, left + item.size.width, top + item.size.width)
        }.toMap()
    }

    // Asked with Pixallery's own pop-up (or Android's), then the covers fly into the bin.
    fun deleteAlbums(albums: List<AlbumCardData>) {
        val items = albums.flatMap { it.items() }
        val bounds = cardBounds(albums.map { it.id }.toSet())
        val flying = albums.mapNotNull { a -> bounds[a.id]?.let { r -> a.newest?.let { FlyingPhoto(it.uri, it.isVideo, r) } } }
        prompt.trash(actions, items) { agreed ->
            if (agreed) {
                flight.launch(flying, bin.bounds)
                gallery.dropNow(items.map { it.id }.toSet())
                state.albumsSelected = emptySet()
            }
        }
    }

    GlassScreen(
        title = if (selecting) "${selected.size} selected" else null,
        subtitle = null,
        pinned = selecting,
        actions = { if (selecting) GlassIconButton(Icons.Rounded.Close, "Cancel selection", onClick = { state.albumsSelected = emptySet() }) },
        // Press and hold an album (slide to pick more): share, rename, cover, delete (24 Oct).
        bottomActions = if (!selecting) null else ({
            GlassIconButton(Icons.Rounded.Share, "Share", onClick = { actions.share(chosen.flatMap { it.items() }.distinctBy { it.id }) })
            val single = chosen.singleOrNull()
            val renamable = single?.kind is AlbumKind.Phone && !AlbumRules.isSystem(single.name)
            if (renamable) GlassIconButton(Icons.Rounded.Edit, "Rename", onClick = { renaming = single })
            if (single != null) GlassIconButton(Icons.Rounded.Wallpaper, "Change cover", onClick = {
                state.albumsSelected = emptySet()
                state.open(Overlay.CoverPicker(single.id, single.name))
            })
            if (chosen.any { it.kind is AlbumKind.Phone }) {
                GlassIconButton(Icons.Rounded.Delete, "Delete", modifier = Modifier.trackSpot(bin), onClick = { deleteAlbums(chosen.filter { it.kind is AlbumKind.Phone }) })
            }
        }),
    ) { padding ->
        if (gallery.access == MediaAccess.NONE) {
            EmptyState(
                "Your albums",
                "Allow access to your photos to see your phone's albums.",
                action = { GlassButton("Allow access", onClick = actions::requestAccess, primary = true) },
            )
            return@GlassScreen
        }
        ScrollToTopEffect(gridState)
        val tablet = LocalTablet.current
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        val currentCards by androidx.compose.runtime.rememberUpdatedState(cards)
        val handler = remember(gridState) {
            object : PhotoGridGestureHandler {
                private var anchor = -1
                private var adding = true
                private var initial: Set<Long> = emptySet()

                override fun photoAt(position: androidx.compose.ui.geometry.Offset): Long? {
                    val info = gridState.layoutInfo
                    val hit = info.visibleItemsInfo.firstOrNull { item ->
                        val top = item.offset.y - info.viewportStartOffset
                        (item.key as? String)?.startsWith("card:") == true &&
                            position.y >= top && position.y < top + item.size.height &&
                            position.x >= item.offset.x && position.x < item.offset.x + item.size.width
                    } ?: return null
                    val id = (hit.key as String).removePrefix("card:")
                    return currentCards.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.toLong()
                }

                override fun onTap(id: Long) {
                    val card = currentCards.getOrNull(id.toInt()) ?: return
                    if (state.albumsSelected.isNotEmpty()) {
                        state.albumsSelected = if (card.id in state.albumsSelected) state.albumsSelected - card.id else state.albumsSelected + card.id
                    } else {
                        state.open(Overlay.Album(card.kind))
                    }
                }

                override fun onDragSelectStart(id: Long) {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    val all = currentCards
                    initial = all.indices.filter { all[it].id in state.albumsSelected }.map { it.toLong() }.toSet()
                    anchor = id.toInt()
                    adding = id !in initial
                    pick(anchor)
                }

                private fun pick(current: Int) {
                    val all = currentCards
                    state.albumsSelected = DragSelect.selection(initial, all.indices.map { it.toLong() }, anchor, current, adding)
                        .mapNotNull { all.getOrNull(it.toInt())?.id }.toSet()
                }

                override fun onDragSelectMove(position: androidx.compose.ui.geometry.Offset) {
                    photoAt(position)?.let { pick(it.toInt()) }
                }

                override fun onDragSelectEnd() = Unit
            }
        }
        LazyVerticalGrid(
            // Pinch to make album cards bigger or smaller (24 Oct), on phones and tablets.
            columns = GridCells.Adaptive(minSize = ALBUM_SIZES[state.albumSize.coerceIn(0, ALBUM_SIZES.lastIndex)]),
            state = gridState,
            contentPadding = padding,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { val p = it.positionInRoot(); gridOrigin[0] = p.x; gridOrigin[1] = p.y }
                .selectGestures({ gridState.isScrollInProgress }, handler)
                .pinchToZoom { zoomIn ->
                    state.albumSize = (state.albumSize + if (zoomIn) 1 else -1).coerceIn(0, ALBUM_SIZES.lastIndex)
                },
        ) {
            item(key = "places", span = { GridItemSpan(maxLineSpan) }) {
                // Three equal tiles, same height, lined up (24 Oct). On tablets they stay small instead of stretching.
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val tile = if (tablet) Modifier.width(200.dp).fillMaxHeight() else Modifier.weight(1f).fillMaxHeight()
                    PlaceTile(
                        Icons.Rounded.Delete, "Recently deleted",
                        if (gallery.trashed.isEmpty()) "Empty" else "${gallery.trashed.size} items",
                        tile,
                    ) { state.open(Overlay.RecentlyDeleted) }
                    PlaceTile(Icons.Rounded.Place, "Places", "Coming soon", tile, enabled = false) {}
                    PlaceTile(Icons.Rounded.Lock, "Secured folder", "Coming soon", tile, enabled = false) {}
                }
            }
            items(cards, key = { "card:${it.id}" }) { card ->
                AlbumCard(
                    name = card.name,
                    count = card.count,
                    covers = gallery.coverOf(card.id, card.newest),
                    icon = card.icon,
                    selecting = selecting,
                    selected = card.id in selected,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    renaming?.let { card ->
        val kind = card.kind as? AlbumKind.Phone
        if (kind != null) {
            RenameAlbumDialog(card.name, onDismiss = { renaming = null }) { newName ->
                renaming = null
                actions.renameAlbum(kind.albumId, newName) { error -> if (error == null) state.albumsSelected = emptySet() }
            }
        }
    }

}

/** Album card sizes for pinching, smallest first. */
private val ALBUM_SIZES = listOf(100.dp, 130.dp, 160.dp, 210.dp, 280.dp)

@Composable
private fun AlbumCard(
    name: String,
    count: Int,
    covers: List<MediaItem>,
    icon: ImageVector?,
    selecting: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val theme = LocalGlassTheme.current
    val inset by androidx.compose.animation.core.animateFloatAsState(if (selected) 0.92f else 1f, motionSpec(), label = "card")
    Column(
        modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = inset; scaleY = inset }
            .glassPanel(GlassCardShape)
            .then(if (selected) Modifier.background(theme.accent.copy(alpha = 0.18f)) else Modifier)
            // Touch is handled by the grid (tap, press and hold, slide to pick more); TalkBack gets the name.
            .semantics(mergeDescendants = true) {
                contentDescription = "$name, ${LibraryCounts.grouped(count)} items"
                if (selecting) this.selected = selected
            }
            .padding(8.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)).background(theme.onGlass.copy(alpha = 0.08f)),
        ) {
            if (covers.isNotEmpty()) {
                CoverMosaic(covers, Modifier.fillMaxSize())
            } else if (icon != null) {
                Icon(icon, null, tint = theme.onGlassSecondary, modifier = Modifier.padding(24.dp).fillMaxSize())
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = selecting,
                enter = androidx.compose.animation.fadeIn(motionSpec()) + androidx.compose.animation.scaleIn(motionSpec()),
                exit = androidx.compose.animation.fadeOut(motionSpec()) + androidx.compose.animation.scaleOut(motionSpec()),
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            ) {
                Box(
                    Modifier.size(24.dp).clip(CircleShape).background(if (selected) theme.accent else Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = theme.onAccent, modifier = Modifier.size(24.dp))
                }
            }
        }
        Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(name, color = theme.onGlass, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${LibraryCounts.grouped(count)} items", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
            }
            if (icon != null && covers.isNotEmpty()) Icon(icon, null, tint = theme.accent)
        }
    }
}

/** An album cover of 1 to 4 photos (24 Oct): one fills it; two side by side; three as one big and two small; four as a grid. */
@Composable
internal fun CoverMosaic(photos: List<MediaItem>, modifier: Modifier = Modifier) {
    val gap = 2.dp
    @Composable
    fun Tile(item: MediaItem, m: Modifier) = AsyncImage(
        model = Thumb(item.uri, item.isVideo, if (photos.size == 1) 512 else 256),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = m,
    )
    when (photos.size) {
        1 -> Tile(photos[0], modifier)
        2 -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap)) {
            photos.forEach { Tile(it, Modifier.weight(1f).fillMaxHeight()) }
        }
        3 -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap)) {
            Tile(photos[0], Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                Tile(photos[1], Modifier.weight(1f).fillMaxWidth())
                Tile(photos[2], Modifier.weight(1f).fillMaxWidth())
            }
        }
        else -> Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
            photos.take(4).chunked(2).forEach { row ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { Tile(it, Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
    }
}

@Composable
private fun PlaceTile(icon: ImageVector, title: String, detail: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val theme = LocalGlassTheme.current
    Column(
        modifier
            .glassPanel(GlassCardShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) theme.accent else theme.onGlassSecondary, modifier = Modifier.size(20.dp))
        Text(
            title,
            color = theme.onGlass,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp).weight(1f),
        )
        Text(detail, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
