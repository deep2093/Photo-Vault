package com.deepak.photovault.core.crypto

/** HKDF-SHA256 (RFC 5869). Every key in the app comes from one root with its own purpose text, so keys never overlap. */
object Hkdf {
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * 32)) { "length" }
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var filled = 0
        var counter = 1
        while (filled < length) {
            previous = hmacSha256(prk, previous + info + byteArrayOf(counter.toByte()))
            val take = minOf(previous.size, length - filled)
            previous.copyInto(out, filled, 0, take)
            filled += take
            counter++
        }
        return out
    }

    fun derive(ikm: ByteArray, purpose: String, length: Int = 32): ByteArray =
        derive(ikm, "pixallery-v1".encodeToByteArray(), purpose.encodeToByteArray(), length)
}
