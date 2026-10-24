package com.deepak.photovault.core.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupSlotTest {
    @Test
    fun eachPhoneInTheFamilyGetsItsOwnHalfHourStartingAtTwo() {
        assertEquals(2 to 0, BackupSchedule.slotTime(0))
        assertEquals(2 to 30, BackupSchedule.slotTime(1))
        assertEquals(3 to 0, BackupSchedule.slotTime(2))
        val twelve = (0 until 12).map { BackupSchedule.slotTime(it) }
        assertEquals(12, twelve.toSet().size)
        assertTrue(twelve.all { (h, _) -> h in 2..7 })
        assertEquals(BackupSchedule.slotTime(0), BackupSchedule.slotTime(12))
    }
}
