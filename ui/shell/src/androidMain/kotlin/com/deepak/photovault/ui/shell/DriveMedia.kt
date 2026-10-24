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

    /** A thumbnail of a drive photo, from the kept thumbnails or made from the downloaded file. */
    suspend fun thumbnail(path: String, sizePx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val dir = thumbs ?: return@withContext null
        val kept = File(dir, "${name(path)}-$sizePx.jpg")
        if (kept.exists()) BitmapFactory.decodeFile(kept.path)?.let { return@withContext it }
        val original = file(path) ?: return@withContext null
        val bitmap = decodeSampled(original, sizePx) ?: return@withContext null
        runCatching { FileOutputStream(kept).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
        bitmap
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
}
