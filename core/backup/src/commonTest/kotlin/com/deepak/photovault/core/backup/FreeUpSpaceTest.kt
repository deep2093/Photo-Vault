package com.deepak.photovault.core.backup

import com.deepak.photovault.core.drive.BytesSource
import com.deepak.photovault.core.drive.MemoryDrive
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.MediaType
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val USER = "user-0001"
private const val DAY = 86_400_000L
private const val OCT_15 = 1_792_058_400_000L

class FreeUpSpaceTest {
    private val drive = MemoryDrive()
    private val files = mutableMapOf<Long, ByteArray>()
    private var clock = OCT_15

    private val reader = object : MediaReader {
        override suspend fun thumbnail(item: MediaItem): ByteArray? = if (files.containsKey(item.id)) "thumb".encodeToByteArray() else null
        override suspend fun open(item: MediaItem, storageSaver: Boolean): PreparedFile {
            val bytes = files[item.id] ?: error("gone")
            return PreparedFile(BytesSource(bytes), item.displayName, item.mimeType, bytes.size.toLong(), compressed = false)
        }
    }

    private fun photo(id: Long, name: String, album: String = "Family trip"): MediaItem {
        files[id] = ByteArray(3000) { ((it + id) % 251).toByte() }
        return MediaItem(
            id = id, uri = "content://$id", type = MediaType.PHOTO, displayName = name, takenAtMillis = OCT_15, sizeBytes = 3000,
            width = 10, height = 10, albumId = album.lowercase(), albumName = album, mimeType = "image/jpeg",
        )
    }

    private fun engine(index: BackupIndex = MemoryBackupIndex()) = BackupEngine(drive, USER, "phone-0000001", reader, index, { clock++ }, TimeZone.UTC)

    private fun record(item: MediaItem, savedAt: Long, encrypted: Boolean = false, saver: Boolean = false) = BackupRecord(
        key = BackupSelection.key(item), drivePath = "users/$USER/albums/${item.albumName}/2026/10/${item.displayName}", sha256 = "x", sizeBytes = 3000,
        originalName = item.displayName, takenAtMillis = item.takenAtMillis, mimeType = "image/jpeg", isVideo = false, savedAtMillis = savedAt,
        deviceId = "phone-0000001", storageSaver = saver, encrypted = encrypted,
    )

    @Test
    fun onlyBackupsOldEnoughAreReadyAndOffMeansNothing() {
        val old = photo(1, "a.jpg")
        val fresh = photo(2, "b.jpg")
        val never = photo(3, "c.jpg")
        val records = mapOf(BackupSelection.key(old) to record(old, OCT_15 - 40 * DAY), BackupSelection.key(fresh) to record(fresh, OCT_15 - 3 * DAY))
        val split = FreeUpSpace.split(listOf(old, fresh, never), records, 30, OCT_15)
        assertEquals(listOf(old), split.ready.map { it.item })
        assertEquals(1, split.waiting)
        // A photo that was never backed up is not even waiting, and Off chooses nothing.
        assertTrue(FreeUpSpace.split(listOf(old), records, 0, OCT_15).ready.isEmpty())
        // Exactly N days old counts.
        assertEquals(1, FreeUpSpace.split(listOf(fresh), records, 3, OCT_15).ready.size)
    }

    @Test
    fun everyAlbumCountsAndPrivateBackupsAreLeftOut() {
        val a = photo(1, "a.jpg", album = "Camera")
        val b = photo(2, "b.jpg", album = "WhatsApp Images")
        val p = photo(3, "p.jpg")
        val records = listOf(a, b).associate { BackupSelection.key(it) to record(it, OCT_15 - 90 * DAY) } + (BackupSelection.key(p) to record(p, OCT_15 - 90 * DAY, encrypted = true))
        assertEquals(listOf(a, b), FreeUpSpace.split(listOf(a, b, p), records, 7, OCT_15).ready.map { it.item })
    }

    @Test
    fun aMissingChangedOrUnbackedFileDoesNotPass() = runTest {
        val item = photo(1, "IMG_1.jpg", album = "Camera")
        val index = MemoryBackupIndex()
        val e = engine(index)
        e.run(listOf(item), BackupSettings(), 100_000_000_000L, BackupTrigger.MANUAL)
        val rec = index.records.single()
        val ready = listOf(FreeUpSpace.Ready(item, rec))
        // Good: passes, and a preview is there afterwards.
        val (passed, failed) = e.verifyForRemoval(ready)
        assertEquals(1, passed.size)
        assertEquals(0, failed)
        assertTrue(NormalThumbs.pathFor(rec.drivePath)!! in drive.allFiles())
        // A record whose checksum doesn't match what is on the drive fails.
        val (p2, f2) = e.verifyForRemoval(listOf(FreeUpSpace.Ready(item, rec.copy(sha256 = "0".repeat(64)))))
        assertTrue(p2.isEmpty())
        assertEquals(1, f2)
        // A file gone from the drive fails.
        drive.delete(rec.drivePath)
        val (p3, f3) = e.verifyForRemoval(ready)
        assertTrue(p3.isEmpty())
        assertEquals(1, f3)
    }

    @Test
    fun noPreviewMeansItStaysOnThePhone() = runTest {
        val item = photo(1, "IMG_1.jpg", album = "Camera")
        val index = MemoryBackupIndex()
        val e = engine(index)
        e.run(listOf(item), BackupSettings(), 100_000_000_000L, BackupTrigger.MANUAL)
        val rec = index.records.single()
        drive.delete(NormalThumbs.pathFor(rec.drivePath)!!)
        // The phone can't make a preview any more (file gone), so the photo must not be removed.
        files.remove(1)
        val (passed, failed) = e.verifyForRemoval(listOf(FreeUpSpace.Ready(item, rec)))
        assertTrue(passed.isEmpty())
        assertEquals(1, failed)
    }

    @Test
    fun smallerStorageSaverCopiesAreKeptApartFromFullOnes() {
        val full = photo(1, "a.jpg")
        val small = photo(2, "b.jpg")
        val result = FreeUpSpace.result(
            listOf(FreeUpSpace.Ready(full, record(full, 0)), FreeUpSpace.Ready(small, record(small, 0, saver = true))), waiting = 2, failedCheck = 1,
        )
        assertEquals(listOf(full), result.verified)
        assertEquals(listOf(small), result.smaller)
        assertEquals(3000L, result.bytes)
        assertEquals(2, result.waiting)
        assertEquals(1, result.failedCheck)
    }

    @Test
    fun freeUpSpaceIsOffByDefaultAndOldSavedSettingsStillLoad() {
        assertEquals(0, BackupSettings().freeUpDays)
        assertEquals(listOf(7, 30, 60, 90), FreeUpSpace.DAY_CHOICES)
        val old = BackupEngine.json.decodeFromString(BackupSettings.serializer(), "{\"videos\":true}")
        assertEquals(0, old.freeUpDays)
    }
}
