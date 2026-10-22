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

    @Test(expected = IllegalArgumentException::class)
    fun pathsCannotEscapeThePhotoVaultFolder() = runBlocking<Unit> {
        SmbDriveFileSystem(profile, root).use { it.read("../outside.txt") }
    }
}
