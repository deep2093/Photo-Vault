package com.deepak.photovault.ui.shell

import kotlin.math.cos
import kotlin.math.sin
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
        val (b, _) = FaceGrouping.group(listOf(base, glasses), emptyList(), 1)
        assertEquals(2, b.size)
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
}
