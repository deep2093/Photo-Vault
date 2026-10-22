package com.deepak.photovault.backup

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.deepak.photovault.core.backup.BackupRecord
import com.deepak.photovault.core.backup.MediaReader
import com.deepak.photovault.core.backup.MediaWriter
import com.deepak.photovault.core.backup.PreparedFile
import com.deepak.photovault.core.backup.RestoreTarget
import com.deepak.photovault.core.drive.ByteSource
import com.deepak.photovault.core.media.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.math.sqrt

/** An input stream as a [ByteSource]. */
private class StreamSource(private val input: InputStream) : ByteSource {
    override fun read(buffer: ByteArray): Int = input.read(buffer)
    override fun close() = input.close()
}

/**
 * Reads phone photos for backup. Uses the original file, including where the photo was taken, when "photo
 * locations" access is granted (Android otherwise hands apps a copy with the location removed).
 *
 * Storage saver (24 Oct): JPEG and HEIC photos are shrunk to 16 megapixels (good-quality prints up to 24 x 16 in) and saved as JPEG at quality 85,
 * keeping date, camera and location details. If that wouldn't be smaller, the original is used. Videos never.
 */
class AndroidMediaReader(context: Context) : MediaReader {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    private fun sourceUri(item: MediaItem): Uri {
        val uri = Uri.parse(item.uri)
        val location = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        return if (location) runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri) else uri
    }

    override suspend fun open(item: MediaItem, storageSaver: Boolean): PreparedFile = withContext(Dispatchers.IO) {
        val uri = sourceUri(item)
        if (storageSaver && !item.isVideo && item.mimeType.lowercase() in COMPRESSIBLE) {
            runCatching { shrink(item, uri) }.getOrNull()?.let { return@withContext it }
        }
        val input = resolver.openInputStream(uri) ?: throw FileNotFoundException("Photo not found")
        PreparedFile(StreamSource(input), item.displayName, item.mimeType.ifEmpty { "application/octet-stream" }, item.sizeBytes, compressed = false)
    }

    private fun shrink(item: MediaItem, uri: Uri): PreparedFile? {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val pixels = w.toLong() * h
            if (pixels > MAX_PIXELS) {
                val scale = sqrt(MAX_PIXELS.toDouble() / pixels)
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            }
        }
        val dir = File(context.cacheDir, "storage-saver").apply { mkdirs() }
        val out = File(dir, UUID.randomUUID().toString() + ".jpg")
        try {
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            val width = bitmap.width
            val height = bitmap.height
            bitmap.recycle()
            copyDetails(uri, out, width, height)
            if (out.length() >= item.sizeBytes) {
                out.delete()
                return null
            }
            val name = item.displayName.substringBeforeLast('.', item.displayName) + ".jpg"
            return PreparedFile(StreamSource(out.inputStream()), name, "image/jpeg", out.length(), compressed = true) { out.delete() }
        } catch (e: Throwable) {
            out.delete()
            throw e
        }
    }

    /** Date, camera and location details from the original onto the smaller copy. */
    private fun copyDetails(from: Uri, to: File, width: Int, height: Int) {
        val source = resolver.openInputStream(from)?.use { ExifInterface(it) } ?: return
        val target = ExifInterface(to.absolutePath)
        EXIF_TAGS.forEach { tag -> source.getAttribute(tag)?.let { target.setAttribute(tag, it) } }
        // The decoder already turned the picture upright.
        target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        target.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
        target.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
        target.saveAttributes()
    }

    private companion object {
        const val MAX_PIXELS = 16_000_000L
        const val QUALITY = 85
        val COMPRESSIBLE = setOf("image/jpeg", "image/jpg", "image/heic", "image/heif")
        val EXIF_TAGS = listOf(
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        )
    }
}

/**
 * Writes restored photos into the gallery under Pictures/Pixallery Restored (videos: Movies/...). Each one is
 * hidden from other apps until it is complete and checked.
 */
class AndroidMediaWriter(context: Context) : MediaWriter {
    private val resolver = context.applicationContext.contentResolver

    override fun begin(record: BackupRecord): RestoreTarget {
        val collection = if (record.isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val folder = (if (record.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/" + RESTORED_FOLDER
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, record.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, record.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.DATE_TAKEN, record.takenAtMillis)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw FileNotFoundException("Couldn't create the photo")
        val out: OutputStream = resolver.openOutputStream(uri) ?: run {
            resolver.delete(uri, null, null)
            throw FileNotFoundException("Couldn't write the photo")
        }
        return object : RestoreTarget {
            override fun write(bytes: ByteArray, length: Int) = out.write(bytes, 0, length)

            override fun commit() {
                out.close()
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }

            override fun abort() {
                runCatching { out.close() }
                runCatching { resolver.delete(uri, null, null) }
            }
        }
    }

    companion object {
        const val RESTORED_FOLDER = "Pixallery Restored"
    }
}
