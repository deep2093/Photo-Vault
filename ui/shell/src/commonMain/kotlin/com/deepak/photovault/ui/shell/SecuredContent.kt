package com.deepak.photovault.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.deepak.photovault.core.backup.isDriveOnly
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.Timeline
import com.deepak.photovault.designsystem.GlassCard
import com.deepak.photovault.designsystem.GlassDialog
import com.deepak.photovault.designsystem.GlassIconButton
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.glassChrome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

/**
 * Moving phone photos and videos into the Secured folder (Phase 8A, 1 Nov): asks once, encrypts and checks each copy,
 * then Android asks to delete the originals for good (not Recently deleted: that would leave a readable copy for 30
 * days). If the person says no there, the encrypted copies are dropped again and nothing has changed. When it goes
 * through, the photos are sucked into the lock button like a delete (3 Nov).
 */
@Stable
class SecuredMover(
    private val state: ShellState,
    private val gallery: GalleryState,
    private val actions: MediaActions,
    private val scope: CoroutineScope,
    private val flight: DeleteFlight,
) {
    var pending by mutableStateOf<List<MediaItem>?>(null)
    private var skipped by mutableIntStateOf(0)
    private var flying: List<FlyingPhoto> = emptyList()
    private var target: () -> Rect? = { null }
    var busy by mutableStateOf<String?>(null)

    /** [fly] says where the photos are drawn right now; [aim] where the lock button is. Both are read when asked, before anything moves. */
    fun request(items: List<MediaItem>, fly: () -> List<FlyingPhoto> = { emptyList() }, aim: () -> Rect? = { null }) {
        val sec = state.secured
        if (!sec.platform.supported) return actions.toast("The Secured folder isn't available on this device.")
        if (!sec.setUp) {
            actions.toast("Set up the Secured folder first.")
            state.open(Overlay.Secured)
            return
        }
        val eligible = items.filter { !it.isDriveOnly }
        skipped = items.size - eligible.size
        if (eligible.isEmpty()) return actions.toast("Only items stored on this phone can be moved in.")
        flying = fly()
        target = aim
        pending = eligible
    }

    private fun confirm(items: List<MediaItem>) {
        pending = null
        val sec = state.secured
        val flyingNow = flying
        val aim = target
        scope.launch {
            busy = "Encrypting 1 of ${items.size}..."
            val stored = try {
                sec.platform.moveIn(items) { i, n -> busy = "Encrypting ${i + 1} of $n..." }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            } finally {
                busy = null
            }
            if (stored.isEmpty()) return@launch actions.toast("Couldn't move them. Nothing was changed.")
            val originals = stored.map { it.item }
            actions.deleteForever(originals) { agreed ->
                if (agreed) {
                    val goneIds = originals.map { it.id }.toSet()
                    flight.launch(flyingNow.filter { f -> originals.any { it.uri == f.uri } }, aim())
                    gallery.dropNow(goneIds)
                    state.selected = emptySet()
                    val left = items.size - stored.size
                    actions.toast(
                        (if (stored.size == 1) "Moved 1 item to the Secured folder." else "Moved ${stored.size} items to the Secured folder.") +
                            if (left > 0) " $left couldn't be moved and stay in your gallery." else "",
                    )
                } else {
                    scope.launch {
                        sec.platform.remove(stored.map { it.id })
                        actions.toast("Nothing was moved. Your photos are still in the gallery.")
                    }
                }
            }
        }
    }

    @Composable
    fun Dialogs() {
        pending?.let { items ->
            ConfirmDialog(
                title = if (items.size == 1) "Move 1 item to the Secured folder?" else "Move ${items.size} items to the Secured folder?",
                body = "They are encrypted on this phone and checked. Then Android asks to delete the originals for good, so they leave your gallery. " +
                    "Copies already backed up to the family drive stay there." +
                    if (skipped > 0) " $skipped selected item(s) that are only on the drive are left out." else "",
                confirm = "Move",
                onDismiss = { pending = null },
                onConfirm = { confirm(items) },
            )
        }
        busy?.let { text ->
            GlassDialog(title = "Moving to the Secured folder", onDismiss = {}, buttons = {}) {
                Text(text, color = LocalGlassTheme.current.onGlass, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun rememberSecuredMover(state: ShellState, gallery: GalleryState, actions: MediaActions): SecuredMover {
    val scope = rememberCoroutineScope()
    val flight = LocalDeleteFlight.current
    return remember(state, gallery, actions, flight) { SecuredMover(state, gallery, actions, scope, flight) }
}

private val NO_FINGER = Offset(Float.NaN, Float.NaN)

/** Inside the folder: the locked photos and videos, selection (hold and slide too), a viewer, Share, Move out, Delete, Change PIN. */
@Composable
internal fun SecuredFolderContent(state: ShellState, sec: SecuredState) {
    val theme = LocalGlassTheme.current
    val platform = sec.platform
    val scope = rememberCoroutineScope()
    val flight = LocalDeleteFlight.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    var items by remember { mutableStateOf<List<SecuredItem>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var changingPin by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<List<String>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val thumbs = remember { mutableStateMapOf<String, ByteArray>() }
    val tileBounds = remember { HashMap<String, Rect>() }
    val bin = remember { ScreenSpot() }
    val gridState = rememberLazyGridState()
    LaunchedEffect(reload) {
        val found = platform.items()
        items = found
        failed = found == null
    }
    DisposableEffect(Unit) {
        onDispose {
            thumbs.clear()
            platform.clearTemp()
        }
    }

    if (changingPin) {
        SecuredChangePin(sec, onDone = { message -> changingPin = false; if (message != null) note = message })
        return
    }

    val list = items.orEmpty()
    val ids = remember(list) { list.map { it.id } }
    val indexOf = remember(ids) { ids.withIndex().associate { (i, id) -> id to i } }
    val selecting = sec.selected.isNotEmpty()
    val headerCount = (if (note != null) 1 else 0) + (if (list.isEmpty()) 1 else 0)
    val zone = remember { TimeZone.currentSystemDefault() }

    fun moveOut(moving: List<String>) {
        scope.launch {
            busy = "Moving out..."
            val n = platform.moveOut(moving)
            busy = null
            sec.selected = emptySet()
            sec.viewing = null
            note = when {
                n == moving.size -> if (n == 1) "Moved 1 item back to its album." else "Moved $n items back to their albums."
                n == 0 -> "Couldn't move them out. Nothing was changed."
                else -> "Moved $n of ${moving.size} out. The rest are still here."
            }
            reload++
        }
    }

    fun share(which: List<String>) {
        scope.launch { if (!platform.share(which)) note = "Couldn't share that." }
    }

    /** Deletes for good, with the genie effect from where each photo is drawn ([flying]) into the delete button. */
    fun deleteNow(which: List<String>, flying: List<FlyingPhoto>) {
        scope.launch {
            flight.launch(flying, bin.bounds)
            platform.remove(which)
            sec.selected = emptySet()
            sec.viewing = null
            note = if (which.size == 1) "Deleted 1 item." else "Deleted ${which.size} items."
            reload++
        }
    }

    fun tileFlying(which: List<String>): List<FlyingPhoto> = which.mapNotNull { id ->
        val rect = tileBounds[id] ?: return@mapNotNull null
        val bytes = thumbs[id] ?: return@mapNotNull null
        FlyingPhoto("secured:$id", false, rect, bytes = bytes)
    }

    // Hold a tile, then slide to select many; sliding near the top or bottom scrolls the page (same as Photos).
    val handler = remember(gridState, ids) {
        object : PhotoGridGestureHandler {
            private var anchor = -1
            private var adding = true
            private var initial: Set<Long> = emptySet()
            private var finger = NO_FINGER
            private var autoScroll: Job? = null
            private val order = ids.indices.map { it.toLong() }

            private fun asIndices(picked: Set<String>): Set<Long> = picked.mapNotNull { indexOf[it]?.toLong() }.toSet()

            private fun write(chosen: Set<Long>) {
                sec.selected = chosen.mapNotNull { ids.getOrNull(it.toInt()) }.toSet()
            }

            override fun photoAt(position: Offset): Long? {
                val info = gridState.layoutInfo
                val gap = with(density) { 2.dp.toPx() }
                val hit = info.visibleItemsInfo.firstOrNull { item ->
                    val top = item.offset.y - info.viewportStartOffset
                    position.x >= item.offset.x && position.x < item.offset.x + item.size.width + gap &&
                        position.y >= top && position.y < top + item.size.height + gap
                } ?: return null
                return indexOf[hit.key as? String]?.toLong()
            }

            override fun onTap(id: Long) {
                val tapped = ids.getOrNull(id.toInt()) ?: return
                if (sec.selected.isNotEmpty()) sec.selected = if (tapped in sec.selected) sec.selected - tapped else sec.selected + tapped
                else sec.viewing = tapped
            }

            override fun onDragSelectStart(id: Long) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                initial = asIndices(sec.selected)
                anchor = id.toInt()
                adding = id !in initial
                write(DragSelect.selection(initial, order, anchor, anchor, adding))
                autoScroll = scope.launch {
                    val zone = with(density) { 96.dp.toPx() }
                    val maxSpeed = with(density) { 22.dp.toPx() }
                    while (isActive) {
                        val height = gridState.layoutInfo.viewportSize.height.toFloat()
                        val speed = DragSelect.edgeSpeed(finger.y, 0f, height, zone, maxSpeed)
                        if (speed != 0f) {
                            gridState.scrollBy(speed)
                            onDragSelectMove(finger)
                        }
                        androidx.compose.runtime.withFrameNanos { }
                    }
                }
            }

            override fun onDragSelectMove(position: Offset) {
                finger = position
                val id = photoAt(position) ?: return
                write(DragSelect.selection(initial, order, anchor, id.toInt(), adding))
            }

            override fun onDragSelectEnd() {
                autoScroll?.cancel()
                autoScroll = null
                finger = NO_FINGER
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        GlassScreen(
            title = if (selecting) "${sec.selected.size} selected" else "Secured folder",
            subtitle = if (selecting) null else if (items == null) null else if (list.size == 1) "1 item" else "${list.size} items",
            onBack = if (selecting) null else ({ state.back() }),
            pinned = selecting,
            actions = {
                if (selecting) {
                    val every = ids.toSet()
                    val all = every.isNotEmpty() && sec.selected.containsAll(every)
                    GlassIconButton(
                        if (all) Icons.Rounded.Deselect else Icons.Rounded.SelectAll,
                        if (all) "Deselect all" else "Select all",
                        onClick = { sec.selected = if (all) emptySet() else every },
                    )
                    GlassIconButton(Icons.Rounded.Close, "Cancel selection", onClick = { sec.selected = emptySet() })
                } else {
                    GlassIconButton(Icons.Rounded.Pin, "Change PIN", onClick = { changingPin = true })
                    GlassIconButton(Icons.Rounded.Lock, "Lock now", onClick = { state.back() })
                }
            },
            bottomActions = if (!selecting) null else ({
                GlassIconButton(Icons.Rounded.Share, "Share", onClick = { share(sec.selected.toList()) })
                GlassIconButton(Icons.Rounded.LockOpen, "Move out of the Secured folder", onClick = { moveOut(sec.selected.toList()) })
                GlassIconButton(Icons.Rounded.Delete, "Delete for good", modifier = Modifier.trackSpot(bin), onClick = { confirmDelete = sec.selected.toList() })
            }),
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().photoGridGestures(gridState, handler),
                    contentPadding = PaddingValues(
                        start = 2.dp, end = 2.dp,
                        top = padding.calculateTopPadding(),
                        bottom = padding.calculateBottomPadding(),
                    ),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    note?.let { text ->
                        item(key = "note", span = { GridItemSpan(maxLineSpan) }) {
                            GlassCard(Modifier.fillMaxWidth().padding(horizontal = SCREEN_MARGIN - 2.dp, vertical = 4.dp)) {
                                Text(text, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (list.isEmpty()) {
                        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                            GlassCard(Modifier.fillMaxWidth().padding(horizontal = SCREEN_MARGIN - 2.dp, vertical = 4.dp)) {
                                Text(
                                    if (failed) "The list of locked items can't be opened." else if (items == null) "Opening..." else "Nothing here yet",
                                    color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                )
                                if (!failed && items != null) Text(
                                    "In Photos or an album, select photos or videos and tap the lock button to move them here. Only you can open them, with your PIN or fingerprint.",
                                    color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                    items(list, key = { it.id }) { item ->
                        val picked = item.id in sec.selected
                        LaunchedEffect(item.id) {
                            if (thumbs[item.id] == null) platform.thumbnail(item.id)?.let { thumbs[item.id] = it }
                        }
                        DisposableEffect(item.id) { onDispose { tileBounds.remove(item.id) } }
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .background(theme.onGlass.copy(alpha = 0.08f))
                                .onGloballyPositioned { tileBounds[item.id] = it.boundsInRoot() },
                        ) {
                            thumbs[item.id]?.let { bytes ->
                                AsyncImage(model = bytes, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            if (item.isVideo) {
                                Icon(Icons.Rounded.PlayArrow, "Video", tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(20.dp))
                            }
                            if (picked) {
                                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                                Icon(Icons.Rounded.CheckCircle, "Selected", tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp))
                            }
                        }
                    }
                }
                // The slider on the right edge and the back-to-top button: both stay hidden until the page is scrolled.
                val starts = remember(list, headerCount) {
                    var last = ""
                    val out = mutableListOf<Pair<Int, String>>()
                    list.forEachIndexed { i, item ->
                        val d = Timeline.localDate(item.takenAtMillis, zone)
                        val label = "${Timeline.monthName(d.month)} ${d.year}"
                        if (label != last) { out += (headerCount + i) to label; last = label }
                    }
                    out
                }
                FastScroller(
                    grid = gridState,
                    starts = starts,
                    total = headerCount + list.size,
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding(),
                    modifier = Modifier.align(Alignment.TopEnd),
                )
                BackToTopButton(
                    grid = gridState,
                    onClick = { scope.launch { gridState.animateScrollToItem(0) } },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = padding.calculateBottomPadding() + 14.dp),
                )
            }
        }
        val open = sec.viewing
        if (open != null && list.any { it.id == open }) {
            SecuredViewer(
                list, open, platform, thumbs, bin,
                onShare = { id -> share(listOf(id)) },
                onMoveOut = { id -> moveOut(listOf(id)) },
                onDelete = { id -> confirmDelete = listOf(id) },
            )
        }
    }

    confirmDelete?.let { which ->
        ConfirmDialog(
            title = if (which.size == 1) "Delete 1 item for good?" else "Delete ${which.size} items for good?",
            body = "They are removed from the Secured folder and can't be brought back.",
            confirm = "Delete",
            onDismiss = { confirmDelete = null },
            onConfirm = {
                confirmDelete = null
                deleteNow(which, tileFlying(which))
            },
        )
    }
    busy?.let { text ->
        GlassDialog(title = "Secured folder", onDismiss = {}, buttons = {}) {
            Text(text, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * A locked photo or video full screen on black, like the main viewer (3 Nov): swipe between items, pinch or double-tap to
 * zoom, tap to show or hide the strip and buttons, a strip of only the Secured folder's items, and the same bottom
 * pill with Share, Move out and Delete. No back arrow: the back gesture closes it.
 */
@Composable
private fun SecuredViewer(
    items: List<SecuredItem>,
    startId: String,
    platform: SecuredPlatform,
    thumbs: Map<String, ByteArray>,
    bin: ScreenSpot,
    onShare: (String) -> Unit,
    onMoveOut: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val pager = rememberPagerState(initialPage = items.indexOfFirst { it.id == startId }.coerceAtLeast(0)) { items.size }
    var zoomed by remember { mutableStateOf(false) }
    var barsVisible by remember { mutableStateOf(true) }
    SystemBarsHidden(hidden = !barsVisible)
    val liveSystem = WindowInsets.systemBars.asPaddingValues()
    var system by remember { mutableStateOf(liveSystem) }
    if (liveSystem.calculateTopPadding() >= system.calculateTopPadding() && liveSystem.calculateBottomPadding() >= system.calculateBottomPadding()) system = liveSystem
    val current = items[pager.currentPage.coerceIn(items.indices)]
    val video = remember(current.id) { VideoControls() }
    var videoStarted by remember(current.id) { mutableStateOf(false) }
    val keys = remember(items) { items.map { it.id } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, key = { items[it].id }) { page ->
            val item = items[page]
            val onTap = { barsVisible = !barsVisible }
            if (item.isVideo) {
                val isCurrent = page == pager.currentPage
                SecuredVideoPage(
                    item, platform, isCurrent, if (isCurrent) video else null, thumbs[item.id], onTap,
                    onStarted = { if (isCurrent) { videoStarted = true; barsVisible = true } },
                )
            } else {
                SecuredPhotoPage(item.id, platform, onTap = onTap, onZoom = { zoomed = it })
            }
        }
        AnimatedVisibility(
            visible = barsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                    .padding(top = 24.dp, bottom = system.calculateBottomPadding() + 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (videoStarted && current.isVideo) VideoBar(video, onTouch = {})
                if (items.size > 1) {
                    FilmstripCore(keys, pager, onCurrentClick = null, label = { "Show item ${it + 1}" }) { index ->
                        thumbs[items[index].id]?.let { bytes ->
                            AsyncImage(model = bytes, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
                Row(
                    Modifier.glassChrome(RoundedCornerShape(percent = 50), elevation = 8.dp).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    GlassIconButton(Icons.Rounded.Share, "Share", onClick = { onShare(current.id) })
                    GlassIconButton(Icons.Rounded.LockOpen, "Move out of the Secured folder", onClick = { onMoveOut(current.id) })
                    GlassIconButton(Icons.Rounded.Delete, "Delete for good", modifier = Modifier.trackSpot(bin), onClick = { onDelete(current.id) })
                }
            }
        }
    }
}

@Composable
private fun SecuredPhotoPage(id: String, platform: SecuredPlatform, onTap: () -> Unit, onZoom: (Boolean) -> Unit) {
    var bytes by remember(id) { mutableStateOf<ByteArray?>(null) }
    var scale by remember(id) { mutableFloatStateOf(1f) }
    var offset by remember(id) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(id) { bytes = platform.photo(id) }
    DisposableEffect(id) { onDispose { onZoom(false) } }
    fun keepInside(o: Offset, s: Float): Offset {
        val mx = size.width * (s - 1f) / 2f
        val my = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    val data = bytes
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero; onZoom(false) } else { scale = 2.5f; onZoom(true) }
                    },
                )
            }
            // Two fingers pinch and move the photo; one finger moves it only while zoomed. At normal size one finger is left
            // alone so the pager can swipe to the next item.
            .pointerInput(id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2 || scale > 1f) {
                            val next = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            scale = next
                            offset = if (next <= 1f) Offset.Zero else keepInside(offset + event.calculatePan(), next)
                            onZoom(next > 1f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (data != null) {
            AsyncImage(
                model = data,
                contentDescription = "Locked photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            )
        }
    }
}

/** A locked video: its still picture and a play button; pressing play decrypts it to a short-lived file and plays that. */
@Composable
private fun SecuredVideoPage(
    item: SecuredItem,
    platform: SecuredPlatform,
    isCurrent: Boolean,
    controls: VideoControls?,
    poster: ByteArray?,
    onTap: () -> Unit,
    onStarted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var started by remember(item.id) { mutableStateOf(false) }
    var uri by remember(item.id) { mutableStateOf<String?>(null) }
    var preparing by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(isCurrent) { if (!isCurrent) started = false }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val playUri = uri
        if (started && controls != null && playUri != null) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val aspect = controls.aspect
                val screen = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else 1f
                val frame = if (aspect > 0f) Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = aspect < screen) else Modifier.fillMaxSize()
                Box(frame) {
                    VideoPlayer(playUri, controls, Modifier.fillMaxSize().graphicsLayer { alpha = if (aspect > 0f) 1f else 0f })
                    if ((!controls.firstFrame || aspect <= 0f) && poster != null) {
                        AsyncImage(model = poster, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) })
        } else {
            if (poster != null) {
                AsyncImage(model = poster, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().clickable(onClick = onTap))
            }
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(role = Role.Button, onClick = {
                        if (preparing) return@clickable
                        preparing = true
                        scope.launch {
                            uri = platform.videoFile(item.id)
                            preparing = false
                            if (uri != null) {
                                controls?.firstFrame = false
                                controls?.playing = true
                                started = true
                                onStarted()
                            }
                        }
                    })
                    .semantics { contentDescription = "Play video, ${Timeline.duration(item.durationMillis)}" },
                contentAlignment = Alignment.Center,
            ) {
                if (preparing) Text("...", color = Color.White, style = MaterialTheme.typography.titleLarge)
                else Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(44.dp))
            }
        }
    }
}

/** Change PIN: the current one, then the new one twice. */
@Composable
private fun SecuredChangePin(sec: SecuredState, onDone: (String?) -> Unit) {
    val theme = LocalGlassTheme.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var old by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    GlassListScreen(title = "Secured folder", subtitle = "Change PIN", onBack = { onDone(null) }) {
        item {
            GlassCard(Modifier.cardWidth()) {
                Text(
                    when (step) { 0 -> "Type your current PIN"; 1 -> "Choose a new PIN"; else -> "Type the new PIN again" },
                    color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                )
                PinEntry(pin, onChange = { next ->
                    if (working) return@PinEntry
                    pin = next
                    if (next.length == SecuredRules.PIN_LENGTH) {
                        pin = ""
                        when (step) {
                            0 -> { old = next; message = null; step = 1 }
                            1 -> {
                                val bad = SecuredRules.pinProblem(next)
                                if (bad != null) message = bad else { first = next; message = null; step = 2 }
                            }
                            else -> if (next != first) {
                                message = "The two PINs are different. Choose the new PIN again."
                                step = 1
                            } else scope.launch {
                                working = true
                                val problem = sec.platform.changePin(old, next)
                                working = false
                                if (problem == null) onDone("Your PIN is changed.") else { message = problem; step = 0 }
                            }
                        }
                    }
                })
                message?.let { Text(it, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
            }
        }
    }
}
