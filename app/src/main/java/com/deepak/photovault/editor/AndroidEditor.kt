package com.deepak.photovault.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import com.deepak.photovault.core.media.EditMath
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.PhotoEdit
import com.deepak.photovault.ui.shell.EditSaved
import com.deepak.photovault.ui.shell.EditorPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The photo editor on Android (Phase 11, 15 Nov). The page edits a small copy of the photo so sliders stay smooth;
 * saving opens the full photo again, applies the same turn, mirror, crop and colours once, and writes a new JPEG
 * beside the original with its date, place and camera details. The original file is only ever read.
 */
class AndroidEditor(context: Context) : EditorPlatform {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    // One save at a time: each holds the full photo in memory.
    private val saving = Mutex()

    override val supported: Boolean get() = true

    override suspend fun open(item: MediaItem): ImageBitmap? = withContext(Dispatchers.Default) {
        decode(Uri.parse(item.uri), PREVIEW_PIXELS)?.asImageBitmap()
    }

    override suspend fun turned(base: ImageBitmap, turns: Int, flipped: Boolean): ImageBitmap = withContext(Dispatchers.Default) {
        val quarter = ((turns % 4) + 4) % 4
        if (quarter == 0 && !flipped) return@withContext base
        val source = base.asAndroidBitmap()
        Bitmap.createBitmap(source, 0, 0, source.width, source.height, shape(quarter, flipped), true).asImageBitmap()
    }

    override suspend fun save(item: MediaItem, edit: PhotoEdit): EditSaved = saving.withLock {
        withContext(Dispatchers.Default) {
            val from = Uri.parse(item.uri)
            val budget = pixelBudget()
            val photo = decode(from, budget) ?: return@withContext EditSaved(false, "Couldn't open this photo to save the copy.")
            val shrunk = item.width.toLong() * item.height > 0 && photo.width.toLong() * photo.height < item.width.toLong() * item.height * 9 / 10
            val file = File(File(app.cacheDir, "edits").apply { mkdirs() }, "edit-${System.nanoTime()}.jpg")
            try {
                val made = try {
                    render(photo, edit)
                } catch (e: OutOfMemoryError) {
                    return@withContext EditSaved(false, "This photo is too big to edit on this phone right now.")
                } finally {
                    photo.recycle()
                }
                val width = made.width
                val height = made.height
                file.outputStream().use { made.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
                made.recycle()
                runCatching { copyDetails(from, file, width, height) }
                if (!intoGallery(file, item)) return@withContext EditSaved(false, "Couldn't save the copy. The original is unchanged.")
                EditSaved(
                    true,
                    if (shrunk) "Saved a copy next to the original (a little smaller, to fit this phone's memory)."
                    else "Saved a copy next to the original.",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                EditSaved(false, "Couldn't save the copy. The original is unchanged.")
            } finally {
                file.delete()
            }
        }
    }

    /** [turns] quarter turns clockwise, then a mirror left to right: the same order as [PhotoEdit]. */
    private fun shape(turns: Int, flipped: Boolean): Matrix = Matrix().apply {
        postRotate(90f * turns)
        if (flipped) postScale(-1f, 1f)
    }

    /** The finished picture: turned, mirrored, cut to the frame and coloured, drawn once. */
    private fun render(photo: Bitmap, edit: PhotoEdit): Bitmap {
        val turns = ((edit.turns % 4) + 4) % 4
        val matrix = shape(turns, edit.flipped)
        val seen = RectF(0f, 0f, photo.width.toFloat(), photo.height.toFloat())
        matrix.mapRect(seen)
        val seenW = if (turns % 2 != 0) photo.height else photo.width
        val seenH = if (turns % 2 != 0) photo.width else photo.height
        val cut = EditMath.pixels(edit.crop, seenW, seenH)
        matrix.postTranslate(-seen.left - cut[0], -seen.top - cut[1])
        val space = photo.colorSpace
        val out = if (space != null) Bitmap.createBitmap(cut[2], cut[3], Bitmap.Config.ARGB_8888, false, space)
        else Bitmap.createBitmap(cut[2], cut[3], Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // A see-through picture (a PNG) gets a white page, as a JPEG has no see-through.
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        if (edit.hasColour) paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(EditMath.colourMatrix(edit)))
        canvas.drawBitmap(photo, matrix, paint)
        return out
    }

    /** Into the original's own folder (so it sits beside it in its album), else Pictures > Pixallery. */
    private fun intoGallery(file: File, item: MediaItem): Boolean {
        val folders = listOfNotNull(item.folder?.trim('/')?.takeIf { it.isNotEmpty() }, "Pictures/Pixallery").distinct()
        for (folder in folders) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, EditMath.copyName(item.displayName))
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, folder)
                put(MediaStore.Images.Media.DATE_TAKEN, item.takenAtMillis)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            // Android refuses some folders (Download, another app's): the next one is tried.
            val uri = runCatching {
                resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            }.getOrNull() ?: continue
            val written = runCatching {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("no stream")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }.isSuccess
            if (written) return true
            runCatching { resolver.delete(uri, null, null) }
        }
        return false
    }

    /** Date, camera and place from the original onto the copy. The copy is already the right way up. */
    private fun copyDetails(from: Uri, to: File, width: Int, height: Int) {
        // The place is only handed over when asked for the untouched file (and the person allowed photo locations).
        val source = runCatching { resolver.openInputStream(MediaStore.setRequireOriginal(from))?.use { ExifInterface(it) } }.getOrNull()
            ?: resolver.openInputStream(from)?.use { ExifInterface(it) }
            ?: return
        val target = ExifInterface(to.absolutePath)
        EXIF_TAGS.forEach { tag -> source.getAttribute(tag)?.let { target.setAttribute(tag, it) } }
        target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        target.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
        target.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
        target.setAttribute(ExifInterface.TAG_SOFTWARE, "Pixallery")
        target.saveAttributes()
    }

    /** The photo the right way up, with at most [maxPixels] pixels. Null when it can't be opened. */
    private fun decode(uri: Uri, maxPixels: Long): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val pixels = info.size.width.toLong() * info.size.height
            if (pixels > maxPixels) {
                val f = kotlin.math.sqrt(maxPixels.toDouble() / pixels)
                decoder.setTargetSize((info.size.width * f).toInt().coerceAtLeast(1), (info.size.height * f).toInt().coerceAtLeast(1))
            }
        }.let { decoded ->
            if (decoded.config == Bitmap.Config.ARGB_8888) decoded
            else {
                val plain: Bitmap? = decoded.copy(Bitmap.Config.ARGB_8888, false)
                plain ?: decoded
            }
        }
    } catch (e: OutOfMemoryError) {
        null
    } catch (e: Exception) {
        null
    }

    /**
     * How big a photo can be saved at: the photo and the finished copy are both in memory for a moment (8 bytes a
     * pixel together), so about half of what the app has free, between 4 and 50 megapixels.
     */
    private fun pixelBudget(): Long {
        val runtime = Runtime.getRuntime()
        val free = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        return (free / 2 / 8).coerceIn(MIN_SAVE_PIXELS, MAX_SAVE_PIXELS)
    }

    private companion object {
        /** The editing preview: about 2.4 megapixels, sharp on a phone or tablet and quick to redraw. */
        const val PREVIEW_PIXELS = 2_400_000L
        const val MIN_SAVE_PIXELS = 4_000_000L
        const val MAX_SAVE_PIXELS = 50_000_000L
        const val QUALITY = 95
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
