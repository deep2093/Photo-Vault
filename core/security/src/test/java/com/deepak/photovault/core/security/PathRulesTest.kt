package com.deepak.photovault.core.security

import org.junit.Assert.assertEquals
import org.junit.Test

class PathRulesTest {
    @Test fun normalisesSlashesAndEdges() {
        assertEquals("", PathRules.normalizeRoot("  "))
        assertEquals("PhotoVault", PathRules.normalizeRoot("/PhotoVault/"))
        assertEquals("Media/PhotoVault", PathRules.normalizeRoot("\\Media\\PhotoVault"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsParentSegments() {
        PathRules.normalizeRoot("PhotoVault/../other")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDotSegments() {
        PathRules.normalizeRoot("PhotoVault/./x")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptySegments() {
        PathRules.normalizeRoot("PhotoVault//x")
    }
}
