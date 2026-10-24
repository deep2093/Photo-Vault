package com.deepak.photovault.core.household

/** PBKDF2-HMAC-SHA256. Android: javax.crypto. */
expect fun pbkdf2Sha256(pin: String, salt: ByteArray, iterations: Int, bytes: Int): ByteArray

/** Cryptographically strong random bytes. Android: SecureRandom. */
expect fun secureRandomBytes(count: Int): ByteArray

/** AES-256-GCM seal: returns ciphertext with the 16-byte tag at the end. Android: javax.crypto. */
expect fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plain: ByteArray): ByteArray

/** The plain bytes, or null when the key is wrong or the data was changed. */
expect fun aesGcmOpen(key: ByteArray, nonce: ByteArray, sealed: ByteArray): ByteArray?

object OwnerPin {
    const val ITERATIONS = 120_000
    const val MIN_DIGITS = 4
    const val MAX_DIGITS = 8

    fun isValid(pin: String): Boolean = pin.length in MIN_DIGITS..MAX_DIGITS && pin.all { it.isDigit() }

    fun hash(pin: String): PinHash {
        require(isValid(pin)) { "PIN must be 4 to 8 digits" }
        val salt = secureRandomBytes(16)
        return PinHash(salt.toHex(), pbkdf2Sha256(pin, salt, ITERATIONS, 32).toHex(), ITERATIONS)
    }

    fun matches(pin: String, stored: PinHash): Boolean {
        if (!isValid(pin)) return false
        val actual = pbkdf2Sha256(pin, stored.saltHex.fromHex(), stored.iterations, 32)
        val expected = stored.hashHex.fromHex()
        // Constant-time compare.
        if (actual.size != expected.size) return false
        var diff = 0
        for (i in actual.indices) diff = diff or (actual[i].toInt() xor expected[i].toInt())
        return diff == 0
    }
}

/**
 * Wrong PINs on this phone: after 5 in a row, wait 30 seconds, doubling each further miss up to 15 minutes.
 * Kept per phone and in memory of the caller; pure so it is testable.
 */
class PinAttempts(private val now: () -> Long) {
    private var failures = 0
    private var lockedUntil = 0L

    fun lockedForMillis(): Long = (lockedUntil - now()).coerceAtLeast(0)

    fun recordFailure() {
        failures++
        if (failures >= 5) {
            val wait = minOf(30_000L shl (failures - 5).coerceAtMost(5), 15 * 60_000L)
            lockedUntil = now() + wait
        }
    }

    fun recordSuccess() {
        failures = 0
        lockedUntil = 0
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

internal fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

