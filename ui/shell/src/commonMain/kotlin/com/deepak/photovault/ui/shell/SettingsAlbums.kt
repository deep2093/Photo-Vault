package com.deepak.photovault.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.deepak.photovault.core.media.LibraryCounts
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.Thumb
import com.deepak.photovault.designsystem.GlassCard
import com.deepak.photovault.designsystem.GlassSwitchRow
import com.deepak.photovault.designsystem.GlassTextField
import com.deepak.photovault.designsystem.LocalGlassTheme

/** One row on the Hide albums page: a real phone album, or the Favourites / Videos cards. */
private data class AlbumEntry(val id: String, val name: String, val count: Int, val cover: MediaItem?, val icon: ImageVector?)

/**
 * Albums > menu > Hide albums (31 Oct; it replaced Settings > Albums, and the order is now set by holding and
 * sliding cards in Albums). A switch for every album: off hides it from Photos and Albums. Hidden albums' photos
 * stay on the phone and still back up.
 */
@Composable
fun HiddenAlbumsScreen(state: ShellState, gallery: GalleryState) {
    val theme = LocalGlassTheme.current
    var query by remember { mutableStateOf("") }
    val favourites = remember(gallery.items, gallery.favourites) { gallery.items.filter { it.id in gallery.favourites } }
    val videos = remember(gallery.items) { gallery.items.filter { it.isVideo } }
    val byId = remember(gallery.allAlbums, favourites, videos) {
        mapOf(
            GalleryState.FAVOURITES_CARD to AlbumEntry(GalleryState.FAVOURITES_CARD, "Favourites", favourites.size, favourites.maxByOrNull { it.takenAtMillis }, Icons.Rounded.Favorite),
            GalleryState.VIDEOS_CARD to AlbumEntry(GalleryState.VIDEOS_CARD, "Videos", videos.size, videos.maxByOrNull { it.takenAtMillis }, Icons.Rounded.VideoLibrary),
        ) + gallery.allAlbums.associate { it.id to AlbumEntry(it.id, it.name, it.count, it.cover, null) }
    }
    val ordered = gallery.orderedCards().mapNotNull { byId[it] }
    val shown = if (query.isBlank()) ordered else ordered.filter { it.name.contains(query.trim(), ignoreCase = true) }
    GlassListScreen(title = "Hide albums", subtitle = null, onBack = { state.back() }) {
        item {
            GlassCard(Modifier.cardWidth()) {
                Text(
                    "Switch an album off to hide it from Photos and Albums. Its photos stay on your phone and still back up.",
                    color = theme.onGlassSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (ordered.size > 8) GlassTextField(query, { query = it }, "Search albums")
                shown.forEach { e ->
                    androidx.compose.runtime.key(e.id) {
                        Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(theme.onGlass.copy(alpha = 0.08f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (e.cover != null) {
                                    AsyncImage(Thumb(e.cover.uri, e.cover.isVideo, 160), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                } else if (e.icon != null) {
                                    Icon(e.icon, null, tint = theme.accent, modifier = Modifier.size(22.dp))
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            GlassSwitchRow(
                                title = e.name,
                                checked = e.id !in gallery.hiddenAlbums,
                                onCheckedChange = { on -> gallery.setHidden(e.id, !on) },
                                description = "${LibraryCounts.grouped(e.count)} items",
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}
