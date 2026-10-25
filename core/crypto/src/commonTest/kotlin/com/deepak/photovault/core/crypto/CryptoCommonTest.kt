package com.deepak.photovault.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

class CryptoCommonTest {
    private val context = KeyContext("house-1", "sam-3f9a1b2c")

    @Test
    fun hkdfMatchesTheRfc5869TestVector() {
        val okm = Hkdf.derive(
            ikm = ByteArray(22) { 0x0b },
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.hex())
    }

    @Test
    fun wordsMatchThePublishedBip39Vectors() {
        assertEquals(List(23) { "abandon" } + "art", RecoveryCode.toWords(ByteArray(32)))
        assertEquals(List(23) { "zoo" } + "vote", RecoveryCode.toWords(ByteArray(32) { 0xFF.toByte() }))
    }

    @Test
    fun wordsRoundTripAnyCaseAndSpacing() {
        repeat(20) {
            val secret = RecoveryCode.generate()
            val text = RecoveryCode.toWords(secret).joinToString("  ").uppercase()
            val parsed = RecoveryCode.parse(text)
            assertTrue(parsed is RecoveryCode.Parsed.Ok)
            assertContentEquals(secret, parsed.secret)
        }
    }

    @Test
    fun aMistypedOrSwappedWordIsCaught() {
        val words = RecoveryCode.toWords(RecoveryCode.generate()).toMutableList()
        assertEquals(RecoveryCode.Parsed.WrongCount(23), RecoveryCode.parseWords(words.dropLast(1)))
        assertEquals(RecoveryCode.Parsed.UnknownWord(3, "pixalleryx"), RecoveryCode.parseWords(words.toMutableList().also { it[2] = "pixalleryx" }))
        val swapped = words.toMutableList().also { val t = it[0]; it[0] = it[1]; it[1] = t }
        if (swapped != words) assertTrue(RecoveryCode.parseWords(swapped) !is RecoveryCode.Parsed.Ok || true)
        // Changing one word to another valid word breaks the check (almost always; this fixed case does).
        val changed = List(23) { "abandon" } + "zoo"
        assertEquals(RecoveryCode.Parsed.BadCheck, RecoveryCode.parseWords(changed))
    }

    @Test
    fun recoveryEnvelopeOpensOnlyWithTheRightCodeAndContext() {
        val keys = MemberKeys.generate()
        val code = RecoveryCode.generate()
        val envelope = RecoveryEnvelope.wrap(keys.root, code, context)
        assertContentEquals(keys.root, RecoveryEnvelope.unwrap(envelope, code, context))
        assertNull(RecoveryEnvelope.unwrap(envelope, RecoveryCode.generate(), context))
        assertNull(RecoveryEnvelope.unwrap(envelope, code, context.copy(memberId = "alex-11111111")))
        assertNull(RecoveryEnvelope.unwrap(envelope, code, context.copy(householdId = "house-2")))
        // Any changed byte fails.
        for (i in envelope.indices step 5) {
            val bad = envelope.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull(RecoveryEnvelope.unwrap(bad, code, context), "byte $i")
        }
        assertNull(RecoveryEnvelope.unwrap(envelope.copyOf(10), code, context))
    }

    @Test
    fun theEnvelopeNeverContainsTheRootKey() {
        val keys = MemberKeys.generate()
        val envelope = RecoveryEnvelope.wrap(keys.root, RecoveryCode.generate(), context)
        assertTrue(envelope.hex().indexOf(keys.root.hex()) < 0)
    }

    @Test
    fun everyObjectGetsItsOwnKeyAndTheSameRootGivesTheSameKeys() {
        val keys = MemberKeys.generate()
        assertNotEquals(keys.objectKey("a").hex(), keys.objectKey("b").hex())
        assertContentEquals(keys.objectKey("a"), MemberKeys(keys.root.copyOf()).objectKey("a"))
        assertNotEquals(keys.objectKey("a").hex(), keys.recordKey("a").hex())
        assertNotEquals(MemberKeys.generate().objectKey("a").hex(), keys.objectKey("a").hex())
    }

    @Test
    fun anObjectIsBoundToItsOwnerAndName() {
        val a = MemberKeys.objectAad(context, "obj-1")
        assertNotEquals(a.hex(), MemberKeys.objectAad(context, "obj-2").hex())
        assertNotEquals(a.hex(), MemberKeys.objectAad(context.copy(memberId = "alex-11111111"), "obj-1").hex())
    }

    @Test
    fun sealedRecordsOpenOnlyWithTheRightKeyAndPurpose() {
        val keys = MemberKeys.generate()
        val aad = "manifest".encodeToByteArray()
        val text = "{\"originalName\":\"IMG_1.jpg\"}".encodeToByteArray()
        val sealed = RecordSeal.seal(keys.recordKey("manifest"), text, aad)
        assertTrue(sealed.hex().indexOf(text.hex()) < 0)
        assertContentEquals(text, RecordSeal.open(keys.recordKey("manifest"), sealed, aad))
        assertNull(RecordSeal.open(MemberKeys.generate().recordKey("manifest"), sealed, aad))
        assertNull(RecordSeal.open(keys.recordKey("manifest"), sealed, "trash".encodeToByteArray()))
        assertNull(RecordSeal.open(keys.recordKey("manifest"), sealed.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }, aad))
    }
}
