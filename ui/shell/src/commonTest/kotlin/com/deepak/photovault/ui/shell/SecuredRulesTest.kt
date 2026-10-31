package com.deepak.photovault.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SecuredRulesTest {
    @Test
    fun pinNeedsSixDigitsThatAreNotEasy() {
        assertNull(SecuredRules.pinProblem("481926"))
        assertNotNull(SecuredRules.pinProblem("12345"))
        assertNotNull(SecuredRules.pinProblem("1234567"))
        assertNotNull(SecuredRules.pinProblem("12a456"))
        assertNotNull(SecuredRules.pinProblem("111111"))
        assertNotNull(SecuredRules.pinProblem("123456"))
    }

    @Test
    fun ownCodeNeedsTwelveCharactersOrFourDifferentWords() {
        assertNull(SecuredRules.codeProblem("blue kettle river mango"))
        assertNull(SecuredRules.codeProblem("Tr0ub4dor&3xyz!"))
        assertNotNull(SecuredRules.codeProblem("short one"))
        assertNotNull(SecuredRules.codeProblem("aaaaaaaaaaaaaaaa"))
        assertNotNull(SecuredRules.codeProblem("one one one one"))
        assertNotNull(SecuredRules.codeProblem("my password is long"))
    }

    @Test
    fun theSameCodeAlwaysReadsTheSame() {
        assertEquals("blue kettle river", SecuredRules.normalizedCode("  blue   kettle\nriver "))
    }

    @Test
    fun wrongPinsWaitLongerAndLonger() {
        assertEquals(0L, PinGuard.waitMillis(4))
        assertEquals(30_000L, PinGuard.waitMillis(5))
        assertEquals(60_000L, PinGuard.waitMillis(6))
        assertEquals(3_600_000L, PinGuard.waitMillis(12))
    }
}
