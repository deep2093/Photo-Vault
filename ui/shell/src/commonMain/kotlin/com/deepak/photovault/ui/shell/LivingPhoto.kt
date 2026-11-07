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
import com.deepak.photovault.designsystem.GlassIconButton
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
)

interface LivingPlatform {
    val supported: Boolean get() = false

    /** Makes (or reads back, when made before) the two layers. Runs on the phone only. */
    suspend fun make(item: MediaItem): LivingLayers = LivingLayers(null, null, note = "Living photos aren't available here.")
}

@Stable
class LivingState(val platform: LivingPlatform = object : LivingPlatform {})

/** How the two layers move at [t] (0 to 1 and back): how much bigger, and how far sideways (share of the width). */
object LivingMotion {
    /** One way takes this long; it then eases back. */
    const val SECONDS = 6

    fun backgroundScale(t: Float, gentle: Boolean) = 1f + (if (gentle) 0.02f else 0.04f) * t

    fun subjectScale(t: Float, gentle: Boolean) = 1f + (if (gentle) 0.035f else 0.075f) * t

    /** The far background moves a little; the subject, closer to the camera, moves more (that is the depth). */
    fun backgroundShift(t: Float, gentle: Boolean) = (t - 0.5f) * (if (gentle) 0.008f else 0.016f)

    fun subjectShift(t: Float, gentle: Boolean) = (t - 0.5f) * (if (gentle) 0.02f else 0.04f)

    /** A little bigger than fitting the screen, so the moving edges never show. */
    const val OVERSCAN = 1.08f
}

/** The page that shows one living photo (from the viewer's Make it move). Always dark, like the viewer. */
@Composable
fun LivingPhotoScreen(photoId: Long, state: ShellState, gallery: GalleryState) {
    val item = remember(photoId, gallery.items) { gallery.find(photoId) }
    val layers by produceState<LivingLayers?>(null, item) {
        value = item?.let { runCatching { state.living.platform.make(it) }.getOrElse { LivingLayers(null, null, note = "Couldn't make this one move.") } }
    }
    val insets = WindowInsets.safeDrawing.asPaddingValues()
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
        Row(Modifier.padding(top = insets.calculateTopPadding() + 8.dp, start = 8.dp)) {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = { state.back() })
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
            val text = when {
                item == null -> "This photo isn't here any more."
                l == null -> "Making it move…"
                else -> l.note
            }
            if (text != null) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (l == null && item != null) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        Box(Modifier.size(10.dp))
                    }
                    Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
            }
            if (l?.background != null) {
                Text("Made on your phone", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** The two layers drifting, forever, centred on the subject. Gentler and slower with Reduce motion. */
@Composable
fun LivingPhoto(layers: LivingLayers, modifier: Modifier = Modifier) {
    val gentle = LocalGlassSettings.current.reduceMotion
    val loop = rememberInfiniteTransition(label = "livingLoop")
    val t by loop.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(LivingMotion.SECONDS * (if (gentle) 2000 else 1000), easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "livingT",
    )
    val bg = layers.background ?: return
    Canvas(modifier) {
        val fit = min(size.width / bg.width, size.height / bg.height) * LivingMotion.OVERSCAN
        val drawW = bg.width * fit
        val drawH = bg.height * fit
        val left = (size.width - drawW) / 2f
        val top = (size.height - drawH) / 2f
        val pivot = Offset(left + drawW * layers.focusX, top + drawH * layers.focusY)
        layer(bg, left, top, drawW, drawH, pivot, LivingMotion.backgroundScale(t, gentle), LivingMotion.backgroundShift(t, gentle) * drawW)
        layers.subject?.let { layer(it, left, top, drawW, drawH, pivot, LivingMotion.subjectScale(t, gentle), LivingMotion.subjectShift(t, gentle) * drawW) }
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
