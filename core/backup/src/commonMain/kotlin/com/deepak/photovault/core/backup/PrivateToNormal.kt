package com.deepak.photovault.core.backup

import com.deepak.photovault.core.drive.ByteSource
import com.deepak.photovault.core.drive.DriveFileSystem
import com.deepak.photovault.core.drive.DriveNotFound
import com.deepak.photovault.core.drive.DrivePaths
import com.deepak.photovault.core.household.HouseholdStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A place on the phone to hold one decrypted file for a moment (the vault hands plain bytes out as it checks them,
 * the drive wants to pull them): written once, then read once.
 */
interface Spool : AutoCloseable {
    fun write(bytes: ByteArray, length: Int)

    /** The bytes written so far, from the start. Call once, after the last [write]. */
    fun source(): ByteSource
}

/** A [Spool] in memory, for tests and small files. */
class MemorySpool : Spool {
    private val chunks = ArrayList<ByteArray>()
    override fun write(bytes: ByteArray, length: Int) {
        chunks += bytes.copyOf(length)
    }

    override fun source(): ByteSource {
        val all = ByteArray(chunks.sumOf { it.size })
        var at = 0
        chunks.forEach { it.copyInto(all, at); at += it.size }
        return com.deepak.photovault.core.drive.BytesSource(all)
    }
    override fun close() = Unit
}

/**
 * Private backups made into normal pictures (the last of Phase 10A, 16 Nov), for someone who switched from Private
 * to Normal and chose "Make them normal".
 *
 * One file at a time, with the same care as a backup: the private file is decrypted and checked against the record
 * (size and SHA-256), written to the drive under a temporary name, read back and checked again, and only then given
 * its real name in the person's albums (users/<id>/albums/<album>/<year>/<month>/<name>), with its preview. A readable
 * list is written that adds the normal file and takes the private one off; only after that is the private file
 * deleted. A file that fails any check stays private and is counted. Stopping part-way loses nothing: what was done
 * is done, the rest is found again next time, and a picture published just before a stop is recognised, not doubled.
 * Private files in Recently deleted are left as they are.
 *
 * Leftovers of a stop (A7, 17 Nov): each run first deletes this phone's own temporary files on the drive, and the
 * private files that its last list already took off but a stop kept it from deleting.
 */
@OptIn(ExperimentalUuidApi::class)
class PrivateToNormal(
    private val drive: DriveFileSystem,
    userId: String,
    private val deviceId: String,
    private val vault: PrivateVault,
    private val now: () -> Long,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val newSpool: () -> Spool = { MemorySpool() },
) {
    private val userFolder = HouseholdStore.userFolder(userId)
    private val incoming = DrivePaths.join(userFolder, BackupEngine.INCOMING)
    private val catalog = BackupCatalog(drive, userId, vault)

    data class Result(val converted: Int, val failed: Int, val left: Int) {
        /** Nothing private is left to do (failed ones are tried again next time). */
        val finished: Boolean get() = left == 0 && failed == 0
    }

    /** The person's private backups that are still private. */
    suspend fun waiting(): List<BackupRecord> = catalog.records().filter(::isWaiting)

    private fun isWaiting(r: BackupRecord) = r.encrypted && r.drivePath.startsWith("$userFolder/") && PrivatePaths.isObject(r.drivePath)

    /**
     * Converts up to [limit] files, newest first, while [keepGoing] says so. Throws only for cancellation or when the
     * drive itself fails (what was finished before that is already saved).
     */
    suspend fun run(limit: Int = Int.MAX_VALUE, keepGoing: () -> Boolean = { true }, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Result {
        val records = catalog.records()
        DrivePublish.clearOwnTemporaryFiles(drive, incoming, deviceId)
        clearLeftovers(records.mapTo(HashSet()) { it.drivePath })
        val all = records.filter(::isWaiting).sortedByDescending { it.takenAtMillis }
        var converted = 0
        var failed = 0
        val batch = ArrayList<Pair<BackupRecord, BackupRecord>>()
        try {
            for (record in all) {
                if (converted + failed >= limit || !keepGoing()) break
                val normal = convert(record)
                if (normal == null) failed++ else {
                    batch += record to normal
                    converted++
                }
                if (batch.size >= BATCH) settle(batch)
                onProgress(converted + failed, all.size)
            }
        } finally {
            withContext(NonCancellable) { settle(batch) }
        }
        return Result(converted, failed, all.size - converted - failed)
    }

    /** The normal record once the picture is in the albums and checked; null when this one must stay private. */
    private suspend fun convert(record: BackupRecord): BackupRecord? {
        val temp = DrivePaths.join(incoming, "$deviceId-${Uuid.random()}${BackupEngine.TEMP_SUFFIX}")
        val spool = newSpool()
        try {
            val hasher = newSha256()
            var size = 0L
            try {
                vault.decryptFile(drive, record.drivePath) { b, n ->
                    spool.write(b, n)
                    hasher.update(b, 0, n)
                    size += n
                }
            } catch (_: ObjectAuthenticationFailed) {
                return null
            } catch (_: DriveNotFound) {
                return null
            }
            if (hasher.hex() != record.sha256 || size != record.sizeBytes) return null
            drive.mkdirs(incoming)
            check(drive.writeNew(temp, spool.source())) { "Temporary name already in use" }
            val (remote, remoteSize) = DrivePublish.checksum(drive, temp)
            if (remote != record.sha256 || remoteSize != record.sizeBytes) {
                drive.delete(temp)
                return null
            }
            val date = Instant.fromEpochMilliseconds(record.takenAtMillis).toLocalDateTime(zone).date
            val folder = DrivePaths.join(
                userFolder, BackupEngine.ALBUMS, BackupEngine.albumFolder(record.album.orEmpty()),
                date.year.toString(), date.monthNumber.toString().padStart(2, '0'),
            )
            drive.mkdirs(folder)
            val path = DrivePublish.publish(drive, temp, folder, BackupEngine.safeName(record.originalName), record.sha256, record.sizeBytes)
            copyPreview(record.drivePath, path)
            return record.copy(drivePath = path, encrypted = false)
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { drive.delete(temp) } }
            throw e
        } finally {
            runCatching { spool.close() }
        }
    }

    /** Best effort: the private preview, decrypted, becomes the normal picture's preview. */
    private suspend fun copyPreview(privatePath: String, normalPath: String) {
        val from = PrivatePaths.thumbFor(privatePath) ?: return
        val to = NormalThumbs.pathFor(normalPath) ?: return
        try {
            var bytes = ByteArray(0)
            // The preview is stored under the photo's own ID (its file ends .pxt, not .pxo).
            vault.decrypt(ObjectKind.THUMB, PrivatePaths.idOf(privatePath), { sink -> drive.readStream(from, sink) }) { b, n -> bytes += b.copyOf(n) }
            if (bytes.isEmpty()) return
            drive.mkdirs(DrivePaths.parent(to))
            drive.createNew(to, bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Writes the list first (normal added, private taken off), then deletes the private files and their previews. */
    private suspend fun settle(batch: MutableList<Pair<BackupRecord, BackupRecord>>) {
        if (batch.isEmpty()) return
        val folder = DrivePaths.join(userFolder, BackupEngine.MANIFESTS, deviceId)
        drive.mkdirs(folder)
        var at = now()
        val manifest = Manifest(deviceId = deviceId, createdAtMillis = at, records = batch.map { it.second }, removed = batch.map { it.first.drivePath })
        // Readable (not sealed): these are normal backups now, and any of the person's phones must be able to read it.
        val bytes = BackupEngine.encodeManifest(null, manifest)
        while (!drive.createNew(DrivePaths.join(folder, BackupEngine.manifestName(at)), bytes)) at++
        for ((old, _) in batch) {
            runCatching { drive.delete(old.drivePath) }
            PrivatePaths.thumbFor(old.drivePath)?.let { runCatching { drive.delete(it) } }
        }
        batch.clear()
    }

    /**
     * A stop inside [settle] after the list was written (the phone switched off, 17 Nov) leaves private files that no
     * list points to any more. This phone's newest conversion list is found again, and each private file it took off
     * is deleted, but only when its normal copy is still listed and the private one is not.
     */
    private suspend fun clearLeftovers(current: Set<String>) {
        val folder = DrivePaths.join(userFolder, BackupEngine.MANIFESTS, deviceId)
        val names = try {
            drive.list(folder).filter { !it.isFolder && it.name.endsWith(".json") }.map { it.name }.sortedDescending().take(LOOK_BACK)
        } catch (_: DriveNotFound) {
            return
        }
        for (name in names) {
            val manifest = try {
                BackupEngine.json.decodeFromString(Manifest.serializer(), drive.read(DrivePaths.join(folder, name)).decodeToString())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            if (!isConversion(manifest)) continue
            for ((locked, normal) in manifest.removed.zip(manifest.records)) {
                if (locked in current || normal.drivePath !in current) continue
                deleteQuietly(locked)
                PrivatePaths.thumbFor(locked)?.let { deleteQuietly(it) }
            }
            return
        }
    }

    /** A list written by [settle]: each normal picture paired with the private file it replaced. */
    private fun isConversion(m: Manifest) = m.removed.isNotEmpty() && m.removed.size == m.records.size &&
        m.records.none { it.encrypted } && m.removed.all { it.startsWith("$userFolder/") && PrivatePaths.isObject(it) }

    private suspend fun deleteQuietly(path: String) {
        try {
            drive.delete(path)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Already gone, or the drive is busy: tried again next run.
        }
    }

    private companion object {
        const val BATCH = 20

        /** How many of this phone's newest lists are read to find its last conversion list. */
        const val LOOK_BACK = 12
    }
}

/** Giving a checked temporary file its real name on the drive (shared by backup and [PrivateToNormal]). */
internal object DrivePublish {
    private const val MAX_NAME_TRIES = 200

    /** Leftovers from an upload that was cut off: this phone's only, since another phone may be uploading now. */
    suspend fun clearOwnTemporaryFiles(drive: DriveFileSystem, incoming: String, deviceId: String) {
        val leftovers = try {
            drive.list(incoming).filter { !it.isFolder && it.name.startsWith("$deviceId-") && it.name.endsWith(BackupEngine.TEMP_SUFFIX) }
        } catch (_: DriveNotFound) {
            return
        }
        for (entry in leftovers) {
            try {
                drive.delete(DrivePaths.join(incoming, entry.name))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Moves [temp] into [folder] as [name], or "name (2)" and so on when taken by a different file. When the same
     * file is already there (same size and SHA-256) the temporary one is deleted and that path is returned.
     */
    suspend fun publish(drive: DriveFileSystem, temp: String, folder: String, name: String, sha256: String, size: Long): String {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val ext = name.substring(dot)
        for (n in 1..MAX_NAME_TRIES) {
            val candidate = DrivePaths.join(folder, if (n == 1) name else "$base ($n)$ext")
            if (drive.rename(temp, candidate)) return candidate
            if (isSameFile(drive, candidate, sha256, size)) {
                // Backed up before (by this phone before a reinstall, or another phone of the same person).
                drive.delete(temp)
                return candidate
            }
        }
        error("Too many files with the name $name")
    }

    suspend fun isSameFile(drive: DriveFileSystem, path: String, sha256: String, size: Long): Boolean {
        val entry = drive.list(DrivePaths.parent(path)).firstOrNull { it.name == path.substringAfterLast('/') } ?: return false
        if (entry.isFolder || entry.size != size) return false
        return checksum(drive, path).first == sha256
    }

    suspend fun checksum(drive: DriveFileSystem, path: String): Pair<String, Long> {
        val hasher = newSha256()
        var size = 0L
        drive.readStream(path) { buffer, n ->
            hasher.update(buffer, 0, n)
            size += n
        }
        return hasher.hex() to size
    }
}
