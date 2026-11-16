package com.deepak.photovault.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.Thumb
import com.deepak.photovault.designsystem.rememberHaptics
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Editing a video (Phase 11 part 4, 16 Nov): choose the part to keep with the two handles on the frame strip, and
 * turn the sound off if you like. Saves a new copy beside the original; the original is never changed. The chosen
 * part plays round and round while you choose.
 */
@Composable
internal fun VideoTrimScreen(item: MediaItem, state: ShellState, actions: MediaActions) {
    val editor = state.editor
    val scope = rememberCoroutineScope()
    val controls = remember(item.uri) { VideoControls() }
    val duration = if (item.durationMillis > 0) item.durationMillis else controls.durationMs
    var start by remember { mutableLongStateOf(0L) }
    // -1 = to the end, whatever length the player reports.
    var end by remember { mutableLongStateOf(-1L) }
    var mute by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val endAt = if (end < 0) duration else end
    val trimmed = duration > 0 && (start > 0 || endAt < duration)
    val changed = trimmed || mute

    LaunchedEffect(changed) { editor.changed = changed }
    DisposableEffect(Unit) {
        onDispose {
            editor.changed = false
            editor.askDiscard = false
        }
    }
    LaunchedEffect(mute) { controls.muted = mute }
    // Plays the chosen part round and round.
    val startNow by rememberUpdatedState(start)
    val endNow by rememberUpdatedState(endAt)
    LaunchedEffect(controls) {
        snapshotFlow { controls.positionMs to controls.playing }.collect { (at, playing) ->
            if (!playing || endNow <= 0) return@collect
            if (at >= endNow || at < startNow - 300) controls.seek(startNow)
        }
    }

    fun leave() {
        editor.changed = false
        editor.askDiscard = false
        state.back()
    }

    fun save() {
        if (saving || !changed) return
        saving = true
        controls.playing = false
        scope.launch {
            val saved = runCatching { editor.platform.trim(item, start, endAt, mute) }.getOrElse { EditSaved(false, "Couldn't save the copy.") }
            saving = false
            actions.toast(saved.message)
            if (saved.ok) leave()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditorIcon(Icons.Rounded.Close, "Close without saving") {
                    if (editor.changed) editor.askDiscard = true else leave()
                }
                Spacer(Modifier.weight(1f))
                if (changed && !saving) {
                    EditorPill("Reset", filled = false) {
                        start = 0L
                        end = -1L
                        mute = false
                    }
                    Spacer(Modifier.width(8.dp))
                }
                EditorPill("Save copy", filled = true, enabled = changed && !saving, onClick = { save() })
            }

            // The video, its own shape; tap to play or pause.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(11.dp)
                    .pointerInput(Unit) { detectTapGestures { controls.playing = !controls.playing } },
                contentAlignment = Alignment.Center,
            ) {
                val shape = controls.aspect
                if (!controls.firstFrame) {
                    AsyncImage(Thumb(item.uri, true, 512), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                VideoPlayer(
                    item.uri,
                    controls,
                    if (shape > 0f) Modifier.aspectRatio(shape) else Modifier.size(1.dp),
                )
            }

            // The part kept, in words.
            Text(
                if (duration <= 0) "" else "${com.deepak.photovault.core.media.Timeline.duration(start)} – ${com.deepak.photovault.core.media.Timeline.duration(endAt)}  ·  ${length(endAt - start)}",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
            )
            if (duration > 0) {
                TrimBar(
                    item = item,
                    duration = duration,
                    start = start,
                    end = endAt,
                    position = controls.positionMs,
                    onStart = { t ->
                        start = t
                        controls.playing = false
                        controls.seek(t)
                    },
                    onEnd = { t ->
                        end = if (t >= duration) -1L else t
                        controls.playing = false
                        controls.seek(t)
                    },
                    onScrub = { t ->
                        controls.playing = false
                        controls.seek(t)
                    },
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp).align(Alignment.CenterHorizontally),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditorIcon(
                    if (controls.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (controls.playing) "Pause" else "Play",
                ) {
                    if (!controls.playing && (controls.positionMs >= endAt - 100 || controls.positionMs < start)) controls.seek(start)
                    controls.playing = !controls.playing
                }
                EditorChoice("Sound on", selected = !mute) { mute = false }
                EditorChoice("Sound off", selected = mute) { mute = true }
            }
            Text(
                "The copy can start up to a second earlier than chosen, where the video allows a clean cut.",
                color = EditorDim,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 14.dp),
            )
        }

        if (saving) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    Modifier.clip(RoundedCornerShape(percent = 50)).background(EditorPanel).padding(horizontal = 20.dp, vertical = 14.dp),
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
            body = "The original video stays as it is either way.",
            confirm = "Discard",
            danger = true,
            onDismiss = { editor.askDiscard = false },
            onConfirm = { leave() },
        )
    }
}

/**
 * The video's frames in a row with a handle at each end of the part kept (the rest is shaded). Drag a handle to
 * move that end; drag inside to look through the video.
 */
@Composable
private fun TrimBar(
    item: MediaItem,
    duration: Long,
    start: Long,
    end: Long,
    position: Long,
    onStart: (Long) -> Unit,
    onEnd: (Long) -> Unit,
    onScrub: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val startNow by rememberUpdatedState(start)
    val endNow by rememberUpdatedState(end)
    val onStartNow by rememberUpdatedState(onStart)
    val onEndNow by rememberUpdatedState(onEnd)
    val onScrubNow by rememberUpdatedState(onScrub)
    BoxWithConstraints(modifier.height(BAR_H)) {
        val frames = (maxWidth / 44.dp).toInt().coerceIn(6, 16)
        // The frames, between the two handles' resting places.
        Row(Modifier.fillMaxSize().padding(horizontal = HANDLE_W, vertical = 4.dp).clip(RoundedCornerShape(6.dp))) {
            repeat(frames) { i ->
                Box(Modifier.weight(1f).fillMaxSize()) {
                    AsyncImage(Thumb(item.uri, true, 160), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    AsyncImage(
                        model = VideoFrame(item.uri, duration * (2 * i + 1) / (2L * frames), 160),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Part to keep: ${com.deepak.photovault.core.media.Timeline.duration(start)} to ${com.deepak.photovault.core.media.Timeline.duration(end)}" }
                .pointerInput(duration) {
                    val handle = HANDLE_W.toPx()
                    val reach = 28.dp.toPx()
                    fun x(t: Long) = handle + (size.width - 2 * handle) * (t.toFloat() / duration)
                    fun t(x: Float) = (((x - handle) / (size.width - 2 * handle)).coerceIn(0f, 1f) * duration).toLong()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val sx = x(startNow) - handle / 2
                        val ex = x(endNow) + handle / 2
                        val dx = down.position.x
                        // The nearer handle within reach; otherwise looking through the video.
                        val grab = when {
                            abs(dx - sx) <= reach && abs(dx - sx) <= abs(dx - ex) -> 1
                            abs(dx - ex) <= reach -> 2
                            else -> 0
                        }
                        if (grab != 0) haptics.tick() else onScrubNow(t(dx).coerceIn(startNow, endNow))
                        val fromStart = startNow
                        val fromEnd = endNow
                        val startX = dx
                        drag(down.id) { change ->
                            change.consume()
                            val moved = change.position.x - startX
                            val per = duration / (size.width - 2 * handle)
                            when (grab) {
                                1 -> {
                                    val next = (fromStart + (moved * per).toLong()).coerceIn(0L, (endNow - MIN_PART_MS).coerceAtLeast(0L))
                                    if (next != startNow) {
                                        if (next == 0L) haptics.detent()
                                        onStartNow(next)
                                    }
                                }
                                2 -> {
                                    val next = (fromEnd + (moved * per).toLong()).coerceIn((startNow + MIN_PART_MS).coerceAtMost(duration), duration)
                                    if (next != endNow) {
                                        if (next == duration) haptics.detent()
                                        onEndNow(next)
                                    }
                                }
                                else -> onScrubNow(t(change.position.x).coerceIn(startNow, endNow))
                            }
                        }
                    }
                },
        ) {
            val handle = HANDLE_W.toPx()
            val inner = size.width - 2 * handle
            val sx = handle + inner * (start.toFloat() / duration)
            val ex = handle + inner * (end.toFloat() / duration)
            val shade = Color.Black.copy(alpha = 0.62f)
            // Shaded outside the part kept.
            drawRect(shade, Offset(handle, 4.dp.toPx()), Size((sx - handle).coerceAtLeast(0f), size.height - 8.dp.toPx()))
            drawRect(shade, Offset(ex, 4.dp.toPx()), Size((handle + inner - ex).coerceAtLeast(0f), size.height - 8.dp.toPx()))
            // A white frame round the part kept: a bar on each side to hold, thin lines top and bottom.
            val line = 3.dp.toPx()
            drawRect(Color.White, Offset(sx, 0f), Size(ex - sx, line))
            drawRect(Color.White, Offset(sx, size.height - line), Size(ex - sx, line))
            drawRoundRect(Color.White, Offset(sx - handle, 0f), Size(handle, size.height), CornerRadius(6.dp.toPx()))
            drawRoundRect(Color.White, Offset(ex, 0f), Size(handle, size.height), CornerRadius(6.dp.toPx()))
            val grip = Color.Black.copy(alpha = 0.55f)
            val gripH = size.height * 0.32f
            drawLine(grip, Offset(sx - handle / 2, size.height / 2 - gripH / 2), Offset(sx - handle / 2, size.height / 2 + gripH / 2), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(grip, Offset(ex + handle / 2, size.height / 2 - gripH / 2), Offset(ex + handle / 2, size.height / 2 + gripH / 2), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            // Where the video is now.
            if (position in start..end) {
                val px = handle + inner * (position.toFloat() / duration)
                drawLine(Color.White, Offset(px, 6.dp.toPx()), Offset(px, size.height - 6.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}

/** "4.5 s", "12 s", "1 min 5 s". */
private fun length(ms: Long): String {
    val s = ms.coerceAtLeast(0L) / 1000.0
    return when {
        s < 10 -> "${(s * 10).roundToInt() / 10.0} s"
        s < 60 -> "${s.roundToInt()} s"
        else -> "${(s / 60).toInt()} min ${(s % 60).roundToInt()} s"
    }
}

private val BAR_H = 60.dp
private val HANDLE_W = 16.dp
private const val MIN_PART_MS = 1000L
