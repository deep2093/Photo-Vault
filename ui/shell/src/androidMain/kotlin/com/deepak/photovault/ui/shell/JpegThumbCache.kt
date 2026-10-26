package com.deepak.photovault.ui.shell

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Thumbnails Pixallery made itself for JPEGs whose own embedded preview is too small (iPhone photos, 26 Oct). Made once,
 * kept in the app's cache folder, oldest removed first once the folder passes [LIMIT_BYTES]. Only these thumbnails
 * count towards that limit; every other cache keeps its own rules.
 */
object JpegThumbCache {
    const val LIMIT_BYTES = 300L * 1024 * 1024
    private var dir: File? = null
    private var writes = 0

    fun install(context: Context) {
        dir = File(context.applicationContext.cacheDir, "jpeg-thumbs").apply { mkdirs() }
    }

    fun key(vararg parts: Any): String =
        MessageDigest.getInstance("SHA-256").digest(parts.joinToString("|").toByteArray()).take(16).joinToString("") { "%02x".format(it) }

    fun get(key: String): Bitmap? {
        val file = File(dir ?: return null, "$key.jpg")
        if (!file.exists()) return null
        file.setLastModified(System.currentTimeMillis())
        return BitmapFactory.decodeFile(file.path)
    }

    @Synchronized
    fun put(key: String, bitmap: Bitmap) {
        val folder = dir ?: return
        runCatching { FileOutputStream(File(folder, "$key.jpg")).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
        if (++writes % 25 == 0) trim(folder)
    }

    private fun trim(folder: File) {
        val files = folder.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= LIMIT_BYTES) break
            total -= f.length()
            f.delete()
        }
    }
}
