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
import com.deepak.photovault.core.media.EditPixels
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

    /** The picture tilted for Straighten (16 Nov), enlarged so no empty corners show. */
    override suspend fun straightened(image: ImageBitmap, degrees: Float): ImageBitmap = withContext(Dispatchers.Default) {
        if (kotlin.math.abs(degrees) < 0.05f) image else Straighten.apply(image.asAndroidBitmap(), degrees).asImageBitmap()
    }

    override val trimSupported: Boolean get() = true

    override suspend fun trim(item: MediaItem, startMs: Long, endMs: Long, mute: Boolean): EditSaved =
        saving.withLock { VideoTrimmer(app).save(item, startMs, endMs, mute) }

    /** The preview with the pixel-by-pixel changes (15 Nov); the colour matrix is added by the screen. */
    override suspend fun pixels(image: ImageBitmap, edit: PhotoEdit): ImageBitmap = withContext(Dispatchers.Default) {
        if (!edit.needsPixels) return@withContext image
        val source = image.asAndroidBitmap()
        val w = source.width
        val h = source.height
        val px = IntArray(w * h)
        source.getPixels(px, 0, w, 0, 0, w, h)
        Bitmap.createBitmap(EditPixels.all(edit, px, w, h, area = edit.crop), w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    override suspend fun save(item: MediaItem, edit: PhotoEdit): EditSaved = saving.withLock {
        withContext(Dispatchers.Default) {
            val from = Uri.parse(item.uri)
            val budget = pixelBudget()
            val photo = decode(from, budget) ?: return@withContext EditSaved(false, "Couldn't open this photo to save the copy.")
            val shrunk = item.width.toLong() * item.height > 0 && photo.width.toLong() * photo.height < item.width.toLong() * item.height * 9 / 10
            val file = File(File(app.cacheDir, "edits").apply { mkdirs() }, "edit-${System.nanoTime()}.jpg")
            try {
                val wasHdr = Hdr.has(photo)
                val made = try {
                    render(photo, edit)
                } catch (e: OutOfMemoryError) {
                    return@withContext EditSaved(false, "This photo is too big to edit on this phone right now.")
                } finally {
                    photo.recycle()
                }
                val width = made.width
                val height = made.height
                // An HDR photo's brightness map goes with the copy (16 Nov): Android writes it into the JPEG itself.
                val hdr = Hdr.has(made)
                file.outputStream().use { made.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
                made.recycle()
                runCatching { copyDetails(from, file, width, height) }
                val hdrKept = hdr && Hdr.inFile(file)
                if (!intoGallery(file, item)) return@withContext EditSaved(false, "Couldn't save the copy. The original is unchanged.")
                EditSaved(
                    true,
                    when {
                        shrunk -> "Saved a copy next to the original (a little smaller, to fit this phone's memory)."
                        hdrKept -> "Saved an HDR copy next to the original."
                        wasHdr -> "Saved a copy next to the original (HDR couldn't be kept on this one)."
                        else -> "Saved a copy next to the original."
                    },
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
        matrix.postTranslate(-seen.left, -seen.top)
        // Straighten (16 Nov): tilted about the middle and enlarged to cover, before the crop.
        if (edit.isTilted) matrix.postConcat(Straighten.matrix(seenW, seenH, edit.straighten))
        matrix.postTranslate(-cut[0].toFloat(), -cut[1].toFloat())
        val space = photo.colorSpace
        val out = if (space != null) Bitmap.createBitmap(cut[2], cut[3], Bitmap.Config.ARGB_8888, false, space)
        else Bitmap.createBitmap(cut[2], cut[3], Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // A see-through picture (a PNG) gets a white page, as a JPEG has no see-through.
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        if (edit.needsPixels) {
            // Pixel by pixel (15 Nov), with the colour matrix last, as the preview does it.
            canvas.drawBitmap(photo, matrix, paint)
            EditBands.apply(out, edit, if (edit.hasColour) EditMath.colourMatrix(edit) else null)
        } else {
            if (edit.hasColour) paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(EditMath.colourMatrix(edit)))
            canvas.drawBitmap(photo, matrix, paint)
        }
        // Never stops the save: a copy without HDR is still a good copy.
        runCatching { Hdr.carry(photo, out, matrix) }
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

/** Straighten (16 Nov): a picture tilted about its middle and enlarged just enough to cover its own frame. */
internal object Straighten {
    fun matrix(w: Int, h: Int, degrees: Float): Matrix = Matrix().apply {
        val s = EditMath.straightenScale(degrees, w, h)
        postRotate(degrees, w / 2f, h / 2f)
        postScale(s, s, w / 2f, h / 2f)
    }

    /** A new picture the same size as [source], tilted by [degrees]. */
    fun apply(source: Bitmap, degrees: Float): Bitmap {
        val space = source.colorSpace
        val out = if (space != null) Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888, true, space)
        else Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(source, matrix(source.width, source.height, degrees), Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        return out
    }
}

/**
 * HDR photos (16 Nov). Since Android 14 an HDR ("Ultra HDR") photo carries a small brightness map that tells HDR
 * screens how much brighter each part may glow. The copy gets the same map, turned, tilted and cropped the same way
 * as the picture, so it still glows. Older Android, or a photo without one: nothing happens.
 */
internal object Hdr {
    fun has(bitmap: Bitmap): Boolean = android.os.Build.VERSION.SDK_INT >= 34 && bitmap.hasGainmap()

    /** Gives [out] the brightness map of [photo]; [geometry] takes [photo]'s pixels to [out]'s. */
    fun carry(photo: Bitmap, out: Bitmap, geometry: Matrix) {
        if (android.os.Build.VERSION.SDK_INT < 34) return
        val gain = photo.gainmap ?: return
        val map = gain.gainmapContents
        // The map covers the whole photo at a smaller size.
        val sx = map.width / photo.width.toFloat()
        val sy = map.height / photo.height.toFloat()
        val w = (out.width * sx).toInt().coerceAtLeast(1)
        val h = (out.height * sy).toInt().coerceAtLeast(1)
        val m = Matrix().apply {
            postScale(1f / sx, 1f / sy)
            postConcat(geometry)
            postScale(w / out.width.toFloat(), h / out.height.toFloat())
        }
        val config = if (map.config == Bitmap.Config.ALPHA_8) Bitmap.Config.ALPHA_8 else Bitmap.Config.ARGB_8888
        val contents = Bitmap.createBitmap(w, h, config)
        Canvas(contents).drawBitmap(map, m, Paint(Paint.FILTER_BITMAP_FLAG))
        val copy = android.graphics.Gainmap(contents)
        gain.ratioMin.let { copy.setRatioMin(it[0], it[1], it[2]) }
        gain.ratioMax.let { copy.setRatioMax(it[0], it[1], it[2]) }
        gain.gamma.let { copy.setGamma(it[0], it[1], it[2]) }
        gain.epsilonSdr.let { copy.setEpsilonSdr(it[0], it[1], it[2]) }
        gain.epsilonHdr.let { copy.setEpsilonHdr(it[0], it[1], it[2]) }
        copy.displayRatioForFullHdr = gain.displayRatioForFullHdr
        copy.minDisplayRatioForHdrTransition = gain.minDisplayRatioForHdrTransition
        out.gainmap = copy
    }

    /** Whether the saved JPEG still has its brightness map (after the date and place were written into it). */
    fun inFile(file: File): Boolean = android.os.Build.VERSION.SDK_INT >= 34 && runCatching {
        val small = android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 16 })
        (small?.hasGainmap() == true).also { small?.recycle() }
    }.getOrDefault(false)
}

/**
 * [EditPixels] on a big picture, a band of rows at a time and in place, so a 50-megapixel photo needs only a little
 * more memory (15 Nov). The rows just above each band are kept as they were, for sharpness.
 */
internal object EditBands {
    private const val BAND = 256

    fun apply(bitmap: Bitmap, edit: PhotoEdit, matrix: FloatArray?, area: com.deepak.photovault.core.media.CropBox = com.deepak.photovault.core.media.CropBox()) {
        val w = bitmap.width
        val h = bitmap.height
        val halo = EditPixels.halo(w, h)
        var before = IntArray(0)
        var y = 0
        while (y < h) {
            val rows = minOf(BAND, h - y)
            val above = minOf(halo, y)
            val below = minOf(halo, h - y - rows)
            val src = IntArray(w * (above + rows + below))
            // Rows above come from the untouched copy kept last time; the rest are not changed yet.
            if (above > 0) System.arraycopy(before, before.size - above * w, src, 0, above * w)
            bitmap.getPixels(src, above * w, w, 0, y, w, rows + below)
            before = src.copyOfRange((above + rows - minOf(halo, rows)) * w, (above + rows) * w)
            val done = EditPixels.rows(edit, src, w, h, rows, above, below, y, area, matrix)
            bitmap.setPixels(done, 0, w, 0, y, w, rows)
            y += rows
        }
    }
}
