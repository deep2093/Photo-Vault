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
