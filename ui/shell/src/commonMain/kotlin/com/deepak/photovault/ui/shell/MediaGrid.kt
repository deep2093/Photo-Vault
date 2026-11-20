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
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.clickable
import com.deepak.photovault.designsystem.accentSurface
import com.deepak.photovault.designsystem.shimmer
import com.deepak.photovault.designsystem.openingWave
import com.deepak.photovault.designsystem.solidGlass
import com.deepak.photovault.designsystem.glassChrome
import com.deepak.photovault.designsystem.lensGlassActive
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
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.Check
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
    /** Which page this grid is on: each keeps its own pinch size, remembered after the app closes (15 Nov). */
    gridKey: String = GRID_PHOTOS,
    tileLabel: TileLabel? = null,
    /** A face to circle on a tile (inside a person: which face matched, 5 Nov): left, top, width, height, 0 to 1. */
    tileMark: ((MediaItem) -> FloatArray?)? = null,
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
    /** One unbroken grid with no date rows, and a date that floats at the top left (Photos > All, like iOS, 31 Oct). */
    continuous: Boolean = false,
    /** Photos: tells the screen whether the grid is away from the top (the pills and the date turn white then). */
    onAwayFromTop: ((Boolean) -> Unit)? = null,
    /** The floating date's colour (Photos changes it with the pills). */
    dateColor: Color = Color.White,
    /**
     * Mixed sizes (16 Nov): here and there a photo is shown double size ([Mosaic]). Only for a [continuous] grid at
     * 3 to 5 columns, and only while Settings > Appearance > Mixed photo sizes is on.
     */
    mixed: Boolean = false,
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
        // Foldables (20 Nov): the folded and the open screen each keep their own photo size (a tablet counts as open).
        val large = LocalTabletDevice.current
        val columns = state.gridColumnsFor(gridKey, large)?.let { AdaptiveLayout.clampColumns(it, width) } ?: AdaptiveLayout.defaultColumns(width)
        val tileDp = maxWidth / columns
        // Mixed sizes: the photos cut into plain runs and two-row blocks; null for the plain grid.
        val flat = remember(sections) { sections.flatMap { it.items } }
        val mosaic = mixed && continuous && state.appearance.mixedGrid && Mosaic.worksWith(columns)
        val parts = remember(flat, columns, mosaic) { if (mosaic) Mosaic.plan(flat.map { it.id }, columns) else null }
        // Each block by its grid key, and for every grid item the first photo it holds (the grid no longer has one
        // item per photo, so the floating date, the side slider and "jump to" count grid items through this).
        val blocks = remember(parts, flat) {
            parts.orEmpty().filterIsInstance<Mosaic.Block>().associateBy { "block-${flat[it.from].id}" }
        }
        val gridFirst = remember(parts, flat) {
            if (parts == null) flat else parts.flatMap { part ->
                when (part) {
                    is Mosaic.Run -> flat.subList(part.from, part.to)
                    is Mosaic.Block -> listOf(flat[part.from])
                }
            }
        }
        val scrollKey = remember { state.scrollKey() }
        val gridState = rememberLazyGridState(
            initialFirstVisibleItemIndex = remember { state.savedScroll[scrollKey]?.first ?: 0 },
            initialFirstVisibleItemScrollOffset = remember { state.savedScroll[scrollKey]?.second ?: 0 },
        )
        DisposableEffect(gridState) {
            onDispose { state.savedScroll[scrollKey] = gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
        }
        var topTaps by remember { mutableStateOf(0) }
        val currentAway by rememberUpdatedState(onAwayFromTop)
        LaunchedEffect(gridState) {
            snapshotFlow { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0 }.collect { currentAway?.invoke(it) }
        }
        DisposableEffect(Unit) { onDispose { currentAway?.invoke(false) } }
        // The real row layout of the "All" grid, so back to top measures the distance like Albums does (17 Nov).
        val gridRows = remember(parts, gridFirst.size, columns, headerItems, continuous) {
            when {
                !continuous -> null
                parts != null -> GridRows.mosaic(headerItems, parts, columns)
                else -> GridRows.plain(headerItems, gridFirst.size, columns)
            }
        }
        val topFade = ScrollToTopEffect(gridState, topTaps, gridRows)
        LaunchedEffect(jumpTo, sections) {
            val key = jumpTo ?: return@LaunchedEffect
            val planned = parts
            if (planned != null) {
                // Mixed sizes: the grid item that holds the section's first photo.
                val first = sections.firstOrNull { it.key == key }?.items?.firstOrNull()
                val at = first?.let { f -> flat.indexOfFirst { it.id == f.id } } ?: -1
                if (at >= 0) {
                    var index = headerItems
                    for (part in planned) {
                        val (from, to, count) = when (part) {
                            is Mosaic.Run -> Triple(part.from, part.to, part.to - part.from)
                            is Mosaic.Block -> Triple(part.from, part.from + Mosaic.photosIn(columns), 1)
                        }
                        if (at < to) {
                            gridState.scrollToItem(index + if (part is Mosaic.Run) at - from else 0)
                            break
                        }
                        index += count
                    }
                }
                onJumped()
                return@LaunchedEffect
            }
            var index = headerItems
            for (section in sections) {
                if (section.key == key) {
                    gridState.scrollToItem(index)
                    break
                }
                index += (if (continuous) 0 else 1) + section.items.size
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

        /** Every photo on screen with where it is drawn, in the grid's own coordinates (mixed-size blocks opened up). */
        fun visibleTiles(): List<Pair<Long, androidx.compose.ui.geometry.Rect>> {
            val info = gridState.layoutInfo
            val out = ArrayList<Pair<Long, androidx.compose.ui.geometry.Rect>>()
            for (item in info.visibleItemsInfo) {
                // Item offsets are measured from the start of the content; the viewport starts above it by the
                // top padding (viewportStartOffset is negative).
                val left = item.offset.x.toFloat()
                val top = (item.offset.y - info.viewportStartOffset).toFloat()
                val key = item.key
                if (key is Long) {
                    out += key to androidx.compose.ui.geometry.Rect(left, top, left + item.size.width, top + item.size.height)
                } else if (key is String) {
                    val block = blocks[key] ?: continue
                    val cell = (item.size.width - spacingPx * (columns - 1)) / columns
                    Mosaic.slots(columns, block.right).forEachIndexed { k, slot ->
                        val photo = flat.getOrNull(block.from + k) ?: return@forEachIndexed
                        val x = left + slot.column * (cell + spacingPx)
                        val y = top + slot.row * (cell + spacingPx)
                        val side = cell * slot.span + spacingPx * (slot.span - 1)
                        out += photo.id to androidx.compose.ui.geometry.Rect(x, y, x + side, y + side)
                    }
                }
            }
            return out
        }

        val handler = remember(gridState, order, columns, startPadPx, endPadPx, widthPx, heightPx, blocks) {
            object : PhotoGridGestureHandler {
                private var anchor = -1
                private var adding = true
                private var initial: Set<Long> = emptySet()
                private var finger = NOWHERE
                private var autoScroll: Job? = null

                override fun photoAt(position: Offset): Long? {
                    // The photos on the row under the finger (a little slack for the hairline gaps), then the
                    // nearest of them sideways. Works for plain tiles and for the tiles inside a mixed-size block.
                    val row = visibleTiles().filter { (_, r) -> position.y >= r.top && position.y < r.bottom + spacingPx }
                    return row.minByOrNull { (_, r) -> if (position.x < r.left) r.left - position.x else if (position.x > r.right) position.x - r.right else 0f }?.first
                }

                override fun onTap(id: Long) {
                    // Looked up among this grid's own photos, so Recently deleted ones open too (24 Oct).
                    if (state.selecting) {
                        state.toggleSelected(id)
                    } else {
                        val item = shown[id] ?: gallery.find(id) ?: return
                        // A light touch as a photo opens (21 Nov).
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        // At once (21 Nov): the small dip before opening made every photo wait, so it is gone.
                        currentOnOpen(item)
                    }
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

        /** The grid item that holds photo [id] (a mixed-size block holds several). */
        fun gridIndexOf(id: Long): Int? {
            val planned = parts
            if (planned != null) {
                val at = positionOf[id] ?: return null
                var index = headerItems
                for (part in planned) {
                    when (part) {
                        is Mosaic.Run -> {
                            if (at < part.to) return index + (at - part.from).coerceAtLeast(0)
                            index += part.to - part.from
                        }
                        is Mosaic.Block -> {
                            if (at < part.from + Mosaic.photosIn(columns)) return index
                            index += 1
                        }
                    }
                }
                return null
            }
            var index = headerItems
            for (section in sections) {
                if (!continuous) index += 1
                val k = section.items.indexOfFirst { it.id == id }
                if (k >= 0) return index + k
                index += section.items.size
            }
            return null
        }

        // The pinch (Phase 12, 20 Nov): the grid grows with the fingers and changes size without a jump (GridPinch).
        val pinch = remember(gridState) { GridPinch(scope, gridState) }
        val pinchHost = remember(pinch) { GridPinchHost().also { pinch.host = it } }
        val pictureA = androidx.compose.ui.graphics.rememberGraphicsLayer()
        val pictureB = androidx.compose.ui.graphics.rememberGraphicsLayer()
        val ticks = com.deepak.photovault.designsystem.rememberHaptics()
        androidx.compose.runtime.SideEffect {
            pinchHost.columns = columns
            pinchHost.maxColumns = maxColumns
            pinchHost.reduceMotion = reduceMotion
            pinchHost.width = widthPx
            pinchHost.height = heightPx
            pinchHost.photoAtFn = handler::photoAt
            pinchHost.tileOfFn = { id -> visibleTiles().firstOrNull { it.first == id }?.second }
            pinchHost.indexOfFn = ::gridIndexOf
            pinchHost.changeColumnsFn = { c -> state.setGridColumns(gridKey, large, c) }
            pinchHost.pastSmallestFn = { currentZoomOut?.let { it(); true } ?: false }
            pinchHost.tickFn = { ticks.tick() }
        }

        /**
         * Brings photo [id] onto the screen if it isn't fully there (20 Nov): the viewer asks, out of sight under the
         * photo, so a photo swiped to in the viewer can shrink back into its own tile.
         */
        suspend fun bringIntoView(id: Long): Boolean {
            val top = topPadPx
            val bottom = heightPx - bottomPadPx
            visibleTiles().firstOrNull { it.first == id }?.second?.let { r -> if (r.top >= top - 1f && r.bottom <= bottom + 1f) return true }
            val index = gridIndexOf(id) ?: return false
            gridState.scrollToItem(index)
            val r = visibleTiles().firstOrNull { it.first == id }?.second ?: return false
            gridState.scrollBy(r.center.y - (top + bottom) / 2f)
            return true
        }
        val revealHere: suspend (Long) -> Boolean = { id -> bringIntoView(id) }
        val revealNow = rememberUpdatedState(revealHere)

        // Tells the delete animation where each photo is drawn (window coordinates).
        val spots = LocalTileSpots.current
        val gridOrigin = remember { floatArrayOf(0f, 0f) }
        DisposableEffect(spots, gridState, blocks) {
            spots?.lookup = { ids ->
                visibleTiles().filter { it.first in ids }.associate { (id, r) -> id to r.translate(gridOrigin[0], gridOrigin[1]) }
            }
            onDispose { spots?.lookup = null }
        }
        // Where one tile is, for the photo opening out of it and closing back into it (16 Nov).
        val depth = LocalPageDepth.current
        DisposableEffect(state, gridState, depth, blocks) {
            val ask: Pair<Int, (Long) -> androidx.compose.ui.geometry.Rect?> = depth to { id ->
                visibleTiles().firstOrNull { it.first == id }?.second?.translate(gridOrigin[0], gridOrigin[1])
            }
            state.tileLookups += ask
            onDispose { state.tileLookups -= ask }
        }
        DisposableEffect(state, depth) {
            val ask: suspend (Long) -> Boolean = { id -> revealNow.value(id) }
            val reveal = depth to ask
            state.tileRevealers += reveal
            onDispose { state.tileRevealers -= reveal }
        }
        // 21 Nov (Deepak: "the date underneath doesn't have glass"): the floating date and Back to top sit inside the
        // page, so they can't look through the screen's own recording (it holds them). Liquid Glass records the grid on
        // its own as well, and those two look through that instead: real glass, with the lens keeping the words readable.
        val liquidHere = LocalGlassTheme.current.isLiquidGlass
        val gridLens = if (liquidHere) rememberLensGlass() else null
        val gridLensOn = liquidHere && lensGlassActive()
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
            verticalArrangement = Arrangement.spacedBy(GRID_SPACING),
            modifier = Modifier
                .fillMaxSize()
                .then(gridLens?.source(gridLensOn) ?: Modifier)
                .then(topFade)
                .pinchDrawing(pinch, pictureA, pictureB)
                .onGloballyPositioned { val p = it.positionInRoot(); gridOrigin[0] = p.x; gridOrigin[1] = p.y }
                .photoGridGestures(gridState, handler)
                .pinchGesture(pinch),
        ) {
            header?.invoke(this)
            // One tile, the same in the plain grid and inside a mixed-size block.
            val tile: @Composable (MediaItem, Dp, Modifier) -> Unit = { item, size, tileModifier ->
                PhotoTile(
                    item = item,
                    tileSize = size,
                    favourite = gallery.isFavourite(item),
                    selecting = state.selecting,
                    selected = item.id in state.selected,
                    label = tileLabel?.invoke(item),
                    mark = tileMark?.invoke(item),
                    onClick = { handler.onTap(item.id) },
                    onLongClick = { state.toggleSelected(item.id) },
                    modifier = tileModifier,
                )
            }
            val planned = parts
            if (planned != null) {
                planned.forEach { part ->
                    when (part) {
                        is Mosaic.Run -> items(flat.subList(part.from, part.to), key = { it.id }, contentType = { "tile" }) { item ->
                            tile(item, tileDp, if (reduceMotion || pinch.resized) Modifier else Modifier.animateItem())
                        }
                        is Mosaic.Block -> item(key = "block-${flat[part.from].id}", span = { GridItemSpan(maxLineSpan) }, contentType = "block") {
                            val slots = remember(columns, part.right) { Mosaic.slots(columns, part.right) }
                            BoxWithConstraints((if (reduceMotion || pinch.resized) Modifier else Modifier.animateItem()).fillMaxWidth()) {
                                val cell = (maxWidth - GRID_SPACING * (columns - 1)) / columns
                                val step = cell + GRID_SPACING
                                Box(Modifier.fillMaxWidth().height(cell * 2 + GRID_SPACING)) {
                                    slots.forEachIndexed { k, slot ->
                                        val photo = flat.getOrNull(part.from + k) ?: return@forEachIndexed
                                        val side = cell * slot.span + GRID_SPACING * (slot.span - 1)
                                        androidx.compose.runtime.key(photo.id) {
                                            tile(photo, side, Modifier.offset(x = step * slot.column, y = step * slot.row).size(side))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else sections.forEach { section ->
                if (!continuous) item(key = "header-${section.key}", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                    // 13 Nov: where most of the section was taken, after the date ("7 Nov 2026 · Melbourne").
                    val where = remember(section.key, section.items.size, state.places.photos) { topPlace(section.items, state.places.photos) }
                    SectionHeader(if (where != null) "${section.label} · $where" else section.label, Modifier.padding(start = SCREEN_MARGIN + 4.dp, end = SCREEN_MARGIN, top = 12.dp, bottom = 4.dp), onWallpaper = true)
                }
                items(section.items, key = { it.id }, contentType = { "tile" }) { item ->
                    // Photos glide to their new place when the list changes (instant with reduce motion); while a pinch
                    // fades between sizes they jump, as the fade covers the change (20 Nov).
                    tile(item, tileDp, if (reduceMotion || pinch.resized) Modifier else Modifier.animateItem())
                }
            }
            footer?.invoke(this)
        }

        androidx.compose.runtime.CompositionLocalProvider(com.deepak.photovault.designsystem.LocalLensGlass provides gridLens) {
        // Clear Glass: the date shares the top pills' firmer film, so it looks like them over any photo.
        if (continuous) androidx.compose.runtime.CompositionLocalProvider(
            com.deepak.photovault.designsystem.LocalGlassFilm provides com.deepak.photovault.designsystem.LiquidGlassInk.topFilm(LocalGlassTheme.current.isDark),
        ) {
            // 21 Nov (Deepak): while Years / Months / All is away (scrolling down), the date rises into its place, at the
            // same pace as the pill and the bottom bar; it goes back down when they return.
            val pillAway by animateFloatAsState(if (LocalChrome.current?.hidden == true) 1f else 0f, motionSpec(CHROME_MS), label = "date rise")
            val risePx = with(LocalDensity.current) { (TopBarHeight - 18.dp).toPx() }
            FloatingDate(
                grid = gridState,
                photos = gridFirst,
                headerItems = headerItems,
                places = state.places.photos,
                color = dateColor,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    // Moved where it is laid out, so its glass knows where it is.
                    .offset { androidx.compose.ui.unit.IntOffset(0, -(pillAway * risePx).toInt()) }
                    .padding(
                    // Calm glass (18 Nov): on its own glass pill, its left edge in line with the Years / Months / All pill
                    // above (Deepak: "isn't aligned"). Before: plain text, moved in to line up with "N selected" (16 Nov).
                    start = (if (com.deepak.photovault.designsystem.GlassFinish.CALM) FLOATING_PILL_START else if (state.selecting) 30.dp else 24.dp) +
                        contentPadding.calculateStartPadding(LayoutDirection.Ltr),
                    top = (contentPadding.calculateTopPadding() - (if (com.deepak.photovault.designsystem.GlassFinish.CALM) 28.dp else 30.dp)).coerceAtLeast(0.dp),
                ),
            )
        }
        if (topButton) {
            BackToTopButton(
                grid = gridState,
                onClick = { topTaps++ },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding() + 14.dp),
            )
        }
        }
        if (parts != null) {
            // Mixed sizes: the slider counts grid items, and names the day of the first photo in each.
            val starts = remember(gridFirst, sections, headerItems) {
                val labelOf = HashMap<Long, String>()
                sections.forEach { sec -> sec.items.forEach { labelOf[it.id] = sec.label } }
                val out = ArrayList<Pair<Int, String>>()
                gridFirst.forEachIndexed { k, item ->
                    val label = labelOf[item.id].orEmpty()
                    if (out.lastOrNull()?.second != label) out += (headerItems + k) to label
                }
                out
            }
            FastScroller(gridState, starts, headerItems + gridFirst.size, contentPadding.calculateTopPadding(), contentPadding.calculateBottomPadding(), Modifier.align(Alignment.TopEnd))
        } else FastScroller(
            grid = gridState,
            sections = sections,
            headerItems = headerItems,
            continuous = continuous,
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
internal fun BackToTopButton(grid: LazyGridState, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
        // 18 Nov (Deepak: "same like the back arrow"): the back button's look, the arrow in the theme's text colour
        // on frosted glass, light by day and dark at night. Solid frost, since blur can't be drawn inside the scrolling
        // page (the see-through version left only a faint ring). No drop shadow: under a fading circle it showed as a square.
        val theme = LocalGlassTheme.current
        Box(
            Modifier
                .size(48.dp)
                // Liquid Glass (21 Nov): a real glass droplet looking through the photos (no shadow, as above).
                .then(
                    if (theme.isLiquidGlass) Modifier.glassChrome(androidx.compose.foundation.shape.CircleShape, elevation = 0.dp)
                    else Modifier.solidGlass(androidx.compose.foundation.shape.CircleShape),
                )
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Back to top", onClick = com.deepak.photovault.designsystem.hapticClick(onClick)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.ArrowUpward, "Back to top", tint = theme.onGlass, modifier = Modifier.size(24.dp))
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
    continuous: Boolean,
    top: Dp,
    bottom: Dp,
    modifier: Modifier = Modifier,
) {
    // Grid position of each section's header, to name the date under the slider.
    val rows = if (continuous) 0 else 1
    val starts = remember(sections, headerItems, continuous) {
        var index = headerItems
        sections.map { s -> (index to s.label).also { index += rows + s.items.size } }
    }
    val total = remember(sections, headerItems, continuous) { headerItems + sections.sumOf { rows + it.items.size } }
    FastScroller(grid, starts, total, top, bottom, modifier)
}

/** The slider itself: [starts] are the grid positions where each named part (a month) begins; [total] counts every grid item. */
@Composable
internal fun FastScroller(
    grid: LazyGridState,
    starts: List<Pair<Int, String>>,
    total: Int,
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

/**
 * Main colours of loaded thumbnails, so adaptive themes don't re-read the same photo. A plain map (18 Nov): as a
 * watched map, every new colour made every tile on screen draw itself again while scrolling. Main thread only.
 */
private val mainColors = HashMap<Long, Int>()

/**
 * Each photo's real shape (width / height) as its small picture was drawn (16 Nov). The phone's own record of
 * width and height ignores how the camera was held, so a portrait photo can be listed as landscape; the small
 * picture is already the right way up. The opening animation uses this so the photo flies in its true shape.
 */
internal val thumbShapes = HashMap<Long, Float>()

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
    mark: FloatArray? = null,
) {
    val theme = LocalGlassTheme.current
    var shape by remember(item.id) { mutableStateOf(0f) }
    // A photo that is only on the drive takes a moment to arrive: until then (or until it can't be read) a soft
    // shine moves across its tile (extra animations, 21 Nov).
    var arrived by remember(item.id) { mutableStateOf(false) }
    val adaptive = theme.adaptive != null
    val open = onClick
    val select = onLongClick
    // Thumbnail sizes are rounded up to a few buckets so the cache is shared between grid sizes.
    val px = with(LocalDensity.current) { tileSize.toPx() }.toInt()
    val bucket = thumbBucket(px)
    val inset by animateFloatAsState(if (selected) 0.86f else 1f, motionSpec())
    // Each tile keeps its own colour; it is worked out away from the main thread, so scrolling never waits for it.
    var main by remember(item.id) { mutableStateOf(if (adaptive) mainColors[item.id] else null) }
    if (adaptive) ContributeScreenColor(main?.let { Color(it) })
    val colorScope = rememberCoroutineScope()
    Box(
        modifier
            .aspectRatio(1f)
            // Square tiles: rounded corners look odd with hairline gaps (23 Oct).
            // Extra animations (21 Nov): as the app opens the photos arrive in a wave, after the logo reveal.
            .openingWave()
            .background(theme.onGlass.copy(alpha = 0.08f))
            .shimmer(item.isDriveOnly && !arrived, theme.onGlass)
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
            model = Thumb(item.uri, item.isVideo, bucket, item.sizeBytes),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onSuccess = { result ->
                if (adaptive && main == null) {
                    val known = mainColors[item.id]
                    if (known != null) {
                        main = known
                    } else {
                        val image = result.result.image
                        colorScope.launch {
                            val picked = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { mainColorOf(image) } ?: return@launch
                            mainColors[item.id] = picked
                            main = picked
                        }
                    }
                }
                val img = result.result.image
                if (img.height > 0) {
                    shape = img.width.toFloat() / img.height
                    thumbShapes[item.id] = shape
                }
                arrived = true
            },
            onError = { arrived = true },
            modifier = Modifier.fillMaxSize().scale(inset).clip(RoundedCornerShape(if (selected) 10.dp else 0.dp)),
        )
        if (mark != null && shape > 0f) {
            // The tile shows the middle of the photo, cropped to a square: place the circle the same way.
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize().scale(inset)) {
                val side = size.width
                val w = if (shape >= 1f) side * shape else side
                val h = if (shape >= 1f) side else side / shape
                val dx = (side - w) / 2f
                val dy = (side - h) / 2f
                val cx = dx + (mark[0] + mark[2] / 2f) * w
                val cy = dy + (mark[1] + mark[3] / 2f) * h
                val r = (maxOf(mark[2] * w, mark[3] * h) * 0.62f).coerceAtLeast(6.dp.toPx())
                val centre = androidx.compose.ui.geometry.Offset(cx, cy)
                drawCircle(Color.Black.copy(alpha = 0.45f), r, centre, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.5.dp.toPx()))
                drawCircle(Color.White, r, centre, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8.dp.toPx()))
            }
        }
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
                // 13 Nov: on a dark dot like the cloud, so the heart shows on bright photos too, and it is read aloud.
                if (favourite) Icon(
                    Icons.Rounded.Favorite, "Favourite", tint = Color.White,
                    modifier = Modifier.size(18.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(3.dp),
                )
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
            SelectionMark(selected, 24.dp)
        }
    }
}

/** Picked: a bright blue circle with a white tick and white ring (15 Nov: the dark tick was hard to see on photos). */
internal val SelectedBlue = Color(0xFF1A73E8)

/**
 * The round mark on a photo while picking (15 Nov): picked is bright blue with a white tick; not picked is an empty
 * white ring on a light shadow, so both show on dark and light photos in every theme.
 */
@Composable
internal fun SelectionMark(selected: Boolean, size: androidx.compose.ui.unit.Dp = 24.dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (selected) SelectedBlue else Color.Black.copy(alpha = 0.25f))
            .border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Rounded.Check, "Picked", tint = Color.White, modifier = Modifier.size(size * 0.7f))
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
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    /** A soft round picture above the words (13 Nov), so every empty screen has the same friendly look. */
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val theme = LocalGlassTheme.current
    androidx.compose.foundation.layout.Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Box(
                Modifier.padding(bottom = 16.dp).size(72.dp).glassPanel(androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(icon, contentDescription = null, tint = theme.accent, modifier = Modifier.size(34.dp))
            }
        }
        Text(title, color = theme.onWallpaper, style = MaterialTheme.typography.titleLarge)
        Text(
            body,
            // This text sits straight on the wallpaper, not on glass.
            color = theme.onWallpaper,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        action?.invoke()
    }
}

/** The picture size a grid tile of [px] pixels asks for. The delete effect asks for the same, so it finds the picture already in memory. */
internal fun thumbBucket(px: Int): Int = when {
    px <= 128 -> 128
    px <= 256 -> 256
    else -> 512
}

/**
 * The date of the photo at the top of the screen, floating at the top left (31 Oct, like iOS Photos). It changes as
 * you scroll; no word "Library", just the date, in the same glass as the other floating controls.
 */
@Composable
private fun FloatingDate(grid: LazyGridState, photos: List<MediaItem>, headerItems: Int, color: Color, modifier: Modifier = Modifier, places: Map<Long, PhotoPlace> = emptyMap()) {
    val theme = LocalGlassTheme.current
    val zone = remember { kotlinx.datetime.TimeZone.currentSystemDefault() }
    val text by remember(photos, headerItems, places) {
        derivedStateOf {
            val item = photos.getOrNull((grid.firstVisibleItemIndex - headerItems).coerceAtLeast(0)) ?: return@derivedStateOf ""
            val d = com.deepak.photovault.core.media.Timeline.localDate(item.takenAtMillis, zone)
            // 13 Nov: with the town the photo at the top was taken in, when it is known.
            val where = places[item.id]?.place?.name
            "${d.dayOfMonth} ${com.deepak.photovault.core.media.Timeline.monthName(d.month).take(3)} ${d.year}" + (if (where != null) " · $where" else "")
        }
    }
    if (text.isEmpty()) return
    if (com.deepak.photovault.designsystem.GlassFinish.CALM) {
        // 18 Nov (Deepak, option 2): only once the photos have reached the top (never over Memories), and only while
        // scrolling: it fades away 2 seconds after the photos stop, and comes back as soon as they move.
        val atPhotos by remember(headerItems) { derivedStateOf { grid.firstVisibleItemIndex >= headerItems } }
        var moving by remember { mutableStateOf(false) }
        LaunchedEffect(grid) {
            snapshotFlow { grid.isScrollInProgress }.collectLatest { scrolling ->
                if (scrolling) moving = true else { delay(DATE_LINGER_MS); moving = false }
            }
        }
        val shown by animateFloatAsState(if (atPhotos && moving) 1f else 0f, motionSpec(220), label = "date")
        if (shown <= 0f) return
        // On a frosted bar of its own, so it reads over any photo, in the title serif; its words start where "Years"
        // starts above it. Solid frost, not blur: inside the scrolling photos the blur drew nothing.
        // Extra animations (21 Nov): each part of the date rolls to its new value as the photos scroll.
        com.deepak.photovault.designsystem.RollingText(
            text,
            color = theme.onGlass,
            style = com.deepak.photovault.designsystem.titled(MaterialTheme.typography.titleSmall),
            fontWeight = FontWeight.Bold,
            modifier = modifier
                .graphicsLayer { alpha = shown }
                // Liquid Glass (21 Nov): clear glass over the photos, the lens keeping the date readable. No shadow:
                // under the fade it would show as a square.
                .then(
                    if (theme.isLiquidGlass) Modifier.glassChrome(RoundedCornerShape(percent = 50), elevation = 0.dp)
                    else Modifier.solidGlass(RoundedCornerShape(percent = 50)),
                )
                .padding(horizontal = FLOATING_PILL_TEXT_INSET, vertical = 5.dp),
        )
        return
    }
    // Plain text on the photos, in the same style as Years / Months / All (31 Oct).
    com.deepak.photovault.designsystem.DepthText(
        text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

/** How long the date stays after the photos stop moving (18 Nov). */
private const val DATE_LINGER_MS = 2000L

/** The town most of [items] were taken in, with "and N more" when there are others; null when none is known. */
internal fun topPlace(items: List<MediaItem>, places: Map<Long, PhotoPlace>): String? {
    if (places.isEmpty()) return null
    val counts = LinkedHashMap<String, Int>()
    for (item in items.take(300)) places[item.id]?.place?.name?.let { counts[it] = (counts[it] ?: 0) + 1 }
    val best = counts.maxByOrNull { it.value } ?: return null
    return if (counts.size > 1) "${best.key} and ${counts.size - 1} more" else best.key
}
