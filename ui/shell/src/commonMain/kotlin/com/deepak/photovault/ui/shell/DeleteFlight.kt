package com.deepak.photovault.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize()) {
        f.photos.forEachIndexed { index, p ->
            val w = with(density) { p.bounds.width.toDp() }
            val h = with(density) { p.bounds.height.toDp() }
            AsyncImage(
                model = Thumb(p.uri, p.isVideo, if (p.fit) 512 else 256),
                contentDescription = null,
                contentScale = if (p.fit) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier
                    .offset { IntOffset(p.bounds.left.roundToInt(), p.bounds.top.roundToInt()) }
                    .size(w, h)
                    .graphicsLayer {
                        val k = FlightMath.progress(t.value, index, f.photos.size)
                        val move = FlightMath.travel(k)
                        translationX = (f.target.x - p.bounds.center.x) * move
                        translationY = (f.target.y - p.bounds.center.y) * move
                        scaleX = FlightMath.scaleX(k)
                        scaleY = FlightMath.scaleY(k)
                        alpha = FlightMath.alpha(k)
                        shape = RoundedCornerShape(12f * k + 4f)
                        clip = true
                    },
            )
        }
    }
}

private const val FLY_MS = 520
