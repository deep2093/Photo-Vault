package com.deepak.photovault.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.deepak.photovault.core.media.LibraryCounts
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.Thumb
import com.deepak.photovault.designsystem.GlassCard
import com.deepak.photovault.designsystem.GlassIconButton
import com.deepak.photovault.designsystem.GlassTextField
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.SectionHeader

/** One row on the Albums settings page: a real phone album, or the Favourites / Videos cards. */
private data class AlbumEntry(val id: String, val name: String, val count: Int, val cover: MediaItem?, val icon: ImageVector?, val virtual: Boolean)

/**
 * Settings > Albums (24 Oct, made less crowded): a search box when there are many, then compact rows (small cover,
 * name, count, an eye to show or hide), with hidden albums gathered in their own group at the bottom.
 * Favourites and Videos can be hidden too; that hides only their card in Albums.
 */
internal fun LazyListScope.albumsPage(gallery: GalleryState) {
    item { AlbumsVisibility(gallery) }
}

@Composable
private fun AlbumsVisibility(gallery: GalleryState) {
    val theme = LocalGlassTheme.current
    var query by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<AlbumEntry?>(null) }
    val favourites = gallery.items.filter { it.id in gallery.favourites }
    val videos = gallery.items.filter { it.isVideo }
    val entries = listOf(
        AlbumEntry(GalleryState.FAVOURITES_CARD, "Favourites", favourites.size, null, Icons.Rounded.Favorite, true),
        AlbumEntry(GalleryState.VIDEOS_CARD, "Videos", videos.size, null, Icons.Rounded.VideoLibrary, true),
    ) + gallery.allAlbums.map { AlbumEntry(it.id, it.name, it.count, it.cover, null, false) }
    val filtered = entries.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    val (hidden, shown) = filtered.partition { it.id in gallery.hiddenAlbums }

    GlassCard(Modifier.cardWidth()) {
        Text(
            "Hidden albums don't show in Photos or Albums. Their photos stay on your phone and still back up.",
            color = theme.onGlassSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        if (entries.size > 8) GlassTextField(query, { query = it }, "Search albums")
        SectionHeader("Shown")
        shown.forEach { e -> AlbumRow(e, hidden = false) { confirm = e } }
        if (hidden.isNotEmpty()) {
            SectionHeader("Hidden")
            hidden.forEach { e -> AlbumRow(e, hidden = true) { gallery.setHidden(e.id, false) } }
        }
    }
    confirm?.let { e ->
        ConfirmDialog(
            title = "Hide ${e.name}?",
            body = if (e.virtual) "The ${e.name} card won't show in Albums. The photos still show in Photos."
            else "Its photos will be hidden from both Photos and Albums. They stay on your phone and still back up. You can show it again here.",
            confirm = "Hide",
            onDismiss = { confirm = null },
        ) {
            gallery.setHidden(e.id, true)
            confirm = null
        }
    }
}

@Composable
private fun AlbumRow(entry: AlbumEntry, hidden: Boolean, onToggle: () -> Unit) {
    val theme = LocalGlassTheme.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(theme.onGlass.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                entry.cover != null -> AsyncImage(
                    model = Thumb(entry.cover.uri, entry.cover.isVideo, 160),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                entry.icon != null -> Icon(entry.icon, contentDescription = null, tint = theme.accent, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, color = if (hidden) theme.onGlassSecondary else theme.onGlass, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${LibraryCounts.grouped(entry.count)} items", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
        }
        GlassIconButton(
            if (hidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
            if (hidden) "Show ${entry.name}" else "Hide ${entry.name}",
            onClick = onToggle,
        )
    }
}
