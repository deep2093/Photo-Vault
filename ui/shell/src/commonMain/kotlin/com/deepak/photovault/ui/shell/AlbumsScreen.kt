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
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import coil3.compose.AsyncImage
import com.deepak.photovault.core.media.LibraryCounts
import com.deepak.photovault.core.media.MediaAccess
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.Thumb
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassCardShape
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.glassPanel

@Composable
fun AlbumsScreen(state: ShellState, gallery: GalleryState, actions: MediaActions) {
    GlassScreen(title = null, subtitle = null) { padding ->
        if (gallery.access == MediaAccess.NONE) {
            EmptyState(
                "Your albums",
                "Allow access to your photos to see your phone's albums.",
                action = { GlassButton("Allow access", onClick = actions::requestAccess, primary = true) },
            )
            return@GlassScreen
        }
        // Worked out once per library change, not on every frame (libraries can hold tens of thousands of items).
        val favourites = remember(gallery.items, gallery.favourites) { gallery.items.filter { it.id in gallery.favourites } }
        val videos = remember(gallery.items) { gallery.items.filter { it.isVideo } }
        val gridState = rememberLazyGridState()
        ScrollToTopEffect(gridState)
        val tablet = LocalTablet.current
        LazyVerticalGrid(
            // Pinch to make album cards bigger or smaller (24 Oct), on phones and tablets.
            columns = GridCells.Adaptive(minSize = ALBUM_SIZES[state.albumSize.coerceIn(0, ALBUM_SIZES.lastIndex)]),
            state = gridState,
            contentPadding = padding,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().pinchToZoom { zoomIn ->
                state.albumSize = (state.albumSize + if (zoomIn) 1 else -1).coerceIn(0, ALBUM_SIZES.lastIndex)
            },
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
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
            if (!gallery.isHidden(GalleryState.FAVOURITES_CARD)) item(key = "favourites") {
                AlbumCard("Favourites", favourites.size, favourites.maxByOrNull { it.takenAtMillis }, Icons.Rounded.Favorite) {
                    state.open(Overlay.Album(AlbumKind.Favourites))
                }
            }
            if (!gallery.isHidden(GalleryState.VIDEOS_CARD)) item(key = "videos") {
                AlbumCard("Videos", videos.size, videos.maxByOrNull { it.takenAtMillis }, Icons.Rounded.VideoLibrary) {
                    state.open(Overlay.Album(AlbumKind.Videos))
                }
            }
            items(gallery.albums, key = { "album-${it.id}" }) { album ->
                AlbumCard(album.name, album.count, album.cover, null) {
                    state.open(Overlay.Album(AlbumKind.Phone(album.id, album.name)))
                }
            }
        }
    }
}

/** Album card sizes for pinching, smallest first. */
private val ALBUM_SIZES = listOf(100.dp, 130.dp, 160.dp, 210.dp, 280.dp)

@Composable
private fun AlbumCard(name: String, count: Int, cover: MediaItem?, icon: ImageVector?, onClick: () -> Unit) {
    val theme = LocalGlassTheme.current
    Column(
        Modifier
            .fillMaxWidth()
            .glassPanel(GlassCardShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$name, ${LibraryCounts.grouped(count)} items" }
            .padding(8.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)).background(theme.onGlass.copy(alpha = 0.08f)),
        ) {
            if (cover != null) {
                AsyncImage(
                    model = Thumb(cover.uri, cover.isVideo, 512),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (icon != null) {
                Icon(icon, null, tint = theme.onGlassSecondary, modifier = Modifier.padding(24.dp).fillMaxSize())
            }
        }
        Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(name, color = theme.onGlass, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${LibraryCounts.grouped(count)} items", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
            }
            if (icon != null && cover != null) Icon(icon, null, tint = theme.accent)
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
