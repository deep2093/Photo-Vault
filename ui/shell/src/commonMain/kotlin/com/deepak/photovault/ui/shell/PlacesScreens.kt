package com.deepak.photovault.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepak.photovault.core.media.Timeline
import com.deepak.photovault.core.media.TimelineGrouping
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassCard
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.glassPanel
import kotlinx.datetime.TimeZone

/**
 * Albums > Places (7B-1, 7 Nov): a card for each town or city photos were taken in, the place with the most photos
 * first; its cover is the newest photo there. The map comes in part 2.
 */
@Composable
fun PlacesScreen(state: ShellState, gallery: GalleryState) {
    val places = state.places
    val theme = LocalGlassTheme.current
    val platform = places.platform
    val reading by platform.progress.collectAsState()
    LaunchedEffect(Unit) {
        platform.readNow()
        places.refresh()
    }
    // Read again as more photos get their place.
    LaunchedEffect(reading == null) { places.refresh() }
    val shown = remember(gallery.items) { gallery.items.associateBy { it.id } }
    val list = remember(places.photos, shown) { places.places(shown.keys) }
    GlassScreen(
        title = "Places",
        subtitle = when {
            reading != null -> "Finding places: ${reading!!.done} of ${reading!!.total}"
            list.isEmpty() -> null
            list.size == 1 -> "1 place"
            else -> "${list.size} places"
        },
        onBack = { state.back() },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = padding,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = SCREEN_MARGIN),
        ) {
            if (!platform.canReadLocations()) {
                item(key = "allow", span = { GridItemSpan(maxLineSpan) }) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("See where your photos were taken", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Android keeps photo locations hidden until you allow it. Places are worked out on this phone; nothing is looked up online.",
                            color = theme.onGlassSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
                        )
                        GlassButton("Allow", primary = true, onClick = { platform.askForLocations() })
                    }
                }
            } else if (list.isEmpty() && places.loaded && reading == null) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "No places yet. Photos show here once they have a location (turn on location in the camera's settings).",
                        color = theme.onGlassSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
            items(list, key = { it.first.key }) { (place, ids) ->
                val cover = remember(ids, shown) { ids.mapNotNull { shown[it] }.maxByOrNull { it.takenAtMillis } }
                Column(
                    Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .glassPanel(RoundedCornerShape(22.dp))
                        .clickable { state.open(Overlay.Place(place.key)) }
                        .padding(8.dp),
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp))) {
                        if (cover != null) CoverMosaic(listOf(cover), Modifier.fillMaxWidth().aspectRatio(1f))
                    }
                    Text(
                        place.name,
                        color = theme.onGlass,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                    )
                    Text(
                        listOf(place.region.ifBlank { place.country }, if (ids.size == 1) "1 item" else "${ids.size} items").joinToString(" · "),
                        color = theme.onGlassSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                    )
                }
            }
        }
    }
}

/** One place's photos and videos, newest first, like an album. */
@Composable
fun PlaceScreen(key: String, state: ShellState, gallery: GalleryState, actions: MediaActions) {
    val zone = remember { TimeZone.currentSystemDefault() }
    val place = remember(key) { Place.fromKey(key) }
    val ids = remember(key, state.places.photos) { state.places.photosAt(key) }
    val items = remember(ids, gallery.items) { gallery.items.filter { it.id in ids } }
    val sections = remember(items) { Timeline.group(items, TimelineGrouping.MONTH, zone, today(zone)) }
    SelectableScreen(
        title = place?.name ?: "Place",
        subtitle = listOfNotNull(place?.let { it.region.ifBlank { it.country } }, if (items.size == 1) "1 item" else "${items.size} items").joinToString(" · "),
        state = state,
        gallery = gallery,
        actions = actions,
        onBack = { state.back() },
        selectAllItems = items,
    ) { padding ->
        MediaGrid(
            sections, state, gallery, padding,
            onOpen = { item -> state.open(Overlay.Viewer(sections.flatMap { s -> s.items.map { it.id } }, item.id)) },
        )
    }
}
