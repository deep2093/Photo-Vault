package com.deepak.photovault.core.household

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

actual fun pbkdf2Sha256(pin: String, salt: ByteArray, iterations: Int, bytes: Int): ByteArray {
    val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, bytes * 8)
    try {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also { SecureRandom().nextBytes(it) }
