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
}
