package com.deepak.photovault.core.crypto

/** Who a key belongs to. It is bound into every sealed key, so one person's envelope never opens as another's. */
data class KeyContext(val householdId: String, val memberId: String, val keyId: String = "v1") {
    internal fun aad(purpose: String): ByteArray = "pixallery/$purpose|$householdId|$memberId|$keyId".encodeToByteArray()
}

/**
 * The root key sealed under the recovery code (`keys/recovery-v1.bin` on the drive). Layout: version byte, 12-byte
 * nonce, AES-256-GCM sealed root key. The recovery code itself is never stored anywhere.
 */
object RecoveryEnvelope {
    private const val VERSION: Byte = 1
    private const val NONCE = 12

    private fun key(code: ByteArray) = Hkdf.derive(code, "recovery-wrap")

    fun wrap(root: ByteArray, code: ByteArray, context: KeyContext): ByteArray {
        val nonce = randomBytes(NONCE)
        return byteArrayOf(VERSION) + nonce + aesGcmSeal(key(code), nonce, root, context.aad("recovery"))
    }

    /** The root key, or null when the code is wrong, the context differs, or the envelope was changed. */
    fun unwrap(envelope: ByteArray, code: ByteArray, context: KeyContext): ByteArray? {
        if (envelope.size < 1 + NONCE + 16 || envelope[0] != VERSION) return null
        val nonce = envelope.copyOfRange(1, 1 + NONCE)
        return aesGcmOpen(key(code), nonce, envelope.copyOfRange(1 + NONCE, envelope.size), context.aad("recovery"))
    }
}

/**
 * The root key sealed under a PIN (14 Nov): the spare copy kept in the person's own Google Drive when they back up
 * to Google only (no family drive, so no recovery words there). Layout: version byte, 16-byte salt, 4-byte rounds,
 * 12-byte nonce, AES-256-GCM sealed root key. The PIN itself is never stored.
 */
object PinEnvelope {
    private const val VERSION: Byte = 1
    private const val SALT = 16
    private const val NONCE = 12

    /** About a second on a phone: each wrong guess costs the same. */
    const val ROUNDS = 600_000

    /** At least six characters; digits or letters. */
    const val MIN_LENGTH = 6

    /** Google backups belong to one person, whichever family: one fixed context for them. */
    val GOOGLE = KeyContext("google", "me")

    fun wrap(root: ByteArray, pin: String, context: KeyContext = GOOGLE, rounds: Int = ROUNDS): ByteArray {
        val salt = randomBytes(SALT)
        val nonce = randomBytes(NONCE)
        val key = pbkdf2Sha256(pin.toCharArray(), salt, rounds, 32)
        val r = byteArrayOf((rounds ushr 24).toByte(), (rounds ushr 16).toByte(), (rounds ushr 8).toByte(), rounds.toByte())
        return byteArrayOf(VERSION) + salt + r + nonce + aesGcmSeal(key, nonce, root, context.aad("pin"))
    }

    /** The root key, or null when the PIN is wrong or the file was changed. */
    fun unwrap(envelope: ByteArray, pin: String, context: KeyContext = GOOGLE): ByteArray? {
        val head = 1 + SALT + 4 + NONCE
        if (envelope.size < head + 16 || envelope[0] != VERSION) return null
        val salt = envelope.copyOfRange(1, 1 + SALT)
        var i = 1 + SALT
        val rounds = ((envelope[i].toInt() and 0xFF) shl 24) or ((envelope[i + 1].toInt() and 0xFF) shl 16) or
            ((envelope[i + 2].toInt() and 0xFF) shl 8) or (envelope[i + 3].toInt() and 0xFF)
        if (rounds < 1_000 || rounds > 10_000_000) return null
        i += 4
        val nonce = envelope.copyOfRange(i, i + NONCE)
        val key = pbkdf2Sha256(pin.toCharArray(), salt, rounds, 32)
        return aesGcmOpen(key, nonce, envelope.copyOfRange(head, envelope.size), context.aad("pin"))
    }
}
