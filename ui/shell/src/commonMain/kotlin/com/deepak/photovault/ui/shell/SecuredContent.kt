package com.deepak.photovault.ui.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.deepak.photovault.core.backup.isDriveOnly
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.designsystem.GlassCard
import com.deepak.photovault.designsystem.GlassDialog
import com.deepak.photovault.designsystem.GlassIconButton
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.glassChrome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Moving phone photos into the Secured folder (Phase 8A slice 2, 1 Nov): asks once, encrypts and checks each copy,
 * then Android asks to delete the originals for good (not Recently deleted: that would leave a readable copy for 30
 * days). If the person says no there, the encrypted copies are dropped again and nothing has changed.
 */
@Stable
class SecuredMover(
    private val state: ShellState,
    private val gallery: GalleryState,
    private val actions: MediaActions,
    private val scope: CoroutineScope,
) {
    var pending by mutableStateOf<List<MediaItem>?>(null)
    private var skipped by mutableIntStateOf(0)
    var busy by mutableStateOf<String?>(null)

    fun request(items: List<MediaItem>) {
        val sec = state.secured
        if (!sec.platform.supported) return actions.toast("The Secured folder isn't available on this device.")
        if (!sec.setUp) {
            actions.toast("Set up the Secured folder first.")
            state.open(Overlay.Secured)
            return
        }
        val eligible = items.filter { !it.isVideo && !it.isDriveOnly }
        skipped = items.size - eligible.size
        if (eligible.isEmpty()) return actions.toast("Only photos stored on this phone can be moved in for now. Videos come later.")
        pending = eligible
    }

    private fun confirm(items: List<MediaItem>) {
        pending = null
        val sec = state.secured
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
                    gallery.dropNow(originals.map { it.id }.toSet())
                    state.selected = emptySet()
                    val left = items.size - stored.size
                    actions.toast(
                        (if (stored.size == 1) "Moved 1 photo to the Secured folder." else "Moved ${stored.size} photos to the Secured folder.") +
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
                title = if (items.size == 1) "Move 1 photo to the Secured folder?" else "Move ${items.size} photos to the Secured folder?",
                body = "They are encrypted on this phone and checked. Then Android asks to delete the originals for good, so they leave your gallery. " +
                    "Copies already backed up to the family drive stay there." +
                    if (skipped > 0) " $skipped selected item(s) can't be moved yet (videos or drive-only photos) and are left out." else "",
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
    return remember(state, gallery, actions) { SecuredMover(state, gallery, actions, scope) }
}

/** Inside the folder: the locked photos, selection, a viewer, Move out, Delete, Change PIN. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SecuredFolderContent(state: ShellState, sec: SecuredState) {
    val theme = LocalGlassTheme.current
    val platform = sec.platform
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<SecuredItem>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var changingPin by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<List<String>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val thumbs = remember { mutableStateMapOf<String, ByteArray>() }
    val gridState = rememberLazyGridState()
    LaunchedEffect(reload) {
        val found = platform.items()
        items = found
        failed = found == null
    }
    DisposableEffect(Unit) { onDispose { thumbs.clear() } }

    if (changingPin) {
        SecuredChangePin(sec, onDone = { message -> changingPin = false; if (message != null) note = message })
        return
    }

    val list = items.orEmpty()
    val selecting = sec.selected.isNotEmpty()
    fun moveOut(ids: List<String>) {
        scope.launch {
            busy = "Moving out..."
            val n = platform.moveOut(ids)
            busy = null
            sec.selected = emptySet()
            sec.viewing = null
            note = when {
                n == ids.size -> if (n == 1) "Moved 1 photo back to your gallery (Pictures/Pixallery)." else "Moved $n photos back to your gallery (Pictures/Pixallery)."
                n == 0 -> "Couldn't move them out. Nothing was changed."
                else -> "Moved $n of ${ids.size} out. The rest are still here."
            }
            reload++
        }
    }

    Box(Modifier.fillMaxSize()) {
        GlassScreen(
            title = if (selecting) "${sec.selected.size} selected" else "Secured folder",
            subtitle = if (selecting) null else if (items == null) null else if (list.size == 1) "1 photo" else "${list.size} photos",
            onBack = if (selecting) null else ({ state.back() }),
            pinned = selecting,
            actions = {
                if (selecting) {
                    val every = list.map { it.id }.toSet()
                    val all = every.isNotEmpty() && sec.selected.containsAll(every)
                    GlassIconButton(
                        if (all) Icons.Rounded.Deselect else Icons.Rounded.SelectAll,
                        if (all) "Deselect all" else "Select all",
                        onClick = { sec.selected = if (all) emptySet() else every },
                    )
                    GlassIconButton(Icons.Rounded.Close, "Cancel selection", onClick = { sec.selected = emptySet() })
                } else {
                    GlassIconButton(Icons.Rounded.Pin, "Change PIN", onClick = { changingPin = true })
                    GlassIconButton(Icons.Rounded.Lock, "Lock now", onClick = { sec.lock(); state.back() })
                }
            },
            bottomActions = if (!selecting) null else ({
                GlassIconButton(Icons.Rounded.LockOpen, "Move out of the Secured folder", onClick = { moveOut(sec.selected.toList()) })
                GlassIconButton(Icons.Rounded.Delete, "Delete for good", onClick = { confirmDelete = sec.selected.toList() })
            }),
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
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
                    if (items == null || list.isEmpty()) {
                        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                            GlassCard(Modifier.fillMaxWidth().padding(horizontal = SCREEN_MARGIN - 2.dp, vertical = 4.dp)) {
                                Text(
                                    if (failed) "The list of locked photos can't be opened." else if (items == null) "Opening..." else "Nothing here yet",
                                    color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                )
                                if (!failed && items != null) Text(
                                    "In Photos or an album, select photos and tap the lock button to move them here. Only you can open them, with your PIN or fingerprint.",
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
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .background(theme.onGlass.copy(alpha = 0.08f))
                                .combinedClickable(
                                    onClick = {
                                        if (selecting) sec.selected = if (picked) sec.selected - item.id else sec.selected + item.id
                                        else sec.viewing = item.id
                                    },
                                    onLongClick = { sec.selected = if (picked) sec.selected - item.id else sec.selected + item.id },
                                ),
                        ) {
                            thumbs[item.id]?.let { bytes ->
                                AsyncImage(model = bytes, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            if (picked) {
                                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                                Icon(Icons.Rounded.CheckCircle, "Selected", tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp))
                            }
                        }
                    }
                }
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
                list, open, platform,
                onClose = { sec.viewing = null },
                onMoveOut = { id -> moveOut(listOf(id)) },
                onDelete = { id -> confirmDelete = listOf(id) },
            )
        }
    }

    confirmDelete?.let { ids ->
        ConfirmDialog(
            title = if (ids.size == 1) "Delete 1 photo for good?" else "Delete ${ids.size} photos for good?",
            body = "They are removed from the Secured folder and can't be brought back.",
            confirm = "Delete",
            onDismiss = { confirmDelete = null },
            onConfirm = {
                confirmDelete = null
                scope.launch {
                    platform.remove(ids)
                    sec.selected = emptySet()
                    sec.viewing = null
                    note = if (ids.size == 1) "Deleted 1 photo." else "Deleted ${ids.size} photos."
                    reload++
                }
            },
        )
    }
    busy?.let { text ->
        GlassDialog(title = "Secured folder", onDismiss = {}, buttons = {}) {
            Text(text, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A locked photo full screen on black: swipe between photos, double-tap to zoom (and drag when zoomed). */
@Composable
private fun SecuredViewer(
    items: List<SecuredItem>,
    startId: String,
    platform: SecuredPlatform,
    onClose: () -> Unit,
    onMoveOut: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val pager = rememberPagerState(initialPage = items.indexOfFirst { it.id == startId }.coerceAtLeast(0)) { items.size }
    var zoomed by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, key = { items[it].id }) { page ->
            SecuredPhotoPage(items[page].id, platform, onZoom = { zoomed = it })
        }
        Box(Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.systemBars).padding(12.dp)) {
            Row(Modifier.glassChrome(RoundedCornerShape(percent = 50), elevation = 8.dp).padding(6.dp)) {
                GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onClose)
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.systemBars).padding(16.dp)) {
            Row(
                Modifier.glassChrome(RoundedCornerShape(percent = 50), elevation = 8.dp).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassIconButton(Icons.Rounded.LockOpen, "Move out of the Secured folder", onClick = { items.getOrNull(pager.currentPage)?.let { onMoveOut(it.id) } })
                GlassIconButton(Icons.Rounded.Delete, "Delete for good", onClick = { items.getOrNull(pager.currentPage)?.let { onDelete(it.id) } })
            }
        }
    }
}

@Composable
private fun SecuredPhotoPage(id: String, platform: SecuredPlatform, onZoom: (Boolean) -> Unit) {
    var bytes by remember(id) { mutableStateOf<ByteArray?>(null) }
    var scale by remember(id) { mutableStateOf(1f) }
    var offset by remember(id) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(id) { bytes = platform.photo(id) }
    DisposableEffect(id) { onDispose { onZoom(false) } }
    val data = bytes
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(id) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero; onZoom(false) } else { scale = 2.5f; onZoom(true) }
                })
            }
            .then(
                if (scale > 1f) Modifier.pointerInput(id) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        offset += drag
                    }
                } else Modifier,
            ),
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
