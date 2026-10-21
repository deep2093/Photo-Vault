package com.deepak.photovault.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.Thumb
import com.deepak.photovault.core.media.Timeline
import com.deepak.photovault.core.media.TimelineSection
import com.deepak.photovault.designsystem.AdaptiveLayout
import com.deepak.photovault.designsystem.ContributeScreenColor
import com.deepak.photovault.designsystem.LocalGlassSettings
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.SectionHeader
import com.deepak.photovault.designsystem.motionSpec

/** One photo's extra label in a grid, e.g. "12 days" in Recently deleted. */
typealias TileLabel = (MediaItem) -> String?

/**
 * The shared photo grid: date sections, pinch to change size (one column per step, smoothly re-laid out),
 * tap to open, long-press to select. Used by Photos, albums, search and Recently deleted.
 */
@Composable
fun MediaGrid(
    sections: List<TimelineSection>,
    state: ShellState,
    gallery: GalleryState,
    contentPadding: PaddingValues,
    onOpen: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    tileLabel: TileLabel? = null,
    header: (LazyGridScope.() -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = maxWidth.value.toInt()
        val maxColumns = AdaptiveLayout.maxColumns(width)
        val columns = state.gridColumns?.let { AdaptiveLayout.clampColumns(it, width) } ?: AdaptiveLayout.defaultColumns(width)
        val currentColumns by rememberUpdatedState(columns)
        val currentMax by rememberUpdatedState(maxColumns)
        val tileDp = maxWidth / columns
        val gridState = rememberLazyGridState()
        val reduceMotion = LocalGlassSettings.current.reduceMotion
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier
                .fillMaxSize()
                .pinchToZoom { zoomIn -> state.zoomGrid(if (zoomIn) +1 else -1, currentColumns, currentMax) },
        ) {
            header?.invoke(this)
            sections.forEach { section ->
                item(key = "header-${section.key}", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                    SectionHeader(section.label, Modifier.padding(start = 4.dp, top = 12.dp))
                }
                items(section.items, key = { it.id }, contentType = { "tile" }) { item ->
                    PhotoTile(
                        item = item,
                        tileSize = tileDp,
                        favourite = gallery.isFavourite(item),
                        selecting = state.selecting,
                        selected = item.id in state.selected,
                        label = tileLabel?.invoke(item),
                        onClick = { if (state.selecting) state.toggleSelected(item.id) else onOpen(item) },
                        onLongClick = { state.toggleSelected(item.id) },
                        // Photos glide to their new place when the grid changes size (instant with reduce motion).
                        modifier = if (reduceMotion) Modifier else Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

/** Main colours of loaded thumbnails, so adaptive themes don't re-read the same photo. */
private val mainColors = mutableStateMapOf<Long, Int>()

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoTile(
    item: MediaItem,
    tileSize: Dp,
    favourite: Boolean,
    selecting: Boolean,
    selected: Boolean,
    label: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalGlassTheme.current
    val adaptive = theme.adaptive != null
    // Thumbnail sizes are rounded up to a few buckets so the cache is shared between grid sizes.
    val px = with(LocalDensity.current) { tileSize.toPx() }.toInt()
    val bucket = when {
        px <= 128 -> 128
        px <= 256 -> 256
        else -> 512
    }
    val inset by animateFloatAsState(if (selected) 0.86f else 1f, motionSpec())
    ContributeScreenColor(mainColors[item.id]?.let { Color(it) })
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(theme.onGlass.copy(alpha = 0.08f))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics {
                contentDescription = buildString {
                    append(if (item.isVideo) "Video" else "Photo")
                    append(", ").append(Timeline.localDate(item.takenAtMillis, kotlinx.datetime.TimeZone.currentSystemDefault()))
                    if (favourite) append(", favourite")
                }
                if (selecting) this.selected = selected
            },
    ) {
        AsyncImage(
            model = Thumb(item.uri, item.isVideo, bucket),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onSuccess = { result ->
                if (adaptive && item.id !in mainColors) mainColorOf(result.result.image)?.let { mainColors[item.id] = it }
            },
            modifier = Modifier.fillMaxSize().scale(inset).clip(RoundedCornerShape(if (selected) 10.dp else 0.dp)),
        )
        if (item.isVideo) {
            Text(
                Timeline.duration(item.durationMillis),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
            if (tileSize >= 72.dp) {
                Icon(Icons.Rounded.PlayCircle, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.align(Alignment.Center).size(28.dp))
            }
        }
        if (favourite) {
            Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(16.dp))
        }
        if (label != null) {
            Text(
                label,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        AnimatedVisibility(
            visible = selecting,
            enter = fadeIn(motionSpec()) + scaleIn(motionSpec()),
            exit = fadeOut(motionSpec()) + scaleOut(motionSpec()),
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (selected) theme.accent else Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = theme.onAccent, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/**
 * A two-finger pinch steps the grid one column at a time. Only acts when two or more fingers are down,
 * so one-finger scrolling is untouched.
 */
internal fun Modifier.pinchToZoom(onStep: (zoomIn: Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var accumulated = 1f
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                accumulated *= event.calculateZoom()
                if (accumulated > 1.2f) {
                    onStep(true)
                    accumulated = 1f
                } else if (accumulated < 0.83f) {
                    onStep(false)
                    accumulated = 1f
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

/** A friendly centred message for empty or permission states. */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val theme = LocalGlassTheme.current
    androidx.compose.foundation.layout.Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = theme.onGlass, style = MaterialTheme.typography.titleLarge)
        Text(
            body,
            color = theme.onGlassSecondary,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        action?.invoke()
    }
}
