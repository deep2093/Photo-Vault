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
class FaceEngine private constructor(private val finder: FaceDetectorYN, private val measurer: FaceRecognizerSF) {
    /** One face: where it is, its measurements, how much it faces the camera (0 side to 1 front) and how sharp it is. */
    class Found(val box: FloatArray, val vector: FloatArray, val frontal: Float, val sharp: Float, val eyes: Float, val strength: Float)

    fun find(bitmap: Bitmap): List<Found> {
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
                if (row[2] < MIN_FACE_PX || row[3] < MIN_FACE_PX) continue
                if (row[2] < biggest * BACKGROUND_SHARE) continue
                val aligned = Mat()
                val feature = Mat()
                val one = faces.row(i)
                try {
                    measurer.alignCrop(bgr, one, aligned)
                    val (sharp, eyes) = quality(aligned)
                    // Out of focus (a blurred face in the background): left out.
                    if (sharp < BLURRY) continue
                    measurer.feature(aligned, feature)
                    val v = FloatArray(feature.cols() * feature.rows())
                    feature.get(0, 0, v)
                    val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
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
                    val both = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
                    if (norm == 0f || both == 0f) continue
                    for (k in v.indices) v[k] /= both
                    val box = floatArrayOf(
                        (row[0] / w).coerceIn(0f, 1f),
                        (row[1] / h).coerceIn(0f, 1f),
                        (row[2] / w).coerceIn(0f, 1f),
                        (row[3] / h).coerceIn(0f, 1f),
                    )
                    out += Found(box, v, frontal(row), sharp, eyes, norm)
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

        /** Faces under this share of the biggest face's width in the same photo are background faces. */
        const val BACKGROUND_SHARE = 0.33f

        /** Below this sharpness a face is too blurred to count (raised from 25 on 5 Nov: motion-blurred faces got in). */
        const val BLURRY = 60f
        private const val FINDER = "face_detection_yunet_2023mar.onnx"
        private const val MEASURER = "face_recognition_sface_2021dec.onnx"

        /** Null when OpenCV or the models can't be loaded on this phone. */
        fun create(context: Context): FaceEngine? = runCatching {
            if (!OpenCVLoader.initLocal()) return null
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
            FaceEngine(finder, measurer)
        }.getOrNull()
    }
}
