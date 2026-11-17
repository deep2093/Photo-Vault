package com.deepak.photovault.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KeyRequestCodeTest {
    @Test
    fun bothPhonesShowTheSameSixDigitsForOneRequest() {
        val key = ByteArray(32) { it.toByte() }
        val code = KeyRequestCode.of(key)
        assertEquals(code, KeyRequestCode.of(key.copyOf()))
        assertTrue(Regex("\\d{3} \\d{3}").matches(code), code)
    }

    @Test
    fun anotherRequestShowsAnotherCode() {
        val a = KeyRequestCode.of(ByteArray(32) { it.toByte() })
        val b = KeyRequestCode.of(ByteArray(32) { (it + 1).toByte() })
        assertNotEquals(a, b)
    }
}
