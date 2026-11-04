package com.deepak.photovault.core.backup

import com.deepak.photovault.core.drive.ByteSource
import com.deepak.photovault.core.drive.BytesSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** One photo kept in the Secured folder. [sha256] and [sizeBytes] are of the plain photo, for the check when it moves out. */
@Serializable
class SecuredEntry(
    val id: String,
    val name: String,
    val mimeType: String,
    val takenAtMillis: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val album: String,
    val sha256: String,
    val addedAtMillis: Long,
    val isVideo: Boolean = false,
    val durationMillis: Long = 0,
)

/**
 * The Secured folder's files on the phone (Phase 8A slice 2, 1 Nov): every photo is encrypted with the folder's own key
 * ([PrivateVault]) into `<id>.pxs`, with an encrypted small picture `<id>.pxt`, and a sealed list `index.pxi` that
 * holds the names. Nothing readable is ever written here. A photo counts as stored only after the encrypted file has
 * been read back, decrypted and matched with the original's checksum.
 */
class SecuredStore(private val dir: File, private val vault: PrivateVault, private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val indexFile get() = File(dir, "index.pxi")

    suspend fun list(): List<SecuredEntry> = lock.withLock { readIndex() }

    /** Throws when the list exists but can't be opened: it is never replaced by an empty one. */
    private fun readIndex(): List<SecuredEntry> {
        if (!indexFile.exists()) return emptyList()
        val plain = vault.openRecord(indexFile.readBytes(), PURPOSE) ?: throw IOException("The Secured folder's list can't be opened")
        return json.decodeFromString(ListSerializer(SecuredEntry.serializer()), plain.decodeToString())
    }

    private fun writeIndex(entries: List<SecuredEntry>) {
        dir.mkdirs()
        val sealed = vault.sealRecord(json.encodeToString(ListSerializer(SecuredEntry.serializer()), entries).encodeToByteArray(), PURPOSE)
        val tmp = File(dir, "index.pxi.tmp")
        tmp.writeBytes(sealed)
        if (!tmp.renameTo(indexFile)) throw IOException("The Secured folder's list couldn't be saved")
    }

    /**
     * Encrypts one photo read from [open], checks it, and adds it to the list. Null when anything fails (nothing is
     * left behind and the original is untouched).
     */
    suspend fun add(
        name: String,
        mimeType: String,
        takenAtMillis: Long,
        width: Int,
        height: Int,
        album: String,
        thumbnail: ByteArray?,
        isVideo: Boolean = false,
        durationMillis: Long = 0,
        open: () -> InputStream,
    ): SecuredEntry? = lock.withLock {
        if (thumbnail == null) return@withLock null
        dir.mkdirs()
        val id = vault.newObjectId()
        val media = File(dir, "$id.pxs")
        val tmp = File(dir, "$id.pxs.tmp")
        val thumbFile = File(dir, "$id.pxt")
        try {
            val before = readIndex()
            val hasher = newSha256()
            var size = 0L
            val input = open()
            val source = object : ByteSource {
                override fun read(buffer: ByteArray): Int {
                    val n = input.read(buffer)
                    if (n > 0) {
                        hasher.update(buffer, 0, n)
                        size += n
                    }
                    return n
                }

                override fun close() = input.close()
            }
            tmp.outputStream().use { out ->
                vault.encrypt(source, ObjectKind.MEDIA, id).use { enc ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val n = enc.read(buffer)
                        if (n < 0) break
                        if (n > 0) out.write(buffer, 0, n)
                    }
                }
            }
            val sha = hasher.hex()
            val checker = newSha256()
            var checked = 0L
            try {
                vault.decrypt(ObjectKind.MEDIA, id, { sink -> feed(tmp, sink) }) { b, n ->
                    checker.update(b, 0, n)
                    checked += n
                }
            } catch (_: ObjectAuthenticationFailed) {
                tmp.delete()
                return@withLock null
            }
            if (checker.hex() != sha || checked != size || size == 0L) {
                tmp.delete()
                return@withLock null
            }
            check(tmp.renameTo(media)) { "Name already in use" }
            thumbFile.outputStream().use { out ->
                vault.encrypt(BytesSource(thumbnail), ObjectKind.THUMB, id).use { enc ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val n = enc.read(buffer)
                        if (n < 0) break
                        if (n > 0) out.write(buffer, 0, n)
                    }
                }
            }
            val entry = SecuredEntry(id, name, mimeType, takenAtMillis, size, width, height, album, sha, now(), isVideo, durationMillis)
            writeIndex(before + entry)
            entry
        } catch (e: CancellationException) {
            media.delete(); tmp.delete(); thumbFile.delete()
            throw e
        } catch (_: Exception) {
            media.delete(); tmp.delete(); thumbFile.delete()
            null
        }
    }

    /** The small picture, decrypted, or null when it is missing or fails its check. */
    suspend fun thumbnail(id: String): ByteArray? = lock.withLock { plain(ObjectKind.THUMB, id) }

    /** The whole photo, decrypted in memory (photos only; for the viewer). */
    suspend fun photo(id: String): ByteArray? = lock.withLock { plain(ObjectKind.MEDIA, id) }

    private suspend fun plain(kind: ObjectKind, id: String): ByteArray? {
        val file = fileFor(kind, id) ?: return null
        if (!file.exists()) return null
        return try {
            val out = ByteArrayOutputStream()
            vault.decrypt(kind, id, { sink -> feed(file, sink) }) { b, n -> out.write(b, 0, n) }
            out.toByteArray()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Decrypts a photo to [sink], chunk by chunk, with the usual check. False if it fails. */
    suspend fun copyOut(id: String, sink: (ByteArray, Int) -> Unit): Boolean = lock.withLock {
        val file = fileFor(ObjectKind.MEDIA, id)?.takeIf { it.exists() } ?: return@withLock false
        try {
            vault.decrypt(ObjectKind.MEDIA, id, { s -> feed(file, s) }, sink)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** Decrypts a photo or video into [target] (for playing or sharing). The file is plain: the caller erases it soon. False if it fails. */
    suspend fun decryptTo(id: String, target: File): Boolean {
        val tmp = File(target.parentFile, target.name + ".part")
        val ok = try {
            target.parentFile?.mkdirs()
            tmp.outputStream().use { out -> copyOut(id) { b, n -> out.write(b, 0, n) } }
        } catch (e: CancellationException) {
            tmp.delete()
            throw e
        } catch (_: Exception) {
            false
        }
        if (!ok || !tmp.renameTo(target)) {
            tmp.delete()
            return false
        }
        return true
    }

    /** Takes these photos out of the folder for good (their files and list entries). */
    suspend fun remove(ids: Collection<String>) = lock.withLock {
        val entries = readIndex()
        val gone = entries.filter { it.id in ids }
        if (gone.isEmpty()) return@withLock
        writeIndex(entries - gone.toSet())
        // Remembered so the drive copy is deleted too at the next copy (8A part 2).
        runCatching { File(dir, DELETED).appendText(gone.joinToString("") { it.id + "\n" }) }
        gone.forEach { e ->
            fileFor(ObjectKind.MEDIA, e.id)?.delete()
            fileFor(ObjectKind.THUMB, e.id)?.delete()
        }
    }

    /** Items deleted (or moved out) on this phone whose drive copy still has to go. */
    suspend fun pendingDeletes(): Set<String> = lock.withLock {
        runCatching { File(dir, DELETED).readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() }.getOrDefault(emptySet())
    }

    /** The drive copy of these is gone: forget them. */
    suspend fun forgetDeletes(ids: Set<String>) = lock.withLock {
        val file = File(dir, DELETED)
        val left = runCatching { file.readLines().map { it.trim() }.filter { it.isNotEmpty() && it !in ids } }.getOrDefault(emptyList())
        if (left.isEmpty()) file.delete() else file.writeText(left.joinToString("") { it + "\n" })
    }

    /** The list sealed with the folder's key, for the drive copy. */
    fun seal(entries: List<SecuredEntry>): ByteArray =
        vault.sealRecord(json.encodeToString(ListSerializer(SecuredEntry.serializer()), entries).encodeToByteArray(), PURPOSE)

    /** A sealed list from the drive, or null when it isn't this folder's or was changed. */
    fun open(sealed: ByteArray): List<SecuredEntry>? = runCatching {
        val plain = vault.openRecord(sealed, PURPOSE) ?: return null
        json.decodeFromString(ListSerializer(SecuredEntry.serializer()), plain.decodeToString())
    }.getOrNull()

    /** The encrypted file of an item on this phone ([thumb] for its small picture), or null. */
    fun encryptedFile(id: String, thumb: Boolean): File? = fileFor(if (thumb) ObjectKind.THUMB else ObjectKind.MEDIA, id)

    /**
     * Takes in an item copied from the drive: [media] and [thumb] are its encrypted files. Kept only when the photo
     * decrypts and matches the list's size and checksum; the files are then moved into the folder.
     */
    suspend fun adopt(entry: SecuredEntry, media: File, thumb: File): Boolean = lock.withLock {
        val target = fileFor(ObjectKind.MEDIA, entry.id) ?: return@withLock false
        val targetThumb = fileFor(ObjectKind.THUMB, entry.id) ?: return@withLock false
        try {
            val entries = readIndex()
            if (entries.any { it.id == entry.id }) return@withLock true
            val checker = newSha256()
            var size = 0L
            vault.decrypt(ObjectKind.MEDIA, entry.id, { sink -> feed(media, sink) }) { b, n ->
                checker.update(b, 0, n)
                size += n
            }
            if (size != entry.sizeBytes || checker.hex() != entry.sha256) return@withLock false
            vault.decrypt(ObjectKind.THUMB, entry.id, { sink -> feed(thumb, sink) }) { _, _ -> }
            dir.mkdirs()
            if (!media.renameTo(target) || !thumb.renameTo(targetThumb)) {
                target.delete(); targetThumb.delete()
                return@withLock false
            }
            writeIndex(entries + entry)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private fun fileFor(kind: ObjectKind, id: String): File? =
        if (id.isNotEmpty() && id.all { it.isLetterOrDigit() }) File(dir, "$id.${if (kind == ObjectKind.MEDIA) "pxs" else "pxt"}") else null

    private fun feed(file: File, sink: (ByteArray, Int) -> Unit) {
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) sink(buffer, n)
            }
        }
    }

    private companion object {
        const val PURPOSE = "secured-index-v1"
        const val DELETED = "deleted.txt"
        const val BUFFER = 64 * 1024
    }
}
