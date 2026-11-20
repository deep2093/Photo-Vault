package com.deepak.photovault.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.deepak.photovault.core.media.Thumb
import com.deepak.photovault.core.media.Timeline
import com.deepak.photovault.designsystem.LocalGlassSettings
import kotlinx.datetime.TimeZone

/**
 * A memory played like a story (16 Nov, asked for after Google Photos' memories): full screen, one photo or video
 * after the other, with a bar at the top split into one piece per picture.
 *
 * Tap the right side for the next picture and the left for the one before; hold to pause; swipe down or tap the cross
 * to close. After the last picture the next memory starts; after the last memory it closes. A memory counts as played
 * the moment it opens, so it leaves the row on Photos until tomorrow.
 */
@Composable
internal fun StoryScreen(story: Overlay.Story, state: ShellState, gallery: GalleryState, actions: MediaActions) {
    val memories = story.memories
    if (memories.isEmpty()) {
        LaunchedEffect(Unit) { state.back() }
        return
    }
    var at by remember { mutableIntStateOf(story.index.coerceIn(0, memories.lastIndex)) }
    var slide by remember { mutableIntStateOf(0) }
    val memory = memories[at]
    val items = remember(memory, gallery.items) { memory.ids.mapNotNull(gallery::find) }
    LaunchedEffect(at) { state.markMemoryWatched(memory) }

    fun next() {
        if (slide < items.lastIndex) slide++
        else if (at < memories.lastIndex) { at++; slide = 0 }
        else state.back()
    }

    fun previous() {
        if (slide > 0) slide--
        else if (at > 0) { at--; slide = 0 }
    }

    // Every picture of this memory has gone (deleted since): on to the next memory.
    if (items.isEmpty()) {
        LaunchedEffect(at) { next() }
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val item = items[slide.coerceIn(0, items.lastIndex)]
    val still = LocalGlassSettings.current.reduceMotion
    var held by remember { mutableStateOf(false) }
    var sound by remember { mutableStateOf(false) }
    // A page opened on top (the photo itself) pauses the story underneath.
    val onTop = state.overlays.lastOrNull() is Overlay.Story
    val paused = held || !onTop
    val video = remember(item.id) { VideoControls().apply { muted = true } }
    LaunchedEffect(paused, item.id) { video.playing = !paused }
    LaunchedEffect(sound, item.id) { video.muted = !sound }

    // How long this picture stays: photos a few seconds, videos their own length (within limits).
    val length = if (item.isVideo) {
        (if (item.durationMillis > 0) item.durationMillis else video.durationMs).coerceIn(MIN_VIDEO_MS, MAX_VIDEO_MS).toInt()
    } else {
        PHOTO_MS
    }
    val progress = remember { Animatable(0f) }
    var timing by remember { mutableStateOf(-1 to -1) }
    LaunchedEffect(at, slide, paused, length) {
        if (timing != (at to slide)) {
            progress.snapTo(0f)
            timing = at to slide
        }
        if (paused) return@LaunchedEffect
        val left = ((1f - progress.value) * length).toInt().coerceAtLeast(1)
        progress.animateTo(1f, tween(left, easing = LinearEasing))
        next()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        held = true
                        tryAwaitRelease()
                        held = false
                    },
                    // Given so a long hold is only a pause, not also a tap.
                    onLongPress = { },
                    onTap = { where -> if (where.x < size.width * 0.3f) previous() else next() },
                )
            }
            .pointerInput(Unit) {
                var pulled = 0f
                detectVerticalDragGestures(
                    onDragStart = { pulled = 0f },
                    onDragEnd = { if (pulled > 96.dp.toPx()) state.back() },
                    onVerticalDrag = { _, dy -> pulled += dy },
                )
            },
    ) {
        // The picture, fitted, growing very slowly while it is shown.
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val grow = if (still || item.isVideo) 1f else 1f + DRIFT * progress.value
                scaleX = grow
                scaleY = grow
            },
            contentAlignment = Alignment.Center,
        ) {
            if (item.isVideo) {
                if (!video.firstFrame) {
                    AsyncImage(Thumb(item.uri, true, 512), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                VideoPlayer(item.uri, video, if (video.aspect > 0f) Modifier.aspectRatio(video.aspect) else Modifier.size(1.dp))
            } else {
                val context = LocalPlatformContext.current
                AsyncImage(Thumb(item.uri, false, 512), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                AsyncImage(
                    model = ImageRequest.Builder(context).data(item.uri).crossfade(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // Soft dark fades so the bar, the words and the buttons can be read on any picture.
        Box(Modifier.fillMaxWidth().height(150.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))))
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(260.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))),
        )

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            // The bar: one piece per picture; done ones full, this one filling, the rest empty.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .semantics { contentDescription = "Picture ${slide + 1} of ${items.size}" },
                horizontalArrangement = Arrangement.spacedBy(if (items.size > 20) 2.dp else 4.dp),
            ) {
                items.indices.forEach { i ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(Color.White.copy(alpha = 0.32f))
                            .drawBehind {
                                val filled = if (i < slide) 1f else if (i == slide) progress.value else 0f
                                if (filled > 0f) drawRect(Color.White, size = Size(size.width * filled, size.height))
                            },
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                EditorIcon(Icons.Rounded.Close, "Close") { state.back() }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(memory.title, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(dayOf(item.takenAtMillis), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
            Spacer(Modifier.weight(1f))
            // The memory's name, large, on its first picture only.
            AnimatedVisibility(visible = slide == 0, enter = fadeIn(com.deepak.photovault.designsystem.motionSpec(300)), exit = fadeOut(com.deepak.photovault.designsystem.motionSpec(300))) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (memory.month > 0) memory.title else memory.ago,
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        when {
                            memory.month > 0 -> memory.ago
                            memory.sameDay -> "On this day in ${memory.year}"
                            else -> "This week in ${memory.year}"
                        },
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.isVideo) {
                    EditorIcon(
                        if (sound) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                        if (sound) "Turn the sound off" else "Turn the sound on",
                    ) { sound = !sound }
                }
                val favourite = gallery.isFavourite(item)
                EditorIcon(
                    if (favourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    if (favourite) "Remove from favourites" else "Add to favourites",
                ) { gallery.toggleFavourite(listOf(item)) }
                EditorIcon(Icons.Rounded.Share, "Share this one") { actions.share(listOf(item)) }
                // The photo itself, in the viewer, with everything the viewer can do; the story waits underneath.
                EditorIcon(Icons.Rounded.OpenInFull, "Open this one") { state.open(Overlay.Viewer(memory.ids, item.id)) }
            }
        }
    }
}

/** "8 November 2023". */
private fun dayOf(millis: Long): String {
    val date = Timeline.localDate(millis, TimeZone.currentSystemDefault())
    return "${date.dayOfMonth} ${Timeline.monthName(date.month)} ${date.year}"
}

private const val PHOTO_MS = 4500
private const val MIN_VIDEO_MS = 3000L
private const val MAX_VIDEO_MS = 15000L

/** How much a photo grows while it is shown (a slow drift, not a zoom). */
private const val DRIFT = 0.06f
