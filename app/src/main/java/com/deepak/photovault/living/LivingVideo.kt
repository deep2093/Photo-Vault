package com.deepak.photovault.living

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.deepak.photovault.ui.shell.LivingMotion
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A living photo as an MP4 (7D-2, 8 Nov): the same motion as on screen, one slow move there and back
 * ([LivingMotion.SECONDS] each way), 30 frames a second, at most [LONG_SIDE] on the long side. Made on the phone with
 * Android's own video encoder; each frame is drawn, turned into the encoder's colour format and given its own time,
 * so the video plays at the right speed however fast the phone makes it.
 */
internal object LivingVideo {
    private const val LONG_SIDE = 1080
    private const val FPS = 30
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

    fun encode(background: Bitmap, subject: Bitmap?, focusX: Float, focusY: Float, ground: Float, out: File, onProgress: (Float) -> Unit) {
        val scale = LONG_SIDE.toFloat() / max(background.width, background.height)
        val w = even16(background.width * scale)
        val h = even16(background.height * scale)
        val frames = FPS * LivingMotion.SECONDS * 2
        val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MIME)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(frame)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            val pixels = IntArray(w * h)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var next = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val pts = next * 1_000_000L / FPS
                        if (next >= frames) {
                            codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            draw(canvas, paint, background, subject, focusX, focusY, ground, w, h, phase(next, frames))
                            frame.getPixels(pixels, 0, w, 0, 0, w, h)
                            val image = codec.getInputImage(index) ?: error("The video encoder gave no frame to fill.")
                            fill(image, pixels, w, h)
                            codec.queueInputBuffer(index, 0, w * h * 3 / 2, pts, 0)
                            next++
                            onProgress(next.toFloat() / frames)
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (buffer != null && info.size > 0 && muxerStarted) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            frame.recycle()
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    /** 0 to 1 and back over the video, eased at both ends like the screen's motion. */
    internal fun phase(frame: Int, frames: Int): Float {
        val p = frame.toFloat() / frames
        val x = if (p < 0.5f) p * 2 else (1 - p) * 2
        return x * x * (3 - 2 * x)
    }

    private fun even16(v: Float): Int = max(16, (v / 16f).roundToInt() * 16)

    private val matrix = Matrix()

    /** The same picture as the screen draws (see LivingPhoto): a still subject, the background growing behind it. */
    private fun draw(canvas: Canvas, paint: Paint, bg: Bitmap, subject: Bitmap?, fx: Float, fy: Float, ground: Float, w: Int, h: Int, t: Float) {
        canvas.drawColor(Color.BLACK)
        val fit = max(w.toFloat() / bg.width, h.toFloat() / bg.height) * LivingMotion.OVERSCAN
        val drawW = bg.width * fit
        val drawH = bg.height * fit
        val left = (w - drawW) / 2f
        val top = (h - drawH) / 2f
        fun layer(image: Bitmap, scale: Float, shift: Float, px: Float, py: Float) {
            val k = drawW / image.width
            matrix.reset()
            matrix.setScale(k, k)
            matrix.postTranslate(left, top)
            matrix.postScale(scale, scale, px, py)
            matrix.postTranslate(-shift * drawW, 0f)
            canvas.drawBitmap(image, matrix, paint)
        }
        if (subject == null) {
            layer(bg, LivingMotion.driftScale(t, false), LivingMotion.driftShift(t, false), left + drawW / 2f, top + drawH / 2f)
            return
        }
        val px = left + drawW * LivingMotion.pivot(fx)
        val py = top + drawH * LivingMotion.pivot(fy)
        val n = LivingMotion.STRIPS
        for (i in 0 until n) {
            val y0 = i.toFloat() / n
            val y1 = (i + 1).toFloat() / n
            val s = LivingMotion.backgroundScale((y0 + y1) / 2f, ground, t, false)
            val from = if (i == 0) 0f else py + (top + drawH * y0 - py) * s - 0.5f
            val to = if (i == n - 1) h.toFloat() else py + (top + drawH * y1 - py) * s + 0.5f
            canvas.save()
            canvas.clipRect(0f, from, w.toFloat(), to)
            layer(bg, s, 0f, px, py)
            canvas.restore()
        }
        layer(subject, 1f, 0f, px, py)
    }

    /** ARGB pixels into the encoder's YUV 4:2:0 frame, whatever its row and pixel steps. */
    private fun fill(image: Image, px: IntArray, w: Int, h: Int) {
        val planes = image.planes
        val yPlane = planes[0]
        val yBuf = yPlane.buffer
        val yRow = ByteArray(w)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                val c = px[base + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                yRow[x] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
            }
            if (yPlane.pixelStride == 1) {
                yBuf.position(y * yPlane.rowStride)
                yBuf.put(yRow, 0, w)
            } else {
                for (x in 0 until w) yBuf.put(y * yPlane.rowStride + x * yPlane.pixelStride, yRow[x])
            }
        }
        val uPlane = planes[1]
        val vPlane = planes[2]
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val cw = w / 2
        val ch = h / 2
        for (y in 0 until ch) {
            for (x in 0 until cw) {
                // The average of the 2 x 2 pixels this colour sample covers.
                var r = 0
                var g = 0
                var b = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val c = px[(y * 2 + dy) * w + x * 2 + dx]
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                }
                r /= 4
                g /= 4
                b /= 4
                val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                uBuf.put(y * uPlane.rowStride + x * uPlane.pixelStride, u)
                vBuf.put(y * vPlane.rowStride + x * vPlane.pixelStride, v)
            }
        }
    }
}
