package com.deepak.photovault.ui.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.deepak.photovault.core.media.Timeline
import com.deepak.photovault.core.media.TimelineGrouping
import com.deepak.photovault.designsystem.GlassIconButton
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * Choose an album's cover (24 Oct): tap 1 to 4 photos (shown newest first), then Save. "Use newest"
 * goes back to the newest photo. The cover is kept on this phone.
 */
@Composable
fun CoverPickerScreen(page: Overlay.CoverPicker, state: ShellState, gallery: GalleryState) {
    val zone = remember { TimeZone.currentSystemDefault() }
    val items = remember(page.cardId, gallery.items, gallery.favourites, gallery.familyAlbum) {
        when (page.cardId) {
            GalleryState.FAVOURITES_CARD -> gallery.items.filter { it.id in gallery.favourites }
            GalleryState.VIDEOS_CARD -> gallery.items.filter { it.isVideo }
            // Everyone picks their own Family Album cover: it is kept on each phone, not shared (25 Oct).
            FAMILY_CARD -> gallery.familyAlbum
            else -> gallery.items.filter { it.albumId == page.cardId }
        }
    }
    val today = remember { Timeline.localDate(Clock.System.now().toEpochMilliseconds(), zone) }
    val sections = remember(items) { Timeline.group(items, TimelineGrouping.MONTH, zone, today) }
    // Start from the current cover; leave nothing selected behind.
    LaunchedEffect(page.cardId) { state.selected = gallery.albumCovers[page.cardId].orEmpty().toSet() }
    DisposableEffect(Unit) { onDispose { state.selected = emptySet() } }
    val picked = state.selected.size
    GlassScreen(
        title = "Cover for ${page.title}",
        subtitle = when {
            picked == 0 -> "Tap up to 4 photos"
            picked > MAX_COVER -> "Up to 4: $picked picked"
            else -> "$picked of 4 picked"
        },
        onBack = { state.back() },
        pinned = true,
        bottomActions = {
            GlassIconButton(Icons.Rounded.AutoAwesome, "Use newest", onClick = {
                gallery.setCover(page.cardId, emptyList())
                state.back()
            })
            if (picked in 1..MAX_COVER) {
                GlassIconButton(Icons.Rounded.Check, "Save cover", onClick = {
                    // Keep the order: newest first among the picked ones.
                    val ids = items.filter { it.id in state.selected }.sortedByDescending { it.takenAtMillis }.map { it.id }
                    gallery.setCover(page.cardId, ids)
                    state.back()
                })
            }
        },
    ) { padding ->
        MediaGrid(sections, state, gallery, padding, onOpen = { state.toggleSelected(it.id) })
    }
}

private const val MAX_COVER = 4
