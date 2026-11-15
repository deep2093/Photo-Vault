package com.deepak.photovault.core.media

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The editor's pixel-by-pixel changes (15 Nov): the ones a colour matrix can't do. In this order: sharpness (from the
 * pixels around), highlights and shadows, vibrance, darker corners (vignette), grain; then, when given, the colour
 * matrix last. The preview runs this without the matrix (the screen adds it live while a slider moves); the saved
 * copy and a living photo's video run it the same way, so all three look alike.
 *
 * A big photo is done in bands of rows, each with a few rows above and below it for sharpness ([halo]).
 */
object EditPixels {
    /** Rows needed above and below a band, for sharpness on a picture [imageW] x [imageH]. */
    fun halo(imageW: Int, imageH: Int): Int = blurRadius(imageW, imageH) + 1

    /** Sharpness compares each pixel with the pixels around it; on a bigger picture, more of them (looks the same). */
    private fun blurRadius(imageW: Int, imageH: Int): Int = max(1, (max(imageW, imageH) / 1600f).roundToInt())

    /** Grain comes in little squares this many pixels wide, so it looks the same on the preview and the full photo. */
    private fun grainCell(imageW: Int, imageH: Int): Int = max(1, (max(imageW, imageH) / 1400f).roundToInt())

    /**
     * Works out [rows] rows of a picture [imageW] x [imageH] (ARGB pixels; see-through kept as it is). [src] holds
     * [above] rows before them and [below] rows after them (for sharpness; fewer at the picture's top and bottom).
     * [firstRow] is the picture row of the first one. [area] is the part of the picture that will be kept (the crop
     * frame), so the vignette sits on what is kept. [matrix] (4 x 5) is applied last when given. Returns the rows.
     */
    fun rows(
        edit: PhotoEdit,
        src: IntArray,
        imageW: Int,
        imageH: Int,
        rows: Int,
        above: Int,
        below: Int,
        firstRow: Int,
        area: CropBox = CropBox(),
        matrix: FloatArray? = null,
    ): IntArray {
        val w = imageW
        val out = IntArray(w * rows)
        val sharp = edit.sharpness / 100f
        val blurred = if (sharp > 0f) blur(src, w, rows + above + below, blurRadius(imageW, imageH)) else null
        val hi = edit.highlights / 100f
        val sh = edit.shadows / 100f
        val vib = edit.vibrance / 100f
        val vig = edit.vignetteAmount
        val grain = edit.grainAmount
        val cell = grainCell(imageW, imageH)
        val areaW = (area.width * imageW).coerceAtLeast(1f)
        val areaH = (area.height * imageH).coerceAtLeast(1f)
        val areaL = area.left * imageW
        val areaT = area.top * imageH
        val m = matrix
        for (r in 0 until rows) {
            val y = firstRow + r
            val srcRow = (r + above) * w
            val vy = ((y + 0.5f - areaT) / areaH - 0.5f) * 2f
            for (x in 0 until w) {
                val c = src[srcRow + x]
                val a = c ushr 24
                var red = ((c shr 16) and 0xFF).toFloat()
                var green = ((c shr 8) and 0xFF).toFloat()
                var blue = (c and 0xFF).toFloat()
                if (blurred != null) {
                    val b = blurred[srcRow + x]
                    red += sharp * 1.4f * (red - ((b shr 16) and 0xFF))
                    green += sharp * 1.4f * (green - ((b shr 8) and 0xFF))
                    blue += sharp * 1.4f * (blue - (b and 0xFF))
                }
                if (hi != 0f || sh != 0f) {
                    val l = ((0.2126f * red + 0.7152f * green + 0.0722f * blue) / 255f).coerceIn(0f, 1f)
                    val d = 255f * (tone(l, sh, hi) - l)
                    red += d
                    green += d
                    blue += d
                }
                if (vib != 0f) {
                    val l = 0.2126f * red + 0.7152f * green + 0.0722f * blue
                    val most = max(red, max(green, blue))
                    val least = min(red, min(green, blue))
                    val sat = ((most - least) / 255f).coerceIn(0f, 1f)
                    // Dull colours are lifted most; skin and already strong colours little.
                    val k = 1f + if (vib > 0f) vib * (1f - sat) * 0.9f else vib * 0.8f
                    red = l + (red - l) * k
                    green = l + (green - l) * k
                    blue = l + (blue - l) * k
                }
                if (vig > 0f) {
                    val vx = ((x + 0.5f - areaL) / areaW - 0.5f) * 2f
                    val f = 1f - vig * 0.72f * vignetteShape(vx, vy)
                    red *= f
                    green *= f
                    blue *= f
                }
                if (grain > 0f) {
                    val n = noise(x / cell, y / cell) * grain * 30f
                    red += n
                    green += n
                    blue += n
                }
                if (m != null) {
                    val r0 = red
                    val g0 = green
                    val b0 = blue
                    red = m[0] * r0 + m[1] * g0 + m[2] * b0 + m[4]
                    green = m[5] * r0 + m[6] * g0 + m[7] * b0 + m[9]
                    blue = m[10] * r0 + m[11] * g0 + m[12] * b0 + m[14]
                }
                out[r * w + x] = (a shl 24) or (clamp(red) shl 16) or (clamp(green) shl 8) or clamp(blue)
            }
        }
        return out
    }

    /** A whole small picture at once (the preview, a living photo's layers). */
    fun all(edit: PhotoEdit, px: IntArray, w: Int, h: Int, area: CropBox = CropBox(), matrix: FloatArray? = null): IntArray =
        rows(edit, px, w, h, h, 0, 0, 0, area, matrix)

    /**
     * Highlights and shadows on brightness [l] (0 to 1): shadows (+ lighter, - darker) works most on the darker
     * tones and leaves pure black and white alone; highlights the same on the brighter tones.
     */
    fun tone(l: Float, shadows: Float, highlights: Float): Float {
        val inv = 1f - l
        val shadowPart = 1.25f * l * inv * inv
        val lightPart = 1.25f * l * l * inv
        return (l + shadows * shadowPart + highlights * lightPart).coerceIn(0f, 1f)
    }

    /** 0 in the middle of the picture, rising smoothly to 1 in its corners. [x], [y] are -1 to 1 across and down. */
    fun vignetteShape(x: Float, y: Float): Float {
        val d = (sqrt(x * x + y * y) / 1.4142135f).coerceIn(0f, 1f)
        val t = ((d - 0.35f) / 0.65f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** The same speck every time for the same square: -1 to 1. */
    fun noise(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return ((h and 0xFFFF) / 32767.5f) - 1f
    }

    private fun clamp(v: Float): Int = when {
        v <= 0f -> 0
        v >= 255f -> 255
        else -> (v + 0.5f).toInt()
    }

    /** A box blur of [px] (w x h), [r] pixels each way, as ARGB (alpha ignored). Rows at the edges reuse the edge. */
    private fun blur(px: IntArray, w: Int, h: Int, r: Int): IntArray {
        val across = IntArray(w * h)
        val span = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sr = 0
            var sg = 0
            var sb = 0
            for (k in -r..r) {
                val c = px[row + k.coerceIn(0, w - 1)]
                sr += (c shr 16) and 0xFF
                sg += (c shr 8) and 0xFF
                sb += c and 0xFF
            }
            for (x in 0 until w) {
                across[row + x] = ((sr / span) shl 16) or ((sg / span) shl 8) or (sb / span)
                val out = px[row + (x - r).coerceIn(0, w - 1)]
                val inn = px[row + (x + r + 1).coerceIn(0, w - 1)]
                sr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                sg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                sb += (inn and 0xFF) - (out and 0xFF)
            }
        }
        val down = IntArray(w * h)
        for (x in 0 until w) {
            var sr = 0
            var sg = 0
            var sb = 0
            for (k in -r..r) {
                val c = across[k.coerceIn(0, h - 1) * w + x]
                sr += (c shr 16) and 0xFF
                sg += (c shr 8) and 0xFF
                sb += c and 0xFF
            }
            for (y in 0 until h) {
                down[y * w + x] = ((sr / span) shl 16) or ((sg / span) shl 8) or (sb / span)
                val out = across[(y - r).coerceIn(0, h - 1) * w + x]
                val inn = across[(y + r + 1).coerceIn(0, h - 1) * w + x]
                sr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                sg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                sb += (inn and 0xFF) - (out and 0xFF)
            }
        }
        return down
    }
}
