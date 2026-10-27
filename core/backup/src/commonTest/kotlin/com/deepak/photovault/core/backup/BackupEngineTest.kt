package com.deepak.photovault.core.backup

import com.deepak.photovault.core.drive.BytesSource
import com.deepak.photovault.core.drive.MemoryDrive
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.MediaType
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val USER = "user-0001"
private const val PHONE = "phone-0000001"
private const val GB = 1_000_000_000L

/** 15 Oct 2026, 10:00 UTC. */
private const val OCT_15 = 1_792_058_400_000L

class BackupEngineTest {
    private val drive = MemoryDrive()
    private var clock = OCT_15
    private val files = mutableMapOf<Long, ByteArray>()

    private val reader = object : MediaReader {
        override suspend fun thumbnail(item: MediaItem): ByteArray? = if (files.containsKey(item.id)) "thumb-${item.id}".encodeToByteArray() else null

        override suspend fun open(item: MediaItem, storageSaver: Boolean): PreparedFile {
            val bytes = files[item.id] ?: error("gone")
            return if (storageSaver) {
                val small = bytes.copyOf(bytes.size / 2)
                PreparedFile(BytesSource(small), item.displayName.substringBeforeLast('.') + ".jpg", "image/jpeg", small.size.toLong(), compressed = true)
            } else {
                PreparedFile(BytesSource(bytes), item.displayName, item.mimeType, bytes.size.toLong(), compressed = false)
            }
        }
    }

    private fun photo(id: Long, name: String, size: Int = 5000, taken: Long = OCT_15, album: String = "Camera", video: Boolean = false): MediaItem {
        files[id] = ByteArray(size) { ((it + id) % 251).toByte() }
        return MediaItem(
            id = id, uri = "content://$id", type = if (video) MediaType.VIDEO else MediaType.PHOTO, displayName = name,
            takenAtMillis = taken, sizeBytes = size.toLong(), width = 10, height = 10, albumId = album.lowercase(),
            albumName = album, mimeType = if (video) "video/mp4" else "image/jpeg",
        )
    }

    private fun engine(index: BackupIndex = MemoryBackupIndex(), device: String = PHONE) =
        BackupEngine(drive, USER, device, reader, index, { clock++ }, TimeZone.UTC)

    private suspend fun run(engine: BackupEngine, items: List<MediaItem>, limit: Long = 100 * GB, settings: BackupSettings = BackupSettings()) =
        engine.run(items, settings, limit, BackupTrigger.MANUAL)

    @Test
    fun backsUpVerifiedIntoYearAndMonthFoldersAndRecordsIt() = runTest {
        val index = MemoryBackupIndex()
        val items = listOf(photo(1, "IMG_1.jpg"), photo(2, "VID_2.mp4", video = true))
        val result = run(engine(index), items)
        assertTrue(result.succeeded)
        assertEquals(2, result.uploaded)
        val all = drive.allFiles()
        assertTrue("users/$USER/albums/Camera/2026/10/IMG_1.jpg" in all, all.toString())
        assertTrue("users/$USER/albums/Camera/2026/10/VID_2.mp4" in all)
        // Exact bytes on the drive.
        assertTrue(files[1]!!.contentEquals(drive.read("users/$USER/albums/Camera/2026/10/IMG_1.jpg")))
        // No temporary files left; one manifest written for this phone.
        assertTrue(all.none { it.endsWith(".part") })
        assertEquals(1, all.count { it.startsWith("users/$USER/manifests/$PHONE/") })
        assertEquals(2, index.records.size)
        assertEquals(64, index.records.first().sha256.length)
    }

    @Test
    fun aPhotoMovedToAnotherAlbumIsNotBackedUpAgain() = runTest {
        val index = MemoryBackupIndex()
        val first = photo(1, "IMG_1.jpg", album = "Camera")
        assertEquals(1, run(engine(index), listOf(first)).uploaded)
        // Moved on the phone: same name, size and date, a different album.
        val moved = first.copy(albumId = "holiday", albumName = "Holiday")
        val again = run(engine(index), listOf(moved))
        assertEquals(0, again.uploaded)
        assertEquals(1, again.alreadyBackedUp)
        assertTrue(drive.allFiles().none { it.contains("/albums/Holiday/") })
    }

    @Test
    fun aFreshPhoneLinksTheOldBackupInsteadOfUploadingAgain() = runTest {
        val items = listOf(photo(1, "IMG_1.jpg"), photo(2, "IMG_2.jpg"))
        assertEquals(2, run(engine(MemoryBackupIndex()), items).uploaded)
        // A new phone (or a reset app) starts with an empty index and finds the manifests on the drive.
        val fresh = MemoryBackupIndex()
        val result = run(engine(fresh, device = "phone-0000002"), items)
        assertEquals(0, result.uploaded)
        assertEquals(2, result.alreadyBackedUp)
        assertEquals(2, fresh.records.size)
    }

    @Test
    fun savesASmallPreviewBesideEachNormalBackup() = runTest {
        val items = listOf(photo(1, "IMG_1.jpg"))
        assertEquals(1, run(engine(), items).uploaded)
        val all = drive.allFiles()
        assertTrue("users/$USER/thumbs/Camera/2026/10/IMG_1.jpg.jpg" in all, all.toString())
        assertEquals(
            "users/$USER/thumbs/Camera/2026/10/IMG_1.jpg.jpg",
            NormalThumbs.pathFor("users/$USER/albums/Camera/2026/10/IMG_1.jpg"),
        )
        assertNull(NormalThumbs.pathFor("users/$USER/objects/ab/abc.pxo"))
    }

    @Test
    fun olderBackupsGetTheirPreviewOnceAndThenTheMarkerStopsTheWork() = runTest {
        val items = listOf(photo(1, "IMG_1.jpg"), photo(2, "IMG_2.jpg"))
        assertEquals(2, run(engine(), items).uploaded)
        // Pretend they were backed up before previews existed.
        drive.allFiles().filter { it.contains("/thumbs/") }.forEach { drive.delete(it) }
        val later = engine(MemoryBackupIndex(), device = "phone-0000002")
        assertEquals(2, later.addMissingThumbnails(items))
        assertTrue(drive.allFiles().count { it.contains("/thumbs/Camera/") } == 2)
        // Done: a marker is left and nothing more is made.
        assertEquals(0, later.addMissingThumbnails(items))
        assertTrue(drive.allFiles().any { it.endsWith(BackupEngine.CAUGHT_UP + "-phone-0000002") })
    }

    @Test
    fun albumFoldersAreSafeNames() {
        assertEquals("Camera", BackupEngine.albumFolder("Camera"))
        assertEquals("WhatsApp_Images", BackupEngine.albumFolder("WhatsApp/Images"))
        assertEquals("No album", BackupEngine.albumFolder(" .. "))
        assertEquals("No album", BackupEngine.albumFolder(""))
    }

    @Test
    fun recentlyDeletedOnTheDriveKeepsTheFileFor30DaysAndRestoresIt() = runTest {
        run(engine(), listOf(photo(1, "IMG_1.jpg"), photo(2, "IMG_2.jpg")))
        val catalog = BackupCatalog(drive, USER)
        val first = catalog.records().single { it.originalName == "IMG_1.jpg" }
        val day = 24L * 60 * 60 * 1000
        catalog.trash(listOf(first), PHONE, clock)
        // Gone from the list and its place, but kept.
        assertEquals(listOf("IMG_2.jpg"), catalog.records().map { it.originalName })
        assertFalse(drive.exists(first.drivePath))
        val bin = catalog.trashed()
        assertEquals(1, bin.size)
        assertTrue(drive.exists(bin[0].trashPath))
        // Not yet old enough to clear.
        assertEquals(1, catalog.purgeExpired(clock + 29 * day).size)
        // Restore: back in the same place, listed again.
        catalog.restore(bin, PHONE, clock + 1000)
        assertTrue(drive.exists(first.drivePath))
        assertEquals(setOf("IMG_1.jpg", "IMG_2.jpg"), catalog.records().map { it.originalName }.toSet())
        assertTrue(catalog.trashed().isEmpty())
    }

    @Test
    fun theDriveTrashClearsAfter30DaysAndDeleteForeverIsFinal() = runTest {
        run(engine(), listOf(photo(1, "IMG_1.jpg"), photo(2, "IMG_2.jpg")))
        val catalog = BackupCatalog(drive, USER)
        val all = catalog.records()
        val day = 24L * 60 * 60 * 1000
        catalog.trash(listOf(all[0]), PHONE, clock)
        catalog.trash(listOf(all[1]), PHONE, clock + 20 * day)
        val left = catalog.purgeExpired(clock + 31 * day)
        assertEquals(1, left.size)
        assertEquals(all[1].drivePath, left[0].record.drivePath)
        catalog.deleteForever(left)
        assertTrue(catalog.trashed().isEmpty())
        assertFalse(drive.exists(left[0].trashPath))
    }

    @Test
    fun deletingFromTheDriveHidesItAndItCanBeBackedUpAgain() = runTest {
        val index = MemoryBackupIndex()
        val items = listOf(photo(1, "IMG_1.jpg"), photo(2, "IMG_2.jpg"))
        run(engine(index), items)
        val catalog = BackupCatalog(drive, USER)
        val first = catalog.records().single { it.originalName == "IMG_1.jpg" }
        catalog.remove(listOf(first), PHONE, clock++)
        index.remove(setOf(first.key))
        assertEquals(listOf("IMG_2.jpg"), catalog.records().map { it.originalName })
        assertFalse(drive.exists(first.drivePath))
        // Still on the phone: the next backup copies it again, and it shows again.
        val again = run(engine(index), items)
        assertEquals(1, again.uploaded)
        assertEquals(setOf("IMG_1.jpg", "IMG_2.jpg"), catalog.records().map { it.originalName }.toSet())
        // Nobody can delete outside their own folder.
        val other = first.copy(drivePath = "users/someone-else/albums/Camera/2026/10/IMG_9.jpg")
        kotlin.test.assertFailsWith<IllegalArgumentException> { catalog.remove(listOf(other), PHONE, clock++) }
    }

    @Test
    fun secondRunSkipsWhatIsAlreadyBackedUp() = runTest {
        val index = MemoryBackupIndex()
        val items = listOf(photo(1, "IMG_1.jpg"))
        run(engine(index), items)
        val again = run(engine(index), items + photo(3, "IMG_3.jpg"))
        assertEquals(1, again.alreadyBackedUp)
        assertEquals(1, again.uploaded)
    }

    @Test
    fun neverOverwritesADifferentFileWithTheSameName() = runTest {
        drive.mkdirs("users/$USER/albums/Camera/2026/10")
        drive.createNew("users/$USER/albums/Camera/2026/10/IMG_1.jpg", "someone else's".encodeToByteArray())
        val result = run(engine(), listOf(photo(1, "IMG_1.jpg")))
        assertTrue(result.succeeded)
        assertEquals("someone else's", drive.read("users/$USER/albums/Camera/2026/10/IMG_1.jpg").decodeToString())
        assertTrue(files[1]!!.contentEquals(drive.read("users/$USER/albums/Camera/2026/10/IMG_1 (2).jpg")))
    }

    @Test
    fun aReinstalledAppRecognisesItsPhotosOnTheDriveWithoutUploadingAgain() = runTest {
        val items = listOf(photo(1, "IMG_1.jpg"), photo(2, "IMG_2.jpg"))
        run(engine(), items)
        val before = drive.allFiles()
        // New install: empty index and a new phone ID. It reads the manifests first.
        val fresh = MemoryBackupIndex()
        val result = run(engine(fresh, device = "phone-reinstall"), items)
        assertTrue(result.succeeded)
        assertEquals(0, result.uploaded)
        assertEquals(2, result.alreadyBackedUp)
        // Only the small "previews are done" note for the new phone ID is added.
        assertEquals(before.filter { !it.contains(BackupEngine.CAUGHT_UP) }, drive.allFiles().filter { !it.contains(BackupEngine.CAUGHT_UP) })
    }

    @Test
    fun aFileThatDoesNotMatchAfterUploadIsNotCountedAndIsRemoved() = runTest {
        val index = MemoryBackupIndex()
        drive.corruptWrites = true
        val result = run(engine(index), listOf(photo(1, "IMG_1.jpg")))
        assertFalse(result.succeeded)
        assertEquals(FailureReason.CHECK_FAILED, result.failure)
        assertEquals(1, result.failedItems)
        assertTrue(index.records.isEmpty())
        assertTrue(drive.allFiles().none { it.contains("/albums/") || it.endsWith(".part") }, drive.allFiles().toString())
        // Fixed cable: the next run backs it up.
        drive.corruptWrites = false
        assertTrue(run(engine(index), listOf(photo(1, "IMG_1.jpg"))).succeeded)
    }

    @Test
    fun stopsAtThePersonsSpaceLimit() = runTest {
        val items = (1L..5L).map { photo(it, "IMG_$it.jpg", size = 1000, taken = OCT_15 + it) }
        val result = run(engine(), items, limit = 2500)
        assertEquals(FailureReason.SPACE_LIMIT_FULL, result.failure)
        assertEquals(2, result.uploaded)
        assertTrue(drive.folderSize("users/$USER/albums") <= 2500)
    }

    @Test
    fun stopsBeforeTheDriveIsFull() = runTest {
        val small = MemoryDrive(total = BackupEngine.DRIVE_MARGIN_BYTES + 1500)
        val engine = BackupEngine(small, USER, PHONE, reader, MemoryBackupIndex(), { clock++ }, TimeZone.UTC)
        val result = engine.run(listOf(photo(1, "a.jpg", size = 1000), photo(2, "b.jpg", size = 1000, taken = OCT_15 - 1)), BackupSettings(), 100 * GB, BackupTrigger.MANUAL)
        assertEquals(FailureReason.DRIVE_FULL, result.failure)
        assertEquals(1, result.uploaded)
    }

    @Test
    fun driveUnpluggedHalfwayKeepsWhatWasVerifiedAndCarriesOnLater() = runTest {
        val index = MemoryBackupIndex()
        val items = (1L..4L).map { photo(it, "IMG_$it.jpg", taken = OCT_15 + it) }
        drive.failAfterWrites = 2
        val first = BackupEngine(drive, USER, PHONE, reader, index, { clock++ }, TimeZone.UTC, classify = {
            BackupFailure(FailureReason.DRIVE_UNREACHABLE)
        }).run(items, BackupSettings(), 100 * GB, BackupTrigger.SCHEDULED)
        assertEquals(FailureReason.DRIVE_UNREACHABLE, first.failure)
        assertEquals(2, first.uploaded)
        assertEquals(2, index.records.size)
        // Back online: the rest goes up, nothing twice. The manifest for the first two was saved... once the drive came back
        // it may be missing, but the index remembers them.
        drive.offline = false
        drive.failAfterWrites = null
        val second = run(engine(index), items)
        assertTrue(second.succeeded, second.toString())
        assertEquals(2, second.uploaded)
        assertEquals(4, drive.allFiles().count { it.contains("/albums/") })
    }

    @Test
    fun aPhotoDeletedOnThePhoneMeanwhileIsSkipped() = runTest {
        val items = listOf(photo(1, "IMG_1.jpg"), photo(2, "IMG_2.jpg"))
        files.remove(1L)
        val result = run(engine(), items)
        assertTrue(result.succeeded)
        assertEquals(1, result.uploaded)
    }

    @Test
    fun storageSaverUploadsTheSmallerCopyAndMarksIt() = runTest {
        val index = MemoryBackupIndex()
        val result = run(engine(index), listOf(photo(1, "IMG_1.heic", size = 4000)), settings = BackupSettings(storageSaver = true))
        assertTrue(result.succeeded)
        val record = index.records.single()
        assertTrue(record.storageSaver)
        assertEquals(2000, record.sizeBytes)
        assertTrue(record.drivePath.endsWith("IMG_1.jpg"))
    }

    @Test
    fun cleansUpItsOwnLeftoversButNotAnotherPhones() = runTest {
        drive.mkdirs("users/$USER/.incoming")
        drive.createNew("users/$USER/.incoming/$PHONE-old.part", ByteArray(5))
        drive.createNew("users/$USER/.incoming/other-phone-1-uploading.part", ByteArray(5))
        run(engine(), listOf(photo(1, "IMG_1.jpg")))
        val all = drive.allFiles()
        assertFalse("users/$USER/.incoming/$PHONE-old.part" in all)
        assertTrue("users/$USER/.incoming/other-phone-1-uploading.part" in all)
    }

    @Test
    fun namesTheDriveCannotHoldAreMadeSafe() {
        assertEquals("a_b_c.jpg", BackupEngine.safeName("a:b?c.jpg"))
        assertEquals("photo", BackupEngine.safeName("  "))
        assertEquals("_.hidden", BackupEngine.safeName(".hidden"))
        assertTrue(BackupEngine.safeName("x".repeat(300) + ".jpg").length <= 120)
    }

    @Test
    fun lastBackupPerPhoneComesFromTheManifests() = runTest {
        run(engine(), listOf(photo(1, "IMG_1.jpg")))
        val last = BackupCatalog(drive, USER).lastBackups()
        assertEquals(setOf(PHONE), last.keys)
        assertTrue(last.getValue(PHONE) >= OCT_15)
    }
}

class RestoreEngineTest {
    private val drive = MemoryDrive()

    @Test
    fun restoresOnlyWhatIsMissingAndChecksEveryFile() = runTest {
        val files = mapOf(1L to ByteArray(3000) { it.toByte() }, 2L to ByteArray(2000) { (it * 3).toByte() })
        val items = files.map { (id, bytes) ->
            MediaItem(id, "content://$id", MediaType.PHOTO, "IMG_$id.jpg", OCT_15 + id, bytes.size.toLong(), 1, 1, albumId = "c", albumName = "Camera", mimeType = "image/jpeg")
        }
        val reader = object : MediaReader {
            override suspend fun open(item: MediaItem, storageSaver: Boolean) =
                PreparedFile(BytesSource(files.getValue(item.id)), item.displayName, item.mimeType, item.sizeBytes, false)
        }
        BackupEngine(drive, USER, PHONE, reader, MemoryBackupIndex(), { OCT_15 }, TimeZone.UTC).run(items, BackupSettings(), 100 * GB, BackupTrigger.MANUAL)

        val restore = RestoreEngine(drive, USER)
        // Only photo 2 is still on the phone.
        val missing = restore.missing(listOf(items[1]))
        assertEquals(listOf("IMG_1.jpg"), missing.map { it.fileName })

        val written = mutableMapOf<String, ByteArray>()
        val writer = object : MediaWriter {
            override fun begin(record: BackupRecord) = object : RestoreTarget {
                val out = mutableListOf<Byte>()
                override fun write(bytes: ByteArray, length: Int) { for (i in 0 until length) out.add(bytes[i]) }
                override fun commit() { written[record.fileName] = out.toByteArray() }
                override fun abort() = Unit
            }
        }
        val result = restore.restore(missing, writer)
        assertEquals(1, result.restored)
        assertNull(result.failure)
        assertTrue(files.getValue(1).contentEquals(written.getValue("IMG_1.jpg")))
        // After restoring, the restored copy (same name and size, any date) counts as present.
        val restored = items[0].copy(id = 9, displayName = missing.single().fileName, takenAtMillis = 1)
        assertTrue(restore.missing(listOf(items[1], restored)).isEmpty())
    }
}

class ScheduleAndPolicyTest {
    private val zone = TimeZone.of("Australia/Melbourne")

    @Test
    fun defaultIsEverySundayAt2am() {
        val s = BackupSettings()
        assertEquals(Frequency.WEEKLY, s.schedule.frequency)
        assertEquals(7, s.schedule.dayOfWeek)
        assertEquals(2, s.schedule.hour)
        assertTrue(s.chargingOnly)
        assertFalse(s.mobileData)
        assertFalse(s.videos)
        assertEquals(null, s.folders)
        assertEquals("Every Sunday at 2:00 am", s.schedule.describe())
    }

    @Test
    fun nextRunFindsTheNextSundayAndNeverNow() {
        // Fri 23 Oct 2026 04:00 in Melbourne (UTC+11) = Thu 22 Oct 17:00 UTC.
        val friday = 1_792_688_400_000L
        val next = BackupSchedule().nextRun(friday, zone)
        // Sun 25 Oct 2026 02:00 Melbourne = Sat 24 Oct 15:00 UTC.
        assertEquals(1_792_854_000_000L, next)
        assertEquals(next + 7 * 24 * 3600_000L, BackupSchedule().nextRun(next, zone))
        val daily = BackupSchedule(frequency = Frequency.DAILY, hour = 23)
        assertEquals(friday + 19 * 3600_000L, daily.nextRun(friday, zone))
        val monthly = BackupSchedule(frequency = Frequency.MONTHLY, dayOfMonth = 1, hour = 2)
        // 1 Nov 2026 02:00 Melbourne = 31 Oct 15:00 UTC.
        assertEquals(1_793_458_800_000L, monthly.nextRun(friday, zone))
    }

    @Test
    fun severalDaysAWeek() {
        // Fri 23 Oct 2026 04:00 Melbourne. Mondays and Wednesdays at 2 am: next is Mon 26 Oct 2:00 = Sun 25 Oct 15:00 UTC.
        val friday = 1_792_688_400_000L
        val s = BackupSchedule(daysOfWeek = setOf(1, 3))
        assertEquals(1_792_940_400_000L, s.nextRun(friday, zone))
        assertEquals("Mon, Wed at 2:00 am", s.describe())
        assertEquals("Every day", BackupSchedule(daysOfWeek = (1..7).toSet()).daysLabel())
        // Settings saved before (one day) still work.
        assertEquals(setOf(7), BackupSchedule().weekDays)
    }

    @Test
    fun cameraOnlyByDefaultAndOtherFoldersWhenChosen() {
        fun item(id: Long, album: String, video: Boolean = false) =
            MediaItem(id, "u$id", if (video) MediaType.VIDEO else MediaType.PHOTO, "f$id", 0, 1, 1, 1, albumId = album.lowercase(), albumName = album, mimeType = "x")
        val items = listOf(item(1, "Camera"), item(2, "Screenshots"), item(3, "WhatsApp Images"), item(4, "Camera", video = true))
        // Videos are off by default.
        assertEquals(listOf(1L), BackupSelection.eligible(items, BackupSettings()).map { it.id })
        assertEquals(listOf(1L, 4L), BackupSelection.eligible(items, BackupSettings(videos = true)).map { it.id })
        val chosen = BackupSettings(folders = setOf("camera", "whatsapp images"), videos = true)
        assertEquals(listOf(1L, 3L, 4L), BackupSelection.eligible(items, chosen).map { it.id })
        // Videos wait for Wi-Fi unless allowed on mobile data.
        assertEquals(listOf(1L), BackupSelection.eligible(items, BackupSettings(mobileData = true, videos = true), onMobileData = true).map { it.id })
        assertEquals(listOf(1L), BackupSelection.eligible(items, BackupSettings(videos = false)).map { it.id })
    }

    @Test
    fun failedScheduledBackupRetriesOnceThenNotifies() {
        fun run(trigger: BackupTrigger, failure: FailureReason?) = BackupRun(trigger, 0, 0, 1, 0, 0, 0, 0, failure)
        assertEquals(RetryPolicy.Action.RETRY_IN_AN_HOUR, RetryPolicy.after(run(BackupTrigger.SCHEDULED, FailureReason.DRIVE_UNREACHABLE)))
        assertEquals(RetryPolicy.Action.NOTIFY, RetryPolicy.after(run(BackupTrigger.RETRY, FailureReason.DRIVE_UNREACHABLE)))
        assertEquals(RetryPolicy.Action.NOTHING, RetryPolicy.after(run(BackupTrigger.RETRY, null)))
        assertEquals(RetryPolicy.Action.NOTHING, RetryPolicy.after(run(BackupTrigger.MANUAL, FailureReason.DRIVE_UNREACHABLE)))
        assertEquals(RetryPolicy.Action.NOTHING, RetryPolicy.after(run(BackupTrigger.SCHEDULED, FailureReason.STOPPED)))
        assertEquals(60 * 60_000L, RetryPolicy.RETRY_DELAY_MILLIS)
    }
}
