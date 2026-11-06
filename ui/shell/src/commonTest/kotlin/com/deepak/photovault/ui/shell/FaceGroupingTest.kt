package com.deepak.photovault.ui.shell

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FaceGroupingTest {
    /** A unit vector pointing at [angle] in its own plane [plane] (0..63) of a 128-number vector, with a small wobble. */
    private fun vec(plane: Int, wobble: Float = 0f): FloatArray {
        val v = FloatArray(128)
        v[plane * 2] = cos(wobble)
        v[plane * 2 + 1] = sin(wobble)
        return v
    }

    private fun face(id: Int, photo: Long, plane: Int, wobble: Float = 0f) = Face(id, photo, floatArrayOf(0.1f, 0.1f, 0.2f, 0.2f), vec(plane, wobble))

    @Test
    fun sameLooksGroupTogetherAndDifferentPeopleDoNot() {
        val faces = listOf(face(1, 10, 0), face(2, 11, 0, 0.2f), face(3, 12, 1), face(4, 13, 0, 0.1f), face(5, 14, 1, 0.3f))
        val (people, next) = FaceGrouping.group(faces, emptyList(), 1)
        assertEquals(2, people.size)
        assertEquals(setOf(setOf(1, 2, 4), setOf(3, 5)), people.map { it.faces }.toSet())
        assertEquals(3, next)
    }

    @Test
    fun handMadeChoicesAreKept() {
        val faces = listOf(face(1, 10, 0), face(2, 11, 0), face(3, 12, 0))
        val (first, next) = FaceGrouping.group(faces, emptyList(), 1)
        val named = first.single().copy(name = "Mum")
        // Face 2 was taken out by hand: it never joins Mum again, even though it looks the same.
        val removed = FaceGrouping.remove(named, setOf(2))
        val (again, _) = FaceGrouping.group(faces + face(4, 13, 0, 0.1f), listOf(removed), next)
        val mum = again.first { it.name == "Mum" }
        assertEquals(setOf(1, 3, 4), mum.faces)
        assertTrue(again.any { 2 in it.faces && it.name == null })
    }

    @Test
    fun aMergedPersonMatchesBothLooksAfterwards() {
        // Plane 0 = without sunglasses, plane 1 = with: different enough to start as two people.
        val faces = listOf(face(1, 10, 0), face(2, 11, 1))
        val (two, next) = FaceGrouping.group(faces, emptyList(), 1)
        assertEquals(2, two.size)
        val merged = FaceGrouping.merge(two[0].copy(name = "Deepak"), listOf(two[1]))
        val (after, _) = FaceGrouping.group(faces + face(3, 12, 1, 0.1f) + face(4, 13, 0, 0.1f), listOf(merged), next)
        assertEquals(1, after.size)
        assertEquals(setOf(1, 2, 3, 4), after.single().faces)
    }

    @Test
    fun onlyNamedPeopleOrPeopleInTwoPhotosAreShown() {
        val spots = mapOf(1 to FaceSpot(1, 10, floatArrayOf()), 2 to FaceSpot(2, 10, floatArrayOf()), 3 to FaceSpot(3, 11, floatArrayOf()), 4 to FaceSpot(4, 12, floatArrayOf()))
        val oneShot = Person(1, setOf(1, 2)) // two faces, same photo
        val twoShots = Person(2, setOf(3, 4))
        val named = Person(3, emptySet(), name = "Dad")
        assertEquals(listOf(3, 2), FaceGrouping.shown(listOf(oneShot, twoShots, named), spots).map { it.id })
    }

    @Test
    fun mergeKeepsTheNameAndEveryFace() {
        val a = Person(1, setOf(1), name = null)
        val b = Person(2, setOf(2), name = "Nani", notFaces = setOf(9))
        val m = FaceGrouping.merge(a, listOf(b))
        assertEquals("Nani", m.name)
        assertEquals(setOf(1, 2), m.faces)
        assertEquals(setOf(9), m.notFaces)
        assertNull(Person(4, emptySet()).name)
    }

    @Test
    fun groupsOfTheSamePersonAreJoinedAfterwards() {
        // Two groups of the same face (made by an odd first photo) and one of someone else.
        val faces = listOf(face(1, 10, 0), face(2, 11, 0, 0.3f), face(3, 12, 0, 0.6f), face(4, 13, 0, 0.9f), face(5, 14, 5), face(6, 15, 5, 0.2f))
        val a = Person(1, setOf(1, 2))
        val b = Person(2, setOf(3, 4))
        val other = Person(3, setOf(5, 6))
        val joined = FaceGrouping.joinGroups(faces, listOf(a, b, other))
        assertEquals(2, joined.size)
        assertTrue(joined.any { it.faces == setOf(1, 2, 3, 4) })
        assertTrue(joined.any { it.faces == setOf(5, 6) })
    }

    @Test
    fun twoDifferentNamesAreNeverJoined() {
        val faces = listOf(face(1, 10, 0), face(2, 11, 0, 0.1f))
        val joined = FaceGrouping.joinGroups(faces, listOf(Person(1, setOf(1), name = "Ravi"), Person(2, setOf(2), name = "Raj")))
        assertEquals(2, joined.size)
        // A named group and an unnamed one of the same face become one, keeping the name.
        val one = FaceGrouping.joinGroups(faces, listOf(Person(1, setOf(1)), Person(2, setOf(2), name = "Raj")))
        assertEquals(listOf("Raj"), one.map { it.name })
        assertEquals(2, one.single().id)
    }

    @Test
    fun aSideViewThatMatchesNobodyDoesNotStartAPerson() {
        val side = Face(1, 10, floatArrayOf(0f, 0f, 0.1f, 0.1f), vec(3), frontal = 0.1f)
        val (none, _) = FaceGrouping.group(listOf(side), emptyList(), 1)
        assertTrue(none.isEmpty())
        // ...but it still joins someone it clearly matches.
        val (one, _) = FaceGrouping.group(listOf(face(2, 11, 3), side), emptyList(), 1)
        assertEquals(setOf(1, 2), one.single().faces)
    }

    @Test
    fun twoFacesInOnePhotoAreNeverOnePerson() {
        // Twins in one photo: the same look, but the same photo.
        val (people, _) = FaceGrouping.group(listOf(face(1, 10, 0), face(2, 10, 0, 0.05f)), emptyList(), 1)
        assertEquals(2, people.size)
    }

    @Test
    fun closeGroupsBecomeAQuestionUntilAnswered() {
        // About 0.41 alike on average, and two faces of b match a face of a face to face (at angles 1.0 and 1.05).
        val faces = listOf(face(1, 10, 0), face(2, 11, 0, 0.1f), face(3, 12, 0, 1.0f), face(4, 13, 0, 1.05f), face(5, 14, 0, 1.5f))
        val a = Person(1, setOf(1, 2))
        val b = Person(2, setOf(3, 4, 5))
        // Not alike enough to join by themselves...
        assertEquals(2, FaceGrouping.joinGroups(faces, listOf(a, b)).size)
        // ...so People asks.
        val asked = FaceGrouping.questions(faces, listOf(a, b), emptySet())
        assertEquals(listOf(1 to 2), asked.map { it.a to it.b })
        // After "No" it never asks again.
        assertTrue(FaceGrouping.questions(faces, listOf(a, b), setOf(1 to 2)).isEmpty())
    }

    @Test
    fun alikeOnAverageButNoFaceMatchesIsNotAsked() {
        // About 0.40 alike on average, but no single pair of faces reaches 0.42.
        val faces = listOf(face(1, 10, 0), face(2, 11, 0, 0.02f), face(3, 12, 0, 1.16f), face(4, 13, 0, 1.18f))
        val asked = FaceGrouping.questions(faces, listOf(Person(1, setOf(1, 2)), Person(2, setOf(3, 4))), emptySet())
        assertTrue(asked.isEmpty())
    }

    @Test
    fun noAnswersRaiseTheLine() {
        assertEquals(FaceGrouping.ASK, FaceGrouping.askFloor(listOf(0.33f, 0.34f), emptyList()))
        val raised = FaceGrouping.askFloor(listOf(0.33f, 0.34f, 0.35f, 0.36f, 0.38f), emptyList())
        assertTrue(raised > 0.37f)
        // Never above a "Yes".
        assertTrue(FaceGrouping.askFloor(listOf(0.33f, 0.34f, 0.35f, 0.36f, 0.38f), listOf(0.35f)) < 0.35f)
    }

    @Test
    fun namedPeopleWithTheNewestPhotoComeFirst() {
        val spots = (1..6).associateWith { FaceSpot(it, it.toLong(), floatArrayOf()) }
        val old = Person(1, setOf(1, 2), name = "Old")
        val recent = Person(2, setOf(3, 4), name = "Recent")
        val big = Person(3, setOf(5, 6))
        val newest = mapOf(1 to 100L, 2 to 900L)
        assertEquals(listOf(2, 1, 3), FaceGrouping.shown(listOf(old, recent, big), spots, newest = { newest[it.id] ?: 0L }).map { it.id })
    }

    @Test
    fun oneWrongFaceInAPersonNoLongerPullsInOthers() {
        // Four faces of one person and one wrong face (plane 2) that slipped in earlier.
        val faces = listOf(face(1, 10, 0), face(2, 11, 0, 0.1f), face(3, 12, 0, 0.2f), face(4, 13, 0, 0.15f), face(5, 14, 2))
        val person = Person(1, setOf(1, 2, 3, 4, 5))
        val (after, _) = FaceGrouping.group(faces + face(6, 15, 2, 0.05f), listOf(person), 2)
        // The new plane-2 face matches only the one wrong face, so it does not join.
        assertTrue(after.first { it.id == 1 }.faces.none { it == 6 })
    }

    @Test
    fun darkGlassesNeedACloserMatch() {
        // cos(1.1) is about 0.45: enough for a normal face, not for one with the eyes hidden.
        val base = face(1, 10, 0)
        val normal = Face(2, 11, floatArrayOf(0f, 0f, 0.2f, 0.2f), vec(0, 1.1f))
        val glasses = Face(3, 12, floatArrayOf(0f, 0f, 0.2f, 0.2f), vec(0, 1.1f), eyes = 0.2f)
        val (a, _) = FaceGrouping.group(listOf(base, normal), emptyList(), 1)
        assertEquals(1, a.size)
        // Not joined, and (6 Nov) not a person of its own either: it is asked about instead.
        val (b, _) = FaceGrouping.group(listOf(base, glasses), emptyList(), 1)
        assertEquals(listOf(setOf(1)), b.map { it.faces })
    }

    @Test
    fun weakMeasurementsDoNotStartPeople() {
        // 40 ordinary faces of one person, and two weak ones of someone else that match each other.
        val strong = (1..40).map { Face(it, it.toLong(), floatArrayOf(0f, 0f, 0.2f, 0.2f), vec(0, it * 0.001f), strength = 20f) }
        val weakA = Face(41, 41, floatArrayOf(0f, 0f, 0.2f, 0.2f), vec(7), strength = 8f)
        val weakB = Face(42, 42, floatArrayOf(0f, 0f, 0.2f, 0.2f), vec(7, 0.05f), strength = 8f)
        val (people, _) = FaceGrouping.group(strong + weakA + weakB, emptyList(), 1)
        assertEquals(1, people.size)
        assertTrue(people.none { 41 in it.faces || 42 in it.faces })
    }

    @Test
    fun aGroupThrowsOutAFaceThatLooksLikeNoneOfTheOthers() {
        val faces = listOf(face(1, 10, 0), face(2, 11, 0, 0.1f), face(3, 12, 0, 0.2f), face(4, 13, 6))
        val mixed = Person(1, setOf(1, 2, 3, 4))
        val checked = FaceGrouping.selfCheck(faces, listOf(mixed)).single()
        assertEquals(setOf(1, 2, 3), checked.faces)
        assertTrue(4 in checked.notFaces)
        // Two looks merged by hand (each look matches its own faces) stay together.
        val looks = listOf(face(1, 10, 0), face(2, 11, 0, 0.1f), face(3, 12, 0, 0.2f), face(5, 14, 6), face(6, 15, 6, 0.1f), face(7, 16, 6, 0.2f))
        assertEquals(6, FaceGrouping.selfCheck(looks, listOf(Person(2, setOf(1, 2, 3, 5, 6, 7)))).single().faces.size)
    }

    @Test
    fun aFaceLikeOneTakenOutDoesNotJoin() {
        // Sisters: the group is mostly sister A (angle 0); sister B's face (angle 0.5) was taken out by hand.
        val faces = listOf(face(1, 10, 0), face(2, 11, 0, 0.05f), face(3, 12, 0, 0.1f), face(4, 13, 0, 0.5f))
        val a = Person(1, setOf(1, 2, 3), notFaces = setOf(4))
        // A new photo of sister B (angle 0.55) still scores over 0.42 against A, but looks more like the face taken out.
        val (after, _) = FaceGrouping.group(faces + face(5, 14, 0, 0.55f), listOf(a), 2)
        assertTrue(5 !in after.first { it.id == 1 }.faces)
    }

    @Test
    fun yesAnswersBringTheJoinLineDown() {
        assertEquals(FaceGrouping.JOIN_GROUPS, FaceGrouping.joinLine(emptyList(), listOf(0.41f, 0.42f)))
        val line = FaceGrouping.joinLine(emptyList(), listOf(0.41f, 0.42f, 0.42f, 0.43f, 0.44f))
        assertTrue(line < FaceGrouping.JOIN_GROUPS && line >= 0.40f)
        // ...but never down to a pair answered No.
        assertTrue(FaceGrouping.joinLine(listOf(0.43f), listOf(0.41f, 0.42f, 0.42f, 0.43f, 0.44f)) >= 0.45f)
    }

    @Test
    fun theClearestFrontFaceIsTheCover() {
        val side = FaceSpot(1, 10, floatArrayOf(0f, 0f, 0.4f, 0.4f), frontal = 0.4f, sharp = 400f)
        val front = FaceSpot(2, 11, floatArrayOf(0f, 0f, 0.2f, 0.2f), frontal = 0.95f, sharp = 300f)
        val blurry = FaceSpot(3, 12, floatArrayOf(0f, 0f, 0.3f, 0.3f), frontal = 0.95f, sharp = 40f)
        assertEquals(2, listOf(side, front, blurry).maxByOrNull { FaceGrouping.coverScore(it) }!!.id)
    }

    @Test
    fun theLearntMeasureNeedsTwoNamedPeople() {
        // Eight good faces each for two named people (planes 0 and 1), with some spread.
        val a = (1..8).map { face(it, it.toLong(), 0, it * 0.03f) }
        val b = (11..18).map { face(it, it.toLong(), 1, (it - 10) * 0.03f) }
        val people = listOf(Person(1, a.map { it.id }.toSet(), name = "Asha"), Person(2, b.map { it.id }.toSet(), name = "Bina"))
        val w = FaceGrouping.whitener(a + b, people)
        assertTrue(w != null)
        val v = w!!.apply(vec(0))
        val length = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() })
        assertTrue(kotlin.math.abs(length - 1.0) < 1e-4)
        assertTrue(FaceGrouping.whitener(a + b, listOf(people[0], people[1].copy(name = null))) == null)
    }

    @Test
    fun someoneWithAKnownLookalikeNeedsACloserMatch() {
        // Sister taken out at angle 0.6 (cos about 0.83 to the person): a known look-alike. A new face at angle 1.1
        // (about 0.45: enough normally) doesn't join; one at angle 0.3 (0.96) still does.
        val faces = listOf(face(1, 10, 0), face(2, 11, 0, 0.02f), face(3, 12, 0, 0.6f))
        val p = Person(1, setOf(1, 2), notFaces = setOf(3))
        val (far, _) = FaceGrouping.group(faces + face(4, 13, 0, -1.1f), listOf(p), 2)
        assertTrue(4 !in far.first { it.id == 1 }.faces)
        val (near, _) = FaceGrouping.group(faces + face(5, 14, 0, -0.3f), listOf(p), 2)
        assertTrue(5 in near.first { it.id == 1 }.faces)
    }

    private val box = floatArrayOf(0.4f, 0.2f, 0.2f, 0.2f)
    private val t0 = 1_700_000_000_000L
    private fun timed(id: Int, photo: Long, wobble: Float, at: Long, clothes: FloatArray? = null) =
        Face(id, photo, box, vec(0, wobble), takenAt = at, clothes = clothes)

    @Test
    fun aBurstPhotoWithAFaceInTheSameSpotJoins() {
        // Angle 1.2 (likeness about 0.36): not enough alone, but the photo 1.5 s later with a face in the same spot is them.
        val p = Person(1, setOf(1))
        val (burst, _) = FaceGrouping.group(listOf(timed(1, 10, 0f, t0), timed(2, 11, 1.2f, t0 + 1_500)), listOf(p), 2)
        assertTrue(2 in burst.first { it.id == 1 }.faces)
        // A minute later is not a burst.
        val (later, _) = FaceGrouping.group(listOf(timed(1, 10, 0f, t0), timed(2, 11, 1.2f, t0 + 60_000)), listOf(p), 2)
        assertTrue(2 !in later.first { it.id == 1 }.faces)
    }

    @Test
    fun theSameClothesThatDayHelpAnAlmostMatch() {
        val red = FloatArray(20).also { it[0] = 1f }
        val p = Person(1, setOf(1))
        // Angle 1.16 (about 0.40): just under the line; the same clothes two hours apart lift it over.
        val (same, _) = FaceGrouping.group(listOf(timed(1, 10, 0f, t0, red), timed(2, 11, 1.16f, t0 + 7_200_000, red)), listOf(p), 2)
        assertTrue(2 in same.first { it.id == 1 }.faces)
        val (none, _) = FaceGrouping.group(listOf(timed(1, 10, 0f, t0), timed(2, 11, 1.16f, t0 + 7_200_000)), listOf(p), 2)
        assertTrue(2 !in none.first { it.id == 1 }.faces)
        // Clothes alone never join someone who doesn't look alike.
        val (far, _) = FaceGrouping.group(listOf(timed(1, 10, 0f, t0, red), timed(2, 11, 1.4f, t0 + 7_200_000, red)), listOf(p), 2)
        assertTrue(2 !in far.first { it.id == 1 }.faces)
    }

    @Test
    fun aDifferentOutfitAtTheSameEventHoldsBackAJustMatch() {
        val red = FloatArray(20).also { it[0] = 1f }
        val blue = FloatArray(20).also { it[10] = 1f }
        val p = Person(1, setOf(1))
        // Angle 1.12 (about 0.44) joins on its own...
        val (plain, _) = FaceGrouping.group(listOf(timed(1, 10, 0f, t0), timed(2, 11, 1.12f, t0 + 3_600_000)), listOf(p), 2)
        assertTrue(2 in plain.first { it.id == 1 }.faces)
        // ...but not wearing something clearly different an hour later at the same event.
        val (other, _) = FaceGrouping.group(listOf(timed(1, 10, 0f, t0, red), timed(2, 11, 1.12f, t0 + 3_600_000, blue)), listOf(p), 2)
        assertTrue(2 !in other.first { it.id == 1 }.faces)
    }

    @Test
    fun boxesOverlap() {
        assertTrue(FaceGrouping.overlap(box, box) > 0.99f)
        assertEquals(0f, FaceGrouping.overlap(box, floatArrayOf(0f, 0f, 0.1f, 0.1f)))
    }

    @Test
    fun sortingAgainDoesNotDependOnTheOrderPhotosWereLookedAt() {
        // Two people, faces given in two different orders: the same groups either way.
        val faces = (0 until 6).map { face(it + 1, 100L + it, 0, (it % 3) * 0.15f) } + (0 until 6).map { face(it + 11, 200L + it, 1, (it % 3) * 0.15f) }
        val a = FaceGrouping.closeTogether(faces).toSet()
        val b = FaceGrouping.closeTogether(faces.reversed()).toSet()
        assertEquals(a, b)
        assertEquals(setOf((1..6).toSet(), (11..16).toSet()), a)
    }

    @Test
    fun sortingAgainKeepsNamedAndHandChangedPeople() {
        val faces = (0 until 4).map { face(it + 1, 100L + it, 0, it * 0.05f) } + (0 until 4).map { face(it + 11, 200L + it, 1, it * 0.05f) }
        val mum = Person(1, setOf(1, 2), name = "Mum")
        val (people, _) = FaceGrouping.resort(faces, listOf(mum), 5)
        assertEquals(setOf(1, 2, 3, 4), people.first { it.name == "Mum" }.faces)
        assertTrue(people.any { it.name == null && it.faces == (11..14).toSet() })
    }

    @Test
    fun aClashInOnePhotoKeepsTheBetterFit() {
        // Faces 1 and 2 share a photo; face 2 fits the others less: only face 1 stays in the group.
        val faces = listOf(face(1, 10, 0), face(2, 10, 0, 0.6f), face(3, 11, 0, 0.05f), face(4, 12, 0, 0.1f))
        val groups = FaceGrouping.closeTogether(faces)
        assertTrue(groups.none { 1 in it && 2 in it })
    }

    @Test
    fun eachNamedPersonLearnsWhatSetsThemApart() {
        // Two sisters: almost the same face; number 5 tells them apart, other numbers wobble.
        fun sister(sign: Float, seed: Int): FloatArray {
            val v = FloatArray(128)
            v[0] = 1f
            v[5] = 0.25f * sign
            v[10 + seed % 40] = 0.2f
            v[60 + (seed * 7) % 40] = -0.15f
            val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
            return FloatArray(128) { v[it] / n }
        }
        val a = (0 until 14).map { Face(100 + it, 1000L + it, floatArrayOf(0.1f, 0.1f, 0.2f, 0.2f), sister(1f, it)) }
        val b = (0 until 14).map { Face(200 + it, 2000L + it, floatArrayOf(0.1f, 0.1f, 0.2f, 0.2f), sister(-1f, it + 3)) }
        val people = listOf(Person(1, a.map { it.id }.toSet(), name = "Priya"), Person(2, b.map { it.id }.toSet(), name = "Anjali"))
        val check = FaceGrouping.personCheck(a + b, people)!!
        val newA = sister(1f, 77)
        val newB = sister(-1f, 91)
        assertTrue(check.chance(1, newA) > check.chance(2, newA))
        assertTrue(check.chance(2, newB) > check.chance(1, newB))
        // Only one named person with enough faces: no check.
        assertNull(FaceGrouping.personCheck(a, people.take(1)))
    }

    @Test
    fun oneLookAlikePhotoDoesNotChainTwoPeopleTogether() {
        // Person A around angle 0, person B around angle 1.7 (not alike at all). One odd photo of each sits in between
        // (angles 0.55 and 1.05) and they match each other: one link used to chain both people into one group.
        val a = listOf(0f, 0.05f, 0.1f, 0.15f, 0.55f).mapIndexed { i, w -> face(i + 1, 100L + i, 0, w) }
        val b = listOf(1.6f, 1.65f, 1.7f, 1.75f, 1.05f).mapIndexed { i, w -> face(i + 11, 200L + i, 0, w) }
        val groups = FaceGrouping.closeTogether(a + b)
        assertTrue(groups.none { g -> g.any { it in 1..4 } && g.any { it in 11..14 } })
    }

    @Test
    fun facesKeptApartStayApart() {
        val faces = (0 until 4).map { face(it + 1, 100L + it, 0, it * 0.05f) }
        val groups = FaceGrouping.closeTogether(faces, apart = setOf(1 to 2))
        assertTrue(groups.none { 1 in it && 2 in it })
    }

    @Test
    fun youComeFirst() {
        val spots = mapOf(1 to FaceSpot(1, 10, floatArrayOf()), 2 to FaceSpot(2, 11, floatArrayOf()), 3 to FaceSpot(3, 12, floatArrayOf()))
        val mum = Person(1, setOf(1), name = "Mum")
        val me = Person(2, setOf(2, 3), me = true)
        assertEquals(listOf(2, 1), FaceGrouping.shown(listOf(mum, me), spots).map { it.id })
    }

    private fun at(id: Int, photo: Long, plane: Int, wobble: Float, day: Long, frontal: Float = 1f) =
        Face(id, photo, floatArrayOf(0.1f, 0.1f, 0.2f, 0.2f), vec(plane, wobble), frontal = frontal, takenAt = t0 + day * 86_400_000L)

    @Test
    fun aOneDayGroupThatLooksLikeSomeoneIsAskedAbout() {
        // Mum over many days; a small group from one day (rangoli) at angle 1.0 (likeness about 0.54) is taken apart
        // and asked about as one set, Mum first.
        val mumFaces = (0 until 4).map { at(it + 1, 100L + it, 0, it * 0.05f, it.toLong() * 30) }
        val day = listOf(at(5, 200, 0, 1.0f, 400), at(6, 201, 0, 1.05f, 400))
        val mum = Person(1, setOf(1, 2, 3, 4), name = "Mum")
        val party = Person(2, setOf(5, 6))
        val (people, sets) = FaceGrouping.unsure(mumFaces + day, listOf(mum, party), emptySet())
        assertTrue(people.none { it.id == 2 })
        assertEquals(listOf(5, 6), sets.single().faces)
        assertEquals(1, sets.single().ranked.first().first)
        assertTrue(sets.single().guesses >= 1)
    }

    @Test
    fun aSideFaceThatJoinedNoOneIsAskedAbout() {
        val mumFaces = (0 until 4).map { at(it + 1, 100L + it, 0, it * 0.05f, it.toLong() * 30) }
        val side = at(7, 300, 0, 1.0f, 500, frontal = 0.1f)
        val mum = Person(1, setOf(1, 2, 3, 4), name = "Mum")
        val (_, sets) = FaceGrouping.unsure(mumFaces + side, listOf(mum), emptySet())
        assertEquals(listOf(7), sets.single().faces)
        // Put aside ("None of these"): not asked again.
        val (_, none) = FaceGrouping.unsure(mumFaces + side, listOf(mum), setOf(7))
        assertTrue(none.isEmpty())
    }

    @Test
    fun sideFacesDoNotMakeAGroupOfTheirOwn() {
        val side = (0 until 4).map { at(20 + it, 400L + it, 1, it * 0.02f, 1, frontal = 0.1f) }
        val (people, _) = FaceGrouping.resort(side, emptyList(), 1)
        assertTrue(people.none { p -> p.faces.any { it in 20..23 } })
    }

    @Test
    fun aGuessThatDoesNotStandOutIsNotAsked() {
        // A side face halfway between Mum (plane 0) and Dad (plane 1): about 0.7 like both, so neither stands out.
        val mumFaces = (0 until 4).map { at(it + 1, 100L + it, 0, it * 0.02f, it.toLong() * 30) }
        val dadFaces = (0 until 4).map { at(it + 11, 150L + it, 1, it * 0.02f, it.toLong() * 30) }
        val middle = FloatArray(128).also { it[0] = 0.7071f; it[2] = 0.7071f }
        val side = Face(7, 300, floatArrayOf(0.1f, 0.1f, 0.2f, 0.2f), middle, frontal = 0.1f, takenAt = t0)
        val people = listOf(Person(1, (1..4).toSet(), name = "Mum"), Person(2, (11..14).toSet(), name = "Dad"))
        val (_, sets) = FaceGrouping.unsure(mumFaces + dadFaces + side, people, emptySet())
        assertTrue(sets.isEmpty())
    }

    @Test
    fun aOneDayGroupOnlyLooselyLikeSomeoneStays() {
        // A one-day group at angle 1.25 from Mum (about 0.34: fair, but far from a sure match) is left alone.
        val mumFaces = (0 until 4).map { at(it + 1, 100L + it, 0, it * 0.02f, it.toLong() * 30) }
        val day = listOf(at(5, 200, 0, 1.24f, 400), at(6, 201, 0, 1.26f, 400))
        val party = Person(2, setOf(5, 6))
        val (people, sets) = FaceGrouping.unsure(mumFaces + day, listOf(Person(1, (1..4).toSet(), name = "Mum"), party), emptySet())
        assertTrue(people.any { it.id == 2 })
        assertTrue(sets.isEmpty())
    }

    @Test
    fun blockedPeopleAreNeverShown() {
        val spots = mapOf(1 to FaceSpot(1, 10, floatArrayOf()), 2 to FaceSpot(2, 11, floatArrayOf()), 3 to FaceSpot(3, 12, floatArrayOf()))
        val stranger = Person(1, setOf(1, 2), blocked = true)
        val mum = Person(2, setOf(3), name = "Mum")
        assertEquals(listOf(2), FaceGrouping.shown(listOf(stranger, mum), spots).map { it.id })
    }

    @Test
    fun aBlockedPersonTakesInTheirOwnUnnamedGroupButNeverSomeoneNamed() {
        val a = (0 until 3).map { face(it + 1, 100L + it, 0, it * 0.05f) }
        val b = (0 until 3).map { face(it + 11, 200L + it, 0, it * 0.05f + 0.02f) }
        val blocked = Person(1, setOf(1, 2, 3), blocked = true)
        val unnamed = Person(2, setOf(11, 12, 13))
        val joined = FaceGrouping.joinGroups(a + b, listOf(blocked, unnamed))
        assertEquals(1, joined.size)
        assertTrue(joined.single().blocked)
        val named = Person(2, setOf(11, 12, 13), name = "Mum")
        val apart = FaceGrouping.joinGroups(a + b, listOf(blocked, named))
        assertEquals(2, apart.size)
    }
}
