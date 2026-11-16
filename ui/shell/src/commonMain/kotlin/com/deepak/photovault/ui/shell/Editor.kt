package com.deepak.photovault.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Rotate90DegreesCcw
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.deepak.photovault.core.media.CropAspect
import com.deepak.photovault.core.media.CropBox
import com.deepak.photovault.core.media.CropCorner
import com.deepak.photovault.core.media.EditAdjust
import com.deepak.photovault.core.media.EditFilter
import com.deepak.photovault.core.media.EditMath
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.PhotoEdit
import com.deepak.photovault.designsystem.rememberHaptics
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The photo editor (Phase 11, part 1, 15 Nov): crop, turn, mirror, light and colour, and ready-made looks, for
 * photos on this phone. It always saves a new copy next to the original; the original is never changed.
 *
 * The page only draws: the sums are in core:media ([PhotoEdit], [EditMath], unit tested) and the phone's part
 * (opening the photo, saving the copy with its date, place and camera details) is behind [EditorPlatform].
 */
interface EditorPlatform {
    val supported: Boolean get() = false

    /** The photo the right way up, small enough to edit smoothly (the saved copy is made from the full photo). */
    suspend fun open(item: MediaItem): ImageBitmap? = null

    /** [base] turned by [turns] quarter turns clockwise, then mirrored left to right when [flipped]. */
    suspend fun turned(base: ImageBitmap, turns: Int, flipped: Boolean): ImageBitmap = base

    /**
     * [image] with the edit's pixel-by-pixel changes (highlights, shadows, vibrance, sharpness, vignette, grain;
     * 15 Nov), for the preview. The colour matrix isn't in it: the screen draws that on top, live.
     */
    suspend fun pixels(image: ImageBitmap, edit: PhotoEdit): ImageBitmap = image

    /** Saves [edit] of [item] as a new photo beside the original. Never writes to the original. */
    suspend fun save(item: MediaItem, edit: PhotoEdit): EditSaved = EditSaved(false, "Editing isn't available here.")

    /** [image] tilted by [degrees] (Straighten, 16 Nov), enlarged so no empty corners show; same size as [image]. */
    suspend fun straightened(image: ImageBitmap, degrees: Float): ImageBitmap = image

    /** Videos can be trimmed and muted (16 Nov). */
    val trimSupported: Boolean get() = false

    /** Saves the part of [item] from [startMs] to [endMs] as a new video beside it, without sound when [mute]. */
    suspend fun trim(item: MediaItem, startMs: Long, endMs: Long, mute: Boolean): EditSaved = EditSaved(false, "Trimming isn't available here.")
}

/** How saving went, in words to show. */
class EditSaved(val ok: Boolean, val message: String)

@Stable
class EditorState(val platform: EditorPlatform = object : EditorPlatform {}) {
    /** The open edit has changes that would be lost: the back gesture asks first. */
    var changed by mutableStateOf(false)

    /** "Discard your changes?" is showing. */
    var askDiscard by mutableStateOf(false)
}

private enum class EditTab(val label: String, val icon: ImageVector) {
    CROP("Crop", Icons.Rounded.Crop),
    ADJUST("Adjust", Icons.Rounded.Tune),
    FILTERS("Filters", Icons.Rounded.AutoAwesome),
    // 15 Nov: the living photo moved here from the viewer.
    MOTION("Motion", Icons.Rounded.Animation),
}

private val Dim = Color.White.copy(alpha = 0.62f)

// Shared with the video page (16 Nov).
internal val EditorDim get() = Dim
internal val EditorPanel get() = Panel

@Composable
internal fun EditorChoice(text: String, selected: Boolean, onClick: () -> Unit) = Choice(text, selected, onClick)

/** Undo goes back this many steps at most. */
private const val MAX_STEPS = 60
private val Panel = Color(0xFF1C1C21)

@Composable
fun EditorScreen(photoId: Long, state: ShellState, gallery: GalleryState, actions: MediaActions) {
    val editor = state.editor
    // Held for the whole visit: the library reloads when the copy is saved, and the page must not lose its photo.
    val item = remember(photoId) { gallery.find(photoId) }
    // Videos get their own page: trim and sound (16 Nov).
    if (item != null && item.isVideo) {
        VideoTrimScreen(item, state, actions)
        return
    }
    val scope = rememberCoroutineScope()
    var base by remember { mutableStateOf<ImageBitmap?>(null) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(PhotoEdit()) }
    var tab by remember { mutableStateOf(EditTab.CROP) }
    var aspect by remember { mutableStateOf(CropAspect.FREE) }
    var adjust by remember { mutableStateOf(EditAdjust.BRIGHTNESS) }
    var saving by remember { mutableStateOf(false) }
    var turning by remember { mutableStateOf(false) }
    // The pixel-by-pixel part of the edit on the preview (15 Nov); the colour matrix goes on top, live.
    var worked by remember { mutableStateOf<ImageBitmap?>(null) }
    // Holding Compare (or the photo): the original shows.
    var showOriginal by remember { mutableStateOf(false) }
    // Motion (15 Nov): on or off, the lifted layers once made, and those layers with the edit.
    val livingOn = state.living.platform.supported
    var motion by remember { mutableStateOf(false) }
    var layers by remember { mutableStateOf<LivingLayers?>(null) }
    var making by remember { mutableStateOf(false) }
    var styled by remember { mutableStateOf<LivingLayers?>(null) }
    var videoProgress by remember { mutableStateOf<Float?>(null) }
    // Straighten (16 Nov): the picture tilted once the slider rests, with the tilt and the picture it was made from.
    var straight by remember { mutableStateOf<Triple<ImageBitmap, Float, ImageBitmap>?>(null) }
    var workedFrom by remember { mutableStateOf<ImageBitmap?>(null) }
    // Undo and redo (16 Nov): each resting state of the edit, and where we are in that list.
    val history = remember { mutableStateListOf(PhotoEdit()) }
    var at by remember { mutableIntStateOf(0) }

    LaunchedEffect(item) {
        val opened = item?.let { runCatching { editor.platform.open(it) }.getOrNull() }
        base = opened
        image = opened
        failed = opened == null
    }
    LaunchedEffect(edit, motion) { editor.changed = !edit.isUnchanged || motion }
    // Straighten: the tilted picture is made a moment after the slider rests; until then the screen tilts it live.
    LaunchedEffect(image, edit.straighten) {
        val shown = image ?: return@LaunchedEffect
        val angle = edit.straighten
        if (!edit.isTilted) return@LaunchedEffect
        val made = straight
        if (made != null && made.second == angle && made.third === shown) return@LaunchedEffect
        kotlinx.coroutines.delay(90)
        runCatching { editor.platform.straightened(shown, angle) }.getOrNull()?.let { straight = Triple(it, angle, shown) }
    }
    // The picture the rest of the edit works on: as turned, or tilted when Straighten is used (null while making it).
    val source: ImageBitmap? = image?.let { shown ->
        if (!edit.isTilted) shown
        else straight?.takeIf { it.second == edit.straighten && it.third === shown }?.first
    }
    // The pixel-by-pixel part, a moment after a slider stops moving (it takes a little while on a big preview).
    val pixelKey = listOf(edit.highlights, edit.shadows, edit.vibrance, edit.sharpness, edit.vignetteAmount, edit.grainAmount, edit.crop)
    LaunchedEffect(source, pixelKey) {
        val from = source ?: return@LaunchedEffect
        if (!edit.needsPixels) {
            worked = null
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(60)
        runCatching { editor.platform.pixels(from, edit) }.getOrNull()?.let {
            worked = it
            workedFrom = from
        }
    }
    // A new step in the history once the edit has rested for a moment (a slider drag is one step, not hundreds).
    LaunchedEffect(edit) {
        kotlinx.coroutines.delay(350)
        if (edit != history[at]) {
            while (history.size > at + 1) history.removeAt(history.size - 1)
            history.add(edit)
            if (history.size > MAX_STEPS) history.removeAt(0)
            at = history.size - 1
        }
    }
    // Motion: the subject is lifted out once, the first time Motion is turned on.
    LaunchedEffect(motion) {
        val photo = item ?: return@LaunchedEffect
        if (!motion || layers != null || making) return@LaunchedEffect
        making = true
        layers = runCatching { state.living.platform.make(photo) }.getOrElse { LivingLayers(null, null, note = "Couldn't make this one move.") }
        making = false
    }
    // The lifted layers turned, cropped and with the pixel-by-pixel changes; colours are added when drawn.
    LaunchedEffect(layers, motion, edit.turns, edit.flipped, edit.crop, edit.straighten, pixelKey) {
        val l = layers
        if (!motion || l == null || l.background == null) {
            styled = null
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(120)
        styled = runCatching { state.living.platform.styled(l, edit) }.getOrNull() ?: l
    }
    DisposableEffect(Unit) {
        onDispose {
            editor.changed = false
            editor.askDiscard = false
        }
    }

    fun leave() {
        editor.changed = false
        editor.askDiscard = false
        state.back()
    }

    /** Turning and mirroring: the picture and its frame change together, so the frame never sits on the old picture. */
    fun reshape(next: PhotoEdit): Boolean {
        val from = base ?: return false
        if (turning || saving) return false
        turning = true
        scope.launch {
            val turned = runCatching { editor.platform.turned(from, next.turns, next.flipped) }.getOrNull()
            if (turned != null) {
                image = turned
                edit = next
                // A locked shape would now be on its side; start free again.
                aspect = CropAspect.FREE
            }
            turning = false
        }
        return true
    }

    /** Goes to an earlier or later state of the edit; a different turn or mirror needs the picture turned again. */
    fun goTo(index: Int) {
        val target = history.getOrNull(index) ?: return
        if (target.turns != edit.turns || target.flipped != edit.flipped) {
            if (!reshape(target)) return
        } else {
            edit = target
        }
        at = index
    }

    // A change still settling counts as the next step: undo takes it back first.
    val settling = edit != history[at]
    val canUndo = settling || at > 0
    val canRedo = !settling && at < history.size - 1

    fun undo() {
        if (saving) return
        if (settling) goTo(at) else goTo(at - 1)
    }

    fun redo() {
        if (saving || !canRedo) return
        goTo(at + 1)
    }

    fun pick(shape: CropAspect) {
        val shown = image ?: return
        aspect = shape
        when (shape) {
            CropAspect.FREE -> Unit
            CropAspect.ORIGINAL -> edit = edit.copy(crop = CropBox())
            else -> shape.ratio?.let { edit = edit.copy(crop = EditMath.fit(CropBox(), it, shown.width, shown.height)) }
        }
    }

    fun save() {
        val photo = item ?: return
        if (saving || edit.isUnchanged) return
        saving = true
        scope.launch {
            val saved = runCatching { editor.platform.save(photo, edit) }.getOrElse { EditSaved(false, "Couldn't save the copy.") }
            saving = false
            actions.toast(saved.message)
            if (saved.ok) leave()
        }
    }

    /** With Motion on: the moving photo as a short video, with the crop and colours (15 Nov). */
    fun saveVideo(share: Boolean) {
        val photo = item ?: return
        val l = styled ?: return
        if (saving) return
        saving = true
        videoProgress = 0f
        scope.launch {
            val said = runCatching { state.living.platform.video(photo, l, share, { videoProgress = it }, edit) }.getOrElse { "Couldn't make the video." }
            saving = false
            videoProgress = null
            said?.let(actions::toast)
            if (!share && said != null && said.startsWith("Saved")) leave()
        }
    }

    // The frame's shape while a shape is locked, in frame units (see EditMath.boxRatio).
    val lockedRatio: Float? = image?.let { shown ->
        when (aspect) {
            CropAspect.FREE -> null
            CropAspect.ORIGINAL -> 1f
            else -> aspect.ratio?.let { EditMath.boxRatio(it, shown.width, shown.height) }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            // Top: close, then Reset and Save copy once something has changed.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditorIcon(Icons.Rounded.Close, "Close without saving") {
                    if (editor.changed) editor.askDiscard = true else leave()
                }
                Spacer(Modifier.weight(1f))
                if ((!edit.isUnchanged || motion) && !saving) {
                    EditorPill("Reset", filled = false) {
                        val from = base
                        edit = PhotoEdit()
                        aspect = CropAspect.FREE
                        motion = false
                        if (from != null) image = from
                    }
                    Spacer(Modifier.width(8.dp))
                }
                if (motion) {
                    val ready = styled != null && !saving
                    if (ready) EditorIcon(Icons.Rounded.Share, "Share as a video") { saveVideo(share = true) }
                    EditorPill("Save video", filled = true, enabled = ready, onClick = { saveVideo(share = false) })
                } else {
                    EditorPill("Save copy", filled = true, enabled = !edit.isUnchanged && image != null && !saving, onClick = { save() })
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val shown = image
                val moving = styled
                when {
                    shown != null && showOriginal -> OriginalPreview(base ?: shown)
                    // Motion on: the moving photo, with the colours, everywhere but while cropping.
                    shown != null && motion && moving != null && tab != EditTab.CROP -> {
                        val matrix = remember(edit) { EditMath.colourMatrix(edit) }
                        val colour = remember(matrix) { if (edit.hasColour) ColorFilter.colorMatrix(ColorMatrix(matrix)) else null }
                        LivingPhoto(
                            moving,
                            Modifier.fillMaxSize().padding(11.dp).holdForOriginal { showOriginal = it },
                            colorFilter = colour,
                        )
                    }
                    shown != null -> EditPreview(
                        // The finished preview when it is ready; else the turned picture, tilted live on screen.
                        image = source?.let { s -> worked?.takeIf { edit.needsPixels && workedFrom === s } ?: s } ?: shown,
                        tilt = if (source == null) edit.straighten else 0f,
                        edit = edit,
                        cropping = tab == EditTab.CROP,
                        ratio = lockedRatio,
                        onCrop = { edit = edit.copy(crop = it) },
                        onHold = { showOriginal = it },
                    )
                    failed -> Text(
                        if (item == null) "This photo isn't here any more." else "This photo can't be opened for editing.",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                    else -> CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.5.dp)
                }
            }

            // Undo, hold to compare, redo (16 Nov): just above the tools, in reach of a thumb.
            if (image != null) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.clip(RoundedCornerShape(percent = 50)).background(Panel).padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        EditorIcon(Icons.AutoMirrored.Rounded.Undo, "Undo", enabled = canUndo, size = 44.dp) { undo() }
                        if (!edit.isUnchanged || motion) {
                            HoldIcon(Icons.Rounded.Compare, "Hold to see the original", size = 44.dp) { showOriginal = it }
                        }
                        EditorIcon(Icons.AutoMirrored.Rounded.Redo, "Redo", enabled = canRedo, size = 44.dp) { redo() }
                    }
                }
            }
            // The tools of the chosen tab, then the tabs.
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 132.dp),
                contentAlignment = Alignment.Center,
                label = "editTools",
            ) { showing ->
                when (showing) {
                    EditTab.CROP -> CropTools(
                        aspect = aspect,
                        straighten = edit.straighten,
                        onStraighten = { edit = edit.straightened(it) },
                        onAspect = { pick(it) },
                        onLeft = { reshape(edit.turnedLeft()) },
                        onRight = { reshape(edit.turnedRight()) },
                        onMirror = { reshape(edit.mirrored()) },
                    )
                    EditTab.ADJUST -> AdjustTools(
                        edit,
                        adjust,
                        // Tapping the slider that is already chosen puts it back to 0 (15 Nov).
                        onPick = { a -> if (a == adjust) edit = edit.with(a, 0) else adjust = a },
                        onChange = { edit = edit.with(adjust, it) },
                    )
                    EditTab.FILTERS -> FilterTools(
                        image,
                        edit,
                        onPick = { edit = edit.copy(filter = it, filterStrength = if (it == edit.filter) edit.filterStrength else 100) },
                        onStrength = { edit = edit.copy(filterStrength = it) },
                    )
                    EditTab.MOTION -> MotionTools(
                        on = motion,
                        busy = making || (motion && styled == null && layers?.background != null),
                        note = layers?.note?.takeIf { motion },
                        onChange = { motion = it },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                EditTab.entries.filter { it != EditTab.MOTION || livingOn }.forEach { t -> TabPill(t, selected = t == tab) { tab = t } }
            }
        }

        if (saving) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    Modifier.clip(RoundedCornerShape(percent = 50)).background(Panel).padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    val p = videoProgress
                    Text(
                        if (p != null) "Making the video… ${(p * 100).roundToInt()}%" else "Saving a copy…",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    if (editor.askDiscard) {
        ConfirmDialog(
            title = "Discard your changes?",
            body = "The original photo stays as it is either way.",
            confirm = "Discard",
            danger = true,
            onDismiss = { editor.askDiscard = false },
            onConfirm = { leave() },
        )
    }
}

/** Where the picture sits in the preview: its top-left corner and drawn size, in pixels of the page. */
private class Placed(val x: Float, val y: Float, val w: Float, val h: Float)

private fun place(areaW: Float, areaH: Float, pictureW: Int, pictureH: Int, pad: Float): Placed {
    val roomW = (areaW - 2 * pad).coerceAtLeast(1f)
    val roomH = (areaH - 2 * pad).coerceAtLeast(1f)
    val fit = min(roomW / pictureW.coerceAtLeast(1), roomH / pictureH.coerceAtLeast(1))
    val w = pictureW * fit
    val h = pictureH * fit
    return Placed((areaW - w) / 2f, (areaH - h) / 2f, w, h)
}

/**
 * The picture being edited. While cropping: the whole picture with the frame over it (drag a corner to resize, drag
 * inside to move). Otherwise: just the part inside the frame, as the copy will look.
 */
@Composable
private fun EditPreview(image: ImageBitmap, edit: PhotoEdit, cropping: Boolean, ratio: Float?, onCrop: (CropBox) -> Unit, onHold: (Boolean) -> Unit = {}, tilt: Float = 0f) {
    val matrix = remember(edit) { EditMath.colourMatrix(edit) }
    val colour = remember(matrix) { if (edit.hasColour) ColorFilter.colorMatrix(ColorMatrix(matrix)) else null }
    val cropNow by rememberUpdatedState(edit.crop)
    val ratioNow by rememberUpdatedState(ratio)
    val onCropNow by rememberUpdatedState(onCrop)
    val density = LocalDensity.current
    val pad = with(density) { 22.dp.toPx() }
    val reach = with(density) { 40.dp.toPx() }
    val gestures: Modifier = if (!cropping) Modifier.holdForOriginal(onHold) else Modifier.pointerInput(image) {
        var corner: CropCorner? = null
        var moving = false
        detectDragGestures(
            onDragStart = { at ->
                val p = place(size.width.toFloat(), size.height.toFloat(), image.width, image.height, pad)
                val x = (at.x - p.x) / p.w
                val y = (at.y - p.y) / p.h
                val box = cropNow
                corner = EditMath.cornerNear(box, x, y, reach / p.w, reach / p.h)
                moving = corner == null && x >= box.left && x <= box.right && y >= box.top && y <= box.bottom
            },
            onDragEnd = {
                corner = null
                moving = false
            },
            onDragCancel = {
                corner = null
                moving = false
            },
            onDrag = { change, drag ->
                change.consume()
                val p = place(size.width.toFloat(), size.height.toFloat(), image.width, image.height, pad)
                val box = cropNow
                val held = corner
                if (held != null) {
                    val x = (if (held.left) box.left else box.right) + drag.x / p.w
                    val y = (if (held.top) box.top else box.bottom) + drag.y / p.h
                    onCropNow(EditMath.resize(box, held, x, y, ratioNow))
                } else if (moving) {
                    onCropNow(EditMath.move(box, drag.x / p.w, drag.y / p.h))
                }
            },
        )
    }
    Canvas(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = if (cropping) "Photo with crop frame" else "Edited photo" }
            .then(gestures),
    ) {
        if (cropping) {
            val p = place(size.width, size.height, image.width, image.height, pad)
            if (tilt != 0f) {
                drawTilted(image, tilt, p.x, p.y, p.w, p.h, p, colour)
            } else {
                drawImage(
                    image,
                    dstOffset = IntOffset(p.x.roundToInt(), p.y.roundToInt()),
                    dstSize = IntSize(p.w.roundToInt().coerceAtLeast(1), p.h.roundToInt().coerceAtLeast(1)),
                    colorFilter = colour,
                    filterQuality = FilterQuality.Medium,
                )
            }
            drawCropFrame(p, edit.crop)
        } else if (tilt != 0f) {
            // The whole picture placed so the part inside the frame fills the space, tilted, and only that part shown.
            val px = EditMath.pixels(edit.crop, image.width, image.height)
            val p = place(size.width, size.height, px[2], px[3], pad / 2f)
            val k = p.w / px[2].coerceAtLeast(1)
            drawTilted(image, tilt, p.x - px[0] * k, p.y - px[1] * k, image.width * k, image.height * k, p, colour)
        } else {
            val px = EditMath.pixels(edit.crop, image.width, image.height)
            val p = place(size.width, size.height, px[2], px[3], pad / 2f)
            drawImage(
                image,
                srcOffset = IntOffset(px[0], px[1]),
                srcSize = IntSize(px[2], px[3]),
                dstOffset = IntOffset(p.x.roundToInt(), p.y.roundToInt()),
                dstSize = IntSize(p.w.roundToInt().coerceAtLeast(1), p.h.roundToInt().coerceAtLeast(1)),
                colorFilter = colour,
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

/**
 * Straighten while the slider moves (16 Nov): the picture drawn at x, y, w, h, tilted about its middle and enlarged
 * to cover, showing only what falls inside [clip]. The same sums as the saved copy, so nothing jumps when it's ready.
 */
private fun DrawScope.drawTilted(image: ImageBitmap, tilt: Float, x: Float, y: Float, w: Float, h: Float, clip: Placed, colour: ColorFilter?) {
    val s = EditMath.straightenScale(tilt, image.width, image.height)
    val middle = Offset(x + w / 2f, y + h / 2f)
    clipRect(clip.x, clip.y, clip.x + clip.w, clip.y + clip.h) {
        withTransform({
            rotate(tilt, middle)
            scale(s, s, middle)
        }) {
            drawImage(
                image,
                dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
                dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
                colorFilter = colour,
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

/** The part outside the frame darkened, a fine white frame with thirds, and a thick bracket on each corner. */
private fun DrawScope.drawCropFrame(p: Placed, box: CropBox) {
    val l = p.x + box.left * p.w
    val t = p.y + box.top * p.h
    val r = p.x + box.right * p.w
    val b = p.y + box.bottom * p.h
    val shade = Color.Black.copy(alpha = 0.62f)
    drawRect(shade, Offset(p.x, p.y), Size(p.w, (t - p.y).coerceAtLeast(0f)))
    drawRect(shade, Offset(p.x, b), Size(p.w, (p.y + p.h - b).coerceAtLeast(0f)))
    drawRect(shade, Offset(p.x, t), Size((l - p.x).coerceAtLeast(0f), (b - t).coerceAtLeast(0f)))
    drawRect(shade, Offset(r, t), Size((p.x + p.w - r).coerceAtLeast(0f), (b - t).coerceAtLeast(0f)))
    val fine = 1.dp.toPx()
    drawRect(Color.White.copy(alpha = 0.9f), Offset(l, t), Size(r - l, b - t), style = Stroke(width = fine))
    for (n in 1..2) {
        val x = l + (r - l) * n / 3f
        val y = t + (b - t) * n / 3f
        drawLine(Color.White.copy(alpha = 0.4f), Offset(x, t), Offset(x, b), strokeWidth = fine)
        drawLine(Color.White.copy(alpha = 0.4f), Offset(l, y), Offset(r, y), strokeWidth = fine)
    }
    val thick = 4.dp.toPx()
    val arm = min(22.dp.toPx(), min(r - l, b - t) / 2f)
    fun bracket(x: Float, y: Float, dx: Float, dy: Float) {
        drawLine(Color.White, Offset(x, y), Offset(x + dx * arm, y), strokeWidth = thick, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(x, y), Offset(x, y + dy * arm), strokeWidth = thick, cap = StrokeCap.Round)
    }
    bracket(l, t, 1f, 1f)
    bracket(r, t, -1f, 1f)
    bracket(l, b, 1f, -1f)
    bracket(r, b, -1f, -1f)
}

@Composable
private fun CropTools(
    aspect: CropAspect,
    straighten: Float,
    onStraighten: (Float) -> Unit,
    onAspect: (CropAspect) -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onMirror: () -> Unit,
) {
    val haptics = rememberHaptics()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(percent = 50)).background(Panel).padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            EditorIcon(Icons.Rounded.Rotate90DegreesCcw, "Turn left", onClick = onLeft)
            // The same arrow, mirrored, for the other way.
            EditorIcon(Icons.Rounded.Rotate90DegreesCcw, "Turn right", mirrored = true, onClick = onRight)
            EditorIcon(Icons.Rounded.Flip, "Mirror", onClick = onMirror)
        }
        // Straighten (16 Nov): tilt by up to 45 degrees either way, in tenths; tap the number to put it level again.
        Row(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tenths = (straighten * 10f).roundToInt()
            Text(
                if (tenths == 0) "Straighten" else (if (tenths > 0) "+" else "−") + "${abs(tenths) / 10}.${abs(tenths) % 10}°",
                color = if (tenths == 0) Dim else Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (tenths == 0) null else FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(min = 92.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(enabled = tenths != 0, role = Role.Button, onClickLabel = "Make level again") {
                        haptics.detent()
                        onStraighten(0f)
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            CentreSlider(
                tenths,
                label = "Straighten",
                min = -450,
                max = 450,
                tick = 10,
                snap = 4,
                onChange = { onStraighten(it / 10f) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CropAspect.entries.forEach { shape ->
                Choice(shape.label, selected = shape == aspect) { onAspect(shape) }
            }
        }
    }
}

@Composable
private fun AdjustTools(edit: PhotoEdit, chosen: EditAdjust, onPick: (EditAdjust) -> Unit, onChange: (Int) -> Unit) {
    val haptics = rememberHaptics()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val value = edit.value(chosen)
        // The number: tap it to put the slider back to 0 (15 Nov).
        Text(
            if (value > 0 && chosen.min < 0) "+$value" else "$value",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .clickable(enabled = value != 0, role = Role.Button, onClickLabel = "Reset ${chosen.label}") {
                    haptics.detent()
                    onChange(0)
                }
                .padding(horizontal = 12.dp, vertical = 2.dp),
        )
        CentreSlider(
            value,
            label = chosen.label,
            min = chosen.min,
            onChange = onChange,
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 28.dp),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EditAdjust.entries.forEach { a ->
                // A dot after the name marks a slider that has been moved; tapping the chosen one again resets it.
                Choice(if (edit.value(a) != 0) a.label + " •" else a.label, selected = a == chosen) { onPick(a) }
            }
        }
    }
}

/** Motion (15 Nov): makes the photo move, with whatever crop and colours are chosen, in any order. */
@Composable
private fun MotionTools(on: Boolean, busy: Boolean, note: String?, onChange: (Boolean) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Still", selected = !on) { onChange(false) }
            Choice("Make it move", selected = on) { onChange(true) }
        }
        val text = when {
            busy -> "Lifting out the subject…"
            note != null -> note
            on -> "The background drifts behind the subject. Your crop and colours are used; saved as a short video."
            else -> "Turn on to make the photo move gently, like a 3D photo."
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = Dim, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun FilterTools(image: ImageBitmap?, edit: PhotoEdit, onPick: (EditFilter) -> Unit, onStrength: (Int) -> Unit) {
    val haptics = rememberHaptics()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    // How strong the chosen filter is (15 Nov); tap the number for full strength again.
    if (edit.filter != EditFilter.NONE) {
        Row(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${edit.filterStrength}%",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .widthIn(min = 52.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(role = Role.Button, onClickLabel = "Full strength") {
                        haptics.detent()
                        onStrength(100)
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            CentreSlider(edit.filterStrength, label = "${edit.filter.label} strength", min = 0, resetTo = 100, onChange = onStrength, modifier = Modifier.weight(1f))
        }
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EditFilter.entries.forEach { look ->
            val selected = look == edit.filter
            Column(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(role = Role.RadioButton, onClickLabel = look.label) { onPick(look) }
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val tile = RoundedCornerShape(16.dp)
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(tile)
                        .background(Panel)
                        .border(2.dp, if (selected) Color.White else Color.Transparent, tile),
                ) {
                    if (image != null) {
                        val colour = remember(look) {
                            if (look == EditFilter.NONE) null else ColorFilter.colorMatrix(ColorMatrix(EditMath.filterMatrix(look)))
                        }
                        Canvas(Modifier.fillMaxSize()) {
                            // The middle square of what is inside the crop frame.
                            val px = EditMath.pixels(edit.crop, image.width, image.height)
                            val side = min(px[2], px[3]).coerceAtLeast(1)
                            drawImage(
                                image,
                                srcOffset = IntOffset(px[0] + (px[2] - side) / 2, px[1] + (px[3] - side) / 2),
                                srcSize = IntSize(side, side),
                                dstSize = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1)),
                                colorFilter = colour,
                                filterQuality = FilterQuality.Low,
                            )
                        }
                    }
                }
                Text(
                    look.label,
                    color = if (selected) Color.White else Dim,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else null,
                )
            }
        }
    }
    }
}

/**
 * A slider up to 100 (15 Nov): from -100 with the thumb starting in the middle (the line fills from the centre), or
 * from 0 with it starting at the left. It holds at 0 for a moment; dragging anywhere moves the thumb from where it is
 * (no jump). Light ticks while it moves, a firmer click at 0 and at either end; a double tap resets it to [resetTo].
 */
@Composable
private fun CentreSlider(
    value: Int,
    label: String,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int = -100,
    resetTo: Int = 0,
    max: Int = 100,
    /** A light tick each time it moves this far (16 Nov: Straighten ticks every degree). */
    tick: Int = 5,
    /** Within this of 0 it holds at 0. */
    snap: Int = 3,
) {
    val haptics = rememberHaptics()
    val valueNow by rememberUpdatedState(value)
    val onChangeNow by rememberUpdatedState(onChange)
    val span = (max - min).toFloat()
    Canvas(
        modifier
            .height(44.dp)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), min.toFloat()..max.toFloat())
                setProgress { target ->
                    onChangeNow(target.roundToInt().coerceIn(min, max))
                    true
                }
            }
            .pointerInput(resetTo) {
                detectTapGestures(onDoubleTap = {
                    if (valueNow != resetTo) {
                        haptics.detent()
                        onChangeNow(resetTo)
                    }
                })
            }
            .pointerInput(min, max) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val startValue = valueNow
                    val startX = down.position.x
                    val track = (size.width - 24.dp.toPx()).coerceAtLeast(1f)
                    var lastTick = startValue
                    drag(down.id) { change ->
                        change.consume()
                        val raw = (startValue + (change.position.x - startX) / track * span).roundToInt().coerceIn(min, max)
                        val next = if (min < 0 && abs(raw) <= snap) 0 else raw
                        if (next != valueNow) {
                            if (next == 0 || next == min || next == max) {
                                haptics.detent()
                                lastTick = next
                            } else if (abs(next - lastTick) >= tick) {
                                haptics.slide()
                                lastTick = next
                            }
                            onChangeNow(next)
                        }
                    }
                }
            },
    ) {
        val inset = 12.dp.toPx()
        val y = size.height / 2f
        val start = inset
        val end = size.width - inset
        val zero = start + (end - start) * ((0 - min) / span)
        val thumb = start + (end - start) * ((value - min) / span)
        drawLine(Color.White.copy(alpha = 0.24f), Offset(start, y), Offset(end, y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Color.White, Offset(zero, y), Offset(thumb, y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        if (min < 0) drawLine(Color.White.copy(alpha = 0.7f), Offset(zero, y - 7.dp.toPx()), Offset(zero, y + 7.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(Color.Black.copy(alpha = 0.35f), radius = 12.dp.toPx(), center = Offset(thumb, y + 1.dp.toPx()))
        drawCircle(Color.White, radius = 11.dp.toPx(), center = Offset(thumb, y))
    }
}

/** The original photo, whole and untouched, while Compare (or the photo) is held. */
@Composable
private fun OriginalPreview(image: ImageBitmap) {
    Box(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val pad = with(density) { 11.dp.toPx() }
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Original photo" }) {
            val p = place(size.width, size.height, image.width, image.height, pad)
            drawImage(
                image,
                dstOffset = IntOffset(p.x.roundToInt(), p.y.roundToInt()),
                dstSize = IntSize(p.w.roundToInt().coerceAtLeast(1), p.h.roundToInt().coerceAtLeast(1)),
                filterQuality = FilterQuality.Medium,
            )
        }
        Text(
            "Original",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/** Shows the original while a finger rests on it for a moment; back to the edit when it lifts (15 Nov). */
private fun Modifier.holdForOriginal(onHold: (Boolean) -> Unit): Modifier = pointerInput(Unit) {
    detectTapGestures(onPress = {
        kotlinx.coroutines.coroutineScope {
            val wait = launch {
                kotlinx.coroutines.delay(180)
                onHold(true)
            }
            tryAwaitRelease()
            wait.cancel()
        }
        onHold(false)
    })
}

/** A round button that shows something only while it is held (Compare). */
@Composable
private fun HoldIcon(icon: ImageVector, label: String, size: androidx.compose.ui.unit.Dp = 48.dp, onHold: (Boolean) -> Unit) {
    val haptics = rememberHaptics()
    val onHoldNow by rememberUpdatedState(onHold)
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    haptics.tick()
                    onHoldNow(true)
                    tryAwaitRelease()
                    onHoldNow(false)
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

/** A round button on the editor's black page: always a white icon, whatever the theme. */
@Composable
internal fun EditorIcon(
    icon: ImageVector,
    label: String,
    mirrored: Boolean = false,
    enabled: Boolean = true,
    size: androidx.compose.ui.unit.Dp = 48.dp,
    onClick: () -> Unit,
) {
    val haptics = rememberHaptics()
    Box(
        Modifier
            .size(size)
            .graphicsLayer { alpha = if (enabled) 1f else 0.32f }
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label) {
                haptics.tap()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(24.dp).graphicsLayer { scaleX = if (mirrored) -1f else 1f })
    }
}

/** Reset (outlined) and Save copy (white) at the top of the editor. */
@Composable
internal fun EditorPill(text: String, filled: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(percent = 50)
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .clip(shape)
            .background(if (filled) Color.White else Color.Transparent)
            .border(1.dp, if (filled) Color.Transparent else Color.White.copy(alpha = 0.4f), shape)
            .clickable(enabled = enabled, role = Role.Button) {
                haptics.tap()
                onClick()
            }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (filled) Color.Black else Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** One of a row of choices (crop shapes, sliders): white when chosen. */
@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(percent = 50)
    Box(
        Modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(if (selected) Color.White else Panel)
            .clickable(role = Role.RadioButton) {
                if (selected) haptics.detent() else haptics.tick()
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) Color.Black else Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun TabPill(tab: EditTab, selected: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(percent = 50)
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(role = Role.Tab) {
                haptics.tick()
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(tab.icon, contentDescription = null, tint = if (selected) Color.White else Dim, modifier = Modifier.size(20.dp))
        Text(tab.label, color = if (selected) Color.White else Dim, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.SemiBold else null)
    }
}
