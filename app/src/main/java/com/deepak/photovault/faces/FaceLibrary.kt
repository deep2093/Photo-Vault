package com.deepak.photovault.faces

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.deepak.photovault.core.media.MediaStoreLibrary
import com.deepak.photovault.ui.shell.Face
import com.deepak.photovault.ui.shell.FaceGrouping
import com.deepak.photovault.ui.shell.FaceSpot
import com.deepak.photovault.ui.shell.PeopleSnapshot
import com.deepak.photovault.ui.shell.Person
import com.deepak.photovault.ui.shell.ScanProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File

/**
 * People's data on this phone only (7A, 5 Nov): the faces found (measurements and where they are, never pictures),
 * which photos were looked at, and the people with their names. Nothing here is uploaded. Turning People off deletes it.
 */
object FaceLibrary {
    private val lock = Mutex()
    private val scanLock = Mutex()
    val progress = MutableStateFlow<ScanProgress?>(null)

    @Volatile private var stopAsked = false

    private fun dir(context: Context) = File(context.applicationContext.filesDir, "people").apply { mkdirs() }
    private fun facesFile(context: Context) = File(dir(context), "faces.bin")
    private fun scannedFile(context: Context) = File(dir(context), "scanned.bin")
    private fun peopleFile(context: Context) = File(dir(context), "people.json")
    fun thumbsDir(context: Context) = File(dir(context), "thumbs").apply { mkdirs() }

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("on", false)

    fun setEnabled(context: Context, on: Boolean) { prefs(context).edit().putBoolean("on", on).apply() }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("pixallery_people", Context.MODE_PRIVATE)

    /** Turning People off: every face, name and face picture on this phone goes. */
    suspend fun forgetAll(context: Context) = lock.withLock {
        withContext(Dispatchers.IO) {
            stopAsked = true
            File(context.applicationContext.filesDir, "people").listFiles()?.filter { it.name != "models" }?.forEach { it.deleteRecursively() }
            progress.value = null
        }
    }

    fun stop() { stopAsked = true }

    // ---- Files ----

    private fun readFaces(context: Context): List<Face> {
        val file = facesFile(context)
        if (!file.exists()) return emptyList()
        val out = mutableListOf<Face>()
        DataInputStream(file.inputStream().buffered()).use { input ->
            try {
                while (true) {
                    val id = input.readInt()
                    val photo = input.readLong()
                    val box = FloatArray(4) { input.readFloat() }
                    val n = input.readShort().toInt()
                    val v = FloatArray(n) { input.readFloat() }
                    out += Face(id, photo, box, v)
                }
            } catch (_: EOFException) {
                // The end (or a half-written last face after a crash, which is dropped).
            }
        }
        return out
    }

    private fun appendFaces(context: Context, faces: List<Face>) {
        if (faces.isEmpty()) return
        DataOutputStream(java.io.FileOutputStream(facesFile(context), true).buffered()).use { out ->
            for (f in faces) {
                out.writeInt(f.id)
                out.writeLong(f.photoId)
                f.box.forEach { out.writeFloat(it) }
                out.writeShort(f.vector.size)
                f.vector.forEach { out.writeFloat(it) }
            }
        }
    }

    private fun readScanned(context: Context): MutableSet<Long> {
        val file = scannedFile(context)
        val out = HashSet<Long>()
        if (!file.exists()) return out
        DataInputStream(file.inputStream().buffered()).use { input ->
            try {
                while (true) out += input.readLong()
            } catch (_: EOFException) {
            }
        }
        return out
    }

    private fun appendScanned(context: Context, ids: List<Long>) {
        if (ids.isEmpty()) return
        DataOutputStream(java.io.FileOutputStream(scannedFile(context), true).buffered()).use { out -> ids.forEach { out.writeLong(it) } }
    }

    private fun readPeople(context: Context): Pair<List<Person>, Int> {
        val file = peopleFile(context)
        if (!file.exists()) return emptyList<Person>() to 1
        return runCatching {
            val root = JSONObject(file.readText())
            val arr = root.getJSONArray("people")
            val list = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                fun ints(key: String): Set<Int> = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }.orEmpty()
                Person(
                    id = o.getInt("id"),
                    faces = ints("faces"),
                    name = if (o.has("name") && !o.isNull("name")) o.getString("name") else null,
                    hidden = o.optBoolean("hidden", false),
                    notFaces = ints("not"),
                )
            }
            list to root.optInt("next", (list.maxOfOrNull { it.id } ?: 0) + 1)
        }.getOrDefault(emptyList<Person>() to 1)
    }

    private fun writePeople(context: Context, people: List<Person>, next: Int) {
        val arr = JSONArray()
        people.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("faces", JSONArray(p.faces.toList()))
                if (p.name != null) put("name", p.name)
                if (p.hidden) put("hidden", true)
                if (p.notFaces.isNotEmpty()) put("not", JSONArray(p.notFaces.toList()))
            })
        }
        val tmp = File(dir(context), "people.json.tmp")
        tmp.writeText(JSONObject().put("people", arr).put("next", next).toString())
        if (!tmp.renameTo(peopleFile(context))) throw java.io.IOException("People couldn't be saved")
    }

    // ---- Reading and changing ----

    suspend fun load(context: Context): PeopleSnapshot = lock.withLock {
        withContext(Dispatchers.Default) {
            val faces = readFaces(context)
            // Once, for people grouped before groups were compared with each other (5 Nov).
            if (prefs(context).getInt("joined", 0) < JOIN_VERSION && peopleFile(context).exists()) {
                val (people, next) = readPeople(context)
                runCatching { writePeople(context, FaceGrouping.joinGroups(faces, people), next) }
                prefs(context).edit().putInt("joined", JOIN_VERSION).apply()
            }
            PeopleSnapshot(
                people = readPeople(context).first,
                spots = faces.associate { it.id to FaceSpot(it.id, it.photoId, it.box) },
                scanned = readScanned(context).size,
            )
        }
    }

    suspend fun edit(context: Context, change: (List<Person>) -> List<Person>) = lock.withLock {
        withContext(Dispatchers.IO) {
            val (people, next) = readPeople(context)
            writePeople(context, change(people), next)
        }
    }

    // ---- Scanning ----

    /**
     * Looks at phone photos not looked at yet, newest first, up to [limit]. Returns true when photos are left for next
     * time. Only one scan runs at a time; [stop] ends it after the current photo.
     */
    suspend fun scan(context: Context, limit: Int = Int.MAX_VALUE): Boolean = withContext(Dispatchers.Default) {
        if (!enabled(context)) return@withContext false
        if (!scanLock.tryLock()) return@withContext true
        stopAsked = false
        try {
            val engine = FaceEngine.create(context) ?: return@withContext false
            val photos = MediaStoreLibrary(context).loadAll().filter { !it.isVideo }.sortedByDescending { it.takenAtMillis }
            val done = lock.withLock { withContext(Dispatchers.IO) { readScanned(context) } }
            val todo = photos.filter { it.id !in done }
            val batch = todo.take(limit)
            var nextFace = lock.withLock { withContext(Dispatchers.IO) { (readFaces(context).maxOfOrNull { it.id } ?: 0) + 1 } }
            val newFaces = mutableListOf<Face>()
            val newScanned = mutableListOf<Long>()
            var count = photos.count { it.id in done }
            progress.value = ScanProgress(count, photos.size, running = true)
            suspend fun flush(join: Boolean) {
                lock.withLock {
                    withContext(Dispatchers.IO) {
                        appendFaces(context, newFaces)
                        appendScanned(context, newScanned)
                        val all = readFaces(context)
                        val (people, next) = readPeople(context)
                        val (grouped, after) = FaceGrouping.group(all, people, next)
                        // Comparing whole groups is slower: done at the end of each turn, not every 40 photos.
                        writePeople(context, if (join) FaceGrouping.joinGroups(all, grouped) else grouped, after)
                    }
                }
                newFaces.clear()
                newScanned.clear()
            }
            for (item in batch) {
                if (stopAsked || !enabled(context)) break
                val bitmap = decode(context, item.uri, MAX_SIDE)
                if (bitmap != null) {
                    try {
                        engine.find(bitmap).forEach { f -> newFaces += Face(nextFace++, item.id, f.box, f.vector) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // A photo the engine can't read is counted as looked at, so it isn't tried forever.
                    } finally {
                        bitmap.recycle()
                    }
                }
                newScanned += item.id
                count++
                progress.value = ScanProgress(count, photos.size, running = true)
                if (newScanned.size >= FLUSH_EVERY) flush(join = false)
            }
            if (enabled(context)) flush(join = true)
            progress.value = ScanProgress(count, photos.size, running = false)
            todo.size > batch.size || (stopAsked && count < photos.size)
        } finally {
            scanLock.unlock()
        }
    }

    /** A small square of one face for its round picture, made once and kept. */
    suspend fun faceThumb(context: Context, spot: FaceSpot): ByteArray? = withContext(Dispatchers.IO) {
        val file = File(thumbsDir(context), "${spot.id}.jpg")
        if (file.exists()) return@withContext file.readBytes()
        val uri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, spot.photoId)
        val bitmap = decode(context, uri.toString(), 1024) ?: return@withContext null
        try {
            val w = bitmap.width
            val h = bitmap.height
            val cx = (spot.box[0] + spot.box[2] / 2) * w
            val cy = (spot.box[1] + spot.box[3] / 2) * h
            val side = (maxOf(spot.box[2] * w, spot.box[3] * h) * 1.6f).coerceAtMost(minOf(w, h).toFloat())
            val left = (cx - side / 2).coerceIn(0f, w - side).toInt()
            val top = (cy - side / 2).coerceIn(0f, h - side).toInt()
            val crop = Bitmap.createBitmap(bitmap, left, top, side.toInt().coerceAtLeast(1), side.toInt().coerceAtLeast(1))
            val small = Bitmap.createScaledBitmap(crop, 256, 256, true)
            val bytes = java.io.ByteArrayOutputStream().use { out -> small.compress(Bitmap.CompressFormat.JPEG, 85, out); out.toByteArray() }
            file.writeBytes(bytes)
            bytes
        } catch (_: Throwable) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(context: Context, uri: String, maxSide: Int): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, Uri.parse(uri))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val long = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
            if (long > maxSide) {
                val scale = maxSide.toFloat() / long
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            }
        }.let { if (it.config != Bitmap.Config.ARGB_8888) it.copy(Bitmap.Config.ARGB_8888, false) else it }
    }.getOrNull()

    private const val JOIN_VERSION = 1
    private const val MAX_SIDE = 1280
    private const val FLUSH_EVERY = 40
}
