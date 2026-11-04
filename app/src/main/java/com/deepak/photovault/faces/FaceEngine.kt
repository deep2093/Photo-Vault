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
    class Found(val box: FloatArray, val vector: FloatArray)

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
            for (i in 0 until faces.rows()) {
                val row = FloatArray(15)
                faces.get(i, 0, row)
                // Faces smaller than this can't be told apart reliably; they are left out.
                if (row[2] < MIN_FACE_PX || row[3] < MIN_FACE_PX) continue
                val aligned = Mat()
                val feature = Mat()
                val one = faces.row(i)
                try {
                    measurer.alignCrop(bgr, one, aligned)
                    measurer.feature(aligned, feature)
                    val v = FloatArray(feature.cols() * feature.rows())
                    feature.get(0, 0, v)
                    val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
                    if (norm == 0f) continue
                    for (k in v.indices) v[k] /= norm
                    val box = floatArrayOf(
                        (row[0] / w).coerceIn(0f, 1f),
                        (row[1] / h).coerceIn(0f, 1f),
                        (row[2] / w).coerceIn(0f, 1f),
                        (row[3] / h).coerceIn(0f, 1f),
                    )
                    out += Found(box, v)
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

    companion object {
        const val MIN_FACE_PX = 36f
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
            val finder = FaceDetectorYN.create(model(FINDER), "", Size(320.0, 320.0), 0.85f, 0.3f, 5000)
            val measurer = FaceRecognizerSF.create(model(MEASURER), "")
            FaceEngine(finder, measurer)
        }.getOrNull()
    }
}
