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
import androidx.compose.runtime.key
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import com.deepak.photovault.designsystem.motionSpec
import com.deepak.photovault.designsystem.rememberHaptics
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
 * Settings > Albums (24 Oct): every album in the order Albums shows them. Press and hold a row, then drag, to move
 * it; the eye shows or hides it straight away (no pop-up): a hidden one turns grey. Hidden albums don't show in
 * Photos or Albums; their photos stay on the phone and still back up. Favourites and Videos can be hidden and
 * moved too (hiding them hides only their card).
 */
internal fun LazyListScope.albumsPage(gallery: GalleryState) {
    item { AlbumsLayout(gallery) }
}

@Composable
private fun AlbumsLayout(gallery: GalleryState) {
    val theme = LocalGlassTheme.current
    val haptics = rememberHaptics()
    var query by remember { mutableStateOf("") }
    val favourites = remember(gallery.items, gallery.favourites) { gallery.items.filter { it.id in gallery.favourites } }
    val videos = remember(gallery.items) { gallery.items.filter { it.isVideo } }
    val byId = remember(gallery.allAlbums, favourites, videos) {
        mapOf(
            GalleryState.FAVOURITES_CARD to AlbumEntry(GalleryState.FAVOURITES_CARD, "Favourites", favourites.size, null, Icons.Rounded.Favorite, true),
            GalleryState.VIDEOS_CARD to AlbumEntry(GalleryState.VIDEOS_CARD, "Videos", videos.size, null, Icons.Rounded.VideoLibrary, true),
        ) + gallery.allAlbums.associate { it.id to AlbumEntry(it.id, it.name, it.count, it.cover, null, false) }
    }
    val ordered = gallery.orderedCards().mapNotNull { byId[it] }
    val searching = query.isNotBlank()
    val shown = if (searching) ordered.filter { it.name.contains(query.trim(), ignoreCase = true) } else ordered

    // Dragging: which row, and how far it has moved.
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowPx by remember { mutableFloatStateOf(1f) }

    GlassCard(Modifier.cardWidth()) {
        Text(
            if (searching) "Hidden albums don't show in Photos or Albums. Their photos stay on your phone and still back up."
            else "Press and hold an album, then drag to change its place in Albums. Tap the eye to hide or show it.",
            color = theme.onGlassSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        if (ordered.size > 8) GlassTextField(query, { query = it }, "Search albums")
        // Keyed rows, so a row being dragged keeps its touch while the list re-orders under it.
        shown.forEach { e -> key(e.id) {
            val hidden = e.id in gallery.hiddenAlbums
            val isDragged = dragging == e.id
            val lift by animateFloatAsState(if (isDragged) 1.03f else 1f, motionSpec(), label = "lift")
            AlbumRow(
                entry = e,
                hidden = hidden,
                onToggle = { gallery.setHidden(e.id, !hidden) },
                modifier = Modifier
                    .onSizeChanged { rowPx = it.height.toFloat().coerceAtLeast(1f) }
                    .zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (isDragged) dragOffset else 0f
                        scaleX = lift
                        scaleY = lift
                        shadowElevation = if (isDragged) 12f else 0f
                    }
                    .then(
                        if (searching) Modifier else Modifier.pointerInput(e.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptics.longPress()
                                    dragging = e.id
                                    dragOffset = 0f
                                },
                                onDragEnd = { dragging = null; dragOffset = 0f },
                                onDragCancel = { dragging = null; dragOffset = 0f },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    // Swap with the neighbour once the row has moved more than half a row past it.
                                    val cards = gallery.orderedCards()
                                    val from = cards.indexOf(e.id)
                                    val steps = (dragOffset / rowPx).toInt()
                                    if (steps != 0 && from >= 0) {
                                        val to = (from + steps).coerceIn(0, cards.lastIndex)
                                        if (to != from) {
                                            gallery.moveAlbum(from, to)
                                            dragOffset -= (to - from) * rowPx
                                            haptics.tick()
                                        }
                                    }
                                },
                            )
                        },
                    ),
            )
        } }
    }
}

@Composable
private fun AlbumRow(entry: AlbumEntry, hidden: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalGlassTheme.current
    // A hidden album fades to grey; showing it again brings the colour back (24 Oct).
    val fade by animateFloatAsState(if (hidden) 0.45f else 1f, motionSpec(250), label = "hidden")
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.DragIndicator, contentDescription = null, tint = theme.onGlassSecondary.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.size(44.dp).graphicsLayer { alpha = fade }.clip(RoundedCornerShape(10.dp)).background(theme.onGlass.copy(alpha = 0.08f)),
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
        Column(Modifier.weight(1f).graphicsLayer { alpha = fade }) {
            Text(entry.name, color = theme.onGlass, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (hidden) "Hidden" else "${LibraryCounts.grouped(entry.count)} items",
                color = theme.onGlassSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        androidx.compose.animation.Crossfade(targetState = hidden, animationSpec = motionSpec(250), label = "eye") { isHidden ->
            GlassIconButton(
                if (isHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                if (isHidden) "Show ${entry.name}" else "Hide ${entry.name}",
                onClick = onToggle,
                modifier = Modifier.graphicsLayer { alpha = if (isHidden) 0.5f else 1f },
            )
        }
    }
}
