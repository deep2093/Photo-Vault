package com.deepak.photovault.core.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryChannel(private var data: ByteArray) : SeekableByteChannel {
    private var pos = 0L
    override fun isOpen() = true
    override fun close() = Unit
    override fun read(dst: ByteBuffer): Int {
        if (pos >= data.size) return -1
        val n = minOf(dst.remaining(), (data.size - pos).toInt())
        dst.put(data, pos.toInt(), n)
        pos += n
        return n
    }
    override fun write(src: ByteBuffer): Int = throw UnsupportedOperationException()
    override fun position() = pos
    override fun position(newPosition: Long): SeekableByteChannel { pos = newPosition; return this }
    override fun size() = data.size.toLong()
    override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException()
}

class CryptoAndroidTest {
    private val context = KeyContext("house-1", "sam-3f9a1b2c")
    private val keys = MemberKeys.generate()
    private val aad = MemberKeys.objectAad(context, "obj-1")

    private fun encrypt(plain: ByteArray, key: ByteArray = keys.objectKey("obj-1"), with: ByteArray = aad): ByteArray {
        val out = ByteArrayOutputStream()
        ObjectCipher.encrypt(out, key, with).use { it.write(plain) }
        return out.toByteArray()
    }

    private fun decrypt(cipher: ByteArray, key: ByteArray = keys.objectKey("obj-1"), with: ByteArray = aad): ByteArray =
        ObjectCipher.decrypt(ByteArrayInputStream(cipher), key, with).use { it.readBytes() }

    @Test
    fun smallAndMultiSegmentObjectsRoundTrip() {
        for (size in listOf(0, 1, 1000, ObjectCipher.SEGMENT_BYTES - 40, ObjectCipher.SEGMENT_BYTES, 2_600_000)) {
            val plain = Random(size).nextBytes(size)
            val cipher = encrypt(plain)
            assertContentEquals(plain, decrypt(cipher), "size $size")
        }
    }

    @Test
    fun ciphertextHidesTheContent() {
        val plain = ByteArray(5000) { 7 }
        val cipher = encrypt(plain)
        assertFalse(cipher.toList().windowed(64).any { w -> w.all { it == 7.toByte() } })
    }

    @Test
    fun anyChangeFailsClosed() {
        val plain = Random(1).nextBytes(2_600_000)
        val cipher = encrypt(plain)
        // Flip one byte in the header, the first, a middle and the last segment.
        for (at in listOf(3, 100, 1_500_000, cipher.size - 5)) {
            val bad = cipher.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
            assertFailsWith<IOException>("byte $at") { decrypt(bad) }
        }
        // Cut short (a dropped last segment) and padded.
        assertFailsWith<IOException> { decrypt(cipher.copyOf(cipher.size - ObjectCipher.SEGMENT_BYTES)) }
        assertFailsWith<IOException> { decrypt(cipher.copyOf(cipher.size - 1)) }
        // Wrong key, wrong owner, wrong object name.
        assertFailsWith<IOException> { decrypt(cipher, key = MemberKeys.generate().objectKey("obj-1")) }
        assertFailsWith<IOException> { decrypt(cipher, with = MemberKeys.objectAad(context.copy(memberId = "alex-11111111"), "obj-1")) }
        assertFailsWith<IOException> { decrypt(cipher, with = MemberKeys.objectAad(context, "obj-2")) }
    }

    @Test
    fun swappedSegmentsAreCaught() {
        // The first segment holds the header, so segments 2 and 3 are plain full-size blocks: [s, 2s) and [2s, 3s).
        val s = ObjectCipher.SEGMENT_BYTES
        val cipher = encrypt(Random(2).nextBytes(3 * s))
        val swapped = cipher.copyOf()
        cipher.copyOfRange(2 * s, 3 * s).copyInto(swapped, s)
        cipher.copyOfRange(s, 2 * s).copyInto(swapped, 2 * s)
        assertFailsWith<IOException> { decrypt(swapped) }
    }

    @Test
    fun seekingIntoTheMiddleWorks() {
        val plain = Random(3).nextBytes(3_000_000)
        val channel = ObjectCipher.decryptSeekable(MemoryChannel(encrypt(plain)), keys.objectKey("obj-1"), aad)
        channel.position(2_345_678)
        val buf = ByteBuffer.allocate(1000)
        var got = 0
        while (got < 1000) { val n = channel.read(buf); if (n < 0) break; got += n }
        assertEquals(1000, got)
        assertContentEquals(plain.copyOfRange(2_345_678, 2_346_678), buf.array())
    }

    @Test
    fun identityComesBackFromTheSameRootAndSignsManifests() {
        val a = MemberIdentity(keys)
        val b = MemberIdentity(MemberKeys(keys.root.copyOf()))
        assertContentEquals(a.signingPublic, b.signingPublic)
        assertContentEquals(a.exchangePublic, b.exchangePublic)
        assertEquals(a.fingerprint, b.fingerprint)
        assertEquals(5, a.fingerprint.split(" ").size)
        val manifest = "manifest rev 7".encodeToByteArray()
        val sig = a.sign(manifest)
        assertTrue(MemberIdentity.verify(a.signingPublic, manifest, sig))
        assertFalse(MemberIdentity.verify(a.signingPublic, "manifest rev 8".encodeToByteArray(), sig))
        assertFalse(MemberIdentity.verify(MemberIdentity(MemberKeys.generate()).signingPublic, manifest, sig))
    }

    @Test
    fun theRecoveryHelperCanHandBackTheKeyAndNobodyElseCan() {
        val helper = MemberIdentity(MemberKeys.generate())
        val owner = MemberIdentity(MemberKeys.generate())
        val wrapped = HelperWrap.wrap(keys.root, helper.exchangePublic, context)
        assertContentEquals(keys.root, HelperWrap.unwrap(wrapped, helper, context))
        assertNull(HelperWrap.unwrap(wrapped, owner, context), "the household owner is not the helper")
        assertNull(HelperWrap.unwrap(wrapped, helper, context.copy(memberId = "alex-11111111")))
        val bad = wrapped.copyOf().also { it[60] = (it[60].toInt() xor 1).toByte() }
        assertNull(HelperWrap.unwrap(bad, helper, context))
    }

    @Test
    fun fullRecoveryOnAFreshPhone() {
        val code = RecoveryCode.generate()
        val envelope = RecoveryEnvelope.wrap(keys.root, code, context)
        val cipher = encrypt("a photo".encodeToByteArray())
        // New phone: only the words, the envelope from the drive and the object from the drive.
        val typed = RecoveryCode.toWords(code).joinToString(" ")
        val parsed = RecoveryCode.parse(typed) as RecoveryCode.Parsed.Ok
        val root = RecoveryEnvelope.unwrap(envelope, parsed.secret, context)!!
        assertEquals("a photo", decrypt(cipher, key = MemberKeys(root).objectKey("obj-1")).decodeToString())
    }
}
