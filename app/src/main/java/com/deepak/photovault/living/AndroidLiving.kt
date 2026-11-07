package com.deepak.photovault.living

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.content.ContentValues
import android.content.Intent
import android.provider.MediaStore
import com.deepak.photovault.core.backup.BackupSelection
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.ui.shell.LivingLayers
import com.deepak.photovault.ui.shell.LivingPlatform
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentationResult
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Living photos on Android (7D-1, 8 Nov). Google's on-device subject cut-out (ML Kit through Play services: the photo
 * never leaves the phone) finds the person, pet or thing; Pixallery fills the space behind them from the colours
 * around it and keeps both layers in the app's cache, so a photo made once opens straight away next time.
 */
class AndroidLiving(context: Context) : LivingPlatform {
    private val app = context.applicationContext
    private val lock = Mutex()

    override val supported: Boolean get() = true

    override suspend fun make(item: MediaItem): LivingLayers = lock.withLock {
        withContext(Dispatchers.Default) {
            val dir = File(app.cacheDir, "living").apply { mkdirs() }
            val name = hash(BackupSelection.key(item))
            val bgFile = File(dir, "$name-bg.jpg")
            val fgFile = File(dir, "$name-fg.png")
            val infoFile = File(dir, "$name.txt")
            readBack(bgFile, fgFile, infoFile)?.let { return@withContext it }
            val photo = decode(Uri.parse(item.uri)) ?: return@withContext LivingLayers(null, null, note = "Couldn't open this photo.")
            val mask = runCatching { subjectMask(photo) }.getOrElse { e ->
                return@withContext LivingLayers(photo.asImageBitmap(), null, note = whyNot(e))
            }
            val w = photo.width
            val h = photo.height
            var count = 0
            var sx = 0.0
            var sy = 0.0
            for (i in mask.indices) if (mask[i] > 0.5f) {
                count++
                sx += i % w
                sy += i / w
            }
            val share = count.toFloat() / (w * h)
            if (count == 0 || share < 0.01f || share > 0.85f) {
                // Nothing clear to lift out: the whole photo drifts on its own.
                bgFile.outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                infoFile.writeText("0.5 0.5 none")
                return@withContext LivingLayers(photo.asImageBitmap(), null, note = NOTHING_FOUND)
            }
            val fx = (sx / count / w).toFloat()
            val fy = (sy / count / h).toFloat()
            val subject = cutOut(photo, mask)
            val background = fillBehind(photo, mask)
            runCatching {
                bgFile.outputStream().use { background.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                fgFile.outputStream().use { subject.compress(Bitmap.CompressFormat.PNG, 100, it) }
                infoFile.writeText("$fx $fy subject")
            }
            trimCache(dir)
            LivingLayers(background.asImageBitmap(), subject.asImageBitmap(), fx, fy)
        }
    }

    override suspend fun video(item: MediaItem, layers: LivingLayers, share: Boolean, onProgress: (Float) -> Unit): String? = lock.withLock {
        withContext(Dispatchers.Default) {
            val bg = layers.background?.asAndroidBitmap() ?: return@withContext "Couldn't make the video."
            val dir = File(app.cacheDir, "living").apply { mkdirs() }
            val file = File(dir, hash(BackupSelection.key(item)) + ".mp4")
            if (!file.exists() || file.length() == 0L) {
                val tmp = File(dir, file.name + ".tmp")
                try {
                    LivingVideo.encode(bg, layers.subject?.asAndroidBitmap(), layers.focusX, layers.focusY, tmp, onProgress)
                } catch (e: Throwable) {
                    tmp.delete()
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    return@withContext "Couldn't make the video on this phone."
                }
                tmp.renameTo(file)
            }
            onProgress(1f)
            if (share) {
                val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", file)
                val send = Intent(Intent.ACTION_SEND)
                    .setType("video/mp4")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                withContext(Dispatchers.Main) {
                    app.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
                null
            } else {
                saveToMovies(file, item)
            }
        }
    }

    /** Into Movies > Pixallery, dated like the photo so it sits next to it in the timeline. */
    private fun saveToMovies(file: File, item: MediaItem): String {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Living_" + item.displayName.substringBeforeLast('.') + ".mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Pixallery")
            put(MediaStore.Video.Media.DATE_TAKEN, item.takenAtMillis)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: return "Couldn't save the video."
        return try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("no stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            "Saved to Movies > Pixallery, next to the photo in your timeline."
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            "Couldn't save the video."
        }
    }

    private fun readBack(bg: File, fg: File, info: File): LivingLayers? {
        if (!bg.exists() || !info.exists()) return null
        val parts = runCatching { info.readText().trim().split(' ') }.getOrNull() ?: return null
        val back = BitmapFactory.decodeFile(bg.path) ?: return null
        val fx = parts.getOrNull(0)?.toFloatOrNull() ?: 0.5f
        val fy = parts.getOrNull(1)?.toFloatOrNull() ?: 0.5f
        if (parts.getOrNull(2) != "subject") return LivingLayers(back.asImageBitmap(), null, fx, fy, note = NOTHING_FOUND)
        val front = BitmapFactory.decodeFile(fg.path) ?: return null
        bg.setLastModified(System.currentTimeMillis())
        return LivingLayers(back.asImageBitmap(), front.asImageBitmap(), fx, fy)
    }

    /** The photo, upright, at most [MAX_SIDE] on its long side (enough for a phone screen, quick to work on). */
    private fun decode(uri: Uri): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val long = maxOf(info.size.width, info.size.height)
            if (long > MAX_SIDE) {
                val f = MAX_SIDE.toFloat() / long
                decoder.setTargetSize((info.size.width * f).toInt().coerceAtLeast(1), (info.size.height * f).toInt().coerceAtLeast(1))
            }
        }.let { if (it.config != Bitmap.Config.ARGB_8888) it.copy(Bitmap.Config.ARGB_8888, false) else it }
    }.getOrNull()

    /** How sure the cut-out is that each pixel belongs to the subject, 0 to 1. Waits once for Play services' model. */
    private suspend fun subjectMask(photo: Bitmap): FloatArray {
        val segmenter = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
        try {
            var tries = 0
            while (true) {
                try {
                    val result = run(segmenter.process(InputImage.fromBitmap(photo, 0)))
                    val buffer = result.foregroundConfidenceMask ?: error("no mask")
                    buffer.rewind()
                    return FloatArray(photo.width * photo.height).also { buffer.get(it) }
                } catch (e: MlKitException) {
                    // The model is still downloading through Play services the first time: wait and try again.
                    if (e.errorCode != MlKitException.UNAVAILABLE || ++tries > 20) throw e
                    delay(3000)
                }
            }
        } finally {
            runCatching { segmenter.close() }
        }
    }

    private suspend fun run(task: com.google.android.gms.tasks.Task<SubjectSegmentationResult>): SubjectSegmentationResult =
        suspendCancellableCoroutine { cont ->
            task.addOnSuccessListener { cont.resume(it) }
            task.addOnFailureListener { cont.resumeWithException(it) }
        }

    private fun whyNot(e: Throwable): String = when {
        e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE ->
            "Google Play services is still getting the cut-out ready. Try again in a minute, on Wi-Fi. For now it just drifts."
        else -> "Couldn't lift anything out of this photo, so it just drifts."
    }

    /** The subject alone: each pixel kept as much as the cut-out is sure of it, with a soft edge. */
    private fun cutOut(photo: Bitmap, mask: FloatArray): Bitmap {
        val w = photo.width
        val h = photo.height
        val px = IntArray(w * h)
        photo.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val m = mask[i]
            // Soften: below 0.35 gone, above 0.75 fully kept.
            val a = ((m - 0.35f) / 0.4f).coerceIn(0f, 1f)
            val alpha = (a * a * (3 - 2 * a) * 255).toInt()
            px[i] = (alpha shl 24) or (px[i] and 0xFFFFFF)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * The photo with the subject's space (and a little around it) filled from the colours nearby, so nothing shows
     * when the subject drifts. "Push-pull": shrink the photo step by step ignoring the hole, then grow it back,
     * letting the blurrier smaller steps fill the hole.
     */
    private fun fillBehind(photo: Bitmap, mask: FloatArray): Bitmap {
        val w = photo.width
        val h = photo.height
        val px = IntArray(w * h)
        photo.getPixels(px, 0, w, 0, 0, w, h)
        val hole = dilate(BooleanArray(w * h) { mask[it] > 0.25f }, w, h, maxOf(4, maxOf(w, h) / 60))
        val filled = Fill.pushPull(px, hole, w, h)
        return Bitmap.createBitmap(filled, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Grows the hole by [r] pixels (a square around each, done as two quick passes). */
    private fun dilate(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val rows = BooleanArray(w * h)
        for (y in 0 until h) {
            var last = -r - 1
            for (x in 0 until w) if (src[y * w + x]) last = x else if (x - last <= r) rows[y * w + x] = true
            last = w + r + 1
            for (x in w - 1 downTo 0) if (src[y * w + x]) { last = x; rows[y * w + x] = true } else if (last - x <= r) rows[y * w + x] = true
        }
        val out = BooleanArray(w * h)
        for (x in 0 until w) {
            var last = -r - 1
            for (y in 0 until h) if (rows[y * w + x]) { last = y; out[y * w + x] = true } else if (y - last <= r) out[y * w + x] = true
            last = h + r + 1
            for (y in h - 1 downTo 0) if (rows[y * w + x]) last = y else if (last - y <= r) out[y * w + x] = true
        }
        return out
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    /** Keeps the cache to the newest 40 photos. */
    private fun trimCache(dir: File) {
        val made = dir.listFiles { f -> f.name.endsWith("-bg.jpg") }?.sortedByDescending { it.lastModified() } ?: return
        made.drop(40).forEach { bg ->
            val base = bg.name.removeSuffix("-bg.jpg")
            listOf("$base-bg.jpg", "$base-fg.png", "$base.txt", "$base.mp4").forEach { File(dir, it).delete() }
        }
    }

    private companion object {
        const val MAX_SIDE = 1280
        const val NOTHING_FOUND = "No one stood out to lift off the background, so the whole photo drifts."
    }
}

/** Filling a hole in a picture from its edges (kept apart so it can be tested on its own). */
internal object Fill {
    /** [px] ARGB pixels; [hole] true where the colour should be made up. Returns new pixels, alpha full. */
    fun pushPull(px: IntArray, hole: BooleanArray, w: Int, h: Int): IntArray {
        // Level 0: colour and weight (0 in the hole).
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)
        val wt = FloatArray(w * h)
        for (i in px.indices) if (!hole[i]) {
            r[i] = ((px[i] shr 16) and 0xFF).toFloat()
            g[i] = ((px[i] shr 8) and 0xFF).toFloat()
            b[i] = (px[i] and 0xFF).toFloat()
            wt[i] = 1f
        }
        class Level(val w: Int, val h: Int, val r: FloatArray, val g: FloatArray, val b: FloatArray, val wt: FloatArray)
        val levels = mutableListOf(Level(w, h, r, g, b, wt))
        // Push: halve until tiny, colours averaged by weight.
        while (levels.last().w > 1 || levels.last().h > 1) {
            val s = levels.last()
            val nw = maxOf(1, (s.w + 1) / 2)
            val nh = maxOf(1, (s.h + 1) / 2)
            val nr = FloatArray(nw * nh)
            val ng = FloatArray(nw * nh)
            val nb = FloatArray(nw * nh)
            val nwt = FloatArray(nw * nh)
            for (y in 0 until s.h) for (x in 0 until s.w) {
                val i = y * s.w + x
                val k = s.wt[i]
                if (k == 0f) continue
                val j = (y / 2) * nw + (x / 2)
                nr[j] += s.r[i] * k
                ng[j] += s.g[i] * k
                nb[j] += s.b[i] * k
                nwt[j] += k
            }
            for (j in 0 until nw * nh) if (nwt[j] > 0f) {
                nr[j] /= nwt[j]; ng[j] /= nwt[j]; nb[j] /= nwt[j]
                nwt[j] = minOf(1f, nwt[j])
            }
            levels += Level(nw, nh, nr, ng, nb, nwt)
        }
        // Pull: from small to big, fill what is missing from the level below (smoothly, from its four neighbours).
        for (li in levels.size - 2 downTo 0) {
            val s = levels[li]
            val c = levels[li + 1]
            for (y in 0 until s.h) for (x in 0 until s.w) {
                val i = y * s.w + x
                val k = s.wt[i]
                if (k >= 1f) continue
                val fx = ((x + 0.5f) / 2f - 0.5f).coerceIn(0f, (c.w - 1).toFloat())
                val fy = ((y + 0.5f) / 2f - 0.5f).coerceIn(0f, (c.h - 1).toFloat())
                val x0 = fx.toInt()
                val y0 = fy.toInt()
                val x1 = minOf(c.w - 1, x0 + 1)
                val y1 = minOf(c.h - 1, y0 + 1)
                val ax = fx - x0
                val ay = fy - y0
                fun at(a: FloatArray): Float {
                    val top = a[y0 * c.w + x0] * (1 - ax) + a[y0 * c.w + x1] * ax
                    val bottom = a[y1 * c.w + x0] * (1 - ax) + a[y1 * c.w + x1] * ax
                    return top * (1 - ay) + bottom * ay
                }
                s.r[i] = s.r[i] * k + at(c.r) * (1 - k)
                s.g[i] = s.g[i] * k + at(c.g) * (1 - k)
                s.b[i] = s.b[i] * k + at(c.b) * (1 - k)
                s.wt[i] = 1f
            }
        }
        val out = IntArray(w * h)
        for (i in out.indices) {
            out[i] = if (!hole[i]) px[i] or (0xFF shl 24) else
                (0xFF shl 24) or (r[i].toInt().coerceIn(0, 255) shl 16) or (g[i].toInt().coerceIn(0, 255) shl 8) or b[i].toInt().coerceIn(0, 255)
        }
        return out
    }
}
