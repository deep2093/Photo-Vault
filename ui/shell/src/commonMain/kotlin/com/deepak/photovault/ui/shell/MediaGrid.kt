package com.deepak.photovault.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.clickable
import com.deepak.photovault.designsystem.glassChrome
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.unit.LayoutDirection
import com.deepak.photovault.designsystem.glassPanel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.Row
import com.deepak.photovault.core.backup.isDriveOnly
import androidx.compose.material.icons.rounded.Cloud
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
import androidx.compose.ui.composed
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.DisposableEffect
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
 * tap to open, long-press to select, long-press and slide to select many, a fast-scroll slider on the right,
 * and tap-the-section-again to glide back to the top. Used by Photos, albums, search and Recently deleted.
 *
 * [headerItems] is how many grid items [header] adds (the slider skips them when showing dates).
 */
@Composable
fun MediaGrid(
    sections: List<TimelineSection>,
    state: ShellState,
    gallery: GalleryState,
    screenPadding: PaddingValues,
    onOpen: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    tileLabel: TileLabel? = null,
    header: (LazyGridScope.() -> Unit)? = null,
    headerItems: Int = if (header != null) 1 else 0,
    /** A section key to scroll to (coming from a month card); [onJumped] clears it once done. */
    jumpTo: String? = null,
    onJumped: () -> Unit = {},
    /** Pinching out past the smallest photos (Photos: goes to Months). */
    onZoomOutPastMax: (() -> Unit)? = null,
    /** Items after the photos (an album's count, 24 Oct). */
    footer: (LazyGridScope.() -> Unit)? = null,
    /** A round button that glides back to the top: shows when away from the top, hides while scrolling down (albums, 27 Oct). */
    topButton: Boolean = false,
) {
    // Photos run edge to edge like Samsung Gallery (23 Oct): only the system's own side insets stay
    // (landscape cut-outs); headers and controls keep the screen margin themselves.
    val contentPadding = PaddingValues(
        start = (screenPadding.calculateStartPadding(LayoutDirection.Ltr) - SCREEN_MARGIN).coerceAtLeast(0.dp),
        end = (screenPadding.calculateEndPadding(LayoutDirection.Ltr) - SCREEN_MARGIN).coerceAtLeast(0.dp),
        top = screenPadding.calculateTopPadding(),
        bottom = screenPadding.calculateBottomPadding(),
    )
    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = maxWidth.value.toInt()
        val maxColumns = AdaptiveLayout.maxColumns(width)
        val columns = state.gridColumns?.let { AdaptiveLayout.clampColumns(it, width) } ?: AdaptiveLayout.defaultColumns(width)
        val currentColumns by rememberUpdatedState(columns)
        val currentMax by rememberUpdatedState(maxColumns)
        val tileDp = maxWidth / columns
        val scrollKey = remember { state.scrollKey() }
        val gridState = rememberLazyGridState(
            initialFirstVisibleItemIndex = remember { state.savedScroll[scrollKey]?.first ?: 0 },
            initialFirstVisibleItemScrollOffset = remember { state.savedScroll[scrollKey]?.second ?: 0 },
        )
        DisposableEffect(gridState) {
            onDispose { state.savedScroll[scrollKey] = gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
        }
        var topTaps by remember { mutableStateOf(0) }
        val topFade = ScrollToTopEffect(gridState, topTaps)
        LaunchedEffect(jumpTo, sections) {
            val key = jumpTo ?: return@LaunchedEffect
            var index = headerItems
            for (section in sections) {
                if (section.key == key) {
                    gridState.scrollToItem(index)
                    break
                }
                index += 1 + section.items.size
            }
            onJumped()
        }
        val currentZoomOut by rememberUpdatedState(onZoomOutPastMax)
        val reduceMotion = LocalGlassSettings.current.reduceMotion
        val density = LocalDensity.current
        val haptics = LocalHapticFeedback.current
        val scope = rememberCoroutineScope()

        // Photos in screen order, for drag selection.
        val order = remember(sections) { sections.flatMap { s -> s.items.map { it.id } } }
        val positionOf = remember(order) { order.withIndex().associate { (i, id) -> id to i } }
        val shown = remember(sections) { sections.flatMap { it.items }.associateBy { it.id } }
        val currentOnOpen by rememberUpdatedState(onOpen)
        val spacingPx = with(density) { GRID_SPACING.toPx() }
        val startPadPx = with(density) { contentPadding.calculateStartPadding(LayoutDirection.Ltr).toPx() }
        val endPadPx = with(density) { contentPadding.calculateEndPadding(LayoutDirection.Ltr).toPx() }
        val topPadPx = with(density) { contentPadding.calculateTopPadding().toPx() }
        val bottomPadPx = with(density) { contentPadding.calculateBottomPadding().toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val widthPx = with(density) { maxWidth.toPx() }

        val handler = remember(gridState, order, columns, startPadPx, endPadPx, widthPx, heightPx) {
            object : PhotoGridGestureHandler {
                private var anchor = -1
                private var adding = true
                private var initial: Set<Long> = emptySet()
                private var finger = NOWHERE
                private var autoScroll: Job? = null

                override fun photoAt(position: Offset): Long? {
                    val info = gridState.layoutInfo
                    // Item offsets are measured from the start of the content; the viewport starts above it
                    // by the top padding (viewportStartOffset is negative).
                    val rowItems = info.visibleItemsInfo
                        .filter { it.key is Long }
                        .filter { item ->
                            val top = item.offset.y - info.viewportStartOffset
                            position.y >= top && position.y < top + item.size.height + spacingPx
                        }
                        .sortedBy { it.offset.x }
                    if (rowItems.isEmpty()) return null
                    val cell = (widthPx - startPadPx - endPadPx + spacingPx) / columns
                    val column = ((position.x - startPadPx) / cell).toInt().coerceIn(0, columns - 1)
                    return rowItems.getOrNull(column)?.key as? Long ?: rowItems.last().key as Long
                }

                override fun onTap(id: Long) {
                    // Looked up among this grid's own photos, so Recently deleted ones open too (24 Oct).
                    if (state.selecting) state.toggleSelected(id) else (shown[id] ?: gallery.find(id))?.let(currentOnOpen)
                }

                override fun onDragSelectStart(id: Long) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    initial = state.selected
                    anchor = positionOf[id] ?: -1
                    adding = id !in initial
                    state.selected = DragSelect.selection(initial, order, anchor, anchor, adding)
                    autoScroll = scope.launch {
                        val zone = with(density) { 96.dp.toPx() }
                        val maxSpeed = with(density) { 22.dp.toPx() }
                        while (isActive) {
                            val speed = DragSelect.edgeSpeed(finger.y, topPadPx, heightPx - bottomPadPx, zone, maxSpeed)
                            if (speed != 0f) {
                                gridState.scrollBy(speed)
                                onDragSelectMove(finger)
                            }
                            withFrameNanos { }
                        }
                    }
                }

                override fun onDragSelectMove(position: Offset) {
                    finger = position
                    val id = photoAt(position) ?: return
                    val current = positionOf[id] ?: return
                    state.selected = DragSelect.selection(initial, order, anchor, current, adding)
                }

                override fun onDragSelectEnd() {
                    autoScroll?.cancel()
                    autoScroll = null
                    finger = NOWHERE
                }
            }
        }

        // Tells the delete animation where each photo is drawn (window coordinates).
        val spots = LocalTileSpots.current
        val gridOrigin = remember { floatArrayOf(0f, 0f) }
        DisposableEffect(spots, gridState) {
            spots?.lookup = { ids ->
                val info = gridState.layoutInfo
                info.visibleItemsInfo.mapNotNull { item ->
                    val id = item.key as? Long ?: return@mapNotNull null
                    if (id !in ids) return@mapNotNull null
                    val left = gridOrigin[0] + item.offset.x
                    val top = gridOrigin[1] + item.offset.y - info.viewportStartOffset
                    id to androidx.compose.ui.geometry.Rect(left, top, left + item.size.width, top + item.size.height)
                }.toMap()
            }
            onDispose { spots?.lookup = null }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
            verticalArrangement = Arrangement.spacedBy(GRID_SPACING),
            modifier = Modifier
                .fillMaxSize()
                .then(topFade)
                .onGloballyPositioned { val p = it.positionInRoot(); gridOrigin[0] = p.x; gridOrigin[1] = p.y }
                .photoGridGestures(gridState, handler)
                .pinchToZoom { zoomIn ->
                    val past = currentZoomOut
                    if (!zoomIn && currentColumns >= currentMax && past != null) past() else state.zoomGrid(if (zoomIn) +1 else -1, currentColumns, currentMax)
                },
        ) {
            header?.invoke(this)
            sections.forEach { section ->
                item(key = "header-${section.key}", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                    SectionHeader(section.label, Modifier.padding(start = SCREEN_MARGIN + 4.dp, end = SCREEN_MARGIN, top = 12.dp, bottom = 4.dp))
                }
                items(section.items, key = { it.id }, contentType = { "tile" }) { item ->
                    PhotoTile(
                        item = item,
                        tileSize = tileDp,
                        favourite = gallery.isFavourite(item),
                        selecting = state.selecting,
                        selected = item.id in state.selected,
                        label = tileLabel?.invoke(item),
                        onClick = { handler.onTap(item.id) },
                        onLongClick = { state.toggleSelected(item.id) },
                        // Photos glide to their new place when the grid changes size (instant with reduce motion).
                        modifier = if (reduceMotion) Modifier else Modifier.animateItem(),
                    )
                }
            }
            footer?.invoke(this)
        }

        if (topButton) BackToTopButton(
            grid = gridState,
            onClick = { topTaps++ },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 56.dp, bottom = contentPadding.calculateBottomPadding() + 14.dp),
        )
        FastScroller(
            grid = gridState,
            sections = sections,
            headerItems = headerItems,
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

/**
 * The back-to-top button (27 Oct): visible whenever the grid is away from the top, and tucked away while the
 * person is scrolling down; it comes back when they scroll up or stop.
 */
@Composable
private fun BackToTopButton(grid: LazyGridState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val away by remember { derivedStateOf { grid.firstVisibleItemIndex > 0 || grid.firstVisibleItemScrollOffset > 0 } }
    var goingDown by remember { mutableStateOf(false) }
    LaunchedEffect(grid) {
        var previous = Long.MIN_VALUE
        snapshotFlow { grid.firstVisibleItemIndex * 1_000_000L + grid.firstVisibleItemScrollOffset }.collect { now ->
            if (previous != Long.MIN_VALUE && now != previous) goingDown = now > previous
            previous = now
        }
    }
    val show = away && !(goingDown && grid.isScrollInProgress)
    AnimatedVisibility(visible = show, enter = fadeIn(motionSpec()) + scaleIn(motionSpec(), initialScale = 0.8f), exit = fadeOut(motionSpec()) + scaleOut(motionSpec(), targetScale = 0.8f), modifier = modifier) {
        Box(
            Modifier
                .size(44.dp)
                .glassChrome(androidx.compose.foundation.shape.CircleShape, elevation = 6.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Back to top", onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            com.deepak.photovault.designsystem.DepthIcon(androidx.compose.material.icons.Icons.Rounded.ArrowUpward, "Back to top", LocalGlassTheme.current.onGlass, Modifier.size(24.dp))
        }
    }
}

/** Thin gaps between photos, as in Samsung Gallery (was 3 dp). */
private val GRID_SPACING = 1.5.dp

/**
 * A slider on the right edge for moving quickly through a big library (feedback 22 Oct). Hidden until the
 * grid scrolls, fades away shortly after it stops, and shows the date while it is being dragged.
 */
@Composable
private fun FastScroller(
    grid: LazyGridState,
    sections: List<TimelineSection>,
    headerItems: Int,
    top: Dp,
    bottom: Dp,
    modifier: Modifier = Modifier,
) {
    val theme = LocalGlassTheme.current
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    val scrolling = grid.isScrollInProgress
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) {
            visible = true
        } else {
            delay(1200)
            visible = false
        }
    }
    // Grid position of each section's header, to name the date under the slider.
    val starts = remember(sections, headerItems) {
        var index = headerItems
        sections.map { s -> (index to s.label).also { index += 1 + s.items.size } }
    }
    val total = remember(sections, headerItems) { headerItems + sections.sumOf { 1 + it.items.size } }
    if (total < 60) return // Short lists don't need it.
    BoxWithConstraints(modifier.fillMaxHeight().padding(top = top, bottom = bottom).width(140.dp)) {
        val density = LocalDensity.current
        val thumbHeight = 44.dp
        val trackPx = with(density) { (maxHeight - thumbHeight).toPx() }.coerceAtLeast(1f)
        val fraction by remember {
            derivedStateOf {
                val layout = grid.layoutInfo
                val visibleCount = layout.visibleItemsInfo.size.coerceAtLeast(1)
                (grid.firstVisibleItemIndex.toFloat() / (layout.totalItemsCount - visibleCount).coerceAtLeast(1)).coerceIn(0f, 1f)
            }
        }
        val label by remember(starts) {
            derivedStateOf { starts.lastOrNull { it.first <= grid.firstVisibleItemIndex }?.second ?: starts.firstOrNull()?.second.orEmpty() }
        }
        fun jumpTo(y: Float) {
            val f = ((y - with(density) { thumbHeight.toPx() } / 2) / trackPx).coerceIn(0f, 1f)
            scope.launch { grid.scrollToItem((f * (total - 1)).toInt()) }
        }
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(motionSpec()) + slideInHorizontally(motionSpec()) { it / 3 },
            exit = fadeOut(motionSpec(300)) + slideOutHorizontally(motionSpec(300)) { it / 3 },
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                // The touch strip: a little wider than the thumb so it is easy to grab.
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .fillMaxHeight()
                        .width(40.dp)
                        .pointerInput(total) {
                            detectVerticalDragGestures(
                                onDragStart = { dragging = true; jumpTo(it.y) },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                                onVerticalDrag = { change, _ ->
                                    change.consume()
                                    jumpTo(change.position.y)
                                },
                            )
                        }
                        .semantics { contentDescription = "Fast scroll" },
                )
                val thumbY = with(density) { (fraction * trackPx).toDp() }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = thumbY, end = 4.dp)
                        .size(width = 8.dp, height = thumbHeight)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (dragging) theme.accent else theme.accent.copy(alpha = 0.8f)),
                )
                if (dragging && label.isNotEmpty()) {
                    Text(
                        label,
                        color = theme.onGlass,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = thumbY + 6.dp, end = 24.dp)
                            .glassPanel(RoundedCornerShape(16.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/** Main colours of loaded thumbnails, so adaptive themes don't re-read the same photo. */
private val mainColors = mutableStateMapOf<Long, Int>()

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
    val open = onClick
    val select = onLongClick
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
            // Square tiles: rounded corners look odd with hairline gaps (23 Oct).
            .background(theme.onGlass.copy(alpha = 0.08f))
            .semantics {
                // Touch is handled by the grid (tap, long-press, slide-select); TalkBack uses these actions.
                onClick(label = "Open") { open(); true }
                onLongClick(label = "Select") { select(); true }
                contentDescription = buildString {
                    append(if (item.isVideo) "Video" else "Photo")
                    append(", ").append(Timeline.localDate(item.takenAtMillis, kotlinx.datetime.TimeZone.currentSystemDefault()))
                    if (favourite) append(", favourite")
                    if (item.isDriveOnly) append(", on the family drive")
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
        // GIFs are marked so they stand out (24 Oct); they move when opened.
        if (item.mimeType.equals("image/gif", ignoreCase = true)) {
            Text(
                "GIF",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        // Bottom left: a cloud for photos that are only on the family drive (25 Oct), and the heart for favourites.
        val onDrive = item.isDriveOnly
        if (onDrive || favourite) {
            Row(Modifier.align(Alignment.BottomStart).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (onDrive) {
                    Icon(
                        Icons.Rounded.Cloud, "On the family drive", tint = Color.White,
                        modifier = Modifier.size(18.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(2.dp),
                    )
                }
                if (favourite) Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
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
internal fun Modifier.pinchToZoom(onStep: (zoomIn: Boolean) -> Unit): Modifier = composed {
    // A small tick on every step (24 Oct).
    val haptics = com.deepak.photovault.designsystem.rememberHaptics()
    val step by androidx.compose.runtime.rememberUpdatedState(onStep)
    pinchSteps { zoomIn ->
        haptics.tick()
        step(zoomIn)
    }
}

private fun Modifier.pinchSteps(onStep: (zoomIn: Boolean) -> Unit): Modifier = pointerInput(Unit) {
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
            // Primary colour: this text sits straight on the wallpaper, not on glass.
            color = theme.onGlass,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        action?.invoke()
    }
}
