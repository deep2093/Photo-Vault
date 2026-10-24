package com.deepak.photovault.core.household

import com.deepak.photovault.core.drive.MemoryDrive
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpaceRequestsTest {
    private val drive = MemoryDrive()
    private var clock = 1_000L
    private fun store() = HouseholdStore(drive) { clock++ }

    @Test
    fun askGiveOrDecline() = runTest {
        val h = store().create("Deepak", "owner-phone-0001", "S26", "2468")
        store().updateSettings(OwnerAuth("owner-phone-0001", "2468")) { it.copy(approveNewPhones = false) }
        val sam = (store().requestJoin("sam-phone-00001", "Pixel", newName = "Sam") as Membership.Joined).me
        val requests = SpaceRequests(drive) { clock++ }
        assertFailsWith<IllegalArgumentException> { requests.ask(sam, sam.limitGb) }
        requests.ask(sam, 200, "Holiday videos")
        var family = store().load()!!
        assertEquals(listOf(200), requests.open(family).map { it.wantGb })
        // Declined: gone from the owner's list; Sam sees it was declined.
        requests.decline(family, requests.open(family).single())
        assertTrue(requests.open(family).isEmpty())
        assertTrue(requests.declined(family, sam.userId))
        // Asking again opens a new request; giving that much closes it by itself.
        requests.ask(sam, 300)
        assertEquals(1, requests.open(family).size)
        store().setLimit(OwnerAuth("owner-phone-0001", "2468"), sam.userId, 300)
        family = store().load()!!
        assertTrue(requests.open(family).isEmpty())
        assertTrue(drive.exists("users/${sam.userId}/requests/space.json"))
        assertTrue(h.ownerUserId != sam.userId)
    }
}
