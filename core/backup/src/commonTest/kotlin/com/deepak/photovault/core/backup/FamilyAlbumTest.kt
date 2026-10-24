package com.deepak.photovault.core.backup

import com.deepak.photovault.core.drive.MemoryDrive
import com.deepak.photovault.core.household.HouseholdStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FamilyAlbumTest {
    private val drive = MemoryDrive()
    private val a = "user-aaaa"
    private val b = "user-bbbb"

    private fun record(user: String, name: String, taken: Long) = BackupRecord(
        key = BackupSelection.keyOf(name, 10, taken),
        drivePath = "${HouseholdStore.userFolder(user)}/albums/Camera/2026/10/$name",
        sha256 = "x", sizeBytes = 10, originalName = name, takenAtMillis = taken, mimeType = "image/jpeg",
        isVideo = false, savedAtMillis = 1, deviceId = "d",
    )

    /** Makes the backup real on the drive, as a manifest, so the album can tell it still exists. */
    private suspend fun backedUp(user: String, vararg rs: BackupRecord) {
        val folder = "${HouseholdStore.userFolder(user)}/manifests/dev1"
        drive.mkdirs(folder)
        rs.forEach { drive.mkdirs(it.drivePath.substringBeforeLast('/')); drive.createNew(it.drivePath, ByteArray(10)) }
        val m = Manifest(deviceId = "dev1", createdAtMillis = 1, records = rs.toList())
        drive.createNew("$folder/${BackupEngine.manifestName(1)}", BackupEngine.json.encodeToString(Manifest.serializer(), m).encodeToByteArray())
    }

    @Test
    fun everyoneSeesWhatEachPersonAddedNewestFirst() = runTest {
        val r1 = record(a, "a1.jpg", 100)
        val r2 = record(b, "b1.jpg", 200)
        backedUp(a, r1)
        backedUp(b, r2)
        FamilyAlbum(drive, a).add(listOf(r1), 10)
        FamilyAlbum(drive, b).add(listOf(r2), 11)
        val seen = FamilyAlbum(drive, a).records(listOf(a, b))
        assertEquals(listOf("b1.jpg", "a1.jpg"), seen.map { it.originalName })
    }

    @Test
    fun aPersonCanOnlyAddAndRemoveTheirOwnPhotos() = runTest {
        val theirs = record(b, "b1.jpg", 200)
        assertFailsWith<IllegalArgumentException> { FamilyAlbum(drive, a).add(listOf(theirs), 10) }
        val r1 = record(a, "a1.jpg", 100)
        backedUp(a, r1)
        backedUp(b, theirs)
        FamilyAlbum(drive, b).add(listOf(theirs), 10)
        // A cannot take B's photo out.
        FamilyAlbum(drive, a).remove(listOf(theirs.drivePath), 20)
        assertEquals(1, FamilyAlbum(drive, a).records(listOf(a, b)).size)
        FamilyAlbum(drive, b).remove(listOf(theirs.drivePath), 21)
        assertTrue(FamilyAlbum(drive, a).records(listOf(a, b)).isEmpty())
    }

    @Test
    fun aPhotoDeletedFromItsOwnersBackupLeavesTheAlbum() = runTest {
        val r1 = record(a, "a1.jpg", 100)
        backedUp(a, r1)
        FamilyAlbum(drive, a).add(listOf(r1), 10)
        BackupCatalog(drive, a).trash(listOf(r1), "dev1", 50)
        assertTrue(FamilyAlbum(drive, b).records(listOf(a)).isEmpty())
    }
}
