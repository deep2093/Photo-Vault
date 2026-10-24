package com.deepak.photovault.core.backup

import com.deepak.photovault.core.drive.ByteSource
import com.deepak.photovault.core.drive.DriveFileSystem
import com.deepak.photovault.core.drive.DriveNotFound
import com.deepak.photovault.core.drive.DrivePaths
import com.deepak.photovault.core.household.HouseholdStore
import com.deepak.photovault.core.media.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/**
 * Backs up one person's phone photos into their own folder on the drive (docs/BACKUP_ENGINE.md).
 *
 * For every photo:
 * 1. Stream it to a private temporary file in users/<id>/.incoming, working out its SHA-256 on the way.
 * 2. Read the temporary file back from the drive and work out its SHA-256 again.
 * 3. Only if both match, publish it with a rename that never replaces anything (albums/<album>/YYYY/MM/name).
 *    If the name is taken by the very same file it counts as already there; a different file gets "name (2)".
 * 4. Only then is it recorded as backed up: in this phone's index and in a manifest file on the drive.
 *
 * Nothing is ever deleted from the phone here, and the only things deleted on the drive are this phone's own
 * temporary files.
 */
class BackupEngine(
    private val drive: DriveFileSystem,
    private val userId: String,
    private val deviceId: String,
    private val reader: MediaReader,
    private val index: BackupIndex,
    private val now: () -> Long,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    /** Turns a platform error (SMB) into a reason a person can act on. */
    private val classify: (Throwable) -> BackupFailure = { BackupFailure(FailureReason.OTHER, it.message) },
) {
    private val userFolder = HouseholdStore.userFolder(userId)
    private val incoming = DrivePaths.join(userFolder, INCOMING)

    /**
     * Backs up whichever of [items] aren't backed up yet, newest first, staying within [limitBytes] of space.
     * Never throws except for cancellation (after saving what was finished); problems end up in the result.
     */
    suspend fun run(
        items: List<MediaItem>,
        settings: BackupSettings,
        limitBytes: Long,
        trigger: BackupTrigger,
        onProgress: (BackupProgress) -> Unit = {},
    ): BackupRun {
        val started = now()
        var uploaded = 0
        var failed = 0
        var bytes = 0L
        var already = 0
        var processed = 0
        var failure: BackupFailure? = null
        val pending = mutableListOf<BackupRecord>()
        try {
            syncIndexIfEmpty()
            val done = index.keys()
            val todo = items.filter { BackupSelection.key(it) !in done }.sortedByDescending { it.takenAtMillis }
            already = items.size - todo.size
            onProgress(BackupProgress(todo.size, 0, 0, 0, 0))
            if (todo.isNotEmpty()) {
                drive.mkdirs(incoming)
                cleanUpOwnTemporaryFiles()
                var used = drive.folderSize(userFolder)
                var free = drive.space().freeBytes
                for (item in todo) {
                    currentCoroutineContext().ensureActive()
                    onProgress(BackupProgress(todo.size, processed, uploaded, failed, bytes, item.displayName))
                    val opened = try {
                        reader.open(item, settings.storageSaver && !item.isVideo)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Deleted or moved on the phone since the list was made: nothing to back up.
                        processed++
                        continue
                    }
                    val outcome = opened.use { prepared ->
                        when {
                            used + prepared.sizeBytes > limitBytes -> Outcome.Stop(FailureReason.SPACE_LIMIT_FULL)
                            free - prepared.sizeBytes < DRIVE_MARGIN_BYTES -> Outcome.Stop(FailureReason.DRIVE_FULL)
                            else -> backUpOne(item, prepared)
                        }
                    }
                    if (outcome !is Outcome.Stop) processed++
                    when (outcome) {
                        is Outcome.Saved -> {
                            index.add(listOf(outcome.record))
                            pending += outcome.record
                            uploaded++
                            bytes += outcome.record.sizeBytes
                            used += outcome.record.sizeBytes
                            free -= outcome.record.sizeBytes
                            if (pending.size >= MANIFEST_BATCH) flush(pending)
                        }
                        Outcome.Mismatch -> failed++
                        is Outcome.Stop -> {
                            failure = BackupFailure(outcome.reason)
                            break
                        }
                    }
                }
                onProgress(BackupProgress(todo.size, processed, uploaded, failed, bytes))
            }
            if (failure == null && failed > 0) failure = BackupFailure(FailureReason.CHECK_FAILED)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { flush(pending) } }
            throw e
        } catch (e: Throwable) {
            failure = classify(e)
        } finally {
            withContext(NonCancellable) { runCatching { flush(pending) } }
        }
        return BackupRun(
            trigger = trigger,
            startedAtMillis = started,
            finishedAtMillis = now(),
            eligible = items.size,
            alreadyBackedUp = already,
            uploaded = uploaded,
            failedItems = failed,
            bytes = bytes,
            failure = failure?.reason,
            failureDetail = failure?.detail,
        )
    }

    private sealed interface Outcome {
        data class Saved(val record: BackupRecord) : Outcome
        data object Mismatch : Outcome
        data class Stop(val reason: FailureReason) : Outcome
    }

    private suspend fun backUpOne(item: MediaItem, prepared: PreparedFile): Outcome {
        val temp = DrivePaths.join(incoming, "$deviceId-${Uuid.random()}$TEMP_SUFFIX")
        val hasher = newSha256()
        var size = 0L
        val hashing = object : ByteSource {
            override fun read(buffer: ByteArray): Int {
                val n = prepared.source.read(buffer)
                if (n > 0) {
                    hasher.update(buffer, 0, n)
                    size += n
                }
                return n
            }

            override fun close() = Unit
        }
        try {
            check(drive.writeNew(temp, hashing)) { "Temporary name already in use" }
            val local = hasher.hex()
            val (remote, remoteSize) = checksumOnDrive(temp)
            if (remote != local || remoteSize != size) {
                drive.delete(temp)
                return Outcome.Mismatch
            }
            val folder = folderFor(item)
            drive.mkdirs(folder)
            val path = publish(temp, folder, safeName(prepared.name), local, size)
            val record = BackupRecord(
                key = BackupSelection.key(item),
                drivePath = path,
                sha256 = local,
                sizeBytes = size,
                originalName = item.displayName,
                takenAtMillis = item.takenAtMillis,
                mimeType = prepared.mimeType,
                isVideo = item.isVideo,
                savedAtMillis = now(),
                deviceId = deviceId,
                storageSaver = prepared.compressed,
                album = item.albumName.ifBlank { null },
            )
            return Outcome.Saved(record)
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { drive.delete(temp) } }
            throw e
        }
    }

    /** Renames [temp] to [name] in [folder] without replacing; "name (2).jpg" and so on when taken. */
    private suspend fun publish(temp: String, folder: String, name: String, sha256: String, size: Long): String {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val ext = name.substring(dot)
        for (n in 1..MAX_NAME_TRIES) {
            val candidate = DrivePaths.join(folder, if (n == 1) name else "$base ($n)$ext")
            if (drive.rename(temp, candidate)) return candidate
            if (isSameFile(candidate, sha256, size)) {
                // Backed up before (by this phone before a reinstall, or another phone of the same person).
                drive.delete(temp)
                return candidate
            }
        }
        error("Too many files with the name $name")
    }

    private suspend fun isSameFile(path: String, sha256: String, size: Long): Boolean {
        val entry = drive.list(DrivePaths.parent(path)).firstOrNull { it.name == path.substringAfterLast('/') } ?: return false
        if (entry.isFolder || entry.size != size) return false
        return checksumOnDrive(path).first == sha256
    }

    private suspend fun checksumOnDrive(path: String): Pair<String, Long> {
        val hasher = newSha256()
        var size = 0L
        drive.readStream(path) { buffer, n ->
            hasher.update(buffer, 0, n)
            size += n
        }
        return hasher.hex() to size
    }

    /** users/<id>/albums/<phone album>/YYYY/MM (24 Oct: the drive mirrors the phone's albums). */
    private fun folderFor(item: MediaItem): String {
        val date = Instant.fromEpochMilliseconds(item.takenAtMillis).toLocalDateTime(zone).date
        return DrivePaths.join(userFolder, ALBUMS, albumFolder(item.albumName), date.year.toString(), date.monthNumber.toString().padStart(2, '0'))
    }

    /** Leftovers from a backup that was cut off (this phone's only; another phone may be uploading now). */
    private suspend fun cleanUpOwnTemporaryFiles() {
        drive.list(incoming).filter { !it.isFolder && it.name.startsWith("$deviceId-") && it.name.endsWith(TEMP_SUFFIX) }
            .forEach { runCatching { drive.delete(DrivePaths.join(incoming, it.name)) } }
    }

    /** Saves finished records as a new manifest file on the drive. */
    private suspend fun flush(pending: MutableList<BackupRecord>) {
        if (pending.isEmpty()) return
        val manifest = Manifest(deviceId = deviceId, createdAtMillis = now(), records = pending.toList())
        val folder = DrivePaths.join(userFolder, MANIFESTS, deviceId)
        drive.mkdirs(folder)
        val bytes = json.encodeToString(Manifest.serializer(), manifest).encodeToByteArray()
        while (!drive.createNew(DrivePaths.join(folder, manifestName(manifest.createdAtMillis)), bytes)) Unit
        pending.clear()
    }

    /** A new phone, or the app reinstalled: learn what is already on the drive before uploading anything. */
    private suspend fun syncIndexIfEmpty() {
        if (index.keys().isNotEmpty()) return
        val records = BackupCatalog(drive, userId).records()
        if (records.isNotEmpty()) index.add(records)
    }

    companion object {
        const val INCOMING = ".incoming"
        const val MANIFESTS = "manifests"

        /** Each phone album gets its own folder under this one (24 Oct). */
        const val ALBUMS = "albums"

        /** A phone album's name made safe as a folder name on any drive. */
        fun albumFolder(name: String): String {
            val clean = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.').trim().take(60)
            return clean.ifEmpty { "No album" }
        }
        const val TEMP_SUFFIX = ".part"
        /** Stop before the drive is completely full, so the router and other phones still work. */
        const val DRIVE_MARGIN_BYTES = 1_000_000_000L
        const val MANIFEST_BATCH = 25
        private const val MAX_NAME_TRIES = 200

        internal val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun manifestName(millis: Long) = "m-${millis.toString().padStart(14, '0')}-${Uuid.random().toString().take(8)}.json"

        fun manifestTime(name: String): Long? = Regex("""m-(\d{14})-[0-9a-f]{8}\.json""").matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()

        /** Characters that SMB drives refuse, replaced; empty names get a stand-in. */
        fun safeName(name: String): String {
            val cleaned = name.map { c -> if (c < ' ' || c in "\\/:*?\"<>|") '_' else c }.joinToString("").trim().trimEnd('.')
            val limited = if (cleaned.length > 120) cleaned.take(100) + cleaned.takeLast(20) else cleaned
            return limited.ifEmpty { "photo" }.let { if (it.startsWith('.')) "_$it" else it }
        }
    }
}

/** Reads what a person has on the drive: every manifest from every one of their phones. */
class BackupCatalog(private val drive: DriveFileSystem, userId: String) {
    private val userFolder = HouseholdStore.userFolder(userId)
    private val manifests = DrivePaths.join(userFolder, BackupEngine.MANIFESTS)

    suspend fun records(): List<BackupRecord> {
        // Every phone's manifests in time order (their names start with the time), so a deletion only hides
        // what was saved before it, and a photo backed up again afterwards shows again.
        val files = drive.list(manifests).filter { it.isFolder }.flatMap { device ->
            val folder = DrivePaths.join(manifests, device.name)
            drive.list(folder).filter { !it.isFolder && it.name.endsWith(".json") }.map { DrivePaths.join(folder, it.name) }
        }.sortedBy { it.substringAfterLast('/') }
        val out = LinkedHashMap<String, BackupRecord>()
        for (path in files) {
            val manifest = try {
                BackupEngine.json.decodeFromString(Manifest.serializer(), drive.read(path).decodeToString())
            } catch (_: DriveNotFound) {
                continue
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue // A damaged manifest never stops a backup.
            }
            manifest.records.forEach { out[it.drivePath] = it }
            manifest.removed.forEach { out.remove(it) }
        }
        return out.values.toList()
    }

    /**
     * Deletes backups from the drive (23 Oct, Settings > Storage, after a warning). Only files inside this
     * person's own folder. The deletion is written down first, then the files go, so the list never shows a
     * file that is gone. The phone itself is never touched.
     */
    suspend fun remove(records: List<BackupRecord>, deviceId: String, now: Long) {
        if (records.isEmpty()) return
        val own = records.filter { it.drivePath.startsWith("$userFolder/") && !it.drivePath.contains("..") }
        require(own.size == records.size) { "Only your own backups can be deleted" }
        val folder = DrivePaths.join(manifests, deviceId)
        drive.mkdirs(folder)
        val note = Manifest(deviceId = deviceId, createdAtMillis = now, records = emptyList(), removed = own.map { it.drivePath })
        val bytes = BackupEngine.json.encodeToString(Manifest.serializer(), note).encodeToByteArray()
        while (!drive.createNew(DrivePaths.join(folder, BackupEngine.manifestName(now)), bytes)) Unit
        for (r in own) {
            try {
                drive.delete(r.drivePath)
            } catch (_: DriveNotFound) {
                // Already gone.
            }
        }
    }

    /** When each of the person's phones last saved something: deviceId to time. */
    suspend fun lastBackups(): Map<String, Long> =
        drive.list(manifests).filter { it.isFolder }.mapNotNull { device ->
            val latest = drive.list(DrivePaths.join(manifests, device.name)).mapNotNull { BackupEngine.manifestTime(it.name) }.maxOrNull()
            latest?.let { device.name to it }
        }.toMap()
}
