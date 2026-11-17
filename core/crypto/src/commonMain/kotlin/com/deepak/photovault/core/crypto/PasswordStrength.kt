package com.deepak.photovault.core.crypto

import kotlin.math.ln
import kotlin.math.min

/**
 * How hard a password is to guess (18 Nov, A2). The spare key in Google Drive is locked by a password; anyone inside
 * that Google account could copy the locked key and try passwords on a fast computer. Each try costs about a second's
 * work on a phone ([PinEnvelope.ROUNDS]), so about 40 bits of guessing (twelve random digits, or eight letters, numbers
 * and a symbol) keeps a fast computer busy for years. A rough estimate, deliberately strict about repeats, runs and
 * common words. Pure, so it is tested without a device.
 */
object PasswordStrength {
    enum class Level { TOO_SHORT, WEAK, GOOD, STRONG }

    const val MIN_LENGTH = 8

    private const val ENOUGH_BITS = 39.5
    private const val STRONG_BITS = 60.0

    private val COMMON = listOf(
        "password", "passw0rd", "qwerty", "123456", "654321", "abcdef", "letmein", "iloveyou", "admin", "welcome",
        "pixallery", "photos", "111111", "000000", "monkey", "dragon", "sunshine", "princess",
    )

    /** Roughly how many bits of guessing [password] takes. */
    fun bits(password: String): Double {
        if (password.isEmpty()) return 0.0
        var pool = 0
        if (password.any { it.isDigit() }) pool += 10
        if (password.any { it.isLowerCase() }) pool += 26
        if (password.any { it.isUpperCase() }) pool += 26
        if (password.any { !it.isLetterOrDigit() }) pool += 33
        var bits = password.length * ln(pool.toDouble().coerceAtLeast(2.0)) / ln(2.0)
        // Repeats and runs (aaaa, 1212, 1234, abcd) add little.
        val distinct = password.toSet().size
        if (distinct <= 2) bits = min(bits, 10.0) else if (distinct * 2 < password.length) bits *= 0.6
        if (isRun(password)) bits *= 0.4
        val lower = password.lowercase()
        if (COMMON.any { it in lower }) bits *= 0.5
        return bits
    }

    fun level(password: String): Level = when {
        password.length < MIN_LENGTH -> Level.TOO_SHORT
        bits(password) < ENOUGH_BITS -> Level.WEAK
        bits(password) < STRONG_BITS -> Level.GOOD
        else -> Level.STRONG
    }

    /** Good enough to lock the spare key with. */
    fun acceptable(password: String): Boolean = level(password) >= Level.GOOD

    /** Every character one up (or one down) from the one before: 12345678, abcdefgh, 98765432. */
    private fun isRun(p: String): Boolean {
        if (p.length < 4) return false
        val up = (1 until p.length).all { p[it].code - p[it - 1].code == 1 }
        val down = (1 until p.length).all { p[it].code - p[it - 1].code == -1 }
        return up || down
    }
}
