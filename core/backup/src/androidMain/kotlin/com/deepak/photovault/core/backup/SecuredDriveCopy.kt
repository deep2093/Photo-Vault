package com.deepak.photovault.core.backup

import com.deepak.photovault.core.drive.ByteSource
import com.deepak.photovault.core.drive.DriveFileSystem
import com.deepak.photovault.core.drive.DrivePaths
import com.deepak.photovault.core.household.HouseholdStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.uuid.Uuid

/** What a copy to the drive did. */
class SecuredCopyResult(val copied: Int, val deleted: Int, val failed: Int, val total: Int)

/** What a restore from the drive did. */
class SecuredRestoreResult(val restored: Int, val failed: Int, val total: Int)

/**
 * The Secured folder's copy on the family drive (Phase 8A part 2, 5 Nov). The phone's files are already encrypted with
 * the folder's own key, so they are copied as they are: nothing readable reaches the drive, not even names (the list
 * that holds them is sealed with the same key). Each file is read back from the drive and compared with the phone's
 * before it counts. A new phone gets the key back from the recovery (24 words or own code) and copies everything home.
 *
 * Drive layout: `users/<id>/secured/recovery.pxr` (the key, locked by the recovery), `objects/<id>.pxs` and `.pxt`,
 * and `index-<time>.pxi` (the newest one is used).
 */
class SecuredDriveCopy(private val drive: DriveFileSystem, userId: String, private val store: SecuredStore, private val now: () -> Long = System::currentTimeMillis) {
    private val folder = folderOf(userId)
    private val objects = DrivePaths.join(folder, "objects")

    /** Copies everything new up, deletes what was deleted on this phone, and writes the new list. */
    suspend fun upload(recovery: ByteArray, onProgress: (Int, Int) -> Unit = { _, _ -> }): SecuredCopyResult {
        drive.mkdirs(objects)
        val recoveryPath = DrivePaths.join(folder, RECOVERY)
        if (!drive.exists(recoveryPath)) drive.createNew(recoveryPath, recovery)
        // Read first: if the drive's list can't be opened with this key, stop before anything is sent.
        val (previous, oldNames) = newestList()
        val local = store.list()
        val deletes = store.pendingDeletes()
        val onDrive = drive.list(objects).filter { !it.isFolder }.associate { it.name to it.size }
        var copied = 0
        var failed = 0
        val kept = mutableListOf<SecuredEntry>()
        local.forEachIndexed { i, entry ->
            currentCoroutineContext().ensureActive()
            onProgress(i, local.size)
            var ok = true
            var sent = false
            for (thumb in listOf(false, true)) {
                val file = store.encryptedFile(entry.id, thumb)
                if (file == null || !file.exists()) { ok = false; continue }
                if (onDrive[file.name] == file.length()) continue
                if (put(file)) sent = true else ok = false
            }
            if (ok) kept += entry else failed++
            if (ok && sent) copied++
        }
        onProgress(local.size, local.size)
        for (id in deletes) {
            drive.delete(DrivePaths.join(objects, "$id.pxs"))
            drive.delete(DrivePaths.join(objects, "$id.pxt"))
        }
        val localIds = local.map { it.id }.toSet()
        val merged = previous.filter { it.id !in deletes && it.id !in localIds } + kept
        var at = now()
        while (!drive.createNew(DrivePaths.join(folder, "index-$at.pxi"), store.seal(merged))) at++
        oldNames.forEach { drive.delete(DrivePaths.join(folder, it)) }
        store.forgetDeletes(deletes)
        return SecuredCopyResult(copied, deletes.size, failed, local.size)
    }

    /** Brings home every item on the drive that this phone doesn't have (and didn't delete). */
    suspend fun download(work: File, onProgress: (Int, Int) -> Unit = { _, _ -> }): SecuredRestoreResult {
        val (entries, _) = newestList()
        val have = store.list().map { it.id }.toSet()
        val deletes = store.pendingDeletes()
        val wanted = entries.filter { it.id !in have && it.id !in deletes }
        var restored = 0
        var failed = 0
        work.mkdirs()
        wanted.forEachIndexed { i, entry ->
            currentCoroutineContext().ensureActive()
            onProgress(i, wanted.size)
            val media = File(work, "${entry.id}.pxs.part")
            val thumb = File(work, "${entry.id}.pxt.part")
            val ok = try {
                fetch(DrivePaths.join(objects, "${entry.id}.pxs"), media) &&
                    fetch(DrivePaths.join(objects, "${entry.id}.pxt"), thumb) &&
                    store.adopt(entry, media, thumb)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            } finally {
                media.delete()
                thumb.delete()
            }
            if (ok) restored++ else failed++
        }
        onProgress(wanted.size, wanted.size)
        return SecuredRestoreResult(restored, failed, wanted.size)
    }

    /** The newest list on the drive that opens with this folder's key, and the names of all list files. */
    private suspend fun newestList(): Pair<List<SecuredEntry>, List<String>> {
        val names = drive.list(folder).filter { !it.isFolder && it.name.startsWith("index-") && it.name.endsWith(".pxi") }.map { it.name }
        val newestFirst = names.sortedByDescending { it.removePrefix("index-").removeSuffix(".pxi").toLongOrNull() ?: 0L }
        for (name in newestFirst) {
            val opened = runCatching { store.open(drive.read(DrivePaths.join(folder, name))) }.getOrNull()
            if (opened != null) return opened to names
        }
        // A list that exists but doesn't open is never treated as empty: nothing on the drive is replaced then.
        if (names.isNotEmpty()) throw IllegalStateException("The Secured folder's list on the drive can't be opened with this key")
        return emptyList<SecuredEntry>() to names
    }

    /** Copies one encrypted file up under a temporary name, checks it, then gives it its real name. */
    private suspend fun put(file: File): Boolean {
        val final = DrivePaths.join(objects, file.name)
        val temp = DrivePaths.join(objects, "${file.name}.${Uuid.random()}.part")
        return try {
            val written = file.inputStream().use { input ->
                drive.writeNew(temp, object : ByteSource {
                    override fun read(buffer: ByteArray): Int = input.read(buffer)
                    override fun close() = Unit
                })
            }
            if (!written || hashOnDrive(temp) != hashOf(file)) {
                drive.delete(temp)
                return false
            }
            drive.delete(final) // a half copy from an earlier try
            if (drive.rename(temp, final)) true else { drive.delete(temp); false }
        } catch (e: CancellationException) {
            runCatching { drive.delete(temp) }
            throw e
        } catch (_: Exception) {
            runCatching { drive.delete(temp) }
            false
        }
    }

    private suspend fun fetch(path: String, to: File): Boolean {
        to.parentFile?.mkdirs()
        to.outputStream().use { out -> drive.readStream(path) { b, n -> out.write(b, 0, n) } }
        return to.length() > 0
    }

    private suspend fun hashOnDrive(path: String): Pair<String, Long> {
        val h = newSha256()
        var size = 0L
        drive.readStream(path) { b, n -> h.update(b, 0, n); size += n }
        return h.hex() to size
    }

    private fun hashOf(file: File): Pair<String, Long> {
        val h = newSha256()
        var size = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                h.update(buffer, 0, n)
                size += n
            }
        }
        return h.hex() to size
    }

    companion object {
        const val RECOVERY = "recovery.pxr"

        fun folderOf(userId: String) = DrivePaths.join(HouseholdStore.userFolder(userId), "secured")

        /** The locked key on the drive, for a new phone; null when this person has no Secured folder copy. */
        suspend fun recovery(drive: DriveFileSystem, userId: String): ByteArray? =
            runCatching { drive.read(DrivePaths.join(folderOf(userId), RECOVERY)) }.getOrNull()
    }
}
