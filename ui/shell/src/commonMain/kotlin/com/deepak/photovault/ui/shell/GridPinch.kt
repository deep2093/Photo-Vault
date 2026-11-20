package com.deepak.photovault.ui.shell

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.sqrt

/*
 * The photo grid's pinch, made smooth (Phase 12, 20 Nov).
 *
 * Before: every 20 % of pinch changed the grid by one column and the photos glided to their new places, so the grid
 * moved in steps and the photo under the fingers could end up anywhere. Now the grid grows and shrinks with the fingers,
 * around the point between them. When the fingers pass a whole step the grid is laid out again with the new number of
 * columns, sized to match what is on screen, with the photo under the fingers kept where it is; a picture of the grid as
 * it was fades away over the new one, so the change never jumps. Letting go settles on the nearer of the two sizes.
 */

/** The numbers behind the pinch, kept apart so they are unit tested. */
internal object PinchMath {
    /** How far past a whole step (in columns) pinching out goes before one more column is laid out. */
    const val STEP_SLACK = 0.06f

    /** Pinching in past the biggest photos stops this far (the grid grows a little, then settles back). */
    const val MAX_STRETCH = 1.15f

    /** Pinching out past the smallest photos stops this far. */
    const val MAX_SQUEEZE = 1.12f

    /** Past the smallest photos by this much, a page with a next level goes there (Photos: to Months). */
    const val PAST_SMALLEST = 1.08f

    /**
     * The columns to lay out while pinching, when the fingers ask for [wanted] columns (any number, from the zoom so far)
     * and [shown] are laid out now. Pinching in lays out fewer columns only once the photos have grown to their new size,
     * so the grid on screen is never smaller than the screen; pinching out adds them as soon as the fingers are a little
     * past a step. Fast pinches can skip a step.
     */
    fun columnsWhilePinching(wanted: Float, shown: Int, min: Int, max: Int): Int {
        val next = when {
            wanted <= shown - 1 + 0.001f -> ceil(wanted - 0.001f).toInt()
            wanted > shown + STEP_SLACK -> ceil(wanted - STEP_SLACK).toInt()
            else -> shown
        }
        return next.coerceIn(min, max)
    }

    /** Where the grid settles when the fingers lift: the nearer of the sizes around [wanted], by how big the photos look. */
    fun columnsWhenLetGo(wanted: Float, shown: Int, min: Int, max: Int): Int {
        if (wanted >= shown || shown <= min) return shown.coerceIn(min, max)
        val lower = shown - 1
        val middle = sqrt(lower.toFloat() * shown)
        return (if (wanted <= middle) lower else shown).coerceIn(min, max)
    }

    /** How far the fingers may zoom from [start] columns: a little past the biggest and the smallest photos. */
    fun zoomRange(start: Int, min: Int, max: Int): ClosedFloatingPointRange<Float> =
        (start / (max * MAX_SQUEEZE))..(start * MAX_STRETCH / min)

    /** How big the grid of [shown] columns is drawn when the fingers ask for [wanted]: never under its own size, except past the smallest photos. */
    fun drawScale(wanted: Float, shown: Int, max: Int): Float {
        val scale = shown / wanted
        return if (shown >= max) scale else maxOf(1f, scale)
    }

    /** Keeps a picture drawn at [scale] and moved by [offset] over the whole [length]: no gap at either end. */
    fun clamp(offset: Float, scale: Float, length: Float): Float {
        val room = length * (1f - scale)
        return offset.coerceIn(minOf(0f, room), maxOf(0f, room))
    }
}

/** What the pinch needs from the grid it sizes. */
internal interface PinchableGrid {
    /** Columns laid out now (as last composed). */
    val columns: Int
    val minColumns: Int
    val maxColumns: Int
    val reduceMotion: Boolean
    val width: Float
    val height: Float

    /** The photo under [point], in the grid's own coordinates. */
    fun photoAt(point: Offset): Long?

    /** Where photo [id] is drawn now, in the grid's own coordinates; null when it isn't on screen. */
    fun tileOf(id: Long): Rect?

    /** The grid item that holds photo [id]. */
    fun indexOf(id: Long): Int?

    /** Lays the grid out with [columns] and remembers it as this page's size. */
    fun changeColumns(columns: Int)

    /** Pinched out past the smallest photos: true when the page went somewhere else (Photos: to Months). */
    fun pastSmallest(): Boolean

    /** A small tick for each new size. */
    fun tick()
}

/** The grid's side of the pinch, brought up to date by the grid each time it is composed. */
internal class GridPinchHost : PinchableGrid {
    override var columns: Int = 0
    override val minColumns: Int get() = com.deepak.photovault.designsystem.AdaptiveLayout.MIN_COLUMNS
    override var maxColumns: Int = com.deepak.photovault.designsystem.AdaptiveLayout.MIN_COLUMNS
    override var reduceMotion: Boolean = false
    override var width: Float = 0f
    override var height: Float = 0f
    var photoAtFn: (Offset) -> Long? = { null }
    var tileOfFn: (Long) -> Rect? = { null }
    var indexOfFn: (Long) -> Int? = { null }
    var changeColumnsFn: (Int) -> Unit = {}
    var pastSmallestFn: () -> Boolean = { false }
    var tickFn: () -> Unit = {}

    override fun photoAt(point: Offset): Long? = photoAtFn(point)
    override fun tileOf(id: Long): Rect? = tileOfFn(id)
    override fun indexOf(id: Long): Int? = indexOfFn(id)
    override fun changeColumns(columns: Int) = changeColumnsFn(columns)
    override fun pastSmallest(): Boolean = pastSmallestFn()
    override fun tick() = tickFn()
}

/**
 * The pinch in progress and how the grid is drawn meanwhile. Only the values read while drawing are state, so a pinch
 * never recomposes the grid except when its number of columns changes.
 */
@Stable
internal class GridPinch(private val scope: CoroutineScope, private val grid: LazyGridState) {
    lateinit var host: PinchableGrid

    /** From the first two-finger touch until the grid has settled: it is drawn through two recorded pictures. */
    var engaged by mutableStateOf(false)
        private set

    /** From the first new size until settled: the tiles jump to their places, as the fade covers the change. */
    var resized by mutableStateOf(false)
        private set

    /** The new size is laid out but not yet placed: only the old picture shows. */
    var hideLive by mutableStateOf(false)
        private set

    // The live grid is drawn at scale x point + offset; the old picture likewise with its own values.
    var scale by mutableFloatStateOf(1f)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set
    var ghostScale by mutableFloatStateOf(1f)
        private set
    var ghostX by mutableFloatStateOf(0f)
        private set
    var ghostY by mutableFloatStateOf(0f)
        private set
    var ghostAlpha by mutableFloatStateOf(0f)
        private set

    /** Which of the two pictures is the live one (the other is the old grid). */
    var liveIsA = true
        private set

    /** The live picture has been recorded at least once since it became the live one. */
    var recorded = false

    /** The old picture holds a recording to show. */
    var ghostReady = false
        private set

    private var start = 0
    private var zoom = 1f
    private var shown = 0
    private var centroid = Offset.Zero
    private var leftPage = false
    private var switching: Job? = null
    private var settling: Job? = null

    /** The columns the fingers ask for now (any number). */
    private val wanted: Float get() = start / zoom

    fun begin(at: Offset) {
        settling?.cancel()
        switching?.cancel()
        settling = null
        switching = null
        start = host.columns
        shown = start
        zoom = 1f
        centroid = at
        leftPage = false
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        ghostAlpha = 0f
        hideLive = false
        recorded = false
        engaged = true
    }

    fun move(zoomBy: Float, pan: Offset, at: Offset) {
        if (!engaged || leftPage) return
        val range = PinchMath.zoomRange(start, host.minColumns, host.maxColumns)
        val next = (zoom * zoomBy).coerceIn(range.start, range.endInclusive)
        val k = next / zoom
        zoom = next
        val to = at + pan
        centroid = to
        // Both pictures follow the fingers: they grow around the point between them and move with it.
        ghostScale *= k
        ghostX = to.x + k * (ghostX - at.x)
        ghostY = to.y + k * (ghostY - at.y)
        val s = PinchMath.drawScale(wanted, shown, host.maxColumns)
        val ks = s / scale
        offsetX = PinchMath.clamp(to.x + ks * (offsetX - at.x), s, host.width)
        offsetY = PinchMath.clamp(to.y + ks * (offsetY - at.y), s, host.height)
        scale = s
        if (switching?.isActive == true) return
        if (shown >= host.maxColumns && wanted >= host.maxColumns * PinchMath.PAST_SMALLEST && host.pastSmallest()) {
            leftPage = true
            rest()
            return
        }
        val columns = PinchMath.columnsWhilePinching(wanted, shown, host.minColumns, host.maxColumns)
        if (columns != shown) switching = scope.launch { switchTo(columns) }
    }

    fun end() {
        if (!engaged || leftPage) {
            if (leftPage) rest()
            return
        }
        settling = scope.launch {
            switching?.join()
            val columns = PinchMath.columnsWhenLetGo(wanted, shown, host.minColumns, host.maxColumns)
            if (columns != shown) settleInto(columns) else settleBack()
            rest()
        }
    }

    /** While pinching: the grid laid out with [columns], drawn at the size the fingers ask for, the old one fading. */
    private suspend fun switchTo(columns: Int) {
        val anchor = anchorNow()
        keepOldPicture()
        host.tick()
        host.changeColumns(columns)
        awaitColumns(columns)
        shown = columns
        val s = PinchMath.drawScale(wanted, shown, host.maxColumns)
        val seen = anchor?.let { (_, old) -> seenAt(old) }
        val now = anchor?.let { (id, _) -> placeRow(id, seen!!.y) }
        scale = s
        offsetX = PinchMath.clamp(if (now != null) seen!!.x - s * now.center.x else centroid.x * (1f - s), s, host.width)
        offsetY = PinchMath.clamp(if (now != null) seen!!.y - s * now.center.y else centroid.y * (1f - s), s, host.height)
        hideLive = false
        if (host.reduceMotion) ghostAlpha = 0f
        else animate(1f, 0f, animationSpec = tween(GHOST_MS)) { v, _ -> ghostAlpha = v }
    }

    /** Let go nearer the bigger photos: the new grid shows at its own size at once, the old picture grows into it as it fades. */
    private suspend fun settleInto(columns: Int) {
        val anchor = anchorNow()
        val grow = wanted / columns
        keepOldPicture()
        host.tick()
        host.changeColumns(columns)
        awaitColumns(columns)
        shown = columns
        zoom = start / columns.toFloat()
        val seen = anchor?.let { (_, old) -> seenAt(old) }
        val now = anchor?.let { (id, _) -> placeRow(id, seen!!.y) }
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        hideLive = false
        val s0 = ghostScale
        val x0 = ghostX
        val y0 = ghostY
        val s1 = s0 * grow
        val old = anchor?.second
        val x1 = if (now != null && old != null) now.center.x - s1 * old.center.x else centroid.x + grow * (x0 - centroid.x)
        val y1 = if (now != null && old != null) now.center.y - s1 * old.center.y else centroid.y + grow * (y0 - centroid.y)
        if (host.reduceMotion) {
            ghostAlpha = 0f
            return
        }
        animate(0f, 1f, animationSpec = tween(SETTLE_MS, easing = SettleEasing)) { t, _ ->
            ghostScale = s0 + (s1 - s0) * t
            ghostX = x0 + (x1 - x0) * t
            ghostY = y0 + (y1 - y0) * t
            ghostAlpha = 1f - t
        }
    }

    /** Let go nearer the size laid out: the grid eases back to its own size and place. */
    private suspend fun settleBack() {
        val s0 = scale
        val x0 = offsetX
        val y0 = offsetY
        if (host.reduceMotion || (s0 == 1f && x0 == 0f && y0 == 0f)) return
        animate(0f, 1f, animationSpec = tween(SETTLE_MS, easing = SettleEasing)) { t, _ ->
            scale = s0 + (1f - s0) * t
            offsetX = x0 * (1f - t)
            offsetY = y0 * (1f - t)
        }
    }

    /** Everything back to plain drawing. */
    private fun rest() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        ghostAlpha = 0f
        hideLive = false
        engaged = false
        resized = false
    }

    /** The picture recorded last becomes the old picture, drawn where the live one was; the other one records from now on. */
    private fun keepOldPicture() {
        ghostReady = recorded
        liveIsA = !liveIsA
        recorded = false
        ghostScale = scale
        ghostX = offsetX
        ghostY = offsetY
        ghostAlpha = 1f
        // Without an old picture to show, the grid stays on screen as it is laid out again.
        hideLive = ghostReady
        resized = true
    }

    /** The photo under the fingers and where its tile is, before the grid changes. */
    private fun anchorNow(): Pair<Long, Rect>? {
        val point = Offset((centroid.x - offsetX) / scale, (centroid.y - offsetY) / scale)
        val id = host.photoAt(point) ?: return null
        val tile = host.tileOf(id) ?: return null
        return id to tile
    }

    /** Where the centre of [tile] (old layout) is on screen now, in the old picture. */
    private fun seenAt(tile: Rect): Offset = Offset(ghostX + ghostScale * tile.center.x, ghostY + ghostScale * tile.center.y)

    /** Scrolls the new layout so photo [id]'s row is at height [y]; returns where its tile is then. */
    private suspend fun placeRow(id: Long, y: Float): Rect? {
        val index = host.indexOf(id) ?: return null
        grid.scrollToItem(index)
        host.tileOf(id)?.let { grid.scrollBy(it.center.y - y) }
        return host.tileOf(id)
    }

    /** Waits (a few frames at most) until the grid has been laid out with [columns]. */
    private suspend fun awaitColumns(columns: Int) {
        repeat(8) {
            if (host.columns == columns) return
            withFrameNanos { }
        }
    }

    private companion object {
        /** How long the old picture takes to fade over the new size while pinching. */
        const val GHOST_MS = 140

        /** How long the grid takes to settle after the fingers lift. */
        const val SETTLE_MS = 220
        val SettleEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    }
}

/**
 * Draws the grid through [pinch]'s two pictures while a pinch is under way (and normally otherwise): the live grid
 * recorded each frame and drawn at its size and place, and the old picture fading over it after each change of size.
 */
internal fun Modifier.pinchDrawing(pinch: GridPinch, a: GraphicsLayer, b: GraphicsLayer): Modifier =
    this.clipToBounds().drawWithContent {
        if (!pinch.engaged) {
            drawContent()
            return@drawWithContent
        }
        val live = if (pinch.liveIsA) a else b
        val old = if (pinch.liveIsA) b else a
        live.record { this@drawWithContent.drawContent() }
        pinch.recorded = true
        if (!pinch.hideLive) {
            live.alpha = 1f
            withTransform({
                translate(pinch.offsetX, pinch.offsetY)
                scale(pinch.scale, pinch.scale, Offset.Zero)
            }) { drawLayer(live) }
        }
        val fade = pinch.ghostAlpha
        if (fade > 0.001f && pinch.ghostReady) {
            old.alpha = fade
            withTransform({
                translate(pinch.ghostX, pinch.ghostY)
                scale(pinch.ghostScale, pinch.ghostScale, Offset.Zero)
            }) { drawLayer(old) }
        }
    }

/**
 * Two fingers on the grid drive [pinch]. Runs before the grid's own scrolling (Initial pass) and takes the fingers
 * while they pinch, so the grid never scrolls under a pinch; one finger is left to the grid as before.
 */
internal fun Modifier.pinchGesture(pinch: GridPinch): Modifier = pointerInput(pinch) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var pinching = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                val before = event.calculateCentroid(useCurrent = false)
                val at = if (before.isSpecified) before else event.calculateCentroid(useCurrent = true)
                if (!pinching) {
                    pinching = true
                    pinch.begin(at)
                }
                pinch.move(event.calculateZoom(), event.calculatePan(), at)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            } else if (pinching) {
                // One finger lifted: the other doesn't start scrolling the grid.
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (pinching) pinch.end()
    }
}
