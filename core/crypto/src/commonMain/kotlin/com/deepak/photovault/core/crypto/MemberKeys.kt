package com.deepak.photovault.core.crypto

/**
 * One person's root key and every key derived from it. The root is 32 random bytes made on the person's first
 * phone; it is never written to the drive in the clear (docs/ENCRYPTION_PLAN.md).
 */
class MemberKeys(val root: ByteArray) {
    init { require(root.size == 32) { "A root key is 32 bytes" } }

    /** The key for one photo or video; a different object always gets a different key. */
    fun objectKey(objectId: String): ByteArray = Hkdf.derive(root, "object|$objectId")

    /** Key for small sealed records (manifests, thumbnails index). */
    fun recordKey(purpose: String): ByteArray = Hkdf.derive(root, "record|$purpose")

    internal fun signingSeed(): ByteArray = Hkdf.derive(root, "identity-signing")
    internal fun exchangeSeed(): ByteArray = Hkdf.derive(root, "identity-exchange")

    companion object {
        fun generate() = MemberKeys(randomBytes(32))

        /** What a stored object is bound to, so a copied or swapped object fails to decrypt. */
        fun objectAad(context: KeyContext, objectId: String, formatVersion: Int = 1): ByteArray =
            "pixallery/object|${context.householdId}|${context.memberId}|$objectId|v$formatVersion".encodeToByteArray()
    }
}
