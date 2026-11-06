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
import com.deepak.photovault.ui.shell.UnsureSet
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

    /** The test model (6 Nov): AuraFace keeps its own faces, people and names in people/aura. */
    fun useAura(context: Context): Boolean = prefs(context).getString("model", "standard") == "aura"

    fun setAura(context: Context, on: Boolean) { prefs(context).edit().putString("model", if (on) "aura" else "standard").apply() }

    private fun dir(context: Context): File {
        val aura = useAura(context)
        // Each model scores on its own scale: the lines follow the model in use.
        FaceGrouping.use(if (aura) com.deepak.photovault.ui.shell.FaceLines.AURA else com.deepak.photovault.ui.shell.FaceLines.SFACE)
        val base = File(context.applicationContext.filesDir, "people")
        return (if (aura) File(base, "aura") else base).apply { mkdirs() }
    }

    /** Settings that belong to one model's people. */
    private fun key(context: Context, name: String) = if (useAura(context)) "aura_$name" else name

    /** Faces found by earlier versions (file, list of photos looked at, layout version): anchors until looked at again. */
    private val OLDER = listOf(
        Triple("faces.bin", "scanned.bin", 1),
        Triple("faces2.bin", "scanned2.bin", 2),
        Triple("faces3.bin", "scanned3.bin", 3),
        Triple("faces4.bin", "scanned4.bin", 4),
        Triple("faces5.bin", "scanned5.bin", 4),
        Triple("faces6.bin", "scanned6.bin", 4),
    )

    /**
     * 6 Nov (second build): dark faces brightened, AuraFace's mirror trick, a bigger look for small faces, plus when each
     * photo was taken and the clothes' colours. Every photo is looked at once more (layout version 5).
     */
    private fun facesFile(context: Context) = File(dir(context), "faces7.bin")
    private fun scannedFile(context: Context) = File(dir(context), "scanned7.bin")
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

    private fun askFloor(context: Context) = FaceGrouping.askFloor(scores(context, key(context, "no_scores")), scores(context, key(context, "yes_scores")))

    /**
     * Turning People off (6 Nov: one model at a time): every face, name and face picture of the model in use goes; the
     * other model's list stays. Waits for a running scan to stop first.
     */
    suspend fun forgetModel(context: Context) {
        waitForScan()
        lock.withLock {
            withContext(Dispatchers.IO) {
                val base = File(context.applicationContext.filesDir, "people")
                if (useAura(context)) {
                    File(base, "aura").deleteRecursively()
                } else {
                    base.listFiles()?.filter { it.name != "models" && it.name != "aura" }?.forEach { it.deleteRecursively() }
                }
                val p = prefs(context)
                p.edit().apply { listOf("joined", "rebuilt", "no_scores", "yes_scores", "notified_day").forEach { remove(key(context, it)) } }.apply()
                progress.value = null
            }
        }
    }

    /** Switches model (6 Nov): a running scan stops first, so its faces are saved with the model that found them. */
    suspend fun switchModel(context: Context, aura: Boolean) {
        waitForScan()
        setAura(context, aura)
        progress.value = null
    }

    /** The Scan button (6 Nov): a background scan that is running stops first, so the shown scan takes over. */
    suspend fun takeOver() = waitForScan()

    private suspend fun waitForScan() {
        again = false
        stopAsked = true
        while (scanLock.isLocked) kotlinx.coroutines.delay(100)
    }

    fun stop() { again = false; stopAsked = true }

    /** Scan was asked for while one was finishing (6 Nov: "Scan" sometimes did nothing): it runs again straight after. */
    @Volatile private var again = false

    // ---- Files ----

    /**
     * [version] 1: no quality; 2: pose and sharpness; 3: also eye visibility; 4: also the model's confidence (5 Nov);
     * 5: also when the photo was taken and the clothes' colours (6 Nov).
     */
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
                    val taken = if (version >= 5) input.readLong() else 0L
                    val clothes = if (version >= 5) {
                        val c = input.readShort().toInt()
                        if (c > 0) FloatArray(c) { input.readFloat() } else null
                    } else null
                    val n = input.readShort().toInt()
                    val v = FloatArray(n) { input.readFloat() }
                    out += Face(id, photo, box, v, frontal, sharp, eyes, strength, taken, clothes)
                }
            } catch (_: EOFException) {
                // The end (or a half-written last face after a crash, which is dropped).
            }
        }
        return out
    }

    /** New faces, plus the faces found by earlier versions while they are still needed (until every photo is looked at again). */
    private fun olderFaces(context: Context): List<Face> = OLDER.flatMap { (f, _, v) -> readFaces(File(dir(context), f), v) }

    private fun allFaces(context: Context): List<Face> = olderFaces(context) + readFaces(facesFile(context), 5)

    private fun olderFacesExist(context: Context) = OLDER.any { File(dir(context), it.first).exists() }

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
                out.writeLong(f.takenAt)
                val c = f.clothes
                out.writeShort(c?.size ?: 0)
                c?.forEach { out.writeFloat(it) }
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

    private class Saved(
        val people: List<Person>,
        val next: Int,
        val different: Set<Pair<Int, Int>>,
        val questions: List<SamePersonQuestion>,
        val unsure: List<UnsureSet> = emptyList(),
        val parked: List<List<Int>> = emptyList(),
    )

    private fun readUnsure(root: JSONObject): List<UnsureSet> = root.optJSONArray("unsure")?.let { a ->
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val f = o.getJSONArray("faces")
            val r = o.getJSONArray("ranked")
            UnsureSet(
                (0 until f.length()).map { f.getInt(it) },
                (0 until r.length()).map { val x = r.getJSONArray(it); x.getInt(0) to x.getDouble(1).toFloat() },
                o.optInt("guesses", 0),
            )
        }
    }.orEmpty()

    private fun readParked(root: JSONObject): List<List<Int>> = root.optJSONArray("parked")?.let { a ->
        (0 until a.length()).map { i -> val f = a.getJSONArray(i); (0 until f.length()).map { f.getInt(it) } }
    }.orEmpty()

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
                    kept = o.optBoolean("kept", false),
                    me = o.optBoolean("me", false),
                )
            }
            val different = root.optJSONArray("different")?.let { a -> (0 until a.length()).map { val p = a.getJSONArray(it); p.getInt(0) to p.getInt(1) }.toSet() }.orEmpty()
            val questions = root.optJSONArray("questions")?.let { a ->
                (0 until a.length()).map { val q = a.getJSONArray(it); SamePersonQuestion(q.getInt(0), q.getInt(1), q.getDouble(2).toFloat()) }
            }.orEmpty()
            Saved(list, root.optInt("next", (list.maxOfOrNull { it.id } ?: 0) + 1), different, questions, readUnsure(root), readParked(root))
        }.getOrDefault(Saved(emptyList(), 1, emptySet(), emptyList()))
    }

    /** [unsure] and [parked] null: kept as they are in the file. */
    private fun writePeople(
        context: Context,
        people: List<Person>,
        next: Int,
        different: Set<Pair<Int, Int>>,
        questions: List<SamePersonQuestion>,
        unsure: List<UnsureSet>? = null,
        parked: List<List<Int>>? = null,
    ) {
        val old = if (unsure == null || parked == null) readPeople(context) else null
        val sets = unsure ?: old!!.unsure
        val aside = parked ?: old!!.parked
        // Faces now in a person no longer wait in a set.
        val inPeople = people.flatMap { it.faces }.toHashSet()
        val keptSets = sets.map { s -> s.copy(faces = s.faces.filter { it !in inPeople }) }.filter { it.faces.isNotEmpty() }
        val keptAside = aside.map { s -> s.filter { it !in inPeople } }.filter { it.isNotEmpty() }
        val unsureJson = JSONArray().apply {
            keptSets.forEach { s ->
                put(JSONObject().put("faces", JSONArray(s.faces)).put("guesses", s.guesses)
                    .put("ranked", JSONArray().apply { s.ranked.forEach { put(JSONArray().put(it.first).put(it.second.toDouble())) } }))
            }
        }
        val parkedJson = JSONArray().apply { keptAside.forEach { put(JSONArray(it)) } }
        val arr = JSONArray()
        people.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("faces", JSONArray(p.faces.toList()))
                if (p.name != null) put("name", p.name)
                if (p.hidden) put("hidden", true)
                if (p.notFaces.isNotEmpty()) put("not", JSONArray(p.notFaces.toList()))
                if (p.kept) put("kept", true)
                if (p.me) put("me", true)
            })
        }
        val diff = JSONArray().apply { different.forEach { put(JSONArray(listOf(it.first, it.second))) } }
        val asks = JSONArray().apply { questions.forEach { put(JSONArray().put(it.a).put(it.b).put(it.score.toDouble())) } }
        val tmp = File(dir(context), "people.json.tmp")
        tmp.writeText(
            JSONObject().put("people", arr).put("next", next).put("different", diff).put("questions", asks)
                .put("unsure", unsureJson).put("parked", parkedJson).toString(),
        )
        if (!tmp.renameTo(peopleFile(context))) throw java.io.IOException("People couldn't be saved")
    }

    /** Groups new faces, joins groups of one person, and works out the questions. Under [lock]. */
    private fun regroup(context: Context, all: List<Face>, join: Boolean, resort: Boolean = false): List<SamePersonQuestion> {
        var saved = readPeople(context)
        var apart = emptySet<Pair<Int, Int>>()
        if (resort) {
            // Sorted again from scratch after a scan (6 Nov): only named, hidden, hand-changed people and "me" stay as they are.
            val keep = saved.people.filter { it.name != null || it.hidden || it.kept || it.me }
            val ids = keep.map { it.id }.toSet()
            // What earlier checks learnt stays (6 Nov): a face taken out of a group is kept apart from that group's faces.
            apart = saved.people.filter { it.id !in ids && it.notFaces.isNotEmpty() }.flatMap { p ->
                val sample = FaceGrouping.spread(p.faces.sorted(), 6)
                p.notFaces.flatMap { n -> sample.map { n to it } }
            }.toSet()
            saved = Saved(keep, saved.next, saved.different.filter { it.first in ids && it.second in ids }.toSet(), emptyList())
        }
        var (grouped, after) = if (resort) FaceGrouping.resort(all, saved.people, saved.next, apart) else FaceGrouping.group(all, saved.people, saved.next)
        if (join) {
            // Groups check their own faces; the ones taken out are sorted again (5 Nov).
            val again = FaceGrouping.group(all, FaceGrouping.selfCheck(all, grouped), after)
            grouped = again.first
            after = again.second
        }
        val joinAt = FaceGrouping.joinLine(scores(context, key(context, "no_scores")), scores(context, key(context, "yes_scores")))
        var people = if (join) FaceGrouping.joinGroups(all, grouped, saved.different, joinAt) else grouped
        // Faces the app isn't sure about are asked about, not grouped on a guess (6 Nov).
        val parked = readPeople(context).parked
        var unsure: List<UnsureSet>? = null
        if (join) {
            val (left, sets) = FaceGrouping.unsure(all, people, parked.flatten().toHashSet(), floor = askFloor(context))
            people = left
            unsure = sets
        }
        val questions = if (join) {
            val spots = all.associate { it.id to FaceSpot(it.id, it.photoId, it.box) }
            FaceGrouping.questions(all, FaceGrouping.shown(people.filter { !it.hidden }, spots), saved.different, floor = askFloor(context))
        } else {
            saved.questions
        }
        writePeople(context, people, after, saved.different, questions, unsure, parked)
        return questions
    }

    // ---- Reading and changing ----

    suspend fun load(context: Context): PeopleSnapshot = lock.withLock {
        withContext(Dispatchers.Default) {
            val faces = allFaces(context)
            // Once, for people grouped before groups were compared with each other (5 Nov).
            if (prefs(context).getInt(key(context, "joined"), 0) < JOIN_VERSION && peopleFile(context).exists()) {
                runCatching { regroup(context, faces, join = true) }
                prefs(context).edit().putInt(key(context, "joined"), JOIN_VERSION).apply()
            }
            // Once, when the stricter rules arrive and no look-again is pending (5 Nov).
            if (prefs(context).getInt(key(context, "rebuilt"), 0) < REBUILD_VERSION && !olderFacesExist(context) && peopleFile(context).exists()) {
                runCatching { rebuildUnnamed(context) }
                prefs(context).edit().putInt(key(context, "rebuilt"), REBUILD_VERSION).apply()
            }
            val saved = readPeople(context)
            PeopleSnapshot(
                people = saved.people,
                spots = faces.associate { it.id to FaceSpot(it.id, it.photoId, it.box, it.frontal, it.sharp, it.eyes, it.strength) },
                scanned = readScanned(scannedFile(context)).size,
                questions = saved.questions,
                lookingAgain = olderFacesExist(context),
                unsure = saved.unsure,
                parked = saved.parked,
            )
        }
    }

    suspend fun edit(context: Context, change: (List<Person>) -> List<Person>) = lock.withLock {
        withContext(Dispatchers.IO) {
            val saved = readPeople(context)
            writePeople(context, change(saved.people), saved.next, saved.different, saved.questions)
        }
    }

    /**
     * Downloads AuraFace once (6 Nov): about 66 MB, only on an unmetered network (Wi-Fi), checked against its known
     * SHA-256 before it is used. Null when it worked, else the problem in plain words.
     */
    suspend fun downloadAura(context: Context, onProgress: (Float) -> Unit): String? = withContext(Dispatchers.IO) {
        val file = FaceEngine.auraFile(context)
        if (file.exists()) return@withContext null
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null || !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            return@withContext "Connect to Wi-Fi first: the test model is about 66 MB."
        }
        file.parentFile?.mkdirs()
        val part = File(file.parentFile, file.name + ".part")
        try {
            val connection = java.net.URL(FaceEngine.AURA_URL).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in 200..299) return@withContext "The test model couldn't be downloaded (error ${connection.responseCode})."
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: 66_000_000L
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var done = 0L
            connection.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (hex != FaceEngine.AURA_SHA256) {
                part.delete()
                return@withContext "The downloaded model didn't pass its check, so it wasn't used."
            }
            if (!part.renameTo(file)) return@withContext "The test model couldn't be saved."
            null
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: Exception) {
            part.delete()
            "The test model couldn't be downloaded. Check the Wi-Fi and try again."
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
                    toId -> p.copy(faces = p.faces + faceIds, notFaces = p.notFaces - faceIds, kept = true)
                    else -> p
                }
            }.toMutableList()
            if (toId == null) people += Person(next++, faceIds, kept = true)
            writePeople(context, people.filter { it.faces.isNotEmpty() || it.name != null }, next, saved.different, saved.questions)
        }
    }

    /**
     * An unsure set answered (6 Nov). Yes (or picked from the grid): the faces join that person, or a new one when
     * [personId] is null, and teach it (kept). No: that person won't get these faces and isn't asked about again; the set
     * moves on to the next guess. [park]: "None of these", the faces go to the Not sure list.
     */
    suspend fun answerUnsure(context: Context, set: UnsureSet, personId: Int?, yes: Boolean, park: Boolean) = lock.withLock {
        withContext(Dispatchers.IO) {
            val saved = readPeople(context)
            val ids = set.faces.toSet()
            val sameSet: (UnsureSet) -> Boolean = { s -> s.faces.any { it in ids } }
            var next = saved.next
            var people = saved.people
            var sets = saved.unsure
            var parked = saved.parked.map { s -> s.filter { it !in ids } }.filter { it.isNotEmpty() }
            when {
                park -> {
                    sets = sets.filterNot(sameSet)
                    parked = parked + listOf(set.faces)
                }
                yes -> {
                    // Out of wherever they were (a set taken apart may still be listed), into the chosen person.
                    people = people.map { p -> if (p.faces.any { it in ids } && p.id != personId) p.copy(faces = p.faces - ids) else p }
                    people = if (personId != null && people.any { it.id == personId }) {
                        people.map { p -> if (p.id == personId) p.copy(faces = p.faces + ids, notFaces = p.notFaces - ids, kept = true) else p }
                    } else {
                        people + Person(next++, ids, kept = true)
                    }
                    sets = sets.filterNot(sameSet)
                }
                personId != null -> {
                    people = people.map { p -> if (p.id == personId) p.copy(notFaces = p.notFaces + ids, kept = true) else p }
                    sets = sets.map { s ->
                        if (!sameSet(s)) s else s.copy(ranked = s.ranked.filter { it.first != personId }, guesses = (s.guesses - 1).coerceAtLeast(0))
                    }
                }
            }
            writePeople(context, people.filter { it.faces.isNotEmpty() || it.name != null }, next, saved.different, saved.questions, sets, parked)
        }
    }

    /** "Same person?" answered: Yes joins the two, No is remembered. Either way the question goes. */
    suspend fun answer(context: Context, q: SamePersonQuestion, same: Boolean) = lock.withLock {
        withContext(Dispatchers.IO) {
            val saved = readPeople(context)
            addScore(context, key(context, if (same) "yes_scores" else "no_scores"), q.score)
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
                val merged = FaceGrouping.merge(keep, listOf(gone)).copy(kept = true)
                // Questions about the person that was merged away now ask about the one that stayed.
                val moved = left.map { x ->
                    val na = if (x.a == gone.id) keep.id else x.a
                    val nb = if (x.b == gone.id) keep.id else x.b
                    SamePersonQuestion(minOf(na, nb), maxOf(na, nb), x.score)
                }.filter { it.a != it.b }.distinctBy { it.a to it.b }
                writePeople(context, saved.people.filter { it.id != a.id && it.id != b.id } + merged, saved.next, saved.different, moved)
            } else {
                // Both stay as they are when everything is sorted again (6 Nov), so the answer keeps meaning something.
                val marked = saved.people.map { if (it.id == q.a || it.id == q.b) it.copy(kept = true) else it }
                writePeople(context, marked, saved.next, saved.different + (q.a to q.b), left)
            }
        }
    }

    // ---- Scanning ----

    /**
     * Looks at phone photos not looked at yet (since 7A-2, with sharpness and pose), newest first, up to [limit].
     * Returns true when photos are left for next time. Only one scan runs at a time; [stop] ends it after the current
     * photo. When every photo has been looked at again, the faces from before 7A-2 are dropped.
     */
    suspend fun scan(context: Context, limit: Int = Int.MAX_VALUE, fromButton: Boolean = false): Boolean = withContext(Dispatchers.Default) {
        if (!enabled(context)) return@withContext false
        if (!scanLock.tryLock()) {
            // One is running or finishing. The Scan button makes it carry on (or run once more); a background turn
            // just skips (6 Nov: it used to ask again and again, which made the button blink).
            if (fromButton) {
                stopAsked = false
                again = true
            }
            return@withContext false
        }
        try {
            var more: Boolean
            do {
                again = false
                stopAsked = false
                more = scanOnce(context, limit)
            } while (again && enabled(context))
            more
        } finally {
            scanLock.unlock()
        }
    }

    private suspend fun scanOnce(context: Context, limit: Int): Boolean {
        return run {
            val model = useAura(context)
            val engine = FaceEngine.create(context, model)
            if (engine == null) {
                progress.value = ScanProgress(0, 0, running = false, problem = (if (model) "The AuraFace model couldn't start on this phone." else "The face finder couldn't start on this phone.") +
                    (FaceEngine.lastError?.let { " The phone said: $it" } ?: ""))
                return false
            }
            val photos = MediaStoreLibrary(context).loadAll().filter { !it.isVideo }.sortedByDescending { it.takenAtMillis }
            val done = lock.withLock { withContext(Dispatchers.IO) { readScanned(scannedFile(context)) } }
            val todo = photos.filter { it.id !in done }
            val batch = todo.take(limit)
            // Nothing new (6 Nov): ends quietly, without sorting every face again or flashing the button.
            if (batch.isEmpty()) {
                if (enabled(context) && olderFacesExist(context)) finishLookingAgain(context)
                progress.value = progress.value?.takeIf { it.problem == null }?.let { ScanProgress(photos.size, photos.size, running = false) }
                engine.close()
                return false
            }
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
                        // Comparing whole groups is slower: done at the end of each turn, not every 40 photos. At the
                        // end every face is sorted again, all together (6 Nov).
                        questions = regroup(context, allFaces(context), join, resort = join)
                    }
                }
                newFaces.clear()
                newScanned.clear()
            }
            for (item in batch) {
                if (stopAsked || !enabled(context) || useAura(context) != model) break
                var long = 0
                val bitmap = decode(context, item.uri, MAX_SIDE) { long = it }
                if (bitmap != null) {
                    try {
                        var found = engine.find(bitmap)
                        // Small faces (6 Nov): a big photo with faces too small at the usual size gets a second, bigger look.
                        if (engine.smallSkipped > 0 && long > MAX_SIDE * 1.4f) {
                            val big = decode(context, item.uri, BIG_SIDE)
                            if (big != null) {
                                try {
                                    val closer = engine.find(big)
                                    if (closer.size > found.size) found = closer
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Throwable) {
                                } finally {
                                    big.recycle()
                                }
                            }
                        }
                        found.forEach { f -> newFaces += Face(nextFace++, item.id, f.box, f.vector, f.frontal, f.sharp, f.eyes, f.strength, item.takenAtMillis, f.clothes) }
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
            // Only saved under the model that found them (6 Nov).
            val same = useAura(context) == model
            if (enabled(context) && same) {
                flush(join = true)
                if (count >= photos.size) finishLookingAgain(context)
            }
            if (same) progress.value = ScanProgress(count, photos.size, running = false)
            engine.close()
            if (enabled(context) && same) maybeNotify(context, questions)
            return todo.size > batch.size || (stopAsked && count < photos.size)
        }
    }

    /**
     * Unnamed, unhidden groups are made again from scratch with the current rules (5 Nov: older, looser rules had joined
     * different people). Named and hidden people, and "No" answers about them, stay. Under [lock].
     */
    private fun rebuildUnnamed(context: Context) {
        regroup(context, allFaces(context), join = true, resort = true)
    }

    /** Every photo has been looked at again: faces from earlier versions leave the people, and their files go. */
    private suspend fun finishLookingAgain(context: Context) = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!olderFacesExist(context)) return@withContext
            val oldIds = olderFaces(context).map { it.id }.toSet()
            val saved = readPeople(context)
            val people = saved.people.map { p -> p.copy(faces = p.faces - oldIds, notFaces = p.notFaces - oldIds) }
                .filter { it.faces.isNotEmpty() || it.name != null }
            writePeople(context, people, saved.next, saved.different, saved.questions)
            OLDER.forEach { (f, sc, _) -> File(dir(context), f).delete(); File(dir(context), sc).delete() }
            oldIds.forEach { File(thumbsDir(context), "$it.jpg").delete() }
            rebuildUnnamed(context)
            prefs(context).edit().putInt(key(context, "rebuilt"), REBUILD_VERSION).apply()
        }
    }

    /** One quiet notification a day at most, when "Same person?" questions are waiting (7A-2). */
    private fun maybeNotify(context: Context, questions: List<SamePersonQuestion>) {
        if (questions.isEmpty() || !askByNotification(context)) return
        val today = System.currentTimeMillis() / 86_400_000L
        val p = prefs(context)
        if (p.getLong(key(context, "notified_day"), -1L) == today) return
        if (PeopleNotice.show(context, questions.size)) p.edit().putLong(key(context, "notified_day"), today).apply()
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

    private fun decode(context: Context, uri: String, maxSide: Int, onSize: (Int) -> Unit = {}): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, Uri.parse(uri))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val long = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
            onSize(long)
            if (long > maxSide) {
                val scale = maxSide.toFloat() / long
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            }
        }.let { if (it.config != Bitmap.Config.ARGB_8888) it.copy(Bitmap.Config.ARGB_8888, false) else it }
    }.getOrNull()

    private const val JOIN_VERSION = 1
    private const val REBUILD_VERSION = 1
    private const val MAX_SIDE = 1280

    /** The second, bigger look for small faces (6 Nov). */
    private const val BIG_SIDE = 2560
    private const val FLUSH_EVERY = 40
}
