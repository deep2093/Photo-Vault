package com.deepak.photovault.ui.shell

import com.deepak.photovault.core.drive.DriveFileSystem
import com.deepak.photovault.core.drive.MemoryDrive
import com.deepak.photovault.core.household.Membership
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The family flow as the screens drive it, with phones sharing one in-memory drive. */
class DriveStateTest {
    private class FakePhone(
        private val drive: MemoryDrive,
        override val deviceId: String,
        override val deviceName: String = "Phone $deviceId",
        var clock: Long = 1_000_000L,
    ) : DrivePlatform {
        var saved: DriveConnection? = null
        var user: String? = null
        var rejectPassword = false

        override fun loadConnection() = saved
        override fun saveConnection(connection: DriveConnection) { saved = connection }
        override fun forgetConnection() { saved = null }
        override suspend fun open(connection: DriveConnection): DriveFileSystem {
            if (rejectPassword) throw IllegalStateException("logon")
            return drive
        }
        override suspend fun discover(knownHost: String) = listOf("192.168.1.1")
        override fun rememberedUserId() = user
        override fun rememberUserId(userId: String?) { user = userId }
        override fun describe(error: Throwable) = "Incorrect username or password."
        override fun now() = clock
    }

    private val connection = DriveConnection(host = "192.168.1.1", share = "usb", username = "admin", password = "x")

    @Test
    fun ownerSetsUpAndApprovesASecondPhone() = runTest {
        val disk = MemoryDrive()
        val ownerPhone = FakePhone(disk, "device-owner-1")
        val owner = DriveState(ownerPhone)
        assertNull(owner.connect(connection))
        assertIs<Membership.NoHousehold>(owner.membership)
        assertNull(owner.createFamily("Deepak", "4821"))
        assertTrue(owner.isOwner)
        assertTrue(owner.ownerUnlocked, "creating the family unlocks the owner controls")

        val other = DriveState(FakePhone(disk, "device-second-2"))
        assertNull(other.connect(connection))
        assertNull(other.join(newName = "Asha"))
        assertIs<Membership.Pending>(other.membership)

        owner.refresh()
        val request = owner.requests.single()
        assertNull(owner.approve(request))
        other.refresh()
        val joined = assertIs<Membership.Joined>(other.membership)
        assertEquals("Asha", joined.me.name)
        assertFalse(other.isOwner)
        assertEquals(100, joined.me.limitGb)
    }

    @Test
    fun ownerActionsNeedThePinAgainAfterFiveMinutes() = runTest {
        val phone = FakePhone(MemoryDrive(), "device-owner-1")
        val owner = DriveState(phone)
        owner.connect(connection)
        owner.createFamily("Deepak", "4821")
        val me = owner.joined!!.me.userId

        phone.clock += DriveState.OWNER_UNLOCK_MS + 1
        assertFalse(owner.ownerUnlocked)
        assertNotNull(owner.setLimit(me, 200), "locked owner controls must refuse")
        assertNotNull(owner.unlockOwner("0000"), "wrong PIN")
        assertNull(owner.unlockOwner("4821"))
        assertNull(owner.setLimit(me, 200))
        assertEquals(200, owner.joined!!.me.limitGb)
    }

    @Test
    fun settingsTogglesApplyAndJoiningSkipsApprovalWhenOff() = runTest {
        val disk = MemoryDrive()
        val owner = DriveState(FakePhone(disk, "device-owner-1"))
        owner.connect(connection)
        owner.createFamily("Deepak", "4821")
        assertNull(owner.updateSettings { it.copy(approveNewPhones = false, requirePin = false) })
        assertFalse(owner.joined!!.household.settings.approveNewPhones)

        val other = DriveState(FakePhone(disk, "device-second-2"))
        other.connect(connection)
        assertNull(other.join(newName = "Asha"))
        assertIs<Membership.Joined>(other.membership)
    }

    @Test
    fun wrongPasswordIsReportedAndNotSaved() = runTest {
        val phone = FakePhone(MemoryDrive(), "device-owner-1").apply { rejectPassword = true }
        val state = DriveState(phone)
        assertEquals("Incorrect username or password.", state.connect(connection))
        assertNull(phone.saved)
        assertFalse(state.isSetUp)
    }

    @Test
    fun forgetClearsThePhoneButNotTheDrive() = runTest {
        val disk = MemoryDrive()
        val phone = FakePhone(disk, "device-owner-1")
        val state = DriveState(phone)
        state.connect(connection)
        state.createFamily("Deepak", null)
        state.forget()
        assertNull(phone.saved)
        assertNull(state.membership)
        // Connecting again finds the same family and the same person (this phone is still listed).
        state.connect(connection)
        assertTrue(state.isOwner)
    }

    @Test
    fun connectionNeverPrintsThePassword() {
        assertFalse(connection.copy(password = "secret-123").toString().contains("secret-123"))
    }
}
