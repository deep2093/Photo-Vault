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
import androidx.compose.material.icons.rounded.AutoAwesome
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

    /** Saves [edit] of [item] as a new photo beside the original. Never writes to the original. */
    suspend fun save(item: MediaItem, edit: PhotoEdit): EditSaved = EditSaved(false, "Editing isn't available here.")
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
}

private val Dim = Color.White.copy(alpha = 0.62f)
private val Panel = Color(0xFF1C1C21)

@Composable
fun EditorScreen(photoId: Long, state: ShellState, gallery: GalleryState, actions: MediaActions) {
    val editor = state.editor
    // Held for the whole visit: the library reloads when the copy is saved, and the page must not lose its photo.
    val item = remember(photoId) { gallery.find(photoId) }
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

    LaunchedEffect(item) {
        val opened = item?.let { runCatching { editor.platform.open(it) }.getOrNull() }
        base = opened
        image = opened
        failed = opened == null
    }
    LaunchedEffect(edit) { editor.changed = !edit.isUnchanged }
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
    fun reshape(next: PhotoEdit) {
        val from = base ?: return
        if (turning || saving) return
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
                if (!edit.isUnchanged && !saving) {
                    EditorPill("Reset", filled = false) {
                        val from = base
                        edit = PhotoEdit()
                        aspect = CropAspect.FREE
                        if (from != null) image = from
                    }
                    Spacer(Modifier.width(8.dp))
                }
                EditorPill("Save copy", filled = true, enabled = !edit.isUnchanged && image != null && !saving, onClick = { save() })
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val shown = image
                when {
                    shown != null -> EditPreview(
                        image = shown,
                        edit = edit,
                        cropping = tab == EditTab.CROP,
                        ratio = lockedRatio,
                        onCrop = { edit = edit.copy(crop = it) },
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
                        onAspect = { pick(it) },
                        onLeft = { reshape(edit.turnedLeft()) },
                        onRight = { reshape(edit.turnedRight()) },
                        onMirror = { reshape(edit.mirrored()) },
                    )
                    EditTab.ADJUST -> AdjustTools(edit, adjust, onPick = { adjust = it }, onChange = { edit = edit.with(adjust, it) })
                    EditTab.FILTERS -> FilterTools(image, edit, onPick = { edit = edit.copy(filter = it) })
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                EditTab.entries.forEach { t -> TabPill(t, selected = t == tab) { tab = t } }
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
                    Text("Saving a copy…", color = Color.White, style = MaterialTheme.typography.bodyMedium)
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
private fun EditPreview(image: ImageBitmap, edit: PhotoEdit, cropping: Boolean, ratio: Float?, onCrop: (CropBox) -> Unit) {
    val matrix = remember(edit.brightness, edit.contrast, edit.saturation, edit.warmth, edit.filter) { EditMath.colourMatrix(edit) }
    val colour = remember(matrix) { if (edit.hasColour) ColorFilter.colorMatrix(ColorMatrix(matrix)) else null }
    val cropNow by rememberUpdatedState(edit.crop)
    val ratioNow by rememberUpdatedState(ratio)
    val onCropNow by rememberUpdatedState(onCrop)
    val density = LocalDensity.current
    val pad = with(density) { 22.dp.toPx() }
    val reach = with(density) { 40.dp.toPx() }
    val gestures: Modifier = if (!cropping) Modifier else Modifier.pointerInput(image) {
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
            drawImage(
                image,
                dstOffset = IntOffset(p.x.roundToInt(), p.y.roundToInt()),
                dstSize = IntSize(p.w.roundToInt().coerceAtLeast(1), p.h.roundToInt().coerceAtLeast(1)),
                colorFilter = colour,
                filterQuality = FilterQuality.Medium,
            )
            drawCropFrame(p, edit.crop)
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
private fun CropTools(aspect: CropAspect, onAspect: (CropAspect) -> Unit, onLeft: () -> Unit, onRight: () -> Unit, onMirror: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(percent = 50)).background(Panel).padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            EditorIcon(Icons.Rounded.Rotate90DegreesCcw, "Turn left", onClick = onLeft)
            // The same arrow, mirrored, for the other way.
            EditorIcon(Icons.Rounded.Rotate90DegreesCcw, "Turn right", mirrored = true, onClick = onRight)
            EditorIcon(Icons.Rounded.Flip, "Mirror", onClick = onMirror)
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
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val value = edit.value(chosen)
        Text(
            if (value > 0) "+$value" else "$value",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        CentreSlider(value, label = chosen.label, onChange = onChange, modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 28.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EditAdjust.entries.forEach { a ->
                // A dot after the name marks a slider that has been moved.
                Choice(if (edit.value(a) != 0) a.label + " •" else a.label, selected = a == chosen) { onPick(a) }
            }
        }
    }
}

@Composable
private fun FilterTools(image: ImageBitmap?, edit: PhotoEdit, onPick: (EditFilter) -> Unit) {
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
                        .size(72.dp)
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

/**
 * A slider from -100 to 100 that starts in the middle: the line fills from the centre towards the thumb, it holds
 * at 0 for a moment (with a tick), and dragging anywhere on it moves the thumb from where it is (no jump).
 */
@Composable
private fun CentreSlider(value: Int, label: String, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val valueNow by rememberUpdatedState(value)
    val onChangeNow by rememberUpdatedState(onChange)
    Canvas(
        modifier
            .height(44.dp)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), -100f..100f)
                setProgress { target ->
                    onChangeNow(target.roundToInt().coerceIn(-100, 100))
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val startValue = valueNow
                    val startX = down.position.x
                    val track = (size.width - 24.dp.toPx()).coerceAtLeast(1f)
                    drag(down.id) { change ->
                        change.consume()
                        val raw = (startValue + (change.position.x - startX) / track * 200f).roundToInt().coerceIn(-100, 100)
                        val next = if (abs(raw) <= 3) 0 else raw
                        if (next != valueNow) {
                            if (next == 0) haptics.tick()
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
        val middle = (start + end) / 2f
        val thumb = middle + (end - start) / 2f * (value / 100f)
        drawLine(Color.White.copy(alpha = 0.24f), Offset(start, y), Offset(end, y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Color.White, Offset(middle, y), Offset(thumb, y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.7f), Offset(middle, y - 7.dp.toPx()), Offset(middle, y + 7.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(Color.Black.copy(alpha = 0.35f), radius = 12.dp.toPx(), center = Offset(thumb, y + 1.dp.toPx()))
        drawCircle(Color.White, radius = 11.dp.toPx(), center = Offset(thumb, y))
    }
}

/** A round button on the editor's black page: always a white icon, whatever the theme. */
@Composable
private fun EditorIcon(icon: ImageVector, label: String, mirrored: Boolean = false, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label) {
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
private fun EditorPill(text: String, filled: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
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
                haptics.tick()
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
