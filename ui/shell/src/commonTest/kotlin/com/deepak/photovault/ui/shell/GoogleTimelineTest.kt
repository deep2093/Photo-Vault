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

    @Test
    fun backupPageSummaries() {
        val s = com.deepak.photovault.core.backup.BackupSettings()
        assertEquals("Photos · Wi-Fi", optionsSummary(s))
        assertEquals("Photos, videos · mobile data · saver", optionsSummary(s.copy(videos = true, mobileData = true, storageSaver = true)))
        assertEquals("1 account", accountsSummary(listOf(GoogleAccount("a@x.com"))))
        assertEquals("Full", accountsSummary(listOf(GoogleAccount("a@x.com", full = true))))
        assertEquals("Sign in again", accountsSummary(listOf(GoogleAccount("a@x.com"), GoogleAccount("b@x.com", needsSignIn = true))))
    }

    @Test
    fun googleStorageAddsUpAccounts() {
        val gb = 1024L * 1024 * 1024
        val photo = record("p", "gdrive/a/p.jpg").copy(sizeBytes = 2 * gb)
        val video = record("v", "gdrive/a/v.mp4").copy(sizeBytes = gb, isVideo = true)
        val a = GoogleUsage.of("a@x.com", limit = 15 * gb, used = 10 * gb, records = listOf(photo, video))
        val b = GoogleUsage.of("b@x.com", limit = 15 * gb, used = 1 * gb, records = emptyList())
        assertEquals(7 * gb, a.otherBytes)
        assertEquals(5 * gb, a.freeBytes)
        val all = GoogleUsage.all(listOf(a, b))
        assertEquals(30 * gb, all.limitBytes)
        assertEquals(1, all.photos)
        assertEquals(1, all.videos)
        assertEquals(8 * gb, all.otherBytes)
        assertEquals(19 * gb, all.freeBytes)
        // Google hasn't said the size of one account: no total.
        assertNull(GoogleUsage.all(listOf(a, b.copy(limitBytes = null))).limitBytes)
    }

    @Test
    fun deletingFromGoogleTakesThemOffTheLists() = kotlinx.coroutines.test.runTest {
        val gb = 1024L * 1024 * 1024
        val keep = record("k", "gdrive/a/users/me/k.jpg").copy(sizeBytes = gb)
        val gone = record("g", "gdrive/a/users/me/g.jpg").copy(sizeBytes = 2 * gb)
        val asked = mutableListOf<Set<String>>()
        val platform = object : GooglePlatform {
            override val supported = true
            override suspend fun accounts() = listOf(GoogleAccount("a@x.com", 5 * gb, 15 * gb))
            override suspend fun storage() = listOf(GoogleUsage.of("a@x.com", 15 * gb, 5 * gb, listOf(keep, gone)))
            override suspend fun keys() = setOf("k")
            override suspend fun deleteBackups(paths: Set<String>): String? {
                asked += paths
                return null
            }
        }
        val google = GoogleState(platform)
        google.loadUsage()
        assertEquals(2, google.records!!.size)
        assertNull(google.deleteBackups(setOf(gone.drivePath)))
        assertEquals(listOf(setOf(gone.drivePath)), asked)
        assertEquals(listOf("k"), google.records!!.map { it.key })
        val u = google.usage!!.single()
        assertEquals(1, u.photos)
        assertEquals(3 * gb, u.usedBytes)
        assertEquals(setOf("k"), google.keys)
    }
}
