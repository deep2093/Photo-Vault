package com.deepak.photovault.core.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads photo EXIF and video metadata on the phone. Nothing leaves the device.
 *
 * Location: Android removes GPS from what apps read unless they hold ACCESS_MEDIA_LOCATION and ask for the
 * original file. Without the permission the panel offers to allow it ([MediaDetails.locationHidden]).
 */
class AndroidMediaDetails(context: Context) : MediaDetailsSource {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    private fun canReadLocation() =
        app.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    override suspend fun details(item: MediaItem): MediaDetails = withContext(Dispatchers.IO) {
        val uri = Uri.parse(item.uri)
        val folder = folderOf(uri)
        runCatching { if (item.isVideo) video(uri) else photo(uri) }
            .getOrDefault(MediaDetails())
            .copy(folder = folder)
    }

    private fun photo(uri: Uri): MediaDetails {
        val original = if (canReadLocation()) runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri) else uri
        val exif = (runCatching { resolver.openInputStream(original) }.getOrNull() ?: resolver.openInputStream(uri))
            ?.use { ExifInterface(it) } ?: return MediaDetails()
        val latLong = exif.latLong
        val focal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, -1.0).takeIf { it > 0 }
        return MediaDetails(
            cameraMake = exif.getAttribute(ExifInterface.TAG_MAKE),
            cameraModel = exif.getAttribute(ExifInterface.TAG_MODEL),
            lensModel = exif.getAttribute(ExifInterface.TAG_LENS_MODEL),
            focalLength35mm = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0).takeIf { it > 0 },
            focalLengthMm = focal,
            iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 },
            aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, -1.0).takeIf { it > 0 },
            exposureSeconds = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, -1.0).takeIf { it > 0 },
            exposureBias = exif.getAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE)?.let { exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, 0.0) },
            flashFired = exif.getAttribute(ExifInterface.TAG_FLASH)?.let { (exif.getAttributeInt(ExifInterface.TAG_FLASH, 0) and 1) == 1 },
            latitude = latLong?.getOrNull(0),
            longitude = latLong?.getOrNull(1),
            locationHidden = latLong == null && !canReadLocation(),
        )
    }

    private fun video(uri: Uri): MediaDetails {
        val retriever = MediaMetadataRetriever()
        return try {
            val original = if (canReadLocation()) runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri) else uri
            runCatching { retriever.setDataSource(app, original) }.recoverCatching { retriever.setDataSource(app, uri) }.getOrThrow()
            val location = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let(::parseIso6709)
            MediaDetails(
                cameraMake = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR),
                frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull(),
                bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull(),
                latitude = location?.first,
                longitude = location?.second,
                locationHidden = location == null && !canReadLocation(),
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** MediaStore's RELATIVE_PATH ("DCIM/Camera/") shown the way Samsung does ("/Internal storage/DCIM/Camera"). */
    private fun folderOf(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.VOLUME_NAME), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val path = c.getString(0)?.trimEnd('/') ?: return@use null
            val volume = c.getString(1)
            val root = if (volume == null || volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) "Internal storage" else "SD card"
            "/$root/$path"
        }
    }.getOrNull()

    /** "+37.7621+144.8123/" -> (37.7621, 144.8123). */
    private fun parseIso6709(value: String): Pair<Double, Double>? {
        val match = Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(value) ?: return null
        val lat = match.groupValues[1].toDoubleOrNull() ?: return null
        val lon = match.groupValues[2].toDoubleOrNull() ?: return null
        return lat to lon
    }
}
