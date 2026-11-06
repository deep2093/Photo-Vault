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
class Face(
    val id: Int,
    val photoId: Long,
    val box: FloatArray,
    val vector: FloatArray,
    val frontal: Float = 1f,
    val sharp: Float = SHARP_DEFAULT,
    /** How visible the eyes are (eye area brightness against the cheeks); low means sunglasses (5 Nov). */
    val eyes: Float = 1f,
    /**
     * The face model's own confidence: the length of its measurement before it is scaled to 1 (0 = not known, faces
     * found before 5 Nov). Weak measurements of different people sit close together, which made "mystery groups".
     */
    val strength: Float = 0f,
    /** When the photo was taken (0 = not known, faces found before 6 Nov): for bursts and same-day clothes. */
    val takenAt: Long = 0L,
    /**
     * The colours of what the person wears just under the face (6 Nov): a small colour count, null when the photo
     * shows too little of it. Only used to compare photos taken the same day.
     */
    val clothes: FloatArray? = null,
) {
    /** Set by [FaceGrouping.markWeak] from the whole library: this face's measurement is weak. */
    var weak: Boolean = false

    val turned: Boolean get() = frontal < FaceGrouping.TURNED
    val eyesHidden: Boolean get() = eyes < FaceGrouping.EYES_HIDDEN

    /** A face good enough to compare new faces with: facing the camera, sharp, eyes showing. */
    val good: Boolean get() = !turned && !eyesHidden && !weak && sharp >= FaceGrouping.GOOD_SHARP

    companion object {
        const val SHARP_DEFAULT = 1000f
    }
}

/** Where a face is in its photo ([box] = left, top, width, height, each 0 to 1), for its small round picture. */
class FaceSpot(val id: Int, val photoId: Long, val box: FloatArray, val frontal: Float = 1f, val sharp: Float = Face.SHARP_DEFAULT, val eyes: Float = 1f, val strength: Float = 0f)

/** Two groups that may be one person: People asks (7A-2). */
data class SamePersonQuestion(val a: Int, val b: Int, val score: Float)

/** A group of faces of one person. [notFaces] were taken out by hand and never join this person again. */
data class Person(
    val id: Int,
    val faces: Set<Int>,
    val name: String? = null,
    val hidden: Boolean = false,
    val notFaces: Set<Int> = emptySet(),
    /**
     * Changed by hand (merged, a "Same person?" answer, photos moved or taken out; 6 Nov): kept as it is when the
     * groups are sorted again from scratch after a scan, like named and hidden people.
     */
    val kept: Boolean = false,
    /** This is the phone's owner ("This is me", 6 Nov): shown first, found by searching "me". Only one person. */
    val me: Boolean = false,
    /**
     * Blocked (6 Nov): never shown in People, search or questions. The person's faces stay together so new photos of
     * them keep joining them, which is what keeps them out of sight.
     */
    val blocked: Boolean = false,
    /** The face picked by hand as the round picture (6 Nov); null: the best one is picked automatically. */
    val cover: Int? = null,
)

/**
 * Faces become people (7A, 5 Nov). A face joins the person it looks most like when it is close enough (cosine of the
 * two measurements at least [SAME]); otherwise it starts a new person. Faces already in a person stay there, so
 * names, merges and removals made by hand are never undone. A person is compared by the average of its faces and by a
 * few of its own faces, so a merged person (with and without sunglasses, say) matches both looks afterwards.
 */
/**
 * The likeness lines of one face model (6 Nov). Each model scores on its own scale, so each gets its own lines:
 * SFace (the standard model, 128 numbers) and AuraFace (the test model, 512 numbers, ArcFace-style scores that run
 * lower for the same person).
 */
class FaceLines(val same: Float, val sameHidden: Float, val ask: Float, val joinGroups: Float, val near: Float) {
    companion object {
        val SFACE = FaceLines(same = 0.42f, sameHidden = 0.50f, ask = 0.32f, joinGroups = 0.45f, near = 0.30f)

        /** First tuning for AuraFace (ArcFace scale: same person usually 0.4+, different people under 0.2). */
        val AURA = FaceLines(same = 0.36f, sameHidden = 0.44f, ask = 0.26f, joinGroups = 0.40f, near = 0.24f)
    }
}

object FaceGrouping {
    /** Switches every line to [lines] (the phone's chosen face model). */
    fun use(lines: FaceLines) {
        SAME = lines.same
        SAME_HIDDEN = lines.sameHidden
        ASK = lines.ask
        JOIN_GROUPS = lines.joinGroups
        NEAR = lines.near
    }

    /** SFace's own "same person" line is 0.363; a little stricter, because merging is easier than splitting. */
    var SAME = 0.42f
        private set

    /** Eyes hidden (dark glasses): such a face needs a closer match to join by itself (5 Nov). */
    const val EYES_HIDDEN = 0.45f
    var SAME_HIDDEN = 0.50f
        private set

    /** Sharp enough to be one of the faces new ones are compared with (raised from 60, 5 Nov). */
    const val GOOD_SHARP = 100f

    /** A measurement weaker than this share of the library's middle one is weak (5 Nov, first guess). */
    const val WEAK_SHARE = 0.75f

    /**
     * Marks weak faces: the model's confidence well under the middle of this library's faces. Done on the whole library
     * so it fits the photos (light, cameras). Needs a few dozen faces with a known confidence first.
     */
    fun markWeak(faces: List<Face>) {
        val known = faces.filter { it.strength > 0f }
        if (known.size < 30) {
            faces.forEach { it.weak = false }
            return
        }
        val sorted = known.map { it.strength }.sorted()
        val line = sorted[sorted.size / 2] * WEAK_SHARE
        faces.forEach { it.weak = it.strength > 0f && it.strength < line }
    }

    /**
     * Each group checks its own faces after a scan (5 Nov). In an unnamed group a face must look like at least two of
     * the group's other faces (one, in a group of two); in a named group it must look at least a little like one of them.
     * Faces that don't are taken out for good ([Person.notFaces]) and sorted again, so a mixed group can't keep growing.
     */
    private fun centre(faces: List<Face>): FloatArray {
        val good = faces.filter { it.good }.ifEmpty { faces }
        return centroid(good.map { it.vector })
    }

    fun selfCheck(faces: List<Face>, people: List<Person>): List<Person> {
        val byId = faces.associateBy { it.id }
        return people.map { p ->
            val mine = p.faces.mapNotNull { byId[it] }
            if (mine.size < 2) return@map p
            val out = mine.filter { f ->
                val others = mine.filter { it.id != f.id }
                if (burstPartner(f, others)) {
                    // Joined through a burst (6 Nov): the photo next to it says it's them.
                    false
                } else if (p.name != null) {
                    others.none { cosine(f.vector, it.vector) >= ASK }
                } else if (others.size >= 6 && cosine(f.vector, centre(others)) >= ASK) {
                    // A big group keeps a face that fits its average (an unusual look of the same person, 5 Nov).
                    false
                } else {
                    val need = minOf(2, others.size)
                    others.count { cosine(f.vector, it.vector) >= SAME } < need
                }
            }.map { it.id }.toSet()
            if (out.isEmpty() || out.size == mine.size) p else remove(p, out)
        }
    }

    /** A face must match this many of a person's faces (or the person's average), not just one (5 Nov). */
    const val AGREE = 3

    /** Two people both matching a face within this of each other: a close call, decided by the learnt measure. */
    const val CLOSE_CALL = 0.08f

    /**
     * A measure learnt from the people you named (5 Nov, for sisters and look-alikes): it works out how much each
     * person's own faces vary in each of the 128 numbers and scales those numbers down, so what differs BETWEEN
     * people counts more (within-class whitening). Needs at least two named people with 8 good faces each. Used only
     * to settle close calls, so the usual lines keep their meaning.
     */
    fun whitener(faces: List<Face>, people: List<Person>): Whitener? {
        val byId = faces.associateBy { it.id }
        val groups = people.filter { it.name != null }
            .map { p -> p.faces.mapNotNull { byId[it] }.filter { it.good }.map { it.vector } }
            .filter { it.size >= 8 }
        if (groups.size < 2) return null
        val d = groups.first().first().size
        val cov = Array(d) { DoubleArray(d) }
        var n = 0
        for (g in groups) {
            val mean = DoubleArray(d)
            g.forEach { v -> for (i in 0 until d) mean[i] += v[i].toDouble() }
            for (i in 0 until d) mean[i] /= g.size
            for (v in g) {
                for (i in 0 until d) {
                    val di = v[i] - mean[i]
                    for (j in 0..i) cov[i][j] += di * (v[j] - mean[j])
                }
                n++
            }
        }
        var trace = 0.0
        for (i in 0 until d) { for (j in 0..i) cov[i][j] /= n; trace += cov[i][i] }
        val ridge = trace / d
        // Half learnt, half plain: safe even with few named photos.
        for (i in 0 until d) { for (j in 0..i) cov[i][j] *= 0.5; cov[i][i] += 0.5 * ridge }
        return Whitener.cholesky(cov)
    }

    /** Below this a face is turned too far to start a person of its own (7A-2): it can only join one. */
    const val TURNED = 0.35f

    /**
     * Groups this alike, but not enough to join by themselves, become a "Same person?" question (7A-2). Raised from 0.24
     * on 5 Nov: in one family's photos completely different people (a man and a woman, a child and an adult) reached it.
     */
    var ASK = 0.32f
        private set

    /** Faces of one group must match faces of the other this many times, face to face, before anyone is asked. */
    const val EVIDENCE = 3

    /** ...and this many before two groups join by themselves. */
    const val EVIDENCE_JOIN = 3

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

    private class Group(
        val person: Person,
        val sum: FloatArray,
        /** Sum of the good faces only: the person's average for matching. */
        val goodSum: FloatArray,
        var goodCount: Int,
        val examples: MutableList<FloatArray>,
        val faces: MutableSet<Int>,
        val photos: MutableSet<Long>,
        /** Faces taken out of this person by hand or by its check: "not them" examples (5 Nov, the sisters). */
        val notThem: List<FloatArray> = emptyList(),
    ) {
        /** A face taken out of this person looks a lot like them (a sister, say): new faces need a closer match. */
        val lookalike: Boolean by lazy {
            val centre = if (goodCount > 0) goodSum else sum
            notThem.any { cosine(it, centre) >= SAME_HIDDEN }
        }

        private var white: FloatArray? = null

        fun whiteCentre(w: Whitener): FloatArray = white ?: w.apply(if (goodCount > 0) goodSum else sum).also { white = it }

        /** How much [v] looks like a face that was taken out of this person. */
        fun notScore(v: FloatArray): Float {
            var best = -1f
            for (n in notThem) { val c = cosine(v, n); if (c > best) best = c }
            return best
        }

        /** How well [v] matches the person's average face only (6 Nov: for questions, one odd photo can't win). */
        fun centreScore(v: FloatArray): Float = cosine(v, if (goodCount > 0) goodSum else sum)

        /** How well [v] matches: the average of good faces, or the [AGREE]th best of its example faces (5 Nov: one odd example used to be enough). */
        fun score(v: FloatArray): Float {
            val centre = cosine(v, if (goodCount > 0) goodSum else sum)
            if (examples.isEmpty()) return centre
            val k = minOf(AGREE, examples.size)
            val top = FloatArray(k) { -2f }
            for (e in examples) {
                val c = cosine(v, e)
                var i = k - 1
                if (c <= top[i]) continue
                while (i > 0 && top[i - 1] < c) { top[i] = top[i - 1]; i-- }
                top[i] = c
            }
            return maxOf(centre, top[k - 1])
        }

        fun add(face: Face) {
            faces += face.id
            photos += face.photoId
            for (i in sum.indices) sum[i] += face.vector[i]
            if (face.good) {
                for (i in goodSum.indices) goodSum[i] += face.vector[i]
                goodCount++
                // Keeps a spread of looks, not only the first ones (5 Nov).
                if (examples.size < EXAMPLES) examples += face.vector else examples[goodCount % EXAMPLES] = face.vector
            }
        }
    }

    private fun buildGroups(people: List<Person>, byId: Map<Int, Face>, size: Int): MutableList<Group> = people.map { p ->
        val mine = p.faces.mapNotNull { byId[it] }
        val good = mine.filter { it.good }
        val sum = FloatArray(size)
        mine.forEach { f -> for (i in sum.indices) sum[i] += f.vector[i] }
        val goodSum = FloatArray(size)
        good.forEach { f -> for (i in goodSum.indices) goodSum[i] += f.vector[i] }
        // Only good faces are compared with (blurred, turned or dark-glasses faces don't pull others in);
        // a person with no good face at all uses what it has.
        val examples = spread((good.ifEmpty { mine }).map { it.vector }, EXAMPLES).toMutableList()
        val notThem = spread(p.notFaces.sorted().mapNotNull { byId[it]?.vector }, EXAMPLES)
        Group(p, sum, goodSum, good.size, examples, p.faces.toMutableSet(), mine.map { it.photoId }.toMutableSet(), notThem)
    }.toMutableList()

    /** Puts every face that isn't in a person yet into the right one, or a new one. Returns the people and the next free id. */
    fun group(faces: List<Face>, people: List<Person>, nextId: Int, startNew: Boolean = true): Pair<List<Person>, Int> {
        markWeak(faces)
        val byId = faces.associateBy { it.id }
        val learnt = whitener(faces, people)
        val check = personCheck(faces, people)
        val placed = people.flatMap { it.faces }.toHashSet()
        val size = faces.firstOrNull()?.vector?.size ?: 0
        val groups = buildGroups(people, byId, size)
        var next = nextId
        val context = Context(faces, groups)
        // Front faces first, so a side view can join a person made in the same scan.
        for (face in faces.sortedWith(compareBy<Face> { it.turned }.thenBy { it.id })) {
            if (face.id in placed) continue
            var best: Group? = null
            val needed = if (face.eyesHidden || face.weak) SAME_HIDDEN else SAME
            var bestScore = needed - 1e-6f
            var runnerUp: Group? = null
            var runnerScore = -2f
            val around = context.around(face)
            // Named people this face is at least a little like, for their own learnt check (6 Nov).
            val namedNear = mutableListOf<Pair<Group, Float>>()
            for (g in groups) {
                // Two faces in one photo are never the same person (7A-2).
                if (face.id in g.person.notFaces || g.faces.isEmpty() || face.photoId in g.photos) continue
                val raw = g.score(face.vector)
                // Looks more like someone taken out of this person than like the person: not them.
                // A tie still goes to the person (the face taken out may be a bad photo of them).
                if (g.notThem.isNotEmpty() && g.notScore(face.vector) > raw + 0.01f) continue
                // The photos around this one (6 Nov): a burst, the same clothes that day, who they are usually with.
                val hint = if (raw >= ASK - CLOTHES_OFF) context.hint(face, g, around) else Hint.NONE
                // Someone with a known look-alike (a sister taken out before) needs a closer match (5 Nov),
                // unless the very same spot in a burst photo was already them.
                if (g.lookalike && raw < SAME_HIDDEN && !(hint.burst && raw >= ASK)) continue
                val score = when {
                    hint.burst && raw >= ASK -> maxOf(raw, needed)
                    raw >= ASK -> raw + hint.bonus
                    else -> raw + minOf(0f, hint.bonus)
                }
                if (check != null && g.person.id in check.people && score >= ASK) namedNear += g to score
                // Strictly better only: on a tie the older person (named, or merged by hand) keeps the face.
                if (score > bestScore) {
                    if (best != null) { runnerUp = best; runnerScore = bestScore }
                    bestScore = score
                    best = g
                } else if (score > runnerScore && score >= needed) {
                    runnerUp = g
                    runnerScore = score
                }
            }
            // Each named person's own learnt check (6 Nov): between named people this face is like, the one whose check
            // is surest wins, if it is nearly as alike; and a named person whose check clearly says "not them" is skipped.
            var decided = false
            val chosen = best
            if (check != null && chosen != null && chosen.person.id in check.people) {
                val mine = check.chance(chosen.person.id, face.vector)
                val other = namedNear.filter { it.first !== chosen && it.second >= needed - CHECK_SLACK }
                    .maxByOrNull { check.chance(it.first.person.id, face.vector) }
                val theirs = other?.let { check.chance(it.first.person.id, face.vector) } ?: 0f
                if (other != null && theirs >= CHECK_SURE && mine < CHECK_DOUBT) {
                    best = other.first
                    decided = true
                } else if (mine < CHECK_NOT) {
                    best = null
                    decided = true
                }
            }
            // Two people both match closely (sisters): the family's own learnt measure decides (5 Nov).
            val second = runnerUp
            if (!decided && learnt != null && best != null && second != null && bestScore - runnerScore < CLOSE_CALL) {
                val w = learnt.apply(face.vector)
                if (cosine(w, second.whiteCentre(learnt)) > cosine(w, best.whiteCentre(learnt))) best = second
            }
            // A side view that matches nobody waits: side views made most of the extra groups of one person.
            // Weak measurements (5 Nov) wait too: they only join someone they clearly match.
            // Side, dark-glasses and weak faces never start someone (6 Nov): if they match no one well, they are asked about.
            if (best == null && (face.turned || face.weak || face.eyesHidden || !startNew)) continue
            val target = best ?: Group(Person(next++, emptySet()), FloatArray(size), FloatArray(size), 0, mutableListOf(), mutableSetOf(), mutableSetOf()).also { groups += it }
            target.add(face)
            context.placed(face, target)
            placed += face.id
        }
        return groups.filter { it.faces.isNotEmpty() }.map { it.person.copy(faces = it.faces.toSet()) } to next
    }

    /** Photos taken this close together, with a face in the same spot, are a burst: the same person (6 Nov). */
    const val BURST_MS = 3_000L

    /** How much two face boxes must overlap (shared area over joint area) to be "the same spot". */
    const val BURST_OVERLAP = 0.4f

    /** Clothes are only compared between photos taken this close together (the same day out). */
    const val DAY_MS = 12 * 3_600_000L

    /** ...and count as "a different outfit at the same event" only this close together. */
    const val EVENT_MS = 3 * 3_600_000L

    /** Colour counts this alike are the same clothes; under [CLOTHES_DIFF] clearly different ones (first guesses). */
    const val CLOTHES_SAME = 0.85f
    const val CLOTHES_DIFF = 0.5f

    /** Same clothes that day: added to the likeness. Never enough alone: only for faces already at the "ask" line. */
    const val CLOTHES_ON = 0.04f

    /** A clearly different outfit at the same event: taken off the likeness (helps tell sisters apart at one party). */
    const val CLOTHES_OFF = 0.03f

    private class Hint(val burst: Boolean, val bonus: Float) {
        companion object { val NONE = Hint(false, 0f) }
    }

    fun overlap(a: FloatArray, b: FloatArray): Float {
        if (a.size < 4 || b.size < 4) return 0f
        val w = minOf(a[0] + a[2], b[0] + b[2]) - maxOf(a[0], b[0])
        val h = minOf(a[1] + a[3], b[1] + b[3]) - maxOf(a[1], b[1])
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        val union = a[2] * a[3] + b[2] * b[3] - inter
        return if (union <= 0f) 0f else inter / union
    }

    /** What the photos around a face say (6 Nov): bursts and clothes on the same day. */
    private class Context(faces: List<Face>, groups: List<Group>) {
        private val timed = faces.filter { it.takenAt > 0L }.sortedBy { it.takenAt }
        private val times = LongArray(timed.size) { timed[it].takenAt }
        private val home = HashMap<Int, Group>().apply { groups.forEach { g -> g.faces.forEach { put(it, g) } } }

        fun placed(face: Face, g: Group) { home[face.id] = g }

        /** Faces in other photos taken within a day of this one (the closest few hundred). */
        fun around(face: Face): List<Face> {
            if (face.takenAt <= 0L || timed.isEmpty()) return emptyList()
            fun first(t: Long): Int { var lo = 0; var hi = times.size; while (lo < hi) { val m = (lo + hi) ushr 1; if (times[m] < t) lo = m + 1 else hi = m }; return lo }
            var from = first(face.takenAt - DAY_MS)
            var to = first(face.takenAt + DAY_MS + 1)
            if (to - from > 400) { from = first(face.takenAt - EVENT_MS); to = first(face.takenAt + EVENT_MS + 1) }
            if (to - from > 400) { val mid = first(face.takenAt); from = maxOf(from, mid - 200); to = minOf(to, mid + 200) }
            return (from until to).map { timed[it] }.filter { it.photoId != face.photoId }
        }

        fun hint(face: Face, g: Group, around: List<Face>): Hint {
            var burst = false
            var sameClothes = false
            var sawEvent = false
            var alikeAtEvent = false
            for (n in around) {
                if (home[n.id] !== g) continue
                val dt = kotlin.math.abs(n.takenAt - face.takenAt)
                if (dt <= BURST_MS && overlap(n.box, face.box) >= BURST_OVERLAP) burst = true
                val a = face.clothes
                val b = n.clothes
                if (a != null && b != null && a.size == b.size) {
                    val c = cosine(a, b)
                    if (c >= CLOTHES_SAME) sameClothes = true
                    if (dt <= EVENT_MS) { sawEvent = true; if (c >= CLOTHES_DIFF) alikeAtEvent = true }
                }
            }
            var bonus = 0f
            if (sameClothes) bonus += CLOTHES_ON else if (sawEvent && !alikeAtEvent) bonus -= CLOTHES_OFF
            return Hint(burst, bonus)
        }
    }

    /** True when [f] has a burst partner among [others]: the same spot within [BURST_MS]. */
    private fun burstPartner(f: Face, others: List<Face>): Boolean = f.takenAt > 0L && others.any {
        it.takenAt > 0L && kotlin.math.abs(it.takenAt - f.takenAt) <= BURST_MS && it.photoId != f.photoId && overlap(it.box, f.box) >= BURST_OVERLAP
    }

    // ---- A learnt check for each named person (6 Nov) ----

    /** Named people need this many clear faces before they get their own check. */
    const val CHECK_MIN = 10
    private const val CHECK_SAMPLES = 80
    private const val CHECK_ROUNDS = 100

    /** A rival named person wins when their check is at least this sure, and the first one's under [CHECK_DOUBT]. */
    const val CHECK_SURE = 0.6f
    const val CHECK_DOUBT = 0.4f

    /** A named person whose check is under this for a face doesn't get it. */
    const val CHECK_NOT = 0.1f

    /** The rival only needs to be this close to the usual line. */
    const val CHECK_SLACK = 0.06f

    private var checkKey: String? = null
    private var checkCache: PersonCheck? = null

    /**
     * Trains a small check for every named person with [CHECK_MIN] clear faces, when there are at least two: it learns
     * which of the measurements tell this person apart from the other named people (logistic regression on the
     * measurements, scaled per number). Kept until the named people change by 20 faces or more.
     */
    fun personCheck(faces: List<Face>, people: List<Person>): PersonCheck? {
        val byId = faces.associateBy { it.id }
        val named = people.filter { it.name != null }
            .map { p -> p.id to spread(p.faces.sorted().mapNotNull { byId[it] }.filter { it.good }.map { it.vector }, CHECK_SAMPLES) }
            .filter { it.second.size >= CHECK_MIN }
        if (named.size < 2) return null
        val key = named.joinToString(",") { "${it.first}:${it.second.size / 20}:${it.second.first().size}" }
        if (key == checkKey) return checkCache
        val d = named.first().second.first().size
        val all = named.flatMap { it.second }
        val mean = FloatArray(d)
        all.forEach { v -> for (i in 0 until d) mean[i] += v[i] / all.size }
        val scale = FloatArray(d)
        all.forEach { v -> for (i in 0 until d) { val x = v[i] - mean[i]; scale[i] += x * x / all.size } }
        // A floor from the average spread, so a number that hardly ever changes can't swing the check.
        val floor = scale.average().toFloat() * 0.1f + 1e-9f
        for (i in 0 until d) scale[i] = 1f / sqrt(scale[i] + floor)
        val xs = all.map { v -> FloatArray(d) { (v[it] - mean[it]) * scale[it] } }
        val owner = named.flatMap { (id, list) -> List(list.size) { id } }
        val weights = HashMap<Int, FloatArray>()
        val bias = HashMap<Int, Float>()
        for ((id, list) in named) {
            val pos = list.size.toFloat()
            val neg = (all.size - list.size).toFloat()
            val w = FloatArray(d)
            var b = 0f
            // A step size that stays steady for scaled measurements of this length.
            val rate = 20f / d
            repeat(CHECK_ROUNDS) {
                val gw = FloatArray(d)
                var gb = 0f
                for (k in xs.indices) {
                    val x = xs[k]
                    var z = b
                    for (i in 0 until d) z += w[i] * x[i]
                    val p = 1f / (1f + kotlin.math.exp(-z))
                    val y = if (owner[k] == id) 1f else 0f
                    // Both sides count the same, however many faces each has.
                    val weight = if (y == 1f) 0.5f / pos else 0.5f / neg
                    val e = (p - y) * weight
                    for (i in 0 until d) gw[i] += e * x[i]
                    gb += e
                }
                for (i in 0 until d) w[i] -= rate * (gw[i] + 0.001f * w[i])
                b -= 2f * gb
            }
            weights[id] = w
            bias[id] = b
        }
        return PersonCheck(mean, scale, weights, bias).also { checkKey = key; checkCache = it }
    }

    // ---- Sorting every face again, all together (6 Nov) ----

    /** How many closest faces each face looks at when the groups are sorted again. */
    private const val NEIGHBOURS = 8

    /** More free faces than this and the sort falls back to one-by-one (too slow on a phone). */
    private const val RESORT_MAX = 8000

    /**
     * After a scan, the groups are made again from scratch so the order photos were looked at no longer matters:
     * faces first join the people in [keep] (named, hidden or changed by hand); the rest are built up by
     * [closeTogether]; what is left is placed one by one as before. [apart] are pairs of faces known not to be one
     * person (faces an earlier check took out of a group, 6 Nov): they never end up together.
     */
    fun resort(faces: List<Face>, keep: List<Person>, nextId: Int, apart: Set<Pair<Int, Int>> = emptySet()): Pair<List<Person>, Int> {
        markWeak(faces)
        val (withKept, after) = group(faces, keep, nextId, startNew = false)
        val placed = withKept.flatMap { it.faces }.toHashSet()
        // Side, dark-glasses and weak faces don't build groups of their own (6 Nov: side views of one person had
        // teamed up into a second group); they join someone later or are asked about.
        val free = faces.filter { it.id !in placed && !it.turned && !it.weak && !it.eyesHidden }
        var next = after
        val against = HashMap<Int, MutableSet<Int>>()
        apart.forEach { (x, y) -> against.getOrPut(x) { HashSet() } += y; against.getOrPut(y) { HashSet() } += x }
        val clusters = closeTogether(free, apart).map { members ->
            // Faces known not to be this person can't join it later either.
            Person(next++, members, notFaces = members.flatMap { against[it].orEmpty() }.toSet() - members)
        }
        return group(faces, withKept + clusters, next)
    }

    /**
     * Builds groups bottom-up (6 Nov, second version): every face starts alone; two groups join only when their faces
     * match on average at the same-person line AND several of their faces are among each other's closest matches (one
     * from each side, at least [EVIDENCE_JOIN] or the smaller group's size). So one look-alike photo can't chain two
     * people together, as the first version (one link was enough) did. Never two faces of one photo, never a pair in
     * [apart]. Returns groups of 2 or more faces.
     */
    fun closeTogether(faces: List<Face>, apart: Set<Pair<Int, Int>> = emptySet()): List<Set<Int>> {
        val n = faces.size
        if (n < 2 || n > RESORT_MAX) return emptyList()
        val d = faces.first().vector.size
        val top = Array(n) { IntArray(NEIGHBOURS) { -1 } }
        val topScore = Array(n) { FloatArray(NEIGHBOURS) { -2f } }
        fun offer(i: Int, j: Int, s: Float) {
            val ts = topScore[i]
            if (s <= ts[NEIGHBOURS - 1]) return
            var k = NEIGHBOURS - 1
            while (k > 0 && ts[k - 1] < s) { ts[k] = ts[k - 1]; top[i][k] = top[i][k - 1]; k-- }
            ts[k] = s
            top[i][k] = j
        }
        for (i in 0 until n) {
            val a = faces[i]
            for (j in i + 1 until n) {
                val b = faces[j]
                if (a.photoId == b.photoId || a.vector.size != d || b.vector.size != d) continue
                var dot = 0f
                val va = a.vector
                val vb = b.vector
                for (k in 0 until d) dot += va[k] * vb[k]
                val line = if (a.eyesHidden || b.eyesHidden || a.weak || b.weak) SAME_HIDDEN else SAME
                if (dot < line) continue
                offer(i, j, dot)
                offer(j, i, dot)
            }
        }
        // Links: pairs that are among each other's closest matches, both ways.
        val links = ArrayList<Pair<Int, Int>>()
        for (i in 0 until n) for (j in top[i]) if (j > i && i in top[j]) links += i to j
        val index = faces.withIndex().associate { it.value.id to it.index }
        val apartIdx = apart.mapNotNull { (x, y) -> val a = index[x]; val b = index[y]; if (a != null && b != null) a to b else null }
        // Each group: its members, their sum (the average match of two groups is sumA . sumB / (|A| |B|)), its photos.
        val owner = IntArray(n) { it }
        val members = HashMap<Int, MutableList<Int>>().apply { for (i in 0 until n) put(i, mutableListOf(i)) }
        val sums = HashMap<Int, FloatArray>().apply { for (i in 0 until n) put(i, faces[i].vector.copyOf()) }
        val photos = HashMap<Int, MutableSet<Long>>().apply { for (i in 0 until n) put(i, mutableSetOf(faces[i].photoId)) }
        for (round in 0 until 40) {
            // Count the links between each pair of groups.
            val between = HashMap<Long, Int>()
            for ((i, j) in links) {
                val a = owner[i]
                val b = owner[j]
                if (a == b) continue
                val key = minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()
                between[key] = (between[key] ?: 0) + 1
            }
            val blocked = HashSet<Long>()
            for ((i, j) in apartIdx) {
                val a = owner[i]
                val b = owner[j]
                if (a != b) blocked += minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()
            }
            val candidates = between.mapNotNull { (key, count) ->
                if (key in blocked) return@mapNotNull null
                val a = (key shr 32).toInt()
                val b = (key and 0xffffffffL).toInt()
                val ma = members.getValue(a)
                val mb = members.getValue(b)
                val need = minOf(EVIDENCE_JOIN, ma.size, mb.size)
                if (count < need) return@mapNotNull null
                if (photos.getValue(a).any { it in photos.getValue(b) }) return@mapNotNull null
                val sa = sums.getValue(a)
                val sb = sums.getValue(b)
                var dot = 0f
                for (k in 0 until d) dot += sa[k] * sb[k]
                val average = dot / (ma.size * mb.size)
                if (average < SAME) null else Triple(a, b, average)
            }
            if (candidates.isEmpty()) break
            val used = HashSet<Int>()
            for ((a, b, _) in candidates.sortedByDescending { it.third }) {
                if (a in used || b in used) continue
                used += a
                used += b
                val keep = if (members.getValue(a).size >= members.getValue(b).size) a else b
                val gone = if (keep == a) b else a
                members.getValue(gone).forEach { owner[it] = keep }
                members.getValue(keep) += members.getValue(gone)
                val sk = sums.getValue(keep)
                val sg = sums.getValue(gone)
                for (k in 0 until d) sk[k] += sg[k]
                photos.getValue(keep) += photos.getValue(gone)
                members.remove(gone)
                sums.remove(gone)
                photos.remove(gone)
            }
        }
        return members.values.filter { it.size >= 2 }.map { list -> list.map { faces[it].id }.toSet() }
    }

    // ---- Faces the app isn't sure about (6 Nov) ----

    /** A group whose photos all fall within this span is a one-day group (an event: rangoli, a party). */
    const val ONE_DAY_MS = 24 * 3_600_000L

    /** One-day groups this small or smaller are asked about when they look like someone else. */
    const val ONE_DAY_MAX_PHOTOS = 20

    /** At most this many guesses are asked before the people grid opens. */
    const val GUESSES = 2

    /** Faces in a set shown in a question. */
    const val SET_SAMPLES = 4

    /** A guess must be at least this much closer than the next person, or it isn't asked (6 Nov: random guesses). */
    const val GUESS_MARGIN = 0.05f

    /** A one-day group is only taken apart when its closest person is nearly a sure match (this under the line). */
    const val ONE_DAY_SLACK = 0.03f

    /**
     * Finds the faces to ask about instead of grouping on a guess (6 Nov): side, dark-glasses and weak faces that
     * joined no one, and small unnamed groups whose photos are all from one day (rangoli, paint, stage light) that look
     * like someone else; those groups are taken apart. Faces are asked about in sets (a one-day group is one set;
     * other faces with the same closest person on the same day share a set). Each set lists every person from the
     * closest to the least; [UnsureSet.guesses] says how many of the first two are close enough (the ask line) to ask
     * "Is this ...?". Sets with no close-enough person wait quietly. [parked] faces ("None of these") are left out.
     * Returns the people (one-day groups taken apart) and the sets.
     */
    fun unsure(faces: List<Face>, all: List<Person>, parked: Set<Int>, floor: Float = ASK): Pair<List<Person>, List<UnsureSet>> {
        val line = maxOf(ASK, floor)
        // Faces put aside ("None of these") stay out of groups the app made itself.
        val people = all.map { p ->
            if (p.name == null && !p.kept && !p.me && !p.hidden && !p.blocked && p.faces.any { it in parked }) p.copy(faces = p.faces - parked) else p
        }.filter { it.faces.isNotEmpty() || it.name != null }
        if (faces.isEmpty()) return people to emptyList()
        markWeak(faces)
        val byId = faces.associateBy { it.id }
        val size = faces.first().vector.size
        val visible = people.filter { !it.hidden && !it.blocked }
        val groups = buildGroups(visible, byId, size).filter { g -> g.person.name != null || g.photos.size >= 2 || g.person.me }
        fun ranked(set: List<Face>, not: Int? = null): List<Pair<Int, Float>> {
            val sample = spread(set, 10)
            val notFor = set.map { it.id }.toSet()
            val setPhotos = set.map { it.photoId }.toSet()
            return groups.filter { g -> g.person.id != not && g.faces.isNotEmpty() && g.person.notFaces.none { it in notFor } && g.photos.none { it in setPhotos } }
                .map { g -> g.person.id to sample.map { g.centreScore(it.vector) }.average().toFloat() }
                .sortedByDescending { it.second }
                .take(60)
        }
        // A guess is asked only when it is close enough AND clearly closer than the next person.
        fun clear(r: List<Pair<Int, Float>>, i: Int): Boolean =
            i < r.size && r[i].second >= line && r[i].second - (r.getOrNull(i + 1)?.second ?: -1f) >= GUESS_MARGIN
        fun guesses(r: List<Pair<Int, Float>>): Int = when {
            !clear(r, 0) -> 0
            GUESSES >= 2 && clear(r, 1) -> 2
            else -> 1
        }
        val sets = mutableListOf<UnsureSet>()
        // One-day groups that look like someone else are taken apart and asked about as one set.
        val takenApart = HashSet<Int>()
        for (p in people) {
            if (p.name != null || p.kept || p.me || p.hidden || p.blocked) continue
            val mine = p.faces.mapNotNull { byId[it] }
            if (mine.size < 2 || mine.any { it.takenAt <= 0L }) continue
            val photos = mine.map { it.photoId }.toSet()
            if (photos.size > ONE_DAY_MAX_PHOTOS) continue
            if (mine.maxOf { it.takenAt } - mine.minOf { it.takenAt } > ONE_DAY_MS) continue
            if (mine.all { it.id in parked }) continue
            val r = ranked(mine, not = p.id)
            // Only when nearly a sure match (6 Nov: correct one-day groups were taken apart on chance matches).
            if (guesses(r) == 0 || r.first().second < SAME - ONE_DAY_SLACK) continue
            takenApart += p.id
            sets += UnsureSet(mine.map { it.id }, r, guesses(r))
        }
        // Single unsure faces, set together when they share their closest person and day.
        val placed = people.filter { it.id !in takenApart }.flatMap { it.faces }.toHashSet()
        val apartFaces = people.filter { it.id in takenApart }.flatMap { it.faces }.toHashSet()
        // Poorly measured faces look a little like everyone: never asked about (6 Nov).
        val loose = faces.filter { it.id !in placed && it.id !in parked && it.id !in apartFaces && !it.weak && (it.turned || it.eyesHidden) }
        val byClosest = HashMap<Pair<Int, Long>, MutableList<Face>>()
        for (f in loose) {
            val r = ranked(listOf(f))
            if (guesses(r) == 0) continue
            val day = if (f.takenAt > 0L) f.takenAt / ONE_DAY_MS else -f.photoId
            byClosest.getOrPut(r.first().first to day) { mutableListOf() } += f
        }
        for ((_, list) in byClosest) {
            // Never two faces of one photo in a set.
            val one = list.distinctBy { it.photoId }
            val r = ranked(one)
            if (guesses(r) > 0) sets += UnsureSet(one.map { it.id }, r, guesses(r))
        }
        return people.filter { it.id !in takenApart } to sets.sortedByDescending { it.faces.size }
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

    /**
     * Two groups this alike on average are one person (5 Nov: the same face had split into groups of 647 and 72).
     * Raised from 0.36 the same day: two different bearded men of the same age had been joined.
     */
    var JOIN_GROUPS = 0.45f
        private set

    /**
     * How many faces of [b] look like someone in [a] at the normal "same person" line. Two different people almost never
     * have several such pairs; two looks of one person do.
     */
    fun matches(a: List<FloatArray>, b: List<FloatArray>): Int = b.count { y -> a.any { x -> cosine(x, y) >= SAME } }

    /**
     * The lowest likeness that is asked about, learnt from the answers (5 Nov): after several "No" answers the line moves
     * above most of them, but never above a "Yes" and never up to the automatic join.
     */
    /**
     * The automatic join line, learnt from the answers (5 Nov): when you keep saying Yes to pairs just under it, it
     * comes down toward them (never under 0.40), but always stays above every pair you said No to.
     */
    fun joinLine(noScores: List<Float>, yesScores: List<Float>): Float {
        if (yesScores.size < 5) return JOIN_GROUPS
        val sorted = yesScores.sorted()
        var line = sorted[(sorted.size * 0.25f).toInt()]
        noScores.maxOrNull()?.let { line = maxOf(line, it + 0.02f) }
        return line.coerceIn(JOIN_GROUPS - 0.05f, JOIN_GROUPS)
    }

    fun askFloor(noScores: List<Float>, yesScores: List<Float>): Float {
        if (noScores.size < 5) return ASK
        val sorted = noScores.sorted()
        var line = sorted[(sorted.size * 0.8f).toInt().coerceAtMost(sorted.lastIndex)] + 0.01f
        yesScores.minOrNull()?.let { line = minOf(line, it - 0.005f) }
        return line.coerceIn(ASK, JOIN_GROUPS - 0.04f)
    }

    /** Groups whose averages are further apart than this are not compared face by face (it is slow). */
    private var NEAR = 0.30f
    private const val SAMPLE = 20

    /**
     * Joins groups that are clearly the same person. Grouping goes photo by photo, so one odd early photo could start
     * a second group of someone that then kept growing; this compares whole groups afterwards. Two different names are
     * never joined. The named (or bigger) group keeps its place.
     */
    fun joinGroups(faces: List<Face>, people: List<Person>, different: Set<Pair<Int, Int>> = emptySet(), joinAt: Float = JOIN_GROUPS): List<Person> {
        markWeak(faces)
        val byId = faces.associateBy { it.id }
        var current = people
        repeat(12) {
            val vectors = current.map { p -> sampleOf(p, byId) }
            val centres = vectors.map { centroid(it) }
            val pairs = mutableListOf<Triple<Int, Int, Float>>()
            for (i in current.indices) for (j in i + 1 until current.size) {
                val a = current[i]
                val b = current[j]
                if (a.name != null && b.name != null && !a.name.equals(b.name, ignoreCase = true)) continue
                // A blocked person only takes in unnamed groups; never someone named, never another blocked person.
                if ((a.blocked || b.blocked) && (a.name != null || b.name != null || (a.blocked && b.blocked))) continue
                if (vectors[i].isEmpty() || vectors[j].isEmpty()) continue
                if (said(different, a.id, b.id) || sharePhotos(a, b, byId)) continue
                if (cosine(centres[i], centres[j]) < NEAR) continue
                val score = linkage(vectors[i], vectors[j])
                if (score < joinAt) continue
                val need = minOf(EVIDENCE_JOIN, vectors[i].size, vectors[j].size)
                if (matches(vectors[i], vectors[j]) >= need && matches(vectors[j], vectors[i]) >= minOf(1, need)) pairs += Triple(i, j, score)
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
                    a.blocked -> a
                    b.blocked -> b
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

    private fun goodOf(p: Person, byId: Map<Int, Face>): List<FloatArray> =
        spread(p.faces.sorted().mapNotNull { byId[it] }.filter { it.good }.map { it.vector }, SAMPLE)

    /** The faces a group is judged by: its good faces when it has a few, otherwise all of them. */
    private fun sampleOf(p: Person, byId: Map<Int, Face>): List<FloatArray> {
        val mine = p.faces.sorted().mapNotNull { byId[it] }
        val good = mine.filter { it.good }
        return spread((if (good.size >= AGREE) good else mine).map { it.vector }, SAMPLE)
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
    fun questions(faces: List<Face>, shown: List<Person>, different: Set<Pair<Int, Int>>, limit: Int = 30, floor: Float = ASK): List<SamePersonQuestion> {
        markWeak(faces)
        val byId = faces.associateBy { it.id }
        val vectors = shown.map { p -> sampleOf(p, byId) }
        val centres = vectors.map { centroid(it) }
        val out = mutableListOf<SamePersonQuestion>()
        for (i in shown.indices) for (j in i + 1 until shown.size) {
            val a = shown[i]
            val b = shown[j]
            if (a.name != null && b.name != null) continue
            if (vectors[i].isEmpty() || vectors[j].isEmpty()) continue
            if (said(different, a.id, b.id) || sharePhotos(a, b, byId)) continue
            if (cosine(centres[i], centres[j]) < floor) continue
            val score = linkage(vectors[i], vectors[j])
            if (score < floor || score >= JOIN_GROUPS) continue
            // Only with real evidence, from clear faces only and both ways (5 Nov: a man and a woman, both looking
            // down, were asked about): a few faces must match face to face, not just look alike on average.
            val ga = goodOf(a, byId)
            val gb = goodOf(b, byId)
            if (ga.size < 2 || gb.size < 2) continue
            val need = minOf(EVIDENCE, ga.size, gb.size)
            if (matches(ga, gb) < need || matches(gb, ga) < need) continue
            out += SamePersonQuestion(minOf(a.id, b.id), maxOf(a.id, b.id), score)
        }
        return out.sortedByDescending { it.score }.take(limit)
    }

    /** [into] keeps its name (or takes the other's), and gets every face of [others]. */
    fun merge(into: Person, others: List<Person>): Person = into.copy(
        faces = into.faces + others.flatMap { it.faces },
        name = into.name ?: others.firstNotNullOfOrNull { it.name },
        hidden = into.hidden && others.all { it.hidden },
        notFaces = into.notFaces + others.flatMap { it.notFaces },
        kept = into.kept || others.any { it.kept },
        me = into.me || others.any { it.me },
        blocked = into.blocked || others.any { it.blocked },
        cover = into.cover ?: others.firstNotNullOfOrNull { it.cover },
    )

    /** Takes [faceIds] out of [person] for good: they find another person (or start one) at the next grouping. */
    fun remove(person: Person, faceIds: Set<Int>): Person = person.copy(faces = person.faces - faceIds, notFaces = person.notFaces + faceIds, kept = true)

    /** People to show: named ones always, others once they appear in at least [minPhotos] photos. */
    fun shown(people: List<Person>, spots: Map<Int, FaceSpot>, minPhotos: Int = 2, newest: (Person) -> Long = { 0L }): List<Person> =
        people.filter { p -> !p.blocked && (p.me || p.name != null || p.faces.mapNotNull { spots[it]?.photoId }.toSet().size >= minPhotos) }
            // You first (6 Nov), then named people, whoever has the newest photo at the front; then the rest by most photos (5 Nov).
            .sortedWith(compareBy<Person> { !it.me }.thenBy { it.name == null }.thenByDescending { if (it.name != null) newest(it) else 0L }.thenByDescending { it.faces.size })

    /**
     * How good a face is as a person's round picture (5 Nov): straight at the camera matters most, then sharp, eyes
     * showing, a confident measurement and a decent size. The best one wins; the next best when it is missing.
     */
    fun coverScore(f: FaceSpot, facesInPhoto: Int = 1): Float {
        val facing = f.frontal * f.frontal
        val sharp = (f.sharp / 300f).coerceIn(0.2f, 1f)
        val eyes = if (f.eyes < EYES_HIDDEN) 0.3f else 1f
        val confident = if (f.strength > 0f) (f.strength / 20f).coerceIn(0.5f, 1f) else 1f
        val side = if (f.box.size >= 4) kotlin.math.sqrt((f.box[2] * f.box[3]).coerceAtLeast(0f)) else 0.1f
        val size = side.coerceIn(0.05f, 0.5f)
        // Not too far away (6 Nov): a tiny face in a wide shot makes a poor round picture.
        val near = if (side < 0.06f) 0.5f else 1f
        // On their own in the photo is best (6 Nov), then with one other person, then a group.
        val alone = when {
            facesInPhoto <= 1 -> 1f
            facesInPhoto == 2 -> 0.55f
            else -> 0.4f
        }
        return facing * sharp * eyes * confident * near * alone * (0.6f + size)
    }

    /** Unnamed people in fewer photos than this sit under "Others", not among the family (7A-2). */
    const val OTHERS_BELOW = 4
}

/** Each named person's learnt check (6 Nov): how sure it is, 0 to 1, that a face is them. */
class PersonCheck(private val mean: FloatArray, private val scale: FloatArray, private val weights: Map<Int, FloatArray>, private val bias: Map<Int, Float>) {
    val people: Set<Int> get() = weights.keys

    fun chance(personId: Int, v: FloatArray): Float {
        val w = weights[personId] ?: return 0.5f
        if (v.size != w.size) return 0.5f
        var z = bias[personId] ?: 0f
        for (i in v.indices) z += w[i] * (v[i] - mean[i]) * scale[i]
        return 1f / (1f + kotlin.math.exp(-z))
    }
}

/** Applies the learnt measure: solves L y = v for the lower-triangular [l] (y = L^-1 v), then scales to length 1. */
class Whitener private constructor(private val l: Array<DoubleArray>) {
    fun apply(v: FloatArray): FloatArray {
        val d = l.size
        val y = DoubleArray(d)
        for (i in 0 until d) {
            var s = v[i].toDouble()
            for (j in 0 until i) s -= l[i][j] * y[j]
            y[i] = s / l[i][i]
        }
        var norm = 0.0
        for (x in y) norm += x * x
        norm = kotlin.math.sqrt(norm)
        return FloatArray(d) { if (norm == 0.0) 0f else (y[it] / norm).toFloat() }
    }

    companion object {
        /** Cholesky split of a symmetric matrix given by its lower half; null when it isn't positive. */
        fun cholesky(a: Array<DoubleArray>): Whitener? {
            val d = a.size
            val l = Array(d) { DoubleArray(d) }
            for (i in 0 until d) {
                for (j in 0..i) {
                    var s = a[i][j]
                    for (k in 0 until j) s -= l[i][k] * l[j][k]
                    if (i == j) {
                        if (s <= 1e-12) return null
                        l[i][i] = kotlin.math.sqrt(s)
                    } else {
                        l[i][j] = s / l[j][j]
                    }
                }
            }
            return Whitener(l)
        }
    }
}

class PeopleSnapshot(
    val people: List<Person> = emptyList(),
    val spots: Map<Int, FaceSpot> = emptyMap(),
    val scanned: Int = 0,
    val questions: List<SamePersonQuestion> = emptyList(),
    /** True while the one-time "look again" (7A-2) hasn't finished: older faces are still in use. */
    val lookingAgain: Boolean = false,
    /** Faces to ask about (6 Nov), and faces put aside with "None of these" (the Not sure list), in sets. */
    val unsure: List<UnsureSet> = emptyList(),
    val parked: List<List<Int>> = emptyList(),
)

/**
 * Faces the app isn't sure about (6 Nov), asked about together: [ranked] is every person from the closest to the least
 * (with likeness), [guesses] how many of the first ones may still be asked about ("Is this ...?") before the people
 * grid opens.
 */
data class UnsureSet(val faces: List<Int>, val ranked: List<Pair<Int, Float>>, val guesses: Int)

class ScanProgress(val done: Int, val total: Int, val running: Boolean, val problem: String? = null)

/** What the phone does for People: finds faces (on the phone only), keeps them, and scans while charging. */
interface PeoplePlatform {
    val supported: Boolean get() = false
    fun isEnabled(): Boolean = false

    /**
     * Turning on starts scanning while charging. Turning off deletes the faces and names of the model in use only
     * (6 Nov); turning off the test model goes back to the standard one, which stays on.
     */
    suspend fun setEnabled(on: Boolean) = Unit

    suspend fun load(): PeopleSnapshot = PeopleSnapshot()

    /** Changes the people (names, merges, hides, removals) and saves them. */
    suspend fun edit(change: (List<Person>) -> List<Person>) = Unit

    /** Scans now, while the app is open (not only while charging). */
    fun scanNow() = Unit

    fun stopScan() = Unit

    /** Clears a problem shown from the last scan (OK on the problem card). */
    fun clearProblem() = Unit

    val progress: StateFlow<ScanProgress?> get() = MutableStateFlow(null)

    /** A small square picture of one face, cut from its photo. */
    suspend fun faceThumb(spot: FaceSpot): ByteArray? = null

    /** Moves faces from one person to another (or a new person when [toId] is null) and teaches both ways (5 Nov). */
    suspend fun move(fromId: Int, faceIds: Set<Int>, toId: Int?) = Unit

    /** Sorts faces that belong to nobody now (after "Not this person"), without waiting for the next scan. */
    suspend fun tidy() = Unit

    /** The answer to a "Same person?" question: Yes joins them, No is remembered so it is never asked again. */
    suspend fun answer(question: SamePersonQuestion, same: Boolean) = Unit

    /**
     * An unsure set answered (6 Nov). [personId] with [yes]: the faces join them (null: a new person). Not [yes]: "No"
     * to [personId], remembered. [park]: "None of these", the faces go to the Not sure list.
     */
    suspend fun answerUnsure(set: UnsureSet, personId: Int?, yes: Boolean, park: Boolean = false) = Unit

    /** AuraFace's first-time setup (6 Nov): downloads it if needed and turns its list on. A problem, or null. */
    suspend fun setUpTestModel(onProgress: (Float) -> Unit): String? = "Not available on this device."

    /** True when the last scan was cut short (Android stopped the app) and nothing is scanning now (6 Nov). */
    fun scanCutShort(): Boolean = false

    fun clearCutShort() = Unit

    /** Opens the phone's own settings for this app, where battery use ("don't put to sleep") is set. */
    fun openBatterySettings() = Unit

    /** A notification when new "Same person?" questions are waiting (at most one a day). Always on (6 Nov). */
    fun askByNotification(): Boolean = true
    fun setAskByNotification(on: Boolean) = Unit

    /** The AuraFace test model is in use (6 Nov): its own faces, people and names, kept apart from the standard ones. */
    fun usesTestModel(): Boolean = false

    /** Switches to (downloading once, on Wi-Fi) or away from the test model. Null when it worked, else the problem. */
    suspend fun setTestModel(on: Boolean, onProgress: (Float) -> Unit): String? = "Not available on this device."
}

object NoPeople : PeoplePlatform

@Stable
class PeopleState(val platform: PeoplePlatform = NoPeople) {
    var enabled by mutableStateOf(platform.isEnabled())
    var people by mutableStateOf<List<Person>>(emptyList())
    var spots by mutableStateOf<Map<Int, FaceSpot>>(emptyMap())
    var scanned by mutableStateOf(0)
    var questions by mutableStateOf<List<SamePersonQuestion>>(emptyList())
    var testModel by mutableStateOf(platform.usesTestModel())
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
        unsure = s.unsure
        parked = s.parked
    }

    /** Faces to ask about and the Not sure list (6 Nov). */
    var unsure by mutableStateOf<List<UnsureSet>>(emptyList())
    var parked by mutableStateOf<List<List<Int>>>(emptyList())
    var skippedUnsure by mutableStateOf<Set<Int>>(emptySet())

    /** The next unsure set to ask about (by its first face), skipping ones put off. */
    fun nextUnsure(): UnsureSet? = unsure.firstOrNull { it.faces.firstOrNull() !in skippedUnsure && it.guesses > 0 && it.ranked.firstOrNull()?.let { r -> find(r.first) } != null }

    suspend fun answerUnsure(set: UnsureSet, personId: Int?, yes: Boolean, park: Boolean = false) {
        platform.answerUnsure(set, personId, yes, park)
        refresh()
    }

    /** The next question to show, skipping ones put off and ones whose people have gone. */
    fun nextQuestion(): SamePersonQuestion? = questions.firstOrNull { q -> q !in skipped && find(q.a) != null && find(q.b) != null }

    suspend fun answer(q: SamePersonQuestion, same: Boolean) {
        platform.answer(q, same)
        refresh()
    }

    fun find(id: Int): Person? = people.firstOrNull { it.id == id }

    fun photosOf(person: Person): Set<Long> = person.faces.mapNotNull { spots[it]?.photoId }.toSet()

    /** Where [person]'s face is in each of their photos (to circle it in the grid, 5 Nov). */
    fun boxesOf(person: Person): Map<Long, FloatArray> = person.faces.mapNotNull { spots[it] }.associate { it.photoId to it.box }

    /**
     * The face to show for a person (5 Nov): the biggest clear front face; when there is none, the biggest face.
     */
    fun coverOf(person: Person): FaceSpot? {
        person.cover?.let { id -> if (id in person.faces) spots[id]?.let { return it } }
        val counts = facesPerPhoto()
        return person.faces.mapNotNull { spots[it] }.maxByOrNull { FaceGrouping.coverScore(it, counts[it.photoId] ?: 1) }
    }

    private var countedFrom: Map<Int, FaceSpot>? = null
    private var counted: Map<Long, Int> = emptyMap()

    /** How many faces each photo has (kept until the faces change). */
    private fun facesPerPhoto(): Map<Long, Int> {
        val now = spots
        if (countedFrom !== now) {
            counted = now.values.groupingBy { it.photoId }.eachCount()
            countedFrom = now
        }
        return counted
    }

    /** The round picture picked by hand (6 Nov): the person's face in [photoId]. */
    suspend fun setCover(personId: Int, photoId: Long) = change { list ->
        list.map { p ->
            if (p.id != personId) p else {
                val face = p.faces.firstOrNull { spots[it]?.photoId == photoId }
                if (face == null) p else p.copy(cover = face, kept = true)
            }
        }
    }

    /** Photos of every named person whose name contains [query] (Search, 7A). */
    fun photosNamed(query: String): Set<Long> {
        val q = query.trim()
        if (q.length < 2) return emptySet()
        return people.filter { !it.hidden && !it.blocked && (it.name?.contains(q, ignoreCase = true) == true || (it.me && q.equals("me", ignoreCase = true))) }
            .flatMap { photosOf(it) }.toSet()
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
        list.filter { it !in chosen } + FaceGrouping.merge(into, chosen - into).copy(kept = true)
    }

    /** Blocks or unblocks people (6 Nov). */
    suspend fun setBlocked(ids: Set<Int>, blocked: Boolean) = change { list -> list.map { if (it.id in ids) it.copy(blocked = blocked, me = it.me && !blocked) else it } }

    /** "This is me" (6 Nov): marks [id] as the phone's owner, or takes the mark off when it was already them. */
    suspend fun setMe(id: Int) = change { list ->
        val already = list.firstOrNull { it.id == id }?.me == true
        list.map { it.copy(me = !already && it.id == id) }
    }

    suspend fun setHidden(ids: Set<Int>, hidden: Boolean) = change { list -> list.map { if (it.id in ids) it.copy(hidden = hidden) else it } }

    /** "Not this person": the faces of [person] in these photos leave it for good, and are sorted again straight away. */
    suspend fun removePhotos(personId: Int, photoIds: Set<Long>) {
        platform.edit { list ->
            list.map { p ->
                if (p.id != personId) p else FaceGrouping.remove(p, p.faces.filter { spots[it]?.photoId in photoIds }.toSet())
            }.filter { it.faces.isNotEmpty() || it.name != null }
        }
        platform.tidy()
        refresh()
    }

    /** "Move to…": these photos' faces of [personId] go to [toId] (or a new person). */
    suspend fun moveTo(personId: Int, photoIds: Set<Long>, toId: Int?) {
        val person = find(personId) ?: return
        val faces = person.faces.filter { spots[it]?.photoId in photoIds }.toSet()
        if (faces.isEmpty()) return
        platform.move(personId, faces, toId)
        refresh()
    }

    /** Where the People page was scrolled to, kept here so coming back from a person always returns there (5 Nov). */
    var scrollIndex by mutableStateOf(0)
    var scrollOffset by mutableStateOf(0)

    fun resetScroll() { scrollIndex = 0; scrollOffset = 0 }
}
