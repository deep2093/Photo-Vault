package com.deepak.photovault.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
    GlassScreen(title = "Albums", subtitle = "${gallery.albums.size} albums on this phone") { padding ->
        if (gallery.access == MediaAccess.NONE) {
            EmptyState(
                "Your albums",
                "Allow access to your photos to see your phone's albums.",
                action = { GlassButton("Allow access", onClick = actions::requestAccess, primary = true) },
            )
            return@GlassScreen
        }
        val favourites = gallery.items.filter { gallery.isFavourite(it) }
        val videos = gallery.items.filter { it.isVideo }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            contentPadding = padding,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlaceTile(
                        Icons.Rounded.Delete, "Recently deleted",
                        if (gallery.trashed.isEmpty()) "Empty" else "${gallery.trashed.size} items",
                        Modifier.weight(1f),
                    ) { state.open(Overlay.RecentlyDeleted) }
                    PlaceTile(Icons.Rounded.Place, "Places", "Coming soon", Modifier.weight(1f), enabled = false) {}
                    PlaceTile(Icons.Rounded.Lock, "Secured folder", "Coming soon", Modifier.weight(1f), enabled = false) {}
                }
            }
            item(key = "favourites") {
                AlbumCard("Favourites", favourites.size, favourites.maxByOrNull { it.takenAtMillis }, Icons.Rounded.Favorite) {
                    state.open(Overlay.Album(AlbumKind.Favourites))
                }
            }
            item(key = "videos") {
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
            .padding(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) theme.accent else theme.onGlassSecondary)
        Text(
            title,
            color = theme.onGlass,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(detail, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
