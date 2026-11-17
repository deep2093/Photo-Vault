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
 * beside the original with its date, place and camera details. "Save" (17 Nov) writes over the photo instead, after
 * putting the untouched original in Recently deleted.
 */
class AndroidEditor(
    context: Context,
    /** Asks Android for leave to change [Uri] (the "Allow Pixallery to modify this photo?" question); true when given. */
    private val permit: suspend (Uri) -> Boolean = { false },
) : EditorPlatform {
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
            val file = tempFile("jpg")
            try {
                val made = make(item, edit, file, Bitmap.CompressFormat.JPEG)
                if (made.problem != null) return@withContext EditSaved(false, made.problem)
                val saved = intoGallery(file, item) ?: return@withContext EditSaved(false, "Couldn't save the copy. The original is unchanged.")
                EditSaved(
                    true,
                    // Short (17 Nov): Android shows only two lines of a message, and the longer ones were cut off.
                    when {
                        made.shrunk -> "Saved a smaller copy (${(made.width.toLong() * made.height + 500_000) / 1_000_000} MP) beside the original."
                        made.hdrKept -> "Saved an HDR copy beside the original."
                        made.wasHdr -> "Saved a copy beside the original, without HDR."
                        else -> "Saved a copy beside the original."
                    },
                    newId = runCatching { android.content.ContentUris.parseId(saved) }.getOrNull(),
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

    /** Photos on this phone in a kind that can be written again as it is: JPEG, PNG and WebP (17 Nov). */
    override fun canReplace(item: MediaItem): Boolean =
        !item.isVideo && item.uri.startsWith("content://media/") && formatOf(item.mimeType) != null

    /**
     * Saves the edit over the photo itself (17 Nov, Deepak). Android asks the person first. Before anything is
     * written, the untouched original is put in Recently deleted, where it stays for 30 days, so it can always be
     * brought back. A photo that would have to be made smaller to fit the phone's memory is not saved over.
     */
    override suspend fun replace(item: MediaItem, edit: PhotoEdit): EditSaved = saving.withLock {
        val target = Uri.parse(item.uri)
        val format = formatOf(item.mimeType) ?: return@withLock EditSaved(false, "This kind of photo can't be saved over. Save a copy instead.")
        if (!permit(target)) return@withLock EditSaved(false, "Not saved. The original is unchanged.")
        withContext(Dispatchers.Default) {
            val file = tempFile(item.displayName.substringAfterLast('.', "jpg").take(5))
            try {
                val made = make(item, edit, file, format)
                if (made.problem != null) return@withContext EditSaved(false, made.problem)
                if (made.shrunk) return@withContext EditSaved(false, "This photo is too big to save over on this phone. Save a copy instead.")
                // 1. The original, untouched, into Recently deleted (or beside the photo, if Android won't take it there).
                val kept = keepOriginal(item, target)
                    ?: return@withContext EditSaved(false, "Couldn't keep the original, so nothing was changed. Save a copy instead.")
                // 2. The edit written over the photo. Android notices the change and makes its preview again.
                val written = runCatching {
                    resolver.openOutputStream(target, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("no stream")
                }.isSuccess
                val where = if (kept.trashed) "The original is in Recently deleted for 30 days." else "The original is kept beside it."
                if (!written) return@withContext EditSaved(false, "Couldn't save over the photo. $where")
                EditSaved(
                    true,
                    when {
                        made.hdrKept -> "Saved in HDR. $where"
                        made.wasHdr -> "Saved, without HDR. $where"
                        else -> "Saved. $where"
                    },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                EditSaved(false, "Couldn't save over the photo.")
            } finally {
                file.delete()
            }
        }
    }

    /** A plain copy of the original photo, straight into Recently deleted; null when it couldn't be made. */
    private fun keepOriginal(item: MediaItem, from: Uri): com.deepak.photovault.GallerySave.Saved? {
        val folders = listOfNotNull(item.folder?.trim('/')?.takeIf { it.isNotEmpty() }, "Pictures/Pixallery").distinct()
        return com.deepak.photovault.GallerySave.saveUri(
            resolver, com.deepak.photovault.GallerySave.photos, item.displayName, item.mimeType, folders, item.takenAtMillis, trashed = true,
        ) { out ->
            // The untouched file, place included when Android allows it.
            val input = runCatching { resolver.openInputStream(MediaStore.setRequireOriginal(from)) }.getOrNull() ?: resolver.openInputStream(from)
            (input ?: error("can't read the original")).use { it.copyTo(out) }
        }
    }

    /** What [make] produced, or why it couldn't. */
    private class Made(
        val width: Int = 0,
        val height: Int = 0,
        val shrunk: Boolean = false,
        val wasHdr: Boolean = false,
        val hdrKept: Boolean = false,
        val problem: String? = null,
    )

    /** The full photo with [edit], written to [file] as [format] with the original's date, place and camera details. */
    private fun make(item: MediaItem, edit: PhotoEdit, file: File, format: Bitmap.CompressFormat): Made {
        val from = Uri.parse(item.uri)
        val photo = decode(from, pixelBudget()) ?: return Made(problem = "Couldn't open this photo to save it.")
        val shrunk = item.width.toLong() * item.height > 0 && photo.width.toLong() * photo.height < item.width.toLong() * item.height * 9 / 10
        val wasHdr = Hdr.has(photo)
        val made = try {
            render(photo, edit)
        } catch (e: OutOfMemoryError) {
            return Made(problem = "This photo is too big to edit on this phone right now.")
        } finally {
            photo.recycle()
        }
        val width = made.width
        val height = made.height
        // An HDR photo's brightness map goes with it (16 Nov): Android writes it into the JPEG itself.
        val hdr = Hdr.has(made)
        file.outputStream().use { made.compress(format, QUALITY, it) }
        made.recycle()
        runCatching { copyDetails(from, file, width, height) }
        return Made(width, height, shrunk, wasHdr, hdrKept = hdr && Hdr.inFile(file))
    }

    private fun tempFile(extension: String) = File(File(app.cacheDir, "edits").apply { mkdirs() }, "edit-${System.nanoTime()}.$extension")

    private fun formatOf(mime: String): Bitmap.CompressFormat? = when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> Bitmap.CompressFormat.JPEG
        "image/png" -> Bitmap.CompressFormat.PNG
        "image/webp" -> Bitmap.CompressFormat.WEBP_LOSSY
        else -> null
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
    private fun intoGallery(file: File, item: MediaItem): Uri? {
        val folders = listOfNotNull(item.folder?.trim('/')?.takeIf { it.isNotEmpty() }, "Pictures/Pixallery").distinct()
        return com.deepak.photovault.GallerySave.saveUri(
            resolver, com.deepak.photovault.GallerySave.photos, EditMath.copyName(item.displayName), "image/jpeg", folders, item.takenAtMillis,
        ) { out -> file.inputStream().use { it.copyTo(out) } }?.uri
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
     * pixel together), between 4 and 50 megapixels.
     *
     * 17 Nov: measured against the phone's free memory, not the app's own small allowance. Since Android 8 a
     * picture's pixels live outside that allowance, so the old sum made even 12 MP photos save a little smaller
     * ("a little smaller, to fit this phone's memory", Deepak). At most a quarter of what the phone has free.
     */
    private fun pixelBudget(): Long {
        val manager = app.getSystemService(android.app.ActivityManager::class.java)
        val info = android.app.ActivityManager.MemoryInfo().also { manager?.getMemoryInfo(it) }
        val usable = ((info.availMem - info.threshold) / 4).coerceAtLeast(0L)
        return (usable / 8).coerceIn(MIN_SAVE_PIXELS, MAX_SAVE_PIXELS)
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
