package com.deepak.photovault.core.crypto

/**
 * Small records (manifests, Recently deleted notes) sealed under a key from the person's root key. Layout: version
 * byte, 12-byte nonce, AES-256-GCM sealed bytes. Anyone without the key can neither read nor change them.
 */
object RecordSeal {
    private const val VERSION: Byte = 1
    private const val NONCE = 12

    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val nonce = randomBytes(NONCE)
        return byteArrayOf(VERSION) + nonce + aesGcmSeal(key, nonce, plain, aad)
    }

    /** The plain bytes, or null when the key or associated data differ, or the record was changed. */
    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? {
        if (sealed.size < 1 + NONCE + 16 || sealed[0] != VERSION) return null
        return aesGcmOpen(key, sealed.copyOfRange(1, 1 + NONCE), sealed.copyOfRange(1 + NONCE, sealed.size), aad)
    }
}
