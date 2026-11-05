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
import com.deepak.photovault.ui.shell.SamePersonQuestion
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
 *
 * 7A-2: faces now also keep how much they face the camera and how sharp they are (faces2.bin). Faces found before
 * (faces.bin) are used as "anchors" while every photo is looked at once more, so names and merges carry over; when
 * that is done the old faces are dropped.
 */
object FaceLibrary {
    private val lock = Mutex()
    private val scanLock = Mutex()
    val progress = MutableStateFlow<ScanProgress?>(null)

    @Volatile private var stopAsked = false

    private fun dir(context: Context) = File(context.applicationContext.filesDir, "people").apply { mkdirs() }
    private fun oldFacesFile(context: Context) = File(dir(context), "faces.bin")
    private fun oldScannedFile(context: Context) = File(dir(context), "scanned.bin")
    private fun midFacesFile(context: Context) = File(dir(context), "faces2.bin")
    private fun midScannedFile(context: Context) = File(dir(context), "scanned2.bin")
    private fun v3FacesFile(context: Context) = File(dir(context), "faces3.bin")
    private fun v3ScannedFile(context: Context) = File(dir(context), "scanned3.bin")
    private fun v4FacesFile(context: Context) = File(dir(context), "faces4.bin")
    private fun v4ScannedFile(context: Context) = File(dir(context), "scanned4.bin")

    /** Same layout as version 4, but measured with the mirror trick (5 Nov), so every photo is looked at again. */
    private fun facesFile(context: Context) = File(dir(context), "faces5.bin")
    private fun scannedFile(context: Context) = File(dir(context), "scanned5.bin")
    private fun peopleFile(context: Context) = File(dir(context), "people.json")
    fun thumbsDir(context: Context) = File(dir(context), "thumbs").apply { mkdirs() }

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("on", false)

    fun setEnabled(context: Context, on: Boolean) { prefs(context).edit().putBoolean("on", on).apply() }

    fun askByNotification(context: Context): Boolean = prefs(context).getBoolean("ask_notify", true)

    fun setAskByNotification(context: Context, on: Boolean) { prefs(context).edit().putBoolean("ask_notify", on).apply() }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("pixallery_people", Context.MODE_PRIVATE)

    /** Likeness of the questions answered No / Yes, so the line for asking fits this family's photos (5 Nov). */
    private fun scores(context: Context, key: String): List<Float> =
        prefs(context).getString(key, "").orEmpty().split(',').mapNotNull { it.toFloatOrNull() }

    private fun addScore(context: Context, key: String, value: Float) {
        val list = (scores(context, key) + value).takeLast(200)
        prefs(context).edit().putString(key, list.joinToString(",")).apply()
    }

    private fun askFloor(context: Context) = FaceGrouping.askFloor(scores(context, "no_scores"), scores(context, "yes_scores"))

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

    /** [version] 1: no quality; 2: pose and sharpness; 3: also eye visibility; 4: also the model's confidence (5 Nov). */
    private fun readFaces(file: File, version: Int): List<Face> {
        if (!file.exists()) return emptyList()
        val out = mutableListOf<Face>()
        DataInputStream(file.inputStream().buffered()).use { input ->
            try {
                while (true) {
                    val id = input.readInt()
                    val photo = input.readLong()
                    val box = FloatArray(4) { input.readFloat() }
                    val frontal = if (version >= 2) input.readFloat() else 1f
                    val sharp = if (version >= 2) input.readFloat() else Face.SHARP_DEFAULT
                    val eyes = if (version >= 3) input.readFloat() else 1f
                    val strength = if (version >= 4) input.readFloat() else 0f
                    val n = input.readShort().toInt()
                    val v = FloatArray(n) { input.readFloat() }
                    out += Face(id, photo, box, v, frontal, sharp, eyes, strength)
                }
            } catch (_: EOFException) {
                // The end (or a half-written last face after a crash, which is dropped).
            }
        }
        return out
    }

    /** New faces, plus the faces found by earlier versions while they are still needed (until every photo is looked at again). */
    private fun allFaces(context: Context): List<Face> =
        readFaces(oldFacesFile(context), 1) + readFaces(midFacesFile(context), 2) + readFaces(v3FacesFile(context), 3) + readFaces(v4FacesFile(context), 4) + readFaces(facesFile(context), 4)

    private fun olderFacesExist(context: Context) = oldFacesFile(context).exists() || midFacesFile(context).exists() || v3FacesFile(context).exists() || v4FacesFile(context).exists()

    private fun appendFaces(context: Context, faces: List<Face>) {
        if (faces.isEmpty()) return
        DataOutputStream(java.io.FileOutputStream(facesFile(context), true).buffered()).use { out ->
            for (f in faces) {
                out.writeInt(f.id)
                out.writeLong(f.photoId)
                f.box.forEach { out.writeFloat(it) }
                out.writeFloat(f.frontal)
                out.writeFloat(f.sharp)
                out.writeFloat(f.eyes)
                out.writeFloat(f.strength)
                out.writeShort(f.vector.size)
                f.vector.forEach { out.writeFloat(it) }
            }
        }
    }

    private fun readScanned(file: File): MutableSet<Long> {
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

    private class Saved(val people: List<Person>, val next: Int, val different: Set<Pair<Int, Int>>, val questions: List<SamePersonQuestion>)

    private fun readPeople(context: Context): Saved {
        val file = peopleFile(context)
        if (!file.exists()) return Saved(emptyList(), 1, emptySet(), emptyList())
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
            val different = root.optJSONArray("different")?.let { a -> (0 until a.length()).map { val p = a.getJSONArray(it); p.getInt(0) to p.getInt(1) }.toSet() }.orEmpty()
            val questions = root.optJSONArray("questions")?.let { a ->
                (0 until a.length()).map { val q = a.getJSONArray(it); SamePersonQuestion(q.getInt(0), q.getInt(1), q.getDouble(2).toFloat()) }
            }.orEmpty()
            Saved(list, root.optInt("next", (list.maxOfOrNull { it.id } ?: 0) + 1), different, questions)
        }.getOrDefault(Saved(emptyList(), 1, emptySet(), emptyList()))
    }

    private fun writePeople(context: Context, people: List<Person>, next: Int, different: Set<Pair<Int, Int>>, questions: List<SamePersonQuestion>) {
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
        val diff = JSONArray().apply { different.forEach { put(JSONArray(listOf(it.first, it.second))) } }
        val asks = JSONArray().apply { questions.forEach { put(JSONArray().put(it.a).put(it.b).put(it.score.toDouble())) } }
        val tmp = File(dir(context), "people.json.tmp")
        tmp.writeText(JSONObject().put("people", arr).put("next", next).put("different", diff).put("questions", asks).toString())
        if (!tmp.renameTo(peopleFile(context))) throw java.io.IOException("People couldn't be saved")
    }

    /** Groups new faces, joins groups of one person, and works out the questions. Under [lock]. */
    private fun regroup(context: Context, all: List<Face>, join: Boolean): List<SamePersonQuestion> {
        val saved = readPeople(context)
        var (grouped, after) = FaceGrouping.group(all, saved.people, saved.next)
        if (join) {
            // Groups check their own faces; the ones taken out are sorted again (5 Nov).
            val again = FaceGrouping.group(all, FaceGrouping.selfCheck(all, grouped), after)
            grouped = again.first
            after = again.second
        }
        val joinAt = FaceGrouping.joinLine(scores(context, "no_scores"), scores(context, "yes_scores"))
        val people = if (join) FaceGrouping.joinGroups(all, grouped, saved.different, joinAt) else grouped
        val questions = if (join) {
            val spots = all.associate { it.id to FaceSpot(it.id, it.photoId, it.box) }
            FaceGrouping.questions(all, FaceGrouping.shown(people.filter { !it.hidden }, spots), saved.different, floor = askFloor(context))
        } else {
            saved.questions
        }
        writePeople(context, people, after, saved.different, questions)
        return questions
    }

    // ---- Reading and changing ----

    suspend fun load(context: Context): PeopleSnapshot = lock.withLock {
        withContext(Dispatchers.Default) {
            val faces = allFaces(context)
            // Once, for people grouped before groups were compared with each other (5 Nov).
            if (prefs(context).getInt("joined", 0) < JOIN_VERSION && peopleFile(context).exists()) {
                runCatching { regroup(context, faces, join = true) }
                prefs(context).edit().putInt("joined", JOIN_VERSION).apply()
            }
            // Once, when the stricter rules arrive and no look-again is pending (5 Nov).
            if (prefs(context).getInt("rebuilt", 0) < REBUILD_VERSION && !olderFacesExist(context) && peopleFile(context).exists()) {
                runCatching { rebuildUnnamed(context) }
                prefs(context).edit().putInt("rebuilt", REBUILD_VERSION).apply()
            }
            val saved = readPeople(context)
            PeopleSnapshot(
                people = saved.people,
                spots = faces.associate { it.id to FaceSpot(it.id, it.photoId, it.box, it.frontal, it.sharp, it.eyes, it.strength) },
                scanned = readScanned(scannedFile(context)).size,
                questions = saved.questions,
                lookingAgain = olderFacesExist(context),
            )
        }
    }

    suspend fun edit(context: Context, change: (List<Person>) -> List<Person>) = lock.withLock {
        withContext(Dispatchers.IO) {
            val saved = readPeople(context)
            writePeople(context, change(saved.people), saved.next, saved.different, saved.questions)
        }
    }

    /** Sorts faces that belong to nobody now, straight away (after "Not this person", 5 Nov). */
    suspend fun tidy(context: Context) = lock.withLock {
        withContext(Dispatchers.Default) { runCatching { regroup(context, allFaces(context), join = false) } }
        Unit
    }

    /**
     * "Move to…" (5 Nov): the faces leave [fromId] for good and go to [toId], or to a new person when null. The target
     * forgets any earlier "not them" about these faces.
     */
    suspend fun move(context: Context, fromId: Int, faceIds: Set<Int>, toId: Int?) = lock.withLock {
        withContext(Dispatchers.IO) {
            val saved = readPeople(context)
            var next = saved.next
            val people = saved.people.map { p ->
                when (p.id) {
                    fromId -> FaceGrouping.remove(p, faceIds)
                    toId -> p.copy(faces = p.faces + faceIds, notFaces = p.notFaces - faceIds)
                    else -> p
                }
            }.toMutableList()
            if (toId == null) people += Person(next++, faceIds)
            writePeople(context, people.filter { it.faces.isNotEmpty() || it.name != null }, next, saved.different, saved.questions)
        }
    }

    /** "Same person?" answered: Yes joins the two, No is remembered. Either way the question goes. */
    suspend fun answer(context: Context, q: SamePersonQuestion, same: Boolean) = lock.withLock {
        withContext(Dispatchers.IO) {
            val saved = readPeople(context)
            addScore(context, if (same) "yes_scores" else "no_scores", q.score)
            val floor = askFloor(context)
            // Questions now under the (perhaps raised) line go too.
            val left = saved.questions.filterNot { (it.a == q.a && it.b == q.b) || it.score < floor }
            if (same) {
                val a = saved.people.firstOrNull { it.id == q.a }
                val b = saved.people.firstOrNull { it.id == q.b }
                if (a == null || b == null) {
                    writePeople(context, saved.people, saved.next, saved.different, left)
                    return@withContext
                }
                val keep = if (a.name != null || (b.name == null && a.faces.size >= b.faces.size)) a else b
                val gone = if (keep === a) b else a
                val merged = FaceGrouping.merge(keep, listOf(gone))
                // Questions about the person that was merged away now ask about the one that stayed.
                val moved = left.map { x ->
                    val na = if (x.a == gone.id) keep.id else x.a
                    val nb = if (x.b == gone.id) keep.id else x.b
                    SamePersonQuestion(minOf(na, nb), maxOf(na, nb), x.score)
                }.filter { it.a != it.b }.distinctBy { it.a to it.b }
                writePeople(context, saved.people.filter { it.id != a.id && it.id != b.id } + merged, saved.next, saved.different, moved)
            } else {
                writePeople(context, saved.people, saved.next, saved.different + (q.a to q.b), left)
            }
        }
    }

    // ---- Scanning ----

    /**
     * Looks at phone photos not looked at yet (since 7A-2, with sharpness and pose), newest first, up to [limit].
     * Returns true when photos are left for next time. Only one scan runs at a time; [stop] ends it after the current
     * photo. When every photo has been looked at again, the faces from before 7A-2 are dropped.
     */
    suspend fun scan(context: Context, limit: Int = Int.MAX_VALUE): Boolean = withContext(Dispatchers.Default) {
        if (!enabled(context)) return@withContext false
        if (!scanLock.tryLock()) return@withContext true
        stopAsked = false
        try {
            val engine = FaceEngine.create(context) ?: return@withContext false
            val photos = MediaStoreLibrary(context).loadAll().filter { !it.isVideo }.sortedByDescending { it.takenAtMillis }
            val done = lock.withLock { withContext(Dispatchers.IO) { readScanned(scannedFile(context)) } }
            val todo = photos.filter { it.id !in done }
            val batch = todo.take(limit)
            var nextFace = lock.withLock { withContext(Dispatchers.IO) { (allFaces(context).maxOfOrNull { it.id } ?: 0) + 1 } }
            val newFaces = mutableListOf<Face>()
            val newScanned = mutableListOf<Long>()
            var count = photos.count { it.id in done }
            progress.value = ScanProgress(count, photos.size, running = true)
            var questions: List<SamePersonQuestion> = emptyList()
            suspend fun flush(join: Boolean) {
                lock.withLock {
                    withContext(Dispatchers.IO) {
                        appendFaces(context, newFaces)
                        appendScanned(context, newScanned)
                        // Comparing whole groups is slower: done at the end of each turn, not every 40 photos.
                        questions = regroup(context, allFaces(context), join)
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
                        engine.find(bitmap).forEach { f -> newFaces += Face(nextFace++, item.id, f.box, f.vector, f.frontal, f.sharp, f.eyes, f.strength) }
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
            if (enabled(context)) {
                flush(join = true)
                if (count >= photos.size) finishLookingAgain(context)
            }
            progress.value = ScanProgress(count, photos.size, running = false)
            if (enabled(context)) maybeNotify(context, questions)
            todo.size > batch.size || (stopAsked && count < photos.size)
        } finally {
            scanLock.unlock()
        }
    }

    /**
     * Unnamed, unhidden groups are made again from scratch with the current rules (5 Nov: older, looser rules had joined
     * different people). Named and hidden people, and "No" answers about them, stay. Under [lock].
     */
    private fun rebuildUnnamed(context: Context) {
        val saved = readPeople(context)
        val keep = saved.people.filter { it.name != null || it.hidden }
        val keptIds = keep.map { it.id }.toSet()
        writePeople(
            context, keep, saved.next,
            saved.different.filter { it.first in keptIds && it.second in keptIds }.toSet(),
            emptyList(),
        )
        regroup(context, allFaces(context), join = true)
    }

    /** Every photo has been looked at again: faces from earlier versions leave the people, and their files go. */
    private suspend fun finishLookingAgain(context: Context) = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!olderFacesExist(context)) return@withContext
            val oldIds = (readFaces(oldFacesFile(context), 1) + readFaces(midFacesFile(context), 2) + readFaces(v3FacesFile(context), 3) + readFaces(v4FacesFile(context), 4)).map { it.id }.toSet()
            val saved = readPeople(context)
            val people = saved.people.map { p -> p.copy(faces = p.faces - oldIds, notFaces = p.notFaces - oldIds) }
                .filter { it.faces.isNotEmpty() || it.name != null }
            writePeople(context, people, saved.next, saved.different, saved.questions)
            listOf(oldFacesFile(context), oldScannedFile(context), midFacesFile(context), midScannedFile(context), v3FacesFile(context), v3ScannedFile(context), v4FacesFile(context), v4ScannedFile(context)).forEach { it.delete() }
            oldIds.forEach { File(thumbsDir(context), "$it.jpg").delete() }
            rebuildUnnamed(context)
            prefs(context).edit().putInt("rebuilt", REBUILD_VERSION).apply()
        }
    }

    /** One quiet notification a day at most, when "Same person?" questions are waiting (7A-2). */
    private fun maybeNotify(context: Context, questions: List<SamePersonQuestion>) {
        if (questions.isEmpty() || !askByNotification(context)) return
        val today = System.currentTimeMillis() / 86_400_000L
        val p = prefs(context)
        if (p.getLong("notified_day", -1L) == today) return
        if (PeopleNotice.show(context, questions.size)) p.edit().putLong("notified_day", today).apply()
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
    private const val REBUILD_VERSION = 1
    private const val MAX_SIDE = 1280
    private const val FLUSH_EVERY = 40
}
