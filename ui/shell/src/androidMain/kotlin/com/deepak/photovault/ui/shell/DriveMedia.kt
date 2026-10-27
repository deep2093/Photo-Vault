package com.deepak.photovault.ui.shell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.deepak.photovault.core.backup.DriveTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Photos and videos that are only on the family drive (Phase 5, one timeline). They are fetched when first needed
 * and kept on the phone: thumbnails for good (small), full files in a cache that trims itself. Nothing here runs
 * unless a drive-only item is on screen. The app gives it a way to read the drive with [install].
 */
object DriveMedia {
    private var reader: (suspend (String, (ByteArray, Int) -> Unit) -> Boolean)? = null
    private var originals: File? = null
    private var thumbs: File? = null

    /** A few downloads at once; more only slows the router down. */
    private val gate = Semaphore(3)

    fun install(context: Context, read: suspend (String, (ByteArray, Int) -> Unit) -> Boolean) {
        reader = read
        originals = File(context.applicationContext.cacheDir, "drive-originals").apply { mkdirs() }
        thumbs = File(context.applicationContext.filesDir, "drive-thumbs").apply { mkdirs() }
        JpegThumbCache.install(context)
        // Earlier versions kept small, blurry previews of iPhone JPEGs under this name: drop them.
        thumbs?.listFiles { f -> f.name.endsWith("-q.jpg") }?.forEach { it.delete() }
    }

    private fun name(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) } + "." + path.substringAfterLast('.', "bin").take(5)
    }

    /** The full file if it is already on the phone; never downloads. */
    fun cached(path: String): File? = originals?.let { File(it, name(path)) }?.takeIf { it.exists() && it.length() > 0 }

    /** The full file, downloaded first if needed; null if the drive can't be reached (and nothing is cached). */
    suspend fun file(path: String): File? = withContext(Dispatchers.IO) {
        cached(path)?.let { it.setLastModified(System.currentTimeMillis()); return@withContext it }
        val dir = originals ?: return@withContext null
        val read = reader ?: return@withContext null
        gate.withPermit {
            cached(path)?.let { return@withPermit it }
            val target = File(dir, name(path))
            val temp = File(dir, target.name + ".part")
            val ok = try {
                FileOutputStream(temp).use { out -> read(path) { bytes, n -> out.write(bytes, 0, n) } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                temp.delete()
                throw e
            } catch (_: Exception) {
                false
            }
            if (!ok || !temp.renameTo(target)) {
                temp.delete()
                return@withPermit null
            }
            trim(dir, KEEP_BYTES)
            target
        }
    }

    private class EnoughBytes : RuntimeException()

    /**
     * The small preview picture most JPEGs carry in their first few kilobytes. Reads only the first 256 KB of the
     * file from the drive (25 Oct: fetching the whole photo for every tile took 5 seconds or more each, because the
     * drive is read one file at a time). Null when the file has no preview.
     */
    private suspend fun quickThumbnail(path: String, sizePx: Int): Bitmap? {
        val read = reader ?: return null
        val head = java.io.ByteArrayOutputStream()
        try {
            read(path) { bytes, n ->
                head.write(bytes, 0, minOf(n, HEAD_BYTES - head.size()))
                if (head.size() >= HEAD_BYTES) throw EnoughBytes()
            }
        } catch (_: EnoughBytes) {
        }
        val data = head.toByteArray()
        if (data.size < 1024) return null
        return runCatching {
            val exif = androidx.exifinterface.media.ExifInterface(java.io.ByteArrayInputStream(data))
            val preview = exif.thumbnailBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: return null
            // iPhone JPEGs carry a tiny 160 px preview; stretched over a tile it looks blurry (feedback 26 Oct).
            // Too small for this tile: use the real photo instead.
            if (maxOf(preview.width, preview.height) < sizePx * 0.8f) return null
            val degrees = when (exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees == 0f) preview else Bitmap.createBitmap(preview, 0, 0, preview.width, preview.height, android.graphics.Matrix().apply { postRotate(degrees) }, true)
        }.getOrNull()
    }

    /** A thumbnail of a drive photo, from the kept thumbnails or made from the downloaded file. */
    suspend fun thumbnail(path: String, sizePx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val dir = thumbs ?: return@withContext null
        val isJpeg = path.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") }
        val jpegKey = JpegThumbCache.key("drive", path, sizePx)
        // A JPEG whose own preview was too small: made once from the photo, kept in the capped JPEG thumbnail folder.
        if (isJpeg) JpegThumbCache.get(jpegKey)?.let { return@withContext it }
        val kept = File(dir, "${name(path)}-$sizePx.jpg")
        if (!isJpeg && kept.exists()) BitmapFactory.decodeFile(kept.path)?.let { return@withContext it }
        // A Normal backup keeps a small preview beside each photo (28 Oct): one small read instead of the whole photo.
        if (sizePx <= PREVIEW_MAX_PX) {
            driveThumbnail(path)?.let { bitmap ->
                if (isJpeg) JpegThumbCache.put(jpegKey, bitmap) else runCatching { FileOutputStream(kept).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
                return@withContext bitmap
            }
        }
        if (path.endsWith(".pxo")) {
            // Encrypted backup: the small picture stored beside it (also the only preview a video has).
            privateThumbnail(path, dir)?.let { bitmap ->
                runCatching { FileOutputStream(kept).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
                return@withContext bitmap
            }
        }
        // Small tiles: the photo's own preview picture, fetched from the first part of the file (about a fifth of a
        // second instead of seconds). Larger sizes and photos without a preview use the full file.
        if (sizePx <= QUICK_MAX_PX && cached(path) == null) {
            val quick = File(dir, "${name(path)}-q2-$sizePx.jpg")
            if (quick.exists()) BitmapFactory.decodeFile(quick.path)?.let { return@withContext it }
            quickThumbnail(path, sizePx)?.let { bitmap ->
                runCatching { FileOutputStream(quick).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
                return@withContext bitmap
            }
        }
        val original = file(path) ?: return@withContext null
        val bitmap = decodeSampled(original, sizePx) ?: return@withContext null
        if (isJpeg) JpegThumbCache.put(jpegKey, bitmap) else runCatching { FileOutputStream(kept).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
        bitmap
    }

    /** Photos with no preview on the drive (backed up before 28 Oct), so they are not asked for again this time. */
    private val noPreview = java.util.Collections.synchronizedSet(HashSet<String>())

    private suspend fun driveThumbnail(path: String): Bitmap? {
        val thumbPath = com.deepak.photovault.core.backup.NormalThumbs.pathFor(path) ?: return null
        if (thumbPath in noPreview) return null
        val read = reader ?: return null
        val bytes = java.io.ByteArrayOutputStream()
        val ok = gate.withPermit {
            try {
                read(thumbPath) { b, n -> bytes.write(b, 0, n) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
        val data = bytes.toByteArray()
        val bitmap = if (ok && data.isNotEmpty()) BitmapFactory.decodeByteArray(data, 0, data.size) else null
        if (bitmap == null) noPreview += thumbPath
        return bitmap
    }

    private suspend fun privateThumbnail(path: String, dir: File): Bitmap? {
        val thumbPath = com.deepak.photovault.core.backup.PrivatePaths.thumbFor(path) ?: return null
        val read = reader ?: return null
        val bytes = java.io.ByteArrayOutputStream()
        val ok = gate.withPermit {
            try {
                read(thumbPath) { b, n -> bytes.write(b, 0, n) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
        if (!ok) return null
        val data = bytes.toByteArray()
        return BitmapFactory.decodeByteArray(data, 0, data.size)
    }

    private fun decodeSampled(file: File, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Deletes the least recently used downloads once the cache is over [limit] bytes. */
    private fun trim(dir: File, limit: Long) {
        val files = dir.listFiles { f -> !f.name.endsWith(".part") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= limit) break
            total -= f.length()
            f.delete()
        }
    }

    fun pathOf(uri: String): String? = DriveTimeline.pathOf(uri)

    private const val KEEP_BYTES = 800L * 1024 * 1024
    private const val HEAD_BYTES = 256 * 1024
    private const val QUICK_MAX_PX = 256
    private const val PREVIEW_MAX_PX = 512
}
