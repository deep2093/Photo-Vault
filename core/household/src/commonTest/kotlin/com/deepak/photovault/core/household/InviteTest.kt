package com.deepak.photovault.core.household

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InviteTest {
    private val payload = InvitePayload(
        id = "11111111-2222-3333-4444-555555555555",
        householdId = "h",
        expiresAtMillis = 1000,
        drive = InviteDrive("192.168.1.1", "usb", "Pixallery", "u", "secret-password"),
        wireGuard = "[Interface]\nPrivateKey = abc",
    )

    @Test
    fun rightCodeOpensItAndTheTextHidesEverything() {
        val text = Invite.lock(payload, "K7QD-M2XP")
        assertTrue(Invite.looksLikeInvite(text))
        assertFalse(text.contains("secret-password") || text.contains("192.168"))
        assertEquals(payload, Invite.unlock(text, "k7qd m2xp"))
    }

    @Test
    fun wrongCodeOrDamagedTextDoesNotOpen() {
        val text = Invite.lock(payload, "K7QD-M2XP")
        assertNull(Invite.unlock(text, "K7QD-M2XQ"))
        assertNull(Invite.unlock(text.dropLast(3) + "AAA", "K7QD-M2XP"))
        assertNull(Invite.unlock("hello", "K7QD-M2XP"))
    }

    @Test
    fun generatedCodesAreReadableAndDifferent() {
        val a = Invite.newCode()
        assertTrue(Regex("[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}").matches(a))
        assertTrue(a != Invite.newCode())
        assertNotNull(Invite.unlock(Invite.lock(payload, a), a))
    }
}
