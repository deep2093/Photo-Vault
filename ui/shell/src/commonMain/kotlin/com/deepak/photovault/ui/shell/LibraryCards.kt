package com.deepak.photovault.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.deepak.photovault.core.media.LibraryCounts
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.MonthSummary
import com.deepak.photovault.core.media.Thumb
import com.deepak.photovault.core.media.YearSummary
import com.deepak.photovault.designsystem.LocalGlassTheme

/**
 * Years: one big card per year with a photo from around today's date that year (24 Oct, after Apple Photos).
 * Tap, or spread two fingers, to go into that year's months.
 */
@Composable
internal fun YearsGrid(
    years: List<YearSummary>,
    grid: LazyGridState,
    contentPadding: PaddingValues,
    onOpen: (YearSummary) -> Unit,
    onPinch: (zoomIn: Boolean) -> Unit,
) {
    ScrollToTopEffect(grid)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 320.dp),
        state = grid,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().pinchToZoom(onPinch),
    ) {
        itemsIndexed(years, key = { _, y -> "year-${y.year}" }) { _, year ->
            LevelCard(
                photos = listOf(year.cover),
                title = year.year.toString(),
                detail = "${LibraryCounts.grouped(year.count)} items",
                big = true,
                description = "${year.year}, ${year.count} items",
                onClick = { onOpen(year) },
            )
        }
    }
}

/** Months: a card per month with a few photos from across it. Tap to see that month in All. */
@Composable
internal fun MonthsGrid(
    months: List<MonthSummary>,
    grid: LazyGridState,
    contentPadding: PaddingValues,
    onOpen: (MonthSummary) -> Unit,
    onPinch: (zoomIn: Boolean) -> Unit,
) {
    ScrollToTopEffect(grid)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 300.dp),
        state = grid,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().pinchToZoom(onPinch),
    ) {
        itemsIndexed(months, key = { _, m -> "month-${m.key}" }) { _, month ->
            LevelCard(
                photos = month.covers,
                title = month.label,
                detail = "${LibraryCounts.grouped(month.count)} items",
                big = false,
                description = "${month.label}, ${month.count} items",
                onClick = { onOpen(month) },
            )
        }
    }
}

/** A rounded photo card: one photo, or one large and two small, with the title on a soft shade. */
@Composable
private fun LevelCard(photos: List<MediaItem>, title: String, detail: String, big: Boolean, description: String, onClick: () -> Unit) {
    val theme = LocalGlassTheme.current
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(if (big) 1.25f else 1.6f)
            .clip(RoundedCornerShape(24.dp))
            .background(theme.onGlass.copy(alpha = 0.08f))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        if (photos.size >= 3) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Cover(photos[0], Modifier.weight(2f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Cover(photos[1], Modifier.weight(1f).fillMaxWidth())
                    Cover(photos[2], Modifier.weight(1f).fillMaxWidth())
                }
            }
        } else {
            photos.firstOrNull()?.let { Cover(it, Modifier.fillMaxSize()) }
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 14.dp),
        ) {
            Column {
                Text(
                    title,
                    color = Color.White,
                    style = if (big) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(detail, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Cover(item: MediaItem, modifier: Modifier) {
    AsyncImage(
        model = Thumb(item.uri, item.isVideo, 512),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}
