package com.deepak.photovault.core.crypto

/** Strong random bytes. Android: SecureRandom. */
expect fun randomBytes(count: Int): ByteArray

expect fun sha256(data: ByteArray): ByteArray

expect fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

/** AES-256-GCM with associated data; the 16-byte tag is at the end of the result. */
expect fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray

/** The plain bytes, or null when the key, nonce, data or associated data do not match. */
expect fun aesGcmOpen(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray?
