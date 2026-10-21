package com.deepak.photovault.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.deepak.photovault.designsystem.AdaptiveLayout
import com.deepak.photovault.designsystem.BackupStatus
import com.deepak.photovault.designsystem.GlassChip
import com.deepak.photovault.designsystem.GlassIconButton
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.SectionHeader
import com.deepak.photovault.designsystem.StatusBadge

private enum class TimelineGrouping(val label: String) { DAY("Day"), MONTH("Month"), YEAR("Year") }

@Composable
fun PhotosScreen(state: ShellState) {
    var grouping by rememberSaveable { mutableStateOf(TimelineGrouping.DAY) }
    val waiting = SampleData.days.sumOf { day -> day.photos.count { it.status == BackupStatus.PHONE_ONLY } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val autoColumns = AdaptiveLayout.defaultColumns(maxWidth.value.toInt())
        val columns = state.gridColumns ?: autoColumns
        val currentColumns by rememberUpdatedState(columns)
        GlassScreen(
            title = "Photos",
            subtitle = "Synced safely · $waiting waiting to back up",
            actions = {
                GlassIconButton(Icons.Rounded.ZoomOut, "Smaller photos", onClick = { state.zoomGrid(-1, columns) })
                GlassIconButton(Icons.Rounded.ZoomIn, "Larger photos", onClick = { state.zoomGrid(+1, columns) })
                GlassIconButton(Icons.Rounded.Search, "Search", onClick = {})
            },
        ) { padding ->
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = padding,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .pinchToZoom { zoomIn -> state.zoomGrid(if (zoomIn) +1 else -1, currentColumns) },
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        TimelineGrouping.entries.forEach { option ->
                            GlassChip(option.label, selected = grouping == option, onClick = { grouping = option })
                        }
                    }
                }
                SampleData.days.forEach { day ->
                    item(key = "header-${day.label}", span = { GridItemSpan(maxLineSpan) }) {
                        SectionHeader(day.label, Modifier.padding(start = 4.dp, top = 12.dp))
                    }
                    items(day.photos, key = { it.id }) { photo -> PhotoTile(photo) }
                }
            }
        }
    }
}

/**
 * A two-finger pinch steps the grid one column at a time. Only acts when two or more fingers are down,
 * so one-finger scrolling is untouched.
 */
private fun Modifier.pinchToZoom(onStep: (zoomIn: Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var accumulated = 1f
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                accumulated *= event.calculateZoom()
                if (accumulated > 1.25f) {
                    onStep(true)
                    accumulated = 1f
                } else if (accumulated < 0.8f) {
                    onStep(false)
                    accumulated = 1f
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

/** A sample photo. The photo itself is never glass: only the small status badge sits on top. */
@Composable
private fun PhotoTile(photo: SamplePhoto) {
    val theme = LocalGlassTheme.current
    val hues = theme.sampleHues
    val a = hues[photo.hue % hues.size]
    val b = hues[(photo.hue + 2) % hues.size]
    val description = buildString {
        append(if (photo.isVideo) "Video" else "Photo")
        append(", ").append(photo.status.description)
        if (photo.favourite) append(", favourite")
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(Brush.linearGradient(listOf(lerp(a, b, photo.shade), lerp(b, Color.White, 0.25f))))
            .semantics { contentDescription = description },
    ) {
        if (photo.isVideo) {
            Text(
                "0:${(10 + photo.id % 50).toString().padStart(2, '0')}",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        if (photo.favourite) {
            Icon(
                Icons.Rounded.Favorite,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(16.dp),
            )
        }
        StatusBadge(photo.status, Modifier.align(Alignment.BottomEnd).padding(4.dp))
    }
}

