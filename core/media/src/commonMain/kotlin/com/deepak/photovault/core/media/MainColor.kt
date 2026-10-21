package com.deepak.photovault.core.media

/**
 * The main colour of a photo for adaptive themes: its most colourful, reasonably bright area, or its
 * average when everything is grey. Same rule as Veyra's CoverColors.pickMain, without Android classes.
 *
 * [pixels] are ARGB ints from a tiny (about 6 x 6) copy of the photo. Returns an opaque ARGB int.
 */
object MainColor {
    fun pick(pixels: IntArray): Int? {
        if (pixels.isEmpty()) return null
        var best = 0
        var bestScore = -1f
        var r = 0L
        var g = 0L
        var b = 0L
        for (p in pixels) {
            val pr = (p shr 16) and 0xFF
            val pg = (p shr 8) and 0xFF
            val pb = p and 0xFF
            r += pr
            g += pg
            b += pb
            val max = maxOf(pr, pg, pb) / 255f
            val min = minOf(pr, pg, pb) / 255f
            val saturation = if (max == 0f) 0f else (max - min) / max
            val score = if (max < 0.15f) 0f else saturation * max
            if (score > bestScore) {
                bestScore = score
                best = p
            }
        }
        if (bestScore >= 0.12f) return best or (0xFF shl 24)
        val n = pixels.size
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }
}
