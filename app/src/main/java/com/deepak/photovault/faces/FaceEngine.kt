package com.deepak.photovault.faces

import android.content.Context
import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN
import org.opencv.objdetect.FaceRecognizerSF
import java.io.File
import kotlin.math.sqrt

/**
 * Finds faces in a picture and measures each one (People, 7A). Everything runs on the phone: OpenCV's YuNet finds the
 * faces, SFace turns each into 128 numbers. Both models ship inside the app (Apache 2.0 / MIT, from OpenCV Zoo).
 * Not thread-safe: [FaceLibrary] uses one engine at a time.
 */
class FaceEngine private constructor(
    private val finder: FaceDetectorYN,
    private val measurer: FaceRecognizerSF,
    /** AuraFace (the test model, 6 Nov), when chosen: measures faces instead of SFace. */
    private val aura: org.tensorflow.lite.Interpreter?,
) {
    fun close() { runCatching { aura?.close() } }

    /** One face: where it is, its measurements, how much it faces the camera (0 side to 1 front) and how sharp it is. */
    class Found(val box: FloatArray, val vector: FloatArray, val frontal: Float, val sharp: Float, val eyes: Float, val strength: Float, val clothes: FloatArray?)

    /** Faces in the last photo that were left out only for being too small (6 Nov): worth a bigger second look. */
    var smallSkipped = 0
        private set

    fun find(bitmap: Bitmap): List<Found> {
        smallSkipped = 0
        val rgba = Mat()
        val bgr = Mat()
        val faces = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            finder.inputSize = Size(bgr.cols().toDouble(), bgr.rows().toDouble())
            finder.detect(bgr, faces)
            val w = bgr.cols().toFloat()
            val h = bgr.rows().toFloat()
            val out = mutableListOf<Found>()
            val rows = (0 until faces.rows()).map { i -> FloatArray(15).also { faces.get(i, 0, it) } }
            // Passers-by (5 Nov): a face much smaller than the biggest one in the same photo is left out.
            val biggest = rows.maxOfOrNull { it[2] } ?: 0f
            for (i in rows.indices) {
                val row = rows[i]
                // Faces smaller than this can't be told apart reliably; they are left out.
                if (row[2] < biggest * BACKGROUND_SHARE) continue
                if (row[2] < MIN_FACE_PX || row[3] < MIN_FACE_PX) {
                    if (row[2] >= SMALL_PX) smallSkipped++
                    continue
                }
                val aligned = Mat()
                val feature = Mat()
                val one = faces.row(i)
                try {
                    measurer.alignCrop(bgr, one, aligned)
                    val (sharp, eyes) = quality(aligned)
                    // Out of focus (a blurred face in the background): left out.
                    if (sharp < BLURRY) continue
                    // Coloured light (6 Nov): even out the colour cast (purple and magenta stage lights) before measuring.
                    neutralise(aligned)
                    // Dark or backlit faces (6 Nov): brightened before measuring.
                    brighten(aligned)
                    val v: FloatArray
                    val norm: Float
                    if (aura != null) {
                        v = auraVector(aligned)
                        norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
                        // Mirror trick for AuraFace too (6 Nov), as SFace has had since 5 Nov.
                        val flipped = Mat()
                        try {
                            org.opencv.core.Core.flip(aligned, flipped, 1)
                            val m = auraVector(flipped)
                            for (k in v.indices) v[k] += m[k]
                        } finally {
                            flipped.release()
                        }
                    } else {
                        measurer.feature(aligned, feature)
                        v = FloatArray(feature.cols() * feature.rows())
                        feature.get(0, 0, v)
                        norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
                        // Mirror trick (5 Nov): measure the face flipped left-right too and add the two, which steadies
                        // the measurement (a standard trick for this kind of model); fewer splits of one person.
                        val flipped = Mat()
                        val mirror = Mat()
                        try {
                            org.opencv.core.Core.flip(aligned, flipped, 1)
                            measurer.feature(flipped, mirror)
                            val m = FloatArray(mirror.cols() * mirror.rows())
                            mirror.get(0, 0, m)
                            if (m.size == v.size) for (k in v.indices) v[k] += m[k]
                        } finally {
                            flipped.release(); mirror.release()
                        }
                    }
                    val both = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
                    if (norm == 0f || both == 0f) continue
                    for (k in v.indices) v[k] /= both
                    val box = floatArrayOf(
                        (row[0] / w).coerceIn(0f, 1f),
                        (row[1] / h).coerceIn(0f, 1f),
                        (row[2] / w).coerceIn(0f, 1f),
                        (row[3] / h).coerceIn(0f, 1f),
                    )
                    out += Found(box, v, frontal(row), sharp, eyes, norm, clothes(bgr, row))
                } finally {
                    aligned.release()
                    feature.release()
                    one.release()
                }
            }
            return out
        } finally {
            rgba.release()
            bgr.release()
            faces.release()
        }
    }

    /**
     * Gray-world colour balance (6 Nov): scales blue, green and red so each averages the same, which takes most of a
     * coloured light's cast off the face. Gains are kept between 0.6 and 1.6 so a truly colourful face isn't wrecked.
     */
    private fun neutralise(aligned: Mat) {
        val m = org.opencv.core.Core.mean(aligned).`val`
        val avg = (m[0] + m[1] + m[2]) / 3.0
        if (avg < 1.0) return
        fun gain(c: Double) = if (c < 1.0) 1.0 else (avg / c).coerceIn(0.6, 1.6)
        org.opencv.core.Core.multiply(aligned, org.opencv.core.Scalar(gain(m[0]), gain(m[1]), gain(m[2])), aligned)
    }

    /**
     * Brightens a dark face (6 Nov): when the lined-up face's average brightness is under [DARK], a curve lifts it to
     * about [LIFT_TO]. Faces in normal light are left as they are.
     */
    private fun brighten(aligned: Mat) {
        val gray = Mat()
        try {
            Imgproc.cvtColor(aligned, gray, Imgproc.COLOR_BGR2GRAY)
            val mean = org.opencv.core.Core.mean(gray).`val`[0]
            if (mean >= DARK || mean < 4.0) return
            val gamma = kotlin.math.ln(LIFT_TO / 255.0) / kotlin.math.ln(mean / 255.0)
            val table = Mat(1, 256, org.opencv.core.CvType.CV_8U)
            try {
                val bytes = ByteArray(256) { i -> (255.0 * Math.pow(i / 255.0, gamma)).toInt().coerceIn(0, 255).toByte() }
                table.put(0, 0, bytes)
                org.opencv.core.Core.LUT(aligned, table, aligned)
            } finally {
                table.release()
            }
        } finally {
            gray.release()
        }
    }

    /**
     * What the person wears (6 Nov): a colour count (hue x saturation, plus how dark) of the area just under the face,
     * about a face and a half deep and twice as wide. Square roots of the shares, so comparing two gives a likeness from
     * 0 to 1. Null when too little of it is in the photo.
     */
    private fun clothes(bgr: Mat, row: FloatArray): FloatArray? {
        val fw = row[2]
        val fh = row[3]
        val x0 = (row[0] - fw * 0.5f).toInt().coerceAtLeast(0)
        val x1 = (row[0] + fw * 1.5f).toInt().coerceAtMost(bgr.cols())
        val y0 = (row[1] + fh * 1.25f).toInt().coerceAtLeast(0)
        val y1 = (row[1] + fh * 2.75f).toInt().coerceAtMost(bgr.rows())
        val want = fw * 2f * fh * 1.5f
        if (x1 - x0 < 8 || y1 - y0 < 8 || (x1 - x0) * (y1 - y0) < want * 0.4f) return null
        val part = bgr.submat(org.opencv.core.Rect(x0, y0, x1 - x0, y1 - y0))
        val small = Mat()
        val hsv = Mat()
        try {
            Imgproc.resize(part, small, Size(32.0, 24.0), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.cvtColor(small, hsv, Imgproc.COLOR_BGR2HSV)
            val px = ByteArray(32 * 24 * 3)
            hsv.get(0, 0, px)
            // 8 hues x 2 strengths for coloured pixels, then 4 shades from black to white for grey ones.
            val counts = FloatArray(8 * 2 + 4)
            for (p in 0 until 32 * 24) {
                val h = px[p * 3].toInt() and 0xff
                val sat = px[p * 3 + 1].toInt() and 0xff
                val v = px[p * 3 + 2].toInt() and 0xff
                if (sat < 50 || v < 40) counts[16 + (v * 4 / 256).coerceIn(0, 3)]++
                else counts[(h * 8 / 180).coerceIn(0, 7) * 2 + if (sat < 140) 0 else 1]++
            }
            val total = counts.sum()
            if (total <= 0f) return null
            return FloatArray(counts.size) { sqrt(counts[it] / total) }
        } finally {
            part.release(); small.release(); hsv.release()
        }
    }

    /** AuraFace: the lined-up 112 x 112 face in RGB, scaled to -1..1, gives 512 numbers. */
    private fun auraVector(aligned: Mat): FloatArray {
        val bytes = ByteArray(112 * 112 * 3)
        aligned.get(0, 0, bytes)
        val input = java.nio.ByteBuffer.allocateDirect(4 * bytes.size).order(java.nio.ByteOrder.nativeOrder())
        for (p in 0 until 112 * 112) {
            val b = bytes[p * 3].toInt() and 0xff
            val g = bytes[p * 3 + 1].toInt() and 0xff
            val r = bytes[p * 3 + 2].toInt() and 0xff
            input.putFloat((r - 127.5f) / 127.5f)
            input.putFloat((g - 127.5f) / 127.5f)
            input.putFloat((b - 127.5f) / 127.5f)
        }
        input.rewind()
        val output = Array(1) { FloatArray(512) }
        aura!!.run(input, output)
        return output[0]
    }

    /**
     * How crisp the 112 x 112 lined-up face is (the spread of its edges, variance of the Laplacian), and how visible
     * the eyes are: the eye areas' brightness against the cheeks below them (dark glasses make it low). In the lined-up
     * face the eyes are always near (38, 52) and (74, 52).
     */
    private fun quality(aligned: Mat): Pair<Float, Float> {
        val gray = Mat()
        val edges = Mat()
        val mean = org.opencv.core.MatOfDouble()
        val std = org.opencv.core.MatOfDouble()
        try {
            Imgproc.cvtColor(aligned, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.Laplacian(gray, edges, org.opencv.core.CvType.CV_64F)
            org.opencv.core.Core.meanStdDev(edges, mean, std)
            val d = std.toArray().firstOrNull() ?: 0.0
            fun area(x: Int, y: Int, w: Int, h: Int): Double {
                val part = gray.submat(org.opencv.core.Rect(x, y, w, h))
                return try { org.opencv.core.Core.mean(part).`val`[0] } finally { part.release() }
            }
            val eyes = (area(30, 46, 16, 12) + area(66, 46, 16, 12)) / 2
            val cheeks = (area(28, 66, 18, 14) + area(66, 66, 18, 14)) / 2
            val ratio = if (cheeks < 1.0) 1.0 else eyes / cheeks
            return (d * d).toFloat() to ratio.toFloat()
        } finally {
            gray.release(); edges.release(); mean.release(); std.release()
        }
    }

    /**
     * 1 when the nose sits midway between the eyes (facing the camera), down to 0 for a side view. YuNet gives the
     * eyes at [4..7] and the nose tip at [8, 9].
     */
    private fun frontal(row: FloatArray): Float {
        val eyeGap = kotlin.math.abs(row[6] - row[4])
        if (eyeGap < 1f) return 0f
        val middle = (row[4] + row[6]) / 2f
        val off = kotlin.math.abs(row[8] - middle) / eyeGap
        // An eye gap far narrower than the face also means the head is turned.
        val narrow = (eyeGap / row[2]).coerceIn(0f, 0.45f) / 0.45f
        val sideways = ((1f - (off / 0.5f).coerceIn(0f, 1f)) * (0.4f + 0.6f * narrow)).coerceIn(0f, 1f)
        // Looking down or up (5 Nov): where the nose tip sits between the eyes and the mouth. Facing the camera it is a
        // little over half way; looking down it slides toward the mouth, looking up toward the eyes.
        val eyesY = (row[5] + row[7]) / 2f
        val mouthY = (row[11] + row[13]) / 2f
        val span = mouthY - eyesY
        if (span < 1f) return 0f
        val r = (row[9] - eyesY) / span
        val upDown = (1f - (kotlin.math.abs(r - 0.58f) / 0.22f)).coerceIn(0f, 1f)
        return minOf(sideways, upDown)
    }

    companion object {
        const val MIN_FACE_PX = 36f

        /** Faces at least this wide but under [MIN_FACE_PX] get a second, bigger look at the photo (6 Nov). */
        const val SMALL_PX = 14f

        /** A lined-up face darker than this on average is brightened to about [LIFT_TO] (0 black to 255 white). */
        const val DARK = 80.0
        const val LIFT_TO = 115.0

        /** Faces under this share of the biggest face's width in the same photo are background faces. */
        const val BACKGROUND_SHARE = 0.33f

        /** Below this sharpness a face is too blurred to count (raised from 25 on 5 Nov: motion-blurred faces got in). */
        const val BLURRY = 60f
        private const val FINDER = "face_detection_yunet_2023mar.onnx"
        private const val MEASURER = "face_recognition_sface_2021dec.onnx"

        /** AuraFace v1 (fal.ai, Apache 2.0), int8 TFLite conversion; downloaded once when the test is turned on. */
        const val AURA_FILE = "auraface_v1_glintr100_wi8.tflite"
        const val AURA_URL = "https://huggingface.co/eungsang/auraface-v1-tflite/resolve/ac623dbbf0d4e883203f76a30c365bcc967fed3a/auraface_v1_glintr100_wi8.tflite"
        const val AURA_SHA256 = "56c854e363b7ae99622deed87e27d95bda239b87dd145be565a71ed64d72ae46"

        fun auraFile(context: Context) = File(context.filesDir, "people/models/$AURA_FILE")

        /** Why the last [create] failed, in the phone's own words (6 Nov: shown so a failure can be fixed). */
        @Volatile var lastError: String? = null
            private set

        /** Null when OpenCV or the models can't be loaded on this phone. [useAura]: measure with AuraFace (must be downloaded). */
        fun create(context: Context, useAura: Boolean = false): FaceEngine? = runCatching {
            lastError = null
            if (!OpenCVLoader.initLocal()) { lastError = "OpenCV didn't load"; return null }
            val dir = File(context.filesDir, "people/models").apply { mkdirs() }
            fun model(name: String): String {
                val file = File(dir, name)
                if (!file.exists() || file.length() == 0L) {
                    context.assets.open("faces/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
                }
                return file.absolutePath
            }
            val finder = FaceDetectorYN.create(model(FINDER), "", Size(320.0, 320.0), 0.9f, 0.3f, 5000)
            val measurer = FaceRecognizerSF.create(model(MEASURER), "")
            val aura = if (useAura) {
                val file = auraFile(context)
                if (!file.exists()) { lastError = "the model file is missing"; return null }
                org.tensorflow.lite.Interpreter(file, org.tensorflow.lite.Interpreter.Options().setNumThreads(4))
            } else null
            FaceEngine(finder, measurer, aura)
        }.onFailure { e -> lastError = (e.message ?: e.javaClass.simpleName).take(300) }.getOrNull()
    }
}
