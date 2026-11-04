package com.deepak.photovault.core.backup

import com.deepak.photovault.core.crypto.KeyContext
import com.deepak.photovault.core.crypto.MemberKeys
import com.deepak.photovault.core.drive.MemoryDrive
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val USER = "user-0001"

class SecuredDriveCopyTest {
    private val drive = MemoryDrive()
    private val keys = MemberKeys.generate()
    private val context = KeyContext("secured", "folder")
    private var clock = 1_000L

    private fun tempDir(name: String) = File.createTempFile(name, "dir").let { it.delete(); it.mkdirs(); it }

    private fun store(dir: File, k: MemberKeys = keys) = SecuredStore(dir, TinkVault(k, context))

    private fun copy(s: SecuredStore) = SecuredDriveCopy(drive, USER, s) { clock++ }

    private fun bytes(seed: Int, size: Int = 200_000) = ByteArray(size) { ((it * seed) % 251).toByte() }

    private suspend fun SecuredStore.put(name: String, data: ByteArray) =
        assertNotNull(add(name, "image/jpeg", 1L, 10, 10, "Camera", "thumb-$name".encodeToByteArray()) { ByteArrayInputStream(data) })

    @Test
    fun aNewPhoneGetsEverythingBackAndNothingReadableIsOnTheDrive() = runTest {
        val phoneA = store(tempDir("a"))
        val one = phoneA.put("SECRET_ONE.jpg", bytes(3))
        val two = phoneA.put("SECRET_TWO.jpg", bytes(7))
        val up = copy(phoneA).upload("recovery".encodeToByteArray())
        assertEquals(2, up.copied)
        assertEquals(0, up.failed)
        drive.allFiles().forEach { path ->
            assertFalse("SECRET" in path, path)
            assertFalse(drive.read(path).toString(Charsets.ISO_8859_1).contains("SECRET"), path)
        }
        // A second copy has nothing new to send.
        assertEquals(0, copy(phoneA).upload("recovery".encodeToByteArray()).copied)

        val dirB = tempDir("b")
        val phoneB = store(dirB)
        val down = copy(phoneB).download(File(dirB.parentFile, dirB.name + "-work"))
        assertEquals(2, down.restored)
        assertEquals(setOf(one.id, two.id), phoneB.list().map { it.id }.toSet())
        assertContentEquals(bytes(3), phoneB.photo(one.id))
        assertContentEquals("thumb-SECRET_TWO.jpg".encodeToByteArray(), phoneB.thumbnail(two.id))
    }

    @Test
    fun deletingOnThePhoneDeletesTheDriveCopyAndItDoesNotComeBack() = runTest {
        val phoneA = store(tempDir("a"))
        val keep = phoneA.put("keep.jpg", bytes(3))
        val gone = phoneA.put("gone.jpg", bytes(5))
        copy(phoneA).upload("r".encodeToByteArray())
        phoneA.remove(listOf(gone.id))
        val up = copy(phoneA).upload("r".encodeToByteArray())
        assertEquals(1, up.deleted)
        assertTrue(drive.allFiles().none { gone.id in it })
        assertTrue(phoneA.pendingDeletes().isEmpty())
        val dirB = tempDir("b")
        val phoneB = store(dirB)
        copy(phoneB).download(File(dirB.parentFile, dirB.name + "-work"))
        assertEquals(listOf(keep.id), phoneB.list().map { it.id })
    }

    @Test
    fun itemsOnlyOnTheDriveAreKeptWhenAnotherPhoneCopies() = runTest {
        val phoneA = store(tempDir("a"))
        val fromA = phoneA.put("a.jpg", bytes(3))
        copy(phoneA).upload("r".encodeToByteArray())
        // Phone B has the same folder key but hasn't restored yet; its own copy must not wipe A's item from the list.
        val phoneB = store(tempDir("b"))
        val fromB = phoneB.put("b.jpg", bytes(9))
        copy(phoneB).upload("r".encodeToByteArray())
        val dirC = tempDir("c")
        val phoneC = store(dirC)
        copy(phoneC).download(File(dirC.parentFile, dirC.name + "-work"))
        assertEquals(setOf(fromA.id, fromB.id), phoneC.list().map { it.id }.toSet())
    }

    @Test
    fun aDamagedDriveCopyIsNotTakenIn() = runTest {
        val phoneA = store(tempDir("a"))
        val e = phoneA.put("a.jpg", bytes(3))
        copy(phoneA).upload("r".encodeToByteArray())
        val path = drive.allFiles().first { it.endsWith("${e.id}.pxs") }
        val damaged = drive.read(path).also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        drive.delete(path)
        drive.createNew(path, damaged)
        val dirB = tempDir("b")
        val phoneB = store(dirB)
        val down = copy(phoneB).download(File(dirB.parentFile, dirB.name + "-work"))
        assertEquals(0, down.restored)
        assertEquals(1, down.failed)
        assertTrue(phoneB.list().isEmpty())
    }

    @Test
    fun theWrongKeyNeverReplacesTheDriveList() = runTest {
        val phoneA = store(tempDir("a"))
        phoneA.put("a.jpg", bytes(3))
        copy(phoneA).upload("r".encodeToByteArray())
        val stranger = store(tempDir("x"), MemberKeys.generate())
        stranger.put("x.jpg", bytes(4))
        assertTrue(runCatching { copy(stranger).upload("r".encodeToByteArray()) }.isFailure)
    }
}
