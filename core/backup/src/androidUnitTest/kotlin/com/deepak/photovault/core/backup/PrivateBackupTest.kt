package com.deepak.photovault.core.backup

import com.deepak.photovault.core.crypto.KeyContext
import com.deepak.photovault.core.crypto.MemberKeys
import com.deepak.photovault.core.drive.BytesSource
import com.deepak.photovault.core.drive.MemoryDrive
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.MediaType
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val USER = "user-0001"
private const val PHONE = "phone-0000001"
private const val OCT_15 = 1_792_058_400_000L

class PrivateBackupTest {
    private val drive = MemoryDrive()
    private var clock = OCT_15
    private val files = mutableMapOf<Long, ByteArray>()
    private val context = KeyContext("house-1", USER)
    private val vault = TinkVault(MemberKeys.generate(), context)

    /** Each note gets its own time, as in real use (notes with the same time have no fixed order). */
    private fun tick(): Long { clock += 1000; return clock }

    private val reader = object : MediaReader {
        override suspend fun open(item: MediaItem, storageSaver: Boolean): PreparedFile {
            val bytes = files.getValue(item.id)
            return PreparedFile(BytesSource(bytes), item.displayName, item.mimeType, bytes.size.toLong(), compressed = false)
        }

        override suspend fun thumbnail(item: MediaItem) = "thumb-${item.id}".encodeToByteArray()
    }

    private fun photo(id: Long, name: String, size: Int): MediaItem {
        files[id] = ByteArray(size) { ((it * 7 + id) % 251).toByte() }
        return MediaItem(
            id = id, uri = "content://$id", type = MediaType.PHOTO, displayName = name, takenAtMillis = OCT_15,
            sizeBytes = size.toLong(), width = 10, height = 10, albumId = "camera", albumName = "Camera", mimeType = "image/jpeg",
        )
    }

    private fun engine(v: PrivateVault? = vault, index: BackupIndex = MemoryBackupIndex()) =
        BackupEngine(drive, USER, PHONE, reader, index, { clock++ }, TimeZone.UTC, vault = v)

    private suspend fun backUp(vararg items: MediaItem) =
        engine().run(items.toList(), BackupSettings(), 100_000_000_000L, BackupTrigger.MANUAL)

    @Test
    fun nothingReadableLandsOnTheDrive() = runTest {
        val result = backUp(photo(1, "SECRET_NAME.jpg", 2_500_000))
        assertTrue(result.succeeded, result.failureMessage)
        val all = drive.allFiles()
        assertTrue(all.none { "SECRET_NAME" in it }, all.toString())
        assertTrue(all.none { it.endsWith(".part") })
        assertTrue(all.any { it.startsWith("users/$USER/objects/") && it.endsWith(".pxo") })
        assertTrue(all.any { it.startsWith("users/$USER/thumbs/") && it.endsWith(".pxt") })
        assertTrue(all.any { it.startsWith("users/$USER/manifests/$PHONE/") && it.endsWith(".pxm") })
        val raw = drive.read(all.first { it.endsWith(".pxm") }).decodeToString()
        assertFalse("SECRET_NAME" in raw)
    }

    @Test
    fun catalogAndRestoreWorkWithTheKeyAndNotWithout() = runTest {
        backUp(photo(1, "IMG_1.jpg", 3_000_000), photo(2, "IMG_2.jpg", 10))
        val records = BackupCatalog(drive, USER, vault).records()
        assertEquals(setOf("IMG_1.jpg", "IMG_2.jpg"), records.map { it.originalName }.toSet())
        assertTrue(records.all { it.encrypted })
        // Without the key nothing in the manifests can be read.
        assertTrue(BackupCatalog(drive, USER).records().isEmpty())
        assertTrue(BackupCatalog(drive, USER, TinkVault(MemberKeys.generate(), context)).records().isEmpty())

        val wanted = records.single { it.originalName == "IMG_1.jpg" }
        val got = java.io.ByteArrayOutputStream()
        val writer = object : MediaWriter {
            override fun begin(record: BackupRecord) = object : RestoreTarget {
                override fun write(bytes: ByteArray, length: Int) = got.write(bytes, 0, length)
                override fun commit() = Unit
                override fun abort() = Unit
            }
        }
        val result = RestoreEngine(drive, USER, vault = vault).restore(listOf(wanted), writer)
        assertEquals(1, result.restored)
        assertTrue(files[1]!!.contentEquals(got.toByteArray()))
    }

    @Test
    fun aChangedFileIsNeverRestored() = runTest {
        backUp(photo(1, "IMG_1.jpg", 1_200_000))
        val record = BackupCatalog(drive, USER, vault).records().single()
        val bytes = drive.read(record.drivePath)
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        drive.delete(record.drivePath)
        drive.createNew(record.drivePath, bytes)
        var committed = 0
        val writer = object : MediaWriter {
            override fun begin(record: BackupRecord) = object : RestoreTarget {
                override fun write(bytes: ByteArray, length: Int) = Unit
                override fun commit() { committed++ }
                override fun abort() = Unit
            }
        }
        val result = RestoreEngine(drive, USER, vault = vault).restore(listOf(record), writer)
        assertEquals(0, committed)
        assertEquals(1, result.failed)
    }

    @Test
    fun decryptingSomeoneElsesFileFailsTheCheck() = runTest {
        backUp(photo(1, "IMG_1.jpg", 50_000))
        val record = BackupCatalog(drive, USER, vault).records().single()
        val other = TinkVault(MemberKeys.generate(), context)
        assertFailsWith<ObjectAuthenticationFailed> { other.decryptFile(drive, record.drivePath) { _, _ -> } }
    }

    @Test
    fun sameBackupIsNotRepeatedAndTrashKeepsNamesHidden() = runTest {
        val index = MemoryBackupIndex()
        val item = photo(1, "IMG_1.jpg", 40_000)
        engine(index = index).run(listOf(item), BackupSettings(), 100_000_000_000L, BackupTrigger.MANUAL)
        val second = engine(index = index).run(listOf(item), BackupSettings(), 100_000_000_000L, BackupTrigger.MANUAL)
        assertEquals(0, second.uploaded)

        val catalog = BackupCatalog(drive, USER, vault)
        val record = catalog.records().single()
        catalog.trash(listOf(record), PHONE, tick())
        assertTrue(catalog.records().isEmpty())
        val trashed = catalog.trashed().single()
        assertTrue(drive.allFiles().none { "IMG_1" in it })
        assertNotNull(PrivatePaths.thumbFor(trashed.trashPath))
        assertTrue(drive.exists(PrivatePaths.thumbFor(trashed.trashPath)!!))
        // Nobody without the key sees the trash notes.
        assertTrue(BackupCatalog(drive, USER).trashed().isEmpty())

        catalog.restore(listOf(trashed), PHONE, tick())
        assertEquals(1, catalog.records().size)
        assertTrue(drive.exists(record.drivePath))
        assertTrue(catalog.trashed().isEmpty())

        catalog.trash(catalog.records(), PHONE, tick())
        catalog.deleteForever(catalog.trashed())
        assertTrue(drive.allFiles().none { it.endsWith(".pxo") || it.endsWith(".pxt") || it.contains("/trash/") }, drive.allFiles().toString())
    }
}
