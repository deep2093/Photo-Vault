package com.deepak.photovault.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.deepak.photovault.core.media.LibraryCounts
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.Thumb
import com.deepak.photovault.core.media.Timeline
import com.deepak.photovault.designsystem.GlassIconButton
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.LocalHazeState
import com.deepak.photovault.designsystem.glassChrome
import com.deepak.photovault.designsystem.motionSpec
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Full-screen viewer: swipe between photos, pinch or double-tap to zoom, tap to show or hide the bars,
 * play videos. Photos are shown on black, never tinted; only the bars are glass.
 */
@Composable
fun ViewerScreen(viewer: Overlay.Viewer, state: ShellState, gallery: GalleryState, actions: MediaActions) {
    // Photos deleted while the viewer is open drop out of the list.
    val ids = viewer.ids.filter { gallery.find(it) != null }
    if (ids.isEmpty()) {
        LaunchedEffect(Unit) { state.back() }
        return
    }
    val start = ids.indexOf(viewer.startId).coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { ids.size }
    var barsVisible by rememberSaveable { mutableStateOf(true) }
    var infoVisible by rememberSaveable { mutableStateOf(false) }
    val current = gallery.find(ids[pager.currentPage.coerceIn(0, ids.lastIndex)]) ?: return
    val system = WindowInsets.safeDrawing.asPaddingValues()

    // No blur source here: the bars use solid glass over the photo.
    CompositionLocalProvider(LocalHazeState provides null) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pager,
                key = { ids[it] },
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val item = gallery.find(ids[page]) ?: return@HorizontalPager
                if (item.isVideo) {
                    VideoPage(item, isCurrent = page == pager.currentPage, onTap = { barsVisible = !barsVisible })
                } else {
                    ZoomablePhoto(item, onTap = { barsVisible = !barsVisible })
                }
            }

            AnimatedVisibility(
                visible = barsVisible,
                enter = fadeIn(motionSpec()) + slideInVertically(motionSpec()) { -it / 2 },
                exit = fadeOut(motionSpec()) + slideOutVertically(motionSpec()) { -it / 2 },
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                ViewerTopBar(current, pager.currentPage + 1, ids.size, onBack = { state.back() }, topPadding = system.calculateTopPadding())
            }

            AnimatedVisibility(
                visible = barsVisible,
                enter = fadeIn(motionSpec()) + slideInVertically(motionSpec()) { it / 2 },
                exit = fadeOut(motionSpec()) + slideOutVertically(motionSpec()) { it / 2 },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(
                    Modifier.padding(bottom = system.calculateBottomPadding() + 12.dp, start = 12.dp, end = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AnimatedVisibility(visible = infoVisible) { InfoCard(current) }
                    ViewerActions(
                        favourite = gallery.isFavourite(current),
                        onShare = { actions.share(listOf(current)) },
                        onFavourite = { gallery.toggleFavourite(listOf(current)) },
                        onInfo = { infoVisible = !infoVisible },
                        onDelete = { actions.moveToRecentlyDeleted(listOf(current)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerTopBar(item: MediaItem, position: Int, total: Int, onBack: () -> Unit, topPadding: androidx.compose.ui.unit.Dp) {
    val theme = LocalGlassTheme.current
    Row(
        Modifier
            .padding(top = topPadding + 12.dp, start = 12.dp, end = 12.dp)
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .glassChrome(RoundedCornerShape(28.dp), elevation = 8.dp)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
        Column(Modifier.weight(1f)) {
            Text(whenTaken(item), color = theme.onGlass, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("$position of ${LibraryCounts.grouped(total)}", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ViewerActions(favourite: Boolean, onShare: () -> Unit, onFavourite: () -> Unit, onInfo: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.glassChrome(RoundedCornerShape(percent = 50), elevation = 8.dp).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassIconButton(Icons.Rounded.Share, "Share", onClick = onShare)
        GlassIconButton(if (favourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (favourite) "Remove from favourites" else "Add to favourites", onClick = onFavourite)
        GlassIconButton(Icons.Rounded.Info, "Details", onClick = onInfo)
        GlassIconButton(Icons.Rounded.Delete, "Move to Recently deleted", onClick = onDelete)
    }
}

@Composable
private fun InfoCard(item: MediaItem) {
    val theme = LocalGlassTheme.current
    Column(
        Modifier
            .padding(bottom = 10.dp)
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .glassChrome(RoundedCornerShape(24.dp), elevation = 8.dp)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(item.displayName, color = theme.onGlass, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Detail("Taken", whenTaken(item))
        Detail("Album", item.albumName)
        Detail("Size", "${megabytes(item.sizeBytes)} · ${item.width} × ${item.height}")
        if (item.isVideo) Detail("Length", Timeline.duration(item.durationMillis))
        Detail("Backup", "Arrives with drive backup")
    }
}

@Composable
private fun Detail(label: String, value: String) {
    val theme = LocalGlassTheme.current
    Row {
        Text(label, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(min = 72.dp))
        Text(value, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Pinch to zoom (up to 6x), drag while zoomed, double-tap to zoom in or out, tap to toggle bars. */
@Composable
private fun ZoomablePhoto(item: MediaItem, onTap: () -> Unit) {
    val scope = rememberCoroutineScope()
    val scale = remember(item.id) { Animatable(1f) }
    val offset = remember(item.id) { Animatable(Offset.Zero, Offset.VectorConverter) }
    val context = LocalPlatformContext.current
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        scope.launch {
                            if (scale.value > 1.05f) {
                                launch { offset.animateTo(Offset.Zero) }
                                scale.animateTo(1f)
                            } else {
                                // Zoom towards where the finger tapped.
                                val target = 2.5f
                                val centre = Offset(size.width / 2f, size.height / 2f)
                                launch { offset.animateTo(clampOffset((centre - tap) * (target - 1f), target, size.width, size.height)) }
                                scale.animateTo(target)
                            }
                        }
                    },
                )
            }
            .pointerInput(item.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        // One finger at 1x belongs to the pager (swipe to the next photo).
                        if (fingers >= 2 || scale.value > 1.01f) {
                            val newScale = (scale.value * event.calculateZoom()).coerceIn(1f, 6f)
                            val newOffset = clampOffset(offset.value + event.calculatePan(), newScale, size.width, size.height)
                            scope.launch {
                                scale.snapTo(newScale)
                                offset.snapTo(newOffset)
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (scale.value < 1.05f) {
                        scope.launch {
                            launch { offset.animateTo(Offset.Zero) }
                            scale.animateTo(1f)
                        }
                    }
                }
            }
            .semantics { contentDescription = "Photo, ${whenTaken(item)}" },
    ) {
        // The cached thumbnail shows instantly; the full photo fades in over it.
        AsyncImage(model = Thumb(item.uri, false, 512), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        AsyncImage(
            model = ImageRequest.Builder(context).data(item.uri).crossfade(true).build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    translationX = offset.value.x
                    translationY = offset.value.y
                },
        )
    }
}

private fun clampOffset(offset: Offset, scale: Float, width: Int, height: Int): Offset {
    val maxX = width * (scale - 1f) / 2f
    val maxY = height * (scale - 1f) / 2f
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

/** A video: its thumbnail with a play button; plays in place when tapped and stops when swiped away. */
@Composable
private fun VideoPage(item: MediaItem, isCurrent: Boolean, onTap: () -> Unit) {
    var playing by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(isCurrent) { if (!isCurrent) playing = false }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (playing) {
            VideoPlayer(item.uri, Modifier.fillMaxSize())
        } else {
            AsyncImage(
                model = Thumb(item.uri, true, 512),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().clickable(onClick = onTap),
            )
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(role = Role.Button, onClick = { playing = true })
                    .semantics { contentDescription = "Play video, ${Timeline.duration(item.durationMillis)}" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(44.dp))
            }
        }
    }
}

private fun whenTaken(item: MediaItem): String {
    val zone = TimeZone.currentSystemDefault()
    val time = Instant.fromEpochMilliseconds(item.takenAtMillis).toLocalDateTime(zone)
    val today = Timeline.localDate(kotlinx.datetime.Clock.System.now().toEpochMilliseconds(), zone)
    val hour = if (time.hour % 12 == 0) 12 else time.hour % 12
    val suffix = if (time.hour < 12) "am" else "pm"
    return "${Timeline.dayLabel(time.date, today)}, $hour:${time.minute.toString().padStart(2, '0')} $suffix"
}

private fun megabytes(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    return if (mb >= 10) "${mb.toInt()} MB" else "${(mb * 10).toInt() / 10.0} MB"
}
