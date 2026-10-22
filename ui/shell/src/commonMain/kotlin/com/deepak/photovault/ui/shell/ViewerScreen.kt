package com.deepak.photovault.ui.shell

import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.Place
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalDensity
import com.deepak.photovault.core.media.DetailsFormat
import com.deepak.photovault.core.media.MediaDetails
import com.deepak.photovault.core.media.MediaDetailsSource
import com.deepak.photovault.designsystem.LocalGlassSettings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.roundToInt
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
 * Full-screen viewer: swipe between photos, pinch or double-tap to zoom, tap to fade the filmstrip and actions
 * in or out, play videos. Photos are shown on black, never tinted. There is no top bar: back is the system back gesture.
 *
 * Swipe up (or tap Details) to slide the photo up and reveal the details panel, as in Samsung Gallery
 * (feedback 22 Oct). Swipe down, tap the photo or press back to close it.
 */
@Composable
fun ViewerScreen(viewer: Overlay.Viewer, state: ShellState, gallery: GalleryState, actions: MediaActions) {
    // Photos deleted while the viewer is open drop out of the list.
    val ids = remember(viewer.ids, gallery.items) { viewer.ids.filter { gallery.find(it) != null } }
    if (ids.isEmpty()) {
        LaunchedEffect(Unit) { state.back() }
        return
    }
    val start = ids.indexOf(viewer.startId).coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { ids.size }
    var barsVisible by rememberSaveable { mutableStateOf(true) }
    // A video that has been started on this page (24 Oct, Samsung-style): its controls and frame strip replace the
    // photo strip; a tap shows them, and while it plays they go away again after 3 seconds without a touch.
    var videoStarted by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage) { videoStarted = false }
    val current = gallery.find(ids[pager.currentPage.coerceIn(0, ids.lastIndex)]) ?: return
    val video = remember(current.id) { VideoControls() }
    var touches by remember { mutableStateOf(0) }
    LaunchedEffect(barsVisible, videoStarted, video.playing, touches) {
        if (barsVisible && videoStarted && video.playing) {
            kotlinx.coroutines.delay(VIDEO_BARS_MS)
            barsVisible = false
        }
    }
    val system = WindowInsets.safeDrawing.asPaddingValues()
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalGlassSettings.current.reduceMotion

    // 0 = photo only, 1 = details panel fully open.
    val progress = remember { Animatable(0f) }
    LaunchedEffect(state.detailsOpen) {
        val target = if (state.detailsOpen) 1f else 0f
        if (reduceMotion) progress.snapTo(target) else progress.animateTo(target, tween(DETAILS_MS, easing = FastOutSlowInEasing))
    }
    DisposableEffect(Unit) { onDispose { state.detailsOpen = false } }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        // The panel is as tall as its (compact) details; until measured, assume about 40% of the screen.
        var measuredPanelPx by remember { mutableStateOf(0) }
        val panelPx = if (measuredPanelPx > 0) measuredPanelPx.toFloat() else with(density) { (maxHeight * DETAILS_SHARE).toPx() }
        fun settle(velocity: Float) {
            // Open past a third of the way, or on an upward fling; close otherwise.
            val open = if (kotlin.math.abs(velocity) > 800f) velocity < 0 else progress.value > 0.33f
            state.detailsOpen = open
            scope.launch {
                if (reduceMotion) progress.snapTo(if (open) 1f else 0f)
                else progress.animateTo(if (open) 1f else 0f, tween(DETAILS_MS, easing = FastOutSlowInEasing), initialVelocity = -velocity / panelPx)
            }
        }
        val drag = rememberDraggableState { dy -> scope.launch { progress.snapTo((progress.value - dy / panelPx).coerceIn(0f, 1f)) } }

        // No blur source here: the bars use solid glass over the photo.
        CompositionLocalProvider(LocalHazeState provides null) {
            HorizontalPager(
                state = pager,
                key = { ids[it] },
                beyondViewportPageCount = 1,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // The photo slides up as the panel rises, leaving it fully visible above the panel.
                        translationY = -progress.value * panelPx * 0.62f
                    }
                    // Vertical swipes (not taken by zoom or the pager) open and close the panel.
                    .draggable(
                        state = drag,
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity -> settle(velocity) },
                    ),
            ) { page ->
                val item = gallery.find(ids[page]) ?: return@HorizontalPager
                val onTap = { if (state.detailsOpen) state.detailsOpen = false else barsVisible = !barsVisible }
                if (item.isVideo) {
                    val isCurrent = page == pager.currentPage
                    VideoPage(
                        item,
                        isCurrent = isCurrent,
                        controls = if (isCurrent) video else null,
                        onTap = onTap,
                        onStarted = {
                            if (isCurrent) {
                                videoStarted = true
                                barsVisible = true
                            }
                        },
                    )
                } else {
                    ZoomablePhoto(item, onTap = onTap)
                }
            }

            DetailsPanel(
                item = current,
                source = gallery.detailsSource,
                actions = actions,
                bottomInset = system.calculateBottomPadding(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.7f)
                    .onSizeChanged { measuredPanelPx = it.height }
                    .graphicsLayer {
                        translationY = (1f - progress.value) * panelPx
                        alpha = (progress.value * 3f).coerceIn(0f, 1f)
                    }
                    .draggable(state = drag, orientation = Orientation.Vertical, onDragStopped = { settle(it) }),
            )

            // A tap on the photo fades the filmstrip and actions away and back, as in Samsung Gallery.
            AnimatedVisibility(
                visible = barsVisible && !state.detailsOpen,
                enter = fadeIn(motionSpec()),
                exit = fadeOut(motionSpec()),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        // A soft shade so the strip and buttons stay visible over bright photos.
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                        .padding(top = 24.dp, bottom = system.calculateBottomPadding() + 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (videoStarted && current.isVideo) {
                        VideoBar(video, onTouch = { touches++ })
                        VideoScrubber(current, video, onTouch = { touches++ })
                    } else if (ids.size > 1) {
                        Filmstrip(ids, gallery, pager)
                    }
                    ViewerActions(
                        favourite = gallery.isFavourite(current),
                        onShare = { actions.share(listOf(current)) },
                        onFavourite = { gallery.toggleFavourite(listOf(current)) },
                        onInfo = { state.detailsOpen = true },
                        onDelete = { actions.moveToRecentlyDeleted(listOf(current)) },
                    )
                }
            }
        }
    }
}

private const val DETAILS_MS = 320
private const val DETAILS_SHARE = 0.4f

private val THUMB_W = 42.dp
private val THUMB_H = 56.dp
private val THUMB_GAP = 4.dp

/**
 * Samsung-style filmstrip (feedback 22 Oct): every photo in the album or section, the current one centred and
 * outlined. Scroll it to move through photos, or tap one to jump there. Swiping the photo moves the strip too.
 */
@Composable
private fun Filmstrip(ids: List<Long>, gallery: GalleryState, pager: PagerState) {
    val strip = rememberLazyListState(initialFirstVisibleItemIndex = pager.currentPage.coerceIn(ids.indices))
    val scope = rememberCoroutineScope()
    val pitchPx = with(LocalDensity.current) { (THUMB_W + THUMB_GAP).toPx() }
    val reduceMotion = LocalGlassSettings.current.reduceMotion
    // True from the moment a finger drags the strip until its fling settles: the strip leads, the photo follows.
    var stripLeads by remember { mutableStateOf(false) }
    val dragged by strip.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) stripLeads = true }
    LaunchedEffect(strip) {
        snapshotFlow { strip.isScrollInProgress }.collect { scrolling -> if (!scrolling) stripLeads = false }
    }
    // Strip -> photo: the thumbnail in the middle is the photo shown.
    LaunchedEffect(strip, ids) {
        snapshotFlow { strip.firstVisibleItemIndex to strip.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            if (!stripLeads) return@collect
            val centred = (index + (offset / pitchPx).roundToInt()).coerceIn(ids.indices)
            if (centred != pager.currentPage) pager.scrollToPage(centred)
        }
    }
    // Photo -> strip: swiping the photo slides the strip along.
    LaunchedEffect(pager.currentPage) {
        if (stripLeads) return@LaunchedEffect
        val target = pager.currentPage.coerceIn(ids.indices)
        if (reduceMotion) strip.scrollToItem(target) else strip.animateScrollToItem(target)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Padding on both sides lets the first and last photos sit in the middle too.
        val side = ((maxWidth - THUMB_W) / 2).coerceAtLeast(0.dp)
        LazyRow(
            state = strip,
            contentPadding = PaddingValues(horizontal = side),
            horizontalArrangement = Arrangement.spacedBy(THUMB_GAP),
            flingBehavior = rememberSnapFlingBehavior(strip),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(THUMB_H + 8.dp),
        ) {
            itemsIndexed(ids, key = { _, id -> id }) { index, id ->
                val item = gallery.find(id)
                val isCurrent = index == pager.currentPage
                val shape = RoundedCornerShape(6.dp)
                Box(
                    Modifier
                        .size(THUMB_W, THUMB_H)
                        .graphicsLayer {
                            val grow = if (isCurrent) 1.12f else 1f
                            scaleX = grow
                            scaleY = grow
                            alpha = if (isCurrent) 1f else 0.8f
                        }
                        .clip(shape)
                        .background(Color(0xFF1C1C21))
                        .then(if (isCurrent) Modifier.border(2.dp, Color.White, shape) else Modifier)
                        .clickable(role = Role.Button) {
                            stripLeads = false
                            scope.launch { pager.scrollToPage(index) }
                        }
                        .semantics { contentDescription = if (item != null) "Show ${whenTaken(item)}" else "Show photo" },
                ) {
                    if (item != null) {
                        AsyncImage(
                            model = Thumb(item.uri, item.isVideo, 160),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerActions(
    favourite: Boolean,
    onShare: () -> Unit,
    onFavourite: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.glassChrome(RoundedCornerShape(percent = 50), elevation = 8.dp).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassIconButton(Icons.Rounded.Share, "Share", onClick = onShare)
        GlassIconButton(if (favourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (favourite) "Remove from favourites" else "Add to favourites", onClick = onFavourite)
        GlassIconButton(Icons.Rounded.Info, "Details", onClick = onInfo)
        GlassIconButton(Icons.Rounded.Delete, "Move to Recently deleted", onClick = onDelete)
    }
}

/**
 * The details panel, laid out like Samsung Gallery's but compact (feedback 23 Oct): it fits without scrolling,
 * so a swipe down anywhere on it closes it. When and what, then camera and exposure, then where.
 * Always dark, because it sits below the photo on the viewer's black background.
 */
@Composable
private fun DetailsPanel(
    item: MediaItem,
    source: MediaDetailsSource,
    actions: MediaActions,
    bottomInset: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val details by produceState<MediaDetails?>(null, item.id) { value = source.details(item) }
    val white = Color(0xFFF5F5F7)
    val grey = Color(0xFFB8B8C0)
    val body = MaterialTheme.typography.bodyMedium
    Column(
        modifier
            .background(Color(0xFF0B0B0D))
            .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = bottomInset + 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Drag handle: shows the panel can be swiped down.
        Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x55FFFFFF)))
        Spacer(Modifier.height(10.dp))
        Text(fullDate(item), color = white, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            listOfNotNull(item.displayName, details?.folder).joinToString("  ·  "),
            color = grey,
            style = body,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        val d = details
        Spacer(Modifier.height(10.dp))
        val camera = listOfNotNull(
            d?.camera,
            if (item.isVideo) null else DetailsFormat.lensKind(d?.focalLength35mm),
        ).joinToString("  ·  ")
        if (camera.isNotEmpty()) Text(camera, color = white, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            listOfNotNull(
                DetailsFormat.size(item.sizeBytes),
                if (item.width > 0) "${item.width}x${item.height}" else null,
                if (item.isVideo) Timeline.duration(item.durationMillis) else DetailsFormat.megapixels(item.width, item.height),
            ).joinToString("  |  "),
            color = grey,
            style = body,
        )
        val exposure = if (item.isVideo) {
            listOfNotNull(DetailsFormat.frameRate(d?.frameRate), DetailsFormat.bitrate(d?.bitrate))
        } else {
            listOfNotNull(
                d?.iso?.let { "ISO $it" },
                DetailsFormat.focal(d?.focalLength35mm, d?.focalLengthMm),
                DetailsFormat.aperture(d?.aperture),
                DetailsFormat.exposure(d?.exposureSeconds),
                if (d?.flashFired == true) "Flash" else null,
            )
        }
        if (exposure.isNotEmpty()) Text(exposure.joinToString("  |  "), color = grey, style = body, maxLines = 1, overflow = TextOverflow.Ellipsis)

        Spacer(Modifier.height(12.dp))
        LocationRow(d, item, actions, white, grey)
    }
}

/** One compact row: where the photo was taken, with Open in Maps. */
@Composable
private fun LocationRow(d: MediaDetails?, item: MediaItem, actions: MediaActions, white: Color, grey: Color) {
    val lat = d?.latitude
    val lon = d?.longitude
    val shape = RoundedCornerShape(16.dp)
    when {
        d == null -> Unit
        lat != null && lon != null -> Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Color(0xFF1C1C21))
                .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Place, null, tint = white, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                DetailsFormat.coordinates(lat, lon),
                color = white,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Open in Maps",
                color = Color(0xFF8AB4FF),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) { actions.openMap(lat, lon, item.displayName) }
                    .padding(horizontal = 10.dp, vertical = 12.dp),
            )
        }
        d.locationHidden -> Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Color(0xFF1C1C21))
                .clickable(role = Role.Button) { actions.requestLocationAccess() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Place, null, tint = white, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Show where photos were taken", color = white, style = MaterialTheme.typography.bodySmall)
        }
        else -> Text("No location saved", color = grey, style = MaterialTheme.typography.bodySmall)
    }
}

/** "Saturday, 29 August 2026 · 3:53 pm", as Samsung shows it. */
private fun fullDate(item: MediaItem): String {
    val t = Instant.fromEpochMilliseconds(item.takenAtMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    val hour = if (t.hour % 12 == 0) 12 else t.hour % 12
    val suffix = if (t.hour < 12) "am" else "pm"
    return "${Timeline.dayName(t.dayOfWeek)}, ${t.dayOfMonth} ${Timeline.monthName(t.month)} ${t.year} · $hour:${t.minute.toString().padStart(2, '0')} $suffix"
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

/**
 * A video: its still with a play button; plays in place when tapped and stops when swiped away. The still stays on
 * top until the first picture is drawn, so the screen never flashes (24 Oct).
 */
@Composable
private fun VideoPage(item: MediaItem, isCurrent: Boolean, controls: VideoControls?, onTap: () -> Unit, onStarted: () -> Unit) {
    var started by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(isCurrent) { if (!isCurrent) started = false }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (started && controls != null) {
            VideoPlayer(item.uri, controls, Modifier.fillMaxSize())
            if (!controls.firstFrame) {
                AsyncImage(
                    model = Thumb(item.uri, true, 512),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Taps on the playing video show or hide the strip and buttons.
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) })
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
                    .clickable(role = Role.Button, onClick = {
                        controls?.firstFrame = false
                        controls?.playing = true
                        started = true
                        onStarted()
                    })
                    .semantics { contentDescription = "Play video, ${Timeline.duration(item.durationMillis)}" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(44.dp))
            }
        }
    }
}

/** Play or pause, where it is, and sound on or off: one small row above the frame strip. */
@Composable
private fun VideoBar(video: VideoControls, onTouch: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = if (video.playing) "Pause" else "Play") {
                video.playing = !video.playing
                onTouch()
            },
            contentAlignment = Alignment.Center,
        ) { Icon(if (video.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White) }
        Text(
            "${Timeline.duration(video.positionMs)} / ${Timeline.duration(video.durationMs)}",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp).weight(1f),
        )
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = if (video.muted) "Sound on" else "Mute") {
                video.muted = !video.muted
                onTouch()
            },
            contentAlignment = Alignment.Center,
        ) { Icon(if (video.muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, null, tint = Color.White) }
    }
}

/**
 * The video's frames in a row, as in Samsung Gallery (24 Oct): drag it to move through the video; while playing it
 * scrolls along by itself under the white line in the middle.
 */
@Composable
private fun VideoScrubber(item: MediaItem, video: VideoControls, onTouch: () -> Unit) {
    val duration = (if (video.durationMs > 0) video.durationMs else item.durationMillis).coerceAtLeast(1L)
    val frames = ScrubberMath.frameCount(duration)
    val strip = rememberLazyListState()
    val density = LocalDensity.current
    val framePx = with(density) { FRAME_W.toPx() }
    val totalPx = framePx * frames
    val dragged by strip.interactionSource.collectIsDraggedAsState()
    // The finger leads while it drags (and while the fling settles); the video follows.
    var fingerLeads by remember { mutableStateOf(false) }
    LaunchedEffect(dragged) {
        if (dragged) {
            fingerLeads = true
            onTouch()
        }
    }
    LaunchedEffect(strip) { snapshotFlow { strip.isScrollInProgress }.collect { if (!it) fingerLeads = false } }
    LaunchedEffect(strip, duration) {
        snapshotFlow { strip.firstVisibleItemIndex * framePx + strip.firstVisibleItemScrollOffset }.collect { px ->
            if (fingerLeads) {
                video.seek(ScrubberMath.timeAt(px, totalPx, duration))
                onTouch()
            }
        }
    }
    // Video -> strip.
    LaunchedEffect(strip, duration) {
        snapshotFlow { video.positionMs }.collect { ms ->
            if (!fingerLeads && !strip.isScrollInProgress) {
                val px = ScrubberMath.offsetAt(ms, totalPx, duration)
                val index = (px / framePx).toInt().coerceIn(0, frames - 1)
                strip.scrollToItem(index, (px - index * framePx).toInt())
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(THUMB_H + 8.dp), contentAlignment = Alignment.Center) {
        val side = maxWidth / 2
        LazyRow(
            state = strip,
            contentPadding = PaddingValues(horizontal = side),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(THUMB_H),
        ) {
            items(frames) { i ->
                AsyncImage(
                    model = VideoFrame(item.uri, ScrubberMath.frameTime(i, frames, duration), 160),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(FRAME_W, THUMB_H).background(Color(0xFF1C1C21)),
                )
            }
        }
        // The playhead.
        Box(Modifier.width(3.dp).height(THUMB_H + 8.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
    }
}

/** Pure maths of the frame strip, tested. */
object ScrubberMath {
    /** About one frame a second, between 8 and 60 frames. */
    fun frameCount(durationMs: Long): Int = (durationMs / 1000L).toInt().coerceIn(8, 60)

    fun frameTime(index: Int, frames: Int, durationMs: Long): Long = durationMs * index / frames

    fun timeAt(offsetPx: Float, totalPx: Float, durationMs: Long): Long =
        if (totalPx <= 0f) 0L else ((offsetPx / totalPx).coerceIn(0f, 1f) * durationMs).toLong()

    fun offsetAt(timeMs: Long, totalPx: Float, durationMs: Long): Float =
        if (durationMs <= 0L) 0f else (timeMs.toFloat() / durationMs).coerceIn(0f, 1f) * totalPx
}

private val FRAME_W = 34.dp
private const val VIDEO_BARS_MS = 3000L

private fun whenTaken(item: MediaItem): String {
    val zone = TimeZone.currentSystemDefault()
    val time = Instant.fromEpochMilliseconds(item.takenAtMillis).toLocalDateTime(zone)
    val today = Timeline.localDate(kotlinx.datetime.Clock.System.now().toEpochMilliseconds(), zone)
    val hour = if (time.hour % 12 == 0) 12 else time.hour % 12
    val suffix = if (time.hour < 12) "am" else "pm"
    return "${Timeline.dayLabel(time.date, today)}, $hour:${time.minute.toString().padStart(2, '0')} $suffix"
}

