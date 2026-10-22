package com.deepak.photovault.core.media

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the viewer's details panel shows, read from the file itself (EXIF for photos, container metadata for
 * videos). Every field is optional: phones, apps and screenshots record very different things.
 */
data class MediaDetails(
    /** Folder as people know it, e.g. "/Internal storage/DCIM/Camera". */
    val folder: String? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val lensModel: String? = null,
    /** 35 mm equivalent, e.g. 23 (shown as "23mm" and named wide-angle, telephoto...). */
    val focalLength35mm: Int? = null,
    /** Real focal length in mm, used when the 35 mm equivalent is missing. */
    val focalLengthMm: Double? = null,
    val iso: Int? = null,
    val aperture: Double? = null,
    val exposureSeconds: Double? = null,
    val exposureBias: Double? = null,
    val flashFired: Boolean? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Location exists in the file but Android hid it, because the app lacks "access photo location". */
    val locationHidden: Boolean = false,
    /** Videos. */
    val frameRate: Double? = null,
    val bitrate: Long? = null,
) {
    val hasLocation: Boolean get() = latitude != null && longitude != null

    val camera: String? get() = DetailsFormat.camera(cameraMake, cameraModel)
}

/** Reads [MediaDetails] for an item. Android: ExifInterface and MediaMetadataRetriever. */
interface MediaDetailsSource {
    suspend fun details(item: MediaItem): MediaDetails
}

object NoMediaDetails : MediaDetailsSource {
    override suspend fun details(item: MediaItem) = MediaDetails()
}

/**
 * Turns raw metadata into the short labels Samsung Gallery uses ("1/50 s", "F1.4", "0.0ev", "12MP").
 * Pure functions, unit-tested.
 */
object DetailsFormat {
    fun camera(make: String?, model: String?): String? {
        val m = model?.trim().orEmpty()
        val k = make?.trim().orEmpty()
        return when {
            m.isEmpty() && k.isEmpty() -> null
            m.isEmpty() -> k
            // Phones name themselves by model ("Galaxy S26 Ultra", "Pixel 9", "iPhone 16"), and some cameras
            // already start the model with the make ("Canon EOS R6").
            k.isEmpty() || m.lowercase().startsWith(k.lowercase()) || k.lowercase() in PHONE_MAKES -> m
            else -> "${k.replaceFirstChar { it.uppercase() }} $m"
        }
    }

    private val PHONE_MAKES = setOf("samsung", "google", "apple")

    /** "Ultra wide-angle lens", "Wide-angle lens", "Telephoto lens" from the 35 mm focal length. */
    fun lensKind(focal35: Int?): String? = when {
        focal35 == null || focal35 <= 0 -> null
        focal35 < 20 -> "Ultra wide-angle lens"
        focal35 < 35 -> "Wide-angle lens"
        focal35 < 60 -> "Standard lens"
        else -> "Telephoto lens"
    }

    fun megapixels(width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        val mp = width.toLong() * height / 1_000_000.0
        return if (mp >= 1) "${mp.roundToInt()}MP" else "${(mp * 10).roundToInt() / 10.0}MP"
    }

    fun size(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "${oneDecimal(bytes / 1_000_000_000.0)} GB"
        bytes >= 1_000_000 -> "${twoDecimals(bytes / 1_000_000.0)} MB"
        bytes >= 1_000 -> "${(bytes / 1_000.0).roundToInt()} KB"
        else -> "$bytes B"
    }

    fun aperture(f: Double?): String? = f?.takeIf { it > 0 }?.let { "F${oneDecimal(it)}" }

    /** 1/50 s for short exposures, 0.5 s and 2 s for long ones. */
    fun exposure(seconds: Double?): String? {
        if (seconds == null || seconds <= 0) return null
        return if (seconds < 0.5) "1/${(1 / seconds).roundToInt()} s" else "${oneDecimal(seconds).removeSuffix(".0")} s"
    }

    fun exposureBias(ev: Double?): String? = ev?.let { "${if (it > 0) "+" else ""}${oneDecimal(it)}ev" }

    fun focal(focal35: Int?, focalMm: Double?): String? = when {
        focal35 != null && focal35 > 0 -> "${focal35}mm"
        focalMm != null && focalMm > 0 -> "${oneDecimal(focalMm).removeSuffix(".0")}mm"
        else -> null
    }

    /** -37.7621, 144.8123 -> "37.76210° S, 144.81230° E". */
    fun coordinates(lat: Double, lon: Double): String =
        "${fiveDecimals(abs(lat))}° ${if (lat < 0) "S" else "N"}, ${fiveDecimals(abs(lon))}° ${if (lon < 0) "W" else "E"}"

    fun frameRate(fps: Double?): String? = fps?.takeIf { it > 0 }?.let { "${it.roundToInt()} fps" }

    fun bitrate(bps: Long?): String? = bps?.takeIf { it > 0 }?.let { "${oneDecimal(it / 1_000_000.0)} Mbps" }

    private fun oneDecimal(v: Double) = ((v * 10).roundToInt() / 10.0).toString()

    private fun twoDecimals(v: Double): String {
        val r = (v * 100).roundToInt()
        return "${r / 100}.${(r % 100).toString().padStart(2, '0')}"
    }

    private fun fiveDecimals(v: Double): String {
        val r = (v * 100_000).roundToInt()
        return "${r / 100_000}.${(r % 100_000).toString().padStart(5, '0')}"
    }
}
