package com.deepak.photovault.core.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GoogleOptionsTest {
    @Test
    fun googleFollowsTheFamilyDriveUntilChanged() {
        val s = BackupSettings(videos = true, folders = setOf("a"), storageSaver = true, googleWifiOnly = false, googleChargingOnly = true)
        assertNull(s.google)
        val g = s.forGoogle()
        assertEquals(true, g.videos)
        assertEquals(setOf("a"), g.folders)
        assertEquals(true, g.storageSaver)
        // The older Wi-Fi only and charging switches carry over.
        assertEquals(true, g.mobileData)
        assertEquals(true, g.chargingOnly)
        // Its own time: every day at 3 am, not the family drive's slot.
        assertEquals(Frequency.DAILY, g.schedule.frequency)
        assertEquals(3, g.schedule.hour)
    }

    @Test
    fun changingGoogleLeavesTheFamilyDriveAlone() {
        val s = BackupSettings(videos = false, folders = setOf("a"))
        val view = s.forGoogle()
        val changed = s.withGoogleFrom(
            view.copy(videos = true, folders = setOf("a", "b"), schedule = view.schedule.copy(frequency = Frequency.WEEKLY, daysOfWeek = setOf(1, 3), hour = 21)),
        )
        // The family drive's own choices are untouched.
        assertEquals(false, changed.videos)
        assertEquals(setOf("a"), changed.folders)
        assertEquals(s.schedule, changed.schedule)
        // Google has its own.
        val g = changed.forGoogle()
        assertEquals(true, g.videos)
        assertEquals(setOf("a", "b"), g.folders)
        assertEquals(setOf(1, 3), g.schedule.weekDays)
        assertEquals(21, g.schedule.hour)
        // And a later change to the family drive no longer moves Google.
        assertEquals(setOf("a", "b"), changed.copy(folders = setOf("z")).forGoogle().folders)
    }
}
