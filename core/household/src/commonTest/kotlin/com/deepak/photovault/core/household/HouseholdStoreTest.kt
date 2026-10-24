package com.deepak.photovault.core.household

import com.deepak.photovault.core.drive.DriveFileSystem
import com.deepak.photovault.core.drive.MemoryDrive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val OWNER_PHONE = "owner-phone-0001"
private const val SAM_PHONE = "sam-phone-00001"
private const val TABLET = "owner-tablet-001"

class HouseholdStoreTest {
    private var clock = 1_000L
    private val drive = MemoryDrive()
    private fun store(d: DriveFileSystem = drive) = HouseholdStore(d) { clock++ }

    private suspend fun ownerSetUp(pin: String? = "2468"): Household = store().create("Deepak", OWNER_PHONE, "Galaxy S26", pin)

    @Test
    fun firstPhoneCreatesTheFamilyAsOwnerWithOwnFolders() = runTest {
        val h = ownerSetUp()
        assertEquals("Deepak", h.owner?.name)
        assertEquals(DEFAULT_LIMIT_GB, h.owner?.limitGb)
        assertTrue(h.settings.requirePin && h.settings.approveNewPhones)
        HouseholdStore.USER_FOLDERS.forEach { assertTrue(drive.exists("users/${h.ownerUserId}/$it")) }
        assertIs<Membership.Joined>(store().membership(OWNER_PHONE)).also { assertTrue(it.isOwner) }
        assertFailsWith<AlreadySetUp> { store().create("Someone", "other-phone-0001", "Pixel", null) }
    }

    @Test
    fun newPhoneWaitsForApprovalThenGetsItsOwnFolder() = runTest {
        ownerSetUp()
        val pending = store().requestJoin(SAM_PHONE, "Pixel 9", newName = "Sam")
        assertIs<Membership.Pending>(pending)
        assertEquals(1, store().pendingRequests().size)

        // Wrong PIN or the wrong phone can't approve.
        val request = store().pendingRequests().single()
        assertFailsWith<NotAllowed> { store().approve(OwnerAuth(OWNER_PHONE, "0000"), request) }
        assertFailsWith<NotAllowed> { store().approve(OwnerAuth(SAM_PHONE, "2468"), request) }

        val h = store().approve(OwnerAuth(OWNER_PHONE, "2468"), request)
        val sam = h.members.single { it.name == "Sam" }
        assertEquals(DEFAULT_LIMIT_GB, sam.limitGb)
        assertTrue(drive.exists("users/${sam.userId}/albums"))
        assertTrue(store().pendingRequests().isEmpty())
        assertIs<Membership.Joined>(store().membership(SAM_PHONE))
    }

    @Test
    fun declineRemovesTheRequest() = runTest {
        ownerSetUp()
        store().requestJoin(SAM_PHONE, "Pixel 9", newName = "Sam")
        store().decline(OwnerAuth(OWNER_PHONE, "2468"), store().pendingRequests().single())
        assertIs<Membership.NotJoined>(store().membership(SAM_PHONE))
    }

    @Test
    fun withApprovalOffPhonesJoinStraightAway() = runTest {
        ownerSetUp()
        store().updateSettings(OwnerAuth(OWNER_PHONE, "2468")) { it.copy(approveNewPhones = false) }
        assertIs<Membership.Joined>(store().requestJoin(SAM_PHONE, "Pixel 9", newName = "Sam"))
    }

    @Test
    fun aLostOwnersNewPhoneJoinsAsOwnerWithThePin() = runTest {
        val h = ownerSetUp()
        val newPhone = "owner-new-phone-1"
        // Wrong PIN: refused, nothing waiting.
        assertFailsWith<NotAllowed> { store().requestJoin(newPhone, "Galaxy S27", existingUserId = h.ownerUserId, ownerPin = "0000") }
        // Right PIN: joins at once even though approval is on, and can act as owner, e.g. remove the lost phone.
        val joined = assertIs<Membership.Joined>(store().requestJoin(newPhone, "Galaxy S27", existingUserId = h.ownerUserId, ownerPin = "2468"))
        assertTrue(joined.isOwner)
        val after = store().removeDevice(OwnerAuth(newPhone, "2468"), OWNER_PHONE)
        assertEquals(listOf(newPhone), after.owner!!.devices.map { it.deviceId })
    }

    @Test
    fun nobodyJoinsAsTheOwnerUnapprovedWithoutThePin() = runTest {
        val h = ownerSetUp()
        store().updateSettings(OwnerAuth(OWNER_PHONE, "2468")) { it.copy(approveNewPhones = false) }
        // Others still join freely, but picking the owner's name only asks to join.
        assertIs<Membership.Pending>(store().requestJoin("stranger-phone-1", "Pixel", existingUserId = h.ownerUserId))
        assertIs<Membership.Joined>(store().requestJoin(SAM_PHONE, "Pixel 9", newName = "Sam"))
    }

    @Test
    fun aSecondPhoneOfTheSamePersonSharesTheirFolder() = runTest {
        val h = ownerSetUp(pin = null)
        val joined = store().requestJoin(TABLET, "Tab S10", existingUserId = h.ownerUserId)
        // No PIN set means approval still applies, but the owner can approve without one.
        assertIs<Membership.Pending>(joined)
        store().approve(OwnerAuth(OWNER_PHONE, null), store().pendingRequests().single())
        val me = (store().membership(TABLET) as Membership.Joined).me
        assertEquals(h.ownerUserId, me.userId)
        assertEquals(2, me.devices.size)
    }

    @Test
    fun limitsChangeOnlyWithThePinAndStayInRange() = runTest {
        ownerSetUp()
        val owner = OwnerAuth(OWNER_PHONE, "2468")
        val h = store().setLimit(owner, store().load()!!.ownerUserId, 250)
        assertEquals(250, h.owner?.limitGb)
        assertFailsWith<NotAllowed> { store().setLimit(OwnerAuth(OWNER_PHONE, "1111"), h.ownerUserId, 50) }
        assertFailsWith<IllegalArgumentException> { store().setLimit(owner, h.ownerUserId, 0) }
    }

    @Test
    fun turningThePinOffLetsTheOwnerActWithoutIt() = runTest {
        ownerSetUp()
        store().updateSettings(OwnerAuth(OWNER_PHONE, "2468")) { it.copy(requirePin = false) }
        store().setLimit(OwnerAuth(OWNER_PHONE, null), store().load()!!.ownerUserId, 50)
        assertEquals(50, store().load()!!.owner?.limitGb)
    }

    @Test
    fun removedPersonKeepsTheirPhotosButLosesMembership() = runTest {
        ownerSetUp()
        store().updateSettings(OwnerAuth(OWNER_PHONE, "2468")) { it.copy(approveNewPhones = false) }
        val sam = (store().requestJoin(SAM_PHONE, "Pixel 9", newName = "Sam") as Membership.Joined).me
        drive.createNew("users/${sam.userId}/albums/a.jpg", ByteArray(10))
        store().removeMember(OwnerAuth(OWNER_PHONE, "2468"), sam.userId)
        assertIs<Membership.Removed>(store().membership(SAM_PHONE, rememberedUserId = sam.userId))
        assertTrue(drive.exists("users/${sam.userId}/albums/a.jpg"))
        assertFailsWith<IllegalArgumentException> { store().removeMember(OwnerAuth(OWNER_PHONE, "2468"), store().load()!!.ownerUserId) }
    }

    @Test
    fun profilePhotosEachPersonOwnAndTheOwnerAnyones() = runTest {
        ownerSetUp()
        store().updateSettings(OwnerAuth(OWNER_PHONE, "2468")) { it.copy(approveNewPhones = false) }
        val sam = (store().requestJoin(SAM_PHONE, "Pixel 9", newName = "Sam") as Membership.Joined).me
        val owner = store().load()!!.ownerUserId
        // Sam sets his own.
        val first = store().setPhoto(SAM_PHONE, sam.userId, byteArrayOf(1, 2, 3)).member(sam.userId)!!.photo!!
        assertTrue(first.startsWith("system/people/"))
        assertEquals(listOf<Byte>(1, 2, 3), store().readPhoto(first).toList())
        // Sam can't change the owner's.
        assertFailsWith<NotAllowed> { store().setPhoto(SAM_PHONE, owner, byteArrayOf(9)) }
        // The owner changes Sam's with the PIN; the old picture is removed, never overwritten.
        assertFailsWith<NotAllowed> { store().setPhoto(OWNER_PHONE, sam.userId, byteArrayOf(4), pin = "0000") }
        val second = store().setPhoto(OWNER_PHONE, sam.userId, byteArrayOf(4), pin = "2468").member(sam.userId)!!.photo!!
        assertTrue(second != first)
        assertTrue(!drive.exists(first))
        store().clearPhoto(SAM_PHONE, sam.userId)
        assertNull(store().load()!!.member(sam.userId)!!.photo)
        assertFailsWith<IllegalArgumentException> { store().readPhoto("users/$owner/albums/a.jpg") }
    }

    @Test
    fun privatePicturesLiveInTheViewersOwnFolderAndReplaceOlderOnes() = runTest {
        val h = ownerSetUp()
        val owner = h.ownerUserId
        val viewer = "viewer-user-0001"
        val first = store().setPrivatePhoto(viewer, owner, byteArrayOf(1))
        assertTrue(first.startsWith("users/$viewer/people/"))
        val second = store().setPrivatePhoto(viewer, owner, byteArrayOf(2))
        assertEquals(mapOf(owner to second), store().privatePhotos(viewer))
        assertTrue(!drive.exists(first))
        assertEquals(listOf<Byte>(2), store().readPrivatePhoto(viewer, second).toList())
        // Nobody else's view changes, and the shared picture is untouched.
        assertTrue(store().privatePhotos(owner).isEmpty())
        assertNull(store().load()!!.owner!!.photo)
        store().clearPrivatePhoto(viewer, owner)
        assertTrue(store().privatePhotos(viewer).isEmpty())
    }

    @Test
    fun aPhoneUpdatesItsOwnName() = runTest {
        ownerSetUp()
        store().renameDevice(OWNER_PHONE, "Galaxy S26 Ultra")
        assertEquals("Galaxy S26 Ultra", store().load()!!.owner!!.devices.single().name)
        val revision = store().load()!!.revision
        store().renameDevice(OWNER_PHONE, "Galaxy S26 Ultra")
        assertEquals(revision, store().load()!!.revision)
    }

    @Test
    fun twoPhonesChangingAtOnceNeverLoseAChange() = runTest {
        ownerSetUp()
        store().updateSettings(OwnerAuth(OWNER_PHONE, "2468")) { it.copy(approveNewPhones = false) }
        val phones = (1..6).map { "racing-phone-000$it" }
        phones.map { id -> async { store().requestJoin(id, "Phone", newName = "Person $id") } }.awaitAll()
        val h = store().load()!!
        assertEquals(7, h.members.size)
        assertTrue(phones.all { id -> h.memberForDevice(id) != null })
    }

    @Test
    fun damagedNewestVersionFallsBackToThePreviousOne() = runTest {
        val h = ownerSetUp()
        drive.createNew(HouseholdStore.versionPath(h.revision + 1), "not json".encodeToByteArray())
        assertEquals(h.householdId, store().load()?.householdId)
    }

    @Test
    fun usageCountsEverythingInThePersonsFolder() = runTest {
        val h = ownerSetUp()
        drive.createNew("users/${h.ownerUserId}/albums/a.jpg", ByteArray(1500))
        drive.createNew("users/${h.ownerUserId}/manifests/b.json", ByteArray(500))
        assertEquals(2000L, store().usedBytes(h.ownerUserId))
    }

    @Test
    fun emptyDriveHasNoFamily() = runTest {
        assertIs<Membership.NoHousehold>(store().membership(OWNER_PHONE))
        assertNull(store().load())
    }
}

class PinTest {
    @Test
    fun pinRulesAndHashing() {
        assertTrue(OwnerPin.isValid("1234"))
        assertTrue(!OwnerPin.isValid("12a4") && !OwnerPin.isValid("123") && !OwnerPin.isValid("123456789"))
        val hash = OwnerPin.hash("2468")
        assertTrue(OwnerPin.matches("2468", hash))
        assertTrue(!OwnerPin.matches("2469", hash))
        // Same PIN, different salt.
        assertTrue(OwnerPin.hash("2468").hashHex != hash.hashHex)
    }

    @Test
    fun wrongPinsLockForAWhile() {
        var now = 0L
        val attempts = PinAttempts { now }
        repeat(4) { attempts.recordFailure() }
        assertEquals(0L, attempts.lockedForMillis())
        attempts.recordFailure()
        assertEquals(30_000L, attempts.lockedForMillis())
        now += 30_000
        assertEquals(0L, attempts.lockedForMillis())
        attempts.recordFailure()
        assertEquals(60_000L, attempts.lockedForMillis())
        attempts.recordSuccess()
        assertEquals(0L, attempts.lockedForMillis())
    }
}
