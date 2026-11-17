package com.deepak.photovault.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasswordStrengthTest {
    @Test
    fun sixDigitPinsAreNoLongerEnough() {
        // A2 (18 Nov): a 6-digit PIN could be guessed in hours on a fast computer.
        assertEquals(PasswordStrength.Level.TOO_SHORT, PasswordStrength.level("482913"))
        assertFalse(PasswordStrength.acceptable("482913"))
        // Eight or ten random digits are still too few.
        assertFalse(PasswordStrength.acceptable("48291375"))
        assertFalse(PasswordStrength.acceptable("4829137561"))
    }

    @Test
    fun twelveRandomDigitsOrAMixOfEightAreEnough() {
        assertTrue(PasswordStrength.acceptable("481920573618"))
        assertTrue(PasswordStrength.acceptable("sunny42!"))
        assertTrue(PasswordStrength.acceptable("Kites over Mt Buller"))
        assertEquals(PasswordStrength.Level.STRONG, PasswordStrength.level("Kites over Mt Buller"))
    }

    @Test
    fun repeatsRunsAndCommonWordsAreWeak() {
        listOf("11111111", "aaaaaaaaaaaa", "12345678", "abcdefghij", "98765432", "password1", "Password2026", "qwertyuiop", "121212121212")
            .forEach { assertFalse(PasswordStrength.acceptable(it), it) }
    }

    @Test
    fun emptyIsNothing() {
        assertEquals(0.0, PasswordStrength.bits(""))
        assertEquals(PasswordStrength.Level.TOO_SHORT, PasswordStrength.level(""))
    }
}
