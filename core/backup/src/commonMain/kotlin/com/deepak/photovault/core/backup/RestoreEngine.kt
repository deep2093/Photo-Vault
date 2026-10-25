package com.deepak.photovault.core.backup

import com.deepak.photovault.core.drive.DriveFileSystem
import com.deepak.photovault.core.media.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Where restored photos are written on the phone. Android: a new MediaStore entry, published only when complete. */
interface MediaWriter {
    fun begin(record: BackupRecord): RestoreTarget
}

interface RestoreTarget {
    fun write(bytes: ByteArray, length: Int)

    /** Makes the photo appear in the gallery. */
    fun commit()

    /** Removes the half-written photo. */
    fun abort()
}

data class RestoreResult(val restored: Int, val failed: Int, val failure: BackupFailure? = null)

/**
 * Brings backed-up photos back to this phone (a new phone, or photos deleted here). Each download is checked
 * against the SHA-256 saved at backup time before it appears in the gallery. Nothing on the drive changes.
 */
class RestoreEngine(
    private val drive: DriveFileSystem,
    userId: String,
    private val classify: (Throwable) -> BackupFailure = { BackupFailure(FailureReason.OTHER, it.message) },
    private val vault: PrivateVault? = null,
) {
    private val catalog = BackupCatalog(drive, userId, vault)

    /**
     * Backed-up photos that aren't on this phone: neither the original nor an earlier restored copy. A restored
     * copy is matched by name and size only, because Android may set its date from the photo's own details.
     */
    suspend fun missing(onPhone: List<MediaItem>): List<BackupRecord> {
        val keys = onPhone.map(BackupSelection::key).toSet()
        val nameAndSize = onPhone.map { "${it.displayName}|${it.sizeBytes}" }.toSet()
        return catalog.records()
            .filter { it.key !in keys && "${it.fileName}|${it.sizeBytes}" !in nameAndSize }
            .sortedByDescending { it.takenAtMillis }
    }

    suspend fun restore(records: List<BackupRecord>, writer: MediaWriter, onProgress: (Int, Int) -> Unit = { _, _ -> }): RestoreResult {
        var restored = 0
        var failed = 0
        try {
            for ((i, record) in records.withIndex()) {
                currentCoroutineContext().ensureActive()
                onProgress(i, records.size)
                val target = writer.begin(record)
                val hasher = newSha256()
                try {
                    val plain: (ByteArray, Int) -> Unit = { bytes, n ->
                        hasher.update(bytes, 0, n)
                        target.write(bytes, n)
                    }
                    if (record.encrypted) {
                        val v = vault ?: throw IllegalStateException("No private key on this phone")
                        v.decrypt(ObjectKind.MEDIA, PrivatePaths.idOf(record.drivePath), { sink -> drive.readStream(record.drivePath, sink) }, plain)
                    } else {
                        drive.readStream(record.drivePath, plain)
                    }
                } catch (_: ObjectAuthenticationFailed) {
                    target.abort()
                    failed++
                    continue
                } catch (e: Throwable) {
                    target.abort()
                    throw e
                }
                if (hasher.hex() == record.sha256) {
                    target.commit()
                    restored++
                } else {
                    target.abort()
                    failed++
                }
            }
            onProgress(records.size, records.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return RestoreResult(restored, failed, classify(e))
        }
        return RestoreResult(restored, failed, if (failed > 0) BackupFailure(FailureReason.CHECK_FAILED) else null)
    }
}
