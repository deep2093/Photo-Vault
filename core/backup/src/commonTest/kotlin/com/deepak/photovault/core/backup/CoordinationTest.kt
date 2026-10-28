package com.deepak.photovault.core.backup

import com.deepak.photovault.core.drive.MemoryDrive
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 15 Oct 2026, 10:00 UTC. */
private const val NOW = 1_792_058_400_000L

class CoordinationTest {
    private val melbourne = TimeZone.of("Australia/Melbourne")
    private val india = TimeZone.of("Asia/Kolkata")
    private val sunday2am = BackupSchedule(hour = 2, minute = 0, dayOfWeek = 7)

    private fun other(s: BackupSchedule, zone: String) = PublishedSchedule("sam-phone", "Pixel 9", "sam", "Sam", zone, s)

    @Test
    fun timesWithinAnHourClashWhereverThePhonesAre() {
        assertTrue(SlotRules.clash(sunday2am, melbourne, sunday2am, melbourne, NOW))
        // Exactly an hour apart is fine.
        assertFalse(SlotRules.clash(sunday2am, melbourne, sunday2am.copy(hour = 3), melbourne, NOW))
        // 2 am Sunday in India is 7:30 am in Melbourne: no clash.
        assertFalse(SlotRules.clash(sunday2am, melbourne, sunday2am, india, NOW))
        // 8:30 pm Saturday in India is 2 am Sunday in Melbourne (daylight saving): clash.
        val saturdayEvening = BackupSchedule(hour = 20, minute = 30, dayOfWeek = 6)
        assertTrue(SlotRules.clash(sunday2am, melbourne, saturdayEvening, india, NOW))
        // Daily clashes with a weekly at the same hour; off never clashes.
        assertTrue(SlotRules.clash(BackupSchedule(frequency = Frequency.DAILY, hour = 2), melbourne, sunday2am, melbourne, NOW))
        assertFalse(SlotRules.clash(sunday2am.copy(enabled = false), melbourne, sunday2am, melbourne, NOW))
    }

    @Test
    fun suggestsTheNearestFreeHour() {
        val taken = listOf(other(sunday2am, "Australia/Melbourne"))
        assertEquals(listOf("sam-phone"), SlotRules.clashes(sunday2am, melbourne, taken, NOW).map { it.deviceId })
        val free = SlotRules.nearestFree(sunday2am, melbourne, taken, NOW)!!
        assertEquals(3, free.hour)
    }

    @Test
    fun onePhoneAtATimeAndAStalePhoneDoesntBlock() = runTest {
        val drive = MemoryDrive()
        var clock = NOW
        val mine = BackupCoordinator(drive, "deepak", "s26", "Galaxy S26", { clock })
        val sams = BackupCoordinator(drive, "sam", "pixel", "Pixel 9", { clock })
        val everyone = listOf("deepak", "sam")
        assertTrue(mine.tryStart(everyone))
        assertFalse(sams.tryStart(everyone))
        assertEquals(setOf("deepak"), sams.activeUsers(everyone))
        mine.finish()
        assertTrue(sams.tryStart(everyone))
        // Sam's phone goes quiet (switched off half way): after a few minutes it no longer blocks.
        clock += BackupCoordinator.STALE_MS + 1
        assertTrue(mine.tryStart(everyone))
    }

    @Test
    fun schedulesArePublishedPerPhone() = runTest {
        val drive = MemoryDrive()
        val mine = BackupCoordinator(drive, "deepak", "s26", "Galaxy S26", { NOW })
        val sams = BackupCoordinator(drive, "sam", "pixel", "Pixel 9", { NOW })
        sams.publish(sunday2am, "Sam", "Asia/Kolkata")
        sams.publish(sunday2am.copy(hour = 4), "Sam", "Asia/Kolkata") // changed: replaces the old one
        mine.publish(sunday2am.copy(hour = 6), "Deepak", "Australia/Melbourne")
        val seen = mine.otherSchedules(listOf("deepak", "sam"))
        assertEquals(1, seen.size)
        assertEquals(4, seen.single().schedule.hour)
    }

    @Test
    fun phonesWaitInTheOrderTheyAskedAndEachKnowsItsPlace() = runTest {
        val drive = MemoryDrive()
        var clock = NOW
        val a = BackupCoordinator(drive, "deepak", "s26", "Galaxy S26", { clock })
        val b = BackupCoordinator(drive, "sam", "pixel", "Pixel 9", { clock })
        val c = BackupCoordinator(drive, "mum", "iphone", "Mum's phone", { clock })
        val everyone = listOf("deepak", "sam", "mum")
        a.joinQueue()
        assertTrue(a.tryStart(everyone))
        a.leaveQueue()
        clock += 1000
        b.joinQueue()
        clock += 1000
        c.joinQueue()
        // A is backing up: B is number 2, C is number 3.
        assertFalse(b.tryStart(everyone))
        assertFalse(c.tryStart(everyone))
        assertEquals(2, b.place(everyone))
        assertEquals(3, c.place(everyone))
        a.finish()
        // C can't jump the line even though the drive is free.
        assertFalse(c.tryStart(everyone))
        assertEquals(2, c.place(everyone))
        assertTrue(b.tryStart(everyone))
        b.leaveQueue()
        b.finish()
        assertEquals(1, c.place(everyone))
        assertTrue(c.tryStart(everyone))
    }

    @Test
    fun aPhoneThatGaveUpWaitingNoLongerHoldsUpTheLine() = runTest {
        val drive = MemoryDrive()
        var clock = NOW
        val b = BackupCoordinator(drive, "sam", "pixel", "Pixel 9", { clock })
        val c = BackupCoordinator(drive, "mum", "iphone", "Mum's phone", { clock })
        val everyone = listOf("sam", "mum")
        b.joinQueue()
        clock += 1000
        c.joinQueue()
        assertFalse(c.tryStart(everyone))
        // Sam's phone goes quiet (switched off): after a few minutes it is out of the line.
        clock += BackupCoordinator.STALE_MS + 1
        c.refreshTicket()
        assertTrue(c.tryStart(everyone))
    }
}
