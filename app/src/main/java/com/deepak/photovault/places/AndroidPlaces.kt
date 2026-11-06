package com.deepak.photovault.places

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.deepak.photovault.MainActivity
import com.deepak.photovault.core.media.MediaStoreLibrary
import com.deepak.photovault.ui.shell.PhotoPlace
import com.deepak.photovault.ui.shell.Place
import com.deepak.photovault.ui.shell.PlacesPlatform
import com.deepak.photovault.ui.shell.PlacesProgress
import com.deepak.photovault.ui.shell.PlacesSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File

/** Places on Android (7B-1, 7 Nov): locations read from each photo (with Android's permission), named offline. */
class AndroidPlaces(private val activity: MainActivity) : PlacesPlatform {
    private val app = activity.applicationContext

    override val supported: Boolean get() = true

    override fun canReadLocations(): Boolean =
        app.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun askForLocations() = activity.requestLocationAccess()

    override suspend fun load(): PlacesSnapshot = PlacesLibrary.snapshot(app)

    override fun readNow() {
        if (!canReadLocations()) return
        activity.lifecycleScope.launch { PlacesLibrary.read(app) }
    }

    override val progress: StateFlow<PlacesProgress?> get() = PlacesLibrary.progress

    override suspend fun address(latitude: Double, longitude: Double): String? = PlacesLibrary.address(app, latitude, longitude)
}

/** The town or city for a spot, from GeoNames' list of places with 1,000 people or more (in the app; 7 Nov: was 15,000). */
internal object CityFinder {
    private class City(val name: String, val region: String, val country: String, val lat: Double, val lon: Double, val people: Long)

    @Volatile private var grid: Map<Long, List<City>>? = null

    private fun cell(lat: Double, lon: Double): Long = (Math.floor(lat).toLong() + 90) * 1000 + (Math.floor(lon).toLong() + 180)

    private fun load(context: Context): Map<Long, List<City>> {
        grid?.let { return it }
        synchronized(this) {
            grid?.let { return it }
            val map = HashMap<Long, MutableList<City>>()
            runCatching {
                context.assets.open("places/cities.tsv").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val p = line.split('\t')
                        if (p.size < 6) return@forEach
                        val lat = p[3].toDoubleOrNull() ?: return@forEach
                        val lon = p[4].toDoubleOrNull() ?: return@forEach
                        map.getOrPut(cell(lat, lon)) { mutableListOf() } += City(p[0], p[1], p[2], lat, lon, p[5].toLongOrNull() ?: 0L)
                    }
                }
            }
            grid = map
            return map
        }
    }

    private fun km(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val h = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(aLat)) * Math.cos(Math.toRadians(bLat)) * Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(h.coerceIn(0.0, 1.0)))
    }

    /**
     * The place a photo belongs to (7 Nov, finer): inside a big city's area (300,000 people or more within 25 km) the
     * city, so suburbs count as "Melbourne"; elsewhere the nearest town within 30 km, so a country photo gets its own
     * town instead of a far bigger one; else the nearest within 150 km; else none (out at sea).
     */
    fun find(context: Context, lat: Double, lon: Double): Place? {
        val g = load(context)
        val near = ArrayList<Pair<City, Double>>()
        for (dy in -2..2) for (dx in -2..2) {
            g[cell(lat + dy, lon + dx)]?.forEach { c -> near += c to km(lat, lon, c.lat, c.lon) }
        }
        val metro = near.filter { it.second <= 25.0 && it.first.people >= 300_000 }.maxByOrNull { it.first.people }
        val town = near.filter { it.second <= 30.0 }.minByOrNull { it.second }
        val pick = metro ?: town ?: near.filter { it.second <= 150.0 }.minByOrNull { it.second }
        return pick?.first?.let { Place(it.name, it.region, it.country) }
    }
}

/** Each photo's location, read once and kept in files/places (7B-1). */
internal object PlacesLibrary {
    private val lock = Mutex()
    val progress = MutableStateFlow<PlacesProgress?>(null)

    private fun file(context: Context) = File(context.applicationContext.filesDir, "places/locations.bin").apply { parentFile?.mkdirs() }

    /** Photo id to (latitude, longitude); NaN when the photo has no location. */
    private fun readAll(context: Context): MutableMap<Long, Pair<Double, Double>> {
        val out = HashMap<Long, Pair<Double, Double>>()
        val f = file(context)
        if (!f.exists()) return out
        runCatching {
            DataInputStream(f.inputStream().buffered()).use { input ->
                while (true) {
                    val id = try { input.readLong() } catch (_: EOFException) { break }
                    out[id] = input.readDouble() to input.readDouble()
                }
            }
        }
        return out
    }

    private fun append(context: Context, rows: Map<Long, Pair<Double, Double>>) {
        if (rows.isEmpty()) return
        DataOutputStream(java.io.FileOutputStream(file(context), true).buffered()).use { out ->
            rows.forEach { (id, at) -> out.writeLong(id); out.writeDouble(at.first); out.writeDouble(at.second) }
        }
    }

    private fun rewrite(context: Context, rows: Map<Long, Pair<Double, Double>>) {
        val tmp = File(file(context).parentFile, "locations.bin.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            rows.forEach { (id, at) -> out.writeLong(id); out.writeDouble(at.first); out.writeDouble(at.second) }
        }
        tmp.renameTo(file(context))
    }

    suspend fun snapshot(context: Context): PlacesSnapshot = lock.withLock {
        withContext(Dispatchers.IO) {
            val rows = readAll(context)
            val photos = HashMap<Long, PhotoPlace>()
            // Photos a few hundred metres apart share one look-up.
            val named = HashMap<Long, Place?>()
            rows.forEach { (id, at) ->
                if (at.first.isNaN()) return@forEach
                val key = Math.round(at.first * 200) * 100_000L + Math.round(at.second * 200)
                val place = named.getOrPut(key) { CityFinder.find(context, at.first, at.second) }
                photos[id] = PhotoPlace(at.first, at.second, place)
            }
            PlacesSnapshot(photos, reading = progress.value != null)
        }
    }

    @Volatile private var running = false

    /** Reads locations of photos and videos not read yet. Photos gone from the phone are forgotten. */
    suspend fun read(context: Context) {
        if (running) return
        running = true
        try {
            withContext(Dispatchers.IO) {
                val items = MediaStoreLibrary(context).loadAll()
                if (items.isEmpty()) return@withContext
                val known = lock.withLock { readAll(context) }
                val ids = items.map { it.id }.toHashSet()
                if (known.keys.any { it !in ids }) lock.withLock { rewrite(context, known.filterKeys { it in ids }) }
                val todo = items.filter { it.id !in known }
                if (todo.isEmpty()) return@withContext
                val batch = HashMap<Long, Pair<Double, Double>>()
                todo.forEachIndexed { i, item ->
                    batch[item.id] = locate(context, Uri.parse(item.uri), item.isVideo) ?: (Double.NaN to Double.NaN)
                    if (batch.size >= 100) {
                        lock.withLock { append(context, batch) }
                        batch.clear()
                    }
                    if (i % 20 == 0) progress.value = PlacesProgress(i, todo.size)
                }
                lock.withLock { append(context, batch) }
            }
        } finally {
            progress.value = null
            running = false
        }
    }

    private fun locate(context: Context, uri: Uri, video: Boolean): Pair<Double, Double>? = runCatching {
        val original = runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
        if (video) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, original)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let(::iso6709)
            } finally {
                runCatching { r.release() }
            }
        } else {
            context.contentResolver.openInputStream(original)?.use { ExifInterface(it).latLong }?.let { it[0] to it[1] }
        }
    }.getOrNull()?.takeIf { (lat, lon) -> !(lat == 0.0 && lon == 0.0) && lat in -90.0..90.0 && lon in -180.0..180.0 }

    /** "+37.8136+144.9631/" to a pair. */
    private fun iso6709(s: String): Pair<Double, Double>? {
        val m = Regex("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)").find(s) ?: return null
        return (m.groupValues[1].toDoubleOrNull() ?: return null) to (m.groupValues[2].toDoubleOrNull() ?: return null)
    }

    /** The phone's own address lookup (Android's Geocoder), only when asked; remembered. */
    suspend fun address(context: Context, lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        val key = "%.5f,%.5f".format(java.util.Locale.ROOT, lat, lon)
        val prefs = context.getSharedPreferences("places_addresses", Context.MODE_PRIVATE)
        prefs.getString(key, null)?.let { return@withContext it }
        if (!android.location.Geocoder.isPresent()) return@withContext null
        @Suppress("DEPRECATION")
        val found = runCatching { android.location.Geocoder(context).getFromLocation(lat, lon, 1) }.getOrNull()?.firstOrNull()
            ?: return@withContext null
        val line = (0..found.maxAddressLineIndex).mapNotNull { found.getAddressLine(it) }.joinToString(", ").ifBlank { null }
            ?: return@withContext null
        prefs.edit().putString(key, line).apply()
        line
    }
}
