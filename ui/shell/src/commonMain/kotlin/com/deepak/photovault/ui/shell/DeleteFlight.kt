package com.deepak.photovault.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import coil3.compose.AsyncImage
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.Thumb
import com.deepak.photovault.designsystem.LocalGlassSettings
import com.deepak.photovault.designsystem.rememberHaptics
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Deleting looks like the photos are sucked down into the bin (23 Oct): copies of the deleted photos shrink,
 * narrow and slide into the delete button that was pressed, one just after another. It plays only after
 * Android's own confirmation, wherever photos or videos are deleted.
 */
@Stable
class DeleteFlight {
    internal class Flight(val id: Long, val photos: List<FlyingPhoto>, val target: Offset)

    internal var flights by mutableStateOf<List<Flight>>(emptyList())
        private set
    private var nextId = 0L

    fun launch(photos: List<FlyingPhoto>, target: Rect?) {
        if (photos.isEmpty() || target == null) return
        flights = flights + Flight(nextId++, photos.take(MAX_FLYING), target.center)
    }

    internal fun done(id: Long) {
        flights = flights.filterNot { it.id == id }
    }

    companion object {
        const val MAX_FLYING = 24
    }
}

/** One photo on its way to the bin: where it was on screen (window coordinates) and how it was drawn. */
data class FlyingPhoto(val uri: String, val isVideo: Boolean, val bounds: Rect, val fit: Boolean = false)

val LocalDeleteFlight = staticCompositionLocalOf { DeleteFlight() }

/** Where something is on screen, kept up to date (for the delete button the photos fly into). */
@Stable
class ScreenSpot {
    var bounds: Rect? = null
        internal set
}

fun Modifier.trackSpot(spot: ScreenSpot): Modifier = onGloballyPositioned { spot.bounds = it.boundsInRoot() }

/** Lets a selection bar find where the grid draws each selected photo. Set by [MediaGrid]. */
@Stable
class TileSpots {
    internal var lookup: ((Collection<Long>) -> Map<Long, Rect>)? = null

    fun boundsOf(ids: Collection<Long>): Map<Long, Rect> = lookup?.invoke(ids) ?: emptyMap()
}

val LocalTileSpots = staticCompositionLocalOf<TileSpots?> { null }

/** The photos of [items] that are on screen, ready to fly. */
fun TileSpots?.flying(items: List<MediaItem>): List<FlyingPhoto> {
    val bounds = this?.boundsOf(items.map { it.id }) ?: return emptyList()
    return items.mapNotNull { item -> bounds[item.id]?.let { FlyingPhoto(item.uri, item.isVideo, it) } }
}

/** Pure maths of one photo's flight, tested. [t] runs 0 to 1. */
object FlightMath {
    /** When photo [index] of [count] starts, as a share of the whole flight (a quick ripple, not one block). */
    fun progress(t: Float, index: Int, count: Int): Float {
        val stagger = if (count <= 1) 0f else STAGGER / (count - 1)
        val start = stagger * index
        val span = 1f - STAGGER
        return ((t - start) / span).coerceIn(0f, 1f)
    }

    /** Narrows first, then shortens: the "sucked into the bin" look. */
    fun scaleX(k: Float) = 1f - (1f - END_SCALE) * k.pow(0.6f)

    fun scaleY(k: Float) = 1f - (1f - END_SCALE) * k.pow(1.6f)

    /** Falls slowly at first, then quickly into the bin. */
    fun travel(k: Float) = k.pow(1.8f)

    fun alpha(k: Float) = if (k < 0.75f) 1f else ((1f - k) / 0.25f).coerceIn(0f, 1f)

    const val STAGGER = 0.3f
    const val END_SCALE = 0.04f
}

/** Draws every flight in progress, above all pages. */
@Composable
fun DeleteFlightLayer(flight: DeleteFlight) {
    flight.flights.forEach { f -> key(f.id) { FlightView(f) { flight.done(f.id) } } }
}

@Composable
private fun FlightView(f: DeleteFlight.Flight, onDone: () -> Unit) {
    val t = remember { Animatable(0f) }
    val reduceMotion = LocalGlassSettings.current.reduceMotion
    val haptics = rememberHaptics()
    LaunchedEffect(Unit) {
        if (!reduceMotion) t.animateTo(1f, tween(FLY_MS, easing = LinearEasing))
        haptics.tick()
        onDone()
    }
    if (reduceMotion) return
    f.photos.forEachIndexed { index, p ->
        val painter = rememberAsyncImagePainter(Thumb(p.uri, p.isVideo, if (p.fit) 512 else 256))
        // Drawn in thin horizontal strips, each squeezed and pulled toward the bin on its own, bottom first:
        // the photo pinches at the bottom and stretches down into the button, like a window minimising (24 Oct).
        Canvas(Modifier.fillMaxSize()) {
            val k = FlightMath.progress(t.value, index, f.photos.size)
            if (k >= 1f) return@Canvas
            val intrinsic = painter.intrinsicSize
            val image = GenieMath.imageRect(p.bounds, intrinsic.takeIf { it.isSpecified && it.width > 0f && it.height > 0f }, p.fit)
            val area = if (p.fit) image else p.bounds
            val corner = 6.dp.toPx()
            val target = f.target
            for (i in 0 until GenieMath.STRIPS) {
                val strip = GenieMath.strip(area, i, k, target, endWidth = corner * 2) ?: continue
                val srcTop = area.top + area.height * i / GenieMath.STRIPS
                val srcHeight = area.height / GenieMath.STRIPS
                val sx = strip.width / area.width
                val sy = strip.height / srcHeight
                withTransform({
                    clipRect(strip.left, strip.top, strip.right, strip.bottom)
                    translate(strip.left, strip.top)
                    scale(sx, sy, pivot = Offset.Zero)
                    translate(-area.left, -srcTop)
                }) {
                    translate(image.left, image.top) {
                        with(painter) { draw(image.size, alpha = FlightMath.alpha(k)) }
                    }
                }
            }
        }
    }
}

/** The shape maths of the genie effect, tested. */
object GenieMath {
    const val STRIPS = 48

    /** Where the photo's picture sits: fitted inside [bounds] (viewer), or filling it and cropped (grid tile). */
    fun imageRect(bounds: Rect, intrinsic: Size?, fit: Boolean): Rect {
        if (intrinsic == null) return bounds
        val scale = if (fit) minOf(bounds.width / intrinsic.width, bounds.height / intrinsic.height)
        else maxOf(bounds.width / intrinsic.width, bounds.height / intrinsic.height)
        val w = intrinsic.width * scale
        val h = intrinsic.height * scale
        return Rect(bounds.center.x - w / 2, bounds.center.y - h / 2, bounds.center.x + w / 2, bounds.center.y + h / 2)
    }

    /** One edge between strips: how far down it is, where its middle is, and how wide the photo is there. */
    private class Edge(val y: Float, val centerX: Float, val width: Float, val gone: Boolean)

    private fun edge(area: Rect, j: Int, k: Float, target: Offset, endWidth: Float): Edge {
        val v = j.toFloat() / STRIPS // 0 top edge, 1 bottom edge
        // The bottom starts first and the top follows: the photo pinches into a funnel, then pours into the bin.
        val local = (k * (1f + LEAD) - (1f - v) * LEAD).coerceIn(0f, 1f)
        val pinch = 1f - (1f - local) * (1f - local) // fast at first: the funnel forms early
        val fall = local * local * local // slow at first: the photo hangs, then drops
        val y0 = area.top + area.height * v
        return Edge(
            y = y0 + (target.y - y0) * fall,
            centerX = area.center.x + (target.x - area.center.x) * pinch,
            width = area.width + (endWidth - area.width) * pinch,
            gone = local >= 1f,
        )
    }

    /**
     * Where strip [i] of [area] is drawn at progress [k]. Neighbouring strips share their edges, so the photo
     * stays one smooth piece (24 Oct: the first version showed gaps). Null once the strip is inside the bin.
     */
    fun strip(area: Rect, i: Int, k: Float, target: Offset, endWidth: Float): Rect? {
        val top = edge(area, i, k, target, endWidth)
        val bottom = edge(area, i + 1, k, target, endWidth)
        if (top.gone && bottom.gone) return null
        val width = (top.width + bottom.width) / 2f
        val centerX = (top.centerX + bottom.centerX) / 2f
        val height = (bottom.y - top.y).coerceAtLeast(0f)
        if (height < 0.2f) return null
        // A hair of overlap hides the seams between strips.
        return Rect(centerX - width / 2f, top.y, centerX + width / 2f, bottom.y + 0.75f)
    }

    private const val LEAD = 1.0f
}

private const val FLY_MS = 560
