package com.deepak.photovault.core.smb

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Against a real Samba share that CI starts (tools/start_test_share.sh). Skipped everywhere else.
 * Each test works in its own folder, so runs never interfere.
 */
class RealShareTest {
    private val profile = SmbProfile(host = "127.0.0.1", share = "family", rootPath = "", username = "pvfixture", password = "ci-fixture-only")
    private lateinit var root: String

    @Before
    fun onlyWithFixture() {
        assumeTrue("Real SMB share not available", System.getenv("PV_SMB_FIXTURE") == "1")
        root = "PhotoVault-test-" + UUID.randomUUID().toString().take(8)
    }

    @Test
    fun createNewIsAtomicAndNeverOverwrites() = runBlocking {
        SmbDriveFileSystem(profile, root).use { drive ->
            drive.mkdirs("system/household")
            assertTrue(drive.createNew("system/household/v000001.json", "first".encodeToByteArray()))
            assertFalse(drive.createNew("system/household/v000001.json", "second".encodeToByteArray()))
            assertArrayEquals("first".encodeToByteArray(), drive.read("system/household/v000001.json"))
            // No temporary files are left behind.
            assertEquals(listOf("v000001.json"), drive.list("system/household").map { it.name })
        }
    }

    @Test
    fun twoPhonesRacingForTheSameNameOnlyOneWins() = runBlocking {
        val a = SmbDriveFileSystem(profile, root)
        val b = SmbDriveFileSystem(profile, root)
        a.mkdirs("system/household")
        val results = listOf(a, b).mapIndexed { i, drive ->
            async(kotlinx.coroutines.Dispatchers.IO) { drive.createNew("system/household/v000002.json", "phone $i".encodeToByteArray()) }
        }.awaitAll()
        assertEquals(1, results.count { it })
        a.close()
        b.close()
    }

    @Test
    fun foldersSizesAndSpace() = runBlocking {
        SmbDriveFileSystem(profile, root).use { drive ->
            drive.mkdirs("users/u1/originals/2026/10")
            drive.mkdirs("users/u1/originals/2026/10") // twice is fine
            drive.createNew("users/u1/originals/2026/10/a.jpg", ByteArray(1000))
            drive.createNew("users/u1/videos.txt", ByteArray(234))
            assertEquals(1234L, drive.folderSize("users/u1"))
            assertEquals(0L, drive.folderSize("users/nobody"))
            assertTrue(drive.list("missing").isEmpty())
            val space = drive.space()
            assertTrue(space.totalBytes > 0 && space.freeBytes in 0..space.totalBytes)
            drive.delete("users/u1/videos.txt")
            assertFalse(drive.exists("users/u1/videos.txt"))
        }
    }

    @Test
    fun streamsBigFilesAndRenamesWithoutReplacing() = runBlocking {
        SmbDriveFileSystem(profile, root).use { drive ->
            drive.mkdirs("users/u1/.incoming")
            drive.mkdirs("users/u1/originals")
            // Bigger than one 256 KB chunk, so the stream really loops.
            val big = ByteArray(1_300_000) { (it * 31 % 251).toByte() }
            assertTrue(drive.writeNew("users/u1/.incoming/a.part", com.deepak.photovault.core.drive.BytesSource(big)))
            assertFalse(drive.writeNew("users/u1/.incoming/a.part", com.deepak.photovault.core.drive.BytesSource(ByteArray(3))))
            val back = java.io.ByteArrayOutputStream()
            drive.readStream("users/u1/.incoming/a.part") { b, n -> back.write(b, 0, n) }
            assertArrayEquals(big, back.toByteArray())
            assertTrue(drive.rename("users/u1/.incoming/a.part", "users/u1/originals/a.jpg"))
            // A second file can never replace the first.
            drive.writeNew("users/u1/.incoming/b.part", com.deepak.photovault.core.drive.BytesSource(ByteArray(10)))
            assertFalse(drive.rename("users/u1/.incoming/b.part", "users/u1/originals/a.jpg"))
            assertEquals(1_300_000L, drive.list("users/u1/originals").single().size)
        }
    }

    @Test
    fun backupEngineVerifiesAndPublishesOnARealShare() = runBlocking {
        SmbDriveFileSystem(profile, root).use { drive ->
            val photos = (1L..3L).associateWith { id -> ByteArray(400_000 + id.toInt()) { (it * id % 253).toByte() } }
            val items = photos.map { (id, bytes) ->
                com.deepak.photovault.core.media.MediaItem(
                    id, "content://$id", com.deepak.photovault.core.media.MediaType.PHOTO, "IMG_$id.jpg", 1_792_058_400_000L + id,
                    bytes.size.toLong(), 1, 1, albumId = "camera", albumName = "Camera", mimeType = "image/jpeg",
                )
            }
            val reader = object : com.deepak.photovault.core.backup.MediaReader {
                override suspend fun open(item: com.deepak.photovault.core.media.MediaItem, storageSaver: Boolean) =
                    com.deepak.photovault.core.backup.PreparedFile(
                        com.deepak.photovault.core.drive.BytesSource(photos.getValue(item.id)), item.displayName, item.mimeType, item.sizeBytes, false,
                    )
            }
            // A different file already has one of the names: it must survive untouched.
            drive.mkdirs("users/u1/originals/2026/10")
            drive.createNew("users/u1/originals/2026/10/IMG_1.jpg", "not yours".encodeToByteArray())
            val index = com.deepak.photovault.core.backup.MemoryBackupIndex()
            val engine = com.deepak.photovault.core.backup.BackupEngine(
                drive, "u1", "phone-test-0001", reader, index, System::currentTimeMillis, kotlinx.datetime.TimeZone.UTC,
            )
            val run = engine.run(items, com.deepak.photovault.core.backup.BackupSettings(), 10_000_000_000L, com.deepak.photovault.core.backup.BackupTrigger.MANUAL)
            assertTrue(run.toString(), run.succeeded)
            assertEquals(3, run.uploaded)
            assertArrayEquals("not yours".encodeToByteArray(), drive.read("users/u1/originals/2026/10/IMG_1.jpg"))
            val back = java.io.ByteArrayOutputStream()
            drive.readStream("users/u1/originals/2026/10/IMG_1 (2).jpg") { b, n -> back.write(b, 0, n) }
            assertArrayEquals(photos.getValue(1L), back.toByteArray())
            assertTrue(drive.list("users/u1/.incoming").isEmpty())
            assertEquals(1, drive.list("users/u1/manifests/phone-test-0001").size)
            // Again: nothing new.
            assertEquals(0, engine.run(items, com.deepak.photovault.core.backup.BackupSettings(), 10_000_000_000L, com.deepak.photovault.core.backup.BackupTrigger.MANUAL).uploaded)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun pathsCannotEscapeThePhotoVaultFolder() = runBlocking<Unit> {
        SmbDriveFileSystem(profile, root).use { it.read("../outside.txt") }
    }
}
