package com.deepak.photovault.ui.shell

import androidx.compose.ui.semantics.heading
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Download
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material.icons.rounded.Place
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalDensity
import com.deepak.photovault.core.media.DetailsFormat
import com.deepak.photovault.core.media.MediaDetails
import com.deepak.photovault.core.media.MediaDetailsSource
import com.deepak.photovault.designsystem.LocalGlassSettings
import com.deepak.photovault.designsystem.lensGlassActive
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import com.deepak.photovault.core.backup.isDriveOnly
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Groups
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
import androidx.compose.ui.draw.clipToBounds
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
import com.deepak.photovault.designsystem.solidGlass
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.deepak.photovault.designsystem.LocalHazeState
import com.deepak.photovault.designsystem.glassChrome
import com.deepak.photovault.designsystem.heartPop
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
 * (feedback 22 Oct). Swipe down or press back to close it: since 20 Nov (Phase 12) the photo follows the finger down,
 * shrinking, with the grid showing through behind it, and when let go it shrinks back into its own tile.
 *
 * On a foldable half open like a small laptop (tabletop), the photo or video sits on the upper half and the buttons on
 * the lower half (20 Nov).
 */
@Composable
fun ViewerScreen(viewer: Overlay.Viewer, state: ShellState, gallery: GalleryState, actions: MediaActions) {
    // 13 Nov: the viewer is always dark, so its bars, menu and popups use a dark theme's glass in every theme
    // (a light theme drew a pale bar over the black page), matching the Living photo page.
    // The chosen theme's night version (18 Nov), so the viewer matches the theme even in the day.
    val chrome = state.viewerTheme
    androidx.compose.runtime.CompositionLocalProvider(LocalGlassTheme provides chrome) {
        ViewerBody(viewer, state, gallery, actions)
    }
}

@Composable
private fun ViewerBody(viewer: Overlay.Viewer, state: ShellState, gallery: GalleryState, actions: MediaActions) {
    // Photos deleted while the viewer is open drop out of the list.
    // Recently deleted photos aren't in the library, so they are looked up among the deleted ones.
    val trashedById = remember(gallery.trashed) { gallery.trashed.associate { it.item.id to it.item } }
    val find: (Long) -> MediaItem? = if (viewer.trash) ({ trashedById[it] }) else gallery::find
    // Copies saved from the editor while the viewer was open go just before their original (17 Nov).
    val copies = if (viewer.trash) emptyMap() else state.savedCopies.toMap()
    val ids = remember(viewer.ids, gallery.items, trashedById, copies) {
        val listed = viewer.ids.toHashSet()
        viewer.ids.flatMap { id -> copies[id].orEmpty().filter { it !in listed } + id }.distinct().filter { find(it) != null }
    }
    val flight = LocalDeleteFlight.current
    val prompt = LocalDeletePrompt.current
    val photoSpot = remember { ScreenSpot() }
    val bin = remember { ScreenSpot() }
    val secureMover = rememberSecuredMover(state, gallery, actions)
    secureMover.Dialogs()
    val lockSpot = remember { ScreenSpot() }
    if (ids.isEmpty()) {
        LaunchedEffect(Unit) { state.back() }
        return
    }
    val start = ids.indexOf(viewer.startId).coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { ids.size }
    // A copy just saved: shown as soon as the library has it.
    LaunchedEffect(state.showCopy, ids) {
        val target = state.showCopy ?: return@LaunchedEffect
        val at = ids.indexOf(target)
        if (at >= 0) {
            state.showCopy = null
            if (state.reduceMotionNow) pager.scrollToPage(at) else pager.animateScrollToPage(at)
        }
    }
    var barsVisible by rememberSaveable { mutableStateOf(true) }
    // A video that has been started on this page (24 Oct, Samsung-style): its controls and frame strip replace the
    // photo strip; a tap shows them, and while it plays they go away again after 3 seconds without a touch.
    var videoStarted by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage) { videoStarted = false }
    val current = find(ids[pager.currentPage.coerceIn(0, ids.lastIndex)]) ?: return
    // For the closing animation (16 Nov): which photo to shrink back into its tile.
    androidx.compose.runtime.SideEffect { state.viewerShowing = current.id }
    val video = remember(current.id) { VideoControls() }
    // 16 Nov: a page opened over the viewer (the video editor) pauses the video here, so two never play at once.
    val covered = state.overlays.lastOrNull() !is Overlay.Viewer
    LaunchedEffect(covered) { if (covered) video.playing = false }
    // HDR photos (17 Nov): the screen goes into HDR only while one is showing here, and back when it isn't.
    // 18 Nov (Deepak: "HDR video looked dark; Skywing shows it right"): an HDR video playing here too, as Skywing does.
    val hdrPhotos = remember { androidx.compose.runtime.mutableStateMapOf<Long, Boolean>() }
    val wantHdr = !covered && !LocalPageLeaving.current &&
        (if (current.isVideo) videoStarted && video.hdr else hdrPhotos[current.id] == true)
    LaunchedEffect(wantHdr) { actions.showHdr(wantHdr) }
    DisposableEffect(Unit) { onDispose { actions.showHdr(false) } }
    // The frame strip opens only when the playing video's own thumbnail in the photo strip is tapped (24 Oct).
    var scrubbing by remember(current.id) { mutableStateOf(false) }
    if (videoStarted && current.isVideo) {
        val duration = if (current.durationMillis > 0) current.durationMillis else video.durationMs
        if (duration > 0) PreloadVideoFrames(current.uri, remember(current.id, duration) { ScrubberMath.frameTimes(duration) }, FRAME_PX)
    }
    var touches by remember { mutableStateOf(0) }
    LaunchedEffect(barsVisible, videoStarted, video.playing, touches, state.tabletopFold) {
        // Half folded (tabletop) the buttons have the lower half to themselves, so they stay (20 Nov).
        if (barsVisible && videoStarted && video.playing && state.tabletopFold == null) {
            kotlinx.coroutines.delay(VIDEO_BARS_MS)
            barsVisible = false
        }
    }
    // Full screen (23 Oct): the status and navigation bars go away with the viewer's own buttons and come back
    // with them, like Samsung Gallery. Bar room is remembered, so the buttons don't jump when they come back.
    SystemBarsHidden(hidden = !barsVisible && !state.detailsOpen)
    val liveSystem = WindowInsets.systemBars.asPaddingValues()
    var system by remember { mutableStateOf(liveSystem) }
    if (liveSystem.calculateTopPadding() >= system.calculateTopPadding() && liveSystem.calculateBottomPadding() >= system.calculateBottomPadding()) system = liveSystem
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalGlassSettings.current.reduceMotion
    // Lens glass on the toolbar too (25 Oct): the photo is recorded so the bar can look through it.
    val lensOn = lensGlassActive()
    val liquidGlass = LocalGlassTheme.current.isLiquidGlass
    // HDR video plays on a separate Android surface outside the Compose backdrop.
    val canSampleBackdrop = !liquidGlass || !videoStarted || !video.hdr
    val lens = if (canSampleBackdrop) rememberLensGlass() else null

    // 0 = photo only, 1 = details panel fully open.
    val progress = remember { Animatable(0f) }
    LaunchedEffect(state.detailsOpen) {
        val target = if (state.detailsOpen) 1f else 0f
        if (reduceMotion) progress.snapTo(target) else progress.animateTo(target, tween(DETAILS_MS, easing = FastOutSlowInEasing))
    }
    DisposableEffect(Unit) { onDispose { state.detailsOpen = false } }

    // Swipe down to close (20 Nov): how far the photo is pulled down, in pixels, while a pull is under way.
    val dismiss = remember { Animatable(0f) }
    var dismissing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        state.viewerDrag = 0f
        state.viewerLetGo = null
        onDispose { state.viewerDrag = 0f }
    }
    // Tabletop (20 Nov): where this page starts on the screen, to know how much of it is above the fold.
    var pageTop by remember { mutableStateOf(0f) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { pageTop = it.positionInRoot().y }
            // The black thins out as the photo is pulled down, so the photos behind show through.
            .drawBehind { drawRect(Color.Black, alpha = viewerBackdrop(state.viewerDrag)) },
    ) {
        val density = LocalDensity.current
        val heightPx = with(density) { maxHeight.toPx() }
        // Half folded: the photo has the part above the fold (null when the phone is flat or not a foldable).
        val areaPx = state.tabletopFold?.let { fold -> (fold.top - pageTop).takeIf { it > heightPx * 0.25f && it < heightPx } }
        // The panel is as tall as its (compact) details; until measured, assume about 40% of the screen.
        var measuredPanelPx by remember { mutableStateOf(0) }
        val panelPx = if (measuredPanelPx > 0) measuredPanelPx.toFloat() else with(density) { (maxHeight * DETAILS_SHARE).toPx() }
        /** Let go far enough down: the photo shrinks back into its tile from where the finger left it. */
        fun closeByDrag() {
            val f = (dismiss.value / heightPx).coerceIn(0f, 1f)
            state.viewerLetGo = ViewerLetGo(scale = 1f - DISMISS_SHRINK * f, shiftY = dismiss.value)
            state.back()
        }
        fun settle(velocity: Float) {
            if (dismissing) {
                if (dismiss.value > heightPx * DISMISS_SHARE || velocity > DISMISS_FLING) {
                    closeByDrag()
                } else {
                    scope.launch {
                        if (reduceMotion) dismiss.snapTo(0f)
                        else dismiss.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 500f)) { state.viewerDrag = (value / heightPx).coerceIn(0f, 1f) }
                        state.viewerDrag = 0f
                        dismissing = false
                    }
                }
                return
            }
            // Open past a third of the way, or on an upward fling; close otherwise.
            val open = if (kotlin.math.abs(velocity) > 800f) velocity < 0 else progress.value > 0.33f
            state.detailsOpen = open
            scope.launch {
                if (reduceMotion) progress.snapTo(if (open) 1f else 0f)
                else progress.animateTo(if (open) 1f else 0f, tween(DETAILS_MS, easing = FastOutSlowInEasing), initialVelocity = -velocity / panelPx)
            }
        }
        val drag = rememberDraggableState { dy ->
            if (dismissing || (dy > 0f && progress.value == 0f && !state.detailsOpen)) {
                if (!dismissing) {
                    dismissing = true
                    // The grid under the viewer brings this photo into view now, while the black still hides it.
                    val depthBelow = state.overlays.indexOfLast { it is Overlay.Viewer }
                    if (depthBelow >= 0) scope.launch { state.revealTile(current.id, depthBelow) }
                }
                scope.launch {
                    dismiss.snapTo((dismiss.value + dy).coerceAtLeast(0f))
                    state.viewerDrag = (dismiss.value / heightPx).coerceIn(0f, 1f)
                }
            } else {
                scope.launch { progress.snapTo((progress.value - dy / panelPx).coerceIn(0f, 1f)) }
            }
        }

        val bottomShade = animateFloatAsState(if (liquidGlass && barsVisible && !state.detailsOpen) 1f else 0f, motionSpec(200), label = "viewer shade")
        // Liquid Glass never blurs (21 Nov): nothing here is recorded for a blur; the lens looks through instead.
        CompositionLocalProvider(LocalHazeState provides null) {
            HorizontalPager(
                state = pager,
                key = { ids[it] },
                beyondViewportPageCount = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (areaPx != null) Modifier.height(with(density) { areaPx.toDp() }) else Modifier.fillMaxHeight())
                    // No black painted behind the photo here (unlike the Secured viewer): it would cover the grid that
                    // shows through while a photo is pulled down to close. The Liquid Glass lens fills see-through parts
                    // with its own dark backdrop, so the bars look the same.
                    .then(lens?.source(lensOn) ?: Modifier)
                    // Liquid Glass (21 Nov, Deepak's screenshots: a screenshot's own buttons and words showed through the
                    // clear toolbar, between ours): a soft dark shade at the bottom while the buttons are showing. It is
                    // inside what the lens records, so the toolbar's glass sees the shaded photo and our buttons stand out.
                    .drawWithContent {
                        drawContent()
                        val k = bottomShade.value * (1f - (dismiss.value / heightPx).coerceIn(0f, 1f) * 4f).coerceIn(0f, 1f)
                        if (k > 0.001f) {
                            val h = minOf(VIEWER_SHADE_HEIGHT.toPx(), size.height)
                            drawRect(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.45f to Color.Black.copy(alpha = 0.40f * k),
                                    1f to Color.Black.copy(alpha = 0.72f * k),
                                    startY = size.height - h,
                                    endY = size.height,
                                ),
                                topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h),
                                size = androidx.compose.ui.geometry.Size(size.width, h),
                            )
                        }
                    }
                    .trackSpot(photoSpot)
                    .graphicsLayer {
                        // The photo slides up as the panel rises, leaving it fully visible above the panel; pulled
                        // down to close, it follows the finger and shrinks a little.
                        val pulled = dismiss.value
                        translationY = -progress.value * panelPx * 0.62f + pulled
                        val k = 1f - DISMISS_SHRINK * (pulled / heightPx).coerceIn(0f, 1f)
                        scaleX = k
                        scaleY = k
                    }
                    // Vertical swipes (not taken by zoom or the pager) open and close the panel.
                    .draggable(
                        state = drag,
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity -> settle(velocity) },
                    ),
            ) { page ->
                val item = find(ids[page]) ?: return@HorizontalPager
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
                    ZoomablePhoto(item, isCurrent = page == pager.currentPage, onTap = onTap, onHdr = { hdrPhotos[item.id] = it })
                }
            }

            DetailsPanel(
                item = current,
                source = gallery.detailsSource,
                actions = actions,
                places = state.places,
                tags = state.tags,
                onOpenMap = { lat, lon ->
                    // The small map was tapped (7B-2): Places opens there.
                    state.places.focus = MapStart(lat, lon, 14.0)
                    state.open(Overlay.Places)
                },
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

            // Set as wallpaper from the bottom toolbar (26 Oct); its pop-up asks home, lock or both.
            var wallpaperItem by remember { mutableStateOf<MediaItem?>(null) }
            // More (8 Nov): Set as wallpaper and Move to Secured folder moved here, so the bar fits.
            var moreOpen by remember { mutableStateOf(false) }
            LaunchedEffect(current.id, barsVisible, state.detailsOpen) { moreOpen = false }
            wallpaperItem?.let { photo ->
                WallpaperDialog(
                    onDismiss = { wallpaperItem = null },
                    onPick = { target ->
                        wallpaperItem = null
                        actions.setWallpaper(photo, target) { error -> actions.toast(error ?: "Wallpaper set: ${target.label.lowercase()}.") }
                    },
                )
            }

            // 18 Nov (Deepak): play and pause in the middle of the screen while a video plays, in the same glass as
            // the other buttons here; it comes and goes with them.
            val pulledAway = Modifier.graphicsLayer { alpha = (1f - state.viewerDrag * 4f).coerceIn(0f, 1f) }
            AnimatedVisibility(
                visible = barsVisible && !state.detailsOpen && videoStarted && current.isVideo,
                enter = fadeIn(motionSpec()),
                exit = fadeOut(motionSpec()),
                modifier = (
                    if (areaPx != null) Modifier.align(Alignment.TopCenter).padding(top = with(density) { (areaPx / 2f).toDp() } - CENTRE_BUTTON / 2)
                    else Modifier.align(Alignment.Center)
                    ).then(pulledAway),
            ) {
                CentrePlayButton(
                    playing = video.playing,
                    label = if (video.playing) "Pause" else "Play",
                    onClick = {
                        video.playing = !video.playing
                        touches++
                    },
                )
            }

            // A small HDR badge (Phase 12, 20 Nov) while an HDR photo, or a playing HDR video, is showing.
            val hdrShown = if (current.isVideo) videoStarted && video.hdr else hdrPhotos[current.id] == true
            AnimatedVisibility(
                visible = hdrShown && barsVisible && !state.detailsOpen,
                enter = fadeIn(motionSpec()),
                exit = fadeOut(motionSpec()),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = system.calculateTopPadding() + 12.dp, end = 16.dp).then(pulledAway),
            ) {
                Text(
                    "HDR",
                    color = LocalGlassTheme.current.onGlass,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .solidGlass(RoundedCornerShape(percent = 50))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .semantics { contentDescription = if (current.isVideo) "HDR video" else "HDR photo" },
                )
            }

            // A tap on the photo fades the filmstrip and actions away and back, as in Samsung Gallery.
            AnimatedVisibility(
                visible = barsVisible && !state.detailsOpen,
                enter = fadeIn(motionSpec()),
                exit = fadeOut(motionSpec()),
                modifier = Modifier.align(Alignment.BottomCenter).then(pulledAway),
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
                    val playingHere = videoStarted && current.isVideo
                    // 20 Nov (Deepak, tablet): the time and sound buttons line up with the ends of the video's strip;
                    // phones keep the full-width row they have.
                    val tablet = LocalTabletDevice.current
                    if (playingHere) {
                        if (tablet) VideoBar(video, onTouch = { touches++ }, modifier = Modifier.fillMaxWidth(TABLET_STRIP_SHARE).widthIn(max = TABLET_STRIP_MAX))
                        else VideoBar(video, onTouch = { touches++ })
                    }
                    // The frame strip opens out of the video's thumbnail and folds back into it (23 Oct: it popped).
                    val growIn = motionSpec<androidx.compose.ui.unit.IntSize>(SCRUB_MS)
                    val fadeSpec = motionSpec<Float>(SCRUB_MS)
                    androidx.compose.animation.AnimatedContent(
                        // 13 Nov: a video on its own has no row of photos to tap, so its time strip shows by itself
                        // while it plays (on the tablet with one video there was no way to open it).
                        targetState = playingHere && (scrubbing || ids.size <= 1),
                        transitionSpec = {
                            if (targetState) {
                                (fadeIn(fadeSpec) + androidx.compose.animation.expandHorizontally(growIn, expandFrom = Alignment.CenterHorizontally)) togetherWith fadeOut(fadeSpec)
                            } else {
                                fadeIn(fadeSpec) togetherWith (fadeOut(fadeSpec) + androidx.compose.animation.shrinkHorizontally(growIn, shrinkTowards = Alignment.CenterHorizontally))
                            }
                        },
                        contentAlignment = Alignment.Center,
                        label = "strip",
                    ) { scrub ->
                        if (scrub) {
                            // 20 Nov: on a tablet the whole video fits the strip and the line moves; phones unchanged.
                            if (tablet) FittedVideoTimeline(current, video, onTouch = { touches++ })
                            else VideoScrubber(current, video, onTouch = { touches++ })
                        } else if (ids.size > 1) {
                            Filmstrip(ids, find, pager, onCurrentClick = if (playingHere) ({ scrubbing = true; touches++ }) else null)
                        }
                    }
                    // The photo flies into the bin once Android has asked (23 Oct).
                    val flyCurrent = { photoSpot.bounds?.let { listOf(FlyingPhoto(current.uri, current.isVideo, it, fit = true)) }.orEmpty() }
                    CompositionLocalProvider(com.deepak.photovault.designsystem.LocalLensGlass provides lens) {
                    if (viewer.trash) {
                        TrashActions(
                            onRestore = { actions.restore(listOf(current)) },
                            onDelete = {
                                val flying = flyCurrent()
                                flight.prepare(flying)
                                prompt.deleteForever(actions, listOf(current)) { agreed ->
                                    if (agreed) {
                                        flight.launch(flying, bin.bounds)
                                        gallery.dropNow(setOf(current.id))
                                    }
                                }
                            },
                            binModifier = Modifier.trackSpot(bin),
                        )
                    } else {
                        ViewerActions(
                            favourite = gallery.isFavourite(current),
                            onShare = { actions.share(listOf(current)) },
                            onFamily = if (LocalFamilyAlbum.current) ({ actions.shareToFamilyAlbum(listOf(current)) }) else null,
                            onSave = when {
                                current.isDriveOnly -> ({ actions.saveToPhone(listOf(current)) })
                                gallery.isExternal(current) -> ({ actions.saveOpenedFile(current) })
                                else -> null
                            },
                            // A file opened from another app is not ours to delete or favourite: Share and Save only.
                            onFavourite = if (gallery.isExternal(current)) null else ({ gallery.toggleFavourite(listOf(current)) }),
                            onDelete = if (gallery.isExternal(current)) null else ({
                                val flying = flyCurrent()
                                flight.prepare(flying)
                                prompt.trash(actions, listOf(current)) { agreed ->
                                    if (agreed) {
                                        flight.launch(flying, bin.bounds)
                                        gallery.dropNow(setOf(current.id))
                                    }
                                }
                            }),
                            binModifier = Modifier.trackSpot(bin),
                            heartKey = current.id,
                            // Make it move is now Edit > Motion (15 Nov).
                            onMore = if (current.isVideo && (gallery.isExternal(current) || current.isDriveOnly)) null else ({ moreOpen = !moreOpen }),
                            // The photo flies to the More button when it goes to the Secured folder.
                            moreModifier = Modifier.trackSpot(lockSpot),
                        )
                    }
                    }
                }
            }
            val moreItem = current
            CompositionLocalProvider(
                com.deepak.photovault.designsystem.LocalLensGlass provides if (liquidGlass) lens else null,
            ) {
                GlassMenuLayer(
                    moreOpen && !viewer.trash,
                    onDismiss = { moreOpen = false },
                    solid = true,
                    bottom = system.calculateBottomPadding() + 12.dp + 60.dp + 8.dp,
                ) {
                    // Edit (Phase 11): photos and (16 Nov) videos on this phone; it saves a copy, the original stays.
                    val editable = if (moreItem.isVideo) state.editor.platform.trimSupported else state.editor.platform.supported
                    if (!moreItem.isDriveOnly && !gallery.isExternal(moreItem) && editable) {
                        GlassMenuItem(Icons.Rounded.Tune, "Edit", onClick = { moreOpen = false; state.open(Overlay.Edit(moreItem.id)) })
                    }
                    if (!moreItem.isVideo) {
                        GlassMenuItem(Icons.Rounded.Wallpaper, "Set as wallpaper", onClick = { moreOpen = false; wallpaperItem = moreItem })
                    }
                    if (!gallery.isExternal(moreItem) && !moreItem.isDriveOnly) {
                        GlassMenuItem(Icons.Rounded.Lock, "Move to Secured folder", onClick = {
                            moreOpen = false
                            val fly = { photoSpot.bounds?.let { listOf(FlyingPhoto(moreItem.uri, moreItem.isVideo, it, fit = true)) }.orEmpty() }
                            secureMover.request(listOf(moreItem), fly = fly, aim = { lockSpot.bounds })
                        })
                    }
                }
            }
        }
    }
}

private const val DETAILS_MS = 320
private const val SCRUB_MS = 260
private const val DETAILS_SHARE = 0.4f

// Swipe down to close (Phase 12, 20 Nov), like Google Photos.
/** How much smaller the photo gets when pulled all the way down. */
private const val DISMISS_SHRINK = 0.35f
/** Let go past this share of the screen's height (or flicked down) and the photo closes. */
private const val DISMISS_SHARE = 0.12f
/** A flick down this fast (pixels a second) closes it from any distance. */
private const val DISMISS_FLING = 1200f
/** How fast the black thins out as the photo is pulled down (gone at 40 % of the screen). */
private const val BACKDROP_FADE = 2.5f

/** How dark the viewer's black is when its photo is pulled [drag] of the screen's height down (1 = fully black). */
internal fun viewerBackdrop(drag: Float): Float = (1f - drag * BACKDROP_FADE).coerceIn(0f, 1f)

/** Where the finger left a photo pulled down to close it: shrunk to [scale] and moved [shiftY] pixels down. */
internal data class ViewerLetGo(val scale: Float, val shiftY: Float)

private val THUMB_W = 42.dp
private val THUMB_H = 56.dp
private val THUMB_GAP = 4.dp

/**
 * Samsung-style filmstrip (feedback 22 Oct): every photo in the album or section, the current one centred and
 * outlined. Scroll it to move through photos, or tap one to jump there. Swiping the photo moves the strip too.
 */
@Composable
internal fun FilmstripCore(ids: List<Any>, pager: PagerState, onCurrentClick: (() -> Unit)?, label: (Int) -> String, thumb: @Composable (Int) -> Unit) {
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
                        .background(com.deepak.photovault.designsystem.Stage.card)
                        .then(if (isCurrent) Modifier.border(2.dp, Color.White, shape) else Modifier)
                        .clickable(role = Role.Button) {
                            stripLeads = false
                            if (isCurrent && onCurrentClick != null) onCurrentClick() else scope.launch { pager.scrollToPage(index) }
                        }
                        .semantics { contentDescription = label(index) },
                ) {
                    thumb(index)
                }
            }
        }
    }
}

@Composable
private fun Filmstrip(ids: List<Long>, find: (Long) -> MediaItem?, pager: PagerState, onCurrentClick: (() -> Unit)? = null) {
    FilmstripCore(
        ids, pager, onCurrentClick,
        label = { index -> find(ids[index])?.let { "Show ${whenTaken(it)}" } ?: "Show photo" },
    ) { index ->
        find(ids[index])?.let { item ->
            AsyncImage(
                model = Thumb(item.uri, item.isVideo, 160, item.sizeBytes),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Where should the wallpaper go? Home screen, lock screen or both. */
@Composable
private fun WallpaperDialog(onDismiss: () -> Unit, onPick: (WallpaperTarget) -> Unit) {
    com.deepak.photovault.designsystem.GlassDialog(
        title = "Set as wallpaper",
        onDismiss = onDismiss,
        buttons = { com.deepak.photovault.designsystem.GlassButton("Cancel", onClick = onDismiss) },
    ) {
        Text("Where do you want this photo?", color = LocalGlassTheme.current.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
        WallpaperTarget.entries.forEach { target ->
            com.deepak.photovault.designsystem.GlassButton(target.label, onClick = { onPick(target) }, modifier = Modifier.fillMaxWidth(), centered = true)
        }
    }
}

/** How far up from the bottom the viewer's soft shade reaches: past the toolbar and the row of small photos. */
private val VIEWER_SHADE_HEIGHT = 230.dp

@Composable
private fun ViewerActions(
    favourite: Boolean,
    onShare: () -> Unit,
    onFavourite: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
    binModifier: Modifier = Modifier,
    onFamily: (() -> Unit)? = null,
    onSave: (() -> Unit)? = null,
    onLiving: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    moreModifier: Modifier = Modifier,
    /** The photo the heart belongs to: it pops when this photo becomes a favourite, not when another photo comes in. */
    heartKey: Any? = null,
) {
    // One pill in every theme (21 Nov, Deepak: "make a single toolbar"; Clear Glass had two groups for a day, as iOS).
    // Same size buttons with the same gap (8 Nov: seven didn't fit and the last was cut off); the rarer ones are in More.
    Row(
        modifier.glassChrome(RoundedCornerShape(percent = 50), elevation = 8.dp).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(Icons.Rounded.Share, "Share", onClick = onShare)
        if (onSave != null) GlassIconButton(Icons.Rounded.Download, "Save to this phone", onClick = onSave)
        if (onFamily != null) GlassIconButton(Icons.Rounded.Groups, "Share to Family Album", onClick = onFamily)
        if (onFavourite != null) {
            GlassIconButton(
                if (favourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (favourite) "Remove from favourites" else "Add to favourites",
                onClick = onFavourite,
                // Extra animations (21 Nov): the heart springs up with a ring and sparks when you favourite a photo.
                modifier = Modifier.heartPop(favourite, heartKey, LocalGlassTheme.current.onGlass),
            )
        }
        if (onLiving != null) GlassIconButton(Icons.Rounded.AutoAwesome, "Make it move", onClick = onLiving)
        if (onDelete != null) GlassIconButton(Icons.Rounded.Delete, "Move to Recently deleted", onClick = onDelete, modifier = binModifier)
        // More (8 Nov): Set as wallpaper and Move to Secured folder.
        if (onMore != null) GlassIconButton(Icons.Rounded.MoreVert, "More", onClick = onMore, modifier = moreModifier)
    }
}

/** A photo opened from Recently deleted: put it back, or delete it for good (Android asks either way). */
@Composable
private fun TrashActions(onRestore: () -> Unit, onDelete: () -> Unit, binModifier: Modifier = Modifier) {
    Row(
        Modifier.glassChrome(RoundedCornerShape(percent = 50), elevation = 8.dp).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassIconButton(Icons.Rounded.Restore, "Restore", onClick = onRestore)
        GlassIconButton(Icons.Rounded.DeleteForever, "Delete for good", onClick = onDelete, modifier = binModifier)
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
    places: PlacesState = PlacesState(),
    tags: TagsState? = null,
    onOpenMap: (Double, Double) -> Unit = { _, _ -> },
) {
    val details by produceState<MediaDetails?>(null, item.id) { value = source.details(item) }
    val white = com.deepak.photovault.designsystem.Stage.text
    val grey = com.deepak.photovault.designsystem.Stage.textSecondary
    val body = MaterialTheme.typography.bodyMedium
    Column(
        modifier
            .background(com.deepak.photovault.designsystem.Stage.panel)
            .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = bottomInset + 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Drag handle: shows the panel can be swiped down.
        Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(com.deepak.photovault.designsystem.Stage.handle))
        Spacer(Modifier.height(10.dp))
        Text(fullDate(item), color = white, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            listOfNotNull(item.displayName, details?.folder).joinToString("  ·  "),
            color = grey,
            style = body,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // Your tags (7C-2), with Add tag.
        if (tags != null) {
            PanelLabel("Tags", grey)
            DetailsTags(item, tags, white, grey)
        }

        val d = details
        if (item.isDriveOnly) {
            // 25 Oct: say plainly that this one lives on the family drive and not on the phone.
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(com.deepak.photovault.designsystem.Stage.card).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.Cloud, null, tint = white)
                Column {
                    Text("On the family drive", color = white, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Not saved on this phone. Opening it fetches it from the drive.", color = grey, style = body)
                    com.deepak.photovault.core.backup.DriveTimeline.pathOf(item.uri)?.let {
                        Text(it.substringBeforeLast('/'), color = grey, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        PanelLabel("Details", grey)
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
        // HDR, Log, colour depth and space, codec (17 Nov); for a photo just "HDR" when it is one.
        d?.colour?.let { c -> Text(if (item.isVideo) c.line else "HDR photo", color = grey, style = body, maxLines = 1, overflow = TextOverflow.Ellipsis) }

        PanelLabel("Place", grey)
        val mapLat = d?.latitude
        val mapLon = d?.longitude
        if (mapLat != null && mapLon != null && places.platform.supported) {
            // A small map with the spot (7B-2); tap it for the Places map.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(16.dp)),
            ) {
                androidx.compose.runtime.key(item.id) {
                    PhotoMap(
                        modifier = Modifier.fillMaxSize(),
                        dark = false,
                        start = MapStart(mapLat, mapLon, 13.0),
                        interactive = false,
                        controller = null,
                        onViewport = { _, _ -> },
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(16.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color.White)
                        .padding(3.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(com.deepak.photovault.designsystem.Stage.pin),
                )
                Box(Modifier.fillMaxSize().clickable(role = Role.Button) { onOpenMap(mapLat, mapLon) })
            }
            Spacer(Modifier.height(8.dp))
        }
        LocationRow(d, item, actions, white, grey, places)
    }
}

/** A small heading inside the details panel (13 Nov), so tags, details and place read as three clear parts. */
@Composable
private fun PanelLabel(text: String, grey: Color) {
    Text(
        text.uppercase(),
        color = grey.copy(alpha = 0.75f),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Sp),
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** One compact row: where the photo was taken, with Open in Maps. */
@Composable
private fun LocationRow(d: MediaDetails?, item: MediaItem, actions: MediaActions, white: Color, grey: Color, places: PlacesState) {
    val lat = d?.latitude
    val lon = d?.longitude
    val shape = RoundedCornerShape(16.dp)
    // The town or city (7B-1), and the street address only when asked for (the phone's own lookup), remembered.
    val place = places.photos[item.id]?.place
    var address by remember(item.id) { mutableStateOf<String?>(null) }
    var asked by remember(item.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { if (!places.loaded) places.refresh() }
    // The exact address shows by itself (7 Nov), from the phone's address lookup, remembered once found.
    LaunchedEffect(item.id, lat, lon) {
        if (lat != null && lon != null && places.platform.supported) {
            asked = true
            address = places.platform.address(lat, lon)
        }
    }
    when {
        d == null -> Unit
        lat != null && lon != null -> Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(com.deepak.photovault.designsystem.Stage.card)
                .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Place, null, tint = white, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                if (place != null) {
                    Text(place.label, color = white, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    DetailsFormat.coordinates(lat, lon),
                    color = if (place != null) grey else white,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (address != null) {
                    Text(address.orEmpty(), color = white, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Text(
                "Open in Maps",
                color = com.deepak.photovault.designsystem.Stage.link,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = com.deepak.photovault.designsystem.hapticClick { actions.openMap(lat, lon, item.displayName) })
                    .padding(horizontal = 10.dp, vertical = 12.dp),
            )
        }
        d.locationHidden -> Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(com.deepak.photovault.designsystem.Stage.card)
                .clickable(role = Role.Button, onClick = com.deepak.photovault.designsystem.hapticClick { actions.requestLocationAccess() })
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
    return "${Timeline.dayName(t.dayOfWeek)}, ${t.dayOfMonth} ${Timeline.monthName(t.month)} ${t.year} · ${Timeline.clockTime(t.hour, t.minute)}"
}

/**
 * The full photo the viewer shows (17 Nov). One request, the same everywhere, so the copy started the moment a tile
 * is tapped ([preload], while the photo flies open) is the very one the viewer then finds ready, instead of starting
 * to decode only once the viewer appears (about half a second, Deepak). Sized to the screen; a short fade over the
 * small picture.
 */
internal object FullPhoto {
    /** The screen's size in pixels, set by the page host; 0 until known (then the photo's own box sizes it). */
    var width = 0
    var height = 0

    fun request(context: coil3.PlatformContext, uri: String, version: Long = 0): ImageRequest =
        ImageRequest.Builder(context).data(uri).crossfade(FADE_MS)
            .apply { if (width > 0 && height > 0) size(width, height) }
            .apply { if (version != 0L) memoryCacheKeyExtra("v", version.toString()) }
            .scale(coil3.size.Scale.FIT)
            .build()

    fun preload(context: coil3.PlatformContext, item: MediaItem) {
        if (item.isVideo || width <= 0 || height <= 0) return
        coil3.SingletonImageLoader.get(context).enqueue(request(context, item.uri, item.sizeBytes))
    }

    private const val FADE_MS = 110
}

/** Pinch to zoom (up to 6x), drag while zoomed, double-tap to zoom in or out, tap to toggle bars. */
@Composable
private fun ZoomablePhoto(item: MediaItem, isCurrent: Boolean, onTap: () -> Unit, onHdr: (Boolean) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val scale = remember(item.id) { Animatable(1f) }
    val offset = remember(item.id) { Animatable(Offset.Zero, Offset.VectorConverter) }
    val context = LocalPlatformContext.current
    // 20 Nov: with reduce motion a double tap zooms at once, and a photo let go below its size is put back at once.
    val still by androidx.compose.runtime.rememberUpdatedState(LocalGlassSettings.current.reduceMotion)
    // A photo that is no longer the one shown goes back to normal size (7 Nov: picking another photo from the strip
    // while zoomed showed it under the still-zoomed one).
    LaunchedEffect(isCurrent) {
        if (!isCurrent && (scale.value != 1f || offset.value != Offset.Zero)) {
            scale.snapTo(1f)
            offset.snapTo(Offset.Zero)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            // A zoomed photo never draws outside its own page, over the photos next to it.
            .clipToBounds()
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        scope.launch {
                            if (scale.value > 1.05f) {
                                if (still) {
                                    offset.snapTo(Offset.Zero)
                                    scale.snapTo(1f)
                                } else {
                                    launch { offset.animateTo(Offset.Zero) }
                                    scale.animateTo(1f)
                                }
                            } else {
                                // Zoom towards where the finger tapped.
                                val target = 2.5f
                                val centre = Offset(size.width / 2f, size.height / 2f)
                                val towards = clampOffset((centre - tap) * (target - 1f), target, size.width, size.height)
                                if (still) {
                                    offset.snapTo(towards)
                                    scale.snapTo(target)
                                } else {
                                    launch { offset.animateTo(towards) }
                                    scale.animateTo(target)
                                }
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
                            if (still) {
                                offset.snapTo(Offset.Zero)
                                scale.snapTo(1f)
                            } else {
                                launch { offset.animateTo(Offset.Zero) }
                                scale.animateTo(1f)
                            }
                        }
                    }
                }
            }
            .semantics { contentDescription = "Photo, ${whenTaken(item)}" },
    ) {
        // The cached thumbnail shows instantly; the full photo fades in over it. Both zoom together (30 Oct: the
        // thumbnail stayed small behind the zoomed photo and showed as a second copy).
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    translationX = offset.value.x
                    translationY = offset.value.y
                },
        ) {
            AsyncImage(model = Thumb(item.uri, false, 512, item.sizeBytes), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            AsyncImage(
                // 17 Nov: the size is part of the request, so a photo saved over (same address, new pixels) loads again.
                model = remember(item.uri, item.sizeBytes) { FullPhoto.request(context, item.uri, item.sizeBytes) },
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                // Whether the loaded photo carries an HDR brightness map; the page turns HDR on for it.
                onState = { st -> if (st is coil3.compose.AsyncImagePainter.State.Success) onHdr(st.result.image.hasHdrMap()) },
            )
        }
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
            // The video and its still picture share one box, shaped like the video, so the picture can't jump
            // when the video takes over (23 Oct). The still fades out once the first frame is on screen.
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val aspect = controls.aspect
                val screen = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else 1f
                val frame = if (aspect > 0f) Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = aspect < screen) else Modifier.fillMaxSize()
                val stillAlpha by animateFloatAsState(if (controls.firstFrame && aspect > 0f) 0f else 1f, motionSpec(150), label = "still")
                Box(frame) {
                    VideoPlayer(item.uri, controls, Modifier.fillMaxSize().graphicsLayer { alpha = if (aspect > 0f) 1f else 0f })
                    if (stillAlpha > 0f) {
                        AsyncImage(
                            model = Thumb(item.uri, true, 512),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = stillAlpha },
                        )
                    }
                }
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
            CentrePlayButton(
                playing = false,
                label = "Play video, ${Timeline.duration(item.durationMillis)}",
                onClick = {
                    controls?.firstFrame = false
                    controls?.playing = true
                    started = true
                    onStarted()
                },
            )
        }
    }
}

/**
 * The big play / pause button in the middle of a video (18 Nov): the same glass, icon colour and depth as the
 * viewer's other buttons, so it matches them in every theme.
 */
@Composable
private fun CentrePlayButton(playing: Boolean, label: String, onClick: () -> Unit) {
    val theme = com.deepak.photovault.designsystem.LocalGlassTheme.current
    val haptics = com.deepak.photovault.designsystem.rememberHaptics()
    Box(
        Modifier
            .size(CENTRE_BUTTON)
            .glassChrome(CircleShape, elevation = 8.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = {
                haptics.tap()
                onClick()
            })
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        com.deepak.photovault.designsystem.DepthIcon(
            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            null,
            theme.onGlass,
            Modifier.size(36.dp),
        )
    }
}

private val CENTRE_BUTTON = 68.dp

/** Play or pause, where it is, and sound on or off: one small row above the frame strip. */
@Composable
internal fun VideoBar(video: VideoControls, onTouch: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            // 48dp (18 Nov, was 40): the size a thumb can hit without looking.
            Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button) {
                video.playing = !video.playing
                onTouch()
            },
            contentAlignment = Alignment.Center,
        ) { Icon(if (video.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (video.playing) "Pause" else "Play", tint = Color.White) }
        Text(
            "${Timeline.duration(video.positionMs)} / ${Timeline.duration(video.durationMs)}",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp).weight(1f),
        )
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button) {
                video.muted = !video.muted
                onTouch()
            },
            contentAlignment = Alignment.Center,
        ) { Icon(if (video.muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, if (video.muted) "Sound on" else "Mute", tint = Color.White) }
    }
}

/**
 * The video's frames in a row, as in Samsung Gallery (24 Oct): drag it to move through the video; while playing it
 * scrolls along by itself under the white line in the middle.
 */
@Composable
private fun VideoScrubber(item: MediaItem, video: VideoControls, onTouch: () -> Unit) {
    // The phone's own figure first, so frame times match the ones read ahead while the video played.
    val duration = (if (item.durationMillis > 0) item.durationMillis else video.durationMs).coerceAtLeast(1L)
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
    // Video -> strip. 13 Nov: moved on every screen frame, not only when the player reports its time (a few times a
    // second, which made the strip step along). Between reports the time is carried forward by the clock while the
    // video plays; a report that differs a lot (a seek) is taken at once, a small difference is eased in.
    LaunchedEffect(strip, duration) {
        var seen = -1L
        var base = video.positionMs.toDouble()
        var baseAt = 0L
        var lastPx = -1f
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            val reported = video.positionMs
            val guess = if (video.playing && baseAt != 0L) base + (now - baseAt) / 1_000_000.0 else base
            if (reported != seen) {
                seen = reported
                base = ScrubberMath.follow(guess, reported.toDouble())
                baseAt = now
            } else if (!video.playing) {
                base = reported.toDouble()
                baseAt = now
            }
            if (!fingerLeads && !dragged) {
                val at = (if (video.playing) base + (now - baseAt) / 1_000_000.0 else base).coerceIn(0.0, duration.toDouble())
                val px = ScrubberMath.offsetAt(at.toLong(), totalPx, duration)
                // Only when it has moved by a pixel: a paused video costs nothing.
                if (kotlin.math.abs(px - lastPx) >= 1f) {
                    lastPx = px
                    val index = (px / framePx).toInt().coerceIn(0, frames - 1)
                    strip.scrollToItem(index, (px - index * framePx).toInt())
                }
            } else {
                lastPx = -1f
            }
        }
    }
    // Opened out from the video's thumbnail: wide enough to scroll comfortably, not the whole screen.
    BoxWithConstraints(
        Modifier.fillMaxWidth(0.78f).height(THUMB_H + 8.dp).clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val side = maxWidth / 2
        LazyRow(
            state = strip,
            contentPadding = PaddingValues(horizontal = side),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(THUMB_H),
        ) {
            items(frames) { i ->
                // The video's own picture shows until the real frame arrives (23 Oct: empty dark boxes).
                Box(Modifier.size(FRAME_W, THUMB_H)) {
                    AsyncImage(Thumb(item.uri, true, 160), null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                    AsyncImage(
                        model = VideoFrame(item.uri, ScrubberMath.frameTime(i, frames, duration), FRAME_PX),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
        }
        // The playhead.
        Box(Modifier.width(3.dp).height(THUMB_H + 8.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
    }
}

/**
 * The tablet's video strip (20 Nov, Deepak: "on the tablet the strip isn't aligned"; phones keep [VideoScrubber]).
 * The whole video fits the strip, centred and the same width every time, using the frames already read ahead; the
 * white line moves across as it plays. Tap or drag anywhere on it to jump there.
 */
@Composable
private fun FittedVideoTimeline(item: MediaItem, video: VideoControls, onTouch: () -> Unit) {
    val duration = (if (item.durationMillis > 0) item.durationMillis else video.durationMs).coerceAtLeast(1L)
    val times = remember(item.id, duration) { ScrubberMath.frameTimes(duration) }
    var dragging by remember { mutableStateOf(false) }
    var fraction by remember { mutableStateOf(0f) }
    fun seekTo(f: Float) {
        val at = f.coerceIn(0f, 1f)
        fraction = at
        video.seek((at * duration).toLong())
        onTouch()
    }
    // The line follows the video on every screen frame, carried forward by the clock between the player's reports
    // (the same easing as the phone's strip, so it glides rather than steps).
    LaunchedEffect(item.id, duration) {
        var seen = -1L
        var base = video.positionMs.toDouble()
        var baseAt = 0L
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            val reported = video.positionMs
            val guess = if (video.playing && baseAt != 0L) base + (now - baseAt) / 1_000_000.0 else base
            if (reported != seen) {
                seen = reported
                base = ScrubberMath.follow(guess, reported.toDouble())
                baseAt = now
            } else if (!video.playing) {
                base = reported.toDouble()
                baseAt = now
            }
            if (!dragging) {
                val at = (if (video.playing) base + (now - baseAt) / 1_000_000.0 else base).coerceIn(0.0, duration.toDouble())
                val f = (at / duration).toFloat()
                if (kotlin.math.abs(f - fraction) > 0.0005f) fraction = f
            }
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth(TABLET_STRIP_SHARE)
            .widthIn(max = TABLET_STRIP_MAX)
            .height(THUMB_H + 8.dp)
            .semantics { contentDescription = "Video timeline. Drag to move through the video" },
        contentAlignment = Alignment.CenterStart,
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val cells = (maxWidth / FRAME_W).toInt().coerceIn(4, times.size.coerceAtLeast(4))
        Row(
            Modifier
                .fillMaxWidth()
                .height(THUMB_H)
                .clip(RoundedCornerShape(8.dp))
                .pointerInput(item.id, duration) {
                    detectTapGestures { o -> seekTo(o.x / size.width) }
                }
                .pointerInput(item.id, duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { o ->
                            dragging = true
                            seekTo(o.x / size.width)
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ -> seekTo(change.position.x / size.width) }
                },
        ) {
            repeat(cells) { c ->
                val time = times[(c * (times.size - 1)) / (cells - 1).coerceAtLeast(1)]
                Box(Modifier.weight(1f).height(THUMB_H)) {
                    AsyncImage(Thumb(item.uri, true, 160), null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                    AsyncImage(
                        model = VideoFrame(item.uri, time, FRAME_PX),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
        }
        // The playhead, moving across.
        Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset((fraction * widthPx - 1.5.dp.toPx()).roundToInt(), 0) }
                .width(3.dp)
                .height(THUMB_H + 8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White),
        )
    }
}

/** The tablet's video strip and its time row: the same share of the width, never wider than this (20 Nov). */
private const val TABLET_STRIP_SHARE = 0.78f
private val TABLET_STRIP_MAX = 720.dp

/** Pure maths of the frame strip, tested. */
object ScrubberMath {
    /** About one frame a second, between 8 and 60 frames. */
    fun frameCount(durationMs: Long): Int = (durationMs / 1000L).toInt().coerceIn(8, 60)

    fun frameTime(index: Int, frames: Int, durationMs: Long): Long = durationMs * index / frames

    /** Every frame time of the strip, in order. */
    fun frameTimes(durationMs: Long): List<Long> {
        val d = durationMs.coerceAtLeast(1L)
        val n = frameCount(d)
        return List(n) { frameTime(it, n, d) }
    }

    fun timeAt(offsetPx: Float, totalPx: Float, durationMs: Long): Long =
        if (totalPx <= 0f) 0L else ((offsetPx / totalPx).coerceIn(0f, 1f) * durationMs).toLong()

    /**
     * The strip's own running time meeting the player's report (13 Nov): far apart (a seek, or the first report) it
     * takes the report; close, it moves a third of the way, so the strip never jerks back or forth.
     */
    fun follow(guessMs: Double, reportedMs: Double): Double =
        if (kotlin.math.abs(reportedMs - guessMs) > 400.0) reportedMs else guessMs + (reportedMs - guessMs) / 3.0

    fun offsetAt(timeMs: Long, totalPx: Float, durationMs: Long): Float =
        if (durationMs <= 0L) 0f else (timeMs.toFloat() / durationMs).coerceIn(0f, 1f) * totalPx
}

private val FRAME_W = 30.dp
private const val FRAME_PX = 160
private const val VIDEO_BARS_MS = 3000L

private fun whenTaken(item: MediaItem): String {
    val zone = TimeZone.currentSystemDefault()
    val time = Instant.fromEpochMilliseconds(item.takenAtMillis).toLocalDateTime(zone)
    val today = Timeline.localDate(kotlinx.datetime.Clock.System.now().toEpochMilliseconds(), zone)
    return "${Timeline.dayLabel(time.date, today)}, ${Timeline.clockTime(time.hour, time.minute)}"
}

/** True when a loaded picture carries an HDR brightness map (Ultra HDR, 17 Nov). */
internal expect fun coil3.Image.hasHdrMap(): Boolean

/**
 * Pictures kept in memory for a photo, at every size (17 Nov): forgotten when the photo is saved over, so nothing
 * shows the old picture. Keys are the address alone (full photo) or the address with a size or time after it.
 */
internal object PictureCache {
    fun forget(context: coil3.PlatformContext, uri: String) {
        val cache = coil3.SingletonImageLoader.get(context).memoryCache ?: return
        cache.keys.filter { k -> k.key == uri || k.key.startsWith("$uri#") || k.key.startsWith("$uri@") }.forEach { cache.remove(it) }
    }
}
