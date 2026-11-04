package com.deepak.photovault.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

/**
 * One face found in a phone photo (Phase 7A, People). [vector] is the face's measurements, never a picture.
 * [frontal] is 1 for a face looking straight at the camera and 0 for a side view; [sharp] is how crisp the face is
 * (7A-2; faces found before that count as front and sharp).
 */
class Face(val id: Int, val photoId: Long, val box: FloatArray, val vector: FloatArray, val frontal: Float = 1f, val sharp: Float = SHARP_DEFAULT) {
    val turned: Boolean get() = frontal < FaceGrouping.TURNED

    companion object {
        const val SHARP_DEFAULT = 1000f
    }
}

/** Where a face is in its photo ([box] = left, top, width, height, each 0 to 1), for its small round picture. */
class FaceSpot(val id: Int, val photoId: Long, val box: FloatArray, val frontal: Float = 1f, val sharp: Float = Face.SHARP_DEFAULT)

/** Two groups that may be one person: People asks (7A-2). */
data class SamePersonQuestion(val a: Int, val b: Int, val score: Float)

/** A group of faces of one person. [notFaces] were taken out by hand and never join this person again. */
data class Person(
    val id: Int,
    val faces: Set<Int>,
    val name: String? = null,
    val hidden: Boolean = false,
    val notFaces: Set<Int> = emptySet(),
)

/**
 * Faces become people (7A, 5 Nov). A face joins the person it looks most like when it is close enough (cosine of the
 * two measurements at least [SAME]); otherwise it starts a new person. Faces already in a person stay there, so
 * names, merges and removals made by hand are never undone. A person is compared by the average of its faces and by a
 * few of its own faces, so a merged person (with and without sunglasses, say) matches both looks afterwards.
 */
object FaceGrouping {
    /** SFace's own "same person" line is 0.363; a little stricter, because merging is easier than splitting. */
    const val SAME = 0.42f

    /** Below this a face is turned too far to start a person of its own (7A-2): it can only join one. */
    const val TURNED = 0.35f

    /** Groups this alike, but not enough to join by themselves, become a "Same person?" question (7A-2). */
    const val ASK = 0.24f

    /** How many of a person's faces are compared as well as the average. */
    private const val EXAMPLES = 24

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val d = sqrt(na) * sqrt(nb)
        return if (d == 0f) 0f else dot / d
    }

    private class Group(val person: Person, val sum: FloatArray, val examples: MutableList<FloatArray>, val faces: MutableSet<Int>, val photos: MutableSet<Long>)

    /** Puts every face that isn't in a person yet into the right one, or a new one. Returns the people and the next free id. */
    fun group(faces: List<Face>, people: List<Person>, nextId: Int): Pair<List<Person>, Int> {
        val byId = faces.associateBy { it.id }
        val placed = people.flatMap { it.faces }.toHashSet()
        val groups = people.map { p ->
            val vectors = p.faces.mapNotNull { byId[it]?.vector }
            val sum = FloatArray(faces.firstOrNull()?.vector?.size ?: 0)
            vectors.forEach { v -> for (i in sum.indices) sum[i] += v[i] }
            Group(p, sum, spread(vectors, EXAMPLES).toMutableList(), p.faces.toMutableSet(), p.faces.mapNotNull { byId[it]?.photoId }.toMutableSet())
        }.toMutableList()
        var next = nextId
        // Front faces first, so a side view can join a person made in the same scan.
        for (face in faces.sortedWith(compareBy<Face> { it.turned }.thenBy { it.id })) {
            if (face.id in placed) continue
            var best: Group? = null
            var bestScore = SAME - 1e-6f
            for (g in groups) {
                // Two faces in one photo are never the same person (7A-2).
                if (face.id in g.person.notFaces || g.faces.isEmpty() || face.photoId in g.photos) continue
                var score = cosine(face.vector, g.sum)
                for (e in g.examples) {
                    val s = cosine(face.vector, e)
                    if (s > score) score = s
                }
                // Strictly better only: on a tie the older person (named, or merged by hand) keeps the face.
                if (score > bestScore) {
                    bestScore = score
                    best = g
                }
            }
            // A side view that matches nobody waits: side views made most of the extra groups of one person.
            if (best == null && face.turned) continue
            val target = best ?: Group(Person(next++, emptySet()), FloatArray(face.vector.size), mutableListOf(), mutableSetOf(), mutableSetOf()).also { groups += it }
            target.faces += face.id
            target.photos += face.photoId
            for (i in target.sum.indices) target.sum[i] += face.vector[i]
            // Keeps a spread of looks, not only the first ones (5 Nov).
            if (target.examples.size < EXAMPLES) target.examples += face.vector else target.examples[target.faces.size % EXAMPLES] = face.vector
            placed += face.id
        }
        return groups.filter { it.faces.isNotEmpty() }.map { it.person.copy(faces = it.faces.toSet()) } to next
    }

    /** Up to [n] items spread evenly over [items] (so a group is judged by all its looks, not its first photos). */
    fun <T> spread(items: List<T>, n: Int): List<T> = if (items.size <= n) items else List(n) { items[it * items.size / n] }

    private fun centroid(vectors: List<FloatArray>): FloatArray {
        val c = FloatArray(vectors.firstOrNull()?.size ?: 0)
        vectors.forEach { v -> for (i in c.indices) c[i] += v[i] }
        return c
    }

    /** How alike two groups are as a whole: the average likeness of their faces, many against many. */
    fun linkage(a: List<FloatArray>, b: List<FloatArray>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        var total = 0f
        for (x in a) for (y in b) total += cosine(x, y)
        return total / (a.size * b.size)
    }

    /** Two groups this alike on average are one person (5 Nov: the same face had split into groups of 647 and 72). */
    const val JOIN_GROUPS = 0.36f

    /** Groups whose averages are further apart than this are not compared face by face (it is slow). */
    private const val NEAR = 0.30f
    private const val SAMPLE = 20

    /**
     * Joins groups that are clearly the same person. Grouping goes photo by photo, so one odd early photo could start
     * a second group of someone that then kept growing; this compares whole groups afterwards. Two different names are
     * never joined. The named (or bigger) group keeps its place.
     */
    fun joinGroups(faces: List<Face>, people: List<Person>, different: Set<Pair<Int, Int>> = emptySet()): List<Person> {
        val byId = faces.associateBy { it.id }
        var current = people
        repeat(12) {
            val vectors = current.map { p -> spread(p.faces.sorted().mapNotNull { byId[it]?.vector }, SAMPLE) }
            val centres = vectors.map { centroid(it) }
            val pairs = mutableListOf<Triple<Int, Int, Float>>()
            for (i in current.indices) for (j in i + 1 until current.size) {
                val a = current[i]
                val b = current[j]
                if (a.name != null && b.name != null && !a.name.equals(b.name, ignoreCase = true)) continue
                if (vectors[i].isEmpty() || vectors[j].isEmpty()) continue
                if (said(different, a.id, b.id) || sharePhotos(a, b, byId)) continue
                if (cosine(centres[i], centres[j]) < NEAR) continue
                val score = linkage(vectors[i], vectors[j])
                if (score >= JOIN_GROUPS) pairs += Triple(i, j, score)
            }
            if (pairs.isEmpty()) return current
            val used = HashSet<Int>()
            val merged = mutableListOf<Person>()
            for ((i, j, _) in pairs.sortedByDescending { it.third }) {
                if (i in used || j in used) continue
                used += i
                used += j
                val a = current[i]
                val b = current[j]
                val keep = when {
                    a.name != null -> a
                    b.name != null -> b
                    a.faces.size >= b.faces.size -> a
                    else -> b
                }
                merged += merge(keep, listOf(if (keep === a) b else a))
            }
            current = current.filterIndexed { k, _ -> k !in used } + merged
        }
        return current
    }

    private fun said(different: Set<Pair<Int, Int>>, a: Int, b: Int) = (minOf(a, b) to maxOf(a, b)) in different

    /** Both groups have a face in the same photos (more than a stray mistake): two different people. */
    private fun sharePhotos(a: Person, b: Person, byId: Map<Int, Face>): Boolean {
        val pa = a.faces.mapNotNull { byId[it]?.photoId }.toSet()
        val pb = b.faces.mapNotNull { byId[it]?.photoId }.toSet()
        val shared = pa.count { it in pb }
        return shared > maxOf(1, minOf(pa.size, pb.size) / 50)
    }

    /**
     * "Same person?" questions (7A-2): pairs of [shown] groups that look alike but not enough to join by themselves,
     * most alike first. Never two names, never a pair already answered No, never two groups in the same photos.
     */
    fun questions(faces: List<Face>, shown: List<Person>, different: Set<Pair<Int, Int>>, limit: Int = 30): List<SamePersonQuestion> {
        val byId = faces.associateBy { it.id }
        val vectors = shown.map { p -> spread(p.faces.sorted().mapNotNull { byId[it]?.vector }, SAMPLE) }
        val centres = vectors.map { centroid(it) }
        val out = mutableListOf<SamePersonQuestion>()
        for (i in shown.indices) for (j in i + 1 until shown.size) {
            val a = shown[i]
            val b = shown[j]
            if (a.name != null && b.name != null) continue
            if (vectors[i].isEmpty() || vectors[j].isEmpty()) continue
            if (said(different, a.id, b.id) || sharePhotos(a, b, byId)) continue
            if (cosine(centres[i], centres[j]) < ASK) continue
            val score = linkage(vectors[i], vectors[j])
            if (score >= ASK && score < JOIN_GROUPS) out += SamePersonQuestion(minOf(a.id, b.id), maxOf(a.id, b.id), score)
        }
        return out.sortedByDescending { it.score }.take(limit)
    }

    /** [into] keeps its name (or takes the other's), and gets every face of [others]. */
    fun merge(into: Person, others: List<Person>): Person = into.copy(
        faces = into.faces + others.flatMap { it.faces },
        name = into.name ?: others.firstNotNullOfOrNull { it.name },
        hidden = into.hidden && others.all { it.hidden },
        notFaces = into.notFaces + others.flatMap { it.notFaces },
    )

    /** Takes [faceIds] out of [person] for good: they find another person (or start one) at the next grouping. */
    fun remove(person: Person, faceIds: Set<Int>): Person = person.copy(faces = person.faces - faceIds, notFaces = person.notFaces + faceIds)

    /** People to show: named ones always, others once they appear in at least [minPhotos] photos. */
    fun shown(people: List<Person>, spots: Map<Int, FaceSpot>, minPhotos: Int = 2, newest: (Person) -> Long = { 0L }): List<Person> =
        people.filter { p -> p.name != null || p.faces.mapNotNull { spots[it]?.photoId }.toSet().size >= minPhotos }
            // Named people first, whoever has the newest photo at the front; then the rest by most photos (5 Nov).
            .sortedWith(compareBy<Person> { it.name == null }.thenByDescending { if (it.name != null) newest(it) else 0L }.thenByDescending { it.faces.size })

    /** Unnamed people in fewer photos than this sit under "Others", not among the family (7A-2). */
    const val OTHERS_BELOW = 4
}

class PeopleSnapshot(
    val people: List<Person> = emptyList(),
    val spots: Map<Int, FaceSpot> = emptyMap(),
    val scanned: Int = 0,
    val questions: List<SamePersonQuestion> = emptyList(),
    /** True while the one-time "look again" (7A-2) hasn't finished: older faces are still in use. */
    val lookingAgain: Boolean = false,
)

class ScanProgress(val done: Int, val total: Int, val running: Boolean)

/** What the phone does for People: finds faces (on the phone only), keeps them, and scans while charging. */
interface PeoplePlatform {
    val supported: Boolean get() = false
    fun isEnabled(): Boolean = false

    /** Turning on starts scanning while charging; turning off deletes every face and name from this phone. */
    suspend fun setEnabled(on: Boolean) = Unit

    suspend fun load(): PeopleSnapshot = PeopleSnapshot()

    /** Changes the people (names, merges, hides, removals) and saves them. */
    suspend fun edit(change: (List<Person>) -> List<Person>) = Unit

    /** Scans now, while the app is open (not only while charging). */
    fun scanNow() = Unit

    fun stopScan() = Unit

    val progress: StateFlow<ScanProgress?> get() = MutableStateFlow(null)

    /** A small square picture of one face, cut from its photo. */
    suspend fun faceThumb(spot: FaceSpot): ByteArray? = null

    /** The answer to a "Same person?" question: Yes joins them, No is remembered so it is never asked again. */
    suspend fun answer(question: SamePersonQuestion, same: Boolean) = Unit

    /** A notification when new "Same person?" questions are waiting (at most one a day). On by default. */
    fun askByNotification(): Boolean = true
    fun setAskByNotification(on: Boolean) = Unit
}

object NoPeople : PeoplePlatform

@Stable
class PeopleState(val platform: PeoplePlatform = NoPeople) {
    var enabled by mutableStateOf(platform.isEnabled())
    var people by mutableStateOf<List<Person>>(emptyList())
    var spots by mutableStateOf<Map<Int, FaceSpot>>(emptyMap())
    var scanned by mutableStateOf(0)
    var questions by mutableStateOf<List<SamePersonQuestion>>(emptyList())
    var lookingAgain by mutableStateOf(false)

    /** Questions put off with "Not sure" (until the app is opened again). */
    var skipped by mutableStateOf<Set<SamePersonQuestion>>(emptySet())

    /** People picked on the People page (merge, hide); back clears them first. */
    var selected by mutableStateOf<Set<Int>>(emptySet())

    suspend fun refresh() {
        val s = platform.load()
        people = s.people
        spots = s.spots
        scanned = s.scanned
        questions = s.questions
        lookingAgain = s.lookingAgain
    }

    /** The next question to show, skipping ones put off and ones whose people have gone. */
    fun nextQuestion(): SamePersonQuestion? = questions.firstOrNull { q -> q !in skipped && find(q.a) != null && find(q.b) != null }

    suspend fun answer(q: SamePersonQuestion, same: Boolean) {
        platform.answer(q, same)
        refresh()
    }

    fun find(id: Int): Person? = people.firstOrNull { it.id == id }

    fun photosOf(person: Person): Set<Long> = person.faces.mapNotNull { spots[it]?.photoId }.toSet()

    /**
     * The face to show for a person (5 Nov): the biggest clear front face; when there is none, the biggest face.
     */
    fun coverOf(person: Person): FaceSpot? {
        val all = person.faces.mapNotNull { spots[it] }
        val front = all.filter { it.frontal >= 0.7f && it.sharp >= 120f }
        return (front.ifEmpty { all }).maxByOrNull { it.box[2] * it.box[3] }
    }

    /** Photos of every named person whose name contains [query] (Search, 7A). */
    fun photosNamed(query: String): Set<Long> {
        val q = query.trim()
        if (q.length < 2) return emptySet()
        return people.filter { !it.hidden && it.name?.contains(q, ignoreCase = true) == true }.flatMap { photosOf(it) }.toSet()
    }

    suspend fun change(edit: (List<Person>) -> List<Person>) {
        platform.edit(edit)
        refresh()
    }

    /** Names a person. A name already used by someone else merges the two (as in Samsung Gallery). */
    suspend fun rename(id: Int, name: String) = change { list ->
        val clean = name.trim().take(40).ifEmpty { null }
        val same = clean?.let { n -> list.filter { it.id != id && it.name.equals(n, ignoreCase = true) } }.orEmpty()
        val me = list.firstOrNull { it.id == id } ?: return@change list
        if (same.isNotEmpty()) {
            val merged = FaceGrouping.merge(me.copy(name = clean), same)
            list.filter { it.id != id && it !in same } + merged
        } else {
            list.map { if (it.id == id) it.copy(name = clean) else it }
        }
    }

    suspend fun merge(ids: List<Int>) = change { list ->
        val chosen = ids.mapNotNull { id -> list.firstOrNull { it.id == id } }
        if (chosen.size < 2) return@change list
        val into = chosen.firstOrNull { it.name != null } ?: chosen.first()
        list.filter { it !in chosen } + FaceGrouping.merge(into, chosen - into)
    }

    suspend fun setHidden(ids: Set<Int>, hidden: Boolean) = change { list -> list.map { if (it.id in ids) it.copy(hidden = hidden) else it } }

    /** "Not this person": the faces of [person] in these photos leave it for good. */
    suspend fun removePhotos(personId: Int, photoIds: Set<Long>) = change { list ->
        list.map { p ->
            if (p.id != personId) p else FaceGrouping.remove(p, p.faces.filter { spots[it]?.photoId in photoIds }.toSet())
        }.filter { it.faces.isNotEmpty() || it.name != null }
    }
}
