package com.deepak.photovault.core.media

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The photo editor's sums (Phase 11, 15 Nov): what an edit is, and the plain arithmetic behind turning, mirroring,
 * cropping and colour. No Android or Compose here, so every rule is unit tested and a PC app can reuse it.
 *
 * An edit never touches the original: the editor draws a preview from these numbers and the phone saves a new copy.
 */

/** Part of a picture, as fractions of its width and height (0 to 1), measured on the picture as the person sees it. */
data class CropBox(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** The whole picture (allowing for rounding). */
    val isFull: Boolean get() = left <= EDGE && top <= EDGE && right >= 1f - EDGE && bottom >= 1f - EDGE

    private companion object {
        const val EDGE = 0.0005f
    }
}

/** A ready-made look. Each is one colour matrix, so the preview and the saved copy match exactly. */
enum class EditFilter(val label: String) {
    NONE("Original"),
    VIVID("Vivid"),
    WARM("Warm"),
    COOL("Cool"),
    SOFT("Soft"),
    FADE("Fade"),
    SEPIA("Sepia"),
    MONO("Mono"),
    NOIR("Noir"),
}

/** The crop shapes offered. [ratio] is width over height in pixels; null means any shape. */
enum class CropAspect(val label: String, val ratio: Float?) {
    FREE("Free", null),
    ORIGINAL("Original", null),
    SQUARE("1:1", 1f),
    FOUR_THREE("4:3", 4f / 3f),
    THREE_FOUR("3:4", 3f / 4f),
    SIXTEEN_NINE("16:9", 16f / 9f),
    NINE_SIXTEEN("9:16", 9f / 16f),
}

/** A corner of the crop frame. */
enum class CropCorner(val left: Boolean, val top: Boolean) {
    TOP_LEFT(true, true),
    TOP_RIGHT(false, true),
    BOTTOM_LEFT(true, false),
    BOTTOM_RIGHT(false, false),
}

/** One of the sliders under Adjust. */
enum class EditAdjust(val label: String) {
    BRIGHTNESS("Brightness"),
    CONTRAST("Contrast"),
    SATURATION("Saturation"),
    WARMTH("Warmth"),
}

/**
 * Everything the editor can change. The picture is first turned by [turns] quarter turns clockwise, then mirrored
 * left to right when [flipped], then cut to [crop]; colour comes last. Sliders run from -100 to 100, 0 = untouched.
 */
data class PhotoEdit(
    val turns: Int = 0,
    val flipped: Boolean = false,
    val crop: CropBox = CropBox(),
    val brightness: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
    val warmth: Int = 0,
    val filter: EditFilter = EditFilter.NONE,
) {
    /** Nothing to save: the copy would be the same picture. */
    val isUnchanged: Boolean
        get() = turns % 4 == 0 && !flipped && crop.isFull && !hasColour

    val hasColour: Boolean
        get() = brightness != 0 || contrast != 0 || saturation != 0 || warmth != 0 || filter != EditFilter.NONE

    fun value(of: EditAdjust): Int = when (of) {
        EditAdjust.BRIGHTNESS -> brightness
        EditAdjust.CONTRAST -> contrast
        EditAdjust.SATURATION -> saturation
        EditAdjust.WARMTH -> warmth
    }

    fun with(of: EditAdjust, value: Int): PhotoEdit {
        val v = value.coerceIn(-100, 100)
        return when (of) {
            EditAdjust.BRIGHTNESS -> copy(brightness = v)
            EditAdjust.CONTRAST -> copy(contrast = v)
            EditAdjust.SATURATION -> copy(saturation = v)
            EditAdjust.WARMTH -> copy(warmth = v)
        }
    }

    /**
     * A quarter turn clockwise as the person sees it; the crop frame turns with the picture. A mirrored picture
     * turned clockwise is the unmirrored one turned the other way, then mirrored.
     */
    fun turnedRight(): PhotoEdit = copy(
        turns = (turns + if (flipped) 3 else 1) % 4,
        crop = CropBox(1f - crop.bottom, crop.left, 1f - crop.top, crop.right),
    )

    /** A quarter turn anticlockwise as the person sees it. */
    fun turnedLeft(): PhotoEdit = copy(
        turns = (turns + if (flipped) 1 else 3) % 4,
        crop = CropBox(crop.top, 1f - crop.right, crop.bottom, 1f - crop.left),
    )

    /** Mirrored left to right as the person sees it; the crop frame mirrors too. */
    fun mirrored(): PhotoEdit = copy(
        flipped = !flipped,
        crop = CropBox(1f - crop.right, crop.top, 1f - crop.left, crop.bottom),
    )

    /** Whether the picture as seen is lying on its side compared with the original (width and height swap). */
    val sideways: Boolean get() = turns % 2 != 0
}

object EditMath {
    /** The crop frame never gets smaller than this share of a side, so its corners can always be grabbed. */
    const val MIN_SIDE = 0.08f

    private val IDENTITY = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    // How bright each colour looks to the eye (Rec. 709).
    private const val LR = 0.2126f
    private const val LG = 0.7152f
    private const val LB = 0.0722f

    fun identity(): FloatArray = IDENTITY.copyOf()

    /**
     * [first] then [then], as one 4 x 5 colour matrix (rows R G B A; the fifth column is added on, 0 to 255, as in
     * Android's ColorMatrix and Compose's).
     */
    fun concat(then: FloatArray, first: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (row in 0 until 4) {
            for (col in 0 until 5) {
                var sum = 0f
                for (k in 0 until 4) sum += then[row * 5 + k] * first[k * 5 + col]
                if (col == 4) sum += then[row * 5 + 4]
                out[row * 5 + col] = sum
            }
        }
        return out
    }

    /** More or less colour: 0 = grey, 1 = as it is, 2 = twice as strong. */
    fun saturationMatrix(s: Float): FloatArray {
        val r = LR * (1f - s)
        val g = LG * (1f - s)
        val b = LB * (1f - s)
        return floatArrayOf(
            r + s, g, b, 0f, 0f,
            r, g + s, b, 0f, 0f,
            r, g, b + s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** Stretch or squeeze around mid grey: 1 = as it is. */
    fun contrastMatrix(c: Float): FloatArray {
        val shift = 127.5f * (1f - c)
        return floatArrayOf(
            c, 0f, 0f, 0f, shift,
            0f, c, 0f, 0f, shift,
            0f, 0f, c, 0f, shift,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** Each colour multiplied by its own number, then [lift] added to all three. */
    fun scaleMatrix(r: Float, g: Float, b: Float, lift: Float = 0f): FloatArray = floatArrayOf(
        r, 0f, 0f, 0f, lift,
        0f, g, 0f, 0f, lift,
        0f, 0f, b, 0f, lift,
        0f, 0f, 0f, 1f, 0f,
    )

    private val SEPIA = floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    fun filterMatrix(filter: EditFilter): FloatArray = when (filter) {
        EditFilter.NONE -> identity()
        EditFilter.VIVID -> concat(contrastMatrix(1.12f), saturationMatrix(1.35f))
        EditFilter.WARM -> concat(scaleMatrix(1.08f, 1.01f, 0.90f), saturationMatrix(1.08f))
        EditFilter.COOL -> concat(scaleMatrix(0.92f, 1.0f, 1.09f), saturationMatrix(1.05f))
        EditFilter.SOFT -> concat(scaleMatrix(1f, 1f, 1f, lift = 10f), concat(contrastMatrix(0.9f), saturationMatrix(0.9f)))
        EditFilter.FADE -> concat(scaleMatrix(0.9f, 0.9f, 0.92f, lift = 28f), saturationMatrix(0.75f))
        EditFilter.SEPIA -> concat(contrastMatrix(0.95f), SEPIA.copyOf())
        EditFilter.MONO -> saturationMatrix(0f)
        EditFilter.NOIR -> concat(contrastMatrix(1.35f), saturationMatrix(0f))
    }

    /** The sliders alone, as one matrix. */
    fun adjustMatrix(edit: PhotoEdit): FloatArray {
        var m = identity()
        if (edit.saturation != 0) m = concat(saturationMatrix(1f + edit.saturation / 100f), m)
        if (edit.warmth != 0) {
            val w = edit.warmth / 100f
            m = concat(scaleMatrix(1f + 0.16f * w, 1f + 0.02f * w, 1f - 0.16f * w), m)
        }
        if (edit.contrast != 0) m = concat(contrastMatrix(1f + edit.contrast / 200f), m)
        if (edit.brightness != 0) m = concat(scaleMatrix(1f, 1f, 1f, lift = edit.brightness * 0.7f), m)
        return m
    }

    /** The whole colour change of [edit]: the look first, then the sliders on top of it. */
    fun colourMatrix(edit: PhotoEdit): FloatArray =
        if (!edit.hasColour) identity() else concat(adjustMatrix(edit), filterMatrix(edit.filter))

    /** One pixel through [m]: for tests, and to show what a matrix does. Colours and the answer are 0 to 255. */
    fun apply(m: FloatArray, r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        fun row(i: Int) = (m[i * 5] * r + m[i * 5 + 1] * g + m[i * 5 + 2] * b + m[i * 5 + 4]).coerceIn(0f, 255f)
        return Triple(row(0), row(1), row(2))
    }

    /** Keeps a frame inside the picture and no smaller than [MIN_SIDE]. */
    fun clamp(box: CropBox): CropBox {
        val w = box.width.coerceIn(MIN_SIDE, 1f)
        val h = box.height.coerceIn(MIN_SIDE, 1f)
        val l = box.left.coerceIn(0f, 1f - w)
        val t = box.top.coerceIn(0f, 1f - h)
        return CropBox(l, t, l + w, t + h)
    }

    /** The frame slid by ([dx], [dy]) without changing size, stopping at the picture's edges. */
    fun move(box: CropBox, dx: Float, dy: Float): CropBox {
        val l = (box.left + dx).coerceIn(0f, max(0f, 1f - box.width))
        val t = (box.top + dy).coerceIn(0f, max(0f, 1f - box.height))
        return CropBox(l, t, l + box.width, t + box.height)
    }

    /**
     * The frame after [corner] is dragged to ([x], [y]); the opposite corner stays put. With [ratio] (the frame's
     * width over height in these 0-to-1 units, see [boxRatio]) the shape is kept.
     */
    fun resize(box: CropBox, corner: CropCorner, x: Float, y: Float, ratio: Float? = null): CropBox {
        val fixedX = if (corner.left) box.right else box.left
        val fixedY = if (corner.top) box.bottom else box.top
        val roomW = if (corner.left) fixedX else 1f - fixedX
        val roomH = if (corner.top) fixedY else 1f - fixedY
        var w = (if (corner.left) fixedX - x else x - fixedX).coerceIn(min(MIN_SIDE, roomW), roomW)
        var h = (if (corner.top) fixedY - y else y - fixedY).coerceIn(min(MIN_SIDE, roomH), roomH)
        if (ratio != null && ratio > 0f && box.width > 0f && box.height > 0f) {
            // Grow or shrink by the average of what the finger asked for across and down (so a drag along one side
            // still resizes, at half speed), then fit the room there is.
            val scale = (w / box.width + h / box.height) / 2f
            w = box.width * scale
            h = w / ratio
            if (h > roomH) {
                h = roomH
                w = h * ratio
            }
            if (w > roomW) {
                w = roomW
                h = w / ratio
            }
            // Not smaller than the corners can be held by, where the room allows.
            val small = min(w, h)
            if (small < MIN_SIDE && small > 0f) {
                val grow = MIN_SIDE / small
                if (w * grow <= roomW && h * grow <= roomH) {
                    w *= grow
                    h *= grow
                }
            }
        }
        val l = if (corner.left) fixedX - w else fixedX
        val t = if (corner.top) fixedY - h else fixedY
        return CropBox(l, t, l + w, t + h)
    }

    /**
     * What a pixel shape ([aspect], width over height) is in frame units for a picture [imageW] x [imageH] as seen:
     * a square on a wide picture is a tall, narrow box in fractions.
     */
    fun boxRatio(aspect: Float, imageW: Int, imageH: Int): Float =
        if (imageW <= 0 || imageH <= 0) aspect else aspect * imageH / imageW

    /** The biggest frame of [aspect] (pixels, width over height) that fits inside [box], around the same middle. */
    fun fit(box: CropBox, aspect: Float, imageW: Int, imageH: Int): CropBox {
        val ratio = boxRatio(aspect, imageW, imageH)
        var w = box.width
        var h = w / ratio
        if (h > box.height) {
            h = box.height
            w = h * ratio
        }
        val cx = (box.left + box.right) / 2f
        val cy = (box.top + box.bottom) / 2f
        return CropBox(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    /** The frame in whole pixels of a picture [imageW] x [imageH]: left, top, width, height. Never empty. */
    fun pixels(box: CropBox, imageW: Int, imageH: Int): IntArray {
        val l = (box.left * imageW).roundToInt().coerceIn(0, max(0, imageW - 1))
        val t = (box.top * imageH).roundToInt().coerceIn(0, max(0, imageH - 1))
        val r = (box.right * imageW).roundToInt().coerceIn(l + 1, max(l + 1, imageW))
        val b = (box.bottom * imageH).roundToInt().coerceIn(t + 1, max(t + 1, imageH))
        return intArrayOf(l, t, r - l, b - t)
    }

    /** Which corner of [box] is within [reach] of ([x], [y]), nearest first; null when none is. All in frame units. */
    fun cornerNear(box: CropBox, x: Float, y: Float, reachX: Float, reachY: Float): CropCorner? =
        CropCorner.entries
            .map { c ->
                val dx = abs(x - if (c.left) box.left else box.right) / reachX
                val dy = abs(y - if (c.top) box.top else box.bottom) / reachY
                c to max(dx, dy)
            }
            .filter { it.second <= 1f }
            .minByOrNull { it.second }
            ?.first

    /** The name of the saved copy: "IMG_1234_edited.jpg". */
    fun copyName(displayName: String): String {
        val stem = displayName.substringBeforeLast('.', displayName).ifBlank { "Photo" }
        return (if (stem.endsWith("_edited")) stem else stem + "_edited") + ".jpg"
    }
}
