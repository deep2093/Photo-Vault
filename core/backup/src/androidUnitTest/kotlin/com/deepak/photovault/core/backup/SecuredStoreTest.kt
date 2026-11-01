package com.deepak.photovault.core.backup

import com.deepak.photovault.core.crypto.KeyContext
import com.deepak.photovault.core.crypto.MemberKeys
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecuredStoreTest {
    private val dir = File.createTempFile("secured", "dir").let { it.delete(); it.mkdirs(); it }
    private val keys = MemberKeys.generate()
    private val context = KeyContext("secured", "folder")
    private fun store(k: MemberKeys = keys) = SecuredStore(dir, TinkVault(k, context))
    private val photo = ByteArray(300_000) { ((it * 13) % 251).toByte() }

    private suspend fun SecuredStore.addPhoto(bytes: ByteArray = photo, thumb: ByteArray? = "thumb".encodeToByteArray()) =
        add("SECRET_NAME.jpg", "image/jpeg", 1L, 10, 10, "Camera", thumb) { ByteArrayInputStream(bytes) }

    @Test
    fun aStoredPhotoComesBackExactlyAndNothingReadableIsOnDisk() = runTest {
        val s = store()
        val e = assertNotNull(s.addPhoto())
        assertEquals(photo.size.toLong(), e.sizeBytes)
        assertContentEquals(photo, s.photo(e.id))
        assertContentEquals("thumb".encodeToByteArray(), s.thumbnail(e.id))
        assertEquals(listOf(e.id), s.list().map { it.id })
        dir.listFiles().orEmpty().forEach { f ->
            assertFalse("SECRET_NAME" in f.name)
            assertFalse(String(f.readBytes(), Charsets.ISO_8859_1).contains("SECRET_NAME"), f.name)
        }
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun aMissingThumbnailOrEmptyPhotoIsRefusedAndLeavesNothing() = runTest {
        val s = store()
        assertNull(s.addPhoto(thumb = null))
        assertNull(s.addPhoto(bytes = ByteArray(0)))
        assertTrue(s.list().isEmpty())
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".pxs") || it.name.endsWith(".pxt") })
    }

    @Test
    fun theWrongKeyCannotOpenTheListOrThePhoto() = runTest {
        val e = assertNotNull(store().addPhoto())
        val other = store(MemberKeys.generate())
        assertNull(other.photo(e.id))
        assertFailsToList(other)
    }

    private suspend fun assertFailsToList(s: SecuredStore) {
        val failed = runCatching { s.list() }.isFailure
        assertTrue(failed, "a list that can't be opened must not look empty")
    }

    @Test
    fun aChangedFileFailsItsCheckAndRemoveDeletesEverything() = runTest {
        val s = store()
        val e = assertNotNull(s.addPhoto())
        val file = File(dir, "${e.id}.pxs")
        val bytes = file.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        file.writeBytes(bytes)
        assertNull(s.photo(e.id))
        assertFalse(s.copyOut(e.id) { _, _ -> })
        s.remove(listOf(e.id))
        assertTrue(s.list().isEmpty())
        assertFalse(file.exists())
        assertFalse(File(dir, "${e.id}.pxt").exists())
    }
}
