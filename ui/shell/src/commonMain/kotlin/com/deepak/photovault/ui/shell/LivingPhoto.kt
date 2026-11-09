package com.deepak.photovault.ui.shell

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.clipRect
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.Thumb
import com.deepak.photovault.designsystem.LocalGlassSettings
import kotlin.math.max
import kotlin.math.min

/**
 * Living photos, part 1 (7D-1, 8 Nov): a still photo that moves gently, like Samsung Gallery's. The person (or pet,
 * or thing) is lifted off the background on the phone, the gap behind them is filled from the colours around it, and
 * the two layers drift by different amounts, so the photo seems to have depth.
 */
class LivingLayers(
    /** The photo with the subject's space filled in; null when it couldn't be made. */
    val background: ImageBitmap?,
    /** The subject alone on a see-through background; null when nothing was found to lift out. */
    val subject: ImageBitmap?,
    /** The middle of the subject, 0 to 1 across and down; the motion is centred on it. */
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
    /** Said under the photo: why it only drifts, or why it couldn't be made. */
    val note: String? = null,
    /** Where the subject meets the ground, 0 to 1 down the photo (7D-3): the background above it is further away. */
    val ground: Float = 1f,
)

interface LivingPlatform {
    val supported: Boolean get() = false

    /** Makes (or reads back, when made before) the two layers. Runs on the phone only. */
    suspend fun make(item: MediaItem): LivingLayers = LivingLayers(null, null, note = "Living photos aren't available here.")

    /** Only a living photo made earlier (quick), else null; nothing new is made (7D-3). */
    suspend fun ready(item: MediaItem): LivingLayers? = null

    /**
     * The living photo as a short video (7D-2, 8 Nov): one slow move there and back. [share] true opens the share
     * sheet; false saves it to the phone. Returns a short line to show, or null when nothing needs saying.
     */
    suspend fun video(item: MediaItem, layers: LivingLayers, share: Boolean, onProgress: (Float) -> Unit): String? = "Videos can't be made here."
}

@Stable
class LivingState(val platform: LivingPlatform = object : LivingPlatform {})

/**
 * How a living photo moves (9 Nov, like Samsung's 3D photos): the subject stays still and only the background moves
 * behind them, as if the camera slowly drew back while keeping the person the same size. The background starts a
 * little smaller and grows to the subject's own size; it never goes bigger, so the space filled in behind the subject
 * always stays hidden under them (no blur shows). Parts of the background further away (higher up than where the
 * subject stands) change more than parts close to them: a guess at depth from the ground (7D-3).
 */
object LivingMotion {
    /** One way takes this long; it then eases back. */
    const val SECONDS = 6

    /** The background is drawn in this many bands, each its own size. */
    const val STRIPS = 40

    /** A little bigger than fitting the screen, so the moving edges never show. */
    const val OVERSCAN = 1.12f

    /** How far away a row of the background is: 1 at the top, down to 0 where the subject stands and below. */
    fun farness(y: Float, ground: Float): Float = if (ground < 0.1f) 0.5f else (1f - y / ground).coerceIn(0f, 1f)

    /** A background row's size against the still subject at [t]: smaller at 0, the subject's own size at 1. */
    fun backgroundScale(y: Float, ground: Float, t: Float, gentle: Boolean): Float {
        val most = if (gentle) 0.03f else 0.065f
        return 1f - most * (0.15f + 0.85f * farness(y, ground)) * (1f - t)
    }

    /** The middle the background grows from: the subject's middle, kept off the very edges so no gap shows. */
    fun pivot(focus: Float): Float = focus.coerceIn(0.25f, 0.75f)

    /** Nothing to lift out: the whole photo slowly comes closer and drifts. */
    fun driftScale(t: Float, gentle: Boolean) = 1f + (if (gentle) 0.02f else 0.04f) * t

    fun driftShift(t: Float, gentle: Boolean) = (t - 0.5f) * (if (gentle) 0.008f else 0.016f)
}

/** The page that shows one living photo (from the viewer's Make it move). Always dark, like the viewer. */
@Composable
fun LivingPhotoScreen(photoId: Long, state: ShellState, gallery: GalleryState, actions: MediaActions) {
    val item = remember(photoId, gallery.items) { gallery.find(photoId) }
    val layers by produceState<LivingLayers?>(null, item) {
        value = item?.let { runCatching { state.living.platform.make(it) }.getOrElse { LivingLayers(null, null, note = "Couldn't make this one move.") } }
    }
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    // Save or share as a video (7D-2): how far it has got, while it is being made.
    var making by remember { mutableStateOf<Float?>(null) }
    val scope = rememberCoroutineScope()
    fun export(share: Boolean) {
        val l = layers ?: return
        val photo = item ?: return
        if (making != null || l.background == null) return
        making = 0f
        scope.launch {
            val said = runCatching { state.living.platform.video(photo, l, share) { making = it } }.getOrElse { "Couldn't make the video." }
            making = null
            said?.let(actions::toast)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(targetState = layers?.background != null, animationSpec = tween(500), label = "living") { ready ->
            val l = layers
            if (ready && l != null) {
                LivingPhoto(l, Modifier.fillMaxSize())
            } else if (item != null) {
                coil3.compose.AsyncImage(
                    model = Thumb(item.uri, item.isVideo, 1024),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Row(
            Modifier
                .padding(top = insets.calculateTopPadding() + 8.dp, start = 12.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(Color(0xCC1C1C21)),
        ) {
            DarkButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { state.back() }
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = insets.calculateBottomPadding() + 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val l = layers
            val busy = making
            val text = when {
                item == null -> "This photo isn't here any more."
                l == null -> "Making it move…"
                busy != null -> "Making the video… ${(busy * 100).toInt()}%"
                else -> l.note
            }
            if (text != null) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if ((l == null && item != null) || busy != null) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        Box(Modifier.size(10.dp))
                    }
                    Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
            }
            if (l?.background != null) {
                // Save and Share as a short video, in the same glass bar as the viewer's (7D-2).
                // 9 Nov: always dark with white icons, like the viewer (in a light theme the icons were dark on black).
                Row(
                    Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Color(0xCC1C1C21))
                        .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(percent = 50))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DarkButton(Icons.Rounded.Download, "Save as a video") { export(share = false) }
                    DarkButton(Icons.Rounded.Share, "Share as a video") { export(share = true) }
                }
                Text("Made on your phone", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** The living photo, forever, there and back. Gentler and slower with Reduce motion. [delayMs] staggers cards. */
@Composable
fun LivingPhoto(layers: LivingLayers, modifier: Modifier = Modifier, fill: Boolean = false, delayMs: Int = 0) {
    val gentle = LocalGlassSettings.current.reduceMotion
    val loop = rememberInfiniteTransition(label = "livingLoop")
    val t by loop.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(LivingMotion.SECONDS * (if (gentle) 2000 else 1000), easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
            initialStartOffset = androidx.compose.animation.core.StartOffset(delayMs),
        ),
        label = "livingT",
    )
    val bg = layers.background ?: return
    Canvas(modifier.clipToBounds()) {
        // [fill]: covers the whole space, cropping the sides (a Memories card); otherwise the whole photo shows.
        val fit = (if (fill) max(size.width / bg.width, size.height / bg.height) else min(size.width / bg.width, size.height / bg.height)) * LivingMotion.OVERSCAN
        val drawW = bg.width * fit
        val drawH = bg.height * fit
        val left = (size.width - drawW) / 2f
        val top = (size.height - drawH) / 2f
        val subject = layers.subject
        if (subject == null) {
            val pivot = Offset(left + drawW * 0.5f, top + drawH * 0.5f)
            layer(bg, left, top, drawW, drawH, pivot, LivingMotion.driftScale(t, gentle), LivingMotion.driftShift(t, gentle) * drawW)
            return@Canvas
        }
        val pivot = Offset(left + drawW * LivingMotion.pivot(layers.focusX), top + drawH * LivingMotion.pivot(layers.focusY))
        val n = LivingMotion.STRIPS
        for (i in 0 until n) {
            val y0 = i.toFloat() / n
            val y1 = (i + 1).toFloat() / n
            val s = LivingMotion.backgroundScale((y0 + y1) / 2f, layers.ground, t, gentle)
            val from = pivot.y + (top + drawH * y0 - pivot.y) * s
            val to = pivot.y + (top + drawH * y1 - pivot.y) * s
            clipRect(0f, if (i == 0) 0f else from - 0.5f, size.width, if (i == n - 1) size.height else to + 0.5f) {
                layer(bg, left, top, drawW, drawH, pivot, s, 0f)
            }
        }
        layer(subject, left, top, drawW, drawH, pivot, 1f, 0f)
    }
}

private fun DrawScope.layer(image: ImageBitmap, left: Float, top: Float, w: Float, h: Float, pivot: Offset, scale: Float, shiftX: Float) {
    withTransform({
        translate(left = -shiftX)
        scale(scale, scale, pivot)
    }) {
        drawImage(
            image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(left.toInt(), top.toInt()),
            dstSize = IntSize(max(1, w.toInt()), max(1, h.toInt())),
            filterQuality = FilterQuality.High,
        )
    }
}

/** A round button with a white icon, for this always-dark page. */
@Composable
private fun DarkButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val haptics = com.deepak.photovault.designsystem.rememberHaptics()
    Box(
        Modifier
            .size(48.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = label) { haptics.tap(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(icon, label, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}
