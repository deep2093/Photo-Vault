package com.deepak.photovault.ui.shell

import com.deepak.photovault.core.backup.BackupRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleTimelineTest {
    private fun record(key: String, path: String) = BackupRecord(
        key = key, drivePath = path, sha256 = "x", sizeBytes = 1, originalName = "a.jpg", takenAtMillis = 1,
        mimeType = "image/jpeg", isVideo = false, savedAtMillis = 1, deviceId = "p",
    )

    @Test
    fun aPhotoInBothPlacesShowsOnce() {
        val drive = listOf(record("a", "users/u/a.jpg"))
        val google = listOf(record("a", "gdrive/me@x.com/users/me/a.jpg"), record("b", "gdrive/me@x.com/users/me/b.jpg"))
        val all = timelineRecords(drive, google)!!
        assertEquals(listOf("users/u/a.jpg", "gdrive/me@x.com/users/me/b.jpg"), all.map { it.drivePath })
        assertTrue(isGooglePath(all[1].drivePath))
    }

    @Test
    fun googleOnlyAndNothing() {
        assertNull(timelineRecords(null, null))
        assertEquals(1, timelineRecords(null, listOf(record("b", "gdrive/x/b.jpg")))!!.size)
        assertEquals(0, timelineRecords(emptyList(), null)!!.size)
    }
}
