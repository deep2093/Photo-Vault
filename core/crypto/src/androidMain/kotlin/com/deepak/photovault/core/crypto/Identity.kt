package com.deepak.photovault.core.crypto

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.subtle.X25519

/**
 * A person's public identity, derived from the root key so it comes back on a new phone with the recovery code:
 * an Ed25519 key that signs manifests and changes, and an X25519 key others can seal a key to (the recovery helper).
 */
class MemberIdentity(keys: MemberKeys) {
    private val signing = Ed25519Sign.KeyPair.newKeyPairFromSeed(keys.signingSeed())
    private val exchangePrivate: ByteArray = keys.exchangeSeed()

    val signingPublic: ByteArray get() = signing.publicKey
    val exchangePublic: ByteArray get() = X25519.publicFromPrivate(exchangePrivate)

    fun sign(data: ByteArray): ByteArray = Ed25519Sign(signing.privateKey).sign(data)

    /** Short text to compare out loud or on screen when checking someone's identity: ten groups of four. */
    val fingerprint: String get() = fingerprintOf(signingPublic)

    internal fun sharedSecretWith(peerPublic: ByteArray): ByteArray = X25519.computeSharedSecret(exchangePrivate, peerPublic)

    companion object {
        fun verify(signingPublic: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
            Ed25519Verify(signingPublic).verify(signature, data); true
        } catch (_: java.security.GeneralSecurityException) { false }

        fun fingerprintOf(signingPublic: ByteArray): String =
            sha256(signingPublic).copyOf(10).joinToString("") { "%02x".format(it) }.chunked(4).joinToString(" ")
    }
}

/**
 * The optional recovery helper: the root key sealed to one trusted family member's public key, so that person (and
 * nobody else, not the owner) can hand it back. Layout: version byte, 32-byte ephemeral public key, 12-byte nonce,
 * sealed root key.
 */
object HelperWrap {
    private const val VERSION: Byte = 1

    private fun key(shared: ByteArray, ephemeralPublic: ByteArray, helperPublic: ByteArray) =
        Hkdf.derive(shared + ephemeralPublic + helperPublic, "helper-wrap")

    fun wrap(root: ByteArray, helperExchangePublic: ByteArray, context: KeyContext): ByteArray {
        val ephemeral = X25519.generatePrivateKey()
        val ephemeralPublic = X25519.publicFromPrivate(ephemeral)
        val shared = X25519.computeSharedSecret(ephemeral, helperExchangePublic)
        val nonce = randomBytes(12)
        return byteArrayOf(VERSION) + ephemeralPublic + nonce +
            aesGcmSeal(key(shared, ephemeralPublic, helperExchangePublic), nonce, root, context.aad("helper"))
    }

    /** [helper] opens a wrap made for it. Null when it was made for someone else or was changed. */
    fun unwrap(wrapped: ByteArray, helper: MemberIdentity, context: KeyContext): ByteArray? {
        if (wrapped.size < 1 + 32 + 12 + 16 || wrapped[0] != VERSION) return null
        val ephemeralPublic = wrapped.copyOfRange(1, 33)
        val nonce = wrapped.copyOfRange(33, 45)
        val shared = runCatching { helper.sharedSecretWith(ephemeralPublic) }.getOrNull() ?: return null
        return aesGcmOpen(key(shared, ephemeralPublic, helper.exchangePublic), nonce, wrapped.copyOfRange(45, wrapped.size), context.aad("helper"))
    }
}
